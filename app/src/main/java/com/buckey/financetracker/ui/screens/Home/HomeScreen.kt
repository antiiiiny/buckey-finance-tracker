package com.buckey.financetracker.ui.screens.Home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.sheets.SheetsSyncWorker
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.summary.BalanceCalculator
import com.buckey.financetracker.data.summary.Ledger
import com.buckey.financetracker.data.summary.LedgerResult
import com.buckey.financetracker.data.wallet.CashBook
import com.buckey.financetracker.data.summary.MonthSummary
import com.buckey.financetracker.data.summary.NetMode
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.data.summary.RunningBalance
import com.buckey.financetracker.data.summary.SpentMode
import com.buckey.financetracker.ui.components.Figure
import com.buckey.financetracker.ui.components.FigurePair
import com.buckey.financetracker.ui.components.GroupHeader
import com.buckey.financetracker.ui.components.PreviewData
import com.buckey.financetracker.ui.components.ProfileAvatar
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.components.TransactionRow
import com.buckey.financetracker.ui.components.rememberMerchantMapImport
import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.format.MoneyFormat
import com.buckey.financetracker.ui.theme.Sizes
import com.buckey.financetracker.ui.theme.Spacing
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How the home list groups rows; switched from the ⋮ menu. */
enum class Grouping { DAY, MONTH }

/** Rows shown under "Latest transactions" when the selected month is empty. */
private const val LATEST_COUNT = 5

/** An unsynced row older than this means sync is failing, not just pending. */
private const val SYNC_STALE_MILLIS = 60 * 60 * 1000L

/**
 * Rows the Sheet should have by now. Signed out, every unsynced row counts:
 * nothing will sync until the user signs in.
 */
fun staleUnsyncedCount(transactions: List<TransactionEntity>, now: Long, isSignedIn: Boolean): Int =
    transactions.count {
        // Holding rows go to the Holdings tab, not Transactions (rule 10).
        !it.syncedToSheets && it.linkKind !in LinkKind.HOLDING &&
            (!isSignedIn || it.createdAt < now - SYNC_STALE_MILLIS)
    }

/** The sheet open over Home, if any. */
enum class HomeSheet { NONE, VERIFY, MONEY, CASH }

/** Everything the home screen can do; supplied by [HomeScreen], faked in previews. */
data class HomeActions(
    val onSelectMonth: (YearMonth) -> Unit = {},
    val onGroupingChange: (Grouping) -> Unit = {},
    val onShowOnlyUncategorized: (Boolean) -> Unit = {},
    val onTransactionTap: (Long) -> Unit = {},
    val onRetrySync: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onImportStatements: () -> Unit = {},
    val onDismissWelcome: () -> Unit = {},
    val onShowSheet: (HomeSheet) -> Unit = {},
    val onAddCash: (amount: Double, category: String, note: String, dateMillis: Long) -> Unit = { _, _, _, _ -> },
    val onRecount: (Double) -> Unit = {},
    val onOpenAccount: () -> Unit = {}
)

/**
 * Home: the month at a glance, then the transactions (design approved
 * 2026-10-24; brief and decisions in .github/design/home-screen.md).
 *
 * Reads Room and preferences and keeps the screen's UI state; [HomeContent]
 * draws it.
 */
