# Stage 7 — Weekly Reconciliation

Standalone Python. **Not part of the Android build** — no Gradle task references it.

Diffs a SIB PDF statement against the synced Google Sheet, and recovers the debit
merchant names the SMS parser cannot see.

## Why this exists

SIB's UPI *debit* SMS contains no payee — only amount, account, timestamp, RRN
and balance. So `SibParser` writes `merchant = "UPI"` on every debit, and no
amount of parser work changes that: the name is not in the message.

The statement narration *does* carry the payee, and the RRN appears on both
sides. That makes the statement the source of truth for debit merchants, and
`TransactionEntity.rawRef` the join key.

## Setup

```bash
cd reconciliation
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt   # Windows
# .venv/bin/python -m pip install -r requirements.txt     # macOS/Linux
```

Then create a **Desktop** OAuth client in the same Google Cloud project as the
app, download the JSON, and save it as `reconciliation/credentials.json`. First
run opens a browser and caches `token.json`. Both are gitignored.

The sheet ID is read from `secrets.properties` at the repo root — the same
`GOOGLE_SHEET_ID` the app builds into `BuildConfig`. Override with `--sheet-id`.

## Use

```bash
# Dry run: report + merchant map, nothing written to the sheet
.venv/Scripts/python sib_statement_diff.py statement.pdf

# Same, then write the recovered merchants into the sheet
.venv/Scripts/python sib_statement_diff.py statement.pdf --apply

# Extract the statement and check balances only — no sheet, no credentials
.venv/Scripts/python sib_statement_diff.py statements/week.pdf --statement-only

# Dump what pdfplumber sees, to tune the extractor against a real layout
.venv/Scripts/python sib_statement_diff.py statement.pdf --inspect
```

Keep real statements in `reconciliation/statements/`. That folder is gitignored
outright, as is `*.pdf`. Run `--statement-only` on a new statement first: it
shows how many rows carry an RRN (the join key) and a payee before any OAuth
setup exists, and exits non-zero if the balance checksum fails.

Useful flags:

| Flag | Effect |
|---|---|
| `--statement-only` | Extract, balance-check and list the rows, then exit. Never touches the sheet. |
| `--apply` | Actually write column D. Off by default — every run is a dry run until you pass this. |
| `--date-window N` | Slack in days for the amount+date fallback match. Default 1. |
| `--allow-fuzzy-merchant` | Also take merchants from amount+date matches, not just RRN matches. Less safe. |
| `--out DIR` | Where the report and merchant map are written. Default `reconciliation/out/`. |

## How matching works

Two tiers, strongest first:

1. **RRN exact** — `SheetRow.raw_ref == StatementRow.ref`. A bank-issued unique
   reference, so this is authoritative even when the dates disagree.
2. **Amount + date (±1 day) + type** — only for rows with no RRN. Accepted only
   when exactly one candidate remains. A tie is reported as *ambiguous* and left
   unmatched rather than guessed.

Everything unmatched is the point of the exercise:

- **statement-only** — the SMS parser missed a transaction. This is the list to act on.
- **sms-only** — in the sheet but not the statement (timing, or a bad parse).

## Write-back

Two destinations, because Room is the source of truth (rule 8) and this script
cannot reach the phone:

- **The Google Sheet**, in place: column D of matched rows, via `--apply`. Fixes
  what you actually read.
- **`out/merchant_map.json`**: always written, and what the app imports to fix
  what it *stores*. `fixes` maps `transaction_id → merchant`. `missing` lists
  statement-only rows (RRN, date, amount, type, payee): SIB sometimes sends no SMS,
  so the app adds these as new transactions unless it already holds the RRN.
  Rows without an RRN are not exported.

Merchant fixes are restricted to **RRN-exact matches** unless you pass
`--allow-fuzzy-merchant`. An amount+date match is a strong hint, not an
identity, and this writes into a financial record.

Only the merchant is written, never the category. Categorization rules live in
Room (`category_rules`), and duplicating them here would drift. Category follows
when the app imports the map.

### Getting the map onto the phone

1. Move `out/merchant_map.json` to the device however you like — Drive, USB, mail.
2. In the app: **Settings → Import Merchant Map**, and pick the file.

The picker uses the Storage Access Framework, so the app needs no storage
permission (rule 11 caps what it may request). `MerchantMapImporter` then repairs
`transactions.merchant` by `transaction_id` and recomputes the category, so a
repaired "BLINKIT" finally matches the seeded `blinkit → Groceries` rule.

The import is conservative and idempotent: it only overwrites merchants that are
still generic, only recomputes a category that is still "Uncategorized", and does
nothing on a second run. It never touches Sheets — those writes are append-only,
so re-syncing a repaired row would duplicate it. `--apply` is what fixes the Sheet.

## How the extractor reads the PDF

`statement.py` is written against the real SIB savings-account layout:

```
DATE      PARTICULARS              CHQ.NO.  WITHDRAWALS  DEPOSITS   BALANCE
03-06-26  UPI/SIBL/<rrn>/ANISH                              531.00  4,546.60Cr
          KAARTHIK M/Paid via Su
05-06-26  UPI/FDRL/<rrn>/AKRAM          250.00                      4,496.60Cr
          PASHA/UPI
```

Three things make this harder than a line regex, and each is handled explicitly:

- **Narration wraps.** The amount and balance are on the first physical line; the
  payee continues on the next. Lines are grouped by vertical position and joined,
  because otherwise every multi-word payee is silently truncated to its first word.
- **Withdrawals and deposits are separate columns** but `extract_text()` collapses
  them into one bare number. Debit vs credit comes from the amount's **x position**,
  resolved against the column headers found on each page — never from keywords.
- **The running balance is a free checksum.** Every row is validated against the
  previous balance: a debit must decrease it by exactly the amount, a credit must
  increase it. This catches column misassignment, which would otherwise flip a
  debit into a credit. Mismatches are reported, and `--apply` refuses to run
  unless you pass `--force`.

Narration shape is `UPI/<bank>/<rrn>/<payee>[/<purpose>]` — the payee is always
field 4, and the reference segment may be `<rrn>`, `RRN-<rrn>` or `RRN<rrn>`.
Non-UPI narration yields no payee rather than a guess.

If `parse_statement` finds nothing it says so and points at `--inspect`, which
dumps grouped lines tagged TXN / cont / NOISE plus each amount's x position and
resolved column. Tune `UPI_NARRATION_RE` and `NOISE_RE` from that output.

This mirrors the app's per-bank parser rule: precise patterns for one known
layout, loud failure otherwise, never a fuzzy extractor that silently produces
plausible-but-wrong rows.

## Tests

```bash
.venv/Scripts/python -m pip install -r requirements-dev.txt   # adds reportlab
.venv/Scripts/python -m unittest test_reconcile -v
```

59 tests: RRN normalization, both match tiers, ambiguity handling, overlap scoping,
merchant-recovery gating, narration parsing against real SIB formats, and
end-to-end extraction from a synthetic PDF built by `make_sample_pdf.py` in the
real statement's shape. The PDF tests skip if reportlab is absent.

Extraction has also been verified against a real 3-month statement (374 rows,
zero balance failures). A statement from a different period or account type may
still differ: run `--statement-only` on each new one first.

## Date scoping

A statement usually covers more than the app has been capturing. The diff only
compares the dates both sides cover. Statement rows older than the first synced
SMS, and sheet rows outside the statement's dates, are counted in the report
header instead of being listed as misses.
