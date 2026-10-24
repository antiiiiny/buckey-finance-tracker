package com.buckey.financetracker.data.importer

import android.util.Log
import com.buckey.financetracker.data.category.CategoryMatcher
import com.buckey.financetracker.data.category.CategoryUpdater
import com.buckey.financetracker.data.category.GenericMerchant
import com.buckey.financetracker.data.category.MerchantMemory
import com.buckey.financetracker.data.db.AppDatabase
import com.buckey.financetracker.data.db.entities.TransactionEntity
import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.sms.TransactionIdGenerator
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Applies `merchant_map.json` from the Stage 7 reconciliation script to Room.
 *
 * This closes the loop that the script cannot: SIB's debit SMS carries no payee,
 * so [com.buckey.financetracker.data.parser.banks.SibParser] stores
 * `merchant = "UPI"` on every debit. The PDF statement does carry the payee, and
 * the script joins the two on the RRN. The script fixes the Google Sheet
 * directly, but Room is the source of truth (rule 8) and lives on the device —
 * this importer is how the repair reaches it.
 *
 * Deliberately conservative, because it overwrites a financial record:
 *
 * - Only rows whose merchant is still generic are touched. A merchant the user
 *   corrected by hand outranks the statement.
 * - Only a category of "Uncategorized" (or none) is recomputed, so a manual
 *   categorization survives the import.
 * - Rows are matched on `transaction_id`, never on amount or date.
 * - Running the same file twice is a no-op the second time.
 *
 * It does **not** re-sync repaired rows to Sheets. Sheets writes are append-only
 * (rule 10), so marking a repaired row unsynced would duplicate it rather than
 * update it. The script's own `--apply` is what fixes the Sheet.
 *
 * The map's optional `missing` list is different: statement rows with no
 * matching Sheet row, because SIB sometimes sends no SMS at all. Each is
 * inserted as a new transaction unless one with the same RRN is already in Room.
 * Being new rows, they sync to the Sheet normally. Any queued unparsed SMS that
 * names the same RRN is marked resolved, so it can't be entered twice by hand.
 */
object MerchantMapImporter {

    private const val TAG = "MerchantMapImporter"

    /** Schema version this importer understands, from the script's payload. */
    private const val SUPPORTED_SCHEMA = 1

    private const val UNCATEGORIZED = "Uncategorized"

    /**
     * `sender_address` for rows that came from the statement rather than an SMS.
     * It also feeds the transaction hash, so these can never collide with a
     * hash computed from a real SMS.
     */
    const val STATEMENT_SENDER = "SIB-STATEMENT"

    /** Statement dates carry no time; noon IST keeps the day stable in any display. */
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** One repair proposed by the reconciliation script. */
    data class Fix(
        val transactionId: String,
        val merchant: String,
        val rrn: String,
        val confidence: String
    )

    /** A statement transaction the app never recorded. */
    data class Missing(
        val rrn: String,
        val date: LocalDate,
        val amount: Double,
        val type: TransactionType,
        val merchant: String
    )

    /** A category rule from the user's rules file: merchant pattern → category. */
    data class Rule(val pattern: String, val category: String)

    /** A one-transaction category, by reference; wins over every rule. */
    data class Override(val rrn: String, val category: String)

    /** A parsed `merchant_map.json`. */
    data class MerchantMap(
        val schema: Int,
        val source: String,
        val generatedAt: String,
        val fixes: List<Fix>,
        val missing: List<Missing> = emptyList(),
        val rules: List<Rule> = emptyList(),
        val overrides: List<Override> = emptyList()
    )

    /**
     * A reference the importer accepts: a bank RRN (digits), or the script's
     * stand-in for statement rows that have none (`STMT-20260625-D-11800[-2]`).
     */
    private val REFERENCE = Regex("""\d+|STMT-\d{8}-[DC]-\d+(-\d+)?""")

    /** Outcome of applying a map, for display in Settings. */
    data class ImportResult(
        val applied: Int = 0,
        val notFound: Int = 0,
        val alreadySpecific: Int = 0,
        val total: Int = 0,
        val added: Int = 0,
        val alreadyRecorded: Int = 0,
        val missingTotal: Int = 0,
        val queueResolved: Int = 0,
        val rulesSaved: Int = 0,
        val rowsRecategorized: Int = 0,
        val overridesApplied: Int = 0
    ) {
        fun summary(): String {
            val merchants = when {
                total == 0 -> "Map contained no fixes."
                applied == 0 -> "No changes: $notFound unknown, $alreadySpecific already named."
                else -> "Updated $applied of $total merchants" +
                    (if (notFound > 0) ", $notFound not on this device" else "") +
                    (if (alreadySpecific > 0) ", $alreadySpecific already named" else "") + "."
            }
            val lines = mutableListOf(merchants)
            if (missingTotal > 0) {
                lines += "Added $added of $missingTotal missing transactions" +
                    (if (alreadyRecorded > 0) ", $alreadyRecorded already recorded" else "") +
                    (if (queueResolved > 0) ", cleared $queueResolved from Review" else "") + "."
            }
            if (rulesSaved > 0 || overridesApplied > 0) {
                lines += "Saved $rulesSaved category rules; recategorized $rowsRecategorized transactions" +
                    (if (overridesApplied > 0) ", $overridesApplied one-off override(s)" else "") + "."
            }
            return lines.joinToString("\n")
        }
    }

