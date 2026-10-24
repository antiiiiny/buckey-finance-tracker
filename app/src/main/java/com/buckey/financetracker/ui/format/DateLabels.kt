package com.buckey.financetracker.ui.format

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date text for the home and pending screens. Every function takes "today"
 * explicitly so the labels are unit tested without a clock.
 */
object DateLabels {

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

    /** "Today", "Yesterday", "21 Oct", or "21 Oct 2025" outside this year. */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) dayMonth.format(date) else dayMonthYear.format(date)
    }

    /** "October", or "October 2025" outside this year. */
    fun month(month: YearMonth, today: LocalDate): String =
        if (month.year == today.year) monthName.format(month) else monthYear.format(month)

    /** "21 Oct", or "21 Oct 2025" outside this year. */
    fun shortDate(epochMillis: Long, zone: ZoneId, today: LocalDate): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return if (date.year == today.year) dayMonth.format(date) else dayMonthYear.format(date)
    }

    /** "9:14 PM". */
    fun time(epochMillis: Long, zone: ZoneId): String =
        time.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "21 Oct 2026, 8:24 PM". */
    fun dateTime(epochMillis: Long, zone: ZoneId): String =
        dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** "12 Jun 2026". */
    fun fullDate(epochMillis: Long, zone: ZoneId): String =
        dayMonthYear.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
