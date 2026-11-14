package com.mytoken.app.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.mytoken.app.data.AppDao
import com.mytoken.app.data.Meter
import com.mytoken.app.data.Purchase
import com.mytoken.app.data.display
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }
    private fun date(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun csv(ctx: Context, meters: List<Meter>, purchases: List<Purchase>): File {
        val labels = meters.associate { it.number to it.display() }
        val sb = StringBuilder("Date,Meter,Label,Token,Units,Amount (KES),KES per unit,Used\n")
        purchases.sortedByDescending { it.dateMillis }.forEach { p ->
            val per = if (p.units > 0) "%.2f".format(Locale.US, p.amount / p.units) else ""
            sb.append(date(p.dateMillis)).append(',')
                .append(q(p.meterNumber)).append(',')
                .append(q(labels[p.meterNumber] ?: "")).append(',')
                .append(q(p.token)).append(',')
                .append("%.2f".format(Locale.US, p.units)).append(',')
                .append("%.2f".format(Locale.US, p.amount)).append(',')
                .append(per).append(',')
                .append(if (p.used) "yes" else "no").append('\n')
        }
        return File(dir(ctx), "mytoken-history.csv").apply { writeText(sb.toString()) }
    }

    fun pdf(ctx: Context, meters: List<Meter>, purchases: List<Purchase>): File {
        val labels = meters.associate { it.number to it.display() }
        val doc = PdfDocument()
        val text = Paint().apply { textSize = 10f }
        val bold = Paint().apply { textSize = 15f; isFakeBoldText = true }
        val head = Paint().apply { textSize = 10f; isFakeBoldText = true }
        val rows = purchases.sortedByDescending { it.dateMillis }
        val chunks = if (rows.isEmpty()) listOf(emptyList<Purchase>()) else rows.chunked(42)
        chunks.forEachIndexed { i, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            val c = page.canvas
            c.drawText("MyToken - KPLC token history", 30f, 40f, bold)
            var y = 70f
            val xs = floatArrayOf(30f, 100f, 220f, 300f, 370f, 450f)
            listOf("Date", "Meter", "Units", "Amount", "KES/unit", "Used").forEachIndexed { k, h -> c.drawText(h, xs[k], y, head) }
            y += 18f
            chunk.forEach { p ->
                val per = if (p.units > 0) "%.2f".format(Locale.US, p.amount / p.units) else "-"
                val name = (labels[p.meterNumber] ?: p.meterNumber).take(18)
                c.drawText(date(p.dateMillis), xs[0], y, text)
                c.drawText(name, xs[1], y, text)
                c.drawText("%.2f".format(Locale.US, p.units), xs[2], y, text)
                c.drawText("%.2f".format(Locale.US, p.amount), xs[3], y, text)
                c.drawText(per, xs[4], y, text)
                c.drawText(if (p.used) "yes" else "no", xs[5], y, text)
                y += 16f
            }
            doc.finishPage(page)
        }
        val f = File(dir(ctx), "mytoken-history.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share MyToken export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    fun backupJson(meters: List<Meter>, purchases: List<Purchase>): String {
        val root = JSONObject()
        val ms = JSONArray()
        meters.forEach {
            ms.put(
                JSONObject()
                    .put("number", it.number)
                    .put("label", it.label)
                    .put("tracked", it.tracked)
                    .put("balanceUnits", it.balanceUnits ?: JSONObject.NULL)
                    .put("balanceAt", it.balanceAt ?: JSONObject.NULL)
            )
        }
        val ps = JSONArray()
        purchases.forEach {
            ps.put(
                JSONObject()
                    .put("meterNumber", it.meterNumber)
                    .put("token", it.token)
                    .put("units", it.units)
                    .put("amount", it.amount)
                    .put("dateMillis", it.dateMillis)
                    .put("used", it.used)
                    .put("manual", it.manual)
            )
        }
        root.put("app", "MyToken").put("version", 1).put("meters", ms).put("purchases", ps)
        return root.toString(2)
    }

    suspend fun restore(json: String, dao: AppDao): Int {
        val root = JSONObject(json)
        val ms = root.getJSONArray("meters")
        val ps = root.getJSONArray("purchases")
        dao.clearPurchases()
        dao.clearMeters()
        for (i in 0 until ms.length()) {
            val o = ms.getJSONObject(i)
            dao.insertMeter(
                Meter(
                    number = o.getString("number"),
                    label = o.optString("label", ""),
                    tracked = o.optBoolean("tracked", false),
                    balanceUnits = if (o.isNull("balanceUnits")) null else o.getDouble("balanceUnits"),
                    balanceAt = if (o.isNull("balanceAt")) null else o.getLong("balanceAt")
                )
            )
        }
        for (i in 0 until ps.length()) {
            val o = ps.getJSONObject(i)
            dao.insertPurchase(
                Purchase(
                    meterNumber = o.getString("meterNumber"),
                    token = o.getString("token"),
                    units = o.getDouble("units"),
                    amount = o.getDouble("amount"),
                    dateMillis = o.getLong("dateMillis"),
                    used = o.optBoolean("used", false),
                    manual = o.optBoolean("manual", false)
                )
            )
        }
        return ps.length()
    }
}
