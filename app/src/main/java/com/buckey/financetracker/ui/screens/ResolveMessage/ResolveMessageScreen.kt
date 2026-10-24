package com.buckey.financetracker.ui.screens.ResolveMessage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.db.entities.UnparsedMessageEntity
import com.buckey.financetracker.data.review.UnparsedResolver
import com.buckey.financetracker.data.category.Categories
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val AUTO_CATEGORY = "Auto (from rules)"

/**
 * Stage 6 manual entry: the user reads an unparsed SMS and types in the fields
 * the parser could not extract. Saving goes through [UnparsedResolver], which
 * creates the transaction and marks the message resolved in one Room transaction.
 *
 * This is the one screen that may show a raw SMS body (rule 12's dedicated raw
 * message view): the user cannot transcribe an amount they cannot see. It stays
 * hidden until explicitly revealed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveMessageScreen(
    database: AppDatabase,
    messageId: Long,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<UnparsedMessageEntity?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<TransactionType?>(null) }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(AUTO_CATEGORY) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var accountLast4 by remember { mutableStateOf("") }
    var rawRef by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messageId) {
        val loaded = database.unparsedMessageDao().getById(messageId)
        if (loaded == null) notFound = true else message = loaded
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resolve Message") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val msg = message
            if (msg == null) {
                Text(if (notFound) "This message no longer exists." else "Loading...")
                return@Column
            }

            MessageHeader(msg, showRaw, onToggleRaw = { showRaw = !showRaw })

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (₹)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == TransactionType.DEBIT,
                    onClick = { type = TransactionType.DEBIT },
                    label = { Text("Debit") }
                )
                FilterChip(
                    selected = type == TransactionType.CREDIT,
                    onClick = { type = TransactionType.CREDIT },
                    label = { Text("Credit") }
                )
            }

            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant / payee") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = !categoryExpanded }
            ) {
                OutlinedTextField(
                    value = category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    (listOf(AUTO_CATEGORY) + (Categories.DEBIT_PRESETS + Categories.CREDIT_PRESETS).distinct()).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                category = option
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = accountLast4,
                onValueChange = { accountLast4 = it.take(4) },
                label = { Text("Account last 4 digits (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = rawRef,
                onValueChange = { rawRef = it },
                label = { Text("Reference / RRN (optional)") },
                supportingText = {
                    Text("Needed for statement matching — copy it from the SMS if present.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            errors.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            resultText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    val entry = UnparsedResolver.ManualEntry(
                        amountText = amountText,
                        type = type,
                        merchant = merchant,
                        category = if (category == AUTO_CATEGORY) "" else category,
                        accountLast4 = accountLast4,
                        rawRef = rawRef
                    )
                    errors = UnparsedResolver.validate(entry)
                    if (errors.isNotEmpty()) return@Button
                    saving = true
                    scope.launch {
                        val outcome = UnparsedResolver.resolve(database, msg, entry)
                        if (outcome == UnparsedResolver.Outcome.ALREADY_EXISTS) {
                            resultText = "A transaction for this SMS already exists. " +
                                    "Marked resolved without adding a duplicate."
                            saving = false
                        } else {
                            onDone()
                        }
                    }
                },
                enabled = !saving && resultText == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Transaction")
            }

            OutlinedButton(
                onClick = {
                    saving = true
                    scope.launch {
                        UnparsedResolver.dismiss(database, msg.id)
                        onDone()
                    }
                },
                enabled = !saving && resultText == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Not a Transaction (dismiss)")
            }
            Text(
                text = "Dismissed messages are kept on the device, just removed from this queue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageHeader(
    message: UnparsedMessageEntity,
    showRaw: Boolean,
    onToggleRaw: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(message.senderAddress, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Received ${dateFormat.format(Date(message.receivedAt))} — " +
                        "saved as the transaction date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onToggleRaw) {
                Text(if (showRaw) "Hide original SMS" else "Show original SMS")
            }
            if (showRaw) {
                Text(
                    text = message.rawBody,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
