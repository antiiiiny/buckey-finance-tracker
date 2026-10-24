# Handoff
Last updated: 2026-10-25 · branch `main` · wallet round committed on top of
`b28b080` (`git log -3` shows the hash) · **not pushed**

New here? Load the `handoff` skill and read in its order: this file, then
CLAUDE.md, COPILOT-INSTRUCTIONS.MD, STAGES.md.

## State in one paragraph
Stages 0–7 are done and were verified on the user's Pixel 9a (2026-10-24). Since
then, two rounds were built and unit-tested but **never run on the device**:
a UI redesign (`b28b080`: Home, Pending, theme, UI rules + intake rule, design
skills) and "track all my money" round 1 (wallet, lending, savings, holdings,
profile pages, DB v5, Holdings + Dashboard tabs). The v5 migration is unproven
on real data, and `fallbackToDestructiveMigration()` is still set.

## Done this session (verified how)
- UI tooling: `frontend-design` and `edge-to-edge` skills copied in unchanged;
  CLAUDE.md "UI rules" + "UI intake" sections. Redesign brief:
  `.github/design/home-screen.md`.
- Wallet round, design `.github/design/wallet.md` (money table, decisions):
  `data/summary/Ledger` model; `data/wallet/` (CashBook, Links, suggestions);
  ATM SMS parser from the user's sample; Room v5 (`MIGRATION_4_5`); Pending
  decisions (Cash/Lend) and Clear suggestions (±₹10, adjustable); category
  editor links; account pages from the profile initial (Profile, Preferences,
  Bank statement); Settings tab removed; rule 10 amended (d)(e)(f).
- Sheets: cash rows → Transactions (Source `cash`); holding rows leave
  Transactions for a Holdings tab; the app creates a formula **Dashboard** tab
  once per layout version (month + "Spent shows" dropdowns, category pie, two
  monthly charts). Sync otherwise writes only Transactions (+ Holdings on change).
  The desktop `sheet_dashboard.py` was retired.
- Editor save → back to Home with a short popup ("Saved category as Food").
- Balance logic checked against a copy of the device DB (deleted after): earliest
  SMS balance + every later row = latest SMS balance, ₹0.00 off.
- Tests: 133 Kotlin, 75 Python, all green (`cli-build-test`). UI scan clean.

## In flight / not verified
- **Everything since 2026-10-24 on the device.** Use `device-verify`:
  1. Install from Android Studio over the current app; confirm ~590 rows survive
     the v5 migration and former debit "Self Transfer" rows read "Savings".
  2. Home: tap the line under Net → "Your money" → Start wallet at ₹168.
  3. Mark the ₹5,000 credit from the person in memory (category-conventions) as
     Holding. Net unchanged, Holding −₹5,000; after a sync the row leaves
     Transactions and a Holdings tab appears.
  4. Sync: Dashboard tab rebuilt by the app (pie, both dropdowns, no #ERROR!).
  5. Verify balance sheet still says "Matches".

## Next steps (ordered)
1. Device verification above; fix what it finds.
2. Bring `ResolveMessageScreen` in line with the UI rules (+ `adjustResize`).
3. Round 2: other bank accounts (Savings becomes an account so Net keeps it).
4. Round 3: Business profile — separate DB file + Sheet per profile, "like an
   app clone", switched from the profile icon.
5. Later list: PDF statement in the app, autopay, more SIB formats.

## User decisions and preferences to respect
- Money rules: `.github/design/wallet.md`. Suggestions are never automatic; a
  Transfer credit only clears a loan when the user links it.
- Spent (default) = debits minus Transfer/Savings/ATM/Lend/Holding debits minus
  Transfer credits; Net = bank + wallet + lent − holding.
- UI: clean and minimal; run the intake interview before UI code unless the user
  specifies the change fully; own palette; profile initial top right of Home.
- Sync writes Transactions only (plus Holdings on change); the Dashboard is
  formulas, created once — don't make sync write it again.
- Keep real names out of the repo. Never open statement PDFs; never pass
  `--apply`, `--force` or `--allow-fuzzy-merchant` to the statement script.
- Don't touch the Gradle wrapper or dependencies (Compose BOM 2024.10.01).
- Commit only when asked; push/merge only when asked. User's terminal is PowerShell.

## Watch out for
- `fallbackToDestructiveMigration()`: if v5 fails to migrate, the data is wiped.
  Check step 1 above before anything else; the Sheet is the backup.
- `WindowInsetsRulers` / `fitInside` don't exist in this Compose BOM.
- The Dashboard can't see holdings or SMS balances; its Net is the month's change.
