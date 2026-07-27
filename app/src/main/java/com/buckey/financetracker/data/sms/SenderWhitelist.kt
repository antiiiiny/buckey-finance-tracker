package com.buckey.financetracker.data.sms

/**
 * Whitelist of SMS sender IDs (addresses) that the app will process.
 *
 * Currently hardcoded for South Indian Bank (SIB).
 * Add new sender IDs / patterns here as new banks are supported.
 *
 * SIB format: XX-SIBSMS-S/T/P where:
 *   XX = two-letter code (varies by circle/region)
 *   SIBSMS = constant identifier
 *   S/T/P = Transaction / OTP / Promotion
 *
 * Examples: AX-SIBSMS-S, AX-SIBSMS-T, MH-SIBSMS-S
 */
object SenderWhitelist {

    // Regex patterns for supported banks
    private val sibPattern = Regex(
        """^[A-Za-z]{2}-SIBSMS-[STP]$""",
        RegexOption.IGNORE_CASE
    )

    // Exact-match sender IDs (for non-pattern senders)
    private val exactMatchIds = setOf(
        "SIBPYMNT",
        "SIBBANK",
        "SIBALERT",
        "SIBNTFT",
        "S-IBANK",
        "SIB",
    )

    private val patterns = listOf(sibPattern)

    /**
     * Returns true if the sender's address is in the whitelist.
     * Checks exact IDs first, then falls through to regex patterns.
     */
    fun isWhitelisted(senderAddress: String): Boolean {
        val trimmed = senderAddress.trim()

        // Check exact match IDs first
        if (exactMatchIds.any { it.equals(trimmed, ignoreCase = true) }) {
            return true
        }

        // Check regex patterns
        return patterns.any { it.matches(trimmed) }
    }
}