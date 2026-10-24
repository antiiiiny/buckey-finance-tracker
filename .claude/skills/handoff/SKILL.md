---
name: handoff
description: Two jobs. (1) Onboarding — the order a fresh agent reads this project in to get productive fast. (2) The pre-commit / pre-handoff checklist that keeps docs, test counts and .github/HANDOFF.md true. Use at the start of a new chat, before every commit, whenever the user says they are handing off to another chat, and when the context window is getting full.
---

# Handoff

The user moves between chats. Each new agent starts cold, so the repo itself has
to carry the context: CLAUDE.md for how things work, STAGES.md for what is done,
`.github/HANDOFF.md` for where the last session stopped.

## Part 1 — Onboarding: read in this order

Stop as soon as you can do the task; the later steps are for broad work.

1. **`.github/HANDOFF.md`** — current state, in-flight work, next steps, open
   questions. Freshest and shortest; read it first.
2. **`CLAUDE.md`** — architecture as built, why per-bank regex, how
   categorization works (the 4 tiers), hard rules, outstanding work. The most
   important single file. Where it disagrees with other docs, it wins — flag it.
3. **`.github/COPILOT-INSTRUCTIONS.MD`** — the 14 numbered rules. Code and
   KDoc cite them by number ("rule 10"), so know them.
4. **`.github/STAGES.md`** — the status blocks at the bottom (Stage 5 onwards,
   "Stage 7 — Follow-up", "What's left"). Skip the original specs at the top
   unless the task touches that stage.
5. **State of the tree:** `git status`, `git log --oneline -10`, current branch.
6. **Memory** (`MEMORY.md` is auto-loaded) — user preferences and privacy rules.
   Verify any file or flag a memory names before relying on it.
7. **Code, in pipeline order** (under `app/src/main/java/com/buckey/financetracker/`):
   1. `data/parser/banks/SibParser.kt` — the two SMS formats; debits have no payee.
   2. `data/sms/SmsReceiver.kt` — the real orchestrator.
   3. `data/db/AppDatabase.kt`, `entities/TransactionEntity.kt`, `dao/TransactionDao.kt`
      — schema version, migrations, the bulk category SQL.
   4. `data/category/` — `CategoryMatcher` (credit rule), `CategoryUpdater`
      (editor scopes), `CategoryRepair` (runs at launch), `GenericMerchant`.
   5. `data/importer/MerchantMapImporter.kt` — the statement → Room path; its
      KDoc lists the apply order.
   6. `data/sheets/` — `SheetsSyncWorker`, `GoogleSheetsRepository`,
      `SheetRowFormat` (column layout), `SheetCategorySync`.
   7. `ui/screens/` — only the screen the task touches.
8. **Reconciliation** (only for statement work): `reconciliation/README.md`, then
   `sib_statement_diff.py` (CLI, map writer), `statement.py` (PDF extraction),
   `matching.py`. Then load the `sib-statement` skill.
9. **Other skills:** `cli-build-test` (building from CLI — the wrapper is
   broken), `device-verify` (instructions for the user's Pixel 9a),
   `sib-statement`.

**Never read:** statement PDFs (`reconciliation/statements/`, anywhere else),
`reconciliation/private/` unless the task is the rules file,
`credentials.json`, `token.json`, `secrets.properties`. See the memory on
unredacted statements.

## Part 2 — Before every commit

Run this before every `git commit`, not only at handoff. A commit that leaves
the docs behind the code breaks the next agent's onboarding.

1. **Tests green.** `cli-build-test` skill: Kotlin `testDebugUnitTest` and the
   Python suite. Note the counts:
   - Kotlin: sum `tests=` in `app/build/test-results/testDebugUnitTest/*.xml`.
   - Python: the `Ran N tests` line.
2. **Counts agree everywhere.** Grep the old number and fix every hit:
   `grep -rn "<old count> tests\|(<old count>)" CLAUDE.md .github reconciliation/README.md .claude/skills`.
   Per-suite counts live in `cli-build-test/SKILL.md` and STAGES.md.
3. **Docs follow the change.** For each kind of change, the docs to touch:
   | Change | Update |
   |---|---|
   | New/changed behaviour, file, flow | CLAUDE.md (architecture, key files, the relevant section) |
   | Stage progress, verification on device | STAGES.md status block + CLAUDE.md stage table |
   | Schema version / migration | CLAUDE.md key files + stage 2 row, `device-verify` skill |
   | Sheet layout or sync behaviour | CLAUDE.md, rule 10 in COPILOT-INSTRUCTIONS (ask first), `SheetRowFormat` KDoc |
   | Script flag or map field | `reconciliation/README.md`, `sib-statement` skill |
   | A rule changed at the user's request | COPILOT-INSTRUCTIONS with "Amended <date> at the user's request" |
   | Something deferred or found broken | CLAUDE.md "Outstanding work" + STAGES.md "What's left" |
4. **`.github/HANDOFF.md` is current** (format below). Update "Last updated",
   what changed, and next steps.
5. **No stale files.** New placeholders, dead functions, scratch output in the
   tree? Check with `git status` and grep for unused symbols you added or
   replaced.
6. **Secrets scan** on `git diff --cached`:
   `grep -nEi "[0-9]{9,}|apps.googleusercontent|spreadsheets/d/|BEGIN PRIVATE"`.
   Expect only fake fixtures (e.g. `620896250099`) and `STMT-` refs. Never stage
   statements, `reconciliation/private/`, `reconciliation/out/`,
   `credentials.json`, `token.json`, `secrets.properties`, or real names from
   the rules file (HANDOFF.md included).
7. Commit with the attribution trailer. Push or merge only when asked.

## Part 3 — When the user says "handoff", or context runs low

If the session is long, a compaction has already happened, or the remaining
work is big, tell the user once, in one line, that a handoff is advisable, and do
it when they agree. Don't wait until the window is exhausted. Never hand off
mid-edit: finish the step or revert it and record where it stood.

1. Run Part 2 in full, even if nothing seems to have changed.
2. Rewrite `.github/HANDOFF.md` for someone with zero context: what was asked
   this session, what is done and verified (with numbers), what is in flight,
   exact next steps, decisions the user made that aren't in code yet.
3. Update memory for anything durable about the user or their preferences.
4. Commit if there are changes (they asked for handoff; still say what you're
   committing). Ask before pushing unless they said to.
5. Reply with: the commit hash, the branch, whether it's pushed, and a
   one-paragraph summary the user can paste as the next chat's first message,
   ending "Start by loading the handoff skill."

## HANDOFF.md format

Keep it under ~80 lines; it points into CLAUDE.md and STAGES.md rather than
repeating them.

```
# Handoff
Last updated: <yyyy-mm-dd> · branch <name> · last commit <hash>

## State in one paragraph
## Done this session (verified how)
## In flight / not verified
## Next steps (ordered)
## User decisions and preferences to respect
## Watch out for
```
