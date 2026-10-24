package com.buckey.financetracker.data.sheets

import com.buckey.financetracker.data.db.entities.TransactionEntity

/**
 * Plans in-place updates of the H:J cells (Category, Source, Categorized By) for
 * rows already in the Sheet.
 *
 * Rule 10 as amended on 2026-10-24: rows are never duplicated or deleted, but a
 * synced row's H:J cells may be rewritten when the category changes in Room.
 * Room stays the source of truth (rule 8); this just stops the Sheet showing a
 * category the app has since corrected.
 *
 * Pure, with no Sheets client or Android types, so it is unit tested on the JVM.
 */
object SheetCategorySync {

    /** One row's H:J range and the values going into it. */
    data class CellUpdate(val transaction: TransactionEntity, val range: String, val values: List<String>)

    /** Rows that can be written now, and rows not found in the Sheet. */
    data class Plan(val updates: List<CellUpdate>, val notInSheet: List<TransactionEntity>)

    /**
     * Builds transaction ID -> 1-based Sheet row from column A's values, as
     * returned by the Sheets API (row 1 is the header). The first occurrence wins
     * if an ID repeats.
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
        val first = SheetRowFormat.MUTABLE_FIRST_COLUMN
        val last = SheetRowFormat.LAST_COLUMN
        for (tx in dirty) {
            val row = rowIndex[tx.transactionId]
            if (row == null) {
                notInSheet += tx
            } else {
                updates += CellUpdate(
                    tx,
                    "${SheetRowFormat.SHEET_NAME}!$first$row:$last$row",
                    SheetRowFormat.mutableValues(tx)
                )
            }
        }
        return Plan(updates, notInSheet)
    }
}
