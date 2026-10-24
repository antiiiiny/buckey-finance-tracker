# Buckey Finance Tracker — Working Notes for Claude

Native Android app (Kotlin, Compose). Captures South Indian Bank transaction SMS
on-device, parses and categorizes them locally, syncs to a Google Sheet the user
owns. No third-party backend. Target device: Pixel 9a, API 35, min SDK 26.

The authoritative rules live in `.github/COPILOT-INSTRUCTIONS.MD`. This file
describes what is actually built and why. Where the two disagree, this file is
the accurate one — flag the drift rather than silently following either.

## Architecture as built

Two entry points feed one pipeline. Neither goes through a domain or repository
layer — the wiring is inline in the entry points themselves.

    Live SMS  ──► SmsReceiver.onReceive
                    └─ SenderWhitelist.isWhitelisted()   ── reject ──► dropped
                    └─ SibParser.parse()
                         ├─ non-null ──► CategoryMatcher.getCategory()
                         │                └─► TransactionEntity  ──► transactions
                         └─ null     ──► UnparsedMessageEntity   ──► unparsed_messages

    First launch ──► SmsBackfill.runBackfill      (blocked on Android 14+; see below)
                    └─ same whitelist → parse → categorize → transactions /
                       unparsed_messages, batched via insertAll (suspend, IO)

    transactions ──► SheetsSyncWorker (periodic 15 min + manual one-shot)
                       └─ getUnsyncedTransactions()
                       └─ GoogleSheetsRepository.isDuplicate() → appendRow()
                       └─ markAsSynced()  ──► Google Sheets "Transactions" tab

    unparsed_messages ──► ReviewQueueScreen → ResolveMessageScreen (Stage 6)
                       └─ UnparsedResolver.resolve()  ──► transactions + markAsResolved
                       └─ UnparsedResolver.dismiss()  ──► markAsResolved only

    merchant_map.json ──► MerchantMapImporter (Settings, user picks the file)
                       └─ repairs transactions.merchant by transaction_id
                       └─ re-runs CategoryMatcher on the repaired merchant
                          (Stage 7 write-back; never touches Sheets)

Room is the source of truth (rule 8). Sheets is a synced copy keyed by
`transaction_id`: rows are appended once, and the only in-place write is a
synced row's Category cell (rule 10, amended 2026-10-24). Nothing is ever read
back from Sheets into Room except column A, to locate rows.

Key files:
- `data/sms/SmsReceiver.kt` — live capture + full pipeline, the real orchestrator
- `data/sms/SmsBackfill.kt` — historical capture, parallel copy of the same logic
- `data/parser/banks/SibParser.kt` — the only parser
- `data/db/AppDatabase.kt` — 3 entities, v4 (`MIGRATION_2_3` adds `category_dirty`, `MIGRATION_3_4` adds `categorized_by`), seeded with 22 category rules
- `data/sheets/SheetsSyncWorker.kt` — the only network caller
- `data/importer/MerchantMapImporter.kt` — applies the Stage 7 merchant map to Room
- `data/review/UnparsedResolver.kt` — Stage 6: manual entry → `TransactionEntity`
- `data/category/GenericMerchant.kt` — the one definition of "UPI-like" merchant
- `MainActivity.kt` — permissions, NavHost, bottom bar; screens take `AppDatabase` directly

### The backfill path is mostly theoretical

On Android 14+ a non-default SMS app cannot query `content://sms/inbox` even
with `READ_SMS`. `SmsBackfill` catches the `SecurityException` and continues.
`READ_SMS` is declared `maxSdkVersion="33"`. On the Pixel 9a, live capture is
the only path that actually runs. Treat `SmsBackfill` as legacy-device support,
and do not assume it will exercise any code you change.

### The unparsed path

Any whitelisted SMS the parser returns `null` for lands in `unparsed_messages`
with `failure_reason` and `manually_resolved = false`. Rule 5: it is never
dropped. The Review tab lists them; resolving or dismissing one sets
`manually_resolved = true` and never deletes the row.

