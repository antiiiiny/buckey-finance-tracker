package com.buckey.financetracker.data.wallet

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategorizedBy
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.sms.TransactionIdGenerator
import com.buckey.financetracker.data.summary.toPaise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Writes the wallet's own rows: cash payments and cash counts (2026-10-25).
 * Builders are pure so the shapes are unit tested; the suspend functions insert.
 */
object CashBook {

    /**
     * A cash payment: Spent in [category], wallet −. [note] becomes the merchant
     * ("Chai"), falling back to the category. Each gets a unique ID, since two
     * identical ₹20 chais on one day are two payments.
     */
    fun buildPayment(
        amountRupees: Double,
        category: String,
        note: String,
        dateMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): TransactionEntity {
        require(amountRupees > 0.0 && amountRupees.isFinite()) { "Enter an amount greater than zero." }
        require(category.isNotBlank()) { "Choose a category." }
        return cashRow(
            amountRupees, TransactionType.DEBIT, note.trim().ifEmpty { category.trim() },
            category.trim(), dateMillis, nowMillis
        )
    }

    /**
     * The row that makes the wallet match the cash the user just counted. The
     * first count opens the wallet ([Wallet.OPENING_MERCHANT]: it starts the
     * wallet, it isn't income); later ones record the difference as a recount.
     * Null when the count already matches.
     */
    fun buildCount(
        countedRupees: Double,
        walletPaise: Long,
        walletStarted: Boolean,
        dateMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): TransactionEntity? {
        require(countedRupees >= 0.0 && countedRupees.isFinite()) { "Enter the cash you have, 0 or more." }
        val counted = toPaise(countedRupees)
        if (!walletStarted) {
            return cashRow(countedRupees, TransactionType.CREDIT, Wallet.OPENING_MERCHANT,
                Categories.WALLET_ADJUSTMENT, dateMillis, nowMillis)
        }
        val difference = counted - walletPaise
        if (difference == 0L) return null
        return cashRow(
            kotlin.math.abs(difference) / 100.0,
            if (difference > 0) TransactionType.CREDIT else TransactionType.DEBIT,
            Wallet.RECOUNT_MERCHANT, Categories.WALLET_ADJUSTMENT, dateMillis, nowMillis
        )
    }

    suspend fun insert(database: AppDatabase, row: TransactionEntity): Long =
        withContext(Dispatchers.IO) { database.transactionDao().insert(row) }

    private fun cashRow(
        amountRupees: Double,
        type: TransactionType,
        merchant: String,
        category: String,
        dateMillis: Long,
        nowMillis: Long
    ) = TransactionEntity(
        transactionId = TransactionIdGenerator.generate(Wallet.CASH_SENDER, nowMillis, UUID.randomUUID().toString()),
        amount = amountRupees,
        date = dateMillis,
        merchant = merchant,
        type = type,
        accountLast4 = Wallet.CASH_ACCOUNT,
        senderAddress = Wallet.CASH_SENDER,
        rawBody = "",
        category = category,
        categorizedBy = CategorizedBy.MANUAL,
        createdAt = nowMillis
    )
}
