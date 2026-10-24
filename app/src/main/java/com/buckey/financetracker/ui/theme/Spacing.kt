package com.buckey.financetracker.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Screens take padding and gaps from here, never an ad-hoc
 * `.dp` (CLAUDE.md, "UI rules").
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Fixed component sizes that aren't spacing. */
object Sizes {
    /** Leading circle on a transaction row. */
    val avatar = 40.dp
    /** Icon inside [avatar] and inline status icons. */
    val icon = 20.dp
    /** Status badge in the verify sheet and illustrations on empty screens. */
    val statusBadge = 40.dp
    val illustration = 56.dp
    /** Hairline dividers. */
    val divider = 1.dp
}
