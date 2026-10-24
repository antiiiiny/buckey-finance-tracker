"""Tests for Stage 7 reconciliation.

Run: python -m unittest test_reconcile -v   (from the reconciliation/ directory)

Covers RRN normalization, both match tiers, ambiguity handling, merchant-recovery
gating, narration parsing, and end-to-end PDF extraction against a synthetic
statement in the real layout's shape (see make_sample_pdf.py).

Narration fixtures are real formats from a SIB statement with the RRNs replaced.
What these tests cannot cover is whether a *real* PDF's word coordinates match
what make_sample_pdf.py produces — confirm that with --inspect on the real file.
"""

from __future__ import annotations

import unittest
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path

import matching
import sheets
from models import Confidence, SheetRow, StatementRow, TxnType
from statement import Columns, extract_payee, extract_ref, validate_balances


def json_dump(obj) -> str:
    import json

    return json.dumps(obj)


def sheet_row(
    row_number=2,
    transaction_id="hash1",
    amount="100.00",
    when=datetime(2026, 7, 27, 20, 11, 44),
    merchant="UPI",
    type_=TxnType.DEBIT,
    raw_ref="620896250123",
    category="Uncategorized",
) -> SheetRow:
    return SheetRow(
        row_number=row_number,
        transaction_id=transaction_id,
        amount=Decimal(amount),
        date=when,
        merchant=merchant,
        type=type_,
        account_last4="6825",
        raw_ref=raw_ref,
        category=category,
    )


def statement_row(
    when=date(2026, 7, 27),
    amount="100.00",
    type_=TxnType.DEBIT,
    narration="UPI/620896250123/LEO STEV/leo@okicici",
    ref="620896250123",
    payee="LEO STEV",
) -> StatementRow:
    return StatementRow(
        date=when,
        amount=Decimal(amount),
        type=type_,
        narration=narration,
        ref=ref,
        payee=payee,
    )


class RrnNormalization(unittest.TestCase):
    """A 12-digit RRN written with USER_ENTERED comes back as a number."""

    def test_float_rrn_becomes_digits(self):
        self.assertEqual(sheets.normalize_ref(620896250123.0), "620896250123")

    def test_int_rrn_becomes_digits(self):
        self.assertEqual(sheets.normalize_ref(620896250123), "620896250123")

    def test_string_with_trailing_dot_zero(self):
        self.assertEqual(sheets.normalize_ref("620896250123.0"), "620896250123")

    def test_plain_string_untouched(self):
        self.assertEqual(sheets.normalize_ref("620896250123"), "620896250123")

    def test_none_becomes_empty(self):
        self.assertEqual(sheets.normalize_ref(None), "")


class RrnMatching(unittest.TestCase):
    def test_matches_on_rrn(self):
        result = matching.reconcile([sheet_row()], [statement_row()])
        self.assertEqual(len(result.matched), 1)
        self.assertIs(result.matched[0].confidence, Confidence.RRN_EXACT)
        self.assertEqual(result.sms_only, [])
        self.assertEqual(result.statement_only, [])

    def test_rrn_wins_over_date_mismatch(self):
        """A matching RRN is authoritative even when dates disagree."""
        stmt = statement_row(when=date(2026, 8, 15))
        result = matching.reconcile([sheet_row()], [stmt])
        self.assertEqual(len(result.matched), 1)

    def test_unmatched_statement_row_is_reported(self):
        stmt = statement_row(ref="999999999999", amount="55.00", when=date(2026, 1, 1))
        result = matching.reconcile([sheet_row()], [stmt])
        self.assertEqual(len(result.statement_only), 1)
        self.assertEqual(len(result.sms_only), 1)