A manually resolved transaction's `transaction_id` is rule 10's hash over the
*source SMS* (`sender|receivedAt|rawBody`), not over the typed values, so
resolving the same SMS twice hits the unique index instead of producing a second
Sheet row. Keep it that way — hashing user input would break Sheets dedup.

## Why per-bank regex parsing

`TransactionParser` is an interface; `SibParser` implements it; rule 3 says one
file per bank under `parser/banks/`, no multi-bank logic in one file. This was
chosen deliberately over a generic amount/date extractor:

1. **Indian bank SMS have no shared grammar.** SIB's debit format puts the
   amount before the account and omits the payee entirely; its credit format
   embeds the payee inside a `UPI/SIBL/{ref}/{name}` path. A generic
   "find Rs.<number>" matcher would happily pick up the *balance* instead of the
   amount — SIB debit SMS contain both.
2. **A wrong parse is worse than no parse.** A generic parser degrades silently
   into plausible-but-wrong rows in the user's financial record. A per-bank regex
   either matches its exact known format or returns `null`, and `null` routes to
   the unparsed queue where a human sees it. Failure is loud by construction.
3. **Format changes are detectable.** When a bank changes its template, the
   regex stops matching and messages pile up in `unparsed_messages` from one
   sender — a signal. A fuzzy parser would keep producing degraded output.
4. **It is testable without a device.** `SibParserTest` runs 15 fixtures as plain
   JVM unit tests. Rule 13: no parser ships without a test file of real (redacted)
   SMS.

The cost is coverage: adding a bank or a format means writing a regex and
fixtures. That is the accepted trade.

### Known parser limits — read before touching categorization

`SibParser` handles exactly two formats, both UPI:

- Debit: `UPI debit:Rs.{amt},A/c X{last4}, {dd-MM-yy HH:mm:ss} RRN:{ref}.`
- Credit: `UPI Credit:INR Rs.{amt} in A/c X{last4}. Info:UPI/{bank}/{ref}/{payee} on {date}.`
  `{bank}` is the payer's bank (SIBL, HDFC, ICIC, SBIN, PPIW…). It was hardcoded
  to SIBL until 2026-10-24, which queued every credit from another bank.

**The debit SMS contains no payee, so `SibParser` hardcodes `merchant = "UPI"`.**
This is the single most important fact about the current system. Consequences:

- Every debit carries the same merchant string, so no keyword rule can tell one
  debit from another. Debits fall to "Uncategorized" and stay visibly pending —
  see "How categorization works" below.
- `GenericMerchant.isGeneric()` stops a manual recategorization of one debit
  from writing a `upi → X` rule that would then relabel every debit. The editor,
  the importer and the Stage 6 resolver all use it. Do not remove that guard
  without solving the root cause.
- **No model can fix this by reading the SMS.** The payee is not in the text. An
  extractor pointed at a debit SMS can only invent one. The fix has to come from
  a second data source, not from better parsing.

Not handled at all: ATM withdrawals, POS/card, NEFT/IMPS/RTGS, cheque, e-mandate,
failed/reversed transactions, and any SIB template without the trailing `.` after
the RRN. All of these currently land in `unparsed_messages`.

## How categorization works

Four tiers, ordered by how much the result can be trusted. Tiers 1–3 exist in
code today; tier 4 is proposed only.

**Tier 1 — rule match on an extracted payee.** `CategoryMatcher.getCategory()`
→ `CategoryDao.findMatch()`, a `LIKE '%pattern%'` scan of `category_rules`
ordered by pattern length so the most specific rule wins. Seeded with 22 starter
rules. **This only ever fires for credits**, because only the credit regex
captures a payee. Falls through to "Uncategorized".

