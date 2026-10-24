# Build Stages — Personal Finance Tracker

Each stage has a fixed deliverable and a definition of done. Do not start a stage until the previous one's definition of done is met. Do not combine stages.

## Stage 0 — Project Setup
**Build:**
- New Android Studio project, Kotlin, min SDK 26, Jetpack Compose enabled.
- Add dependencies: Room, WorkManager, Coroutines, Google Sign-In, Google Sheets API client.
- Create the folder structure exactly as specified in `copilot-instructions.md`.
- Empty `AppDatabase` with zero entities, builds and runs on device.

**Done when:** App installs on the Pixel 9a and shows a blank Compose screen. No crashes on launch.

**Status: ✅ COMPLETE (2026-07-25)**

What was built:
- Gradle config: AGP 8.7.3, Kotlin 2.0.21, KSP, Gradle 8.9 wrapper
- Dependencies: Compose (BOM 2024.10.01), Room 2.6.1, WorkManager 2.9.1, Coroutines 1.9.0, Google Sign-In 21.2.0, Sheets API v4
- AndroidManifest with `RECEIVE_SMS`, `READ_SMS` permissions and `SmsReceiver` stub
- `FinanceTrackerApp` (Application class), `MainActivity` (blank Compose screen), Material 3 theme with green palette
- `AppDatabase` (empty, zero entities, Room singleton pattern)
- Full folder structure per `copilot-instructions.md` — all directories created with placeholder files
- `.gitignore` for Android project
- `README.md` with project description and stage table
- ⚠️ Android SDK not present on this machine — final build verification on Pixel 9a is pending

---

## Stage 1 — SMS Capture (no parsing yet)
**Build:**
- `SenderWhitelist.kt` — hardcode SIB's sender ID(s) for now.
- `SmsReceiver.kt` — `BroadcastReceiver` on `SMS_RECEIVED_ACTION`, filters by whitelist, logs matched messages to Logcat only (no DB yet).
- Runtime permission request flow for `RECEIVE_SMS` and `READ_SMS`.
- `SmsBackfill.kt` — one-time query against `content://sms/inbox`, filtered by whitelist, logged to Logcat.

**Done when:** Sending yourself a test SMS from a whitelisted sender shows up in Logcat within 1 second of arrival. Backfill on fresh install logs all matching historical SMS.

**Status: ✅ COMPLETE (2026-07-27)**

What was built:
- `SenderWhitelist.kt` — initially hardcoded SIB IDs, later updated to regex pattern `^[A-Za-z]{2}-SIBSMS-[STP]$` to match SIB's `XX-SIBSMS-S/T/P` format
- `SmsReceiver.kt` — `BroadcastReceiver` on `SMS_RECEIVED_ACTION`, filters via `SenderWhitelist.isWhitelisted()`, logs matched messages to Logcat via `Log.i`. Non-whitelisted senders are silently dropped
- `SmsBackfill.kt` — one-time `ContentResolver` query against `content://sms/inbox`, sorted by date DESC, filters in-memory against whitelist. Wrapped in `try-catch(SecurityException)` because Android 14+ blocks non-default SMS apps from reading `content://sms/inbox`
- `MainActivity.kt` — runtime permission request via `registerForActivityResult(RequestMultiplePermissions)`, auto-triggers on first launch. Shows `PermissionPrompt` Compose screen when SMS perms not granted, shows `BackfillComplete` status screen after
- Build fixes: Compose compiler plugin (Kotlin 2.0+), launcher icon (vector drawables), META-INF duplicates exclusion

**Verified on Pixel 9a:**
- ✅ Live SMS from `AD-SIBSMS-S` captured and logged within 1 second
- ❌ Backfill blocked by Android 14+ (expected — live capture is the critical path)
- SIB UPI debit format identified for Stage 3: `UPI debit:Rs.{amount},A/c {last4}, {date} RRN:{ref}. Bal:...`

---

## Stage 2 — Local Storage
**Build:**
- Room entities: `TransactionEntity`, `UnparsedMessageEntity`.
- DAOs with insert, query-by-date-range, update-category methods.
- Wire `SmsReceiver` and `SmsBackfill` to write raw SMS into `UnparsedMessageEntity` (parsing comes next stage — for now everything is "unparsed").

**Done when:** Captured SMS persist in Room and survive app restart. Query them via Database Inspector in Android Studio.

**Status: ✅ COMPLETE (2026-07-27)**