class AmountDateFallback(unittest.TestCase):
    def test_matches_within_window(self):
        row = sheet_row(raw_ref="")
        stmt = statement_row(ref=None, when=date(2026, 7, 28))
        result = matching.reconcile([row], [stmt])
        self.assertEqual(len(result.matched), 1)
        self.assertIs(result.matched[0].confidence, Confidence.AMOUNT_DATE)

    def test_outside_window_does_not_match(self):
        row = sheet_row(raw_ref="")
        stmt = statement_row(ref=None, when=date(2026, 7, 30))
        result = matching.reconcile([row], [stmt])
        self.assertEqual(len(result.matched), 0)
        self.assertEqual(len(result.statement_only), 1)

    def test_type_must_agree(self):
        row = sheet_row(raw_ref="", type_=TxnType.CREDIT)
        stmt = statement_row(ref=None, type_=TxnType.DEBIT)
        result = matching.reconcile([row], [stmt])
        self.assertEqual(len(result.matched), 0)

    def test_ties_are_ambiguous_not_guessed(self):
        rows = [
            sheet_row(row_number=2, transaction_id="a", raw_ref=""),
            sheet_row(row_number=3, transaction_id="b", raw_ref=""),
        ]
        stmt = statement_row(ref=None)
        result = matching.reconcile(rows, [stmt])
        self.assertEqual(len(result.matched), 0)
        self.assertEqual(len(result.ambiguous), 1)
        self.assertEqual(len(result.merchant_fixes), 0)

    def test_amounts_compare_exactly(self):
        row = sheet_row(raw_ref="", amount="100.00")
        stmt = statement_row(ref=None, amount="100.01")
        result = matching.reconcile([row], [stmt])
        self.assertEqual(len(result.matched), 0)


class MissingEntries(unittest.TestCase):
    """Statement-only rows exported for the app to add (SIB sometimes sends no SMS)."""

    def test_shape_matches_the_app_importer(self):
        import sib_statement_diff

        row = statement_row(
            when=date(2026, 8, 4), amount="600.00", type_=TxnType.CREDIT,
            ref="658298209523", payee="AARON JACOB",
        )
        self.assertEqual(
            sib_statement_diff.missing_entries([row]),
            [{"rrn": "658298209523", "date": "2026-08-04", "amount": "600.00",
              "type": "CREDIT", "merchant": "AARON JACOB"}],
        )

    def test_rows_without_rrn_get_a_stable_statement_ref(self):
        import sib_statement_diff

        charge = statement_row(
            when=date(2026, 6, 25), amount="118.00", narration="SMS charges including GST-Qtly",
            ref=None, payee=None,
        )
        entry = sib_statement_diff.missing_entries([charge])[0]
        self.assertEqual(entry["rrn"], "STMT-20260625-D-11800")
        self.assertEqual(entry["merchant"], "SMS CHARGES INCLUDING GST-QTLY")
        # Same statement, same ref: a re-import cannot duplicate it.
        self.assertEqual(sib_statement_diff.missing_entries([charge])[0]["rrn"], entry["rrn"])

    def test_interest_label_never_carries_the_account_number(self):
        import sib_statement_diff

        interest = statement_row(
            type_=TxnType.CREDIT, amount="12.00",
            narration="0000000000001234:Int.Pd:01-04-2026 to 30- 06-2026", ref=None, payee=None,
        )
        entry = sib_statement_diff.missing_entries([interest])[0]
        self.assertEqual(entry["merchant"], "INTEREST")
        self.assertNotIn("1234", json_dump(entry))

    def test_identical_rows_on_one_day_get_distinct_refs(self):
        import sib_statement_diff

        row = statement_row(narration="SMS charges", ref=None, payee=None)
        refs = [e["rrn"] for e in sib_statement_diff.missing_entries([row, row])]
        self.assertEqual(len(set(refs)), 2)
        self.assertTrue(refs[1].endswith("-2"))

    def test_rules_file_passes_through(self):
        import tempfile

        import sib_statement_diff

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "rules.json"
            path.write_text(
                '{"rules": [{"pattern": "jj stall", "category": "Food"}],'
                ' "overrides": [{"rrn": "620896250099", "category": "Food"}]}',
                encoding="utf-8",
            )
            loaded = sib_statement_diff.load_rules(path)
        self.assertEqual(loaded["rules"], [{"pattern": "jj stall", "category": "Food"}])
        self.assertEqual(loaded["overrides"][0]["rrn"], "620896250099")
        self.assertEqual(sib_statement_diff.load_rules(None), {"rules": [], "overrides": []})


