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
        added
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
            while (c.moveToNext() && out.size < 150) {
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
        val meter = p.meter ?: "UNKNOWN"
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
                if (SmsScanner.save(dao, parsed)) {
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
