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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.summary.GroupKind
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.summary.LedgerResult
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.data.wallet.LinkSuggestion
import com.buckey.financetracker.data.wallet.LinkSuggestions
import com.buckey.financetracker.data.wallet.Links
import com.buckey.financetracker.ui.components.Figure
import com.buckey.financetracker.ui.components.FigurePair
import com.buckey.financetracker.ui.components.GroupHeader
import com.buckey.financetracker.ui.components.PersonDialog
import com.buckey.financetracker.ui.components.PreviewData
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.SectionHeader
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.components.TransactionRow
import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.format.MoneyFormat
import com.buckey.financetracker.ui.theme.Sizes
import com.buckey.financetracker.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Everything Pending can do; supplied by [PendingScreen], faked in previews. */
data class PendingActions(
    val onMessageTap: (Long) -> Unit = {},
    val onTransactionTap: (Long) -> Unit = {},
    val onCash: (TransactionEntity) -> Unit = {},
    val onLend: (TransactionEntity, String) -> Unit = { _, _ -> },
    val onAccept: (LinkSuggestion) -> Unit = {},
    val onDismiss: (LinkSuggestion) -> Unit = {}
)

/** What Pending lists, worked out from all rows by [pendingState]. */
data class PendingState(
    val transferDecisions: List<TransactionEntity>,
    val suggestions: List<LinkSuggestion>,
    val uncategorized: List<TransactionEntity>
)

/** Pure, so the screen's lists follow the ledger exactly. */
fun pendingState(rows: List<TransactionEntity>, ledger: LedgerResult, tolerancePaise: Long, dismissed: Set<String>) =
    PendingState(
        transferDecisions = rows.filter { Ledger.needsTransferDecision(it, ledger.walletStart) },
        suggestions = LinkSuggestions.find(rows, ledger, tolerancePaise, dismissed),
        uncategorized = rows.filter(MonthSummary::isUncategorized)
    )

/**
 * Pending: everything waiting on the user, laid out like Home: two counts on
 * top, then one clearly headed section each (redesign 2026-10-24, wallet and
 * holdings 2026-10-25).
 *
 * - **Transfer or lend?** — Transfer debits since the wallet started: Cash
 *   (they handed back cash) or Lend (they owe it).
 * - **Settle a holding or loan?** — payments within the match range of an open
 *   holding, credits within it of an open loan. Only suggestions: Clear links,
 *   Not this hides it.
 * - **SMS to resolve** — whitelisted SMS the parser could not read (Stage 6,
 *   rule 5). The raw body is withheld here (rule 12).
 * - **To categorize** — "Uncategorized" rows by day; a tap opens the editor.
 */