**Tier 2 — manual, remembered.** `CategoryEditorScreen` → `CategoryUpdater`,
with three scopes the user picks:
- *Only this transaction* — no rule written (rule 7 as amended).
- *This and every later transaction* — rule written via
  `MerchantMemory.rememberCategory()`, plus existing rows dated at or after this
  one re-labelled.
- *Every transaction* — rule written, every existing row re-labelled.

"From this merchant" means the new rule is the one `CategoryDao.findMatch` picks
for the row, so bulk edits never disagree with tier-1 matching. Generic merchants
(`GenericMerchant.isGeneric()`) are forced to *only this transaction*.

**Credits are Income or Transfer** (user's rule, 2026-10-24). A credit only
carries Income, Transfer or Self Transfer; anything else, including no rule,
becomes Transfer (`CategoryMatcher.forType`). Enforced at capture, in bulk SQL
(`CASE WHEN type = 'CREDIT' ...`), in the editor's picker, and by
`CategoryRepair` on every launch. So `abel paul → Food` labels payments *to*
Abel; money *from* Abel is Transfer. Income comes from explicit rules only
(`joy john` — Joy John Ukken; John Joy Ukken is a different person, Transfer).
Credit SMS truncate the payer to 8 characters ("JOY JOHN", "RHEA GRA"), so
credit rules must match the truncated form.

**The Sheet is kept in date order, with two extra columns.** Layout lives in
`SheetRowFormat`: A–H as before, then **I Source** (`sms` / `bank_statement`, from
`sender_address`) and **J Categorized By** (`transactions.categorized_by`:
`auto` = a rule when the row arrived, `manual` = the user in the app,
`development` = a rules-file import). Imports never relabel a manual row, and
only mark rows whose category actually changes, so `auto` keeps meaning "the
rules got it right on arrival". After appending, the sync sorts the tab by the
Date column. The first sync on DB v4 (`MIGRATION_3_4`) rebuilds the whole tab
from Room — backup tab first — and Settings → Rebuild Google Sheet repeats that.
`--rules-only --backfill-categorized-by` (one-off) labels pre-v4 rows by the rule
that categorizes them.

**Sheet categories follow Room.** Every DAO write to `category` sets
`category_dirty`. After appending, `SheetsSyncWorker` reads column A once,
then rewrites column H of dirty synced rows in one batch (`SheetCategorySync`),
clearing the flag only if the category didn't change mid-sync.

**Tier 3 — statement matching on RRN (Stage 7, built). This is the real answer
for debits.** SIB's PDF statement narration carries the payee, and `rawRef` (the
RRN) is stored on every row as the join key — that is why the column exists.
`reconciliation/sib_statement_diff.py` joins statement to sheet on RRN, and the
statement wins on merchant. Write-back goes two places: column D of the Sheet
(`--apply`, dry run by default) and `out/merchant_map.json`, which the app
imports, since the script cannot reach Room itself. Merchant fixes come only from
RRN-exact matches unless `--allow-fuzzy-merchant` is passed. See
`reconciliation/README.md`.

**Room catches up via the importer.** `data/importer/MerchantMapImporter.kt`
reads `merchant_map.json` (Settings → Import Merchant Map, via the Storage Access
Framework so no new permission is needed) and repairs `transactions.merchant` by
`transaction_id`, then recomputes the category — once a debit says "BLINKIT" it
matches the seeded `blinkit → Groceries` rule for the first time. It is
deliberately conservative: only generic merchants are overwritten, only an
"Uncategorized" category is recomputed, and re-importing the same file is a
no-op. It does **not** re-sync repaired rows — Sheets writes are append-only
(rule 10), so re-syncing would duplicate rather than update. The script's
`--apply` is what fixes the Sheet's merchant column; the Sheet's category column
is not repaired by anything.

