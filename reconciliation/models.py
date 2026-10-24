"""Shared data types for statement reconciliation.

Money is always Decimal, never float. These values end up written back into a
financial record, and binary float rounding is not acceptable there.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import Optional


class TxnType(str, Enum):
    DEBIT = "DEBIT"
    CREDIT = "CREDIT"


#: Merchant strings that carry no information. SibParser writes "UPI" on every
#: debit because the debit SMS has no payee; these are exactly the rows the
#: statement is meant to repair. Keep in sync with
#: CategoryEditorScreen.isGenericMerchant().
GENERIC_MERCHANTS = {"", "UPI", "NEFT", "IMPS", "RTGS", "UNKNOWN"}


@dataclass(frozen=True)
class StatementRow:
    """One transaction line extracted from the bank's PDF statement."""

    date: date
    amount: Decimal
    type: TxnType
    narration: str
    ref: Optional[str] = None
    payee: Optional[str] = None
    balance: Optional[Decimal] = None
    page: int = 0

    def describe(self) -> str:
        return (
            f"{self.date.isoformat()}  {self.type.value:<6} "
            f"{self.amount:>12,.2f}  {self.payee or '-':<28} "
            f"ref={self.ref or '-'}"
        )


@dataclass(frozen=True)
class SheetRow:
    """One row of the synced Google Sheet, as written by GoogleSheetsRepository.

    ``row_number`` is the 1-based Sheets row index and is required for
    write-back, so rows must never be re-sorted before use.
    """

    row_number: int
    transaction_id: str
    amount: Decimal
    date: datetime
    merchant: str
    type: TxnType
    account_last4: str
    raw_ref: str
    category: str

    @property
    def has_generic_merchant(self) -> bool:
        return self.merchant.strip().upper() in GENERIC_MERCHANTS

    def describe(self) -> str:
        return (
            f"{self.date.date().isoformat()}  {self.type.value:<6} "
            f"{self.amount:>12,.2f}  {self.merchant or '-':<28} "
            f"ref={self.raw_ref or '-'}"
        )


class Confidence(str, Enum):
    """How a statement row was tied to a sheet row."""

    RRN_EXACT = "rrn-exact"
    AMOUNT_DATE = "amount-date"


@dataclass(frozen=True)
class Match:
    sheet_row: SheetRow
    statement_row: StatementRow
    confidence: Confidence


@dataclass(frozen=True)
class MerchantFix:
    """A proposed repair of a generic merchant, sourced from the statement."""

    transaction_id: str
    row_number: int
    old_merchant: str
    new_merchant: str
    rrn: str
    confidence: Confidence


@dataclass
class Reconciliation:
    matched: list[Match]
    sms_only: list[SheetRow]
    statement_only: list[StatementRow]
    ambiguous: list[StatementRow]
    merchant_fixes: list[MerchantFix]
