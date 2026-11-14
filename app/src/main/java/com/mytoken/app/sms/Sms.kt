package com.mytoken.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.mytoken.app.data.AppDao
import com.mytoken.app.data.AppDb
import com.mytoken.app.data.Meter
import com.mytoken.app.data.Purchase
import com.mytoken.app.parser.Fields
import com.mytoken.app.parser.Kind
import com.mytoken.app.parser.KplcParser
import com.mytoken.app.parser.ParsedToken
import com.mytoken.app.widget.DaysWidget
import com.mytoken.app.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReviewItem(val address: String, val date: Long, val body: String, val kind: String?, val fields: Fields)

object SmsScanner {
    /** Reads the whole SMS inbox and stores every KPLC token message and KPLC payment found. Returns NEW records. */
    suspend fun scan(ctx: Context): Int = withContext(Dispatchers.IO) {
        val dao = AppDb.get(ctx).dao()
        var added = 0
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("body", "date"),
            null, null, "date DESC"
        ) ?: return@withContext 0
        cursor.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(0) ?: continue
                val parsed = KplcParser.parse(body, c.getLong(1)) ?: continue
                if (save(dao, parsed)) added++
            }
        }
        reconcile(dao)
        added
    }

    /**
     * Token messages from some meters carry no amount. When a payment confirmation (M-Pesa, bank)
     * for the same meter arrived within 24h, the token takes that payment's amount.
     */
    suspend fun reconcile(dao: AppDao) {
        val all = dao.purchasesNow()
        val payments = all.filter { it.kind == "PAYMENT" && it.amount > 0 }.toMutableList()
        if (payments.isEmpty()) return
        val day = 86_400_000L
        val tokens = all.filter { it.kind != "PAYMENT" && it.amount <= 0 }
        // Payments whose amount already matches a token are taken.
        all.filter { it.kind != "PAYMENT" && it.amount > 0 }.forEach { t ->
            payments.firstOrNull { kotlin.math.abs(it.amount - t.amount) <= 1.0 && kotlin.math.abs(it.dateMillis - t.dateMillis) <= day }
                ?.let { payments.remove(it) }
        }
        for (t in tokens.sortedBy { it.dateMillis }) {
            val match = payments
                .filter { it.meterNumber == t.meterNumber || it.meterNumber == "UNKNOWN" || t.meterNumber == "UNKNOWN" }
                .filter { kotlin.math.abs(it.dateMillis - t.dateMillis) <= day }
                .minByOrNull { kotlin.math.abs(it.dateMillis - t.dateMillis) } ?: continue
            dao.updatePurchase(t.copy(amount = match.amount))
            payments.remove(match)
        }
    }

    /** Lists the messages that look KPLC-related and how the app read each one. */
    suspend fun review(ctx: Context): List<ReviewItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ReviewItem>()
        val cursor = ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("body", "date", "address"),
            null, null, "date DESC"
        ) ?: return@withContext out
        cursor.use { c ->
            while (c.moveToNext() && out.size < 400) {
                val body = c.getString(0) ?: continue
                if (!KplcParser.looksRelevant(body)) continue
                val date = c.getLong(1)
                val parsed = KplcParser.parse(body, date)
                out.add(ReviewItem(c.getString(2) ?: "?", date, body, parsed?.kind?.name, KplcParser.extract(body)))
            }
        }
        out
    }

    suspend fun save(dao: AppDao, p: ParsedToken): Boolean {
        val meter: String = p.meter ?: run {
            val real = dao.metersNow().filter { it.number != "UNKNOWN" }
            val tracked = real.filter { it.tracked }
            when {
                tracked.size == 1 -> tracked[0].number
                real.size == 1 -> real[0].number
                else -> "UNKNOWN"
            }
        }
        dao.insertMeter(Meter(number = meter))
        val id = dao.insertPurchase(
            Purchase(
                meterNumber = meter,
                token = p.token,
                units = p.units ?: 0.0,
                amount = p.amount ?: 0.0,
                dateMillis = p.dateMillis,
                kind = p.kind.name
            )
        )
        if (id == -1L) {
            // Already stored: a later, fuller read of the same message may fill in what was missed before.
            val old = dao.purchaseByToken(p.token)
            if (old != null) {
                val amt = if (old.amount <= 0.0 && (p.amount ?: 0.0) > 0.0) p.amount!! else old.amount
                val un = if (old.units <= 0.0 && (p.units ?: 0.0) > 0.0) p.units!! else old.units
                if (amt != old.amount || un != old.units) dao.updatePurchase(old.copy(amount = amt, units = un))
            }
        }
        return id != -1L
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val ts = msgs.first().timestampMillis
        val parsed = KplcParser.parse(body, ts) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(context).dao()
                val isNew = SmsScanner.save(dao, parsed)
                SmsScanner.reconcile(dao)
                if (isNew) {
                    if (parsed.kind == Kind.TOKEN) {
                        val units = parsed.units?.let { " (%.1f units)".format(it) } ?: ""
                        Notifier.post(context, 1001, "New KPLC token saved", "Meter ${parsed.meter ?: "unknown"}$units")
                    } else {
                        Notifier.post(context, 1002, "KPLC payment noted", "KES %.0f paid - waiting for the token message".format(parsed.amount ?: 0.0))
                    }
                    DaysWidget.refresh(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
