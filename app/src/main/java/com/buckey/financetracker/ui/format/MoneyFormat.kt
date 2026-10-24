package com.buckey.financetracker.ui.format

import kotlin.math.abs

/**
 * Rupee amounts as the UI shows them: Indian digit grouping (₹1,23,456), paise
 * only when non-zero. Written by hand because `DecimalFormat` cannot group
 * lakhs and crores, and locale data differs between the JVM and the device.
 */
object MoneyFormat {

    /** U+2212, so a negative amount lines up with "+" in a column. */
    const val MINUS = "−"

    /** "₹18,240", "₹1,299.50"; negative amounts get [MINUS]. */
    fun rupees(paise: Long): String {
        val magnitude = abs(paise)
        val whole = group(magnitude / 100)
        val fraction = magnitude % 100
        val text = if (fraction == 0L) "₹$whole" else "₹$whole.${fraction.toString().padStart(2, '0')}"
        return if (paise < 0) "$MINUS$text" else text
    }

    /** Always signed: "+₹2,000", "−₹1,120"; zero is unsigned. */
    fun signed(paise: Long): String = if (paise > 0) "+${rupees(paise)}" else rupees(paise)

    /** Words for TalkBack: "2,000 rupees", "minus 1,120 rupees 50 paise". */
    fun spoken(paise: Long): String {
        val magnitude = abs(paise)
        val fraction = magnitude % 100
        val words = "${group(magnitude / 100)} rupees" + if (fraction == 0L) "" else " $fraction paise"
        return if (paise < 0) "minus $words" else words
    }

    private fun group(rupees: Long): String {
        val digits = rupees.toString()
        if (digits.length <= 3) return digits
        val parts = ArrayDeque<String>()
        var rest = digits.dropLast(3)
        while (rest.length > 2) {
            parts.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.addFirst(rest)
        return parts.joinToString(",") + "," + digits.takeLast(3)
    }
}
