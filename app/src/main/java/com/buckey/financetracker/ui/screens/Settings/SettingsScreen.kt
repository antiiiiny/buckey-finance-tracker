package com.buckey.financetracker.ui.screens.Settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckey.financetracker.BuildConfig
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.sheets.SheetsSyncWorker
import com.buckey.financetracker.data.sms.SmsBackfill
import com.buckey.financetracker.data.summary.NetMode
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.data.summary.SpentMode
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.components.rememberMerchantMapImport
import com.buckey.financetracker.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Sheet link; the ID is appended at runtime from BuildConfig. */
private const val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/"

/** What Settings shows; built by [SettingsScreen], faked in previews. */
data class SettingsState(
    val isSignedIn: Boolean,
    val syncedCount: Int,
    val unsyncedCount: Int,
    val netMode: NetMode,
    val spentMode: SpentMode,
    val smsPermissionGranted: Boolean,
    val sheetConfigured: Boolean,
    val message: String?,
    val showClearDialog: Boolean
)

/** Everything Settings can do. */
data class SettingsActions(
    val onSignIn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onNetModeChange: (NetMode) -> Unit = {},
    val onSpentModeChange: (SpentMode) -> Unit = {},
    val onOpenSheet: () -> Unit = {},
    val onImportMerchantMap: () -> Unit = {},
    val onRebuildSheet: () -> Unit = {},
    val onRunBackfill: () -> Unit = {},
    val onClearRequest: () -> Unit = {},
    val onClearConfirm: () -> Unit = {},
    val onClearDismiss: () -> Unit = {}
)

/**
 * Settings, which since the 2026-10-24 redesign also holds Google Sheets sync
 * (the old Sync tab), "Open in Google Sheets", and the home screen's "Net shows"
 * and "Spent shows" choices.
 */
