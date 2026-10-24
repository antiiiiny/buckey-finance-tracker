package com.buckey.financetracker.ui.screens.CategoryEditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.category.CategoryUpdater
import com.buckey.financetracker.data.category.CategoryUpdater.Scope
import com.buckey.financetracker.data.category.GenericMerchant
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
    "Housing", "Home", "Room", "Income", "Transfer", "Self Transfer", "Entertainment",
    "Health", "Education", "College", "Scooter", "Travel", "Bills",
    "SMS Charges", "Miscellaneous", "Other", "Uncategorized"
)

private const val CUSTOM_OPTION = "＋ Add custom category..."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryEditorScreen(
    database: AppDatabase,
    transactionId: Long,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var selectedCategory by remember { mutableStateOf("") }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customCategory by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(Scope.THIS_AND_LATER) }
    var updatedCount by remember { mutableStateOf<Int?>(null) }
    val saved = updatedCount != null

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
                .verticalScroll(rememberScrollState())
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
                    // Credits are income or a transfer (CategoryMatcher.forType).
                    val isCredit = tx.type == TransactionType.CREDIT
                    val options = if (isCredit) CategoryMatcher.CREDIT_CATEGORIES else PRESET_CATEGORIES
                    options.forEach { category ->
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
                    if (!isCredit) {
                        DropdownMenuItem(
                            text = { Text(CUSTOM_OPTION, color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                showCustomInput = true
                                dropdownExpanded = false
                            }
                        )
                    }
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

            ScopeChooser(
                merchant = tx.merchant,
                generic = GenericMerchant.isGeneric(tx.merchant),
                selected = CategoryUpdater.effectiveScope(tx, scope),
                enabled = !saved,
                onSelect = { scope = it }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val categoryToSave = if (customCategory.isNotBlank()) customCategory else selectedCategory
                    coroutineScope.launch {
                        updatedCount = CategoryUpdater.update(database, tx, categoryToSave, scope)
                        selectedCategory = categoryToSave
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !saved && (selectedCategory.isNotEmpty() || customCategory.isNotBlank())
            ) {
                Text(if (saved) "Saved!" else "Save Category")
            }

            updatedCount?.let { count ->
                Spacer(modifier = Modifier.height(8.dp))
                val displayCategory = if (customCategory.isNotBlank()) customCategory else selectedCategory
                val others = count - 1
                Text(
                    text = when (CategoryUpdater.effectiveScope(tx, scope)) {
                        Scope.THIS_ONLY -> "Updated this transaction to \"$displayCategory\". " +
                            "Other transactions and future SMS are unchanged."
                        Scope.THIS_AND_LATER -> "Updated this and $others later transaction(s) " +
                            "from \"${tx.merchant}\" to \"$displayCategory\". Future SMS will match too."
                        Scope.ALL -> "Updated $count transaction(s) from \"${tx.merchant}\" " +
                            "to \"$displayCategory\". Future SMS will match too."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * How far a category change reaches; see [CategoryUpdater]. For a generic
 * merchant ("UPI") only the first option is offered, since one debit's payee
 * says nothing about the next one's.
 */
@Composable
private fun ScopeChooser(
    merchant: String,
    generic: Boolean,
    selected: Scope,
    enabled: Boolean,
    onSelect: (Scope) -> Unit
) {
    val name = merchant.ifEmpty { "this merchant" }
    val options = listOf(
        Scope.THIS_ONLY to "Only this transaction",
        Scope.THIS_AND_LATER to "This and every later transaction from \"$name\"",
        Scope.ALL to "Every transaction from \"$name\""
    )
    Column(modifier = Modifier.selectableGroup()) {
        Text("Apply to", style = MaterialTheme.typography.labelLarge)
        options.forEach { (option, label) ->
            val optionEnabled = enabled && (option == Scope.THIS_ONLY || !generic)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected == option,
                        enabled = optionEnabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == option, onClick = null, enabled = optionEnabled)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        if (generic) {
            Text(
                text = "\"UPI\" debits carry no payee, so each is categorized on its own. " +
                    "Importing a bank statement fills in the name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
