package com.buckey.financetracker.importer

import com.buckey.financetracker.data.db.entities.TransactionType
import com.buckey.financetracker.data.importer.MerchantMapImporter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for parsing `merchant_map.json` as emitted by
 * `reconciliation/sib_statement_diff.py`.
 *
 * Applying a map needs Room and is exercised on-device; the parse step is pure
 * and is what decides whether bad input reaches a financial record at all.
 */
class MerchantMapImporterTest {

    private fun map(fixes: String, schema: Int = 1) = """
        {
          "generated_at": "2026-09-03T11:20:00",
          "source": "statement.pdf",
          "schema": $schema,
          "fixes": [$fixes]
        }
    """.trimIndent()

    private val blinkit = """
        {
          "transaction_id": "abc123",
          "rrn": "620896250007",
          "old_merchant": "UPI",
          "merchant": "BLINKIT",
          "confidence": "rrn-exact"
        }
    """.trimIndent()

    // ==================== HAPPY PATH ====================

    @Test
    fun `parses a well formed map`() {
        val result = MerchantMapImporter.parse(map(blinkit))

        assertEquals(1, result.schema)
        assertEquals("statement.pdf", result.source)
        assertEquals(1, result.fixes.size)
        val fix = result.fixes.first()
        assertEquals("abc123", fix.transactionId)
        assertEquals("BLINKIT", fix.merchant)
        assertEquals("620896250007", fix.rrn)
        assertEquals("rrn-exact", fix.confidence)
    }

    @Test
    fun `parses an empty fix list`() {
        val result = MerchantMapImporter.parse(map(""))
        assertTrue(result.fixes.isEmpty())
    }

    @Test
    fun `parses multiple fixes`() {
        val second = blinkit
            .replace("abc123", "def456")
            .replace("BLINKIT", "ANISH KAARTHIK M")
        val result = MerchantMapImporter.parse(map("$blinkit,$second"))

        assertEquals(2, result.fixes.size)
        assertEquals("ANISH KAARTHIK M", result.fixes[1].merchant)
    }

    // ==================== REJECTED INPUT ====================

    @Test
    fun `rejects malformed json`() {
        assertInvalid("{ not json", "valid JSON")
    }

    @Test
    fun `rejects a file with no schema field`() {
        assertInvalid("""{"fixes": []}""", "schema")
    }

    @Test
    fun `rejects a newer schema rather than guessing at it`() {
        assertInvalid(map(blinkit, schema = 2), "Unsupported schema 2")
    }

    @Test
    fun `rejects a map with no fixes array`() {
        assertInvalid("""{"schema": 1}""", "fixes")
    }

    private fun assertInvalid(json: String, expectedFragment: String) {
        try {
            MerchantMapImporter.parse(json)
            fail("Expected InvalidMapException for: $json")
        } catch (e: MerchantMapImporter.InvalidMapException) {
            assertTrue(
                "Message was: ${e.message}",
                e.message!!.contains(expectedFragment, ignoreCase = true)
            )
        }
    }

    // ==================== SKIPPED ENTRIES ====================

    @Test
    fun `skips a fix with no transaction id`() {
        val broken = blinkit.replace("\"abc123\"", "\"\"")
        val result = MerchantMapImporter.parse(map(broken))
        assertTrue(result.fixes.isEmpty())
    }

    @Test
    fun `skips a fix with a blank merchant`() {
        // Writing a blank merchant would erase what little the row had.
        val broken = blinkit.replace("\"BLINKIT\"", "\"   \"")
        val result = MerchantMapImporter.parse(map(broken))
        assertTrue(result.fixes.isEmpty())
    }

    @Test
    fun `keeps good fixes alongside broken ones`() {
        val broken = blinkit.replace("\"abc123\"", "\"\"")
        val result = MerchantMapImporter.parse(map("$broken,$blinkit"))

        assertEquals(1, result.fixes.size)
        assertEquals("BLINKIT", result.fixes.first().merchant)
    }

    @Test
    fun `trims surrounding whitespace`() {
        val padded = blinkit.replace("\"BLINKIT\"", "\"  BLINKIT  \"")
        val result = MerchantMapImporter.parse(map(padded))
        assertEquals("BLINKIT", result.fixes.first().merchant)
    }

