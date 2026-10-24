"""SIB PDF statement extraction.

This is the bank-specific half of reconciliation, and it mirrors the Android
app's per-bank parser rule: precise patterns for one known layout, and a loud
failure when the layout does not match, rather than a fuzzy extractor that
silently produces plausible-but-wrong rows.

Layout (SIB savings account statement, verified against a real statement):

    DATE      PARTICULARS              CHQ.NO.  WITHDRAWALS  DEPOSITS   BALANCE
    03-06-26  UPI/SIBL/<rrn>/ANISH                              531.00  4,546.60Cr
              KAARTHIK M/Paid via Su
    05-06-26  UPI/FDRL/<rrn>/AKRAM          250.00                      4,496.60Cr
              PASHA/UPI

Three things make this harder than a line regex:

* **Narration wraps.** The amount and balance sit on the first physical line;
  the payee's name continues on the next one. Joining them is mandatory or every
  multi-word payee gets truncated.
* **Withdrawals and deposits are separate columns**, but ``extract_text``
  collapses them into one whitespace-separated number. Debit vs credit therefore
  comes from the *x position* of the amount, resolved against the column headers
  on each page — not from keywords in the narration.
* **The running balance is a free checksum.** Every parsed row is validated
  against the previous balance, which catches column misassignment.
"""

from __future__ import annotations

import re
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterator, NamedTuple, Optional

import pdfplumber
from pdfminer.pdfdocument import PDFPasswordIncorrect
from pdfplumber.utils.exceptions import PdfminerException

from models import StatementRow, TxnType

DATE_FORMATS = ("%d-%m-%y", "%d/%m/%y", "%d-%m-%Y", "%d/%m/%Y", "%d-%b-%Y")

#: Leading date cell, e.g. "03-06-26".
DATE_RE = re.compile(r"^\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\d{2,4}$")

#: An amount cell: "2,000.00", "4,015.60Cr", "104,708.21", "-7,113.40Cr".
#: The leading minus matters: without it an overdrawn balance is not recognised
#: as an amount, leaks into the narration, and truncates the payee.
AMOUNT_RE = re.compile(r"^-?[\d,]+\.\d{2}(?:Cr|Dr|CR|DR)?$")

#: SIB UPI narration: UPI/<bank>/<rrn>/<payee>[/<purpose>]
#: Real examples: UPI/SIBL/<rrn>/ANISH KAARTHIK M/Paid via Su
#:               UPI/HDFC/RRN-<rrn>/Blinkit/UPIIntent
#:               UPI/UTIB/RRN<rrn>/Google India Digital Service
UPI_NARRATION_RE = re.compile(
    r"""^UPI[/-]
    (?P<bank>[A-Za-z]{4})[/-]
    (?P<ref>[^/]*)[/-]
    (?P<payee>[^/]+?)
    (?:[/-](?P<purpose>.*))?$""",
    re.VERBOSE,
)

#: The reference segment may be "620896250123", "RRN-620896250123" or
#: "RRN620896250123". Take the digit run.
REF_DIGITS_RE = re.compile(r"(\d{6,})")

#: "M/S" (Messrs) ahead of a business name. Its slash would otherwise end the
#: payee field after "M". Real example: UPI/YESB/RRN-<rrn>/M/S RAMDEV FANCY AND GIFT CE
MESSRS_RE = re.compile(r"(?<=/)M/S\.?\s*", re.IGNORECASE)

#: Fallback when narration is not UPI-shaped: derive a name from a VPA.
VPA_RE = re.compile(r"(?P<local>[\w.\-]{3,})@[\w.\-]+")

#: Header, footer and summary lines that are not transactions.
NOISE_RE = re.compile(
    r"page total|grand total|opening balance|closing balance|"
    r"statement of account|^\s*date\s+particulars|page \d+ of \d+|"
    r"system generated statement|visit us at|customer care|"
    r"^\s*ifsc|^\s*customer id|^\s*a/c no|^\s*mode of opr|^\s*currency code|"
    r"south indian bank|date/time:",
    re.IGNORECASE,
)

#: Column labels used to locate the amount columns on each page.
WITHDRAWAL_HEADER = "WITHDRAWALS"
DEPOSIT_HEADER = "DEPOSITS"
BALANCE_HEADER = "BALANCE"


class StatementParseError(RuntimeError):
    """Raised when a PDF yields no usable transaction rows."""


class StatementPasswordRequired(StatementParseError):
    """The PDF is encrypted and no (or the wrong) password was given.

    Bank-issued statements usually are. The caller asks for the password; it is
    never stored, logged, or written anywhere.
    """


def _open(pdf_path: Path, password: Optional[str]):
    try:
        return pdfplumber.open(str(pdf_path), password=password or "")
    except PdfminerException as exc:
        if exc.args and isinstance(exc.args[0], PDFPasswordIncorrect):
            raise StatementPasswordRequired(
                f"{pdf_path.name} is password-protected"
                + (" and that password is wrong." if password else ".")
            ) from None
        raise