@Composable
fun HomeScreen(
    database: AppDatabase,
    snackbar: SnackbarHostState,
    isSignedIn: Boolean,
    onTransactionTap: (Long) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val transactions by database.transactionDao().getAllTransactions()
        .collectAsStateWithLifecycle(initialValue = null)

    // Re-read on every visit: Preferences may have changed them.
    val netMode = remember { HomePreferences.netMode(context) }
    val spentMode = remember { HomePreferences.spentMode(context) }
    val profileName = remember { HomePreferences.profileName(context) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)

    var selectedMonth by rememberSaveable { mutableStateOf(YearMonth.now(zone).toString()) }
    var grouping by rememberSaveable { mutableStateOf(Grouping.DAY) }
    var onlyUncategorized by rememberSaveable { mutableStateOf(false) }
    var welcomeDismissed by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(HomeSheet.NONE) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val importStatements = rememberMerchantMapImport(database) { importMessage = it }

    HomeContent(
        transactions = transactions,
        netMode = netMode,
        spentMode = spentMode,
        selectedMonth = YearMonth.parse(selectedMonth),
        grouping = grouping,
        onlyUncategorized = onlyUncategorized,
        showWelcome = !welcomeDismissed,
        staleUnsynced = transactions?.let { staleUnsyncedCount(it, System.currentTimeMillis(), isSignedIn) } ?: 0,
        isSignedIn = isSignedIn,
        importMessage = importMessage,
        sheet = sheet,
        profileName = profileName,
        today = today,
        zone = zone,
        snackbar = snackbar,
        actions = HomeActions(
            onSelectMonth = { selectedMonth = it.toString(); grouping = Grouping.DAY },
            onGroupingChange = { grouping = it },
            onShowOnlyUncategorized = { onlyUncategorized = it },
            onTransactionTap = onTransactionTap,
            onRetrySync = { SheetsSyncWorker.enqueueOneShot(context) },
            onSignIn = onOpenProfile,
            onImportStatements = importStatements,
            onDismissWelcome = { welcomeDismissed = true },
            onShowSheet = { sheet = it },
            onAddCash = { amount, category, note, date ->
                scope.launch { CashBook.insert(database, CashBook.buildPayment(amount, category, note, date)) }
                sheet = HomeSheet.NONE
            },
            onRecount = { counted ->
                scope.launch {
                    val ledger = Ledger.compute(database.transactionDao().getAllOldestFirst())
                    CashBook.buildCount(
                        counted, ledger.walletPaise, ledger.walletStart != null, System.currentTimeMillis()
                    )?.let { CashBook.insert(database, it) }
                }
            },
            onOpenAccount = onOpenAccount
        )
    )
}

