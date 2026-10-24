package com.buckey.financetracker.ui.screens.CategoryEditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.category.CategoryUpdater
import com.buckey.financetracker.data.category.CategoryUpdater.Scope
import com.buckey.financetracker.data.category.GenericMerchant
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.CategorizedBy
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.summary.GroupKind
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.summary.LedgerResult
import com.buckey.financetracker.data.summary.LinkGroup
import com.buckey.financetracker.data.summary.toPaise
import com.buckey.financetracker.data.wallet.Links
import com.buckey.financetracker.data.wallet.Wallet
import com.buckey.financetracker.ui.components.PersonDialog
import com.buckey.financetracker.ui.components.PreviewData
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.format.MoneyFormat
import com.buckey.financetracker.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

private const val CUSTOM_OPTION = "＋ Add custom category..."

/** Everything the editor can do; supplied by [CategoryEditorScreen]. */
data class EditorActions(
    val onCash: () -> Unit = {},
    val onLend: (String) -> Unit = {},
    val onHolding: (String) -> Unit = {},
    val onPayFromHolding: (LinkGroup, Boolean) -> Unit = { _, _ -> },
    val onRepayLoan: (LinkGroup) -> Unit = {},
    val onSaveCategory: (String, Scope) -> Unit = { _, _ -> }
)

/**
 * Category editor. Besides the plain category (three scopes, see
 * [CategoryUpdater]), a bank row can be tied to money with someone
 * (2026-10-25): a debit is cash in hand, a loan, or a payout from a holding; a
 * credit is money held for someone or a loan paid back. A payout or credit
 * within the matching range of a holding or loan is suggested, never assumed:
 * a Transfer credit only clears a loan when the user says so.
 */
