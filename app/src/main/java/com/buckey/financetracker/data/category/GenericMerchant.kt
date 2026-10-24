package com.buckey.financetracker.data.category

/**
 * Identifies merchant strings that name a payment rail rather than a payee.
 *
 * SIB's debit SMS carries no payee, so SibParser stores `merchant = "UPI"` on
 * every debit. A category rule written for such a string would relabel every
 * debit at once, and a blank pattern would match everything via `LIKE '%%'`.
 * Anything this returns true for must never become a `category_rules` entry
 * (rule 7 applies to real merchants only), and may be overwritten by the Stage 7
 * merchant map.
 *
 * Mirrors `GENERIC_MERCHANTS` in the reconciliation script's models.py.
 */
object GenericMerchant {

    private val GENERIC = setOf("", "UPI", "NEFT", "IMPS", "RTGS", "UNKNOWN")

    fun isGeneric(merchant: String): Boolean = merchant.trim().uppercase() in GENERIC

    /** The generic strings themselves, uppercase, for SQL `IN` clauses. */
    val all: List<String> = GENERIC.toList()
}
