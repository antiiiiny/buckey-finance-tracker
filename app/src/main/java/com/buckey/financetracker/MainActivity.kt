package com.buckey.financetracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import com.buckey.financetracker.data.sms.SmsBackfill
import com.buckey.financetracker.ui.screens.CategoryEditor.CategoryEditorScreen
import com.buckey.financetracker.ui.screens.TransactionList.TransactionListScreen
import com.buckey.financetracker.ui.theme.FinanceTrackerTheme

class MainActivity : ComponentActivity() {

    private var smsPermissionGranted by mutableStateOf(false)
    private var backfillCount by mutableStateOf(0)

    private val requestSmsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        smsPermissionGranted = permissions.values.all { it }
        if (smsPermissionGranted) {
            runBackfill()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if SMS permissions are already granted
        smsPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

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
                        NavHost(
                            navController = navController,
                            startDestination = "transaction_list"
                        ) {
                            composable("transaction_list") {
                                TransactionListScreen(
                                    database = database,
                                    onTransactionTap = { txId ->
                                        navController.navigate("category_editor/$txId")
                                    }
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