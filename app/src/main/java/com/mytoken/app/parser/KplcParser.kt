package com.mytoken.app.parser

import java.time.LocalDate
import java.time.ZoneId

enum class Kind { TOKEN, PAYMENT }

/** Whatever could be pulled out of a message, even if it is not a complete token message. */
data class Fields(val meter: String?, val token: String?, val units: Double?, val amount: Double?)

data class ParsedToken(
    val kind: Kind,
    val meter: String?,
    val token: String,
    val units: Double?,
    val amount: Double?,
    val dateMillis: Long
)

/**
 * Tolerant, sender-independent reader for KPLC related messages:
 *  - token messages (a 20-digit token, in any layout)
 *  - payment confirmations to KPLC (M-Pesa, Equity, other banks) that carry no token
 */
object KplcParser {
    private val tokenRe = Regex("""(?<!\d)\d{4}(?:[\s.\-\u2010-\u2015_/,:]{0,2}\d{4}){4}(?!\d)""")
    private val meterLabelRe = Regex("""(?i)(?:mtr|meter)\s*(?:no\.?|number|num)?\s*[:#=\-]?\s*(?:is\s*)?(\d{6,13})""")
    private val acctLabelRe = Regex("""(?i)(?:acc(?:ount)?|a/c|acct)\s*(?:no\.?|number|num)?\s*[:#=\-]?\s*(?:is\s*)?(\d{6,13})""")
    private val longNumRe = Regex("""(?<!\d)\d{7,13}(?!\d)""")
    private val phoneRe = Regex("""^(0[17]\d{8}|254[17]\d{8})$""")
    private val unitsRe1 = Regex("""(?i)units?\s*[:=\-]?\s*(\d+(?:\.\d+)?)""")
    private val unitsRe2 = Regex("""(?i)(\d+(?:\.\d+)?)\s*(?:units?|kwh)\b""")
    private val amtLabelRe = Regex("""(?i)(?<![a-z])(?:amt|amount|paid|total)\s*[:=]?\s*(?:ksh[s]?\.?|kes\.?)?\s*(\d[\d,]*(?:\.\d+)?)""")
    private val currencyRe = Regex("""(?i)(?:ksh[s]?\.?|kes\.?)\s*(\d[\d,]*(?:\.\d+)?)""")
    private val tknAmtRe = Regex("""(?i)tknamt\s*[:=]?\s*(?:ksh[s]?\.?|kes\.?)?\s*(\d[\d,]*(?:\.\d+)?)""")
    private val dateRe = Regex("""(?i)date\s*[:=]?\s*(\d{4})[-/]?(\d{2})[-/]?(\d{2})""")
    private val refRe = Regex("""^\s*([A-Z0-9]{10})\s+(?i:confirmed)""")

    private val relevantWords = listOf("kplc", "kenya power", "kenyapower", "token", "units", "888880", "prepaid", "mtr", "meter", "kwh")

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun looksRelevant(body: String): Boolean {
        val l = body.lowercase()
        return relevantWords.any { it in l } || tokenRe.containsMatchIn(body)
    }

    fun extract(body: String): Fields {
        val text = norm(body)
        val tokenMatch = tokenRe.find(text)
        val token = tokenMatch?.value?.filter { it.isDigit() }
        val rest = if (tokenMatch != null) text.replace(tokenRe, " ") else text

        // Meter priority: "Mtr/Meter" label, then an 11-digit "account" label, then any 11-digit number,
        // then other plausible account-like numbers. Phone numbers are never used.
        val byMeterLabel = meterLabelRe.findAll(rest).map { it.groupValues[1] }.filter { !phoneRe.matches(it) }.toList()
        val byAcctLabel = acctLabelRe.findAll(rest).map { it.groupValues[1] }.filter { !phoneRe.matches(it) }.toList()
        val unlabelled = longNumRe.findAll(rest).map { it.value }.filter { !phoneRe.matches(it) }.toList()
        val meter = byMeterLabel.firstOrNull()
            ?: byAcctLabel.firstOrNull { it.length == 11 }
            ?: unlabelled.firstOrNull { it.length == 11 }
            ?: byAcctLabel.firstOrNull { it.length in 7..12 }
            ?: unlabelled.firstOrNull { it.length in 7..12 }

        val units = unitsRe1.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: unitsRe2.find(rest)?.groupValues?.get(1)?.toDoubleOrNull()
        val amtStr = amtLabelRe.find(rest)?.groupValues?.get(1)
            ?: currencyRe.find(rest)?.groupValues?.get(1)
            ?: tknAmtRe.find(rest)?.groupValues?.get(1)
        val amount = amtStr?.replace(",", "")?.toDoubleOrNull()
        return Fields(meter, token, units, amount)
    }

    fun parse(body: String, smsMillis: Long? = null): ParsedToken? {
        val text = norm(body)
        val lower = text.lowercase()
        val f = extract(text)
        val date = smsMillis ?: parseDate(text) ?: System.currentTimeMillis()

        if (f.token != null) {
            return ParsedToken(Kind.TOKEN, f.meter, f.token, f.units, f.amount, date)
        }

        val payContext = listOf("kplc", "kenya power", "kenyapower", "888880").any { it in lower }
        val payWord = listOf("paid", "sent", "payment", "confirmed", "debited", "transferred", "purchase").any { it in lower }
        val postpaidOnly = "888888" in lower && "888880" !in lower
        if (payContext && payWord && !postpaidOnly && f.amount != null && f.amount > 0) {
            val ref = refRe.find(text)?.groupValues?.get(1)
            val key = ref ?: Integer.toHexString("${f.amount}|$date|${f.meter}".hashCode())
            return ParsedToken(Kind.PAYMENT, f.meter, "PAY-$key", null, f.amount, date)
        }
        return null
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
