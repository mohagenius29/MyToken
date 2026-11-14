package com.mytoken.app.logic

import com.mytoken.app.data.Meter
import com.mytoken.app.data.Purchase

data class Insight(
    val dailyUnits: Double?,
    val remainingUnits: Double?,
    val daysLeft: Double?,
    val totalUnits: Double,
    val totalSpend: Double,
    val avgPrice: Double?
)

object Insights {
    private const val DAY = 86_400_000.0

    fun compute(meter: Meter?, purchases: List<Purchase>, now: Long = System.currentTimeMillis()): Insight {
        val ps = purchases.sortedBy { it.dateMillis }
        val totalUnits = ps.sumOf { it.units }
        val spend = ps.sumOf { it.amount }
        val avgPrice = if (totalUnits > 0) spend / totalUnits else null

        // Average daily use: units bought before the latest purchase, spread over the time span.
        var daily: Double? = null
        if (ps.size >= 2) {
            val span = (ps.last().dateMillis - ps.first().dateMillis) / DAY
            if (span >= 1.0) daily = ps.dropLast(1).sumOf { it.units } / span
        }

        var remaining: Double? = null
        val bu = meter?.balanceUnits
        val bt = meter?.balanceAt
        if (bu != null && bt != null) {
            val bought = ps.filter { it.dateMillis > bt }.sumOf { it.units }
            val elapsed = (now - bt) / DAY
            remaining = bu + bought - (daily ?: 0.0) * elapsed
        } else if (ps.isNotEmpty()) {
            val elapsed = (now - ps.last().dateMillis) / DAY
            remaining = ps.last().units - (daily ?: 0.0) * elapsed
        }
        remaining = remaining?.coerceAtLeast(0.0)
        val daysLeft = if (remaining != null && daily != null && daily > 0) remaining / daily else null
        return Insight(daily, remaining, daysLeft, totalUnits, spend, avgPrice)
    }
}
