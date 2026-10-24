package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.db.entities.TransactionEntity

/**
 * Plans in-place Category cell updates for rows already in the Sheet.
 *
 * Rule 10 as amended on 2026-10-24: rows are still only ever appended, never
 * duplicated or deleted, but the Category cell (column H) of an appended row may
 * be rewritten when the category changes in Room. Room stays the source of truth
 * (rule 8); this just stops the Sheet from showing a category the app has since
 * corrected.
 *
 * Pure, with no Sheets client or Android types, so it is unit tested on the JVM.
 */
object SheetCategorySync {

    /** Must match GoogleSheetsRepository's sheet name and header order. */
    private const val SHEET_NAME = "Transactions"
    private const val CATEGORY_COLUMN = "H"

    private const val UNCATEGORIZED = "Uncategorized"

    /** One cell to write: the A1 range and the value going into it. */
    data class CellUpdate(val transaction: TransactionEntity, val range: String, val value: String)

    /** Rows that can be written now, and rows not found in the Sheet. */
    data class Plan(val updates: List<CellUpdate>, val notInSheet: List<TransactionEntity>)

    /**
     * Builds row → 1-based Sheet row from column A's values, as returned by the
     * Sheets API (row 1 is the header). The first occurrence wins if an ID repeats.
     */
    fun indexRows(columnA: List<List<Any?>>?): Map<String, Int> {
        val index = HashMap<String, Int>()
        columnA?.forEachIndexed { i, row ->
            val id = row.firstOrNull()?.toString()?.trim().orEmpty()
            if (id.isNotEmpty()) index.putIfAbsent(id, i + 1)
        }
        return index
    }

    fun plan(rowIndex: Map<String, Int>, dirty: List<TransactionEntity>): Plan {
        val updates = mutableListOf<CellUpdate>()
        val notInSheet = mutableListOf<TransactionEntity>()
        for (tx in dirty) {
            val row = rowIndex[tx.transactionId]
            if (row == null) {
                notInSheet += tx
            } else {
                updates += CellUpdate(tx, "$SHEET_NAME!$CATEGORY_COLUMN$row", tx.category ?: UNCATEGORIZED)
            }
        }
        return Plan(updates, notInSheet)
    }
}
