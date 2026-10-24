package com.buckey.financetracker.ui.screens.Account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.buckey.financetracker.data.summary.HomePreferences
import com.buckey.financetracker.ui.components.PreviewSurface
import com.buckey.financetracker.ui.components.ThemePreviews
import com.buckey.financetracker.ui.theme.Sizes
import com.buckey.financetracker.ui.theme.Spacing

/** The three profile pages (user, 2026-10-25). */
enum class AccountPage { PROFILE, PREFERENCES, STATEMENT }

/**
 * Opened from the profile initial on Home: the profile's name, then Profile
 * (Google Sheets, data tools), Preferences (Net, Spent, matching range) and Bank
 * statement (merchant map now, PDF upload later).
 */
@Composable
fun AccountScreen(onOpen: (AccountPage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val name = remember { HomePreferences.profileName(context) }
    AccountContent(name, onOpen, onBack)
}

@Composable
fun AccountContent(profileName: String, onOpen: (AccountPage) -> Unit, onBack: () -> Unit) {
    AccountScaffold(title = "Account", onBack = onBack) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            modifier = Modifier.padding(vertical = Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(Sizes.avatar)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    profileName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column {
                Text(profileName, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PageRow(Icons.Outlined.Person, "Profile", "Name, Google Sheets, data") { onOpen(AccountPage.PROFILE) }
        PageRow(Icons.Outlined.Tune, "Preferences", "What Net and Spent show, matching range") {
            onOpen(AccountPage.PREFERENCES)
        }
        PageRow(Icons.Outlined.Description, "Bank statement", "Import the statement's merchant map") {
            onOpen(AccountPage.STATEMENT)
        }
    }
}

@Composable
private fun PageRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@ThemePreviews
@Composable
private fun AccountPreview() {
    PreviewSurface { AccountContent("Personal", onOpen = {}, onBack = {}) }
}