@Composable
fun PendingScreen(
    database: AppDatabase,
    snackbar: SnackbarHostState,
    onMessageTap: (Long) -> Unit,
    onTransactionTap: (Long) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by database.unparsedMessageDao().getUnresolvedMessages()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val rows by database.transactionDao().getAllTransactions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val zone = remember { ZoneId.systemDefault() }
    val tolerance = remember { HomePreferences.matchTolerancePaise(context) }
    var dismissed by remember { mutableStateOf(HomePreferences.dismissedSuggestions(context)) }
    val ledger = remember(rows) { Ledger.compute(rows) }
    val state = remember(rows, ledger, dismissed) { pendingState(rows, ledger, tolerance, dismissed) }

    PendingContent(
        messages = messages,
        state = state,
        ledger = ledger,
        today = LocalDate.now(zone),
        zone = zone,
        snackbar = snackbar,
        actions = PendingActions(
            onMessageTap = onMessageTap,
            onTransactionTap = onTransactionTap,
            onCash = { tx ->
                scope.launch {
                    Links.markCash(database, tx)
                    snackbar.showSnackbar("Marked as cash in hand", duration = SnackbarDuration.Short)
                }
            },
            onLend = { tx, person ->
                scope.launch {
                    Links.markLend(database, tx, person)
                    snackbar.showSnackbar("Lent to $person", duration = SnackbarDuration.Short)
                }
            },
            onAccept = { s ->
                scope.launch {
                    val done = if (s.group.kind == GroupKind.HOLDING) {
                        Links.payFromHolding(database, s.transaction, s.group, clear = true)
                        "Cleared ${s.group.counterparty}'s holding"
                    } else {
                        Links.repayLoan(database, s.transaction, s.group)
                        "Cleared ${s.group.counterparty}'s loan"
                    }
                    snackbar.showSnackbar(done, duration = SnackbarDuration.Short)
                }
            },
            onDismiss = { s ->
                HomePreferences.dismissSuggestion(context, s.transaction.transactionId, s.group.id)
                dismissed = HomePreferences.dismissedSuggestions(context)
            }
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingContent(
    messages: List<UnparsedMessageEntity>,
    state: PendingState,
    ledger: LedgerResult,
    today: LocalDate,
    zone: ZoneId,
    actions: PendingActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }
) {
    var lending by remember { mutableStateOf<TransactionEntity?>(null) }
    val toSort = state.uncategorized.size + state.transferDecisions.size

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text("Pending") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            item(key = "summary") {
                FigurePair(
                    start = { modifier ->
                        Figure(label = "SMS to resolve", value = "${messages.size}", emphasise = false, modifier = modifier)
                    },
                    end = { modifier ->
                        Figure(label = "To sort out", value = "$toSort", emphasise = false, modifier = modifier)
                    }
                )
            }

            if (messages.isEmpty() && toSort == 0 && state.suggestions.isEmpty()) {
                item(key = "nothing") {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = Spacing.md),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    NothingPending()
                }
                return@LazyColumn
            }

            if (state.transferDecisions.isNotEmpty()) {
                item(key = "transfer-header") {
                    SectionHeader(
                        title = "Transfer or lend?",
                        subtitle = "Money sent to someone. Cash: they gave you cash back. Lend: they owe you.",
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                items(state.transferDecisions, key = { "decide-${it.id}" }) { tx ->
                    ChoiceRow(tx, DateLabels.shortDate(tx.date, zone, today), actions.onTransactionTap) {
                        OutlinedButton(onClick = { actions.onCash(tx) }) { Text("Cash") }
                        OutlinedButton(onClick = { lending = tx }) { Text("Lend") }
                    }
                }
            }

            if (state.suggestions.isNotEmpty()) {
                item(key = "suggest-header") {
                    SectionHeader(
                        title = "Settle a holding or loan?",
                        subtitle = "These amounts are close to money you're holding or are owed.",
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                items(state.suggestions, key = { "suggest-${it.transaction.id}" }) { s ->
                    ChoiceRow(s.transaction, DateLabels.shortDate(s.transaction.date, zone, today), actions.onTransactionTap,
                        note = suggestionNote(s)) {
                        Button(onClick = { actions.onAccept(s) }) {
                            Text(if (s.group.kind == GroupKind.HOLDING) "Clear holding" else "Clear loan")
                        }
                        TextButton(onClick = { actions.onDismiss(s) }) { Text("Not this") }
                    }
                }
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
                        onClick = { actions.onMessageTap(message.id) }
                    )
                }
            }

            if (state.uncategorized.isNotEmpty()) {
                item(key = "uncategorized-header") {
                    SectionHeader(
                        title = "To categorize",
                        subtitle = "Transactions no rule matched. Tap one to pick a category.",
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
                MonthSummary.byDay(state.uncategorized, zone, ledger = ledger).forEach { group ->
                    item(key = "day-${group.key}") {
                        GroupHeader(title = DateLabels.day(group.key, today), trailing = null)
                    }
                    items(group.transactions, key = { "tx-${it.id}" }) { tx ->
                        TransactionRow(
                            transaction = tx,
                            whenText = DateLabels.time(tx.date, zone),
                            onClick = { actions.onTransactionTap(tx.id) }
                        )
                    }
                }
            }
        }
    }

    lending?.let { tx ->
        PersonDialog(
            title = "Who did you lend ${MoneyFormat.rupees(Math.round(tx.amount * 100))} to?",
            confirmLabel = "Lend",
            onConfirm = { person -> actions.onLend(tx, person); lending = null },
            onDismiss = { lending = null }
        )
    }
}

/** "A Friend's holding: ₹5,000 left. Clearing leaves you ₹5." */
private fun suggestionNote(s: LinkSuggestion): String {
    val left = s.group.remainingPaise
    val diff = left - Math.round(s.transaction.amount * 100)
    return if (s.group.kind == GroupKind.HOLDING) {
        "${s.group.counterparty}'s holding: ${MoneyFormat.rupees(left)} left." + when {
            diff > 0 -> " Clearing makes the ${MoneyFormat.rupees(diff)} left over yours."
            diff < 0 -> " The ${MoneyFormat.rupees(-diff)} extra counts as spent."
            else -> ""
        }
    } else {
        "${s.group.counterparty} owes you ${MoneyFormat.rupees(left)}."
    }
}

/** A transaction row with its choices underneath. */
@Composable
private fun ChoiceRow(
    tx: TransactionEntity,
    whenText: String,
    onTap: (Long) -> Unit,
    note: String? = null,
    buttons: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TransactionRow(transaction = tx, whenText = whenText, onClick = { onTap(tx.id) })
        Column(
            modifier = Modifier.padding(start = Spacing.lg + Sizes.avatar + Spacing.md, end = Spacing.lg, bottom = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { buttons() }
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
    val held = PreviewData.tx("A FRIEND", 5000.0, TransactionType.CREDIT, "Holding", 3, 10)
        .let { it.copy(linkKind = LinkKind.HOLD_IN, linkGroup = it.transactionId, counterparty = "A Friend") }
    val rows = listOf(
        PreviewData.tx("Opening cash", 168.0, TransactionType.CREDIT, "Wallet Adjustment", 4, 9)
            .copy(senderAddress = "CASH"),
        held,
        PreviewData.tx("UPI", 500.0, TransactionType.DEBIT, "Transfer", 1, 18),
        PreviewData.tx("UPI", 4995.0, TransactionType.DEBIT, "Uncategorized", 0, 11),
        PreviewData.tx("UPI", 330.0, TransactionType.DEBIT, "Uncategorized", 1, 10)
    )
    val ledger = Ledger.compute(rows)
    PreviewSurface {
        PendingContent(
            messages = previewMessages,
            state = pendingState(rows, ledger, Ledger.CLEAR_TOLERANCE_PAISE, emptySet()),
            ledger = ledger,
            today = PreviewData.today,
            zone = PreviewData.zone,
            actions = PendingActions()
        )
    }
}

@ThemePreviews
@Composable
private fun PendingEmptyPreview() {
    PreviewSurface {
        PendingContent(
            messages = emptyList(),
            state = PendingState(emptyList(), emptyList(), emptyList()),
            ledger = Ledger.compute(emptyList()),
            today = PreviewData.today,
            zone = PreviewData.zone,
            actions = PendingActions()
        )
    }
}
