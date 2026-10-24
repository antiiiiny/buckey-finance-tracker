#!/usr/bin/env python3
"""Weekly reconciliation: SIB PDF statement vs the synced Google Sheet.

Stage 7. Standalone — not part of the Android build.

Two jobs:
  1. Diff the statement against the sheet and report matched / SMS-only /
     statement-only. Statement-only rows are the ones the SMS parser missed.
  2. Recover debit merchant names. SIB's debit SMS carries no payee, so every
     debit reaches the sheet as "UPI"; the statement narration has the real
     name. Matched on RRN, the statement is the source of truth for merchant.

Write-back is off by default. Pass --apply to update the sheet.

    python sib_statement_diff.py statement.pdf
    python sib_statement_diff.py statement.pdf --apply
    python sib_statement_diff.py statement.pdf --inspect
    python sib_statement_diff.py statement.pdf --statement-only
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Optional

import matching
import sheets
import statement as statement_mod
from models import Reconciliation, StatementRow

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
DEFAULT_CREDENTIALS = HERE / "credentials.json"
DEFAULT_TOKEN = HERE / "token.json"
#: The user's category rules. Gitignored: it names real people.
DEFAULT_RULES = HERE / "private" / "category_rules.json"
#: Where --push puts the merchant map; the app's file picker opens Downloads.
PHONE_MAP_PATH = "/sdcard/Download/merchant_map.json"
DEFAULT_OUT = HERE / "out"


def read_sheet_id(explicit: Optional[str]) -> str:
    """Reuse the app's configured sheet ID rather than keeping a second copy."""
    if explicit:
        return explicit

    secrets = REPO_ROOT / "secrets.properties"
    if secrets.exists():
        for line in secrets.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "GOOGLE_SHEET_ID" and value.strip():
                return value.strip()

    raise SystemExit(
        "No sheet ID. Pass --sheet-id, or set GOOGLE_SHEET_ID in secrets.properties."
    )


def _section(title: str, body: list[str]) -> str:
    rule = "=" * 78
    lines = [rule, title, rule]
    lines.extend(body if body else ["  (none)"])
    return "\n".join(lines)


def render_report(
    result: Reconciliation, pdf_name: str, before_capture: int = 0, out_of_range: int = 0
) -> str:
    total = len(result.matched) + len(result.statement_only) + len(result.ambiguous)
    by_rrn = sum(1 for m in result.matched if m.confidence.value == "rrn-exact")

    parts = [
        f"Reconciliation — {pdf_name} — {datetime.now():%Y-%m-%d %H:%M}",
        "",
        f"  statement rows      {total}",
        f"  matched             {len(result.matched)}  ({by_rrn} by RRN)",
        f"  statement-only      {len(result.statement_only)}   <- no SMS recorded (missed, or never sent)",
        f"  sms-only            {len(result.sms_only)}",
        f"  ambiguous           {len(result.ambiguous)}",
        f"  merchant fixes      {len(result.merchant_fixes)}",
        "",
        f"  not compared: {before_capture} statement row(s) older than the first "
        f"synced SMS (exported to the app as missing),",
        f"  and {out_of_range} sheet row(s) outside the statement's dates.",
        "",
    ]

    parts.append(
        _section(
            "STATEMENT-ONLY — in the bank statement, missing from the sheet",
            [f"  {row.describe()}" for row in result.statement_only],
        )
    )
    parts.append("")
    parts.append(
        _section(
            "SMS-ONLY — in the sheet, not found in the statement",
            [f"  {row.describe()}" for row in result.sms_only],
        )
    )
    parts.append("")
    parts.append(
        _section(
            "AMBIGUOUS — several sheet rows share this amount and date; not matched",
            [f"  {row.describe()}" for row in result.ambiguous],
        )
    )
    parts.append("")
    parts.append(
        _section(
            "MERCHANT FIXES — recovered from statement narration",
            [
                f"  row {fix.row_number:<5} {fix.old_merchant or '(blank)':<12}"
                f" -> {fix.new_merchant:<30} rrn={fix.rrn} [{fix.confidence.value}]"
                for fix in result.merchant_fixes
            ],
        )
    )
    return "\n".join(parts)


def mask_long_numbers(text: str) -> str:
    """Mask digit runs longer than a 12-digit RRN, keeping the last 4.

    Non-UPI narration can carry the account number itself (the interest credit
    does), and this listing is printed to the terminal.
    """
    return re.sub(r"\d{13,}", lambda m: "*" * (len(m.group()) - 4) + m.group()[-4:], text)


