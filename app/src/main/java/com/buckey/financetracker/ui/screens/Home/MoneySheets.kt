package com.buckey.financetracker.ui.screens.Home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.db.entities.LinkKind
import com.buckey.financetracker.data.review.UnparsedResolver
import com.buckey.financetracker.data.summary.LedgerResult
import com.buckey.financetracker.data.summary.LinkGroup
import com.buckey.financetracker.ui.components.PreviewData
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.format.DateLabels
import com.buckey.financetracker.ui.format.MoneyFormat
import com.buckey.financetracker.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Net's parts (user's design, 2026-10-25): Net = bank + wallet + lent − holding.
 * [bankPaise] is null until an SMS has quoted a balance.
 */
data class NetBreakdown(val bankPaise: Long?, val walletPaise: Long, val lentPaise: Long, val holdingPaise: Long) {
    val netPaise: Long? get() = bankPaise?.let { it + walletPaise + lentPaise - holdingPaise }
}

/**
 * "Bank ₹14,020 · Wallet ₹168 · Lent ₹500 · Holding −₹5,000" under the
 * figures, always visible; zero parts are left out. Tapping opens the money sheet.
 */
@Composable
fun BreakdownLine(breakdown: NetBreakdown, onClick: () -> Unit) {
    val parts = listOfNotNull(
        breakdown.bankPaise?.let { "Bank ${MoneyFormat.rupees(it)}" },
        breakdown.walletPaise.takeIf { it != 0L }?.let { "Wallet ${MoneyFormat.rupees(it)}" },
        breakdown.lentPaise.takeIf { it != 0L }?.let { "Lent ${MoneyFormat.rupees(it)}" },
        breakdown.holdingPaise.takeIf { it != 0L }?.let { "Holding ${MoneyFormat.rupees(-it)}" }
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Wallet, loans and holdings", role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .padding(horizontal = Spacing.lg)
    )
}

/**
 * Wallet (with Recount), money lent and money held, per person with history.
 * Cleared loans and holdings drop off ("if I clear it, it goes").
 */
@Composable
fun MoneySheetContent(
    ledger: LedgerResult,
    zone: ZoneId,
    onRecount: (Double) -> Unit,
    onClose: () -> Unit
) {
    var recounting by remember { mutableStateOf(false) }
    var counted by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.xl, end = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SheetTitle("Your money", onClose)
        Column(
            modifier = Modifier.padding(end = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SectionLabel("Wallet")
            if (ledger.walletStart == null) {
                Text(
                    "Not set up yet. Enter the cash you have now to start tracking it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AmountRow("Cash in hand", MoneyFormat.rupees(ledger.walletPaise))
            }
            if (recounting || ledger.walletStart == null) {
                OutlinedTextField(
                    value = counted,
                    onValueChange = { counted = it },
                    label = { Text("Cash you have now") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                val amount = counted.trim().removePrefix("₹").replace(",", "").toDoubleOrNull()
                Button(
                    onClick = { amount?.let(onRecount); recounting = false; counted = "" },
                    enabled = amount != null && amount >= 0.0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (ledger.walletStart == null) "Start wallet" else "Save count") }
            } else {
                TextButton(onClick = { recounting = true }) { Text("Recount") }
            }

            GroupSection("Lent", "Nobody owes you money.", ledger.loans.filter { it.isOpen }, zone)
            GroupSection("Holding", "You aren't holding money for anyone.", ledger.holdings.filter { it.isOpen }, zone)
        }
    }
}

@Composable
private fun GroupSection(title: String, empty: String, groups: List<LinkGroup>, zone: ZoneId) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    SectionLabel(title)
    if (groups.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    groups.forEach { group ->
        var open by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide history" else "Show history", role = Role.Button) {
                    open = !open
                }
                .minimumInteractiveComponentSize()
        ) {
            AmountRow(group.counterparty, MoneyFormat.rupees(group.remainingPaise))
            Text(
                "Since ${DateLabels.fullDate(group.openedAt, zone)} · of ${MoneyFormat.rupees(group.openingPaise)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (open) {
                group.movements.forEach { m ->
                    val sign = when (m.transaction.linkKind) {
                        LinkKind.HOLD_IN, LinkKind.LEND -> ""
                        else -> MoneyFormat.MINUS
                    }
                    Text(
                        "${DateLabels.fullDate(m.transaction.date, zone)}  $sign${MoneyFormat.rupees(m.amountPaise)}" +
                            "  → ${MoneyFormat.rupees(m.remainingPaise)} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The "+ Cash" form: amount, a spending category, a note and the day. Saved
 * as a cash row (wallet −, Spent +).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashPaymentContent(
    today: LocalDate,
    zone: ZoneId,
    onSave: (amount: Double, category: String, note: String, dateMillis: Long) -> Unit,
    onClose: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(today) }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val amount = UnparsedResolver.parseAmount(amountText)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.xl, end = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SheetTitle("Cash payment", onClose)
        Column(
            modifier = Modifier.padding(end = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { menu = !menu }) {
                OutlinedTextField(
                    value = category,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Categories.CASH_PRESETS.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { category = option; menu = false })
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                placeholder = { Text("e.g. Chai") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FilterChip(selected = day == today, onClick = { day = today }, label = { Text("Today") })
                FilterChip(
                    selected = day == today.minusDays(1),
                    onClick = { day = today.minusDays(1) },
                    label = { Text("Yesterday") }
                )
                FilterChip(
                    selected = day != today && day != today.minusDays(1),
                    onClick = { picking = true },
                    label = {
                        Text(if (day != today && day != today.minusDays(1)) DateLabels.day(day, today) else "Pick a day")
                    }
                )
            }
            Button(
                onClick = {
                    // Today: now. Another day: noon, so it sorts inside that day.
                    val millis = if (day == today) System.currentTimeMillis()
                    else day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
                    onSave(amount!!, category, note, millis)
                },
                enabled = amount != null && category.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save cash payment") }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        day = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().coerceAtMost(today)
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun SheetTitle(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun AmountRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

// ==================== Previews (fake data only) ====================

@ThemePreviews
@Composable
private fun MoneySheetPreview() {
    PreviewSurface {
        MoneySheetContent(
            ledger = com.buckey.financetracker.data.summary.Ledger.compute(PreviewData.withMoney),
            zone = PreviewData.zone,
            onRecount = {},
            onClose = {}
        )
    }
}

@ThemePreviews
@Composable
private fun CashPaymentPreview() {
    PreviewSurface {
        CashPaymentContent(today = PreviewData.today, zone = PreviewData.zone, onSave = { _, _, _, _ -> }, onClose = {})
    }
}