/**
 * Stateless home screen. [transactions] is null while Room loads, so a fresh
 * install's welcome never flashes for a user who has data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    transactions: List<TransactionEntity>?,
    netMode: NetMode,
    spentMode: SpentMode,
    selectedMonth: YearMonth,
    grouping: Grouping,
    onlyUncategorized: Boolean,
    showWelcome: Boolean,
    staleUnsynced: Int,
    isSignedIn: Boolean,
    importMessage: String?,
    sheet: HomeSheet,
    profileName: String,
    today: LocalDate,
    zone: ZoneId,
    actions: HomeActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }
) {
    val rows = transactions.orEmpty()
    val balance = remember(rows) { BalanceCalculator.compute(rows) }
    val ledger = remember(rows) { Ledger.compute(rows) }
    val breakdown = NetBreakdown(balance?.balancePaise, ledger.walletPaise, ledger.lentPaise, ledger.holdingPaise)
    val totals = remember(rows, selectedMonth, spentMode, ledger) {
        MonthSummary.totals(rows, selectedMonth, zone, spentMode, ledger)
    }
    val months = remember(rows) { MonthSummary.months(rows, zone, YearMonth.from(today)) }
    val uncategorized = remember(rows) { rows.filter(MonthSummary::isUncategorized) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            HomeTopBar(
                title = DateLabels.month(selectedMonth, today),
                months = months,
                today = today,
                grouping = grouping,
                profileInitial = profileName.take(1).uppercase(),
                actions = actions
            )
        },
        floatingActionButton = {
            if (transactions != null && (rows.isNotEmpty() || !showWelcome)) {
                ExtendedFloatingActionButton(
                    onClick = { actions.onShowSheet(HomeSheet.CASH) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Cash") }
                )
            }
        }
    ) { innerPadding ->
        if (transactions == null) return@Scaffold

        val netPaise = when (netMode) {
            // Net = bank + wallet + lent - holding (user, 2026-10-25).
            NetMode.RUNNING_BALANCE -> breakdown.netPaise ?: if (rows.isEmpty()) 0L else null
            NetMode.THIS_MONTH -> totals.netPaise
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding
        ) {
            item(key = "summary") {
                val spentWord = if (spentMode == SpentMode.TOTAL) "Total spent" else "Spent"
                FigurePair(
                    start = { modifier ->
                        Figure(
                            label = if (selectedMonth == YearMonth.from(today)) "$spentWord this month"
                            else "$spentWord in ${DateLabels.month(selectedMonth, today)}",
                            value = MoneyFormat.rupees(totals.spentPaise),
                            emphasise = false,
                            modifier = modifier
                        )
                    },
                    end = { modifier ->
                        Figure(
                            label = if (netMode == NetMode.THIS_MONTH) "Net this month" else "Net",
                            value = netPaise?.let(MoneyFormat::rupees) ?: "—",
                            emphasise = (netPaise ?: 0L) >= 0L,
                            modifier = modifier,
                            onClickLabel = "Verify balance",
                            onClick = if (rows.isEmpty()) null else ({ actions.onShowSheet(HomeSheet.VERIFY) })
                        )
                    }
                )
            }
            if (rows.isNotEmpty() && netMode == NetMode.RUNNING_BALANCE) {
                item(key = "breakdown") {
                    BreakdownLine(breakdown, onClick = { actions.onShowSheet(HomeSheet.MONEY) })
                }
            }
            if (uncategorized.isNotEmpty()) {
                item(key = "uncategorized") {
                    UncategorizedLine(
                        count = uncategorized.size,
                        filtering = onlyUncategorized,
                        onToggle = { actions.onShowOnlyUncategorized(!onlyUncategorized) }
                    )
                }
            }
            if (staleUnsynced > 0) {
                item(key = "sync") {
                    SyncBanner(
                        count = staleUnsynced,
                        isSignedIn = isSignedIn,
                        onAction = if (isSignedIn) actions.onRetrySync else actions.onSignIn
                    )
                }
            }
            item(key = "divider") {
                HorizontalDivider(
                    modifier = Modifier.padding(top = Spacing.md),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            when {
                rows.isEmpty() && showWelcome -> item(key = "welcome") {
                    Welcome(
                        importMessage = importMessage,
                        onImport = actions.onImportStatements,
                        onLater = actions.onDismissWelcome
                    )
                }
                onlyUncategorized && uncategorized.isNotEmpty() ->
                    dayGroups(uncategorized, today, zone, spentMode, ledger, actions.onTransactionTap)
                grouping == Grouping.MONTH ->
                    monthGroups(rows, today, zone, spentMode, ledger, actions.onTransactionTap)
                else -> {
                    val inMonth = rows.filter { MonthSummary.monthOf(it, zone) == selectedMonth }
                    if (inMonth.isNotEmpty()) {
                        dayGroups(inMonth, today, zone, spentMode, ledger, actions.onTransactionTap)
                    } else {
                        item(key = "empty-month") {
                            EmptyMonth(DateLabels.month(selectedMonth, today))
                        }
                        val latest = rows.take(LATEST_COUNT)
                        if (latest.isNotEmpty()) {
                            item(key = "latest-header") {
                                LatestHeader(onSeeAll = { actions.onGroupingChange(Grouping.MONTH) })
                            }
                            items(latest, key = { it.id }) { tx ->
                                TransactionRow(
                                    transaction = tx,
                                    whenText = DateLabels.shortDate(tx.date, zone, today),
                                    onClick = { actions.onTransactionTap(tx.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (sheet != HomeSheet.NONE) {
            val close = { actions.onShowSheet(HomeSheet.NONE) }
            ModalBottomSheet(
                onDismissRequest = close,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                when (sheet) {
                    HomeSheet.VERIFY -> VerifyBalanceContent(balance = balance, zone = zone, onClose = close)
                    HomeSheet.MONEY -> MoneySheetContent(ledger, zone, onRecount = actions.onRecount, onClose = close)
                    HomeSheet.CASH -> CashPaymentContent(today, zone, onSave = actions.onAddCash, onClose = close)
                    HomeSheet.NONE -> Unit
                }
            }
        }
    }
}

private fun LazyListScope.dayGroups(
    rows: List<TransactionEntity>,
    today: LocalDate,
    zone: ZoneId,
    spentMode: SpentMode,
    ledger: LedgerResult,
    onTap: (Long) -> Unit
) {
    MonthSummary.byDay(rows, zone, spentMode, ledger).forEach { group ->
        item(key = "day-${group.key}") {
            GroupHeader(
                title = DateLabels.day(group.key, today),
                trailing = spentTrailing(group.spentPaise)
            )
        }
        items(group.transactions, key = { it.id }) { tx ->
            TransactionRow(
                transaction = tx,
                whenText = DateLabels.time(tx.date, zone),
                onClick = { onTap(tx.id) }
            )
        }
    }
}

private fun LazyListScope.monthGroups(
    rows: List<TransactionEntity>,
    today: LocalDate,
    zone: ZoneId,
    spentMode: SpentMode,
    ledger: LedgerResult,
    onTap: (Long) -> Unit
) {
    MonthSummary.byMonth(rows, zone, spentMode, ledger).forEach { group ->
        item(key = "month-${group.key}") {
            GroupHeader(
                title = DateLabels.month(group.key, today),
                trailing = spentTrailing(group.spentPaise)
            )
        }
        items(group.transactions, key = { it.id }) { tx ->
            TransactionRow(
                transaction = tx,
                whenText = DateLabels.shortDate(tx.date, zone, today),
                onClick = { onTap(tx.id) }
            )
        }
    }
}

/**
 * A group's spending as "−₹1,120"; "+₹500" when repayments outran it that day;
 * nothing when it nets to zero.
 */
