package com.mytoken.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mytoken.app.R
import com.mytoken.app.data.AppDb
import com.mytoken.app.data.Prefs
import com.mytoken.app.data.display
import com.mytoken.app.logic.Insights
import com.mytoken.app.ui.MainActivity
import com.mytoken.app.widget.DaysWidget
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CHANNEL = "mytoken"

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "MyToken alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val found = try { com.mytoken.app.sms.SmsScanner.scan(ctx) } catch (e: Exception) { 0 }
        if (found > 0) Notifier.post(ctx, 1003, "New KPLC message(s) saved", "$found new record(s) added to MyToken")

        val dao = AppDb.get(ctx).dao()
        val meters = dao.metersNow().filter { it.tracked }
        val all = dao.purchasesNow()
        val allReadings = dao.readingsNow()
        val threshold = Prefs.threshold(ctx)
        val now = System.currentTimeMillis()
        for (m in meters) {
            val ins = Insights.compute(m, all.filter { it.meterNumber == m.number }, allReadings.filter { it.meterNumber == m.number })
            val d = ins.daysLeft
            if (d != null && d <= threshold && now - Prefs.lastLow(ctx, m.number) > 20L * 3_600_000L) {
                Prefs.setLastLow(ctx, m.number, now)
                Notifier.post(
                    ctx, m.number.hashCode(),
                    "Low tokens: ${m.display()}",
                    "About %.1f days of units left. Time to buy.".format(d)
                )
            }
        }
        DaysWidget.refresh(ctx)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("reminder", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}