def render_statement(rows: list[StatementRow], balance_problems: list[str], pdf_name: str) -> str:
    """Extraction summary for --statement-only: what the PDF yielded, no sheet.

    The counts are the things a real statement can get wrong that the synthetic
    one cannot: rows without an RRN cannot join to the sheet, and rows without a
    payee cannot repair a debit merchant.
    """
    debits = [r for r in rows if r.type.value == "DEBIT"]
    credits = [r for r in rows if r.type.value == "CREDIT"]
    parts = [
        f"Statement extraction — {pdf_name}",
        "",
        f"  rows                {len(rows)}  ({len(debits)} debit, {len(credits)} credit)",
        f"  with RRN            {sum(1 for r in rows if r.ref)}   <- only these can join the sheet",
        f"  with payee          {sum(1 for r in rows if r.payee)}",
        f"  debits with payee   {sum(1 for r in debits if r.payee)}   <- merchant fixes possible",
        f"  balance failures    {len(balance_problems)}",
        "",
        _section("ROWS", [f"  p{r.page:<3} {r.describe()}" for r in rows]),
    ]
    no_ref = [r for r in rows if not r.ref]
    if no_ref:
        parts.append("")
        parts.append(
            _section(
                "NO RRN — narration shown so the reference pattern can be tuned",
                [f"  {r.date.isoformat()}  {mask_long_numbers(r.narration)}" for r in no_ref],
            )
        )
    return "\n".join(parts)


def statement_ref(row: StatementRow, seen: dict[str, int]) -> str:
    """The row's RRN, or a stable stand-in for rows that have none.

    Non-UPI rows (interest, charges, NEFT) carry no RRN, but the app dedups added
    rows on this value. ``STMT-<date>-<D|C>-<paise>`` is the same on every run
    over the same statement. A ``-<n>`` suffix separates identical rows on one
    day, numbered in statement order.
    """
    if row.ref:
        return row.ref
    base = f"STMT-{row.date:%Y%m%d}-{row.type.value[0]}-{int(row.amount * 100)}"
    seen[base] = seen.get(base, 0) + 1
    return base if seen[base] == 1 else f"{base}-{seen[base]}"


def statement_label(row: StatementRow) -> str:
    """Merchant for a row with no payee: the narration, minus anything sensitive.

    Long digit runs are dropped, because the interest narration's only number is
    the account number.
    """
    if row.payee:
        return row.payee
    if re.search(r"Int\.?\s*Pd", row.narration, re.IGNORECASE):
        return "INTEREST"
    text = re.sub(r"\d{6,}", " ", row.narration)
    text = re.sub(r"[^\w\s&./-]", " ", text)
    text = re.sub(r"\s+", " ", text).strip().upper()
    return text[:40] or "BANK ENTRY"


def missing_entries(rows: list[StatementRow]) -> list[dict]:
    """Untracked statement rows in the shape MerchantMapImporter.parseMissing expects.

    Every row is exported, with or without an RRN: the user wants the app to hold
    every transaction the statement shows (SIB sometimes sends no SMS).
    """
    seen: dict[str, int] = {}
    return [
        {
            "rrn": statement_ref(row, seen),
            "date": row.date.isoformat(),
            "amount": f"{row.amount:.2f}",  # string: Decimal must not pass through float
            "type": row.type.value,
            "merchant": statement_label(row),
        }
        for row in rows
    ]


def load_rules(path: Optional[Path]) -> dict:
    """The user's category rules and per-transaction overrides, from --rules.

    Shape: {"rules": [{"pattern", "category"}], "overrides": [{"rrn", "category"}]}.
    Kept outside the repo's tracked files because it names real people.
    """
    if path is None:
        return {"rules": [], "overrides": []}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {"rules": data.get("rules", []), "overrides": data.get("overrides", [])}


