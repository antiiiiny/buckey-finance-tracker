package com.buckey.financetracker.data.review

import android.util.Log
import androidx.room.withTransaction
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.category.GenericMerchant
import com.buckey.financetracker.data.category.MerchantMemory
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.sms.TransactionIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 6: turns an unparsed SMS into a [TransactionEntity] from values the user
 * typed in, or dismisses it as not-a-transaction.
 *
 * The source row is marked resolved, never deleted — rule 5 says a whitelisted
 * SMS is never dropped, and the raw body stays available for writing the regex
 * that should have parsed it.
 *
 * The transaction ID uses rule 10's formula over the *source message*
 * (sender + received time + raw body), not over the typed values. So resolving
 * the same SMS twice yields the same ID, the unique index rejects the second
 * insert, and Sheets dedup never sees two rows for one SMS. Hashing the typed
 * amount instead would mint a new ID every time the user fixed a typo.
 */
object UnparsedResolver {

    private const val TAG = "UnparsedResolver"

    /** What the user entered in the manual form, before validation. */
    data class ManualEntry(
        val amountText: String,
        val type: TransactionType?,
        val merchant: String,
        /** Blank means "let the rules decide" (tier 1). */
        val category: String = "",
        val accountLast4: String = "",
        val rawRef: String = ""
    )

    /** Result of [resolve]. */
    enum class Outcome {
        CREATED,

        /** A transaction for this SMS already existed; the message is still resolved. */
        ALREADY_EXISTS
    }

    /**
     * Parses a user-typed amount. Accepts "1,234.50", "Rs.500", "₹ 20" — the
     * forms a user copies out of a bank SMS. Returns null for anything that is
     * not a positive finite number: a zero or negative manual amount is a typo,
     * not a transaction.
     */
    fun parseAmount(text: String): Double? {
        val cleaned = text.trim()
            .removePrefix("₹")
            .replace(Regex("^(?i)(rs\\.?|inr)"), "")
            .replace(",", "")
            .trim()
        if (!cleaned.matches(Regex("\\d+(\\.\\d{1,2})?"))) return null
        return cleaned.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    }

    /**
     * Returns human-readable problems with [entry], empty if it can be saved.
     * Pure, so the rules that guard a financial record are unit tested.
     */
    fun validate(entry: ManualEntry): List<String> = buildList {
        if (parseAmount(entry.amountText) == null) add("Enter an amount greater than zero.")
        if (entry.type == null) add("Choose debit or credit.")
        if (entry.merchant.isBlank()) add("Enter who the money went to or came from.")
        val last4 = entry.accountLast4.trim()
        if (last4.isNotEmpty() && !last4.matches(Regex("\\d{4}"))) {
            add("Account must be exactly 4 digits, or left blank.")
        }
        val ref = entry.rawRef.trim()
        if (ref.isNotEmpty() && !ref.matches(Regex("[A-Za-z0-9]+"))) {
            add("Reference must be letters and digits only, or left blank.")
        }
    }

    /** The dedup key for a transaction created from [message]; see class KDoc. */
    fun transactionIdFor(message: UnparsedMessageEntity): String =
        TransactionIdGenerator.generate(message.senderAddress, message.receivedAt, message.rawBody)

    /**
     * Builds the entity. Pure. The transaction date is the SMS arrival time —
     * bank alerts arrive within seconds of the transaction, and it is the one
     * timestamp on the message that cannot be mistyped.
     *
     * @throws IllegalArgumentException if [entry] fails [validate].
     */
    fun buildTransaction(
        message: UnparsedMessageEntity,
        entry: ManualEntry,
        category: String
    ): TransactionEntity {
        val errors = validate(entry)
        require(errors.isEmpty()) { errors.joinToString(" ") }
        return TransactionEntity(
            transactionId = transactionIdFor(message),
            amount = parseAmount(entry.amountText)!!,
            date = message.receivedAt,
            merchant = entry.merchant.trim(),
            type = entry.type!!,
            accountLast4 = entry.accountLast4.trim(),
            rawRef = entry.rawRef.trim(),
            senderAddress = message.senderAddress,
            rawBody = message.rawBody,
            category = category
        )
    }

    /**
     * Creates the transaction and marks [message] resolved, atomically — a crash
     * between the two must not leave a transaction whose source is still queued.
     *
     * An explicit category on a real merchant is remembered (rule 7); on a generic
     * merchant like "UPI" it applies to this row only.
     */
    suspend fun resolve(
        database: AppDatabase,
        message: UnparsedMessageEntity,
        entry: ManualEntry
    ): Outcome = withContext(Dispatchers.IO) {
        val merchant = entry.merchant.trim()
        val chosen = entry.category.trim()
        val picked = chosen.ifEmpty { CategoryMatcher.getCategory(database, merchant) }
        val category = entry.type?.let { CategoryMatcher.forType(picked, it) } ?: picked
        val transaction = buildTransaction(message, entry, category)

        val outcome = database.withTransaction {
            val rowId = database.transactionDao().insert(transaction)
            database.unparsedMessageDao().markAsResolved(message.id)
            if (chosen.isNotEmpty() && !GenericMerchant.isGeneric(merchant)) {
                MerchantMemory.rememberCategory(database, merchant, chosen)
            }
            // IGNORE conflict strategy returns -1 when the transaction_id exists.
            if (rowId == -1L) Outcome.ALREADY_EXISTS else Outcome.CREATED
        }
        Log.i(TAG, "Resolved unparsed message ${message.id}: $outcome")
        outcome
    }

    /** Marks an SMS that is not a transaction (OTP, balance alert) as handled. */
    suspend fun dismiss(database: AppDatabase, messageId: Long) {
        database.unparsedMessageDao().markAsResolved(messageId)
        Log.i(TAG, "Dismissed unparsed message $messageId")
    }
}
