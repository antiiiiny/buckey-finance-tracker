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

/** Persists the home screen's choices in the app's shared preferences. */
object HomePreferences {

    private const val PREFS_NAME = "buckey_finance_prefs"
    private const val KEY_NET = "home_net_mode"
    private const val KEY_SPENT = "home_spent_mode"

    fun netMode(context: Context): NetMode =
        read(context, KEY_NET, NetMode.entries) ?: NetMode.RUNNING_BALANCE

    fun setNetMode(context: Context, mode: NetMode) = write(context, KEY_NET, mode.name)

    fun spentMode(context: Context): SpentMode =
        read(context, KEY_SPENT, SpentMode.entries) ?: SpentMode.AFTER_TRANSFERS

    fun setSpentMode(context: Context, mode: SpentMode) = write(context, KEY_SPENT, mode.name)

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