What was built:
- `TransactionType.kt` — enum with `DEBIT`, `CREDIT`
- `TransactionEntity.kt` — Room entity with fields: id (auto PK), transactionId (SHA-256 hash, unique index), amount, date, merchant, type, accountLast4, rawRef, senderAddress, rawBody, category, syncedToSheets, createdAt
- `UnparsedMessageEntity.kt` — Room entity with fields: id (auto PK), senderAddress, rawBody, receivedAt, failureReason, manuallyResolved
- `TransactionDao.kt` — DAO: insert, insertAll, getAllTransactions (Flow), getByDateRange, getByTransactionId, updateCategory, markAsSynced, getUnsyncedTransactions
- `UnparsedMessageDao.kt` — DAO: insert, insertAll, getUnresolvedMessages (Flow), getAllMessages, deleteById, markAsResolved, getById
- `AppDatabase.kt` — proper `@Database(entities = [TransactionEntity, UnparsedMessageEntity])`, abstract DAO accessors, singleton pattern
- `TransactionIdGenerator.kt` — SHA-256 hex hash of `senderAddress|receivedAt|rawBody` for stable dedup keys
- `FinanceTrackerApp.kt` — lazy DB initialization, static `getInstance()` accessor
- `SmsReceiver.kt` — on whitelisted SMS capture, creates `UnparsedMessageEntity` and inserts via coroutine scope
- `SmsBackfill.kt` — batch-collects whitelisted messages, then bulk-inserts via `insertAll`

---

## Stage 3 — Parsing
**Build:**
- `ParsedTransaction.kt` data class per the spec in `copilot-instructions.md`.
- `SibParser.kt` implementing `TransactionParser` interface — regex extraction of amount, merchant, type, account last-4, ref number from SIB SMS formats.
- Unit tests in `test/parser/SibParserTest.kt` with at least 10 real (redacted) SIB SMS samples covering debit, credit, and failure cases.
- Wire parser into the capture pipeline: raw SMS → parser → on success, write `TransactionEntity`; on failure, stays in `UnparsedMessageEntity`.

**Done when:** All 10+ test fixtures pass. Real incoming SIB SMS produce correctly populated `TransactionEntity` rows, verified against the actual SMS content by manual check.

**Status: ✅ COMPLETE (2026-07-27)**

What was built:
- `ParsedTransaction.kt` — data class with amount (Double), date (Instant), merchant (String), type (TransactionType), accountLast4 (String), rawRef (String)
- `TransactionParser.kt` — interface with single `parse(senderAddress, rawBody): ParsedTransaction?` method
- `SibParser.kt` — regex-based parser for SIB UPI SMS. Two patterns:
  - Debit: `UPI debit:Rs.{amount},A/c X{last4}, {date} {time} RRN:{ref}.`
  - Credit: `UPI Credit:INR Rs.{amount} in A/c X{last4}. Info:UPI/SIBL/{ref}/{merchant} on {date} {time}.`
  - Date parsed via `DateTimeFormatter("dd-MM-yy HH:mm:ss")` with IST timezone
  - Returns null (unparsed) for any non-matching message
- `SibParserTest.kt` — 12 unit tests:
  - 4 debit tests (standard, larger amount, zero amount, integer amount)
  - 3 credit tests (standard with merchant name, salary credit, small amount with multi-word merchant)
  - 5 failure tests (non-SIB SMS, malformed amount, OTP message, empty body, wrong date format)
- `SmsReceiver.kt` — wired full pipeline: whitelist → parse → success: TransactionEntity, failure: UnparsedMessageEntity
- `SmsBackfill.kt` — same pipeline logic for backfill (batch parse + insert)
- **Tests: All 12/12 passed** in Android Studio (verified manually on-device)

**SIB SMS formats captured (for reference):**
- Debit: `UPI debit:Rs.1.00,A/c X6825, 27-07-26 20:11:44 RRN:620896250xxxx. Bal:Rs.755.99...`
- Credit: `UPI Credit:INR Rs.1.00 in A/c X6825. Info:UPI/SIBL/11030666xxxx/LEO STEV on 27-07-26 20:43:01.Final balance...`

---

## Stage 4 — Categorization
**Build:**
- `category` table (merchant string → category) seeded with a starter list (Swiggy→Food, Uber→Transport, etc.).
- `CategoryMatcher.kt` — matches transaction merchant string against the table, case-insensitive substring match.
- UI screen: `TransactionList` showing captured transactions with category, and a tap-to-recategorize action.
- Recategorization writes back to the `category` table (`MerchantMemory.kt`).

