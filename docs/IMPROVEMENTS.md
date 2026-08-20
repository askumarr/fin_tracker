# FinTracker — improvement backlog

## Priority sequence (in progress / done)

1. **Same-day payment dedupe** — done: bill-payment keys, insert-time sibling lookup, startup merge
2. **Bulk confirm / dismiss in review** — done: multi-select + bulk actions + Undo snackbar
3. **Search + day grouping in History** — done
4. **Category breakdown + pie** — done (Home + History month view)
5. **Rebuild / dismiss feedback with counts** — done (Backup rebuild message + review Undo)
6. **Budgets** — done: per-category monthly limits + 80% alerts (More → Budgets)
7. **Sender learning** — done: dismiss → IGNORE sender; confirm → ALLOW (applied on ingest)
8. **Recurring detection** — done: SIP/rent/EMI-style patterns (More → Recurring)
9. **Smart categorization** — done: built-in India merchant keywords + normalized merchant learning on edit/review
10. **Statement import** — done: Canara CSV auto-detect + Canara e-Passbook PDF

## Still open (later)

- Smarter category ML / embeddings (beyond keyword + learned rules)
- More bank PDF layouts beyond Canara e-Passbook
- Refund linking to original expense
- More bank templates / merchant cleanup
- Account-aware balances / credit-card statement view
- Compare months % change
- Export filtered period CSV
- Biometric lock / home-screen widget
- Play SMS policy packaging polish

## Notes

- **“This month”** = IST calendar month (`Asia/Kolkata`), 1st 00:00 through last ms of the month.
- Transfers (credit-card bill payments) are excluded from spend/income totals.
- After parser/dedupe upgrades: Backup → **Clear SMS entries & rebuild**, or **Merge same-day SMS duplicates**.
- **Categories**: SMS/CSV auto-categorize via learned merchant rules, then built-in India keyword map (Amazon→Shopping, Swiggy→Food, …), then type heuristics (TRANSFER→Transfers, salary credits→Salary). Editing a category teaches the merchant root for next time.
- **Import**: More → **Import statement**. Auto-import Canara CSV (skips preamble, strips Excel `="..."` cells, extracts UPI merchant from narration) or Canara e-Passbook PDF. CRED Club → Transfer.
