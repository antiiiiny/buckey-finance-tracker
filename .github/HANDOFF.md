# Handoff
Last updated: 2026-10-24 · branch `main` · last code commit `972a3c3`
(this file arrived in the commit after it; `git log -3` shows what's pushed)

New here? Load the `handoff` skill and read in its order: this file, then
CLAUDE.md, COPILOT-INSTRUCTIONS.MD, STAGES.md.

## State in one paragraph
Stages 0–7 are done and verified on the user's Pixel 9a. The app captures SIB UPI
SMS, categorizes them, and syncs to a Google Sheet that is kept in date order
with Source and Categorized By columns. Debit payees come from the monthly bank
statement (`reconciliation/`), which also adds transactions SIB sent no SMS for.
`main` and `stage-6-7-review-and-reconciliation` both point at `972a3c3`.

## Done this session (verified how)
- Statement backfill, user's rules file, category editor scopes, credit rule,
  Sheet category sync, date-sorted Sheet with columns I/J. Verified over adb and
  by reading the Sheet: 587 transactions, 0 unsynced, 0 dirty; Categorized By
  435 development / 88 manual / 64 auto.
- Docs refreshed, stale `domain/` placeholders and `appendBatch` removed.
  Branch merged into `main` (fast-forward) and pushed.
- Handoff tooling: project skill `.claude/skills/handoff/` (read order,
  pre-commit checklist, handoff steps), this file, a pointer at the top of
  CLAUDE.md. A general `context-handoff` skill lives in the user's
  `~/.claude/skills/` for other projects; the project skill takes precedence here.
- Tests: 76 Kotlin, 73 Python, all green (re-run at handoff).

## In flight / not verified
Nothing in flight.

## Next steps (ordered)
Nothing is queued; wait for the user. Their "later" list (STAGES.md → What's left):
1. PDF statement straight into the app.
2. Autopay / e-mandate handling.
3. More SIB SMS formats (ATM, POS, NEFT/IMPS, failed/reversed; one UTIB credit).

Next month's statement is a test of auto-categorization: compare the share of
new rows marked `auto` against 77% last time.

## User decisions and preferences to respect
- Credits are only Income or Transfer. Income = Joy John Ukken; John Joy Ukken is
  a different person (Transfer).
- The user runs the statement command on the unredacted PDF and deletes it.
  Never open statement PDFs; review only `reconciliation/out/` and the device DB.
- Never pass `--apply`, `--force` or `--allow-fuzzy-merchant` yourself.
- Don't touch the Gradle wrapper or dependencies without asking.
- Commit only when asked; push/merge only when asked. The user verifies on
  device first.
- User's terminal is PowerShell; give commands in PowerShell syntax.

## Watch out for
- 7 transactions are still Uncategorized: 5 the user left blank, 2 UPI debits
  matched by amount+date only, so unnamed (query the device DB for details;
  don't write transaction details into committed files).
- The Sheet has a backup tab "Transactions backup 2026-10-24 2038"; the user may
  delete it.
- Gitignored leftovers the user may delete: `reconciliation/statements/bank-statement-redacted.pdf`,
  `reconciliation/out/to_categorize.md`.
- `fallbackToDestructiveMigration()` is still set: every DB version bump needs a
  real migration.
