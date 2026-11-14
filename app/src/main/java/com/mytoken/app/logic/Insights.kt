package com.mytoken.app.logic

import com.mytoken.app.data.Meter
import com.mytoken.app.data.Purchase
import com.mytoken.app.data.Reading
import kotlin.math.abs

/** A purchase with its units resolved (estimated when the message did not give them). */
data class Eff(val p: Purchase, val units: Double, val estimated: Boolean, val duplicate: Boolean)

data class Insight(
    val dailyUnits: Double?,
    val remainingUnits: Double?,
    val daysLeft: Double?,
    val totalUnits: Double,
    val totalSpend: Double,
    val avgPrice: Double?,
    val basis: String,
    val estimatedCount: Int,
    /** Days since the latest purchase (how long the current token has already been running). */
    val daysRunning: Double? = null,
    /** daysRunning + daysLeft: how long the current token is expected to last in total. */
    val totalDays: Double? = null,
    val lastUnits: Double? = null,
    val lastBoughtAt: Long? = null
)

object Insights {
    private const val DAY = 86_400_000.0
    private const val DAY_MS = 86_400_000L

    /**
     * Combines token messages and payment-only messages. A payment that matches a token message
     * (same amount, within 24h) is a duplicate. Payments with no token get estimated units using
     * this meter's own units-per-shilling ratio from messages that did report units.
     */
    fun effective(purchases: List<Purchase>): List<Eff> {
        val tokens = purchases.filter { it.kind != "PAYMENT" }
        val payments = purchases.filter { it.kind == "PAYMENT" }
        val known = tokens.filter { it.units > 0 && it.amount > 0 }
        val ratio = if (known.isNotEmpty()) known.sumOf { it.units } / known.sumOf { it.amount } else null

        val out = mutableListOf<Eff>()
        tokens.forEach { t ->
            if (t.units <= 0 && t.amount > 0 && ratio != null) out.add(Eff(t, t.amount * ratio, true, false))
            else out.add(Eff(t, t.units, false, false))
        }
        payments.forEach { pay ->
            val dup = tokens.any { t ->
                (t.amount <= 0.0 || abs(t.amount - pay.amount) <= maxOf(1.0, pay.amount * 0.02)) && abs(t.dateMillis - pay.dateMillis) <= DAY_MS
            }
            out.add(Eff(pay, if (ratio != null) pay.amount * ratio else 0.0, true, dup))
        }
        return out.sortedBy { it.p.dateMillis }
    }

    fun compute(
        meter: Meter?,
        purchases: List<Purchase>,
        readings: List<Reading>,
        now: Long = System.currentTimeMillis()
    ): Insight {
        val effs = effective(purchases).filter { !it.duplicate }
        val totalUnits = effs.sumOf { it.units }
        val spend = effs.sumOf { it.p.amount }
        val avgPrice = if (totalUnits > 0) spend / totalUnits else null
        val rs = readings.sortedBy { it.atMillis }

        var daily: Double? = null
        var basis = "not enough data yet"

        // 1) Two or more meter readings: real consumption between readings.
        if (rs.size >= 2) {
            var consumed = 0.0
            for (i in 1 until rs.size) {
                val a = rs[i - 1]
                val b = rs[i]
                val bought = effs.filter { it.p.dateMillis > a.atMillis && it.p.dateMillis <= b.atMillis }.sumOf { it.units }
                consumed += (a.units + bought - b.units).coerceAtLeast(0.0)
            }
            val span = (rs.last().atMillis - rs.first().atMillis) / DAY
            if (span >= 1.0 && consumed > 0) {
                daily = consumed / span
                basis = "your meter readings and purchases"
            }
        }
        // 2) One reading: units bought up to that reading minus what is left, since the first purchase.
        if (daily == null && rs.size == 1 && effs.isNotEmpty()) {
            val r = rs[0]
            val span = (r.atMillis - effs.first().p.dateMillis) / DAY
            val bought = effs.filter { it.p.dateMillis <= r.atMillis }.sumOf { it.units }
            val cons = bought - r.units
            if (span >= 2.0 && cons > 0) {
                daily = cons / span
                basis = "your balance and purchase history"
            }
        }
        // 3) Purchases only.
        if (daily == null && effs.size >= 2) {
            val span = (effs.last().p.dateMillis - effs.first().p.dateMillis) / DAY
            if (span >= 1.0) {
                daily = effs.dropLast(1).sumOf { it.units } / span
                basis = "purchase history only (enter your balance to improve this)"
            }
        }

        var remaining: Double? = null
        if (rs.isNotEmpty()) {
            val r = rs.last()
            val bought = effs.filter { it.p.dateMillis > r.atMillis }.sumOf { it.units }
            remaining = r.units + bought - (daily ?: 0.0) * ((now - r.atMillis) / DAY)
        } else if (effs.isNotEmpty()) {
            val last = effs.last()
            remaining = last.units - (daily ?: 0.0) * ((now - last.p.dateMillis) / DAY)
        }
        remaining = remaining?.coerceAtLeast(0.0)
        val daysLeft = if (remaining != null && daily != null && daily > 0) remaining / daily else null
        val lastBuy = effs.lastOrNull { it.p.dateMillis <= now } ?: effs.lastOrNull()
        val running = lastBuy?.let { ((now - it.p.dateMillis) / DAY).coerceAtLeast(0.0) }
        val total = if (running != null && daysLeft != null) running + daysLeft else null
        return Insight(
            daily, remaining, daysLeft, totalUnits, spend, avgPrice, basis, effs.count { it.estimated },
            running, total, lastBuy?.units, lastBuy?.p?.dateMillis
        )
    }
}
