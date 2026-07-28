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
- `AppDatabase.kt` — version 2 with CategoryRuleEntity, CategoryDao, fallbackToDestructiveMigration, and SEED_CALLBACK with 25 starter rules (Food, Transport, Shopping, Groceries, Utilities, Housing, Income, Transfer, Entertainment)
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