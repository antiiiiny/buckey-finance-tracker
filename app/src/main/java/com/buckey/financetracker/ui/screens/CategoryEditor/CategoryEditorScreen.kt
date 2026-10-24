package com.buckey.financetracker.ui.screens.CategoryEditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.buckey.financetracker.data.category.GenericMerchant
import com.buckey.financetracker.data.category.MerchantMemory
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categories offered in every category picker in the app. */
internal val PRESET_CATEGORIES = listOf(
    "Food", "Transport", "Shopping", "Groceries", "Utilities",
    "Housing", "Income", "Transfer", "Entertainment", "Health",
    "Education", "Travel", "Bills", "Other", "Uncategorized"
)

private const val CUSTOM_OPTION = "＋ Add custom category..."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditorScreen(
    database: AppDatabase,
    transactionId: Long,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedCategory by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customCategory by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Load transaction
    LaunchedEffect(transactionId) {
        val tx = database.transactionDao().getById(transactionId)
        if (tx != null) {
            transaction = tx
            selectedCategory = tx.category ?: "Uncategorized"
        }
    }

    val tx = transaction

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .padding(16.dp)
        ) {
            if (tx == null) {
                Text("Loading...")
                return@Column
            }

            // Transaction details card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = tx.merchant.ifEmpty { "Unknown" },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                    Text(
                        text = dateFormat.format(Date(tx.date)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₹${"%.2f".format(tx.amount)} — ${
                            if (tx.type == TransactionType.DEBIT) "Debit" else "Credit"
                        }",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Category dropdown
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = !dropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedCategory.ifEmpty { customCategory },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    PRESET_CATEGORIES.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category) },
                            onClick = {
                                selectedCategory = category
                                customCategory = ""
                                showCustomInput = false
                                dropdownExpanded = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(CUSTOM_OPTION, color = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            showCustomInput = true
                            dropdownExpanded = false
                        }
                    )
                }
            }

            // Custom category text field
            if (showCustomInput) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customCategory,
                    onValueChange = { customCategory = it },
                    label = { Text("Enter custom category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Save button
            Button(
                onClick = {
                    val categoryToSave = if (customCategory.isNotBlank()) customCategory else selectedCategory
                    scope.launch {
                        if (!GenericMerchant.isGeneric(tx.merchant)) {
                            MerchantMemory.rememberCategory(database, tx.merchant, categoryToSave)
                        }
                        database.transactionDao().updateCategory(tx.id, categoryToSave)
                        selectedCategory = categoryToSave
                        saved = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saved && (selectedCategory.isNotEmpty() || customCategory.isNotBlank())
            ) {
                Text(if (saved) "Saved!" else "Save Category")
            }

            if (saved) {
                Spacer(modifier = Modifier.height(8.dp))
                val displayCategory = if (customCategory.isNotBlank()) customCategory else selectedCategory
                val merchant = tx.merchant
                if (GenericMerchant.isGeneric(merchant)) {
                    Text(
                        text = "Category updated to \"$displayCategory\" for this transaction only. " +
                                "Full categorization will come from bank statement matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Future transactions from \"$merchant\" will " +
                                "automatically be categorized as \"$displayCategory\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
