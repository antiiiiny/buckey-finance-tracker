package com.buckey.financetracker

import android.app.Application
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.sheets.SheetsSyncWorker

class FinanceTrackerApp : Application() {

    /** App-wide Room database instance. Initialized lazily on first access. */
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Schedule periodic Google Sheets sync
        SheetsSyncWorker.schedulePeriodic(this)
    }

    companion object {
        @Volatile
        private var instance: FinanceTrackerApp? = null

        fun getInstance(): FinanceTrackerApp {
            return instance ?: throw IllegalStateException(
                "FinanceTrackerApp not initialized — check AndroidManifest android:name"
            )
        }
    }
}