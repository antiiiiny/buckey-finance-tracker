package com.buckey.financetracker.data.db

import android.content.Context

/**
 * Placeholder for Room database.
 *
 * Will be converted to a proper @Database class in Stage 2
 * when entities (TransactionEntity, UnparsedMessageEntity) are created.
 *
 * KSP does not accept @Database(entities = []), so this is a plain
 * singleton until Stage 2.
 */
class AppDatabase private constructor() {

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = AppDatabase()
                INSTANCE = instance
                instance
            }
        }
    }
}