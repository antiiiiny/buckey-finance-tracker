package com.buckey.financetracker.ui.screens.SyncStatus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.sheets.SheetsSyncWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(
    database: AppDatabase,
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val allTransactions by database.transactionDao().getAllTransactions()
        .collectAsState(initial = emptyList())
    val unsyncedCount = allTransactions.count { !it.syncedToSheets }
    val syncedCount = allTransactions.count { it.syncedToSheets }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync Status") },
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
            // Google Sign-In status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Google Account",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isSignedIn) "Signed in" else "Not signed in",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSignedIn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = if (isSignedIn) onSignOut else onSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSignedIn) "Sign Out" else "Sign In with Google")
                    }
                }
            }

            // Sync stats card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Transaction Sync",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "$syncedCount",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Synced",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "$unsyncedCount",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (unsyncedCount > 0) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Pending",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            SheetsSyncWorker.enqueueOneShot(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isSignedIn && unsyncedCount > 0
                    ) {
                        Text("Sync Now")
                    }
                }
            }

            // Info text
            if (!isSignedIn) {
                Text(
                    text = "Sign in with Google to sync your transactions " +
                            "to the configured Google Sheet. Sync runs " +
                            "automatically every 15 minutes when signed in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}