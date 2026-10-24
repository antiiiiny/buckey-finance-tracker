package com.buckey.financetracker.ui.screens.Account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.data.summary.NetMode
import com.buckey.financetracker.data.summary.SpentMode
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.theme.Spacing

data class PreferencesState(val netMode: NetMode, val spentMode: SpentMode, val tolerancePaise: Long)

data class PreferencesActions(
    val onNetMode: (NetMode) -> Unit = {},
    val onSpentMode: (SpentMode) -> Unit = {},
    val onTolerance: (Long) -> Unit = {}
)

/**
 * Preferences (user, 2026-10-25): what Net and Spent show in the app, and the
 * matching range. The Sheet's Dashboard has its own Spent dropdown.
 */
@Composable
fun PreferencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(
            PreferencesState(
                HomePreferences.netMode(context),
                HomePreferences.spentMode(context),
                HomePreferences.matchTolerancePaise(context)
            )
        )
    }
    PreferencesContent(
        state = state,
        actions = PreferencesActions(
            onNetMode = { HomePreferences.setNetMode(context, it); state = state.copy(netMode = it) },
            onSpentMode = { HomePreferences.setSpentMode(context, it); state = state.copy(spentMode = it) },
            onTolerance = {
                HomePreferences.setMatchTolerancePaise(context, it)
                state = state.copy(tolerancePaise = it)
            }
        ),
        onBack = onBack
    )
}

@Composable
fun PreferencesContent(state: PreferencesState, actions: PreferencesActions, onBack: () -> Unit) {
    var rangeText by remember(state.tolerancePaise) { mutableStateOf((state.tolerancePaise / 100).toString()) }

    AccountScaffold(title = "Preferences", onBack = onBack) {
        Section("Net shows") {
            Column(modifier = Modifier.selectableGroup()) {
                ChoiceOption(
                    title = "Running balance",
                    detail = "Bank + wallet + lent − holding, checked against SIB SMS",
                    selected = state.netMode == NetMode.RUNNING_BALANCE,
                    onSelect = { actions.onNetMode(NetMode.RUNNING_BALANCE) }
                )
                ChoiceOption(
                    title = "This month's net",
                    detail = "How much your money changed in the selected month",
                    selected = state.netMode == NetMode.THIS_MONTH,
                    onSelect = { actions.onNetMode(NetMode.THIS_MONTH) }
                )
            }
        }

        Section("Spent shows") {
            Column(modifier = Modifier.selectableGroup()) {
                ChoiceOption(
                    title = "Debits minus transfers",
                    detail = "Leaves out transfers, savings, ATM, lending and holdings; " +
                        "subtracts money paid back to you; adds cash payments",
                    selected = state.spentMode == SpentMode.AFTER_TRANSFERS,
                    onSelect = { actions.onSpentMode(SpentMode.AFTER_TRANSFERS) }
                )
                ChoiceOption(
                    title = "Total spent",
                    detail = "Every bank debit, transfers included",
                    selected = state.spentMode == SpentMode.TOTAL,
                    onSelect = { actions.onSpentMode(SpentMode.TOTAL) }
                )
            }
        }

        Section("Matching range") {
            Text(
                "A payment this close to a holding, or a credit this close to a loan, " +
                    "is suggested for clearing it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = rangeText,
                onValueChange = { text ->
                    rangeText = text.filter(Char::isDigit).take(5)
                    rangeText.toLongOrNull()?.let { actions.onTolerance(it * 100) }
                },
                label = { Text("Within") },
                prefix = { Text("± ₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("Default ₹10") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm)
            )
        }
    }
}

@ThemePreviews
@Composable
private fun PreferencesPreview() {
    PreviewSurface {
        PreferencesContent(
            PreferencesState(NetMode.RUNNING_BALANCE, SpentMode.AFTER_TRANSFERS, 1000),
            PreferencesActions(),
            onBack = {}
        )
    }
}
