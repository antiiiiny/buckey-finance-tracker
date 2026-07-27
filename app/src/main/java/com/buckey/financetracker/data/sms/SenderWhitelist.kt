package com.buckey.financetracker.data.sms

/**
 * Whitelist of SMS sender IDs (addresses) that the app will process.
 *
 * Currently hardcoded for South Indian Bank (SIB).
 * Add new sender IDs here as new banks are supported.
 */
object SenderWhitelist {

    // South Indian Bank sender IDs — both transactional and promotional
    private val sibSenderIds = setOf(
        "SIBPYMNT",
        "SIBBANK",
        "SIBALERT",
        "SIBNTFT",
        "S-IBANK",
        "SIB",
    )

    // Combined list for easy lookup
    private val allSenderIds: Set<String> = sibSenderIds

    /**
     * Returns true if the sender's address is in the whitelist.
     * Comparison is case-insensitive.
     */
    fun isWhitelisted(senderAddress: String): Boolean {
        return allSenderIds.any { it.equals(senderAddress.trim(), ignoreCase = true) }
    }
}