def write_merchant_map(
    result: Reconciliation,
    out_dir: Path,
    pdf_name: str,
    before_capture: Optional[list[StatementRow]] = None,
    rules: Optional[dict] = None,
) -> Path:
    """Emit the file the app's Settings -> Import Merchant Map consumes.

    Room is the source of truth (rule 8), and this script cannot reach it. The
    sheet write-back fixes what the user reads; this file fixes what the app
    stores.

    ``fixes`` repair generic merchants on rows the app already has. ``missing``
    carries every statement row the app never recorded: unmatched rows in the
    overlap, plus everything from before capture began. The app adds each one
    unless it already holds that reference. ``rules`` and ``overrides`` are the
    user's categories (see load_rules), applied by the app after adding rows.
    """
    rules = rules or {"rules": [], "overrides": []}
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "merchant_map.json"
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "source": pdf_name,
        "schema": 1,
        "fixes": [
            {
                "transaction_id": fix.transaction_id,
                "rrn": fix.rrn,
                "old_merchant": fix.old_merchant,
                "merchant": fix.new_merchant,
                "confidence": fix.confidence.value,
            }
            for fix in result.merchant_fixes
        ],
        "missing": missing_entries(
            sorted([*(before_capture or []), *result.statement_only], key=lambda r: r.date)
        ),
        "rules": rules["rules"],
        "overrides": rules["overrides"],
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="sib_statement_diff.py",
        description="Reconcile a SIB PDF statement against the synced Google Sheet.",
    )
    p.add_argument("pdf", type=Path, help="Path to the SIB PDF statement")
    p.add_argument(
        "--inspect",
        action="store_true",
        help="Dump the PDF's text and detected tables, then exit. Use this to tune "
        "the extractor in statement.py against a real layout.",
    )
    p.add_argument(
        "--statement-only",
        action="store_true",
        help="Extract the statement and run the balance check, then exit. Never "
        "touches the sheet, so no credentials are needed.",
    )
    p.add_argument("--sheet-id", help="Override GOOGLE_SHEET_ID from secrets.properties")
    p.add_argument(
        "--apply",
        action="store_true",
        help="Actually write recovered merchants into the sheet (default: dry run)",
    )
    p.add_argument(
        "--date-window",
        type=int,
        default=1,
        help="Days of slack for the amount+date fallback match (default: 1)",
    )
    p.add_argument(
        "--allow-fuzzy-merchant",
        action="store_true",
        help="Also take merchants from amount+date matches, not just RRN matches. "
        "Less safe — these are hints, not identities.",
    )
    p.add_argument(
        "--force",
        action="store_true",
        help="Allow --apply even when rows fail the running-balance check",
    )
    p.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help="Output directory for artifacts"
    )
    p.add_argument(
        "--rules",
        type=Path,
        help="JSON of category rules and per-transaction overrides to include in "
        "the merchant map (see load_rules). Default: private/category_rules.json "
        "if it exists",
    )
    p.add_argument(
        "--push",
        action="store_true",
        help="Copy the merchant map to the phone's Downloads over adb",
    )
    p.add_argument("--credentials", type=Path, default=DEFAULT_CREDENTIALS)
    p.add_argument("--token", type=Path, default=DEFAULT_TOKEN)
    return p


def safe_name(name: str) -> str:
    """A file name fit for reports: bank file names often embed the account number.

    Any digit run of six or more keeps only its last four.
    """
    return re.sub(r"\d{6,}", lambda m: "*" * (len(m.group()) - 4) + m.group()[-4:], name)


def resolve_rules(explicit: Optional[Path]) -> Optional[Path]:
    """--rules if given, else the user's private rules file if it exists."""
    if explicit is not None:
        return explicit
    return DEFAULT_RULES if DEFAULT_RULES.exists() else None


def find_adb() -> Optional[str]:
    """adb from the Android SDK (Android Studio's default location) or PATH."""
    exe = "adb.exe" if os.name == "nt" else "adb"
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"),
    ]
    for root in roots:
        if root and (Path(root) / "platform-tools" / exe).exists():
            return str(Path(root) / "platform-tools" / exe)
    return shutil.which("adb")


