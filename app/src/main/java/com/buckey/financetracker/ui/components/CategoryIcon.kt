package com.buckey.financetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingBasket
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.ui.theme.Sizes

/**
 * Icon for a category name. Covers the editor's preset categories; anything
 * else (a custom category) gets the generic icon.
 */
fun categoryIcon(category: String?): ImageVector = when (category?.trim()?.lowercase()) {
    "food" -> Icons.Outlined.Restaurant
    "groceries" -> Icons.Outlined.ShoppingBasket
    "transport" -> Icons.Outlined.DirectionsBus
    "shopping" -> Icons.Outlined.ShoppingBag
    "utilities" -> Icons.Outlined.Bolt
    "bills", "sms charges" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "housing", "home", "room" -> Icons.Outlined.Home
    "income" -> Icons.Outlined.Payments
    "transfer", "self transfer" -> Icons.Outlined.SwapHoriz
    "entertainment" -> Icons.Outlined.Movie
    "health" -> Icons.Outlined.LocalHospital
    "education", "college" -> Icons.Outlined.School
    "scooter" -> Icons.Outlined.TwoWheeler
    "travel" -> Icons.Outlined.Flight
    null, "uncategorized" -> Icons.AutoMirrored.Outlined.HelpOutline
    else -> if (category.contains("sms", ignoreCase = true)) Icons.Outlined.Sms else Icons.Outlined.Category
}

/**
 * The circle at the start of a transaction row. Neutral for debits; credits
 * alone get the green tint (user's choice, 2026-10-24). Decorative: the row
 * carries the spoken description.
 */
@Composable
fun CategoryAvatar(category: String?, type: TransactionType, modifier: Modifier = Modifier) {
    val credit = type == TransactionType.CREDIT
    Box(
        modifier = modifier
            .size(Sizes.avatar)
            .clip(CircleShape)
            .background(
                if (credit) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = categoryIcon(category),
            contentDescription = null,
            modifier = Modifier.size(Sizes.icon),
            tint = if (credit) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
