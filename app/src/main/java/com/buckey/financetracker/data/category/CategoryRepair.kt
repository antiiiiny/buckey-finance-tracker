package com.buckey.financetracker.data.category

import android.content.Context
import android.util.Log
import com.buckey.financetracker.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repairs `category_rules` damage left by older builds. Runs on every launch.
 *
 * Found on the real device on 2026-10-24: the table held 4 rules instead of the
 * 22 starters, one of them `upi → Transfer`. An early "Clear All Data" emptied it
 * without reseeding, and a debit recategorized before [GenericMerchant] existed
 * wrote the `upi` rule, which then stamped "Transfer" on every debit.
 *
 * Steps, each idempotent:
 * 1. Delete rules whose pattern is a generic merchant. [GenericMerchant] already
 *    stops new ones being written; this removes those written before it.
 * 2. Re-add missing starter rules, leaving existing patterns alone.
 * 3. Once only, move generic-merchant rows labelled "Transfer" back to
 *    "Uncategorized". That label meant "unknown" all along, and an honest
 *    "Uncategorized" is what the Stage 7 merchant import recomputes. Done once,
 *    so a debit the user marks Transfer by hand afterwards is never undone.
 */
object CategoryRepair {

    private const val TAG = "CategoryRepair"

    // Shared with SmsBackfill's backfill flag.
    private const val PREFS_NAME = "buckey_finance_prefs"
    private const val PREF_TRANSFER_RESET_DONE = "generic_transfer_reset_done"

    private const val TRANSFER = "Transfer"
    private const val UNCATEGORIZED = "Uncategorized"

    /** What a run changed, for logging. */
    data class Result(val rulesRemoved: Int, val rulesRestored: Int, val rowsReset: Int)

    suspend fun run(context: Context, database: AppDatabase): Result = withContext(Dispatchers.IO) {
        val removed = database.categoryDao().deleteByPatterns(GenericMerchant.all)
        val restored = AppDatabase.restoreMissingSeedRules(database)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val reset = if (prefs.getBoolean(PREF_TRANSFER_RESET_DONE, false)) {
            0
        } else {
            database.transactionDao()
                .recategorizeMerchants(GenericMerchant.all, TRANSFER, UNCATEGORIZED)
                .also { prefs.edit().putBoolean(PREF_TRANSFER_RESET_DONE, true).apply() }
        }

        if (removed + restored + reset > 0) {
            Log.i(TAG, "Removed $removed generic rule(s), restored $restored starter rule(s), reset $reset row(s)")
        }
        Result(removed, restored, reset)
    }
}
