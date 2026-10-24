package com.buckey.financetracker.ui.screens.Account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.components.rememberMerchantMapImport

/**
 * Bank statement (user, 2026-10-25). Today: import the merchant map the desktop
 * script makes from the statement (Stage 7). Uploading the PDF itself comes later.
 */
@Composable
fun StatementScreen(database: AppDatabase, onBack: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    val import = rememberMerchantMapImport(database) { message = it }
    StatementContent(message, onImport = import, onBack = onBack)
}

@Composable
fun StatementContent(message: String?, onImport: () -> Unit, onBack: () -> Unit) {
    AccountScaffold(title = "Bank statement", onBack = onBack) {
        Section("Merchant map") {
            Text(
                "Run the statement script on your computer, copy merchant_map.json to the " +
                    "phone, then import it here. It names the payees SIB's SMS leave out and " +
                    "adds transactions SIB sent no SMS for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import merchant map") }
            MessageLine(message)
        }
        Section("Statement PDF") {
            Text(
                "Uploading the bank statement PDF straight into Buckey is coming later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@ThemePreviews
@Composable
private fun StatementPreview() {
    PreviewSurface { StatementContent("Applied 84 merchant fixes.", onImport = {}, onBack = {}) }
}
