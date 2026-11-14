package com.mytoken.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mytoken.app.R
import com.mytoken.app.data.AppDb
import com.mytoken.app.data.Prefs
import com.mytoken.app.data.display
import com.mytoken.app.logic.Insights
import com.mytoken.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DaysWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refresh(context, goAsync())
    }

    companion object {
        fun refresh(ctx: Context, pending: BroadcastReceiver.PendingResult? = null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val mgr = AppWidgetManager.getInstance(ctx)
                    val ids = mgr.getAppWidgetIds(ComponentName(ctx, DaysWidget::class.java))
                    if (ids.isEmpty()) return@launch
                    val dao = AppDb.get(ctx).dao()
                    val sel = Prefs.selectedMeter(ctx)
                    val tracked = dao.metersNow().filter { it.tracked }
                    val meter = tracked.firstOrNull { it.number == sel } ?: tracked.firstOrNull()
                    val all = dao.purchasesNow()
                    val allReadings = dao.readingsNow()
                    val ins = meter?.let { m -> Insights.compute(m, all.filter { it.meterNumber == m.number }, allReadings.filter { it.meterNumber == m.number }) }

                    val rv = RemoteViews(ctx.packageName, R.layout.widget_days)
                    rv.setTextViewText(R.id.w_days, ins?.daysLeft?.let { "%.0f".format(it) } ?: "--")
                    rv.setTextViewText(R.id.w_sub, if (meter == null) "Open app to set up" else "days left - ${meter.display()}")
                    rv.setTextViewText(
                        R.id.w_run,
                        if (ins?.daysRunning != null) "Ran %.1f d".format(ins.daysRunning) +
                            (ins.totalDays?.let { " - total %.1f d".format(it) } ?: "") else ""
                    )
                    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    rv.setOnClickPendingIntent(R.id.w_root, pi)
                    ids.forEach { mgr.updateAppWidget(it, rv) }
                } finally {
                    pending?.finish()
                }
            }
        }
    }
}
