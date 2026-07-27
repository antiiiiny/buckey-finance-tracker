package com.buckey.financetracker.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * BroadcastReceiver that intercepts incoming SMS messages.
 *
 * Only processes messages from sender IDs in [SenderWhitelist].
 * All other messages are silently ignored — they are not passed to the parser.
 *
 * For now, matched messages are logged to Logcat only.
 * Stage 2 will wire this to Room persistence.
 */
class SmsReceiver : BroadcastReceiver() {

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

        // Stage 2 TODO: persist to UnparsedMessageEntity
        // Stage 3 TODO: run through parser pipeline
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}