The map also carries `missing`: **every** statement row the app never
recorded — unmatched rows in the overlap and everything before capture began,
because SIB sometimes sends no SMS. The importer inserts each as a new
transaction (`sender_address = "SIB-STATEMENT"`, empty `raw_body`,
`transaction_id` hashed over the reference) unless Room already holds that
reference, and resolves any queued SMS naming it. Rows with no RRN (interest,
charges, NEFT) get a stable stand-in, `STMT-<yyyymmdd>-<D|C>-<paise>[-n]`, and
a label with long digit runs removed (the interest narration's only number is
the account number).

`--rules reconciliation/private/category_rules.json` (gitignored: it names real
people) adds `rules` (pattern → category) and `overrides` (reference →
category) to the map. The importer saves the rules first, then relabels every
row each rule governs — the editor's "Every transaction" selection — then
applies overrides last.

**`CategoryRepair` runs on every launch** (`data/category/CategoryRepair.kt`).
The real device was found with 4 rules instead of 22, one being `upi → Transfer`,
so every debit read "Transfer". It deletes generic-merchant rules, re-adds missing
starter rules without overwriting edited ones, and (once, flag in prefs) resets
generic-merchant rows from "Transfer" to "Uncategorized".

**Tier 4 — category inference over a payee string (Stage 4.5, optional).** Once
tier 1 or 3 has produced a real merchant name, a model can guess a category for
names no rule covers ("MALABAR GOLD" → Shopping). Inference over text that
exists. Not extraction from text that does not.

Seeded rules deliberately exclude `upi`/`neft`/`imps` → Transfer; see the KDoc
on `SEED_CATEGORIES` in `AppDatabase.kt` for why, and do not re-add them.

## Build stage status (actual)

| Stage | State |
|---|---|
| 0 Project setup | Done. AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9, KSP, compileSdk 35, minSdk 26. |
| 1 SMS capture | Done, verified on-device. Backfill blocked on Android 14+ by design. |
| 2 Local storage | Done. 3 entities, 3 DAOs, `AppDatabase` v4 (real migrations from v2; never rely on the destructive fallback). |
| 3 Parsing | Done for UPI debit/credit only. 15 passing unit tests. See limits above. |
| 4 Categorization | Done, but only reaches credits. Debits have no payee to match on and land as "Uncategorized" by design. |
| 5 Sheets sync | Done and working. Deviations: sheet ID is baked in at build time from `secrets.properties` into `BuildConfig.GOOGLE_SHEET_ID` rather than user-entered; sync interval is 15 min, not the 6 h the spec suggests. |
| 4.5 On-device fallback | Proposed, not started. See below. |
| 6 Review queue | **Done, verified on device (2026-10-24).** Review tab, manual entry form, dismiss, 13 unit tests. Per-sender format-change flagging not built. |
| 7 Reconciliation | **Built.** `reconciliation/` — PDF diff + RRN join + merchant write-back, 59 tests. **Verified on a real 3-month statement** (2026-10-24): 374 rows, 0 balance failures; diff matched 112/171 (109 by RRN). **Round trip verified on device (2026-10-24):** `--apply` wrote 84 merchants; import applied 84/84 fixes, added 59 statement-only rows, cleared 11 from Review, 0 duplicate RRNs; Sheet = Room + header. |

`.github/STAGES.md` is the stage spec, with status blocks for every stage built.
When a stage completes, update it there as well as here.

## Stage 4.5 — Gemini Nano on the Tensor G4

Proposed insertion point: between "regex parser returned null" and "queue it for
the human in Stage 6". The regex parser stays the primary and authoritative path.

    SibParser.parse() → null
        └─► Gemini Nano (on-device, AICore) attempts field extraction
              ├─ confident + schema-valid ──► TransactionEntity (flagged low-confidence)
              └─ unavailable / unsure     ──► UnparsedMessageEntity → Stage 6 queue

**What Nano cannot do: recover a payee for a UPI debit.** That SMS contains no
payee — only amount, account, timestamp, RRN and balance. A model asked to
extract a merchant from it has nothing to extract and will hallucinate a
plausible name straight into a financial record, which is exactly the failure
mode the per-bank regex design exists to prevent. Debit merchants come from
statement matching (tier 3 above), not from inference. Do not point an extractor
at this problem.