class OverlapScoping(unittest.TestCase):
    """A 3-month statement against ~5 weeks of synced SMS must not flood the diff."""

    def test_statement_rows_before_capture_are_set_aside(self):
        sheet = [sheet_row(when=datetime(2026, 7, 28, 10, 0))]
        early = statement_row(when=date(2026, 6, 3), ref="111111111111")
        late = statement_row(when=date(2026, 7, 28))
        _, stmt_in, before = matching.scope_to_overlap(sheet, [early, late])
        self.assertEqual(before, [early])
        self.assertEqual(stmt_in, [late])

    def test_window_keeps_the_day_before_capture(self):
        sheet = [sheet_row(when=datetime(2026, 7, 28, 0, 5))]
        edge = statement_row(when=date(2026, 7, 27))
        _, stmt_in, before = matching.scope_to_overlap(sheet, [edge])
        self.assertEqual(stmt_in, [edge])
        self.assertEqual(before, [])

    def test_sheet_rows_after_statement_end_are_dropped(self):
        inside = sheet_row(transaction_id="a", when=datetime(2026, 8, 1, 9, 0))
        after = sheet_row(transaction_id="b", when=datetime(2026, 10, 1, 9, 0))
        sheet_in, _, _ = matching.scope_to_overlap(
            [inside, after], [statement_row(when=date(2026, 8, 1))]
        )
        self.assertEqual(sheet_in, [inside])

    def test_empty_sheet_leaves_statement_untouched(self):
        stmt = [statement_row()]
        self.assertEqual(matching.scope_to_overlap([], stmt), ([], stmt, []))


class MerchantRecovery(unittest.TestCase):
    def test_generic_merchant_is_repaired(self):
        result = matching.reconcile([sheet_row()], [statement_row()])
        self.assertEqual(len(result.merchant_fixes), 1)
        fix = result.merchant_fixes[0]
        self.assertEqual(fix.old_merchant, "UPI")
        self.assertEqual(fix.new_merchant, "LEO STEV")
        self.assertEqual(fix.row_number, 2)

    def test_real_merchant_is_left_alone(self):
        row = sheet_row(merchant="SWIGGY")
        result = matching.reconcile([row], [statement_row()])
        self.assertEqual(len(result.merchant_fixes), 0)

    def test_no_payee_means_no_fix(self):
        result = matching.reconcile([sheet_row()], [statement_row(payee=None)])
        self.assertEqual(len(result.matched), 1)
        self.assertEqual(len(result.merchant_fixes), 0)

    def test_fuzzy_match_excluded_by_default(self):
        row = sheet_row(raw_ref="")
        stmt = statement_row(ref=None)
        result = matching.reconcile([row], [stmt])
        self.assertEqual(len(result.matched), 1)
        self.assertEqual(len(result.merchant_fixes), 0)

    def test_fuzzy_match_included_when_opted_in(self):
        row = sheet_row(raw_ref="")
        stmt = statement_row(ref=None)
        result = matching.reconcile([row], [stmt], allow_fuzzy_merchant=True)
        self.assertEqual(len(result.merchant_fixes), 1)

    def test_blank_merchant_counts_as_generic(self):
        result = matching.reconcile([sheet_row(merchant="")], [statement_row()])
        self.assertEqual(len(result.merchant_fixes), 1)