**Done when:** New transactions auto-categorize on capture. Manually changing a category once causes all future transactions from that merchant to use the new category, without app restart.

**Status: ✅ COMPLETE (2026-07-28)**

What was built:
- `CategoryRuleEntity.kt` — Room entity with merchantPattern (unique index) and category fields
- `CategoryDao.kt` — DAO with getAllRules (Flow), insertOrUpdate, findMatch (SQL LIKE substring match), deleteById
- `AppDatabase.kt` — version 2 with CategoryRuleEntity, CategoryDao, fallbackToDestructiveMigration, and SEED_CALLBACK with 25 starter rules (Food, Transport, Shopping, Groceries, Utilities, Housing, Income, Transfer, Entertainment). *Later cut to 22: the `upi`/`neft`/`imps` → Transfer rules were removed because every SIB debit has merchant "UPI" — see the KDoc on `SEED_CATEGORIES`.*
- `CategoryMatcher.kt` — matches merchant string against rules via SQL `LIKE '%pattern%'`, returns "Uncategorized" if no match
- `MerchantMemory.kt` — saves/updates merchant→category mapping when user recategorizes
- `TransactionListScreen.kt` — Compose screen with TopAppBar, LazyColumn of TransactionCards showing merchant, amount (color-coded red/green), date, category badge. Empty state when no transactions
- `CategoryEditorScreen.kt` — shows transaction details, ExposedDropdownMenu with 15 category options, save button that writes to both category_rules (MerchantMemory) and updates the transaction's category
- `MainActivity.kt` — replaced BackfillComplete screen with NavHost (transaction_list → category_editor/{id} routes)
- `TransactionDao.kt` — added `getById(id)` query
- `SmsReceiver.kt` — auto-categorizes via CategoryMatcher before inserting TransactionEntity

---

## Stage 5 — Google Sheets Sync
**Build:**
- Google Sign-In flow, scoped to `spreadsheets` only.
- `GoogleSheetsRepository.kt` — append transaction rows to a user-specified Sheet ID.
- Transaction ID (hash of sender + timestamp + raw body) written as a column, used to skip already-synced rows.
- `SheetsSyncWorker.kt` — WorkManager periodic job (e.g. every 6 hours) plus a manual "Sync now" button in Settings.

**Done when:** Running sync twice in a row produces zero duplicate rows in the Sheet. Killing and reopening the app does not re-sync already-synced transactions.

---

## Stage 6 — Review Queue for Unparsed Messages
**Build:**
- UI screen listing all `UnparsedMessageEntity` rows.
- Manual entry form: user fills in amount/merchant/type/category by hand for an unparsed message, which creates a `TransactionEntity` and removes it from the queue.
- (Optional, only after everything above is stable) — flag repeat unparsed patterns from the same sender so you know when a bank changed its SMS format.

**Done when:** An unparsed message can be manually resolved into a proper transaction without leaving the app.

---

## Stage 7 — Weekly Reconciliation (separate from the Android app)
**Build:**
- `reconciliation/sib_statement_diff.py` — standalone script, not part of the Android build.
- Uses `pdfplumber` to extract transaction rows from the SIB PDF statement.
- Pulls the week's rows from the synced Google Sheet via Sheets API.
- Diffs on amount + date (±1 day) + merchant, outputs three lists: matched, SMS-only (missing from statement), statement-only (missed by SMS parser).

**Done when:** Running the script against a real weekly SIB statement and the corresponding Sheet range produces a diff report you can read and act on — specifically, statement-only entries tell you the parser missed something.

---

## Order of operations if you get stuck
Do not skip ahead. If Stage 3 parsing is fragile, do not start Stage 4 — fix the parser and expand its test fixtures first. Each stage assumes the previous stage's "done when" criteria are actually true, not approximately true.
---

## Stage 5 — Status (recorded late)

**Status: ✅ COMPLETE (2026-07-28)** — implemented in commit `719d4b7`, status
block added retroactively on 2026-09-02.

Deviations from the spec above:
- Sheet ID is **not** user-specified at runtime. It is read from
  `secrets.properties` into `BuildConfig.GOOGLE_SHEET_ID` at build time.
- Sync interval is **15 minutes**, not 6 hours.
- ~~`isDuplicate()` re-reads the whole range once per transaction.~~ Fixed
  2026-10-24: one read of column A per sync (`fetchRowIndex`) serves dedup and
  the category pass; `isDuplicate()` is only the fallback if that read fails.
  The unused `appendBatch()` was removed.
