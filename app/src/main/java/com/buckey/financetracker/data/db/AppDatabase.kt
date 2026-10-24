package com.buckey.financetracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.db.dao.CategoryDao
import com.buckey.financetracker.data.db.dao.TransactionDao
import com.buckey.financetracker.data.db.dao.UnparsedMessageDao
import com.buckey.financetracker.data.db.entities.CategoryRuleEntity
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        TransactionEntity::class,
        UnparsedMessageEntity::class,
        CategoryRuleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun unparsedMessageDao(): UnparsedMessageDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "buckey_finance_db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(SEED_CALLBACK)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val SEED_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    // Wait for database to be ready, then seed
                    seedDefaultCategories(getDatabase(FinanceTrackerApp.getInstance()))
                }
            }
        }

        /**
         * Inserts the starter rules into `category_rules`.
         *
         * Seeding otherwise happens exactly once, in [SEED_CALLBACK] on database
         * creation. Anything that empties the table — notably "Clear All Data" in
         * Settings — must call this afterwards, or auto-categorization stays dead
         * until the app is reinstalled.
         */
        suspend fun seedDefaultCategories(database: AppDatabase) {
            database.categoryDao().insertAll(SEED_CATEGORIES)
        }

        /**
         * Adds any starter rule whose pattern is missing, without touching the
         * ones present. Returns how many were added.
         *
         * Unlike [seedDefaultCategories] this is safe to call on every launch: it
         * repairs a table emptied by an older "Clear All Data" (which did not
         * reseed) without overwriting a rule the user re-pointed.
         */
        suspend fun restoreMissingSeedRules(database: AppDatabase): Int =
            database.categoryDao().insertAllIfAbsent(SEED_CATEGORIES).count { it != -1L }

        /**
         * Starter category rules — merchant keyword → category.
         *
         * Deliberately contains no "upi"/"neft"/"imps" → Transfer rules. SIB's debit
         * SMS carries no payee, so SibParser sets merchant = "UPI" on every debit;
         * a rule on that string would label every debit "Transfer", which looks
         * categorized but actually means unknown. Without it debits fall to
         * "Uncategorized" and stay visibly pending until a real payee arrives from
         * statement matching (Stage 7). Do not add them back.
         */
        private val SEED_CATEGORIES = listOf(
            CategoryRuleEntity(merchantPattern = "swiggy", category = "Food"),
            CategoryRuleEntity(merchantPattern = "zomato", category = "Food"),
            CategoryRuleEntity(merchantPattern = "uber", category = "Transport"),
            CategoryRuleEntity(merchantPattern = "ola", category = "Transport"),
            CategoryRuleEntity(merchantPattern = "rapido", category = "Transport"),
            CategoryRuleEntity(merchantPattern = "amazon", category = "Shopping"),
            CategoryRuleEntity(merchantPattern = "flipkart", category = "Shopping"),
            CategoryRuleEntity(merchantPattern = "myntra", category = "Shopping"),
            CategoryRuleEntity(merchantPattern = "meesho", category = "Shopping"),
            CategoryRuleEntity(merchantPattern = "blinkit", category = "Groceries"),
            CategoryRuleEntity(merchantPattern = "zepto", category = "Groceries"),
            CategoryRuleEntity(merchantPattern = "instamart", category = "Groceries"),
            CategoryRuleEntity(merchantPattern = "bigbasket", category = "Groceries"),
            CategoryRuleEntity(merchantPattern = "recharge", category = "Utilities"),
            CategoryRuleEntity(merchantPattern = "electricity", category = "Utilities"),
            CategoryRuleEntity(merchantPattern = "rent", category = "Housing"),
            CategoryRuleEntity(merchantPattern = "salary", category = "Income"),
            CategoryRuleEntity(merchantPattern = "refund", category = "Income"),
            CategoryRuleEntity(merchantPattern = "netflix", category = "Entertainment"),
            CategoryRuleEntity(merchantPattern = "spotify", category = "Entertainment"),
            CategoryRuleEntity(merchantPattern = "prime video", category = "Entertainment"),
            CategoryRuleEntity(merchantPattern = "hotstar", category = "Entertainment"),
        )
    }
}