@Composable
fun SettingsScreen(
    database: AppDatabase,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transactions by database.transactionDao().getAllTransactions()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var netMode by remember { mutableStateOf(HomePreferences.netMode(context)) }
    var spentMode by remember { mutableStateOf(HomePreferences.spentMode(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    val importMerchantMap = rememberMerchantMapImport(database) { message = it }

    val state = SettingsState(
        isSignedIn = isSignedIn,
        syncedCount = transactions.count { it.syncedToSheets },
        unsyncedCount = transactions.count { !it.syncedToSheets },
        netMode = netMode,
        spentMode = spentMode,
        smsPermissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED,
        sheetConfigured = BuildConfig.GOOGLE_SHEET_ID.isNotEmpty(),
        message = message,
        showClearDialog = showClearDialog
    )

    SettingsContent(
        state = state,
        actions = SettingsActions(
            onSignIn = onSignIn,
            onSignOut = onSignOut,
            onSyncNow = {
                SheetsSyncWorker.enqueueOneShot(context)
                message = "Sync started."
            },
            onNetModeChange = {
                HomePreferences.setNetMode(context, it)
                netMode = it
            },
            onSpentModeChange = {
                HomePreferences.setSpentMode(context, it)
                spentMode = it
            },
            onOpenSheet = {
                // Hands the link to the Sheets app or the browser; the app itself
                // makes no request. The ID comes from secrets.properties via
                // BuildConfig and is never written into source.
                val uri = Uri.parse(SHEET_URL_PREFIX + BuildConfig.GOOGLE_SHEET_ID)
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: ActivityNotFoundException) {
                    message = "No app on this phone can open a Google Sheets link."
                }
            },
            onImportMerchantMap = importMerchantMap,
            onRebuildSheet = {
                // Backs up the current tab, then rewrites every row from Room in
                // date order (SheetsSyncWorker.rebuild).
                SheetsSyncWorker.requestRebuild(context)
                message = "Rebuilding the Google Sheet in date order. A backup tab is kept."
            },
            onRunBackfill = {
                scope.launch {
                    val count = SmsBackfill.runBackfill(context)
                    message = "Backfill complete: $count messages processed"
                }
            },
            onClearRequest = { showClearDialog = true },
            onClearConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        database.transactionDao().deleteAll()
                        database.categoryDao().deleteAll()
                        database.unparsedMessageDao().deleteAll()
                        // Seeding only runs on database creation, so the starter
                        // rules have to be restored by hand here.
                        AppDatabase.seedDefaultCategories(database)
                    }
                    showClearDialog = false
                }
            },
            onClearDismiss = { showClearDialog = false }
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(state: SettingsState, actions: SettingsActions) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Section("Google Sheets sync") {
                Text(
                    text = if (state.isSignedIn) "Signed in" else "Not signed in",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.isSignedIn) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    text = "${state.syncedCount} synced · ${state.unsyncedCount} waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!state.isSignedIn) {
                    Text(
                        text = "Sign in with Google to sync your transactions to the configured " +
                            "Google Sheet. Sync runs every 15 minutes when signed in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = actions.onSyncNow, enabled = state.isSignedIn) {
                        Text("Sync now")
                    }
                    OutlinedButton(onClick = if (state.isSignedIn) actions.onSignOut else actions.onSignIn) {
                        Text(if (state.isSignedIn) "Sign out" else "Sign in with Google")
                    }
                }
                OutlinedButton(
                    onClick = actions.onOpenSheet,
                    enabled = state.sheetConfigured,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open in Google Sheets")
                }
            }

            Section("Home") {
                Text(
                    text = "Net shows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.selectableGroup()) {
                    ChoiceOption(
                        title = "Running balance",
                        detail = "Your account balance, checked against SIB SMS",
                        selected = state.netMode == NetMode.RUNNING_BALANCE,
                        onSelect = { actions.onNetModeChange(NetMode.RUNNING_BALANCE) }
                    )
                    ChoiceOption(
                        title = "This month's net",
                        detail = "Credits minus debits in the selected month",
                        selected = state.netMode == NetMode.THIS_MONTH,
                        onSelect = { actions.onNetModeChange(NetMode.THIS_MONTH) }
                    )
                }
                Text(
                    text = "Spent shows",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                Column(modifier = Modifier.selectableGroup()) {
                    ChoiceOption(
                        title = "Debits minus transfers",
                        detail = "Leaves out Transfer debits and subtracts money paid back to you",
                        selected = state.spentMode == SpentMode.AFTER_TRANSFERS,
                        onSelect = { actions.onSpentModeChange(SpentMode.AFTER_TRANSFERS) }
                    )
                    ChoiceOption(
                        title = "Total spent",
                        detail = "Every debit, transfers included",
                        selected = state.spentMode == SpentMode.TOTAL,
                        onSelect = { actions.onSpentModeChange(SpentMode.TOTAL) }
                    )
                }
            }

            Section("Data") {
                OutlinedButton(onClick = actions.onImportMerchantMap, modifier = Modifier.fillMaxWidth()) {
                    Text("Import merchant map")
                }
                OutlinedButton(
                    onClick = actions.onRebuildSheet,
                    enabled = state.isSignedIn,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Rebuild Google Sheet")
                }
                OutlinedButton(onClick = actions.onRunBackfill, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-run SMS backfill")
                }
                TextButton(onClick = actions.onClearRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear all data", color = MaterialTheme.colorScheme.error)
                }
            }

            state.message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Import failed")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Section("About") {
                InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                InfoRow("SMS permission", if (state.smsPermissionGranted) "Granted" else "Not granted")
                InfoRow("Google Sheet", if (state.sheetConfigured) "Configured" else "Not configured")
                Text(
                    text = "No data leaves your device except to Google Sheets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.showClearDialog) {
            AlertDialog(
                onDismissRequest = actions.onClearDismiss,
                title = { Text("Clear all data?") },
                text = {
                    Text(
                        "This will delete all transactions and unparsed messages from the " +
                            "local database, and reset category rules to the defaults. This " +
                            "cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions.onClearConfirm) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions.onClearDismiss) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(top = Spacing.sm)
                .semantics { heading() }
        )
        content()
    }
}

@Composable
private fun ChoiceOption(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The row handles the click, so the button itself takes none.
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ==================== Previews (fake data only) ====================

private val previewState = SettingsState(
    isSignedIn = true,
    syncedCount = 120,
    unsyncedCount = 0,
    netMode = NetMode.RUNNING_BALANCE,
    spentMode = SpentMode.AFTER_TRANSFERS,
    smsPermissionGranted = true,
    sheetConfigured = true,
    message = null,
    showClearDialog = false
)

@ThemePreviews
@Composable
private fun SettingsSignedInPreview() {
    PreviewSurface { SettingsContent(previewState, SettingsActions()) }
}

@ThemePreviews
@Composable
private fun SettingsErrorPreview() {
    PreviewSurface {
        SettingsContent(
            previewState.copy(
                isSignedIn = false,
                unsyncedCount = 12,
                netMode = NetMode.THIS_MONTH,
                message = "Import failed: not a merchant map"
            ),
            SettingsActions()
        )
    }
}
