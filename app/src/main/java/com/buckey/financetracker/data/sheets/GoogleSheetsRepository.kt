package com.buckey.financetracker.data.sheets

import android.content.Context
import android.util.Log
import com.buckey.financetracker.BuildConfig
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Repository for Google Sheets API v4 operations.
 *
 * Rows are appended once, keyed by transactionId hash for dedup (Rule 10). The
 * one in-place write is a synced row's Category cell, via [updateCells] (rule 10
 * as amended 2026-10-24; see SheetCategorySync).
 * Scoped to the single spreadsheet identified by [BuildConfig.GOOGLE_SHEET_ID].
 */
class GoogleSheetsRepository(private val context: Context) {

    private val TAG = "GoogleSheetsRepo"

    companion object {
        private const val SHEET_NAME = "Transactions"
        private const val DATA_RANGE = "$SHEET_NAME!A:H"
        private const val HEADER_RANGE = "$SHEET_NAME!A1:H1"

        private val HEADERS = listOf(
            "Transaction ID", "Amount", "Date", "Merchant", "Type",
            "Account Last4", "Raw Ref", "Category"
        )
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
            // Check if the "Transactions" sheet exists by getting spreadsheet metadata
            val spreadsheet = service.spreadsheets().get(sheetId).execute()
            val sheetExists = spreadsheet.sheets.any {
                it.properties?.title == SHEET_NAME
            }

            if (!sheetExists) {
                // Create the sheet
                val addRequest = com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest().setRequests(
                    listOf(
                        com.google.api.services.sheets.v4.model.Request().setAddSheet(
                            com.google.api.services.sheets.v4.model.AddSheetRequest().setProperties(
                                com.google.api.services.sheets.v4.model.SheetProperties().setTitle(SHEET_NAME)
                            )
                        )
                    )
                )
                service.spreadsheets().batchUpdate(sheetId, addRequest).execute()
                Log.i(TAG, "Created sheet '$SHEET_NAME'")
            }

            // Now write headers
            val headerRange = ValueRange().setValues(listOf(HEADERS))
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

    /**
     * Checks if a transaction with [transactionId] already exists in the sheet.
     */
    suspend fun isDuplicate(transactionId: String): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val existing = service.spreadsheets().values()
                .get(sheetId, DATA_RANGE)
                .execute()

            existing.values?.let { rows ->
                for (row in rows) {
                    if (row is List<*>) {
                        val first = row.firstOrNull()
                        if (first != null && first.toString() == transactionId) {
                            return@withContext true
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking duplicate: ${e.message}")
            false
        }
    }

    /**
     * Appends a single [TransactionEntity] as a row to the sheet.
     * Does NOT check for duplicates — caller must call [isDuplicate] first.
     */
    suspend fun appendRow(transaction: TransactionEntity): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val row = listOf(
                transaction.transactionId,
                transaction.amount.toString(),
                dateFormat.format(Date(transaction.date)),
                transaction.merchant,
                transaction.type.name,
                transaction.accountLast4,
                transaction.rawRef,
                transaction.category ?: "Uncategorized"
            )

            val valueRange = ValueRange().setValues(listOf(row))
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

    /**
     * Appends multiple transactions in a single batch API call.
     */
    suspend fun appendBatch(transactions: List<TransactionEntity>): Boolean = withContext(Dispatchers.IO) {
        val service = sheetsService ?: return@withContext false
        if (transactions.isEmpty()) return@withContext true
        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val rows = transactions.map { tx ->
                listOf(
                    tx.transactionId,
                    tx.amount.toString(),
                    dateFormat.format(Date(tx.date)),
                    tx.merchant,
                    tx.type.name,
                    tx.accountLast4,
                    tx.rawRef,
                    tx.category ?: "Uncategorized"
                )
            }

            val valueRange = ValueRange().setValues(rows)
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

    /** Writes each update's value into its cell, all in one batch call. */
    suspend fun updateCells(updates: List<SheetCategorySync.CellUpdate>): Boolean =
        withContext(Dispatchers.IO) {
            val service = sheetsService ?: return@withContext false
            if (updates.isEmpty()) return@withContext true
            try {
                val request = BatchUpdateValuesRequest()
                    .setValueInputOption("RAW")
                    .setData(updates.map { ValueRange().setRange(it.range).setValues(listOf(listOf(it.value))) })
                service.spreadsheets().values().batchUpdate(sheetId, request).execute()
                Log.i(TAG, "Updated ${updates.size} category cell(s)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error updating category cells: ${e.message}")
                false
            }
        }

    /** Returns the sheets service instance, or null if not authenticated. */
    fun isAuthenticated(): Boolean = sheetsService != null
}