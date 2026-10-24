package com.buckey.financetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.data.summary.toPaise
import com.buckey.financetracker.ui.format.MoneyFormat
import com.buckey.financetracker.ui.theme.Spacing

/**
 * One transaction, two lines: merchant and amount, then category and [whenText]
 * (a time inside a day group, a date elsewhere). Debits are plain; credits are
 * "+₹" in the primary green, so the two differ by sign as well as color.
 */
@Composable
fun TransactionRow(
    transaction: TransactionEntity,
    whenText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val credit = transaction.type == TransactionType.CREDIT
    val paise = toPaise(transaction.amount)
    val merchant = transaction.merchant.ifBlank { "Unknown" }
    val category = if (MonthSummary.isUncategorized(transaction)) "Uncategorized"
    else transaction.category.orEmpty()
    val description = (if (credit) "Credit, ${MoneyFormat.spoken(paise)}, from $merchant"
    else "Debit, ${MoneyFormat.spoken(paise)}, to $merchant") + ", $category, $whenText"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Edit category") { onClick(); true }
            }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        CategoryAvatar(category = transaction.category, type = transaction.type)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = merchant,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$category · $whenText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (credit) MoneyFormat.signed(paise) else MoneyFormat.rupees(paise),
            style = MaterialTheme.typography.titleMedium,
            color = if (credit) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Section header with an optional right-hand figure, e.g. "Today  −₹1,120". */
@Composable
fun GroupHeader(title: String, trailing: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