class Columns(NamedTuple):
    """x-midpoints of the three amount columns, read from a page's header."""

    withdrawal: float
    deposit: float
    balance: float

    def classify(self, x_mid: float) -> str:
        """Return 'withdrawal', 'deposit' or 'balance' for an amount's position."""
        candidates = (
            ("withdrawal", abs(x_mid - self.withdrawal)),
            ("deposit", abs(x_mid - self.deposit)),
            ("balance", abs(x_mid - self.balance)),
        )
        return min(candidates, key=lambda c: c[1])[0]


def _parse_date(raw: str):
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(raw.strip(), fmt).date()
        except ValueError:
            continue
    return None


def _parse_amount(raw: str) -> Optional[Decimal]:
    cleaned = re.sub(r"(?i)(cr|dr)$", "", raw.strip()).replace(",", "")
    try:
        return Decimal(cleaned)
    except (InvalidOperation, ValueError):
        return None


def extract_ref(narration: str) -> Optional[str]:
    """Pull the RRN out of the narration's reference segment.

    This is the join key against TransactionEntity.rawRef, which SibParser fills
    from the SMS's ``RRN:<digits>``.

    Non-UPI narration yields None. The sheet's refs all come from UPI SMS, so a
    digit run from anywhere else can never join, and in a real statement the
    interest-credit narration's only digit run is the account number itself.
    """
    m = UPI_NARRATION_RE.match(_normalize(narration))
    if m:
        digits = REF_DIGITS_RE.search(m.group("ref"))
        if digits:
            return digits.group(1)
    return None


def extract_payee(narration: str) -> Optional[str]:
    """Pull the payee name out of the narration.

    Returns None rather than a guess when the narration is not UPI-shaped — an
    unnamed row goes to the report instead of writing a made-up merchant into a
    financial record.
    """
    m = UPI_NARRATION_RE.match(_normalize(narration))
    if m:
        payee = _clean_payee(m.group("payee"))
        if payee:
            return payee

    m = VPA_RE.search(narration)
    if m:
        local = m.group("local")
        if not local.isdigit():  # a phone-number VPA is not a name
            return _clean_payee(local.replace(".", " ").replace("-", " "))
    return None


def _normalize(narration: str) -> str:
    """Remove the slash inside a "M/S" prefix so it can't split the payee field."""
    return MESSRS_RE.sub("", narration)


def _clean_payee(raw: str) -> Optional[str]:
    payee = re.sub(r"\s+", " ", raw).strip(" -/.").strip()
    payee = re.sub(r"^(?:mr|mrs|ms|dr)\.?\s+", "", payee, flags=re.IGNORECASE)
    if len(payee) < 2 or payee.isdigit():
        return None
    # Reject bank plumbing that is not a merchant.
    if payee.upper() in {
        "UPI", "NEFT", "IMPS", "RTGS", "SIBL", "PAYMENT", "COLLECT",
        "UPIINTENT", "PAID VIA SU", "PAYME",
    }:
        return None
    return payee.upper()


def _page_words(page) -> list[dict]:
    """Words on a page, with fake-bold overprinting collapsed.

    SIB prints the "STATEMENT OF ACCOUNT FOR THE PERIOD ..." heading in fake bold:
    each character is drawn three times at nearly the same position, so it reads
    as "SSSTTTAAATTT...". The NOISE_RE filter then misses it, and the heading gets
    glued onto the previous row's payee. dedupe_chars drops those repeats.
    """
    return page.dedupe_chars().extract_words(use_text_flow=False, keep_blank_chars=False)


def _find_columns(words: list[dict]) -> Optional[Columns]:
    """Locate the amount columns from a page's header row."""
    found: dict[str, float] = {}
    for word in words:
        text = word["text"].strip().upper()
        for header in (WITHDRAWAL_HEADER, DEPOSIT_HEADER, BALANCE_HEADER):
            if text == header and header not in found:
                found[header] = (word["x0"] + word["x1"]) / 2
    if len(found) == 3:
        return Columns(
            withdrawal=found[WITHDRAWAL_HEADER],
            deposit=found[DEPOSIT_HEADER],
            balance=found[BALANCE_HEADER],
        )
    return None


def _group_lines(words: list[dict], tolerance: float = 3.0) -> list[list[dict]]:
    """Group words into visual lines by their vertical position."""
    lines: list[list[dict]] = []
    for word in sorted(words, key=lambda w: (round(w["top"], 1), w["x0"])):
        if lines and abs(lines[-1][0]["top"] - word["top"]) <= tolerance:
            lines[-1].append(word)
        else:
            lines.append([word])
    return [sorted(line, key=lambda w: w["x0"]) for line in lines]


class _Pending:
    """A transaction row being assembled across wrapped narration lines."""

    __slots__ = ("date", "narration", "withdrawal", "deposit", "balance", "page")

    def __init__(self, date, page: int):
        self.date = date
        self.page = page
        self.narration: list[str] = []
        self.withdrawal: Optional[Decimal] = None
        self.deposit: Optional[Decimal] = None
        self.balance: Optional[Decimal] = None

    def finish(self) -> Optional[StatementRow]:
        amount = self.withdrawal if self.withdrawal is not None else self.deposit
        if amount is None:
            return None
        narration = re.sub(r"\s+", " ", " ".join(self.narration)).strip()
        return StatementRow(
            date=self.date,
            amount=amount,
            type=TxnType.DEBIT if self.withdrawal is not None else TxnType.CREDIT,
            narration=narration,
            ref=extract_ref(narration),
            payee=extract_payee(narration),
            balance=self.balance,
            page=self.page,
        )


