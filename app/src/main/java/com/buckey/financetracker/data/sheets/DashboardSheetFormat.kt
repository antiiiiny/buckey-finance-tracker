package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.category.Categories
import com.buckey.financetracker.data.wallet.Wallet
import java.time.LocalDate

/**
 * The Dashboard tab: live formulas over the Transactions tab (user, 2026-10-25).
 *
 * The app creates it once, when the Sheet has none or [LAYOUT_VERSION] changed;
 * after that a sync only writes Transactions (and Holdings), and the Dashboard
 * recalculates by itself. Two dropdowns drive it: the month (B3) and what Spent
 * means (D3), mirroring the app's "Spent shows" preference, which formulas can't
 * read. Net is the month's change: a running balance needs the SMS balances,
 * which the Sheet doesn't have.
 *
 * Rules follow the app's ledger as far as the Sheet can see (holdings live in
 * their own tab; an undecided Transfer debit looks like a cash one):
 *   Spent (default) = debits outside [Categories.NON_SPENDING_DEBITS], cash
 *     payments included, minus Transfer credits (friends paying back)
 *   Spent (total)   = every bank debit (Source isn't cash)
 *   Income          = credits categorized Income
 *   Net             = credits minus debits; ATM and Transfer debits since the
 *     wallet's "Opening cash" row only move money into cash, lending keeps Net,
 *     repaid loans and the opening count don't add to it
 *
 * Pure, so the formulas are unit tested; formatting and charts are in
 * [DashboardSheetRequests].
 */
object DashboardSheetFormat {

    const val SHEET_NAME = "Dashboard"

    /** Bump when the cells or requests change: the next sync rebuilds the tab. */
    const val LAYOUT_VERSION = 3

    /** Hidden helper columns T..Z: months, chart tables, wallet start, category table. */
    const val HIDDEN_FIRST_COLUMN = 19 // T
    const val HIDDEN_LAST_COLUMN = 25 // Z
    const val CHART_MONTHS = 12

    const val SPENT_DEFAULT = "Debits minus transfers"
    const val SPENT_TOTAL = "Total spent"

    private const val SOURCE = SheetRowFormat.SHEET_NAME
    private const val AMOUNT = "$SOURCE!\$B\$2:\$B"
    private const val DATE = "$SOURCE!\$C\$2:\$C"
    private const val MERCHANT = "$SOURCE!\$D\$2:\$D"
    private const val TYPE = "$SOURCE!\$E\$2:\$E"
    private const val CATEGORY = "$SOURCE!\$H\$2:\$H"
    private const val ORIGIN = "$SOURCE!\$I\$2:\$I"

    private const val MONTH = "\$B\$3"
    private const val MODE = "\$D\$3"
    private const val WALLET_START = "\$X\$2"
    private const val LAST_MONDAY = "(TODAY()-WEEKDAY(TODAY(),3)-7)"

    private fun between(start: String, end: String) = "$DATE,\">=\"&$start,$DATE,\"<\"&$end"

    /** Spent over [start, end), by the D3 dropdown. */
    fun spent(start: String, end: String): String {
        val window = between(start, end)
        val excluded = Categories.NON_SPENDING_DEBITS.joinToString(",") { "$CATEGORY,\"<>$it\"" }
        val afterTransfers = "SUMIFS($AMOUNT,$TYPE,\"DEBIT\",$window,$excluded)" +
            "-SUMIFS($AMOUNT,$TYPE,\"CREDIT\",$window,$CATEGORY,\"${Categories.TRANSFER}\")"
        val total = "SUMIFS($AMOUNT,$TYPE,\"DEBIT\",$window,$ORIGIN,\"<>${SheetRowFormat.SOURCE_CASH}\")"
        return "IF($MODE=\"$SPENT_TOTAL\",$total,$afterTransfers)"
    }

    fun spentInMonth(start: String) = spent(start, "EDATE($start,1)")

    fun incomeInMonth(start: String) =
        "SUMIFS($AMOUNT,$TYPE,\"CREDIT\",${between(start, "EDATE($start,1)")},$CATEGORY,\"${Categories.INCOME}\")"

    fun netInMonth(start: String): String {
        val window = between(start, "EDATE($start,1)")
        val sinceWallet = "$DATE,\">=\"&$WALLET_START"
        return "(SUMIFS($AMOUNT,$TYPE,\"CREDIT\",$window,$CATEGORY,\"<>${Categories.LOAN_REPAID}\"," +
            "$MERCHANT,\"<>${Wallet.OPENING_MERCHANT}\")" +
            "-SUMIFS($AMOUNT,$TYPE,\"DEBIT\",$window,$CATEGORY,\"<>${Categories.LEND}\")" +
            "+SUMIFS($AMOUNT,$TYPE,\"DEBIT\",$window,$CATEGORY,\"${Categories.ATM_WITHDRAWAL}\",$sinceWallet)" +
            "+SUMIFS($AMOUNT,$TYPE,\"DEBIT\",$window,$CATEGORY,\"${Categories.TRANSFER}\",$sinceWallet))"
    }

