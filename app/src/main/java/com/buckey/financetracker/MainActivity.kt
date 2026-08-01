package com.buckey.financetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buckey.financetracker.data.sheets.GoogleSignInHelper
import com.buckey.financetracker.data.sheets.SheetsSyncWorker
import com.buckey.financetracker.data.sms.SmsBackfill
import com.buckey.financetracker.ui.screens.CategoryEditor.CategoryEditorScreen
import com.buckey.financetracker.ui.screens.Settings.SettingsScreen
import com.buckey.financetracker.ui.screens.SyncStatus.SyncStatusScreen
import com.buckey.financetracker.ui.screens.TransactionList.TransactionListScreen
import com.buckey.financetracker.ui.theme.FinanceTrackerTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_SIGN_IN = 9001
    }

    private var smsPermissionGranted by mutableStateOf(false)
    private var backfillCount by mutableStateOf(0)
    private var isSignedIn by mutableStateOf(false)

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        smsPermissionGranted = permissions.values.all { it }
        if (smsPermissionGranted) {
            runBackfill()
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                Log.i(TAG, "Google Sign-In successful: ${account.email}")
                isSignedIn = true
                // Enqueue initial sync
                SheetsSyncWorker.enqueueOneShot(this)
            } catch (e: ApiException) {
                Log.w(TAG, "Google Sign-In failed: code=${e.statusCode}")
                isSignedIn = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if SMS permissions are already granted
        smsPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        // Check if already signed in to Google
        isSignedIn = GoogleSignInHelper.isSignedIn(this)

        val database = (application as FinanceTrackerApp).database

        setContent {
            FinanceTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!smsPermissionGranted) {
                        PermissionPrompt(
                            onRequestPermission = {
                                requestSmsPermission.launch(
                                    arrayOf(
                                        Manifest.permission.RECEIVE_SMS,
                                        Manifest.permission.READ_SMS
                                    )
                                )
                            }
                        )
                    } else {
                        val navController = rememberNavController()
                        var selectedTab by mutableStateOf(0)

                        Scaffold(
                            bottomBar = {
                                BottomAppBar {
                                    NavigationBarItem(
                                        selected = selectedTab == 0,
                                        onClick = {
                                            selectedTab = 0
                                            navController.navigate("transaction_list") {
                                                popUpTo("transaction_list") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        icon = { Icon(Icons.Default.List, contentDescription = "Transactions") },
                                        label = { Text("Transactions") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 1,
                                        onClick = {
                                            selectedTab = 1
                                            navController.navigate("sync_status") {
                                                popUpTo("transaction_list") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Refresh, contentDescription = "Sync") },
                                        label = { Text("Sync") }
                                    )
                                    NavigationBarItem(
                                        selected = selectedTab == 2,
                                        onClick = {
                                            selectedTab = 2
                                            navController.navigate("settings") {
                                                popUpTo("transaction_list") { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        },
                                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                        label = { Text("Settings") }
                                    )
                                }
                            }
                        ) { padding ->
                            NavHost(
                                navController = navController,
                                startDestination = "transaction_list",
                                modifier = Modifier.padding(padding)
                            ) {
                                composable("transaction_list") {
                                    TransactionListScreen(
                                        database = database,
                                        onTransactionTap = { txId ->
                                            navController.navigate("category_editor/$txId")
                                        }
                                    )
                                }
                                composable("sync_status") {
                                    SyncStatusScreen(
                                        database = database,
                                        isSignedIn = isSignedIn,
                                        onSignIn = { launchGoogleSignIn() },
                                        onSignOut = { signOut() }
                                    )
                                }
                                composable("settings") {
                                    SettingsScreen(
                                        database = database,
                                        context = this@MainActivity
                                    )
                                }
                                composable(
                                    route = "category_editor/{transactionId}",
                                    arguments = listOf(
                                        navArgument("transactionId") { type = NavType.LongType }
                                    )
                                ) { backStackEntry ->
                                    val txId = backStackEntry.arguments?.getLong("transactionId") ?: return@composable
                                    CategoryEditorScreen(
                                        database = database,
                                        transactionId = txId,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto-request on first launch
        if (!smsPermissionGranted) {
            requestSmsPermission.launch(
                arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS
                )
            )
        } else if (!SmsBackfill.isBackfillDone(this)) {
            runBackfill()
        }

        // Enqueue initial sync if already signed in
        if (isSignedIn) {
            SheetsSyncWorker.enqueueOneShot(this)
        }
    }

    private fun launchGoogleSignIn() {
        val signInIntent = GoogleSignInHelper.getSignInIntent(this)
        googleSignInLauncher.launch(signInIntent)
    }

    private fun signOut() {
        GoogleSignInHelper.signOut(this)
        isSignedIn = false
    }

    private fun runBackfill() {
        val count = SmsBackfill.runBackfill(this)
        backfillCount = count
    }
}

@androidx.compose.runtime.Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "SMS Permission Required",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Buckey Finance Tracker needs SMS access to read " +
                        "bank transaction messages. Your data stays on-device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant SMS Permission")
            }
        }
    }
}