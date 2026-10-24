package com.buckey.financetracker.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.buckey.financetracker.ui.theme.Sizes
import com.buckey.financetracker.ui.theme.Spacing

/**
 * Two headline figures side by side with equal weight, split by a hairline: the
 * top of Home (Spent | Net) and of Pending (to resolve | to categorize).
 */
@Composable
fun FigurePair(
    start: @Composable (Modifier) -> Unit,
    end: @Composable (Modifier) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        start(Modifier.weight(1f))
        VerticalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = Spacing.lg),
            thickness = Sizes.divider,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        end(Modifier.weight(1f))
    }
}

/**
 * A small label over a large value. [emphasise] draws the value in the primary
 * green. With [onClick] the figure is tappable and shows a chevron; the value
 * crossfades when it changes.
 */
@Composable
fun Figure(
    label: String,
    value: String,
    emphasise: Boolean,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else Modifier

    Column(modifier = modifier.then(clickable)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Crossfade(targetState = value, label = "figure") { shown ->
                Text(
                    text = shown,
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (emphasise) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A section title with one line saying what the section is for, under a
 * hairline, so sections on a screen are told apart at a glance.
 */
@Composable
fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.lg,
                bottom = Spacing.xs
            )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