    /** Spending by category for the B3 month, biggest first, by the D3 dropdown. */
    fun categoryQuery(): String {
        val start = "TEXT($MONTH,\"yyyy-mm-dd\")"
        val end = "TEXT(EDATE($MONTH,1),\"yyyy-mm-dd\")"
        fun query(filter: String) =
            "QUERY({$CATEGORY,$AMOUNT,$TYPE,$DATE,$ORIGIN},\"select Col1, sum(Col2) where Col3 = 'DEBIT' " +
                "and Col4 >= datetime '\"&$start&\" 00:00:00' and Col4 < datetime '\"&$end&\" 00:00:00' " +
                "$filter group by Col1 order by sum(Col2) desc label Col1 'Category', sum(Col2) 'Spent'\",0)"
        val spending = Categories.NON_SPENDING_DEBITS.joinToString(" ") { "and Col1 <> '$it'" }
        val bank = "and Col5 <> '${SheetRowFormat.SOURCE_CASH}'"
        return "=IFERROR(IF($MODE=\"$SPENT_TOTAL\",${query(bank)},${query(spending)}),{\"Category\",\"Spent\"})"
    }

    /** Every cell the tab starts with. */
    fun layoutCells(today: LocalDate): List<Pair<String, String>> {
        val lastMonth = "EDATE($MONTH,-1)"
        val week = spent(LAST_MONDAY, "$LAST_MONDAY+7")
        val cells = mutableListOf(
            "A1" to "Buckey dashboard",
            "A2" to "Live from the Transactions tab. Pick a month and what Spent means:",
            "A3" to "Month",
            "B3" to today.withDayOfMonth(1).toString(),
            "C3" to "Spent shows",
            "D3" to SPENT_DEFAULT,
            "A5" to "=IF($MODE=\"$SPENT_TOTAL\",\"Total spent\",\"Spent\")",
            "B5" to "Income",
            "C5" to "Net this month",
            "D5" to "vs last month",
            "E5" to "Avg per day, last week",
            "A6" to "=" + spentInMonth(MONTH),
            "B6" to "=" + incomeInMonth(MONTH),
            "C6" to "=" + netInMonth(MONTH),
            "D6" to "=A6-(${spentInMonth(lastMonth)})",
            "E6" to "=($week)/7",
            "A7" to "=IF($MODE=\"$SPENT_TOTAL\",\"Every bank debit, transfers included\"," +
                "\"Bank and cash spending, minus repayments\")",
            "B7" to "Credits categorized Income",
            "C7" to "Change in your money",
            "D7" to "=IFERROR(D6/(${spentInMonth(lastMonth)}),\"\")",
            "E7" to "=TEXT($LAST_MONDAY,\"d mmm\")&\" – \"&TEXT($LAST_MONDAY+6,\"d mmm\")&\", ₹\"&TEXT($week,\"#,##0\")&\" in all\"",
            // Hidden helpers.
            "T1" to "Months",
            "T2" to "=IFERROR(SORT(UNIQUE(ARRAYFORMULA(DATE(YEAR(FILTER($DATE,ISNUMBER($DATE)))," +
                "MONTH(FILTER($DATE,ISNUMBER($DATE))),1))),1,FALSE),DATE(YEAR(TODAY()),MONTH(TODAY()),1))",
            "U1" to "Month",
            "V1" to "Spent",
            "W1" to "Income",
            "X1" to "Wallet start",
            "X2" to "=IF(COUNTIF($MERCHANT,\"${Wallet.OPENING_MERCHANT}\")=0,DATE(9999,1,1)," +
                "MINIFS($DATE,$MERCHANT,\"${Wallet.OPENING_MERCHANT}\"))",
            "Y1" to categoryQuery()
        )
        for (i in 0 until CHART_MONTHS) {
            val row = i + 2
            val start = "EDATE($MONTH,${i - (CHART_MONTHS - 1)})"
            cells += "U$row" to "=TEXT($start,\"mmm yy\")"
            cells += "V$row" to "=" + spentInMonth(start)
            cells += "W$row" to "=" + incomeInMonth(start)
        }
        return cells
    }
}
