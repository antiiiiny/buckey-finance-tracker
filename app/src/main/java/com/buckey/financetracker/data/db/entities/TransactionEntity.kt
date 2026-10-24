package com.buckey.financetracker.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["transaction_id"], unique = true),
        Index(value = ["date"])
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Stable hash of sender + timestamp + raw body — used for Sheets dedup */
    @ColumnInfo(name = "transaction_id")
    val transactionId: String,

    @ColumnInfo(name = "amount")
    val amount: Double,

    /** Epoch millis */
    @ColumnInfo(name = "date")
    val date: Long,

    @ColumnInfo(name = "merchant")
    val merchant: String = "",

    @ColumnInfo(name = "type")
    val type: TransactionType,

    @ColumnInfo(name = "account_last4")
    val accountLast4: String = "",

    /** Transaction reference number from the SMS (RRN, UPI ref, etc.) */
    @ColumnInfo(name = "raw_ref")
    val rawRef: String = "",

    @ColumnInfo(name = "sender_address")
    val senderAddress: String,

    /** Raw SMS body — never logged, never sent over network */
    @ColumnInfo(name = "raw_body")
    val rawBody: String,

    @ColumnInfo(name = "category")
    val category: String? = null,

    @ColumnInfo(name = "synced_to_sheets")
    val syncedToSheets: Boolean = false,

    /**
     * True when the category changed after this row reached the Sheet, so the
     * next sync rewrites its Category cell in place (rule 10, amended
     * 2026-10-24). Every DAO write to `category` sets it.
     */
    @ColumnInfo(name = "category_dirty", defaultValue = "0")
    val categoryDirty: Boolean = false,

    /**
     * Who set [category]: [CategorizedBy.AUTO] (a rule, when the row arrived),
     * [CategorizedBy.MANUAL] (the user, in the app) or [CategorizedBy.DEVELOPMENT]
     * (a rules-file import). Manual rows are never relabelled by an import.
     */
    @ColumnInfo(name = "categorized_by", defaultValue = CategorizedBy.AUTO)
    val categorizedBy: String = CategorizedBy.AUTO,

    /**
     * How this row ties into a loan, a holding or the wallet ([LinkKind]); null
     * for an ordinary row. Set only by data/wallet/Links, on the user's choice.
     */
    @ColumnInfo(name = "link_kind")
    val linkKind: String? = null,

    /**
     * The loan or holding this row belongs to: the `transaction_id` of the row
     * that opened it (the Lend debit or the Holding credit).
     */
    @ColumnInfo(name = "link_group")
    val linkGroup: String? = null,

    /** Who the loan or holding is with, as the user typed it. */
    @ColumnInfo(name = "counterparty")
    val counterparty: String? = null,

    /**
     * For [LinkKind.HOLD_CLEAR], the holding's remaining amount (rupees) when it
     * was cleared, so the leftover or overspend stays reproducible.
     */
    @ColumnInfo(name = "link_amount")
    val linkAmount: Double? = null,

    /** Epoch millis when this row was created */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/** Values of [TransactionEntity.linkKind] (wallet and holdings, 2026-10-25). */
object LinkKind {
    /** A Transfer debit the user confirmed came back as cash: wallet +. */
    const val CASH = "CASH"

    /** A debit lent to [TransactionEntity.counterparty]; opens a loan. */
    const val LEND = "LEND"

    /** A credit paying back the loan in [TransactionEntity.linkGroup]. */
    const val LEND_REPAID = "LEND_REPAID"

    /** A credit held for someone; opens a holding. */
    const val HOLD_IN = "HOLD_IN"

    /** A debit paid out of the holding in [TransactionEntity.linkGroup]. */
    const val HOLD_OUT = "HOLD_OUT"

    /** A debit that pays out and closes the holding, whatever was left. */
    const val HOLD_CLEAR = "HOLD_CLEAR"

    /** Holding rows live in the Sheet's Holdings tab, not Transactions (rule 10). */
    val HOLDING: Set<String> = setOf(HOLD_IN, HOLD_OUT, HOLD_CLEAR)
}

/** Values of [TransactionEntity.categorizedBy]; also written to the Sheet. */
object CategorizedBy {
    const val AUTO = "auto"
    const val MANUAL = "manual"
    const val DEVELOPMENT = "development"
}