    /** Raised when the file is not a merchant map this importer can apply. */
    class InvalidMapException(message: String) : Exception(message)

    /**
     * Parses the script's JSON payload. Pure — no database access — so it can be
     * unit tested on the JVM without a device.
     *
     * @throws InvalidMapException if the file is malformed or a newer schema.
     */
    fun parse(json: String): MerchantMap {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw InvalidMapException("Not a valid JSON file.")
        }

        val schema = root.optInt("schema", -1)
        if (schema != SUPPORTED_SCHEMA) {
            throw InvalidMapException(
                if (schema < 0) "Missing \"schema\" — is this a merchant map?"
                else "Unsupported schema $schema; this build understands $SUPPORTED_SCHEMA."
            )
        }

        val array = root.optJSONArray("fixes")
            ?: throw InvalidMapException("Missing \"fixes\" array.")

        val fixes = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val transactionId = item.optString("transaction_id").trim()
                val merchant = item.optString("merchant").trim()
                // A fix with no target or no name cannot be applied; skipping it
                // is better than writing a blank merchant into the record.
                if (transactionId.isEmpty() || merchant.isEmpty()) {
                    Log.w(TAG, "Skipping fix $i — missing transaction_id or merchant")
                    continue
                }
                add(
                    Fix(
                        transactionId = transactionId,
                        merchant = merchant,
                        rrn = item.optString("rrn").trim(),
                        confidence = item.optString("confidence", "unknown")
                    )
                )
            }
        }

        return MerchantMap(
            schema = schema,
            source = root.optString("source", "unknown"),
            generatedAt = root.optString("generated_at", ""),
            fixes = fixes,
            missing = parseMissing(root),
            rules = parseRules(root),
            overrides = parseOverrides(root)
        )
    }

    /**
     * The optional `rules` list. A generic pattern ("upi") is refused for the same
     * reason the editor refuses it, and a very short one would match nearly
     * every merchant through `LIKE '%pattern%'`.
     */
    private fun parseRules(root: JSONObject): List<Rule> {
        val array = root.optJSONArray("rules") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pattern = item.optString("pattern").trim().lowercase()
                val category = item.optString("category").trim()
                if (pattern.length < 3 || category.isEmpty() || GenericMerchant.isGeneric(pattern)) {
                    Log.w(TAG, "Skipping rule $i — pattern too short, generic, or no category")
                    continue
                }
                add(Rule(pattern, category))
            }
        }
    }

    /** The optional `overrides` list: one transaction's category, by reference. */
    private fun parseOverrides(root: JSONObject): List<Override> {
        val array = root.optJSONArray("overrides") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rrn = item.optString("rrn").trim()
                val category = item.optString("category").trim()
                if (!REFERENCE.matches(rrn) || category.isEmpty()) {
                    Log.w(TAG, "Skipping override $i — invalid reference or no category")
                    continue
                }
                add(Override(rrn, category))
            }
        }
    }

    /**
     * The optional `missing` list. Every field is validated, and an entry that
     * fails is skipped: this list creates financial records, so a malformed
     * amount must never turn into a row.
     */
    private fun parseMissing(root: JSONObject): List<Missing> {
        val array = root.optJSONArray("missing") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rrn = item.optString("rrn").trim()
                val date = try {
                    LocalDate.parse(item.optString("date").trim())
                } catch (e: DateTimeParseException) {
                    null
                }
                val amount = item.optString("amount").trim().toBigDecimalOrNull()
                val type = when (item.optString("type").trim().uppercase()) {
                    "DEBIT" -> TransactionType.DEBIT
                    "CREDIT" -> TransactionType.CREDIT
                    else -> null
                }
                if (!REFERENCE.matches(rrn) || date == null ||
                    amount == null || amount <= BigDecimal.ZERO || type == null
                ) {
                    Log.w(TAG, "Skipping missing entry $i — invalid rrn, date, amount or type")
                    continue
                }
                // No payee (rare for UPI) stays generic, so a later map can name it.
                val merchant = item.optString("merchant").trim()
                    .takeUnless { it.isEmpty() || it == "null" } ?: "UPI"
                add(Missing(rrn, date, amount.toDouble(), type, merchant))
            }
        }
    }

    /**
     * Applies a parsed map to Room, recategorizing each repaired row.
     *
     * Once a debit has a real merchant, tier 1 matching works on it for the first
     * time — "BLINKIT" hits the seeded `blinkit → Groceries` rule — so the
     * category is recomputed rather than left stale.
     */
    suspend fun apply(database: AppDatabase, map: MerchantMap): ImportResult =
        withContext(Dispatchers.IO) {
            val dao = database.transactionDao()
            var applied = 0
            var notFound = 0
            var alreadySpecific = 0

            // Rules first, so repaired merchants and added rows categorize
            // against them below.
            for (rule in map.rules) {
                MerchantMemory.rememberCategory(database, rule.pattern, rule.category)
            }

            for (fix in map.fixes) {
                val existing = dao.getByTransactionId(fix.transactionId)
                if (existing == null) {
                    notFound++
                    continue
                }
                if (!GenericMerchant.isGeneric(existing.merchant)) {
                    alreadySpecific++
                    continue
                }

                // Preserve a category the user chose by hand.
                val current = existing.category
                val category = if (current.isNullOrBlank() || current == UNCATEGORIZED) {
                    CategoryMatcher.getCategory(database, fix.merchant, existing.type)
                } else {
                    current
                }

                applied += dao.updateMerchantAndCategory(
                    transactionId = fix.transactionId,
                    merchant = fix.merchant,
                    category = category
                )
            }

            var added = 0
            var alreadyRecorded = 0
            var queueResolved = 0
            for (missing in map.missing) {
                if (dao.getByRawRef(missing.rrn) != null) {
                    alreadyRecorded++
                } else if (dao.insert(missing.toEntity(database)) != -1L) {
                    added++
                } else {
                    alreadyRecorded++ // same hash: imported by an earlier run
                }
                queueResolved += database.unparsedMessageDao().resolveByReference(missing.rrn)
            }

            // Then relabel every existing row each rule governs — the same
            // selection as "Every transaction from X" in the category editor —
            // and finally the one-off overrides, which beat any rule.
            var rowsRecategorized = 0
            for (rule in map.rules) {
                rowsRecategorized += CategoryUpdater.applyRuleToExisting(database, rule.pattern, rule.category)
            }
            // Fill rows still Uncategorized from whichever rule now wins for them.
            // A longer rule saved earlier with "This and later" (jj stall christ)
            // beats a shorter imported one (jj stall) for its own earlier rows,
            // which neither pass above touches. Uncategorized means no decision
            // yet, so filling it never overrides a choice the user made.
            for (row in dao.getUncategorized()) {
                if (GenericMerchant.isGeneric(row.merchant)) continue
                val category = CategoryMatcher.getCategory(database, row.merchant, row.type)
                if (category != UNCATEGORIZED) {
                    dao.updateCategory(row.id, category)
                    rowsRecategorized++
                }
            }

            var overridesApplied = 0
            for (override in map.overrides) {
                overridesApplied += dao.updateCategoryByRawRef(
                    override.rrn, override.category,
                    CategoryMatcher.CREDIT_CATEGORIES, CategoryMatcher.CREDIT_FALLBACK
                )
            }

            Log.i(
                TAG,
                "Merchant map from ${map.source}: $applied applied, " +
                    "$notFound not found, $alreadySpecific already specific; " +
                    "$added added, $alreadyRecorded already recorded, $queueResolved queue resolved; " +
                    "${map.rules.size} rules, $rowsRecategorized recategorized, $overridesApplied overrides"
            )
            ImportResult(
                applied = applied,
                notFound = notFound,
                alreadySpecific = alreadySpecific,
                total = map.fixes.size,
                added = added,
                alreadyRecorded = alreadyRecorded,
                missingTotal = map.missing.size,
                queueResolved = queueResolved,
                rulesSaved = map.rules.size,
                rowsRecategorized = rowsRecategorized,
                overridesApplied = overridesApplied
            )
        }

    private suspend fun Missing.toEntity(database: AppDatabase): TransactionEntity {
        val millis = date.atTime(12, 0).atZone(IST).toInstant().toEpochMilli()
        return TransactionEntity(
            // Rule 10's formula, over the statement's identity instead of an SMS:
            // stable across re-imports, so the unique index rejects a repeat.
            transactionId = TransactionIdGenerator.generate(STATEMENT_SENDER, millis, "RRN:$rrn"),
            amount = amount,
            date = millis,
            merchant = merchant,
            type = type,
            rawRef = rrn,
            senderAddress = STATEMENT_SENDER,
            rawBody = "",
            category = CategoryMatcher.getCategory(database, merchant, type)
        )
    }

    /** Convenience: parse then apply. */
    suspend fun import(database: AppDatabase, json: String): ImportResult =
        apply(database, parse(json))
}