class NarrationExtraction(unittest.TestCase):
    """Fixtures taken from a real SIB statement (RRNs replaced with fakes).

    Narration shape is UPI/<bank>/<rrn>/<payee>[/<purpose>], and the payee is
    always field 4.
    """

    def test_payee_multi_word(self):
        self.assertEqual(
            extract_payee("UPI/SIBL/123456789012/ANISH KAARTHIK M/Paid via Su"),
            "ANISH KAARTHIK M",
        )

    def test_payee_with_rrn_dash_prefix(self):
        self.assertEqual(
            extract_payee("UPI/HDFC/RRN-123456789012/Blinkit/UPIIntent"), "BLINKIT"
        )

    def test_payee_with_rrn_prefix_no_purpose(self):
        self.assertEqual(
            extract_payee("UPI/UTIB/RRN123456789012/Google India Digital Service"),
            "GOOGLE INDIA DIGITAL SERVICE",
        )

    def test_payee_honorific_stripped(self):
        self.assertEqual(
            extract_payee("UPI/IDIB/RRN123456789012/Mrs YASHODHA /UPI"), "YASHODHA"
        )

    def test_payee_three_part_name(self):
        self.assertEqual(
            extract_payee("UPI/SIBL/123456789012/ALICIA THERESA PEREIRA/Payme"),
            "ALICIA THERESA PEREIRA",
        )

    def test_payee_short_name(self):
        self.assertEqual(
            extract_payee("UPI/YESB/RRN123456789012/FOOD C/UPI"), "FOOD C"
        )

    def test_ref_plain_digits(self):
        self.assertEqual(
            extract_ref("UPI/SIBL/620896250123/ANISH KAARTHIK M/Paid via Su"),
            "620896250123",
        )

    def test_ref_with_rrn_dash_prefix(self):
        self.assertEqual(
            extract_ref("UPI/HDFC/RRN-620896250123/Blinkit/UPIIntent"), "620896250123"
        )

    def test_ref_with_rrn_prefix(self):
        self.assertEqual(
            extract_ref("UPI/UTIB/RRN620896250123/Google India Digital Service"),
            "620896250123",
        )

    def test_no_ref_returns_none(self):
        self.assertIsNone(extract_ref("ATM WDL SELF"))

    def test_ref_with_space_after_rrn_dash(self):
        self.assertEqual(
            extract_ref("UPI/YESB/RRN- 620896250123/SHAKUNTHALA JOGIHALLI JAYANN"),
            "620896250123",
        )

    def test_non_upi_digit_run_is_not_a_ref(self):
        """Real interest-credit shape: its only digit run is an account number."""
        self.assertIsNone(extract_ref("0000000000001234:Int.Pd:01-04-2026 to 30- 06-2026"))
        self.assertIsNone(extract_ref("2000000000 CHRIST UNIVERSITY CHRIST UNIVERSITY TRU"))

    def test_messrs_prefix_does_not_split_payee(self):
        narration = "UPI/YESB/RRN-620896250123/M/S RAMDEV FANCY AND GIFT CE"
        self.assertEqual(extract_payee(narration), "RAMDEV FANCY AND GIFT CE")
        self.assertEqual(extract_ref(narration), "620896250123")

    def test_non_upi_narration_has_no_payee(self):
        """A non-UPI credit is left alone; the credit SMS already names it."""
        self.assertIsNone(extract_payee("CHRIST UNIVERSITY CHRIST UNIVERSITY TRU"))

    def test_payee_falls_back_to_vpa_local_part(self):
        self.assertEqual(extract_payee("SOMETHING leo.stev@okicici PAID"), "LEO STEV")

    def test_numeric_vpa_is_not_a_name(self):
        self.assertIsNone(extract_payee("PAID TO 9876543210@ybl"))


class ColumnClassification(unittest.TestCase):
    """Debit vs credit comes from x position, since both columns hold bare numbers."""

    columns = Columns(withdrawal=430.0, deposit=530.0, balance=630.0)

    def test_withdrawal_column(self):
        self.assertEqual(self.columns.classify(428.0), "withdrawal")

    def test_deposit_column(self):
        self.assertEqual(self.columns.classify(535.0), "deposit")

    def test_balance_column(self):
        self.assertEqual(self.columns.classify(627.0), "balance")

    def test_midpoint_resolves_to_nearest(self):
        self.assertEqual(self.columns.classify(475.0), "withdrawal")
        self.assertEqual(self.columns.classify(485.0), "deposit")


