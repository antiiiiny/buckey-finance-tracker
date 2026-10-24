---
name: sib-statement
description: Work with a real (redacted) South Indian Bank PDF statement for Stage 7 reconciliation — validate extraction, tune statement.py against the real layout, add fixtures, and run the dry-run diff. Use when the user drops a statement PDF, mentions their bank statement, or asks to run/verify reconciliation.
---

# SIB statement workflow (Stage 7)

Everything runs from `reconciliation/` with its venv:
`reconciliation/.venv/Scripts/python` (Windows). Run the scripts by path from the
`reconciliation/` directory; read `reconciliation/README.md` for the full flag list.

## 0. Privacy check — before reading anything

- Real statements live in `reconciliation/statements/`. Confirm with
  `git check-ignore -v reconciliation/statements/<file>` that the file is ignored
  (both that folder and `*.pdf` are in `.gitignore`). Never move a statement anywhere
  that is not ignored, and never `git add -f` one.
- Account number, customer ID, name and address should already be redacted. If they
  are visible in extraction output, stop and tell the user. Do not repeat them in
  replies, fixtures, commit messages, or memory.
- **RRNs, narration, amounts, balances and dates must NOT be redacted** — the RRN is
  the join key. If RRNs are blacked out, say the statement can't verify the join and
  ask for one with RRNs intact.
- A black box drawn over text in a PDF leaves the text layer intact. If "redacted"
  values show up in extraction, warn the user.

## 1. Extraction — no credentials needed

```
.venv/Scripts/python sib_statement_diff.py statements/<file>.pdf --statement-only
```

Check, in order:
1. `rows` matches the number of transactions the user sees in the PDF for that week.
   If the statement spans more than the week, count from the PDF itself.
2. `balance failures` is 0. Non-zero means an amount landed in the wrong column
   (debit/credit flip) or a row was dropped/merged. Exit code is 1 in that case.
3. `with RRN` ≈ number of UPI rows. Rows listed under **NO RRN** show their narration:
   if they are UPI rows, `UPI_NARRATION_RE` / `extract_ref` in `statement.py` needs a
   new reference shape. Non-UPI rows (ATM, NEFT, charges, interest) legitimately have
   none.
4. Payees look like names, not truncated first words (wrapped narration) and not
   purpose text ("Paid via ...").

If `parse_statement` finds nothing, or any check fails, go to step 2.

## 2. Tuning against the real layout

```
.venv/Scripts/python sib_statement_diff.py statements/<file>.pdf --inspect
```

This dumps grouped lines tagged TXN / cont / NOISE with each amount's x position
and resolved column. Compare against the constants in `statement.py`:
`DATE_FORMATS`, `DATE_RE`, `AMOUNT_RE`, `UPI_NARRATION_RE`, `NOISE_RE`, the
`WITHDRAWAL_HEADER` / `DEPOSIT_HEADER` / `BALANCE_HEADER` strings, and the line
grouping tolerance in `_group_lines`.

Rules while tuning (they mirror the app's per-bank parser rule):
- Precise patterns for the one known layout. Loud failure otherwise. Never a fuzzy
  fallback that produces plausible-but-wrong rows.
- Debit vs credit comes from the amount's x position against the headers, never
  from keywords in the narration.
- Non-UPI narration yields no payee rather than a guess.

## 3. Lock it in with a fixture

Every layout difference found in a real statement becomes a test:
- Narration shapes → add cases to `NarrationExtraction` in `test_reconcile.py`, using
  the real narration with the RRN replaced by a fake 12-digit number and personal
  names replaced if the user prefers.
- Layout/coordinate differences → reproduce them in `make_sample_pdf.py` so the
  `PdfExtraction` tests cover them.

Then run the suite and update the test count in `reconciliation/README.md`,
`CLAUDE.md` (stage table) and `.github/STAGES.md` (Stage 7 status) if it changed:

```
.venv/Scripts/python -m unittest test_reconcile -v
```

## 4. Diff against the Sheet — needs credentials

Requires `reconciliation/credentials.json` (a **Desktop** OAuth client in the same
Google Cloud project as the app). That's a user action; if it's missing, say what's
needed and stop. The first run opens a browser and caches `token.json`.

```
.venv/Scripts/python sib_statement_diff.py statements/<file>.pdf
```

That is a dry run. Read the report in `out/` with the user: statement-only rows mean
the SMS parser missed something, sms-only rows mean timing or a bad parse, and
merchant fixes should come only from RRN-exact matches.

**Never pass `--apply`, `--force` or `--allow-fuzzy-merchant` yourself.** They write
into the user's financial record. Show the dry-run result and let the user run them
or explicitly tell you to.

## 5. Back to the phone

`out/merchant_map.json` → the Pixel → Settings → Import Merchant Map. If the phone
is on adb, push the file yourself rather than handing the user a command.
From Git Bash, disable path mangling or `/sdcard/...` turns into a Windows path:

```
MSYS_NO_PATHCONV=1 "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" push reconciliation/out/merchant_map.json /sdcard/Download/merchant_map.json
```

The user's terminal is PowerShell. A quoted exe path there needs the call
operator: `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push ...`. For the on-device check, use the `device-verify` skill (Stage 7 import).
