package com.buckey.financetracker.ui.components

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.ui.theme.FinanceTrackerTheme
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Every screen preview renders in light and dark (CLAUDE.md, "UI rules"). */
@Preview(name = "Light", showBackground = true, heightDp = 780)
@Preview(
    name = "Dark",
    showBackground = true,
    heightDp = 780,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
)
annotation class ThemePreviews

/** Theme plus a background, for previews. */
@Composable
fun PreviewSurface(content: @Composable () -> Unit) {
    FinanceTrackerTheme {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

/**
 * Fake rows for previews. Merchants and people are invented, amounts and
 * references are made up; never real SMS or bank data (rule 12, UI rules).
 */
object PreviewData {

    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    val today: LocalDate = LocalDate.of(2026, 10, 24)

    private var nextId = 1L

    fun tx(
        merchant: String,
        amount: Double,
        type: TransactionType,
        category: String?,
        daysAgo: Long,
        hour: Int,
        minute: Int = 0,
        smsBalance: Double? = null
    ): TransactionEntity {
        val id = nextId++
        val millis = today.minusDays(daysAgo).atTime(LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()
        return TransactionEntity(
            id = id,
            transactionId = "preview-$id",
            amount = amount,
            date = millis,
            merchant = merchant,
            type = type,
            accountLast4 = "0000",
            rawRef = "000000000$id",
            senderAddress = "AD-SIBSMS-S",
            rawBody = smsBalance?.let { "UPI debit:Rs.$amount,A/c X0000, preview RRN:0. Bal:Rs.$it" } ?: "",
            category = category,
            syncedToSheets = true
        )
    }

    val loaded: List<TransactionEntity> by lazy {
        listOf(
            tx("BLINKIT", 450.0, TransactionType.DEBIT, "Groceries", 0, 21, 14, smsBalance = 42310.0),
            tx("ANITA RAO", 2000.0, TransactionType.CREDIT, "Income", 0, 11, 2),
            tx("ZOMATO", 320.0, TransactionType.DEBIT, "Food", 0, 1, 20),
            tx("METRO RECHARGE", 500.0, TransactionType.DEBIT, "Transport", 1, 20, 41),
            tx("UPI", 330.0, TransactionType.DEBIT, "Uncategorized", 1, 10, 12),
            tx("AMAZON", 1299.0, TransactionType.DEBIT, "Shopping", 3, 18, 5),
            tx("RAHUL M", 5000.0, TransactionType.DEBIT, "Transfer", 4, 9, 30),
            // 42,310 + the 5,899 net outflow since: so the verify sheet matches.
            tx("SWIGGY", 420.0, TransactionType.DEBIT, "Food", 5, 13, 45, smsBalance = 48209.0),
        ).sortedByDescending { it.date }
    }

    /** Only older rows, so the selected month (October) is empty. */
    val olderOnly: List<TransactionEntity> by lazy {
        listOf(
            tx("SWIGGY", 420.0, TransactionType.DEBIT, "Food", 30, 13, smsBalance = 42310.0),
            tx("AMAZON", 1299.0, TransactionType.DEBIT, "Shopping", 31, 18),
            tx("INDIGO", 3420.0, TransactionType.DEBIT, "Travel", 32, 7),
        ).sortedByDescending { it.date }
    }
}