class BalanceChecksum(unittest.TestCase):
    """The running balance validates that amounts landed in the right column."""

    def _row(self, amount, type_, balance, day=1):
        return StatementRow(
            date=date(2026, 6, day),
            amount=Decimal(amount),
            type=type_,
            narration="n",
            balance=Decimal(balance),
        )

    def test_consistent_rows_pass(self):
        rows = [
            self._row("2000.00", TxnType.CREDIT, "4015.60", day=3),
            self._row("531.00", TxnType.CREDIT, "4546.60", day=3),
            self._row("250.00", TxnType.DEBIT, "4296.60", day=5),
        ]
        self.assertEqual(validate_balances(rows), [])

    def test_flipped_type_is_caught(self):
        rows = [
            self._row("2000.00", TxnType.CREDIT, "4015.60", day=3),
            # Marked DEBIT but the balance went up — column misassignment.
            self._row("531.00", TxnType.DEBIT, "4546.60", day=3),
        ]
        problems = validate_balances(rows)
        self.assertEqual(len(problems), 1)
        self.assertIn("expected", problems[0])

    def test_missing_balance_breaks_the_chain_without_false_positives(self):
        rows = [
            self._row("2000.00", TxnType.CREDIT, "4015.60", day=3),
            StatementRow(
                date=date(2026, 6, 4),
                amount=Decimal("100.00"),
                type=TxnType.DEBIT,
                narration="n",
                balance=None,
            ),
            self._row("250.00", TxnType.DEBIT, "3665.60", day=5),
        ]
        self.assertEqual(validate_balances(rows), [])


class PdfExtraction(unittest.TestCase):
    """End-to-end extraction against a synthetic PDF in the real statement's shape.

    Skipped if reportlab is not installed — it is a test-only dependency
    (requirements-dev.txt), not needed for an actual reconciliation run.
    """

    pdf_path = None
    rows = None

    @classmethod
    def setUpClass(cls):
        try:
            import make_sample_pdf
        except ImportError:
            raise unittest.SkipTest("reportlab not installed")

        import tempfile

        from statement import parse_statement

        cls._tmp = tempfile.TemporaryDirectory()
        cls.pdf_path = Path(cls._tmp.name) / "sample_statement.pdf"
        make_sample_pdf.build(cls.pdf_path)
        cls.rows = parse_statement(cls.pdf_path)

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, "_tmp", None):
            cls._tmp.cleanup()

    def test_all_rows_extracted(self):
        self.assertEqual(len(self.rows), 10)

    def test_wrapped_narration_is_joined(self):
        """The payee spans two physical lines; truncating it is the failure mode."""
        by_ref = {r.ref: r for r in self.rows}
        self.assertEqual(by_ref["620896250001"].payee, "ANISH KAARTHIK M")
        self.assertEqual(by_ref["620896250002"].payee, "ALICIA THERESA PEREIRA")
        self.assertEqual(by_ref["620896250009"].payee, "DIVYA SHREE M V")

    def test_single_line_narration_still_works(self):
        by_ref = {r.ref: r for r in self.rows}
        self.assertEqual(by_ref["620896250007"].payee, "BLINKIT")

    def test_debit_and_credit_split_by_column(self):
        by_ref = {r.ref: r for r in self.rows}
        self.assertIs(by_ref["620896250001"].type, TxnType.CREDIT)
        self.assertIs(by_ref["620896250004"].type, TxnType.DEBIT)

    def test_amounts_are_exact_decimals(self):
        by_ref = {r.ref: r for r in self.rows}
        self.assertEqual(by_ref["620896250004"].amount, Decimal("250.00"))
        self.assertEqual(by_ref["620896250009"].amount, Decimal("1830.00"))

    def test_balances_are_parsed_and_consistent(self):
        self.assertTrue(all(r.balance is not None for r in self.rows))
        self.assertEqual(validate_balances(self.rows), [])

    def test_totals_and_footers_are_not_transactions(self):
        """'Page Total' and the footer must not become rows."""
        self.assertNotIn(Decimal("11860.00"), [r.amount for r in self.rows])