def _iter_rows(pdf_path: Path, password: Optional[str] = None) -> Iterator[StatementRow]:
    with _open(pdf_path, password) as pdf:
        columns: Optional[Columns] = None
        pending: Optional[_Pending] = None

        for page_no, page in enumerate(pdf.pages, start=1):
            words = _page_words(page)
            columns = _find_columns(words) or columns
            if columns is None:
                continue  # cover page / no table yet

            for line in _group_lines(words):
                text = " ".join(w["text"] for w in line)
                if NOISE_RE.search(text):
                    if pending is not None:
                        row = pending.finish()
                        if row:
                            yield row
                        pending = None
                    continue

                first = line[0]["text"].strip()
                if DATE_RE.match(first):
                    if pending is not None:
                        row = pending.finish()
                        if row:
                            yield row
                    parsed = _parse_date(first)
                    if parsed is None:
                        pending = None
                        continue
                    pending = _Pending(parsed, page_no)
                    _consume(pending, line[1:], columns)
                elif pending is not None:
                    _consume(pending, line, columns)

        if pending is not None:
            row = pending.finish()
            if row:
                yield row


def _consume(pending: _Pending, words: list[dict], columns: Columns) -> None:
    """Route each word on a line into an amount column or the narration."""
    for word in words:
        text = word["text"].strip()
        if AMOUNT_RE.match(text):
            value = _parse_amount(text)
            if value is None:
                continue
            slot = columns.classify((word["x0"] + word["x1"]) / 2)
            if slot == "withdrawal" and pending.withdrawal is None:
                pending.withdrawal = value
            elif slot == "deposit" and pending.deposit is None:
                pending.deposit = value
            elif slot == "balance":
                pending.balance = value
        else:
            pending.narration.append(text)


def validate_balances(rows: list[StatementRow]) -> list[str]:
    """Cross-check every row against the running balance.

    The statement's balance column is a free checksum: a debit must decrease it
    by exactly the amount and a credit must increase it. A mismatch means the
    amount landed in the wrong column, so the report says so rather than
    silently trusting a wrong debit/credit flag.
    """
    problems: list[str] = []
    previous: Optional[Decimal] = None

    for row in rows:
        if row.balance is None:
            previous = None
            continue
        if previous is not None:
            delta = row.balance - previous
            expected = row.amount if row.type is TxnType.CREDIT else -row.amount
            if delta != expected:
                problems.append(
                    f"p{row.page} {row.date.isoformat()} {row.amount} "
                    f"{row.type.value}: balance moved {delta:+}, expected {expected:+}"
                )
        previous = row.balance
    return problems


def parse_statement(pdf_path: Path, password: Optional[str] = None) -> list[StatementRow]:
    """Extract transaction rows from a SIB PDF statement."""
    rows = list(_iter_rows(pdf_path, password))
    if not rows:
        raise StatementParseError(
            f"No transaction rows extracted from {pdf_path.name}.\n"
            f"The layout does not match what statement.py expects. Run:\n"
            f"    python sib_statement_diff.py '{pdf_path}' --inspect\n"
            f"and tune the patterns from the dumped structure."
        )
    return rows


def inspect(pdf_path: Path, max_pages: int = 2, max_lines: int = 45, password: Optional[str] = None) -> str:
    """Dump what pdfplumber sees, so the extractor can be tuned against reality."""
    out: list[str] = []
    with _open(pdf_path, password) as pdf:
        out.append(f"{pdf_path.name}: {len(pdf.pages)} pages")
        columns: Optional[Columns] = None

        for page_no, page in enumerate(pdf.pages[:max_pages], start=1):
            out.append(f"\n{'=' * 78}\nPAGE {page_no}\n{'=' * 78}")
            words = _page_words(page)
            found = _find_columns(words)
            columns = found or columns
            out.append(f"columns on this page: {found or '(not found — reusing prior)'}")

            out.append(f"\n--- grouped lines (first {max_lines}) ---")
            for line in _group_lines(words)[:max_lines]:
                text = " ".join(w["text"] for w in line)
                first = line[0]["text"].strip()
                if NOISE_RE.search(text):
                    tag = "NOISE"
                elif DATE_RE.match(first):
                    tag = "TXN  "
                else:
                    tag = "cont "
                out.append(f"{tag}| {text}")

                if columns and (tag == "TXN  " or tag == "cont "):
                    for w in line:
                        if AMOUNT_RE.match(w["text"].strip()):
                            mid = (w["x0"] + w["x1"]) / 2
                            out.append(
                                f"      -> amount {w['text']:>14} "
                                f"x={mid:7.1f} => {columns.classify(mid)}"
                            )
    return "\n".join(out)