Two jobs it can legitimately do:

1. **Field extraction from genuinely unparsed SMS** (ATM, POS, NEFT) into the
   `ParsedTransaction` shape, reusing that data class as the output schema.
   These formats *do* contain the fields; the regex simply does not cover them
   yet. Weigh this against just writing the regex, which is cheaper and testable.
2. **Category inference from a merchant string** that no rule matches — tier 4
   above. The narrower and more defensible of the two.

Design constraints, non-negotiable:

- **On-device only.** No network call, no cloud Gemini fallback, ever. The whole
  premise of this app is that raw financial SMS never leaves the phone (rule 12).
- **This conflicts with `COPILOT-INSTRUCTIONS.MD` rule 6**, which reads "No LLM
  call, no network call, for categorization. Ever." That rule was written to ban
  *cloud* inference. Before Stage 4.5 ships, get an explicit amendment from the
  user rewording rule 6 to permit on-device inference and keep the network ban.
  Do not proceed on the assumption that on-device was the intent.
- **Availability is not guaranteed and must be checked at runtime.** Gemini Nano
  is delivered through AICore, gated per device and per model variant; the Pixel
  9a's memory tier means it may get a reduced variant or none. Every call site
  needs a capability check and a graceful path straight to the Stage 6 queue.
  Never make Nano a hard dependency of the capture pipeline.
- **Never overwrite a regex result.** Nano output is advisory. Mark such rows so
  the review queue and the Stage 7 reconciliation can treat them as unverified.
- **Add a schema/confidence gate.** Reject anything that does not produce a valid
  `ParsedTransaction` — a hallucinated amount in a finance record is the exact
  failure mode the per-bank regex design exists to prevent.
- **Build Stage 6 first.** The review queue is the safety net Nano's failures fall
  into. Do not ship an inference fallback with no human backstop behind it.
- **Stage 7 outranks this.** Statement matching fixes debit categorization for
  real; Nano only decorates merchant names that already exist. Build 6, then 7,
  then reconsider whether 4.5 is still worth it.

## Conventions in the existing code

Follow these; they are consistent enough to be real conventions.

- **Kotlin style:** 4-space indent, `val` over `var`, data classes for models,
  small single-purpose functions, KDoc on every public declaration explaining
  *why* and citing the rule number where one applies. Keep citing rule numbers.
- **Stateless helpers are `object` singletons** taking their dependencies as
  parameters: `CategoryMatcher.getCategory(database, merchant)`,
  `MerchantMemory.rememberCategory(database, merchant, category)`,
  `TransactionIdGenerator.generate(...)`. No DI framework. Keep it that way
  unless the user asks.
- **Room:** snake_case `@ColumnInfo` names against camelCase Kotlin properties;
  epoch-millis `Long` for all timestamps in entities; `Instant` only inside
  `ParsedTransaction`; explicit `indices` on anything queried or deduped.
  Reads that feed UI return `Flow<List<T>>`; reads that feed a worker are
  `suspend` and return `List<T>`. All writes are `suspend`.
- **Parsers** return `null` on any failure and never throw. Callers convert
  `null` into an `UnparsedMessageEntity`.
- **Logging:** `private const val TAG` in a companion object; `Log.i` for
  pipeline milestones, `Log.w` for expected failures, `Log.e` for unexpected.
  **Never log a raw SMS body** (rule 12) — log its length or sender instead.
- **Coroutines:** IO work goes through `withContext(Dispatchers.IO)` inside a
  `suspend` function (the `data/sheets` package and `SmsBackfill` are the
  reference). No `runBlocking`. Activities launch from `lifecycleScope`.
