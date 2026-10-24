---
name: cli-build-test
description: Compile the Android app and run its JVM unit tests and the reconciliation Python tests from the CLI, without touching the broken Gradle wrapper. Use after changing Kotlin or Python code, before telling the user something works.
---

# CLI build and tests

`gradlew.bat` is broken from the CLI (the wrapper jar has no `GradleWrapperMain`).
**Do not regenerate or repair the wrapper, and do not change dependencies or build
config.** The user builds in Android Studio and has asked for hands-off build
tooling. Report problems; don't fix them.

## Kotlin: compile and unit tests

Use the cached Gradle distribution with Android Studio's JBR. `.claude/settings.json`
already sets `JAVA_HOME`. Set it explicitly too, in case the shell lost it:

```bash
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle.bat | head -1)
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" "$GRADLE" -p "/c/Antony/Christ uni/buckey-finance-tracker" testDebugUnitTest
```

- `compileDebugKotlin` compiles only. `testDebugUnitTest` compiles and runs
  `app/src/test`.
- One class: add `--tests com.buckey.financetracker.review.UnparsedResolverTest`.
- Results: `app/build/reports/tests/testDebugUnitTest/index.html`. Failures also
  print to the console.
- The first run is slow (daemon start).
- If Android Studio has a build running at the same time, the two can fight over
  `app/build`. Ask the user before retrying.

Current suites: `SibParserTest` (15), `UnparsedResolverTest` (13),
`MerchantMapImporterTest` (26), `CategoryUpdaterTest` (8), `CategoryMatcherTest` (4),
`SheetCategorySyncTest` (5), `SheetRowFormatTest` (5). 76 in all.

Unit tests don't prove UI or device behaviour. For that, write steps with the
`device-verify` skill.

## Python: reconciliation tests

```bash
cd "/c/Antony/Christ uni/buckey-finance-tracker/reconciliation" && .venv/Scripts/python -m unittest test_reconcile
```

73 tests. The PDF tests need `reportlab` (from `requirements-dev.txt`, already
installed in the venv) and skip without it. If a skip count appears, say so rather
than reporting everything green.

## Reporting

State the exact command run and the pass/fail counts. If something fails, show the
failing test and its message, not a summary.
