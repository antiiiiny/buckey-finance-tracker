# Handoff
Last updated: 2026-10-24 · branch `main` · UI redesign committed on top of
`ce25a0d` (`git log -3` shows the hash; not pushed)

New here? Load the `handoff` skill and read in its order: this file, then
CLAUDE.md, COPILOT-INSTRUCTIONS.MD, STAGES.md.

## State in one paragraph
Stages 0–7 are done and verified on the user's Pixel 9a. This session added UI
tooling and rules, then redesigned the UI from an approved brief
(`.github/design/home-screen.md`): Home (Spent + running balance with an
SMS-verified check), Pending tab, Sync and "Open in Google Sheets" in Settings,
Buckey's own palette and type scale. It builds and all tests pass; it has
**not** been run on the device yet.

## Done this session (verified how)
- Skills copied in by hand (no installer run), byte-identical to upstream:
  `.claude/skills/frontend-design/` (Anthropic) and `.claude/skills/edge-to-edge/`
  (Google android/skills). No official phone Compose/M3 design skill exists; the
  Android CLI is not installed and isn't needed.
- CLAUDE.md: "UI rules" and "UI intake — interview before any UI code".
- Intake interview → brief → user's mockups → build. Second round of user
  feedback built: "Buckey" label on Home, Pending restyled like Home, Open in
  Google Sheets, Spent shows (debits minus transfers and repayments / total), ⋮
  lists both groupings with a tick.
- Balance logic checked against a copy of the device DB (deleted afterwards):
  268/268 SMS rows carry a parseable balance; earliest SMS balance + every later
  row = latest SMS balance, difference ₹0.00.
- Tests: 102 Kotlin (was 76), 73 Python, all green via `cli-build-test`. Scan:
  no literal colors/sp/dp/font weights in the new UI.

## In flight / not verified
- **On-device check of the redesign.** Use the `device-verify` skill: tabs,
  Home in light/dark, Buckey label, month picker, ⋮ ticks, "N to categorize"
  filter, Verify balance (expect "Matches"), Settings → Net shows / Spent shows /
  Open in Google Sheets, Pending layout, sync banner.

## Next steps (ordered)
1. User verifies on device; fix what they find.
2. Bring `CategoryEditorScreen` and `ResolveMessageScreen` in line with the UI
   rules (via the intake interview), incl. `adjustResize` for their text fields.
3. The user's "later" list: PDF statement in the app, autopay, more SIB formats.

## User decisions and preferences to respect
- UI: clean and minimal; intake interview before any UI code; Buckey's own
  palette (not Material You); credits "+₹" in green; neutral category circles.
- Net = running balance by default. Spent = debits minus Transfer debits minus
  Transfer credits by default; both "Transfer" directions stay "Transfer".
- Credits are only Income or Transfer. Income = one named person (see memory);
  a similarly named other person is Transfer. Keep real names out of the repo
  (the mockup image is not committed for that reason).
- Never open statement PDFs; never pass `--apply`, `--force` or
  `--allow-fuzzy-merchant` yourself.
- Don't touch the Gradle wrapper or dependencies (Compose BOM stays 2024.10.01).
- `.claude/settings.json` WebFetch allow rules stay (user, 2026-10-24).
- Commit only when asked; push/merge only when asked. User's terminal is PowerShell.

## Watch out for
- `WindowInsetsRulers` / `fitInside` from the edge-to-edge skill don't exist in
  this Compose BOM; use `imePadding()` and Scaffold padding.
- 7 transactions are still Uncategorized (they now show in Pending).
- `fallbackToDestructiveMigration()` is still set: every DB version bump needs a
  real migration. The redesign added no schema change.
