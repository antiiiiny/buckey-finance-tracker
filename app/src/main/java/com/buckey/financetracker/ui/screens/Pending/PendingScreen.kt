package com.buckey.financetracker.ui.screens.Pending

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.ui.components.Figure
import com.buckey.financetracker.ui.components.FigurePair
import com.buckey.financetracker.ui.components.GroupHeader
import com.buckey.financetracker.ui.components.PreviewData
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.SectionHeader
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.components.TransactionRow
import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.theme.Sizes
import com.buckey.financetracker.ui.theme.Spacing
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pending: everything waiting on the user, laid out like Home (redesign,
 * 2026-10-24): two counts at the top, then one clearly headed section each.
 *
 * - **SMS to resolve** — whitelisted SMS the parser could not read (Stage 6,
 *   rule 5). Rows show sender, failure reason and time only; the raw body is
 *   withheld here (rule 12) and revealed on demand in the resolve screen.
 * - **To categorize** — transactions still "Uncategorized", grouped by day as on
 *   Home; a tap opens the category editor.
 */
@Composable
fun PendingScreen(
    database: AppDatabase,
    onMessageTap: (Long) -> Unit,
    onTransactionTap: (Long) -> Unit
) {
    val messages by database.unparsedMessageDao().getUnresolvedMessages()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val uncategorized by database.transactionDao().observeUncategorized()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val zone = remember { ZoneId.systemDefault() }

    PendingContent(
        messages = messages,
        uncategorized = uncategorized,
        today = LocalDate.now(zone),
        zone = zone,
        onMessageTap = onMessageTap,
        onTransactionTap = onTransactionTap
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingContent(
    messages: List<UnparsedMessageEntity>,
    uncategorized: List<TransactionEntity>,
    today: LocalDate,
    zone: ZoneId,
    onMessageTap: (Long) -> Unit,
    onTransactionTap: (Long) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Pending") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            item(key = "summary") {
                FigurePair(
                    start = { modifier ->
                        Figure(
                            label = "SMS to resolve",
                            value = "${messages.size}",
                            emphasise = false,
                            modifier = modifier
                        )
                    },
                    end = { modifier ->
                        Figure(
                            label = "To categorize",
                            value = "${uncategorized.size}",
                            emphasise = false,
                            modifier = modifier
                        )
                    }
                )
            }

            if (messages.isEmpty() && uncategorized.isEmpty()) {
                item(key = "nothing") {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = Spacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    NothingPending()
                }
                return@LazyColumn
            }

            if (messages.isNotEmpty()) {
                item(key = "messages-header") {
                    SectionHeader(
                        title = "SMS to resolve",
                        subtitle = "Bank SMS Buckey couldn't read. Tap one to enter it by hand.",
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                items(messages, key = { "message-${it.id}" }) { message ->
                    UnparsedMessageRow(
                        message = message,
                        whenText = DateLabels.shortDate(message.receivedAt, zone, today) +
                            ", " + DateLabels.time(message.receivedAt, zone),
                        onClick = { onMessageTap(message.id) }
                    )
                }
            }

            if (uncategorized.isNotEmpty()) {
                item(key = "uncategorized-header") {
                    SectionHeader(
                        title = "To categorize",
                        subtitle = "Transactions no rule matched. Tap one to pick a category.",
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                MonthSummary.byDay(uncategorized, zone).forEach { group ->
                    item(key = "day-${group.key}") {
                        GroupHeader(title = DateLabels.day(group.key, today), trailing = null)
                    }
                    items(group.transactions, key = { "tx-${it.id}" }) { tx ->
                        TransactionRow(
                            transaction = tx,
                            whenText = DateLabels.time(tx.date, zone),
                            onClick = { onTransactionTap(tx.id) }
                        )
                    }
                }
            }
        }
    }
}

/** Same shape as a transaction row: icon circle, two lines, time on the right. */
@Composable
private fun UnparsedMessageRow(message: UnparsedMessageEntity, whenText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Resolve", onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.avatar)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Sms,
                contentDescription = null,
                modifier = Modifier.size(Sizes.icon),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.senderAddress,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = message.failureReason ?: "Couldn't read this SMS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = whenText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NothingPending() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text = "Nothing pending", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Bank SMS Buckey can't read and transactions without a category will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ==================== Previews (fake data only) ====================

private val previewMessages = listOf(
    UnparsedMessageEntity(
        id = 1,
        senderAddress = "AD-SIBSMS-S",
        rawBody = "",
        receivedAt = PreviewData.today.atTime(9, 30).atZone(PreviewData.zone).toInstant().toEpochMilli(),
        failureReason = "No parser matched"
    )
)

@ThemePreviews
@Composable
private fun PendingLoadedPreview() {
    PreviewSurface {
        PendingContent(
            messages = previewMessages,
            uncategorized = listOf(
                PreviewData.tx("UPI", 330.0, TransactionType.DEBIT, "Uncategorized", 1, 10),
                PreviewData.tx("UPI", 120.0, TransactionType.DEBIT, "Uncategorized", 1, 8),
                PreviewData.tx("UPI", 85.0, TransactionType.DEBIT, null, 6, 19)
            ),
            today = PreviewData.today,
            zone = PreviewData.zone,
            onMessageTap = {},
            onTransactionTap = {}
        )
    }
}

@ThemePreviews
@Composable
private fun PendingEmptyPreview() {
    PreviewSurface {
        PendingContent(
            messages = emptyList(),
            uncategorized = emptyList(),
            today = PreviewData.today,
            zone = PreviewData.zone,
            onMessageTap = {},
            onTransactionTap = {}
        )
    }
}