    // ==================== RESULT SUMMARY ====================

    @Test
    fun `summary reports an applied import`() {
        val summary = MerchantMapImporter.ImportResult(
            applied = 3, notFound = 1, alreadySpecific = 2, total = 6
        ).summary()

        assertTrue(summary, summary.contains("Updated 3 of 6"))
        assertTrue(summary, summary.contains("1 not on this device"))
        assertTrue(summary, summary.contains("2 already named"))
    }

    @Test
    fun `summary reports a no-op import`() {
        val summary = MerchantMapImporter.ImportResult(
            applied = 0, notFound = 2, alreadySpecific = 1, total = 3
        ).summary()

        assertTrue(summary, summary.startsWith("No changes"))
    }

    @Test
    fun `summary reports an empty map`() {
        val summary = MerchantMapImporter.ImportResult().summary()
        assertTrue(summary, summary.contains("no fixes"))
    }

    // ==================== MISSING TRANSACTIONS ====================

    private fun mapWithMissing(missing: String) = """
        {
          "generated_at": "2026-10-24T18:15:00",
          "source": "statement.pdf",
          "schema": 1,
          "fixes": [],
          "missing": [$missing]
        }
    """.trimIndent()

    private val aaron = """
        {"rrn": "658298209523", "date": "2026-08-04", "amount": "600.00",
         "type": "CREDIT", "merchant": "AARON JACOB"}
    """.trimIndent()

    @Test
    fun `a map without a missing list still parses`() {
        assertTrue(MerchantMapImporter.parse(map(blinkit)).missing.isEmpty())
    }

    @Test
    fun `parses a missing transaction`() {
        val missing = MerchantMapImporter.parse(mapWithMissing(aaron)).missing.single()

        assertEquals("658298209523", missing.rrn)
        assertEquals(LocalDate.of(2026, 8, 4), missing.date)
        assertEquals(600.00, missing.amount, 0.001)
        assertEquals(TransactionType.CREDIT, missing.type)
        assertEquals("AARON JACOB", missing.merchant)
    }

    @Test
    fun `a missing payee becomes the generic merchant`() {
        val entry = """{"rrn": "658298209523", "date": "2026-08-04", "amount": "10.00",
            "type": "DEBIT", "merchant": null}"""
        assertEquals("UPI", MerchantMapImporter.parse(mapWithMissing(entry)).missing.single().merchant)
    }

    @Test
    fun `invalid missing entries are skipped, valid ones kept`() {
        val bad = listOf(
            """{"rrn": "", "date": "2026-08-04", "amount": "1.00", "type": "DEBIT"}""",
            """{"rrn": "65829820952X", "date": "2026-08-04", "amount": "1.00", "type": "DEBIT"}""",
            """{"rrn": "658298209523", "date": "04-08-26", "amount": "1.00", "type": "DEBIT"}""",
            """{"rrn": "658298209523", "date": "2026-08-04", "amount": "abc", "type": "DEBIT"}""",
            """{"rrn": "658298209523", "date": "2026-08-04", "amount": "0.00", "type": "DEBIT"}""",
            """{"rrn": "658298209523", "date": "2026-08-04", "amount": "1.00", "type": "REFUND"}"""
        )
        val parsed = MerchantMapImporter.parse(mapWithMissing((bad + aaron).joinToString(",")))
        assertEquals(listOf("658298209523"), parsed.missing.map { it.rrn })
        assertEquals("AARON JACOB", parsed.missing.single().merchant)
    }

    @Test
    fun `summary reports added missing transactions`() {
        val summary = MerchantMapImporter.ImportResult(
            applied = 2, total = 2, added = 3, alreadyRecorded = 1, missingTotal = 4, queueResolved = 2
        ).summary()

        assertTrue(summary, summary.contains("Updated 2 of 2"))
        assertTrue(summary, summary.contains("Added 3 of 4 missing"))
        assertTrue(summary, summary.contains("1 already recorded"))
        assertTrue(summary, summary.contains("cleared 2 from Review"))
    }
}
