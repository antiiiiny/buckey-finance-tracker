package com.buckey.financetracker.data.sheets

import android.content.Context
import android.util.Log
import com.buckey.financetracker.BuildConfig
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.sheets.SheetRowFormat.SHEET_NAME
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest
import com.google.api.services.sheets.v4.model.ClearValuesRequest
import com.google.api.services.sheets.v4.model.DuplicateSheetRequest
import com.google.api.services.sheets.v4.model.GridRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.SortRangeRequest
import com.google.api.services.sheets.v4.model.SortSpec
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for Google Sheets API v4 operations.
 *
 * Rows are written once, keyed by transactionId hash for dedup, and kept in date
 * order (rule 10, amended 2026-10-24). In-place writes are limited to a synced
 * row's H:J cells ([updateCells]; see SheetCategorySync), re-sorting by date
 * ([sortByDate]), and the explicit full [rebuild].
 * Scoped to the single spreadsheet identified by [BuildConfig.GOOGLE_SHEET_ID].
 */
class GoogleSheetsRepository(private val context: Context) {

    private val TAG = "GoogleSheetsRepo"

    companion object {
        private val LAST = SheetRowFormat.LAST_COLUMN
        private val DATA_RANGE = "$SHEET_NAME!A:$LAST"
        private val HEADER_RANGE = "$SHEET_NAME!A1:${LAST}1"
    }

    private val sheetsService: Sheets? = GoogleSignInHelper.buildSheetsService(context)
    private val sheetId: String get() = BuildConfig.GOOGLE_SHEET_ID