def push_to_phone(map_path: Path) -> str:
    """Copy the merchant map to the phone's Downloads over adb; returns a status line."""
    adb = find_adb()
    if adb is None:
        return "Not pushed: adb not found. Copy out/merchant_map.json to the phone yourself."
    try:
        done = subprocess.run(
            [adb, "push", str(map_path), PHONE_MAP_PATH],
            capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"Not pushed: {exc}"
    if done.returncode != 0:
        detail = (done.stderr or done.stdout).strip().splitlines()[-1:] or ["unknown error"]
        return f"Not pushed ({detail[0]}). Is the phone plugged in with USB debugging on?"
    return f"Pushed to the phone: {PHONE_MAP_PATH}"


def read_statement(pdf: Path) -> list[StatementRow]:
    """Parse the PDF, asking once for its password if it is encrypted.

    The password is read without echo and lives only in this call; it is never
    stored, logged or written to any output.
    """
    try:
        return statement_mod.parse_statement(pdf)
    except statement_mod.StatementPasswordRequired as exc:
        if not sys.stdin.isatty():
            raise
        print(str(exc), file=sys.stderr)
        return statement_mod.parse_statement(pdf, password=getpass.getpass("PDF password: "))


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.statement_only and (args.apply or args.push):
        parser.error("--statement-only never writes; drop --apply / --push")

    if not args.pdf.exists():
        print(f"No such file: {args.pdf}", file=sys.stderr)
        return 2

    pdf_name = safe_name(args.pdf.name)

    if args.inspect:
        # Dumps every line, account header included. Only for redacted files.
        print(statement_mod.inspect(args.pdf))
        return 0

    try:
        statement_rows = read_statement(args.pdf)
    except statement_mod.StatementParseError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(f"Extracted {len(statement_rows)} rows from {pdf_name}", file=sys.stderr)

    # The running balance column is a free checksum on the extraction. A
    # mismatch means an amount landed in the wrong column, which would flip a
    # debit to a credit — say so loudly rather than reconciling bad rows.
    balance_problems = statement_mod.validate_balances(statement_rows)
    if balance_problems:
        print(
            f"\nWARNING: {len(balance_problems)} row(s) fail the running-balance "
            f"check. The extractor may be misreading columns:",
            file=sys.stderr,
        )
        for problem in balance_problems[:10]:
            print(f"  {problem}", file=sys.stderr)
        if len(balance_problems) > 10:
            print(f"  ... and {len(balance_problems) - 10} more", file=sys.stderr)
        if (args.apply or args.push) and not args.force:
            print(
                "\nRefusing to --apply / --push with balance mismatches. Re-run "
                "with --force to override, or fix the extractor first.",
                file=sys.stderr,
            )
            return 1
        print("", file=sys.stderr)

    if args.statement_only:
        print(render_statement(statement_rows, balance_problems, pdf_name))
        return 1 if balance_problems else 0

    sheet_id = read_sheet_id(args.sheet_id)
    try:
        service = sheets.authorize(args.credentials, args.token)
        sheet_rows = sheets.fetch_rows(service, sheet_id)
    except sheets.SheetsError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(f"Fetched {len(sheet_rows)} rows from the sheet", file=sys.stderr)

    sheet_in, statement_in, before_capture = matching.scope_to_overlap(
        sheet_rows, statement_rows, date_window_days=args.date_window
    )

    result = matching.reconcile(
        sheet_in,
        statement_in,
        date_window_days=args.date_window,
        allow_fuzzy_merchant=args.allow_fuzzy_merchant,
    )

    report = render_report(
        result,
        pdf_name,
        before_capture=len(before_capture),
        out_of_range=len(sheet_rows) - len(sheet_in),
    )
    print(report)

    rules_path = resolve_rules(args.rules)
    rules = load_rules(rules_path)
    map_path = write_merchant_map(result, args.out, pdf_name, before_capture, rules)
    missing_count = len(before_capture) + len(result.statement_only)

    outcome = [
        "",
        "=" * 78,
        "RUN",
        "=" * 78,
        f"  statement           {pdf_name}, "
        f"{min(r.date for r in statement_rows)} to {max(r.date for r in statement_rows)}",
        f"  balance failures    {len(balance_problems)}",
        f"  merchant map        {len(result.merchant_fixes)} fixes, {missing_count} missing, "
        f"{len(rules['rules'])} rules, {len(rules['overrides'])} overrides"
        + (f" (from {rules_path.name})" if rules_path else ""),
    ]

    if result.merchant_fixes and args.apply:
        updated = sheets.apply_merchant_fixes(service, sheet_id, result.merchant_fixes)
        outcome.append(f"  sheet               {updated} merchant cell(s) written")
    elif result.merchant_fixes:
        outcome.append(
            f"  sheet               DRY RUN, {len(result.merchant_fixes)} merchant "
            f"fix(es) not written (add --apply)"
        )
    else:
        outcome.append("  sheet               nothing to write")

    phone = push_to_phone(map_path) if args.push else "not pushed (add --push)"
    outcome.append(f"  phone               {phone}")
    outcome.append("  next                Settings -> Import Merchant Map on the phone, then Sync Now")

    args.out.mkdir(parents=True, exist_ok=True)
    report_path = args.out / f"reconciliation_{date.today():%Y%m%d}.txt"
    report_path.write_text(report + "\n" + "\n".join(outcome) + "\n", encoding="utf-8")
    print("\n".join(outcome), file=sys.stderr)
    print(f"\nReport:       {report_path}", file=sys.stderr)
    print(f"Merchant map: {map_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
