package com.buckey.financetracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.buckey.financetracker.data.category.CategoryRepair
import com.buckey.financetracker.data.sheets.GoogleSignInHelper
import com.buckey.financetracker.data.sheets.SheetsSyncWorker
import com.buckey.financetracker.data.sms.SmsBackfill
import com.buckey.financetracker.ui.screens.CategoryEditor.CategoryEditorScreen
import com.buckey.financetracker.ui.screens.Home.HomeScreen
import com.buckey.financetracker.ui.screens.Pending.PendingScreen
import com.buckey.financetracker.ui.screens.ResolveMessage.ResolveMessageScreen
import com.buckey.financetracker.ui.screens.Settings.SettingsScreen
import com.buckey.financetracker.ui.theme.FinanceTrackerTheme
import com.buckey.financetracker.ui.theme.Spacing
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_SIGN_IN = 9001

        // The three tabs (redesign, 2026-10-24). Sync status lives in Settings.
        private const val ROUTE_HOME = "home"
        private const val ROUTE_PENDING = "pending"
        private const val ROUTE_SETTINGS = "settings"
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
        // Draw behind the system bars (edge-to-edge skill). ComponentActivity's
        // version also picks light or dark bar icons from the theme.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Otherwise three-button navigation gets a translucent scrim over the
        // bottom bar's color.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // Check if SMS permissions are already granted
        smsPermissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        // Check if already signed in to Google
        isSignedIn = GoogleSignInHelper.isSignedIn(this)

        val database = (application as FinanceTrackerApp).database

        // Idempotent; undoes category-rule damage left by older builds.
        lifecycleScope.launch { CategoryRepair.run(this@MainActivity, database) }

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
                        val backStackEntry by navController.currentBackStackEntryAsState()
                        val currentRoute = backStackEntry?.destination?.route
                        val unresolvedMessages by database.unparsedMessageDao()
                            .getUnresolvedMessages()
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        val uncategorized by database.transactionDao()
                            .observeUncategorized()
                            .collectAsStateWithLifecycle(initialValue = emptyList())
                        val pendingCount = unresolvedMessages.size + uncategorized.size

                        // Tabs pop back to Home, so Back from any tab lands there.
                        fun openTab(route: String) {
                            navController.navigate(route) {
                                popUpTo(ROUTE_HOME) { inclusive = route == ROUTE_HOME }
                                launchSingleTop = true
                            }
                        }

                        Scaffold(
                            bottomBar = {
                                NavigationBar {
                                    NavigationBarItem(
                                        selected = currentRoute == ROUTE_HOME,
                                        onClick = { openTab(ROUTE_HOME) },
                                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                                        label = { Text("Home") }
                                    )
                                    NavigationBarItem(
                                        selected = currentRoute == ROUTE_PENDING,
                                        onClick = { openTab(ROUTE_PENDING) },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (pendingCount > 0) {
                                                        Badge { Text("$pendingCount") }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Outlined.Schedule, contentDescription = null)
                                            }
                                        },
                                        label = { Text("Pending") }
                                    )
                                    NavigationBarItem(
                                        selected = currentRoute == ROUTE_SETTINGS,
                                        onClick = { openTab(ROUTE_SETTINGS) },
                                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                        label = { Text("Settings") }
                                    )
                                }
                            }
                        ) { padding ->
                            NavHost(
                                navController = navController,
                                startDestination = ROUTE_HOME,
                                // Consumed so each screen's own Scaffold doesn't pad
                                // for the system bars a second time (edge-to-edge).
                                modifier = Modifier
                                    .padding(padding)
                                    .consumeWindowInsets(padding)
                            ) {
                                composable(ROUTE_HOME) {
                                    HomeScreen(
                                        database = database,
                                        isSignedIn = isSignedIn,
                                        onTransactionTap = { txId ->
                                            navController.navigate("category_editor/$txId")
                                        },
                                        onOpenSettings = { openTab(ROUTE_SETTINGS) }
                                    )
                                }
                                composable(ROUTE_PENDING) {
                                    PendingScreen(
                                        database = database,
                                        onMessageTap = { messageId ->
                                            navController.navigate("resolve_message/$messageId")
                                        },
                                        onTransactionTap = { txId ->
                                            navController.navigate("category_editor/$txId")
                                        }
                                    )
                                }
                                composable(
                                    route = "resolve_message/{messageId}",
                                    arguments = listOf(
                                        navArgument("messageId") { type = NavType.LongType }
                                    )
                                ) { backStackEntry ->
                                    val messageId = backStackEntry.arguments?.getLong("messageId") ?: return@composable
                                    ResolveMessageScreen(
                                        database = database,
                                        messageId = messageId,
                                        onDone = { navController.popBackStack() }
                                    )
                                }
                                composable(ROUTE_SETTINGS) {
                                    SettingsScreen(
                                        database = database,
                                        isSignedIn = isSignedIn,
                                        onSignIn = { launchGoogleSignIn() },
                                        onSignOut = { signOut() }
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
        lifecycleScope.launch {
            backfillCount = SmsBackfill.runBackfill(this@MainActivity)
        }
    }
}

@androidx.compose.runtime.Composable
private fun PermissionPrompt(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(Spacing.xxl)
        ) {
            Text(
                text = "SMS Permission Required",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                text = "Buckey Finance Tracker needs SMS access to read " +
                        "bank transaction messages. Your data stays on-device.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant SMS Permission")
            }
        }
    }
}