class StatementOnlyMode(unittest.TestCase):
    """--statement-only validates a real PDF before any OAuth setup exists."""

    @classmethod
    def setUpClass(cls):
        try:
            import make_sample_pdf
        except ImportError:
            raise unittest.SkipTest("reportlab not installed")

        import tempfile

        cls._tmp = tempfile.TemporaryDirectory()
        cls.pdf_path = Path(cls._tmp.name) / "sample_statement.pdf"
        make_sample_pdf.build(cls.pdf_path)

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def _run(self, *extra):
        import contextlib
        import io
        from unittest import mock

        import sib_statement_diff

        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(
            sheets, "authorize", side_effect=AssertionError("sheet touched")
        ), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = sib_statement_diff.main([str(self.pdf_path), *extra])
        return code, out.getvalue()

    def test_never_touches_the_sheet(self):
        code, _ = self._run("--statement-only")
        self.assertEqual(code, 0)

    def test_summary_counts_rows_refs_and_payees(self):
        _, out = self._run("--statement-only")
        self.assertRegex(out, r"rows\s+10 ")
        self.assertRegex(out, r"with RRN\s+9 ")  # the NEFT row carries no RRN
        self.assertIn("NEFT/", out)  # ...so its narration is listed for tuning
        self.assertIn("ANISH KAARTHIK M", out)

    def test_long_digit_runs_are_masked_in_no_rrn_listing(self):
        """An account number (longer than a 12-digit RRN) is never printed."""
        import sib_statement_diff

        self.assertEqual(
            sib_statement_diff.mask_long_numbers("0000000000001234:Int.Pd x 123456789012"),
            "************1234:Int.Pd x 123456789012",
        )

    def test_refuses_apply(self):
        import contextlib
        import io

        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                self._run("--statement-only", "--apply")



class UnredactedStatementRun(unittest.TestCase):
    """The user runs the real, unredacted statement themselves; nothing sensitive leaks."""

    def test_file_name_digits_are_masked(self):
        import sib_statement_diff

        self.assertEqual(
            sib_statement_diff.safe_name("0000000000001234_Statement_20261024.pdf"),
            "************1234_Statement_****1024.pdf",
        )
        self.assertEqual(sib_statement_diff.safe_name("week.pdf"), "week.pdf")

    def test_rules_default_to_the_private_file(self):
        from unittest import mock

        import sib_statement_diff

        explicit = Path("elsewhere.json")
        self.assertEqual(sib_statement_diff.resolve_rules(explicit), explicit)
        with mock.patch.object(sib_statement_diff, "DEFAULT_RULES", Path("no/such/file.json")):
            self.assertIsNone(sib_statement_diff.resolve_rules(None))

    def test_push_reports_success_and_failure(self):
        from types import SimpleNamespace
        from unittest import mock

        import sib_statement_diff

        with mock.patch.object(sib_statement_diff, "find_adb", return_value="adb"), \
                mock.patch.object(sib_statement_diff.subprocess, "run",
                                  return_value=SimpleNamespace(returncode=0, stdout="", stderr="")) as run:
            self.assertIn("Pushed", sib_statement_diff.push_to_phone(Path("m.json")))
            self.assertEqual(run.call_args[0][0][1:], ["push", "m.json", sib_statement_diff.PHONE_MAP_PATH])

        failed = SimpleNamespace(returncode=1, stdout="", stderr="adb: no devices/emulators found")
        with mock.patch.object(sib_statement_diff, "find_adb", return_value="adb"), \
                mock.patch.object(sib_statement_diff.subprocess, "run", return_value=failed):
            self.assertIn("no devices", sib_statement_diff.push_to_phone(Path("m.json")))

        with mock.patch.object(sib_statement_diff, "find_adb", return_value=None):
            self.assertIn("adb not found", sib_statement_diff.push_to_phone(Path("m.json")))

    def test_statement_only_refuses_push(self):
        import contextlib
        import io

        import sib_statement_diff

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            sib_statement_diff.main(["x.pdf", "--statement-only", "--push"])


