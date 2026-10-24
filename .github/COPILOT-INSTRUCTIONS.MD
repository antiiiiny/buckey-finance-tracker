# Buckey Finance Tracker (axio-clone)

## Project
Native Android app. Kotlin. Captures bank transaction SMS on-device, categorizes locally, syncs to a Google Sheet the user owns. No third-party backend. No cloud storage of financial data. Min SDK 26. Target device: Pixel 9a.

## Stack
- Kotlin, Jetpack Compose, Room, Coroutines + Flow, WorkManager, Google Sheets API v4.
- No RxJava. No LiveData — use StateFlow. No Firebase. No analytics SDK. No crash-reporting SDK.
- The only network calls in this app are to the Google Sheets API. Nothing else.

## Folder Structure
```
app/
  data/
    sms/        SmsReceiver.kt, SmsBackfill.kt, SenderWhitelist.kt
    parser/      TransactionParser.kt, banks/SibParser.kt, ParsedTransaction.kt
    db/          entities/, dao/, AppDatabase.kt
    category/    CategoryMatcher.kt, MerchantMemory.kt, CategoryUpdater.kt, CategoryRepair.kt, GenericMerchant.kt
    sheets/      GoogleSheetsRepository.kt, SheetsSyncWorker.kt, SheetRowFormat.kt, SheetCategorySync.kt
    review/      UnparsedResolver.kt
    importer/    MerchantMapImporter.kt
    summary/     BalanceCalculator.kt, MonthSummary.kt, HomePreferences.kt
  ui/            screens/Home, screens/Pending, screens/Settings, screens/CategoryEditor,
                 screens/ResolveMessage; components/, format/, theme/
reconciliation/   sib_statement_diff.py  (separate Python script, not part of the app build)
```
There is no `domain/` layer: the use-case placeholders were never filled and were
removed on 2026-10-24. Wiring lives in the entry points (see CLAUDE.md).

## Rules — do not deviate without asking

1. SMS capture uses a `BroadcastReceiver` on `SMS_RECEIVED_ACTION` for live messages and a one-time `ContentResolver` query against `content://sms/inbox` for backfill on first launch. No other SMS access method.
2. Only process messages from sender IDs listed in `SenderWhitelist`. Reject everything else at the receiver — do not pass non-whitelisted messages into the parser.
3. One parser file per bank in `parser/banks/`. Start with `SibParser.kt`. Do not put multi-bank logic in a single file.
4. Every parsed transaction is a `ParsedTransaction` data class with exactly these fields: amount (Double), date (Instant), merchant (String), type (DEBIT or CREDIT enum), accountLast4 (String), rawRef (String).
5. If a whitelisted SMS fails to parse, insert it into an `unparsed_messages` table and surface it in the UI review queue. Never drop it silently.
6. Categorization is keyword/merchant-string matching against a local Room table. No LLM call, no network call, for categorization. Ever.
7. When the user manually recategorizes a transaction, write that merchant→category mapping back to the local table immediately — unless the user chooses "Only this transaction" (amended 2026-10-24 at the user's request), or the merchant is generic ("UPI").
8. Room is the source of truth. Sheets is a synced copy, never the only copy. Every write path goes: SMS → Parser → Room → (later, async) → Sheets.
9. Sheets sync uses OAuth scoped to `https://www.googleapis.com/auth/spreadsheets` only. Do not request Drive-wide scope.
10. Sheets rows are append-only, keyed by a stable transaction ID (hash of sender + timestamp + raw SMS body) to prevent duplicate rows on re-sync. Rows are never deleted or appended twice. Amended 2026-10-24 at the user's request: (a) a synced row's Category, Source and Categorized By cells (H:J) may be rewritten in place when the category changes in Room, located by transaction ID; (b) the Sheet is kept in date order — new rows are sorted into position after each sync; (c) an explicit rebuild (first sync after the v4 upgrade, or Settings → Rebuild Google Sheet) backs up the tab, then rewrites every row from Room. Room remains the source of truth (rule 8).
11. Permissions requested by the app: `RECEIVE_SMS`, `READ_SMS`, `INTERNET` (for the Sheets API calls only), and the Google Sign-In scope above. Nothing else. Do not add permissions without the user asking first.
12. Raw SMS body is stored in Room for parser debugging but is never logged, never included in crash output, never sent over network, never shown in any screen except a dedicated "raw message" debug view.
13. Every bank parser gets a unit test file with real (redacted/sample) SMS formats as fixtures. No parser ships without a test.
14. Kotlin style: 4-space indent, `val` over `var` wherever possible, data classes for models, small single-responsibility functions.

## Explicitly forbidden
- No analytics SDK, no crash-reporting SDK, no ad SDK.
- No network call except Google Sheets API.
- No LLM API call from the Android app.
- No third-party backend, no custom server, no Firebase.
- No permission beyond the ones listed in rule 11.