---
name: device-verify
description: Write step-by-step Android Studio verification instructions for the user to run on their Pixel 9a — what to click, which Logcat tag to filter, which Database Inspector table/columns to check, and the exact UI text to expect. Use when a feature needs on-device verification or the user asks how to test something in Android Studio.
---

# On-device verification walkthroughs

The user tests on a physical Pixel 9a (Android 15) through Android Studio and reads
code in VS Code. Claude cannot see the device, so the output is instructions the user
follows and reports back on. Make every step observable: say what to click and what
should appear.

## Before writing steps

Read the code involved. Don't recall it from memory. Get the exact strings:
- UI labels: `grep -o 'Text("[^"]*"' app/src/main/java/com/buckey/financetracker/ui/screens/<Name>/<Name>Screen.kt`
- Log lines: `grep -n 'Log\.[iwe](' <file>`. The tag is `private const val TAG` in that class.
- Column names: `@ColumnInfo(name = ...)` in `data/db/entities/`.

Quote those strings exactly in the steps.

## Fixed facts about this app

- **Tabs** (bottom bar, left to right): Transactions, Review (badge = unresolved
  count), Sync, Settings.
- **Logcat tags:** `SmsReceiver`, `SheetsSyncWorker`, `UnparsedResolver`,
  `MerchantMapImporter`, `MainActivity`, `SettingsScreen`. Filter as
  `package:mine tag:<Tag>`.
- **Database Inspector:** View → Tool Windows → App Inspection → Database Inspector,
  process `com.buckey.financetracker`. Tables are `transactions`,
  `unparsed_messages` and `category_rules` (22 seeded rows).
- **Unit tests in Android Studio:** right-click
  `app/src/test/java/com.buckey.financetracker` → Run 'Tests in …'.
- **Rule 12 check for any capture/sync step:** the raw SMS body must never appear in
  Logcat. Only the sender and length should.

## Things a physical phone cannot do

- **Fake a SIB sender.** Only `XX-SIBSMS-S/T/P` passes the whitelist, and a phone
  can't send from that ID. Live-capture tests need a real transaction (a ₹1 UPI
  payment works).
- To seed the **Review queue**, use a real SIB OTP/alert if one exists. Otherwise, in
  Database Inspector use New Query:
  `INSERT INTO unparsed_messages (sender_address, raw_body, received_at, failure_reason, manually_resolved) VALUES ('AD-SIBSMS-S', 'TEST ...', strftime('%s','now')*1000, 'manual test', 0);`
  Room's Flows may not notice writes made from the Inspector. If the UI doesn't
  update, tell the user to force-stop and reopen the app.
- Anything resolved into `transactions` **will sync to the real Sheet**. Say so, and
  include cleanup steps: delete the Sheet row, the `transactions` row, and any
  `category_rules` row a test category created.

## Shape of the output

Numbered steps grouped by feature. Each step has an **action** and an **expect**.
End with a short "report back" list of what the user should tell you: pass/fail per
step, plus any unexpected Logcat line. Keep it to what this change touches; don't
re-verify the whole app unless asked.
