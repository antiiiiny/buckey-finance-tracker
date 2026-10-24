package com.buckey.financetracker.data.sms

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.parser.banks.SibParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-time query against the device's SMS inbox for historical messages
 * from whitelisted senders, run on first launch after SMS permission is granted
 * (rule 1).
 *
 * Uses a SharedPreferences flag to ensure it only runs once per install.
 *
 * NOTE: On Android 14+, non-default SMS apps cannot query content://sms/inbox
 * even with READ_SMS permission. The query will fail gracefully with a log warning.
 * Live SMS capture via BroadcastReceiver still works regardless.
 */
object SmsBackfill {

    private const val TAG = "SmsBackfill"
    private const val PREF_BACKFILL_DONE = "sms_backfill_completed"
    private const val PREFS_NAME = "buckey_finance_prefs"

    /**
     * Returns true if backfill has already been completed.
     */
    fun isBackfillDone(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_BACKFILL_DONE, false)
    }

    /**
     * Queries [Telephony.Sms.Inbox.CONTENT_URI] for SMS from whitelisted senders,
     * runs them through the same parse → categorize pipeline as live capture, and
     * persists the results. Skips if backfill was already performed.
     *
     * Suspends and does all its work on [Dispatchers.IO], so it is safe to call
     * from the main thread's coroutine scope.
     *
     * On Android 14+, this will fail with SecurityException if the app is not
     * the default SMS app. That's expected — we catch it gracefully.
     *
     * @return the number of whitelisted messages found
     */
    suspend fun runBackfill(context: Context): Int = withContext(Dispatchers.IO) {
        if (isBackfillDone(context)) {
            Log.d(TAG, "Backfill already completed, skipping")
            return@withContext 0
        }

        val matched = try {
            queryWhitelistedInbox(context)
        } catch (e: SecurityException) {
            Log.w(TAG, "Backfill blocked by Android 14+ restriction: ${e.message}. " +
                    "Live SMS capture via BroadcastReceiver still works.")
            emptyList()
        }

        if (matched.isNotEmpty()) {
            persist(matched)
        }

        // Mark backfill as done (even if 0 messages found)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_BACKFILL_DONE, true).apply()

        Log.i(TAG, "Backfill complete — matched ${matched.size} whitelisted messages")
        matched.size
    }

    /**
     * Reads the inbox and keeps only whitelisted senders (rule 2). Sender IDs
     * vary, so filtering happens in memory rather than in the query.
     */
    private fun queryWhitelistedInbox(context: Context): List<UnparsedMessageEntity> {
        val projection = arrayOf(
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE_SENT
        )
        val sortOrder = "${Telephony.Sms.Inbox.DATE_SENT} DESC"
        val matched = mutableListOf<UnparsedMessageEntity>()

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null, sortOrder
        )?.use { c ->
            val addressCol = c.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS)
            val bodyCol = c.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY)
            val dateCol = c.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE_SENT)

            while (c.moveToNext()) {
                val address = c.getString(addressCol) ?: continue
                if (!SenderWhitelist.isWhitelisted(address)) continue
                matched.add(
                    UnparsedMessageEntity(
                        senderAddress = address,
                        rawBody = c.getString(bodyCol) ?: "",
                        receivedAt = c.getLong(dateCol)
                    )
                )
            }
        }
        return matched
    }

    /**
     * Parses each message; successes become categorized transactions, failures
     * go to the unparsed queue (rule 5). Batched to keep first launch cheap.
     */
    private suspend fun persist(messages: List<UnparsedMessageEntity>) {
        val database = FinanceTrackerApp.getInstance().database
        val transactions = mutableListOf<TransactionEntity>()
        val failures = mutableListOf<UnparsedMessageEntity>()

        for (msg in messages) {
            val parsed = SibParser.parse(msg.senderAddress, msg.rawBody)
            if (parsed == null) {
                failures.add(msg.copy(failureReason = "No parser matched this SMS format"))
                continue
            }
            transactions.add(
                TransactionEntity(
                    transactionId = TransactionIdGenerator.generate(
                        msg.senderAddress, msg.receivedAt, msg.rawBody
                    ),
                    amount = parsed.amount,
                    date = parsed.date.toEpochMilli(),
                    merchant = parsed.merchant,
                    type = parsed.type,
                    accountLast4 = parsed.accountLast4,
                    rawRef = parsed.rawRef,
                    senderAddress = msg.senderAddress,
                    rawBody = msg.rawBody,
                    category = CategoryMatcher.getCategory(database, parsed.merchant, parsed.type)
                )
            )
        }

        if (transactions.isNotEmpty()) database.transactionDao().insertAll(transactions)
        if (failures.isNotEmpty()) database.unparsedMessageDao().insertAll(failures)
        Log.i(TAG, "Persisted ${messages.size} backfill messages " +
                "(${transactions.size} parsed, ${failures.size} unparsed)")
    }
}
