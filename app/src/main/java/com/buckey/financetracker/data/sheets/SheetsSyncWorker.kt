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
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that syncs unsynced transactions to Google Sheets.
 *
 * Runs on [Dispatchers.IO] (default for CoroutineWorker).
 * Queries transactions where [TransactionEntity.syncedToSheets] is false,
 * checks dedup via [GoogleSheetsRepository.isDuplicate], appends new rows,
 * then marks each transaction as synced.
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
                ExistingWorkPolicy.KEEP,
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

            // Fetch unsynced transactions
            val unsynced = database.transactionDao().getUnsyncedTransactions()
            if (unsynced.isEmpty()) {
                Log.i(TAG, "No unsynced transactions to sync")
                return Result.success()
            }

            var syncedCount = 0
            var skippedCount = 0
            var failedCount = 0

            for (tx in unsynced) {
                try {
                    // Dedup check
                    if (repository.isDuplicate(tx.transactionId)) {
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

            Log.i(TAG, "Sync complete: $syncedCount synced, $skippedCount skipped (duplicates), $failedCount failed")

            if (failedCount > 0 && syncedCount == 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            Result.retry()
        }
    }
}