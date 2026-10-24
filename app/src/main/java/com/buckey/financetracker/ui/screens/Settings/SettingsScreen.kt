package com.buckey.financetracker.ui.screens.Settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.buckey.financetracker.BuildConfig
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.importer.MerchantMapImporter
import com.buckey.financetracker.data.sheets.GoogleSignInHelper
import com.buckey.financetracker.data.sms.SmsBackfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val MERCHANT_MAP_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    database: AppDatabase,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var showClearDataDialog by remember { mutableStateOf(false) }
    var backfillMessage by remember { mutableStateOf<String?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    // Storage Access Framework: the user picks merchant_map.json themselves, so
    // no storage permission is needed (rule 11 caps what this app may request).
    val importMerchantMap = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importMessage = "Importing…"
        scope.launch {
            importMessage = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                } ?: throw IllegalStateException("Could not open the selected file.")

                MerchantMapImporter.import(database, json).summary()
            } catch (e: MerchantMapImporter.InvalidMapException) {
                "Import failed: ${e.message}"
            } catch (e: Exception) {
                Log.e("SettingsScreen", "Merchant map import failed", e)
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val smsPermissionGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECEIVE_SMS
    ) == PackageManager.PERMISSION_GRANTED

    val isSignedIn = GoogleSignInHelper.isSignedIn(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Info",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    InfoRow("SMS Permission", if (smsPermissionGranted) "Granted" else "Not granted")
                    InfoRow("Google Account", if (isSignedIn) "Signed in" else "Not signed in")
                    InfoRow("Google Sheet ID", BuildConfig.GOOGLE_SHEET_ID.ifEmpty { "Not configured" })
                }
            }

            // Actions card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Actions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                val count = SmsBackfill.runBackfill(context)
                                backfillMessage = "Backfill complete: $count messages processed"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Re-run SMS Backfill")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        // Some document providers report .json as octet-stream or
                        // plain text; filtering on application/json alone can make
                        // the file unselectable.
                        onClick = { importMerchantMap.launch(MERCHANT_MAP_MIME_TYPES) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import Merchant Map")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { showClearDataDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Clear All Data")
                    }
                }
            }

            // Backfill message
            backfillMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Merchant map import result
            importMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Import failed"))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }

            // Version info
            Text(
                text = "Buckey Finance Tracker\nNo data leaves your device except to Google Sheets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Clear data confirmation dialog
        if (showClearDataDialog) {
            AlertDialog(
                onDismissRequest = { showClearDataDialog = false },
                title = { Text("Clear All Data?") },
                text = {
                    Text("This will delete all transactions and unparsed messages " +
                            "from the local database, and reset category rules to " +
                            "the defaults. This cannot be undone.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    database.transactionDao().deleteAll()
                                    database.categoryDao().deleteAll()
                                    database.unparsedMessageDao().deleteAll()
                                    // Seeding only runs on database creation, so the
                                    // starter rules have to be restored by hand here.
                                    AppDatabase.seedDefaultCategories(database)
                                }
                                showClearDataDialog = false
                            }
                        }
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDataDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}