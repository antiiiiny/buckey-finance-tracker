package com.buckey.financetracker.data.sheets

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.db.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that syncs unsynced transactions to Google Sheets.
 *
 * Runs on [Dispatchers.IO] (default for CoroutineWorker).
 * Queries transactions where [TransactionEntity.syncedToSheets] is false,
 * checks dedup against one read of the Sheet's ID column, appends new rows,
 * then marks each transaction as synced. Finally rewrites the Category cell of
 * synced rows whose category changed since (rule 10, amended 2026-10-24).
 */
class SheetsSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SheetsSyncWorker"
        private const val PERIODIC_SYNC_NAME = "periodic_sheets_sync"
        private const val ONE_TIME_SYNC_NAME = "oneshot_sheets_sync"
        private const val INTERVAL_MINUTES = 15L

        // Shared with SmsBackfill and CategoryRepair.
        private const val PREFS_NAME = "buckey_finance_prefs"

        /**
         * Set once the Sheet has been rebuilt in the current layout (sorted, with
         * Source and Categorized By). Unset means the next sync rebuilds it: true
         * on the first run after upgrading, and after [requestRebuild].
         */
        private const val PREF_SHEET_REBUILT = "sheet_rebuilt_v4"

        /** SQLite caps bound variables at 999; stay well under it. */
        private const val ID_CHUNK = 500

        /** Settings -> Rebuild Google Sheet: rebuild on the next sync, starting now. */
        fun requestRebuild(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_SHEET_REBUILT, false).apply()
            enqueueOneShot(context)
        }

        /**
         * Enqueues periodic sync every 15 minutes with a network constraint.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid duplicate schedules.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<SheetsSyncWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(PERIODIC_SYNC_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_SYNC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Periodic sync scheduled every $INTERVAL_MINUTES minutes")
        }

        /**
         * Enqueues a one-time sync for immediate execution.
         * If a sync is already running, the new request waits.
         */
        fun enqueueOneShot(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SheetsSyncWorker>()
                .setConstraints(constraints)
                .addTag(ONE_TIME_SYNC_NAME)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_SYNC_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.i(TAG, "One-shot sync enqueued")
        }
    }

    override suspend fun doWork(): Result {
        if (!GoogleSignInHelper.isSignedIn(applicationContext)) {
            Log.w(TAG, "Sync skipped — user not signed in to Google")
            return Result.success()
        }

        val database = FinanceTrackerApp.getInstance().database
        val repository = GoogleSheetsRepository(applicationContext)

        return try {
            // Ensure headers exist in the sheet
            repository.ensureHeaders()

            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_SHEET_REBUILT, false)) {
                return if (rebuild(database, repository)) {
                    prefs.edit().putBoolean(PREF_SHEET_REBUILT, true).apply()
                    Result.success()
                } else {
                    Result.retry()
                }
            }

            // One read of column A serves both the dedup check and the category
            // pass. Null means the read failed; fall back to per-row dedup.
            var rowIndex = repository.fetchRowIndex()

            val unsynced = database.transactionDao().getUnsyncedTransactions()
            var syncedCount = 0
            var skippedCount = 0
            var failedCount = 0

            for (tx in unsynced) {
                try {
                    val duplicate = rowIndex?.containsKey(tx.transactionId)
                        ?: repository.isDuplicate(tx.transactionId)
                    if (duplicate) {
                        database.transactionDao().markAsSynced(tx.id)
                        skippedCount++
                        continue
                    }

                    val success = repository.appendRow(tx)
                    if (success) {
                        database.transactionDao().markAsSynced(tx.id)
                        syncedCount++
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync transaction ${tx.id}: ${e.message}")
                    failedCount++
                }
            }

            if (unsynced.isNotEmpty()) {
                Log.i(TAG, "Sync complete: $syncedCount synced, $skippedCount skipped (duplicates), $failedCount failed")
            }

            // Appends add rows at the bottom, so re-read only if there were any.
            if (syncedCount > 0) {
                // New rows land at the bottom; one sort puts each at its date.
                repository.sortByDate()
                rowIndex = repository.fetchRowIndex()
            }
            val categoriesOk = rowIndex?.let { syncCategories(database, repository, it) } ?: false

            if ((failedCount > 0 && syncedCount == 0) || !categoriesOk) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Writes every Room row to the Sheet, oldest first, after the repository
     * backs up the current tab. Only the rows actually written are marked synced,
     * so an SMS arriving mid-rebuild is appended by the next sync.
     */
    private suspend fun rebuild(database: AppDatabase, repository: GoogleSheetsRepository): Boolean {
        val dao = database.transactionDao()
        val rows = dao.getAllOldestFirst()
        if (!repository.rebuild(rows)) return false
        rows.map { it.id }.chunked(ID_CHUNK).forEach { dao.markRebuilt(it) }
        Log.i(TAG, "Sheet rebuilt: ${rows.size} rows, sorted by date")
        return true
    }

    /**
     * Rewrites the Category cell of synced rows whose category changed in Room
     * (rule 10, amended 2026-10-24). Returns false if the write failed.
     *
     * A row missing from the Sheet stays dirty: it may have been appended after
     * [rowIndex] was read, and if it was deleted by hand, retrying costs nothing.
     */
    private suspend fun syncCategories(
        database: AppDatabase,
        repository: GoogleSheetsRepository,
        rowIndex: Map<String, Int>
    ): Boolean {
        val dao = database.transactionDao()
        val dirty = dao.getCategoryDirty()
        if (dirty.isEmpty()) return true

        val plan = SheetCategorySync.plan(rowIndex, dirty)
        if (!repository.updateCells(plan.updates)) return false
        plan.updates.forEach { dao.clearCategoryDirty(it.transaction.id, it.transaction.category) }

        Log.i(TAG, "Category sync: ${plan.updates.size} updated, ${plan.notInSheet.size} not in sheet")
        return true
    }
}