@Composable
fun CategoryEditorScreen(
    database: AppDatabase,
    transactionId: Long,
    onBack: () -> Unit,
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var ledger by remember { mutableStateOf<LedgerResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val tolerance = remember { HomePreferences.matchTolerancePaise(context) }
    val zone = remember { ZoneId.systemDefault() }

    LaunchedEffect(transactionId, version) {
        withContext(Dispatchers.IO) {
            transaction = database.transactionDao().getById(transactionId)
            ledger = Ledger.compute(database.transactionDao().getAllOldestFirst())
        }
    }

    // Success hands [done] to the caller, which returns to Home with a popup
    // (user, 2026-10-25); a refused link stays here with the reason.
    fun act(done: String, block: suspend (TransactionEntity) -> Unit) {
        val tx = transaction ?: return
        scope.launch {
            try {
                block(tx)
                onDone(done)
            } catch (e: IllegalArgumentException) {
                message = e.message
                version++
            }
        }
    }

    CategoryEditorContent(
        transaction = transaction,
        ledger = ledger,
        tolerancePaise = tolerance,
        zone = zone,
        message = message,
        onBack = onBack,
        actions = EditorActions(
            onCash = { act("Marked as cash in hand") { Links.markCash(database, it) } },
            onLend = { person -> act("Lent to $person") { Links.markLend(database, it, person) } },
            onHolding = { person -> act("Holding for $person") { Links.markHolding(database, it, person) } },
            onPayFromHolding = { group, clear ->
                act(if (clear) "Cleared ${group.counterparty}'s holding" else "Paid out of ${group.counterparty}'s holding") {
                    Links.payFromHolding(database, it, group, clear)
                }
            },
            onRepayLoan = { group ->
                act("Cleared ${group.counterparty}'s loan") { Links.repayLoan(database, it, group) }
            },
            onSaveCategory = { category, chosenScope ->
                act("Saved category as $category") { tx ->
                    // A linked row is unlinked by its own edit; anything tied to
                    // it is released first so nothing points at a dead loan or holding.
                    if (tx.linkKind != null) {
                        Links.releaseDependents(database, tx)
                        database.transactionDao().updateCategory(tx.id, category, CategorizedBy.MANUAL)
                    }
                    CategoryUpdater.update(database, tx.copy(linkKind = null), category, chosenScope)
                }
            }
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditorContent(
    transaction: TransactionEntity?,
    ledger: LedgerResult?,
    tolerancePaise: Long,
    zone: ZoneId,
    message: String?,
    onBack: () -> Unit,
    actions: EditorActions
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            val tx = transaction
            if (tx == null || ledger == null) {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            Details(tx, zone)
            LinkStatus(tx, ledger)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (!Wallet.isCash(tx) && tx.linkKind == null) {
                Suggestions(tx, ledger, tolerancePaise, actions)
                MoneyWithSomeone(tx, ledger, actions)
            }
            PlainCategory(tx, actions)
        }
    }
}

@Composable
private fun Details(tx: TransactionEntity, zone: ZoneId) {
    Column {
        Text(tx.merchant.ifEmpty { "Unknown" }, style = MaterialTheme.typography.titleLarge)
        Text(
            DateLabels.dateTime(tx.date, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val kind = when {
            Wallet.isCash(tx) -> "Cash"
            tx.type == TransactionType.DEBIT -> "Debit"
            else -> "Credit"
        }
        Text("${MoneyFormat.rupees(toPaise(tx.amount))} · $kind", style = MaterialTheme.typography.bodyLarge)
    }
}

/** What the row is tied to now, if anything. */
@Composable
private fun LinkStatus(tx: TransactionEntity, ledger: LedgerResult) {
    val group = ledger.groups.firstOrNull { it.id == tx.linkGroup }
    val who = tx.counterparty ?: group?.counterparty ?: ""
    val text = when (tx.linkKind) {
        LinkKind.CASH -> "Cash in hand: this went into your wallet."
        LinkKind.LEND -> "Lent to $who. ${group?.let { "${MoneyFormat.rupees(it.remainingPaise)} still owed." } ?: ""}"
        LinkKind.LEND_REPAID -> "$who paid back their loan."
        LinkKind.HOLD_IN -> "Holding for $who. ${group?.let { "${MoneyFormat.rupees(it.remainingPaise)} left." } ?: ""}"
        LinkKind.HOLD_OUT -> "Paid out of $who's holding."
        LinkKind.HOLD_CLEAR -> "Cleared $who's holding."
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(Spacing.md).fillMaxWidth()) {
            Text(text.trim(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Choosing a plain category below removes this link.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A holding or loan whose remaining amount is within the matching range. */
@Composable
private fun Suggestions(tx: TransactionEntity, ledger: LedgerResult, tolerancePaise: Long, actions: EditorActions) {
    val holding = Ledger.clearSuggestions(ledger, tx, tolerancePaise).firstOrNull()
    val loan = Ledger.repaySuggestions(ledger, tx, tolerancePaise).firstOrNull()
    val group = holding ?: loan ?: return
    val diff = group.remainingPaise - toPaise(tx.amount)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(Spacing.md).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                if (group.kind == GroupKind.HOLDING)
                    "This looks like ${group.counterparty}'s holding (${MoneyFormat.rupees(group.remainingPaise)} left)." +
                        when {
                            diff > 0 -> " Clearing makes the ${MoneyFormat.rupees(diff)} left over yours."
                            diff < 0 -> " The ${MoneyFormat.rupees(-diff)} extra counts as spent."
                            else -> ""
                        }
                else "This looks like ${group.counterparty} paying back ${MoneyFormat.rupees(group.remainingPaise)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Button(onClick = {
                if (group.kind == GroupKind.HOLDING) actions.onPayFromHolding(group, true) else actions.onRepayLoan(group)
            }) { Text(if (group.kind == GroupKind.HOLDING) "Clear holding" else "Clear loan") }
        }
    }
}

/** Cash, lend and holding options for a bank row with no link yet. */
@Composable
private fun MoneyWithSomeone(tx: TransactionEntity, ledger: LedgerResult, actions: EditorActions) {
    var asking by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<GroupKind?>(null) }
    val openHoldings = ledger.holdings.filter { it.isOpen && it.openedAt <= tx.date }
    val openLoans = ledger.loans.filter { it.isOpen && it.openedAt <= tx.date }

    SectionTitle("Money with someone")
    if (tx.type == TransactionType.DEBIT) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = actions.onCash) { Text("Cash in hand") }
            OutlinedButton(onClick = { asking = "lend" }) { Text("Lend…") }
        }
        if (openHoldings.isNotEmpty()) {
            OutlinedButton(onClick = { picking = GroupKind.HOLDING }) { Text("Pay out of a holding…") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedButton(onClick = { asking = "hold" }) { Text("Holding…") }
            if (openLoans.isNotEmpty()) {
                OutlinedButton(onClick = { picking = GroupKind.LOAN }) { Text("Loan paid back…") }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    when (asking) {
        "lend" -> PersonDialog(
            title = "Who did you lend this to?",
            confirmLabel = "Lend",
            onConfirm = { actions.onLend(it); asking = null },
            onDismiss = { asking = null }
        )
        "hold" -> PersonDialog(
            title = "Who are you holding this for?",
            confirmLabel = "Hold",
            initial = tx.merchant.takeUnless { GenericMerchant.isGeneric(it) }.orEmpty(),
            onConfirm = { actions.onHolding(it); asking = null },
            onDismiss = { asking = null }
        )
    }
    picking?.let { kind ->
        GroupPicker(
            kind = kind,
            groups = if (kind == GroupKind.HOLDING) openHoldings else openLoans,
            onPick = { group, clear ->
                if (kind == GroupKind.HOLDING) actions.onPayFromHolding(group, clear) else actions.onRepayLoan(group)
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
}

/** Choose which holding (and whether to clear it) or which loan. */
@Composable
private fun GroupPicker(
    kind: GroupKind,
    groups: List<LinkGroup>,
    onPick: (LinkGroup, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var chosen by remember { mutableStateOf(groups.firstOrNull()) }
    var clear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == GroupKind.HOLDING) "Pay out of whose holding?" else "Whose loan is this?") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                groups.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = chosen == group, onClick = { chosen = group }, role = Role.RadioButton)
                            .minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = chosen == group, onClick = null)
                        Text(
                            "${group.counterparty} · ${MoneyFormat.rupees(group.remainingPaise)} left",
                            modifier = Modifier.padding(start = Spacing.md)
                        )
                    }
                }
                if (kind == GroupKind.HOLDING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = clear, onValueChange = { clear = it }, role = Role.Checkbox)
                            .minimumInteractiveComponentSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = clear, onCheckedChange = null)
                        Text("Clear it, whatever is left", modifier = Modifier.padding(start = Spacing.md))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onPick(it, clear) } }, enabled = chosen != null) { Text("Link") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The plain category, at one of three scopes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainCategory(tx: TransactionEntity, actions: EditorActions) {
    val isCredit = tx.type == TransactionType.CREDIT
    val options = when {
        Wallet.isCash(tx) -> Categories.CASH_PRESETS
        isCredit -> Categories.CREDIT_PRESETS
        else -> Categories.DEBIT_PRESETS
    }
    var selected by remember(tx.id, tx.category) {
        mutableStateOf(tx.category?.takeIf { it in options } ?: "")
    }
    var custom by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(Scope.THIS_AND_LATER) }

    SectionTitle("Category")
    ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = !menu }) {
        OutlinedTextField(
            value = if (showCustom) custom else selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    selected = option; showCustom = false; custom = ""; menu = false
                })
            }
            if (!isCredit) {
                DropdownMenuItem(
                    text = { Text(CUSTOM_OPTION, color = MaterialTheme.colorScheme.primary) },
                    onClick = { showCustom = true; menu = false }
                )
            }
        }
    }
    if (showCustom) {
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            label = { Text("Custom category") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (!Wallet.isCash(tx)) {
        ScopeChooser(
            merchant = tx.merchant,
            generic = GenericMerchant.isGeneric(tx.merchant),
            selected = CategoryUpdater.effectiveScope(tx, scope),
            onSelect = { scope = it }
        )
    }
    val category = if (showCustom) custom.trim() else selected
    Button(
        onClick = { actions.onSaveCategory(category, if (Wallet.isCash(tx)) Scope.THIS_ONLY else scope) },
        enabled = category.isNotEmpty(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save category") }
}

/**
 * How far a category change reaches; see [CategoryUpdater]. For a generic
 * merchant ("UPI") only the first option is offered, since one debit's payee
 * says nothing about the next one's.
 */
@Composable
private fun ScopeChooser(merchant: String, generic: Boolean, selected: Scope, onSelect: (Scope) -> Unit) {
    val name = merchant.ifEmpty { "this merchant" }
    val options = listOf(
        Scope.THIS_ONLY to "Only this transaction",
        Scope.THIS_AND_LATER to "This and every later transaction from \"$name\"",
        Scope.ALL to "Every transaction from \"$name\""
    )
    Column(modifier = Modifier.selectableGroup()) {
        Text("Apply to", style = MaterialTheme.typography.labelLarge)
        options.forEach { (option, label) ->
            val enabled = option == Scope.THIS_ONLY || !generic
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == option, enabled = enabled, role = Role.RadioButton) {
                        onSelect(option)
                    }
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == option, onClick = null, enabled = enabled)
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = Spacing.sm))
            }
        }
        if (generic) {
            Text(
                "\"UPI\" debits carry no payee, so each is categorized on its own. " +
                    "Importing a bank statement fills in the name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

// ==================== Previews (fake data only) ====================

@ThemePreviews
@Composable
private fun EditorDebitPreview() {
    val rows = PreviewData.withMoney
    val payout = PreviewData.tx("UPI", 4995.0, TransactionType.DEBIT, "Uncategorized", 0, 12)
    PreviewSurface {
        CategoryEditorContent(
            transaction = payout,
            ledger = Ledger.compute(rows + payout),
            tolerancePaise = Ledger.CLEAR_TOLERANCE_PAISE,
            zone = PreviewData.zone,
            message = null,
            onBack = {},
            actions = EditorActions()
        )
    }
}

@ThemePreviews
@Composable
private fun EditorLinkedPreview() {
    val rows = PreviewData.withMoney
    val held = rows.first { it.linkKind == LinkKind.HOLD_IN }
    PreviewSurface {
        CategoryEditorContent(
            transaction = held,
            ledger = Ledger.compute(rows),
            tolerancePaise = Ledger.CLEAR_TOLERANCE_PAISE,
            zone = PreviewData.zone,
            message = "Holding for A Friend.",
            onBack = {},
            actions = EditorActions()
        )
    }
}
