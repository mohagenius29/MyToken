package com.mytoken.app.parser

import java.time.LocalDate
import java.time.ZoneId

data class ParsedToken(
    val meter: String?,
    val token: String,
    val units: Double?,
    val amount: Double?,
    val dateMillis: Long
)

/** Tolerant parser for KPLC prepaid token SMS messages. */
object KplcParser {
    private val tokenRe = Regex("""(?<!\d)(\d{4})[\s-]?(\d{4})[\s-]?(\d{4})[\s-]?(\d{4})[\s-]?(\d{4})(?!\d)""")
    private val meterRe = Regex("""(?i)(?:mtr|meter)\s*(?:no\.?|number)?\s*[:#=]?\s*(\d{6,13})""")
    private val unitsRe = Regex("""(?i)units?\s*[:=]?\s*(\d+(?:\.\d+)?)""")
    private val amtRe = Regex("""(?i)(?<![a-z])(?:amt|amount|paid)\s*[:=]?\s*(?:ksh\.?|kes)?\s*(\d[\d,]*(?:\.\d+)?)""")
    private val tknAmtRe = Regex("""(?i)tknamt\s*[:=]?\s*(?:ksh\.?|kes)?\s*(\d[\d,]*(?:\.\d+)?)""")
    private val dateRe = Regex("""(?i)date\s*[:=]?\s*(\d{4})[-/]?(\d{2})[-/]?(\d{2})""")

    fun parse(body: String, smsMillis: Long? = null): ParsedToken? {
        val t = tokenRe.find(body) ?: return null
        val lower = body.lowercase()
        if (!("token" in lower || "units" in lower || "mtr" in lower || "kplc" in lower)) return null

        val token = t.groupValues.drop(1).joinToString("")
        val meter = meterRe.find(body)?.groupValues?.get(1)
        val units = unitsRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
        val amtStr = amtRe.find(body)?.groupValues?.get(1) ?: tknAmtRe.find(body)?.groupValues?.get(1)
        val amount = amtStr?.replace(",", "")?.toDoubleOrNull()
        val date = smsMillis ?: parseDate(body) ?: System.currentTimeMillis()
        return ParsedToken(meter, token, units, amount, date)
    }

    private fun parseDate(body: String): Long? {
        val m = dateRe.find(body) ?: return null
        return try {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
