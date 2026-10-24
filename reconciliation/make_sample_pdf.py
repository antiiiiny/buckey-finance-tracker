"""Generate a synthetic SIB-shaped statement PDF for testing the extractor.

Not part of the reconciliation run. This exists so `statement.py` can be tested
end-to-end without committing a real bank statement to the repo.

The layout mirrors a real SIB savings statement: six columns, narration that
wraps onto a second line, withdrawals and deposits in separate columns, and a
running balance with a `Cr` suffix.

    python make_sample_pdf.py sample.pdf
"""

from __future__ import annotations

import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

# x positions chosen to match the proportions of a real statement.
COL_DATE = 40
COL_PARTICULARS = 105
COL_CHQ = 300
COL_WITHDRAWAL_RIGHT = 430
COL_DEPOSIT_RIGHT = 510
COL_BALANCE_RIGHT = 580

#: (date, narration_line_1, narration_line_2, withdrawal, deposit, balance)
#: Narration formats and payees are taken from a real statement; RRNs are fake.
ROWS = [
    ("03-06-26", "NEFT/123456/CHRIST UNIVERSITY CHRIST", "UNIVERSITY TRU",
     None, "2,000.00", "4,015.60"),
    ("03-06-26", "UPI/SIBL/620896250001/ANISH", "KAARTHIK M/Paid via Su",
     None, "531.00", "4,546.60"),
    ("04-06-26", "UPI/SIBL/620896250002/ALICIA", "THERESA PEREIRA/Payme",
     None, "100.00", "4,646.60"),
    ("05-06-26", "UPI/SIBL/620896250003/RHEA", "GRACY/UPI",
     None, "100.00", "4,746.60"),
    ("05-06-26", "UPI/FDRL/620896250004/AKRAM", "PASHA/UPI",
     "250.00", None, "4,496.60"),
    ("05-06-26", "UPI/YESB/620896250005/LINGARAJU", "SHWETHA/UPI",
     "280.00", None, "4,216.60"),
    ("06-06-26", "UPI/YESB/RRN-620896250006/Santosh/UPI", None,
     "70.00", None, "4,146.60"),
    ("07-06-26", "UPI/HDFC/RRN-620896250007/Blinkit/UPIIntent", None,
     "969.00", None, "3,177.60"),
    ("07-06-26", "UPI/UTIB/RRN620896250008/Google India", "Digital Service",
     "235.00", None, "2,942.60"),
    ("07-06-26", "UPI/SBIN/RRN620896250009/DIVYA SHREE", "M V/UPI",
     "1,830.00", None, "1,112.60"),
]


def build(path: Path, encrypt: str | None = None) -> None:
    """Write the sample statement; ``encrypt`` password-protects it, like a real one."""
    c = canvas.Canvas(str(path), pagesize=A4, encrypt=encrypt)
    _, height = A4
    y = height - 60

    c.setFont("Helvetica-Bold", 9)
    c.drawString(COL_DATE, y, "SOUTH INDIAN Bank")
    y -= 24
    c.setFont("Helvetica", 8)
    c.drawString(COL_DATE, y, "STATEMENT OF ACCOUNT FOR THE PERIOD FROM 01-06-2026 TO 02-09-2026")
    y -= 22

    # Header row — statement.py locates the amount columns from these labels.
    c.setFont("Helvetica-Bold", 8)
    c.drawString(COL_DATE, y, "DATE")
    c.drawString(COL_PARTICULARS, y, "PARTICULARS")
    c.drawString(COL_CHQ, y, "CHQ.NO.")
    c.drawRightString(COL_WITHDRAWAL_RIGHT, y, "WITHDRAWALS")
    c.drawRightString(COL_DEPOSIT_RIGHT, y, "DEPOSITS")
    c.drawRightString(COL_BALANCE_RIGHT, y, "BALANCE")
    y -= 18

    c.setFont("Helvetica", 8)
    for date, line1, line2, withdrawal, deposit, balance in ROWS:
        c.drawString(COL_DATE, y, date)
        c.drawString(COL_PARTICULARS, y, line1)
        if withdrawal:
            c.drawRightString(COL_WITHDRAWAL_RIGHT, y, withdrawal)
        if deposit:
            c.drawRightString(COL_DEPOSIT_RIGHT, y, deposit)
        c.drawRightString(COL_BALANCE_RIGHT, y, f"{balance}Cr")
        y -= 11
        if line2:
            c.drawString(COL_PARTICULARS, y, line2)
            y -= 11
        y -= 4

    y -= 10
    c.drawString(COL_DATE, y, "Page Total :")
    c.drawRightString(COL_WITHDRAWAL_RIGHT, y, "11,860.00")
    c.drawRightString(COL_DEPOSIT_RIGHT, y, "2,731.00")
    y -= 20
    c.drawString(COL_DATE, y, "This is a system generated statement and does not require any signature")

    c.showPage()
    c.save()


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "sample_statement.pdf")
    build(out)
    print(f"wrote {out}")
