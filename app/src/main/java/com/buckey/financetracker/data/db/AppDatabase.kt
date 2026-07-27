package com.buckey.financetracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.buckey.financetracker.data.db.dao.TransactionDao
import com.buckey.financetracker.data.db.dao.UnparsedMessageDao
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity

@Database(
    entities = [
        TransactionEntity::class,
        UnparsedMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun unparsedMessageDao(): UnparsedMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "buckey_finance_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}