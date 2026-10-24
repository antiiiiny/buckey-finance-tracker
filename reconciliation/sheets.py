"""Google Sheets access for reconciliation.

Reads the rows SheetsSyncWorker appended, and writes recovered merchant names
back into them.

Auth is separate from the Android app's: this uses a desktop OAuth client
(credentials.json) and caches a token in token.json. Both are gitignored.

Column layout is fixed by GoogleSheetsRepository.HEADERS:
    A transaction_id | B amount | C date | D merchant | E type
    F account_last4  | G raw_ref | H category
"""

from __future__ import annotations

import datetime as dt
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Optional

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build

from models import SheetRow, TxnType

SCOPES = ["https://www.googleapis.com/auth/spreadsheets"]
SHEET_NAME = "Transactions"
DATA_RANGE = f"{SHEET_NAME}!A:H"
MERCHANT_COLUMN = "D"
HEADER_ROWS = 1

#: Sheets' epoch for serial date values.
_SHEETS_EPOCH = dt.datetime(1899, 12, 30)


class SheetsError(RuntimeError):
    pass


def authorize(credentials_path: Path, token_path: Path):
    """Run the desktop OAuth flow, caching the token between runs."""
    creds: Optional[Credentials] = None
    if token_path.exists():
        creds = Credentials.from_authorized_user_file(str(token_path), SCOPES)

    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            if not credentials_path.exists():
                raise SheetsError(
                    f"Missing {credentials_path}. Create a Desktop OAuth client in "
                    f"Google Cloud Console (same project as the app), download the "
                    f"JSON, and save it there."
                )
            flow = InstalledAppFlow.from_client_secrets_file(
                str(credentials_path), SCOPES
            )
            creds = flow.run_local_server(port=0)
        token_path.write_text(creds.to_json(), encoding="utf-8")

    return build("sheets", "v4", credentials=creds, cache_discovery=False)


def _to_decimal(value: Any) -> Optional[Decimal]:
    if value is None or value == "":
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _to_datetime(value: Any) -> Optional[dt.datetime]:
    """Parse column C, which may come back as a string or a Sheets serial number.

    GoogleSheetsRepository appends with valueInputOption=USER_ENTERED, so Sheets
    coerces the "yyyy-MM-dd HH:mm:ss" string it was given into a real datetime
    value. Read back UNFORMATTED it is a float; read back FORMATTED it is
    whatever the sheet's locale renders. Handle both.
    """
    if isinstance(value, (int, float)):
        return _SHEETS_EPOCH + dt.timedelta(days=float(value))
    if not isinstance(value, str) or not value.strip():
        return None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d", "%d/%m/%Y %H:%M:%S", "%d/%m/%Y"):
        try:
            return dt.datetime.strptime(value.strip(), fmt)
        except ValueError:
            continue
    return None


def normalize_ref(value: Any) -> str:
    """Normalize an RRN read from the sheet back to a digit string.

    A 12-digit RRN written with USER_ENTERED is stored by Sheets as a *number*,
    so it reads back as 620896250123.0 rather than "620896250123". Without this
    the RRN join silently matches nothing.
    """
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    if isinstance(value, int):
        return str(value)
    text = str(value).strip()
    if re.fullmatch(r"\d+\.0", text):
        return text[:-2]
    return text


def fetch_rows(service, sheet_id: str) -> list[SheetRow]:
    """Read every synced transaction row, preserving 1-based sheet row numbers."""
    try:
        response = (
            service.spreadsheets()
            .values()
            .get(
                spreadsheetId=sheet_id,
                range=DATA_RANGE,
                valueRenderOption="UNFORMATTED_VALUE",
                dateTimeRenderOption="SERIAL_NUMBER",
            )
            .execute()
        )
    except Exception as exc:  # noqa: BLE001 - surfaced verbatim to the operator
        raise SheetsError(f"Could not read {DATA_RANGE} from {sheet_id}: {exc}") from exc

    values = response.get("values", [])
    rows: list[SheetRow] = []

    for offset, raw in enumerate(values[HEADER_ROWS:], start=HEADER_ROWS + 1):
        padded = list(raw) + [""] * (8 - len(raw))
        transaction_id = str(padded[0]).strip()
        amount = _to_decimal(padded[1])
        when = _to_datetime(padded[2])
        if not transaction_id or amount is None or when is None:
            continue  # header repeat, blank row, or manual edit

        type_raw = str(padded[4]).strip().upper()
        rows.append(
            SheetRow(
                row_number=offset,
                transaction_id=transaction_id,
                amount=amount,
                date=when,
                merchant=str(padded[3]).strip(),
                type=TxnType.CREDIT if type_raw == "CREDIT" else TxnType.DEBIT,
                account_last4=normalize_ref(padded[5]),
                raw_ref=normalize_ref(padded[6]),
                category=str(padded[7]).strip(),
            )
        )
    return rows


def apply_merchant_fixes(service, sheet_id: str, fixes) -> int:
    """Write recovered merchant names into column D. Returns rows updated."""
    if not fixes:
        return 0

    data = [
        {
            "range": f"{SHEET_NAME}!{MERCHANT_COLUMN}{fix.row_number}",
            "values": [[fix.new_merchant]],
        }
        for fix in fixes
    ]
    body = {"valueInputOption": "RAW", "data": data}
    try:
        response = (
            service.spreadsheets()
            .values()
            .batchUpdate(spreadsheetId=sheet_id, body=body)
            .execute()
        )
    except Exception as exc:  # noqa: BLE001
        raise SheetsError(f"Merchant write-back failed: {exc}") from exc
    return int(response.get("totalUpdatedCells", 0))
