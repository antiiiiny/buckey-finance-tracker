package com.buckey.financetracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The UI rules allow 4 text sizes and 2 weights across the app. Every Material
// slot maps onto one of these, so a component's default style can't add a fifth
// size, and screens written before the rules shrink onto the same scale.

private val Large = TextStyle(fontFamily = FontFamily.Default, fontSize = 28.sp, lineHeight = 36.sp)
private val Title = TextStyle(fontFamily = FontFamily.Default, fontSize = 20.sp, lineHeight = 28.sp)
private val Body = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp)
private val Small = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp)

private val Regular = FontWeight.Normal
private val Emphasis = FontWeight.Medium

/** Large: the summary numbers. Title: top bars and sheet titles. Body: rows and
 * headers. Small: second lines, labels and captions. */
val Typography = Typography(
    displayLarge = Large.copy(fontWeight = Emphasis),
    displayMedium = Large.copy(fontWeight = Emphasis),
    displaySmall = Large.copy(fontWeight = Emphasis),
    headlineLarge = Large.copy(fontWeight = Emphasis),
    headlineMedium = Large.copy(fontWeight = Emphasis),
    headlineSmall = Large.copy(fontWeight = Emphasis),
    titleLarge = Title.copy(fontWeight = Emphasis),
    titleMedium = Body.copy(fontWeight = Emphasis),
    titleSmall = Body.copy(fontWeight = Emphasis),
    bodyLarge = Body.copy(fontWeight = Regular),
    bodyMedium = Body.copy(fontWeight = Regular),
    bodySmall = Small.copy(fontWeight = Regular),
    labelLarge = Body.copy(fontWeight = Emphasis),
    labelMedium = Small.copy(fontWeight = Emphasis),
    labelSmall = Small.copy(fontWeight = Regular),
)
