"""Match statement rows against synced sheet rows, and diff what is left.

Two tiers, deliberately ordered strongest-first:

  A. RRN exact       — the RRN is a bank-issued unique reference. This is why
                       TransactionEntity.rawRef exists.
  B. amount + date   — fallback for rows with no RRN. Only accepted when
     (+/- window)     exactly one candidate remains; a tie is reported as
                       ambiguous rather than guessed, because a wrong merchant
                       written into a financial record is worse than a gap.

Anything still unmatched is the actual output of the exercise:
  * statement-only -> the SMS parser missed a transaction
  * sms-only       -> in the sheet but not the statement (timing, or a bad parse)
"""

from __future__ import annotations

from collections import defaultdict
from datetime import timedelta
from decimal import Decimal

from models import (
    Confidence,
    Match,
    MerchantFix,
    Reconciliation,
    SheetRow,
    StatementRow,
)

CENTS = Decimal("0.01")


def _money(value: Decimal) -> Decimal:
    return value.quantize(CENTS)


def scope_to_overlap(
    sheet_rows: list[SheetRow],
    statement_rows: list[StatementRow],
    date_window_days: int = 1,
) -> tuple[list[SheetRow], list[StatementRow], list[StatementRow]]:
    """Trim both sides to the dates they both cover.

    A statement usually spans more than the app has been capturing. Without this,
    every statement row from before the first synced SMS lands in statement-only,
    which claims the parser missed it, and every sheet row after the statement
    ends lands in sms-only. Both are noise that buries the real misses.

    Returns (sheet rows in range, statement rows in range, statement rows from
    before capture started). The last list is reported as a count, not as misses.
    """
    if not sheet_rows or not statement_rows:
        return sheet_rows, statement_rows, []

    window = timedelta(days=date_window_days)
    capture_start = min(row.date.date() for row in sheet_rows)
    stmt_start = min(row.date for row in statement_rows)
    stmt_end = max(row.date for row in statement_rows)

    sheet_in = [
        row
        for row in sheet_rows
        if stmt_start - window <= row.date.date() <= stmt_end + window
    ]
    before = [row for row in statement_rows if row.date < capture_start - window]
    stmt_in = [row for row in statement_rows if row.date >= capture_start - window]
    return sheet_in, stmt_in, before


def reconcile(
    sheet_rows: list[SheetRow],
    statement_rows: list[StatementRow],
    date_window_days: int = 1,
    allow_fuzzy_merchant: bool = False,
) -> Reconciliation:
    matched: list[Match] = []
    ambiguous: list[StatementRow] = []

    unmatched_sheet = {row.transaction_id: row for row in sheet_rows}
    unmatched_statement: list[StatementRow] = []

    # --- Tier A: RRN exact -------------------------------------------------
    by_rrn: dict[str, list[SheetRow]] = defaultdict(list)
    for row in sheet_rows:
        if row.raw_ref:
            by_rrn[row.raw_ref].append(row)

    for stmt in statement_rows:
        candidates = [
            row
            for row in by_rrn.get(stmt.ref or "", [])
            if row.transaction_id in unmatched_sheet
        ]
        if len(candidates) == 1:
            sheet_row = candidates[0]
            matched.append(Match(sheet_row, stmt, Confidence.RRN_EXACT))
            del unmatched_sheet[sheet_row.transaction_id]
        else:
            unmatched_statement.append(stmt)

    # --- Tier B: amount + date window + type -------------------------------
    still_unmatched: list[StatementRow] = []
    window = timedelta(days=date_window_days)

    for stmt in unmatched_statement:
        candidates = [
            row
            for row in unmatched_sheet.values()
            if row.type == stmt.type
            and _money(row.amount) == _money(stmt.amount)
            and abs(row.date.date() - stmt.date) <= window
        ]
        if len(candidates) == 1:
            sheet_row = candidates[0]
            matched.append(Match(sheet_row, stmt, Confidence.AMOUNT_DATE))
            del unmatched_sheet[sheet_row.transaction_id]
        elif len(candidates) > 1:
            ambiguous.append(stmt)
        else:
            still_unmatched.append(stmt)

    return Reconciliation(
        matched=matched,
        sms_only=sorted(unmatched_sheet.values(), key=lambda r: r.date),
        statement_only=sorted(still_unmatched, key=lambda r: r.date),
        ambiguous=ambiguous,
        merchant_fixes=propose_merchant_fixes(matched, allow_fuzzy_merchant),
    )


def propose_merchant_fixes(
    matched: list[Match], allow_fuzzy: bool = False
) -> list[MerchantFix]:
    """Find matched rows whose merchant is generic but whose statement has a name.

    Restricted to RRN-exact matches unless ``allow_fuzzy`` is set: an
    amount+date match is a strong hint, not an identity, and this writes into
    the user's financial record.
    """
    fixes: list[MerchantFix] = []
    for match in matched:
        if match.confidence is not Confidence.RRN_EXACT and not allow_fuzzy:
            continue
        if not match.sheet_row.has_generic_merchant:
            continue
        payee = match.statement_row.payee
        if not payee or payee.strip().upper() == match.sheet_row.merchant.strip().upper():
            continue
        fixes.append(
            MerchantFix(
                transaction_id=match.sheet_row.transaction_id,
                row_number=match.sheet_row.row_number,
                old_merchant=match.sheet_row.merchant,
                new_merchant=payee,
                rrn=match.sheet_row.raw_ref,
                confidence=match.confidence,
            )
        )
    return fixes