- Rule 10 amended 2026-10-24: H:J of a synced row may be rewritten, the tab is
  sorted by date after appends, and an explicit rebuild rewrites it from Room.
  See "Stage 7 — Follow-up" below.

## Stage 6 — Status

**Status: ✅ COMPLETE — verified on the Pixel 9a (2026-10-24).** Built
2026-09-25.

What was built:
- `data/review/UnparsedResolver.kt` — validates the manual entry, builds the
  `TransactionEntity`, and inserts it + marks the source message resolved inside
  one Room transaction. An explicit category on a real merchant is remembered
  (rule 7); on a generic merchant ("UPI") it applies to that row only.
- **Transaction ID for manual rows:** rule 10's formula over the *source SMS*
  (`sender|receivedAt|rawBody`), not over the typed values. Resolving the same
  SMS twice collides on the unique index instead of creating a second row, so
  Sheets dedup holds. The transaction date is the SMS arrival time.
- "Not a Transaction" dismisses OTPs/balance alerts by marking them resolved.
  Nothing is deleted (rule 5).
- `ui/screens/ReviewQueue/ReviewQueueScreen.kt` — list of unresolved messages
  (sender, time, failure reason — no body). New "Review" tab with an unresolved
  count badge.
- `ui/screens/ResolveMessage/ResolveMessageScreen.kt` — manual entry form:
  amount, debit/credit, merchant, category (or auto from rules), account last 4,
  RRN. The raw SMS is hidden behind "Show original SMS" — this screen is rule 12's
  dedicated raw-message view, since the user can't transcribe what they can't see.
- `data/category/GenericMerchant.kt` — single definition of "generic merchant",
  now shared by the category editor, the merchant map importer and the resolver.
- `UnparsedResolverTest.kt` — 13 unit tests (amount parsing, validation, ID
  stability).

Not built: the optional "flag repeat unparsed patterns per sender" item.

**Done when — met (2026-10-24):** an unparsed SMS is resolved into a
transaction from the Review tab, it appears in the Transactions list, the badge
count drops, and the next sync appends exactly one Sheet row for it.

## Stage 7 — Status

**Status: ✅ BUILT (2026-09-02)**

Scope changed during Stage 7 planning. The original spec describes a read-only
diff report. It is now also **the source of truth for debit merchant names**,
because SIB's UPI debit SMS contains no payee and no parser or model can recover
one from the message text. The statement narration has it; the RRN joins the two.

Built in `reconciliation/`:
- `sib_statement_diff.py` — CLI. Dry run by default; `--apply` writes.
- `statement.py` — SIB PDF extraction, written against the real statement layout:
  wrapped narration joined across physical lines, debit/credit resolved from the
  amount's x position against the per-page column headers, and every row checked
  against the running balance. Plus `--inspect` for retuning.
- `sheets.py` — Sheets read/write, including RRN normalization (a 12-digit RRN
  written with `USER_ENTERED` reads back as a float and breaks the join).
- `matching.py` — RRN-exact tier, then amount+date±1day, ties left unmatched.
- `make_sample_pdf.py` — synthetic statement in the real layout, for tests.
- `test_reconcile.py` — 59 unit tests at the time (75 now), all passing.

Write-back goes to both the Sheet (column D) and `out/merchant_map.json`.
`--apply` refuses to run when rows fail the balance check unless `--force`.

Narration format confirmed from a real statement:
`UPI/{BANK}/{RRN}/{PAYEE}/{purpose}` — payee is field 4, reference segment may be
`{rrn}`, `RRN-{rrn}` or `RRN{rrn}`. Amount and balance sit on the first physical
line; the payee's name wraps onto the second.

On the app side, `data/importer/MerchantMapImporter.kt` consumes
`merchant_map.json` (Settings → Import Merchant Map, via the Storage Access
Framework — no new permission) and repairs `transactions.merchant` by
`transaction_id`, recomputing the category so a repaired merchant matches the
rules. Conservative and idempotent: generic merchants only, "Uncategorized"
categories only, no Sheets writes. 19 unit tests in `MerchantMapImporterTest`
at the time (26 now; the importer grew, see the follow-up below).