class RulesOnlyMap(unittest.TestCase):
    """Category changes between statements: rules, no PDF, no Sheet."""

    def test_writes_rules_and_nothing_else(self):
        import contextlib
        import io
        import json
        import tempfile
        from unittest import mock

        import sib_statement_diff

        with tempfile.TemporaryDirectory() as tmp:
            rules = Path(tmp) / "rules.json"
            rules.write_text('{"rules": [{"pattern": "lulu", "category": "Food"}]}', encoding="utf-8")
            with mock.patch.object(sheets, "authorize", side_effect=AssertionError("sheet touched")), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = sib_statement_diff.main(["--rules-only", "--rules", str(rules), "--out", tmp])
            payload = json.loads((Path(tmp) / "merchant_map.json").read_text(encoding="utf-8"))

        self.assertEqual(code, 0)
        self.assertEqual(payload["fixes"], [])
        self.assertEqual(payload["missing"], [])
        self.assertEqual(payload["rules"], [{"pattern": "lulu", "category": "Food"}])

    def test_backfill_flag_is_opt_in(self):
        import contextlib
        import io
        import json
        import tempfile

        import sib_statement_diff

        with tempfile.TemporaryDirectory() as tmp:
            rules = Path(tmp) / "rules.json"
            rules.write_text('{"rules": []}', encoding="utf-8")
            out = Path(tmp) / "map.json"
            with contextlib.redirect_stderr(io.StringIO()):
                sib_statement_diff.main(["--rules-only", "--rules", str(rules), "--out", tmp])
                plain = json.loads((Path(tmp) / "merchant_map.json").read_text(encoding="utf-8"))
                sib_statement_diff.main(
                    ["--rules-only", "--rules", str(rules), "--out", tmp, "--backfill-categorized-by"]
                )
                flagged = json.loads((Path(tmp) / "merchant_map.json").read_text(encoding="utf-8"))
        self.assertNotIn("backfill_categorized_by", plain)
        self.assertTrue(flagged["backfill_categorized_by"])

    def test_refuses_a_pdf_or_apply(self):
        import contextlib
        import io

        import sib_statement_diff

        for argv in (["x.pdf", "--rules-only"], ["--rules-only", "--apply"]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                sib_statement_diff.main(argv)

    def test_pdf_required_otherwise(self):
        import contextlib
        import io

        import sib_statement_diff

        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            sib_statement_diff.main([])

class EncryptedStatement(unittest.TestCase):
    """Bank-issued statements are usually password-protected."""

    @classmethod
    def setUpClass(cls):
        try:
            import make_sample_pdf
        except ImportError:
            raise unittest.SkipTest("reportlab not installed")

        import tempfile

        cls._tmp = tempfile.TemporaryDirectory()
        cls.pdf_path = Path(cls._tmp.name) / "locked.pdf"
        make_sample_pdf.build(cls.pdf_path, encrypt="s3cret")

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def test_no_password_asks_for_one(self):
        import statement

        with self.assertRaises(statement.StatementPasswordRequired):
            statement.parse_statement(self.pdf_path)

    def test_wrong_password_says_so(self):
        import statement

        with self.assertRaises(statement.StatementPasswordRequired) as ctx:
            statement.parse_statement(self.pdf_path, password="nope")
        self.assertIn("wrong", str(ctx.exception))

    def test_right_password_extracts_every_row(self):
        import statement

        rows = statement.parse_statement(self.pdf_path, password="s3cret")
        self.assertEqual(len(rows), 10)
        self.assertEqual(validate_balances(rows), [])


class CashRowsSkipped(unittest.TestCase):
    """The statement never contains the wallet, so cash rows are not matched."""

    def test_cash_rows_are_skipped(self):
        header = ["Transaction ID", "Amount", "Date", "Merchant", "Type",
                  "Account Last4", "Raw Ref", "Category", "Source"]
        bank = ["h1", 100, 46000.5, "UPI", "DEBIT", "6825", "620896250123", "Food", "sms"]
        cash = ["h2", 20, 46000.6, "Chai", "DEBIT", "cash", "", "Food", "cash"]
        rows = sheets.rows_from_values([header, bank, cash])
        self.assertEqual([r.transaction_id for r in rows], ["h1"])

    def test_rows_without_a_source_column_still_load(self):
        header = ["Transaction ID", "Amount", "Date", "Merchant", "Type", "Account Last4", "Raw Ref", "Category"]
        old = ["h1", 100, 46000.5, "UPI", "DEBIT", "6825", "620896250123", "Food"]
        self.assertEqual(len(sheets.rows_from_values([header, old])), 1)


if __name__ == "__main__":
    unittest.main()
