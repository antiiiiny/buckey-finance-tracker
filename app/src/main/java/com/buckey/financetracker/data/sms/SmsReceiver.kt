package com.buckey.financetracker.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that intercepts incoming SMS messages.
 *
 * Only processes messages from sender IDs in [SenderWhitelist].
 * All other messages are silently ignored — they are not passed to the parser.
 *
 * Matched messages are persisted to [UnparsedMessageEntity] in Room.
 * Stage 3 will add parsing — on success, write [TransactionEntity]; on failure, stays unparsed.
 */
class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        // Group all parts into a single body
        val fullBody = messages.joinToString("") { it.messageBody ?: "" }
        val senderAddress = messages.first().originatingAddress ?: return

        Log.d(TAG, "SMS received from: $senderAddress")

        if (!SenderWhitelist.isWhitelisted(senderAddress)) {
            Log.d(TAG, "Sender not whitelisted, ignoring: $senderAddress")
            return
        }

        Log.i(TAG, "Whitelisted SMS captured — sender: $senderAddress, body: $fullBody")

        // Persist to Room as unparsed
        val receivedAt = System.currentTimeMillis()
        scope.launch {
            try {
                val app = FinanceTrackerApp.getInstance()
                val dao = app.database.unparsedMessageDao()

                val entity = UnparsedMessageEntity(
                    senderAddress = senderAddress,
                    rawBody = fullBody,
                    receivedAt = receivedAt
                )
                dao.insert(entity)
                Log.i(TAG, "Persisted unparsed message from: $senderAddress")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist message: ${e.message}")
            }
        }

        // Stage 3 TODO: run through parser pipeline
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}