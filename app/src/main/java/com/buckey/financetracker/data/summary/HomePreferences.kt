package com.buckey.financetracker.data.summary

import android.content.Context

/** What the home screen's Net figure shows. Chosen in Settings. */
enum class NetMode {
    /** The account balance from [BalanceCalculator]. The default. */
    RUNNING_BALANCE,

    /** The selected month's credits minus its debits. */
    THIS_MONTH
}

/** What the home screen's Spent figure (and group totals) count. Chosen in Settings. */
enum class SpentMode {
    /** Debits minus transfers, see [MonthSummary.spentPaise]. The default. */
    AFTER_TRANSFERS,

    /** Every debit, transfers included. */
    TOTAL
}

/** Persists the user's preferences and profile in the app's shared preferences. */
object HomePreferences {

    private const val PREFS_NAME = "buckey_finance_prefs"
    private const val KEY_NET = "home_net_mode"
    private const val KEY_SPENT = "home_spent_mode"
    private const val KEY_TOLERANCE = "match_tolerance_paise"
    private const val KEY_PROFILE = "profile_name"
    private const val KEY_DISMISSED = "dismissed_link_suggestions"
    const val DEFAULT_PROFILE = "Personal"

    fun netMode(context: Context): NetMode =
        read(context, KEY_NET, NetMode.entries) ?: NetMode.RUNNING_BALANCE

    fun setNetMode(context: Context, mode: NetMode) = write(context, KEY_NET, mode.name)

    fun spentMode(context: Context): SpentMode =
        read(context, KEY_SPENT, SpentMode.entries) ?: SpentMode.AFTER_TRANSFERS

    fun setSpentMode(context: Context, mode: SpentMode) = write(context, KEY_SPENT, mode.name)

    /**
     * How close a payment must be to a holding or loan to suggest clearing it
     * (user, 2026-10-25: default ₹10, adjustable in Preferences).
     */
    fun matchTolerancePaise(context: Context): Long =
        prefs(context).getLong(KEY_TOLERANCE, Ledger.CLEAR_TOLERANCE_PAISE)

    fun setMatchTolerancePaise(context: Context, paise: Long) =
        prefs(context).edit().putLong(KEY_TOLERANCE, paise.coerceAtLeast(0)).apply()

    /** The profile's name; its initial is the account icon. "Personal" for now. */
    fun profileName(context: Context): String =
        prefs(context).getString(KEY_PROFILE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROFILE

    fun setProfileName(context: Context, name: String) =
        prefs(context).edit().putString(KEY_PROFILE, name.trim()).apply()

    /** Suggestions the user answered "Not this", as "rowTransactionId|groupId". */
    fun dismissedSuggestions(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_DISMISSED, emptySet()).orEmpty()

    fun dismissSuggestion(context: Context, rowId: String, groupId: String) =
        prefs(context).edit()
            .putStringSet(KEY_DISMISSED, dismissedSuggestions(context) + suggestionKey(rowId, groupId))
            .apply()

    fun suggestionKey(rowId: String, groupId: String): String = "$rowId|$groupId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun <E : Enum<E>> read(context: Context, key: String, entries: List<E>): E? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
        return entries.firstOrNull { it.name == stored }
    }

    private fun write(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