**Extraction verified on a real statement (2026-10-24):** a 3-month SIB
statement (3 Jun – 2 Sep 2026) gave 374 rows with 0 balance-checksum failures, and
every UPI row carried an RRN. Three fixes came out of it, each with a test:
fake-bold headings (`dedupe_chars`), `M/S` business prefixes splitting the payee,
and non-UPI narration no longer yielding a ref (the interest credit's only digit
run is the account number). Added `--statement-only` (no OAuth) and scoping of
the diff to the overlapping date range.

**Dry-run diff against the Sheet (2026-10-24):** 171 statement rows in the
overlapping dates, 112 matched (109 by RRN), 84 merchant fixes, 59 statement-only,
10 sms-only (all on the statement's last day or after). The statement-only rows
showed SIB sometimes sends no SMS, and that the credit regex rejected payers at
other banks (fixed: `UPI/{bank}/` instead of `UPI/SIBL/`). `merchant_map.json`
now carries those rows as `missing`, and the importer adds them.

Also found on the device: `category_rules` held 4 rules including
`upi → Transfer`. `CategoryRepair` (runs at launch) removes generic rules,
restores missing starter rules, and resets the affected debits once.

**Status: ✅ COMPLETE — round trip verified on the Pixel 9a (2026-10-24).**
`--apply` wrote 84 merchant cells; the import applied 84/84 fixes, added the 59
statement-only rows (all synced, 0 duplicate RRNs), and cleared 11 queued SMS
(rows kept, rule 5). `CategoryRepair` restored the starter rules (25 rules: 22
starters plus 3 learned) and removed `upi → Transfer`.

## Stage 7 — Follow-up (2026-10-24)

**Status: ✅ COMPLETE — verified on the Pixel 9a and in the Sheet (2026-10-24).**

Built after the first round trip, at the user's request:

- **Full statement backfill.** `merchant_map.json` carries `missing`: every
  statement row the app never recorded, including those before capture began
  (SIB sometimes sends no SMS). The importer inserts them
  (`sender_address = "SIB-STATEMENT"`) and clears any queued SMS naming the same
  RRN. Rows with no RRN get `STMT-<yyyymmdd>-<D|C>-<paise>[-n]`.
- **The user's rules.** `reconciliation/private/category_rules.json`
  (gitignored; it names real people) adds `rules` and `overrides` to the map.
  The importer saves the rules and relabels every row each one governs.
  `--rules-only --push` sends rules without a statement.
- **Category editor scopes.** Only this / this and every later / every
  transaction from the merchant (`CategoryUpdater`). Rule 7 amended accordingly.
- **Credit rule.** Credits carry only Income, Transfer or Self Transfer;
  anything else becomes Transfer (`CategoryMatcher.forType`), enforced at
  capture, in bulk SQL, in the editor and by `CategoryRepair`.
- **Parser fix.** Credits from other banks (`UPI/{bank}/` instead of
  `UPI/SIBL/`), 3 new fixtures.
- **Sheet follows Room.** Room v3 (`category_dirty`): category changes rewrite
  the Sheet in place. Room v4 (`categorized_by`): two new columns,
  **I Source** (`sms` / `bank_statement`) and **J Categorized By**
  (`auto` / `manual` / `development`). The tab is sorted by date after every
  append; the first v4 sync rebuilt it from Room behind a backup tab, and
  Settings → Rebuild Google Sheet repeats that.
- **Monthly flow on the unredacted statement.** The user runs
  `sib_statement_diff.py <pdf> --apply --push`, answers the password prompt,
  deletes the PDF, and imports on the phone. Claude reviews only
  `reconciliation/out/` and the device DB. `--apply`/`--push` refuse to run if any
  row fails the balance check.

**Verified:** 587 transactions in Room and in the Sheet, one row each, in date
order; Source 319 bank_statement / 268 sms; Categorized By 435 development /
88 manual / 64 auto; 0 unsynced, 0 dirty, 0 credits outside Income/Transfer.
A second statement categorized 113 of 147 debits (77%) automatically.

Tests at the end of Stage 7: 76 Kotlin and 73 Python.

## UI redesign (2026-10-24) — built, not yet verified on device

Design interview → approved brief and mockups (`.github/design/home-screen.md`),
then built:

- **Home** replaces the transaction list: Spent this month and Net side by side.
  Spent is debits minus Transfer debits minus Transfer credits (repayments) by
  default, or every debit (Preferences → Spent shows). Net is a running balance
  (earliest SMS balance + every later row) by default, or the month's net
  (Preferences → Net shows). Tapping Net opens a sheet that checks it against the
  latest SMS balance. "Buckey" label over the month picker; rows grouped by day
  or month (⋮, current choice ticked); "N to categorize" filter; sync-error
  banner; first-run welcome.
- **Pending** tab = the Review queue plus uncategorized transactions, laid out
  like Home: two counts on top, headed sections, uncategorized rows by day.
- **Settings** holds Sync (old tab) and "Open in Google Sheets" (link built from
  `BuildConfig.GOOGLE_SHEET_ID`). Bottom bar: Home · Pending · Settings.
- Own palette (neutral + deep green, dynamic color off), 4-size type scale,
  spacing tokens, `enableEdgeToEdge()`. UI rules and the intake rule in CLAUDE.md;
  `frontend-design` and `edge-to-edge` skills in `.claude/skills/`.
- `SibParser.parseBalance` reads the balance from the stored SMS (no migration).
  On the device data the running balance matched the latest SMS balance exactly.

Tests at the time: 102 Kotlin (SibParser 20, UnparsedResolver 13, MerchantMapImporter 26,
CategoryUpdater 8, CategoryMatcher 4, SheetCategorySync 5, SheetRowFormat 5,
BalanceCalculator 7, MonthSummary 9, Format 5) and 77 Python (4 for the
Sheet dashboard).

## Wallet, lending, savings and holdings (2026-10-25) — built, not yet verified on device

Round 1 of 3 toward tracking all the user's money (design:
`.github/design/wallet.md`). Net = bank + wallet + lent − holding.

