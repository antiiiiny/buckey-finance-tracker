package com.buckey.financetracker.ui.screens.Account

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.buckey.financetracker.BuildConfig
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.sheets.SheetsSyncWorker
import com.buckey.financetracker.data.sms.SmsBackfill
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Sheet link; the ID is appended at runtime from BuildConfig. */
private const val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/"

/** What the Profile page shows; built by [ProfileScreen], faked in previews. */
data class ProfileState(
    val profileName: String,
    val isSignedIn: Boolean,
    val syncedCount: Int,
    val unsyncedCount: Int,
    val smsPermissionGranted: Boolean,
    val sheetConfigured: Boolean,
    val message: String?,
    val showClearDialog: Boolean
)

data class ProfileActions(
    val onRename: (String) -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onOpenSheet: () -> Unit = {},
    val onRebuildSheet: () -> Unit = {},
    val onRunBackfill: () -> Unit = {},
    val onClearRequest: () -> Unit = {},
    val onClearConfirm: () -> Unit = {},
    val onClearDismiss: () -> Unit = {}
)

/**
 * Profile (user, 2026-10-25): the profile's name, Google Sheets (what was the
 * Sync tab, plus "Open in Google Sheets"), the data tools and About.
 */
@Composable
fun ProfileScreen(
    database: AppDatabase,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transactions by database.transactionDao().getAllTransactions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var name by remember { mutableStateOf(HomePreferences.profileName(context)) }
    var message by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    // Holding rows live in the Holdings tab, so they never wait for Transactions.
    val forTransactionsTab = transactions.filter { it.linkKind !in LinkKind.HOLDING }

    ProfileContent(
        state = ProfileState(
            profileName = name,
            isSignedIn = isSignedIn,
            syncedCount = forTransactionsTab.count { it.syncedToSheets },
            unsyncedCount = forTransactionsTab.count { !it.syncedToSheets },
            smsPermissionGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED,
            sheetConfigured = BuildConfig.GOOGLE_SHEET_ID.isNotEmpty(),
            message = message,
            showClearDialog = showClearDialog
        ),
        actions = ProfileActions(
            onRename = {
                HomePreferences.setProfileName(context, it)
                name = HomePreferences.profileName(context)
            },
            onSignIn = onSignIn,
            onSignOut = onSignOut,
            onSyncNow = {
                SheetsSyncWorker.enqueueOneShot(context)
                message = "Sync started."
            },
            onOpenSheet = {
                // Hands the link to the Sheets app or the browser; the app itself
                // makes no request. The ID comes from secrets.properties via
                // BuildConfig and is never written into source.
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(SHEET_URL_PREFIX + BuildConfig.GOOGLE_SHEET_ID))
                    )
                } catch (e: ActivityNotFoundException) {
                    message = "No app on this phone can open a Google Sheets link."
                }
            },
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
        ),
        onBack = onBack
    )
}

@Composable
fun ProfileContent(state: ProfileState, actions: ProfileActions, onBack: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(state.profileName) { mutableStateOf(state.profileName) }

    AccountScaffold(title = "Profile", onBack = onBack) {
        Section("Name") {
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(onClick = { actions.onRename(draft); editing = false }, enabled = draft.isNotBlank()) {
                        Text("Save")
                    }
                    TextButton(onClick = { draft = state.profileName; editing = false }) { Text("Cancel") }
                }
            } else {
                Text(state.profileName, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { editing = true }) { Text("Rename") }
            }
        }

        Section("Google Sheets") {
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
                Button(onClick = actions.onSyncNow, enabled = state.isSignedIn) { Text("Sync now") }
                OutlinedButton(onClick = if (state.isSignedIn) actions.onSignOut else actions.onSignIn) {
                    Text(if (state.isSignedIn) "Sign out" else "Sign in with Google")
                }
            }
            OutlinedButton(
                onClick = actions.onOpenSheet,
                enabled = state.sheetConfigured,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open in Google Sheets") }
            OutlinedButton(
                onClick = actions.onRebuildSheet,
                enabled = state.isSignedIn,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Rebuild Google Sheet") }
        }

        MessageLine(state.message)

        Section("Data") {
            OutlinedButton(onClick = actions.onRunBackfill, modifier = Modifier.fillMaxWidth()) {
                Text("Re-run SMS backfill")
            }
            TextButton(onClick = actions.onClearRequest, modifier = Modifier.fillMaxWidth()) {
                Text("Clear all data", color = MaterialTheme.colorScheme.error)
            }
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
                    "This will delete all transactions, cash entries and unparsed messages " +
                        "from the local database, and reset category rules to the defaults. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = actions.onClearConfirm) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onClearDismiss) { Text("Cancel") } }
        )
    }
}

// ==================== Previews (fake data only) ====================

private val previewState = ProfileState(
    profileName = "Personal",
    isSignedIn = true,
    syncedCount = 120,
    unsyncedCount = 0,
    smsPermissionGranted = true,
    sheetConfigured = true,
    message = null,
    showClearDialog = false
)

@ThemePreviews
@Composable
private fun ProfileSignedInPreview() {
    PreviewSurface { ProfileContent(previewState, ProfileActions(), onBack = {}) }
}

@ThemePreviews
@Composable
private fun ProfileErrorPreview() {
    PreviewSurface {
        ProfileContent(
            previewState.copy(isSignedIn = false, unsyncedCount = 12, message = "No app on this phone can open a Google Sheets link."),
            ProfileActions(),
            onBack = {}
        )
    }
}
