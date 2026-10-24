# Home screen — design brief (approved 2026-10-24)

Produced by the UI intake interview (CLAUDE.md, "UI intake"), then refined
against the user's light/dark mockups. The mockup image is **not** in the repo:
it shows a real person's name. Code: `ui/screens/Home/HomeScreen.kt`.

## Brief

- **Purpose:** the month at a glance first, then recent transactions.
- **Focal point:** two equal figures.
  - *Spent this month* — by default debits not categorized Transfer or Self
    Transfer, **minus credits categorized Transfer** (a friend paying back
    something the user paid for). User's reasoning: a Transfer debit is usually
    handing money over for cash, a Transfer credit is a repayment; neither
    changes what was really spent, so both stay "Transfer". Self Transfer
    credits are the user's own money and don't count. Settings → "Spent shows"
    switches to *Total spent* (every debit). Day and month totals follow it.
  - *Net* — by default the **running balance**: the balance quoted by the
    earliest SIB SMS, plus every credit and minus every debit after it (all
    categories). Settings → "Net shows" switches it to *this month's net*
    (the month's credits minus debits).
  - Tapping Net opens **Verify balance**: Buckey's estimate vs the latest SMS
    balance, "Matches" or "Off by ₹X", the SMS date, and a hint.
- **Layout:** plain top bar with a small "Buckey" label over the month
  ("October ▾" opens a month list) and ⋮ (Group by day / Group by month, both
  always listed, a tick on the current one) → summary → "N to categorize" (tap filters the list)
  → sync banner when sync is wrong → rows grouped by day with each day's spending.
- **Rows:** two lines (merchant + amount; category · time), no cards. Neutral
  category circle; credits get a green circle and "+₹" in green.
- **States:** empty month keeps the summary, says "No transactions in October"
  and still shows the latest transactions ("See all" → month grouping). Fully
  empty only on a fresh install: welcome, "Import statements" (merchant-map
  import), "I'll do this later". Sync banner: unsynced rows older than an hour,
  or any unsynced row while signed out (action: Retry / Sign in).
- **Color:** Buckey's own palette, dynamic color off: neutral greys, one deep
  green; softer green on charcoal in dark mode.
- **Type:** 4 sizes (28/20/15/13 sp), Normal and Medium only.
- **Motion:** figures crossfade when they change; M3 defaults for sheet and menus.
- **Accessibility:** rows read as "Debit, 450 rupees, to BLINKIT, Groceries,
  9:14 PM"; debit/credit differ by sign, not only color; 48dp targets.
- **Navigation:** Home · Pending · Settings. Pending = unreadable SMS (Stage 6)
  plus uncategorized transactions, styled like Home: "SMS to resolve | To
  categorize" counts on top, then a headed section for each with one line on
  what to do, uncategorized rows grouped by day. Sync status moved into
  Settings, which also has "Open in Google Sheets".

## Decisions taken while building

- The balance is read from the stored SMS text (`SibParser.parseBalance`), not
  a new column, so no migration. All 268 SMS-backed rows on the device matched
  the two formats; anchor + later rows equalled the latest SMS balance exactly.
- The check compares the estimate *at the latest SMS* with that SMS's balance,
  so statement-only rows dated after it don't cause a false mismatch.
- Mismatch hint changed from the mockup's "check for uncategorized items"
  (categories don't affect the balance) to "SIB sent no SMS for a transaction;
  importing your latest statement adds the missing rows".
- Day and month group totals show spending (same definition as Spent); "+₹"
  when repayments outran spending that day.
- Settings → About shows "Configured" instead of the Sheet ID itself.
- Merchants are shown as stored (e.g. "BLINKIT", "UPI"), not title-cased.
