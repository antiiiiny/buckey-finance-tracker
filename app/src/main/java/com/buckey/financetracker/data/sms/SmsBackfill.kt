package com.buckey.financetracker.data.sms

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * One-time query against the device's SMS inbox for historical messages
 * from whitelisted senders, run on first launch after SMS permission is granted.
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
     * Queries [Telephony.Sms.Inbox.CONTENT_URI] for SMS from whitelisted senders
     * and logs each matched message. Skips if backfill was already performed.
     *
     * On Android 14+, this will fail with SecurityException if the app is not
     * the default SMS app. That's expected — we catch it gracefully.
     *
     * @return the number of whitelisted messages found
     */
    fun runBackfill(context: Context): Int {
        if (isBackfillDone(context)) {
            Log.d(TAG, "Backfill already completed, skipping")
            return 0
        }

        val contentResolver: ContentResolver = context.contentResolver
        val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI

        // We can't filter by sender in the query because sender IDs vary,
        // so we fetch recent messages and filter in-memory
        val projection = arrayOf(
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE_SENT,
            Telephony.Sms.Inbox._ID
        )
        val sortOrder = "${Telephony.Sms.Inbox.DATE_SENT} DESC"

        var matchedCount = 0

        try {
            val cursor: Cursor? = contentResolver.query(
                uri, projection, null, null, sortOrder
            )

            cursor?.use { c ->
                val unparsedMessages = mutableListOf<UnparsedMessageEntity>()

                while (c.moveToNext()) {
                    val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
                    val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
                    val dateSent = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.Inbox.DATE_SENT))
                    val smsId = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.Inbox._ID))

                    if (SenderWhitelist.isWhitelisted(address)) {
                        matchedCount++
                        Log.i(TAG, "Backfill matched #${smsId} — sender: $address, " +
                                "date: $dateSent, body: $body")

                        unparsedMessages.add(
                            UnparsedMessageEntity(
                                senderAddress = address,
                                rawBody = body,
                                receivedAt = dateSent
                            )
                        )
                    }
                }

                // Batch persist (one-time init call — safe to block IO)
                if (unparsedMessages.isNotEmpty()) {
                    runBlocking(Dispatchers.IO) {
                        val app = FinanceTrackerApp.getInstance()
                        val dao = app.database.unparsedMessageDao()
                        dao.insertAll(unparsedMessages)
                    }
                    Log.i(TAG, "Persisted ${unparsedMessages.size} backfill messages to Room")
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Backfill blocked by Android 14+ restriction: ${e.message}. " +
                    "Live SMS capture via BroadcastReceiver still works.")
        }

        // Mark backfill as done (even if 0 messages found)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_BACKFILL_DONE, true).apply()

        Log.i(TAG, "Backfill complete — matched $matchedCount whitelisted messages")
        return matchedCount
    }
}