package com.buckey.financetracker.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.buckey.financetracker.FinanceTrackerApp
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.parser.ParsedTransaction
import com.buckey.financetracker.data.parser.banks.SibParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver that intercepts incoming SMS messages.
 *
 * Only processes messages from sender IDs in [SenderWhitelist].
 * All other messages are silently ignored.
 *
 * Pipeline: SMS -> whitelist gate -> SibParser -> success: TransactionEntity, failure: UnparsedMessageEntity
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

        // Rule 12: never log the body — it carries amounts, balances and account digits.
        Log.i(TAG, "Whitelisted SMS captured — sender: $senderAddress, length: ${fullBody.length}")

        // Run parser pipeline
        scope.launch {
            try {
                val app = FinanceTrackerApp.getInstance()
                val parsed: ParsedTransaction? = SibParser.parse(senderAddress, fullBody)

                if (parsed != null) {
                    // Auto-categorize
                    val category = CategoryMatcher.getCategory(
                        app.database, parsed.merchant
                    )

                    // Parse succeeded — write TransactionEntity
                    val transaction = TransactionEntity(
                        transactionId = TransactionIdGenerator.generate(
                            senderAddress, System.currentTimeMillis(), fullBody
                        ),
                        amount = parsed.amount,
                        date = parsed.date.toEpochMilli(),
                        merchant = parsed.merchant,
                        type = parsed.type,
                        accountLast4 = parsed.accountLast4,
                        rawRef = parsed.rawRef,
                        senderAddress = senderAddress,
                        rawBody = fullBody,
                        category = category
                    )
                    app.database.transactionDao().insert(transaction)
                    Log.i(TAG, "Parsed and saved transaction: ${parsed.type} Rs.${parsed.amount} [$category]")
                } else {
                    // Parse failed — persist as unparsed
                    val entity = UnparsedMessageEntity(
                        senderAddress = senderAddress,
                        rawBody = fullBody,
                        failureReason = "No parser matched this SMS format"
                    )
                    app.database.unparsedMessageDao().insert(entity)
                    Log.w(TAG, "Could not parse SMS from $senderAddress — saved as unparsed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SMS processing pipeline: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}