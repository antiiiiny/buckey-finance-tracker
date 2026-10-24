# Wallet, lending, savings and holdings — design (approved 2026-10-25)

Round 1 of 3 toward tracking all the user's money, not just the SIB account.
Round 2: other bank accounts. Round 3: a fully separate Business profile (its
own database and Sheet, "almost like an app clone"). Code: `data/summary/Ledger.kt`
(the model), `data/wallet/` (the user's actions), UI on Home, Pending, the
category editor and the account pages.

## The money model

Net = **bank + wallet + lent − holding**. Every row's effect:

| Row | Spent | Net | Pot |
|---|---|---|---|
| Debit, spending category | +x | −x | |
| Debit Transfer, marked **Cash** | | 0 | wallet +x |
| Debit **ATM Withdrawal** (since the wallet started) | | 0 | wallet +x |
| Debit **Lend** (to someone) | | 0 | lent +x |
| Debit **Savings** (was "Self Transfer") | | −x | |
| Debit paid out of a holding | | 0 | holding −x |
| Debit x **clearing** holding h | max(x−h, 0) | h−x | holding −h |
| Credit Income / Self Transfer | | +x | |
| Credit Transfer (a friend paying back) | −x | +x | |
| Credit linked to a loan (**Loan Repaid**) | | 0 | lent −x |
| Credit **Holding** (held for someone) | | 0 | holding +x |
| **Cash payment** (entered by hand) | +x | −x | wallet −x |
| Wallet recount | | ±x | wallet ±x |
| Opening cash count | | 0 | wallet +x |

- The wallet starts at the first cash count (the user started it at ₹168 on
  2026-10-25). Nothing earlier moves it.
- A Transfer debit since then waits in Pending until the user picks **Cash** or
  **Lend** (and who). Undecided, it only leaves the bank.
- **Suggestions, never assumptions:** a payment within ±₹10 (Preferences →
  Matching range) of an open holding suggests clearing it; a credit within it of
  an open loan suggests clearing the loan. A Transfer credit is never linked to a
  loan on its own. "Not this" hides a suggestion for good.
- "Total spent" (Preferences) counts every bank debit except holding payouts;
  cash payments aren't added, since the ATM withdrawal already counted that cash.

## Where it shows

- Home: Net breakdown line under the figures ("Bank · Wallet · Lent · Holding",
  zero parts hidden) → **Your money** sheet: wallet with Recount, open loans and
  holdings per person with history. "+ Cash" button for cash payments. Cash rows
  have a wallet icon; linked rows show who on their second line.
- Pending: "Transfer or lend?" (Cash | Lend), "Settle a holding or loan?"
  (Clear | Not this), SMS to resolve, To categorize.
- Category editor: Money with someone — debit: Cash in hand, Lend…, Pay out of a
  holding… (optionally clearing it); credit: Holding…, Loan paid back….
- Profile icon (top right of Home): Profile (name, Google Sheets, data tools),
  Preferences (Net shows, Spent shows, Matching range), Bank statement (merchant
  map import; PDF upload later). The Settings tab is gone.

## Sheets

- Cash rows go to Transactions with Source = `cash`.
- Holding rows never go to Transactions; a synced row that becomes a holding is
  deleted from it. The **Holdings** tab lists every movement (date, who, In/Out,
  amount, remaining) and is rewritten from Room when it changes. Rule 10 amended.
- The statement diff skips cash rows. The **Dashboard** tab is formulas over
  Transactions, created once by the app; its own dropdown picks the Spent rule.
  It can't see holdings or the SMS balances, so its Net is the month's change and
  an undecided Transfer debit counts as cash. Spending by category is a pie.

## Data

Room v5 (`MIGRATION_4_5`): `link_kind`, `link_group`, `counterparty`,
`link_amount` on `transactions`; debit "Self Transfer" renamed to "Savings".
Bulk category writes skip linked rows; a single-row edit unlinks (and releases
rows tied to a loan or holding it opened). ATM SMS parse from the user's sample
(no date or reference in it: the arrival time is the date).
