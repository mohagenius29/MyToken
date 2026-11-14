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
import com.mytoken.app.parser.KplcParser
import com.mytoken.app.parser.ParsedToken
import com.mytoken.app.work.Notifier
import com.mytoken.app.widget.DaysWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SmsScanner {
    /** Reads the SMS inbox and stores every KPLC token message found. Returns the number of NEW tokens. */
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

    suspend fun save(dao: AppDao, p: ParsedToken): Boolean {
        val meter = p.meter ?: "UNKNOWN"
        dao.insertMeter(Meter(number = meter))
        val id = dao.insertPurchase(
            Purchase(
                meterNumber = meter,
                token = p.token,
                units = p.units ?: 0.0,
                amount = p.amount ?: 0.0,
                dateMillis = p.dateMillis
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
                    val units = parsed.units?.let { " (%.1f units)".format(it) } ?: ""
                    Notifier.post(context, 1001, "New KPLC token saved", "Meter ${parsed.meter ?: "unknown"}$units")
                    DaysWidget.refresh(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