private fun spentTrailing(spentPaise: Long): String? =
    if (spentPaise == 0L) null else MoneyFormat.signed(-spentPaise)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    title: String,
    months: List<YearMonth>,
    today: LocalDate,
    grouping: Grouping,
    profileInitial: String,
    actions: HomeActions
) {
    var monthMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            // A quiet app name over the month, so it's clear which app this is
            // without taking the title from the month (user's choice, 2026-10-24).
            Column {
                Text(
                    text = "Buckey",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.clickable(onClickLabel = "Choose month", role = Role.Button) {
                        monthMenu = true
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                months.forEach { month ->
                    DropdownMenuItem(
                        text = { Text(DateLabels.month(month, today)) },
                        onClick = {
                            monthMenu = false
                            actions.onSelectMonth(month)
                        }
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { overflowMenu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
            }
            // Both choices always listed; a tick marks the current one.
            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                listOf(Grouping.DAY to "Group by day", Grouping.MONTH to "Group by month")
                    .forEach { (option, label) ->
                        val selected = option == grouping
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = {
                                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
                            },
                            modifier = Modifier.semantics { this.selected = selected },
                            onClick = {
                                overflowMenu = false
                                actions.onGroupingChange(option)
                            }
                        )
                    }
            }
            // The profile, top right (user, 2026-10-25): Profile, Preferences, Bank statement.
            ProfileAvatar(initial = profileInitial, onClick = actions.onOpenAccount)
        }
    )
}

@Composable
private fun UncategorizedLine(count: Int, filtering: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .minimumInteractiveComponentSize()
            .padding(horizontal = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Outlined.Category,
            contentDescription = null,
            modifier = Modifier.size(Sizes.icon),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = if (filtering) "Showing $count to categorize · Show all" else "$count to categorize",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SyncBanner(count: Int, isSignedIn: Boolean, onAction: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(Sizes.icon)
            )
            Text(
                text = "$count transaction${if (count == 1) "" else "s"} not synced",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) {
                Text(
                    text = if (isSignedIn) "Retry" else "Sign in",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun EmptyMonth(monthLabel: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "No transactions in $monthLabel",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Your transactions will appear here once you start spending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LatestHeader(onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Latest transactions",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        TextButton(onClick = onSeeAll) { Text("See all") }
    }
}

/** First run: nothing captured or imported yet (copy from the approved mockup). */
@Composable
private fun Welcome(importMessage: String?, onImport: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            imageVector = Icons.Outlined.Eco,
            contentDescription = null,
            modifier = Modifier.size(Sizes.illustration),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Welcome to Buckey",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "Start importing your bank statements to see your spending and track your money.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Import statements")
        }
        TextButton(onClick = onLater) { Text("I'll do this later") }
        if (importMessage != null) {
            Text(
                text = importMessage,
                style = MaterialTheme.typography.bodySmall,
                color = if (importMessage.startsWith("Import failed")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The verify sheet: Buckey's balance against the latest SIB SMS balance.
 * [balance] is null when no SMS has quoted a balance yet.
 */
@Composable
fun VerifyBalanceContent(balance: RunningBalance?, zone: ZoneId, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Verify balance",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() }
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close")
            }
        }

        Column(
            modifier = Modifier.padding(end = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (balance == null) {
                Text(
                    text = "No SIB SMS with a balance yet. Buckey starts the running balance " +
                        "from the first one it receives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val check = balance.check
            StatusLine(matches = check.matches, differencePaise = check.differencePaise)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DetailRow("Buckey estimated", MoneyFormat.rupees(check.estimatedPaise))
            DetailRow("Latest SMS balance", MoneyFormat.rupees(check.smsBalancePaise))
            if (!check.matches) {
                DetailRow("Difference", MoneyFormat.rupees(check.differencePaise), error = true)
            }
            DetailRow("SMS date", DateLabels.dateTime(check.smsDate, zone))
            InfoBox(
                text = if (check.matches) {
                    "This includes all credits and debits since " +
                        "${DateLabels.fullDate(check.anchorDate, zone)} (earliest SMS)."
                } else {
                    "This can happen when SIB sent no SMS for a transaction. Importing your " +
                        "latest statement adds the missing rows."
                },
                error = !check.matches
            )
        }
    }
}

@Composable
private fun StatusLine(matches: Boolean, differencePaise: Long) {
    val accent = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Surface(
            modifier = Modifier.size(Sizes.statusBadge),
            shape = MaterialTheme.shapes.extraLarge,
            color = accent,
            contentColor = if (matches) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onError
        ) {
            Icon(
                imageVector = if (matches) Icons.Filled.Check else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.padding(Spacing.sm)
            )
        }
        Column {
            Text(
                text = if (matches) "Matches" else "Off by ${MoneyFormat.rupees(abs(differencePaise))}",
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
            Text(
                text = if (matches) "Your balance matches the latest SIB SMS balance."
                else "Your balance doesn't match the latest SIB SMS balance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, error: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun InfoBox(text: String, error: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(Sizes.icon)
            )
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ==================== Previews (fake data only) ====================

@Composable
private fun PreviewHome(
    transactions: List<TransactionEntity>,
    staleUnsynced: Int = 0,
    showWelcome: Boolean = true
) {
    PreviewSurface {
        HomeContent(
            transactions = transactions,
            netMode = NetMode.RUNNING_BALANCE,
            spentMode = SpentMode.AFTER_TRANSFERS,
            selectedMonth = YearMonth.from(PreviewData.today),
            grouping = Grouping.DAY,
            onlyUncategorized = false,
            showWelcome = showWelcome,
            staleUnsynced = staleUnsynced,
            isSignedIn = true,
            importMessage = null,
            sheet = HomeSheet.NONE,
            profileName = "Personal",
            today = PreviewData.today,
            zone = PreviewData.zone,
            actions = HomeActions()
        )
    }
}

@ThemePreviews
@Composable
private fun HomeLoadedPreview() = PreviewHome(PreviewData.loaded)

@ThemePreviews
@Composable
private fun HomeEmptyMonthPreview() = PreviewHome(PreviewData.olderOnly)

@ThemePreviews
@Composable
private fun HomeFreshInstallPreview() = PreviewHome(emptyList())

@ThemePreviews
@Composable
private fun HomeSyncErrorPreview() = PreviewHome(PreviewData.loaded, staleUnsynced = 12)

@ThemePreviews
@Composable
private fun VerifyMatchesPreview() {
    PreviewSurface {
        VerifyBalanceContent(
            balance = BalanceCalculator.compute(PreviewData.loaded),
            zone = PreviewData.zone,
            onClose = {}
        )
    }
}

@ThemePreviews
@Composable
private fun VerifyMismatchPreview() {
    PreviewSurface {
        // Drop a row between the two SMS balances: the estimate is off by its amount.
        val mismatch = BalanceCalculator.compute(PreviewData.loaded.filterNot { it.merchant == "AMAZON" })
        VerifyBalanceContent(balance = mismatch, zone = PreviewData.zone, onClose = {})
    }
}