- **Wallet:** cash rows (Source `cash`), started by an opening count (the user's
  ₹168), "+ Cash" payments on Home (Spent), recounts. ATM withdrawals and
  Transfer debits marked Cash fill it.
- **ATM SMS parsed** from the user's sample; no date in it, so the arrival time
  is used (`TransactionParser.parse` gained `receivedAt`).
- **Transfer or lend:** Transfer debits since the wallet started wait in Pending
  for Cash / Lend (who). Loans are owed to the user and keep Net.
- **Savings:** debit Self Transfer renamed (v5 migration + CategoryRepair).
- **Holdings:** credits held for someone don't touch Spent or Net; payouts link
  to them; a payout within ±₹10 (Preferences) suggests clearing, a leftover
  becomes the user's, an overspend counts as Spent. Loans get the same
  suggestion for credits. Holding rows leave the Transactions tab for a new
  Holdings tab (rule 10 amended).
- **Account pages** from the profile initial on Home: Profile (name, Google
  Sheets, data tools), Preferences (Net, Spent, Matching range), Bank statement.
  Settings tab removed; bottom bar is Home · Pending.
- Room v5 (`MIGRATION_4_5`): link columns. Statement diff skips cash rows.
- **Dashboard tab created by the app** once per layout version: live formulas
  over Transactions, month and "Spent shows" dropdowns, a category pie and two
  monthly charts. Sync otherwise writes only Transactions (and Holdings when a
  holding changes). The desktop `sheet_dashboard.py` was retired.
- Saving in the category editor returns to Home with a short popup ("Saved
  category as Food", "Cleared A's holding"); Pending's buttons show the same
  popup in place.

Tests: 133 Kotlin (SibParser 23, UnparsedResolver 13, MerchantMapImporter 26,
CategoryUpdater 8, CategoryMatcher 4, SheetCategorySync 5, SheetRowFormat 5,
BalanceCalculator 7, Ledger 18, MonthSummary 9, Wallet 5, Format 5,
DashboardSheet 5) and 75 Python.

## What's left

- **Verify the wallet round on the Pixel 9a**, including the v5 migration.
- **Round 2: other bank accounts. Round 3: Business profile** (separate DB and
  Sheet per profile).

- **Verify the UI redesign on the Pixel 9a.** Then bring `ResolveMessageScreen`
  in line with the UI rules.
- **PDF statement straight into the app**, replacing the desktop script.
- **Autopay / e-mandate** messages (currently dismissed from the Pending tab).
- **More SIB SMS formats:** POS/card, NEFT/IMPS/RTGS, failed/reversed, and
  the one queued UTIB credit whose payee has a character outside `[\w\s]`.
- Stage 6 optional: flag repeated unparsed patterns per sender.
- Stage 4.5 (on-device Gemini Nano): proposed only; needs a rule 6 amendment.
- `gradlew.bat` is broken from the CLI (wrapper jar); Android Studio is fine.