- **Compose:** screens are top-level `@Composable` functions in
  `ui/screens/<Name>/<Name>Screen.kt`, taking `database: AppDatabase` plus
  navigation lambdas. Note the package segments are PascalCase, against Kotlin
  convention — match the existing style for new screens rather than mixing.
  There are no ViewModels; screens collect DAO Flows directly with
  `collectAsState`. `lifecycle-runtime-compose` is on the classpath if you want
  `collectAsStateWithLifecycle`.
- **Secrets:** `secrets.properties` is gitignored and read in `app/build.gradle.kts`
  into `BuildConfig`. Never commit a client ID or sheet ID.

## Hard rules — do not break without asking

From `.github/COPILOT-INSTRUCTIONS.MD`, restated because they are easy to
violate accidentally:

- Only Google Sheets API network calls. No analytics, crash reporting, ads,
  Firebase, or custom backend.
- Only `RECEIVE_SMS`, `READ_SMS`, `INTERNET` and the `spreadsheets` OAuth scope.
  Never request Drive scope.
- Non-whitelisted senders are rejected at the receiver, before the parser.
- Whitelisted SMS that fail to parse are persisted, never dropped.
- Sheets rows are appended once and deduped on `transaction_id`; only the
  Category cell of a synced row may be rewritten (rule 10, amended).
- Schema changes need a real `Migration`. `fallbackToDestructiveMigration()` is
  still set, so a version bump without one wipes the user's data.
- Raw SMS bodies stay in Room for debugging: never logged, never networked,
  never shown outside a dedicated debug view. `ResolveMessageScreen` is that
  view — the body is hidden until the user taps "Show original SMS".

## Outstanding work

- **Stage 6 — optional extra, not built:** flag repeated unparsed patterns per
  sender to detect format changes.
- **`gradlew.bat` is broken from the CLI.** The committed
  `gradle/wrapper/gradle-wrapper.jar` has no `GradleWrapperMain` class, and the
  machine's global `JAVA_HOME` points at a `java.exe` file. Android Studio builds
  are unaffected. Until fixed, build from the CLI with the cached distribution:
  `~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle.bat`, with
  `JAVA_HOME` set to Android Studio's `jbr` (`.claude/settings.json` does this).
  Do not regenerate the wrapper without asking.
- **Stage 7 — done.** First real round trip completed 2026-10-24 (statement
  3 Jun – 2 Sep). Of its 59 statement-only rows, ~18 predated capture, 11 were
  other-bank credits stuck in the queue (parser since fixed), ~30 had no SMS at
  all. Debits after the statement's end are still "UPI" until the next statement.
- **Queued credits from before the parser fix stay queued.** The fix only applies
  to new SMS. Don't resolve them by hand: the next statement import adds them and
  clears them by RRN, whereas a hand-entered row with no RRN would be duplicated.
- **Category scopes and Sheet category sync — built, not yet verified on
  device.** First launch on v3 marks every synced row dirty, so the first sync
  rewrites all of column H once.
- **Later (user's list):** PDF statement straight into the app, replacing the
  desktop script; autopay handling.
- **Statement backfill + user's rules — verified on device 2026-10-24:** 203
  pre-capture rows added (530 transactions, Divya Shree 3/3), 96 rules.
- **Monthly flow (since 2026-10-24):** the user runs `sib_statement_diff.py
  <pdf> --apply --push` on the *unredacted* statement, handles the PDF password
  prompt, deletes the PDF, then imports on the phone. Claude reviews only
  `reconciliation/out/` and the device DB, never the PDF.
- **One queued credit still fails to parse** (UTIB payer, payee contains a
  character outside `[\w\s]`). Parser work is otherwise deferred by the user.
- **Tier 3 is closed end to end**, but the round trip is manual: run the script
  on a desktop, move `out/merchant_map.json` to the phone, import it in Settings.
  There is no automation of that hop and, given rule 11's permission cap, that is
  probably correct.
- Known defects and cross-module inconsistencies are tracked separately; ask
  before assuming any of them have been fixed.