    /**
     * Ensures the "Transactions" sheet exists and has headers.
     * Creates the sheet if it doesn't exist — idempotent.
     */
    suspend fun ensureHeaders(): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            if (tabId(service) == null) {
                val addRequest = BatchUpdateSpreadsheetRequest().setRequests(
                    listOf(Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(SHEET_NAME))))
                )
                service.spreadsheets().batchUpdate(sheetId, addRequest).execute()
                Log.i(TAG, "Created sheet '$SHEET_NAME'")
            }

            val headerRange = ValueRange().setValues(listOf(SheetRowFormat.HEADERS))
            service.spreadsheets().values()
                .update(sheetId, HEADER_RANGE, headerRange)
                .setValueInputOption("RAW")
                .execute()
            Log.i(TAG, "Headers ensured in sheet '$SHEET_NAME'")
            true
        } catch (e: GoogleJsonResponseException) {
            Log.w(TAG, "Failed to ensure headers: ${e.statusCode} ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring headers: ${e.message}")
            false
        }
    }

    /** The numeric ID of the "Transactions" tab, or null if it doesn't exist. */
    private fun tabId(service: Sheets): Int? =
        service.spreadsheets().get(sheetId).execute().sheets
            ?.firstOrNull { it.properties?.title == SHEET_NAME }
            ?.properties?.sheetId

    /**
     * Checks if a transaction with [transactionId] already exists in the sheet.
     * Fallback only: the sync dedups against one [fetchRowIndex] read.
     */
    suspend fun isDuplicate(transactionId: String): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val existing = service.spreadsheets().values()
                .get(sheetId, "$SHEET_NAME!A:A")
                .execute()
            SheetCategorySync.indexRows(existing.getValues()).containsKey(transactionId)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking duplicate: ${e.message}")
            false
        }
    }

    /**
     * Adds one [TransactionEntity] as a row. Does NOT check for duplicates, and
     * lands at the bottom: the sync calls [sortByDate] after its appends.
     */
    suspend fun appendRow(transaction: TransactionEntity): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val valueRange = ValueRange().setValues(listOf(SheetRowFormat.values(transaction)))
            service.spreadsheets().values()
                .append(sheetId, DATA_RANGE, valueRange)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()

            Log.i(TAG, "Appended row for transaction ${transaction.transactionId}")
            true
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Sheets API error: ${e.statusCode} ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error appending row: ${e.message}")
            false
        }
    }

    /** Appends multiple transactions in a single batch API call. */
    suspend fun appendBatch(transactions: List<TransactionEntity>): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        if (transactions.isEmpty()) return@withContext true
        try {
            val valueRange = ValueRange().setValues(transactions.map { SheetRowFormat.values(it) })
            service.spreadsheets().values()
                .append(sheetId, DATA_RANGE, valueRange)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute()

            Log.i(TAG, "Appended batch of ${transactions.size} transactions")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending batch: ${e.message}")
            false
        }
    }

    /**
     * Sorts every data row (row 2 down) by the Date column, oldest first, in one
     * request. Run after appends so a new row, from SMS or a bank statement,
     * ends up at its chronological position.
     */
    suspend fun sortByDate(): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val tab = tabId(service) ?: return@withContext false
            val sort = SortRangeRequest()
                .setRange(
                    GridRange().setSheetId(tab).setStartRowIndex(1)
                        .setStartColumnIndex(0).setEndColumnIndex(SheetRowFormat.HEADERS.size)
                )
                .setSortSpecs(
                    listOf(SortSpec().setDimensionIndex(SheetRowFormat.DATE_COLUMN_INDEX).setSortOrder("ASCENDING"))
                )
            service.spreadsheets()
                .batchUpdate(sheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setSortRange(sort))))
                .execute()
            Log.i(TAG, "Sorted '$SHEET_NAME' by date")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sorting by date: ${e.message}")
            false
        }
    }

    /**
     * Replaces every data row with [rows] (already oldest first), after copying
     * the tab to "Transactions backup <timestamp>" so nothing is lost. Room is
     * the source of truth (rule 8): rows that exist only in the Sheet are
     * dropped, and every row gets the current columns.
     */
    suspend fun rebuild(rows: List<TransactionEntity>): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val tab = tabId(service) ?: return@withContext false
            val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
            val backup = DuplicateSheetRequest()
                .setSourceSheetId(tab)
                .setNewSheetName("$SHEET_NAME backup $stamp")
                .setInsertSheetIndex(1)
            service.spreadsheets()
                .batchUpdate(sheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(Request().setDuplicateSheet(backup))))
                .execute()

            service.spreadsheets().values()
                .clear(sheetId, "$SHEET_NAME!A2:Z", ClearValuesRequest())
                .execute()
            if (rows.isNotEmpty()) {
                service.spreadsheets().values()
                    .update(sheetId, "$SHEET_NAME!A2", ValueRange().setValues(rows.map { SheetRowFormat.values(it) }))
                    .setValueInputOption("USER_ENTERED")
                    .execute()
            }
            Log.i(TAG, "Rebuilt '$SHEET_NAME' with ${rows.size} rows (backup tab: $stamp)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error rebuilding sheet: ${e.message}")
            false
        }
    }

    /**
     * Reads column A once and returns transaction ID -> 1-based row, or null on
     * error. One read per sync, instead of one full-sheet read per transaction.
     */
    suspend fun fetchRowIndex(): Map<String, Int>? = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext null
        try {
            val columnA = service.spreadsheets().values()
                .get(sheetId, "$SHEET_NAME!A:A")
                .execute()
            SheetCategorySync.indexRows(columnA.getValues())
        } catch (e: Exception) {
            Log.e(TAG, "Error reading transaction IDs: ${e.message}")
            null
        }
    }

    /** Writes each update's H:J values into its row, all in one batch call. */
    suspend fun updateCells(updates: List<SheetCategorySync.CellUpdate>): Boolean =
        withContext(Dispatchers.IO) {
            val service = sheetsService ?: return@withContext false
            if (updates.isEmpty()) return@withContext true
            try {
                val request = BatchUpdateValuesRequest()
                    .setValueInputOption("RAW")
                    .setData(updates.map { ValueRange().setRange(it.range).setValues(listOf(it.values)) })
                service.spreadsheets().values().batchUpdate(sheetId, request).execute()
                Log.i(TAG, "Updated ${updates.size} row(s) of category cells")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error updating category cells: ${e.message}")
                false
            }
        }

    /** Returns the sheets service instance, or null if not authenticated. */
    fun isAuthenticated(): Boolean = sheetsService != null
}
