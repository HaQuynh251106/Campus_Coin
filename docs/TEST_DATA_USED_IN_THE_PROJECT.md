# Test Data Used in the Project

**Project:** Campus Coin
**Documented:** 2026-09-27, by the final QA / test-data / pre-deployment pass
**Source:** the live `campuscoin` database (MySQL 8.0.46) and the scripts in `db/`. Every count below
was queried, not recalled. This document records **actual** project data; no fictional dataset appears
in it.

> **No secrets.** No password, access token, JWT secret, database credential, SMTP credential or AI
> provider key appears here. Sample account **addresses** are given because they are published demo
> credentials in `docs/CREDENTIALS.md`; the passwords are referred to that document rather than
> repeated. The AI prompt examples are the **shapes** of questions asked, with no real user data in
> them.

The data falls into three clearly separated categories: data shipped with the project
(**Preseeded Demo Data**), the state a new account starts in (**Newly Registered User Initial State**),
and data created while testing (**Temporary QA/Test Data**).

| Test Data Category | Example / Description | Purpose | Related Functionality | Status |
|---|---|---|---|---|
| Preseeded demo accounts | Administrator, Alex Nguyen, Bella Tran | Demonstrate both roles out of the box | All | Retained |
| Preseeded default categories | 12 shared (5 income, 7 expense) | Starter set for every account | UC-20, BR-06 | Retained |
| Preseeded demo financial history | Alex 31 transactions / 4 months; Bella 38 / 6 months | Dashboard, reports, trends, AI context | UC-12, UC-15 | Retained |
| Preseeded demo budgets | 5 each; Alex Food `NEAR` 80%, Bella Food `EXCEEDED` 120% | Both alert states visible without editing data | UC-13, BR-12 | Retained |
| The owner's own budgets | 4 (owner account id 8) | That account's own data, not demo data | UC-13 | Retained — not ours to delete |
| Preseeded demo recurring rules | 2 each | Recurring-rule demonstration | UC-09 | Retained |
| Preseeded tips and insights | Alex 9 tips / 3 insights; Bella 6 tips | BR-14 and the monthly narrative | UC-18, UC-17 | Retained |
| Newly registered user | Empty financial state + shared defaults | Prove demo history is not inherited | Onboarding | Retained (as behaviour) |
| Temporary QA data | Post-seed transactions, a QA budget, QA tips, probe rows | Exercise the QA relationships | Cross-module | **Cleaned up** |

---

## 1. Preseeded Demo Data

Shipped by the seed and demo scripts (`db/05_seed.sql`, `db/06_demo.sql` and the merged
`db/merged/campuscoin_full.sql`), present before any QA session, intended for demonstration. The demo
scenario is deliberately shaped so the rules can be verified directly on screen — the script's own
header records this.

### A. Test Accounts

| Account | Role | Purpose |
|---|---|---|
| Administrator (`admin@campuscoin.edu`) | `ADMIN` | Demonstrate every administration surface |
| Alex Nguyen (`an.nguyen@student.campuscoin.edu`) | `STUDENT` | Primary demo account — the whole student experience |
| Bella Tran (`binh.tran@student.campuscoin.edu`) | `STUDENT` | Second profile **and** the ownership-isolation demonstration (BR-02) |

Temporary QA accounts are a separate category — see §3.

### B. Categories

| Group | Detail | Status |
|---|---|---|
| Shared income categories | 5: Allowance, Part-time Job, Scholarship, Gift, Other Income | Active |
| Shared expense categories | 7: Food, Transport, Hostel/Rent, Academics, Subscriptions, Entertainment, Miscellaneous | Active |
| A student's own category | "Gym & Sports" — owned by Bella, not shared | Active |
| A retired shared category | "Bookstore Supplies" (id 14) — created and retired through the admin API | `isActive: false` |

The database holds **15** category rows in total: 12 active shared, 1 student-owned, 2 retired. A
student only ever chooses from the active rows; retired rows are still returned by
`GET /api/v1/categories` so a student can restore one (BR-07).

### C. Transactions

| Group | Detail | Where it is used |
|---|---|---|
| Income examples | Allowance, Part-time Job | Dashboard income total, income-by-category report |
| Expense examples | Food, Transport, Hostel/Rent, Academics, Subscriptions, Entertainment, Misc | Expense-by-category report, trend |
| Historical months | Alex 4 months; Bella 6 months (one near-empty) | Six-month trend, insights, AI context |
| Soft-deleted records | One (a transaction that referenced a retired category) | Present but excluded from every figure (BR-09) |

Alex: **31** live transactions. Bella: **38** live transactions. Both figures are the documented demo
counts in `docs/CREDENTIALS.md`, verified live.

### D. Budgets

| Account | Budgets | States demonstrated |
|---|---|---|
| Alex | 5 | Food `NEAR` at exactly 80%; the other four `ON_TRACK` |
| Bella | 5 | Food **`EXCEEDED`** at 120%; Transport, Entertainment, Subscriptions, Gym & Sports `ON_TRACK` |

This is what makes both alert states observable in a running system without editing any data. Alex's
Food budget is the UAT-07 anchor (30 limit, 24 spent = exactly 80%).

### E. Recurring Transactions

| Account | Rules |
|---|---|
| Alex | Monthly allowance (200.00), Music streaming plan (8.00) |
| Bella | Monthly allowance (150.00), Sports centre membership (22.00) |

Both are `MONTHLY`. They demonstrate the recurring-rule surface and provide the data the scheduler
consumes. **Note:** the default schedule firing has not been observed by a human (see §3 of the
consolidated report) — the rules exist and are wired, but the automatic wall-clock run is unverified.

### F. Tips and Insights

| Item | Detail |
|---|---|
| Tip templates | 7 rows — the rules tips are generated from |
| Alex's stored tips | 9, of which the dashboard shows at most `tips.max_dashboard` (3) |
| Bella's stored tips | 6 |
| Alex's insights | 3 months (2026-07, 2026-08, 2026-09) |
| Bella's insights | 5 months (2026-05 through 2026-09) |
| Bookmarked tips | 2 (Alex, on two of his own tips) |

The gap between "stored" and "shown" is intentional and is the subject of the `tips.max_dashboard`
fix: the dashboard is bounded at read time, the stored history is preserved, and `GET /api/v1/tips`
still returns every stored tip. **No historical tip was deleted to make a count come out right.**

### G. Announcements

| Id | Title | Audience | Severity | State |
|---|---|---|---|---|
| 1 | Welcome to Campus Coin | `STUDENTS` | `INFO` | Active |
| 2 | Import your past spending from a CSV file | `STUDENTS` | `SUCCESS` | Active |

Announcements reach students **only** through `GET /api/v1/dashboard → announcements[]`. They do
**not** create a notification row — that is the current design, and the fix does not change it.

### I. Email / Password Reset Test Data

The flow was verified by hand against real SMTP (message delivered, link targeted this application,
password set once, replay refused, nothing secret logged). No mailbox address, credential or reset
token is recorded here. In the development profile a **file-backed sink** exists so that reset-token
tests need no mail server; the human verification used the real transport, not the sink.

### J. Import / CSV Test Data

The import feature supports a preview → commit flow that separates valid rows from invalid ones. No CSV
fixture is retained in the database — `import_batches` and `import_rows` both hold **0** rows. Any CSV
used during QA was committed into transactions (and cleaned up as temporary data) or cancelled.

---

## 2. Newly Registered User Initial State

Verified by registering a real account through `POST /api/v1/auth/register` and reading every surface
back with that account's own token. A new user starts with **no financial history**.

| Surface | Initial state |
|---|---|
| Profile | The account row, with profile defaults and `ACTIVE` status |
| Shared default categories | Visible and immediately usable (they belong to nobody, BR-06); **0 owned** |
| Transactions | `[]` |
| Budgets | `[]` |
| Recurring rules | `[]` |
| Tips | `0` stored, `0` shown |
| Insights | `{"months": []}` |
| Notifications | `0` |
| Bookmarks | `0` |
| Imports | `{"limit":20,"entries":[]}` |
| Dashboard announcements | `2` — the system notices, which are **shared** content rather than personal data |
| Demo financial history inherited | **None** |

**What is initialised:** the account and profile defaults, access to the shared default categories, and
the shared announcements.

**What starts empty:** every financial surface — transactions, budgets, recurring rules, tips,
insights, notifications, bookmarks, imports, and the user's own categories.

**What the user must create themselves:** their transactions, budgets, recurring rules, personal
categories, bookmarks and preferences. Nothing is derived on their behalf until they do.

**Isolation confirmed:** no demo transaction, budget, rule, insight, tip, bookmark or notification is
copied into a new account, and no demo-only category ("Gym & Sports") appears in a new user's list. The
AI context layer scopes to the authenticated user, so a new account has nothing to leak and sees only
its own (empty) state.

The accounts created purely for this verification were removed afterwards, so the demo set remains
exactly the administrator plus the two student accounts.

---

## 3. Temporary QA/Test Data

Data created while testing. It is **not** part of the product dataset and was cleaned up unless noted.

| Item | Origin | Related functionality | Status |
|---|---|---|---|
| Post-seed transactions (Alex, Bella) | QA sessions | Financial core | Cleaned up via `DELETE /transactions/{id}` |
| A QA budget (Alex) | QA session | UC-13 | Cleaned up via `DELETE /budgets/{id}` |
| QA-generated tips and alert rows | QA sessions | UC-18, BR-12 | Cleaned up; regenerated through `POST /tips/generate` |
| A polluted September insight | QA session | UC-17 | Regenerated through `POST /insights/generate` |
| QA-named default category, id 14 | QA session | UC-20 | Renamed to a real name and **retired** through the admin API |
| QA-named default category, id 15 | QA session | UC-20 | **Retained — cannot be deleted.** A soft-deleted transaction still references it and **BR-09 forbids hard-deleting a transaction**. It is retired, so no student is offered it |
| Withdrawn probe announcements | QA sessions | UC-21 | Removed |
| Probe categories and accounts | This pass's causal probes | Cross-role QA | Removed after each probe recorded its result |
| Extra development accounts | QA sessions | Auth | Removed |

**Accounts present at the end of this document's pass**

| Id | Account | Role | Disposition |
|---|---|---|---|
| 1 | Administrator | `ADMIN` | Retained — demo |
| 2 | Alex Nguyen | `STUDENT` | Retained — demo |
| 3 | Bella Tran | `STUDENT` | Retained — demo |
| 8 | A third student account, owned by the project owner | `STUDENT` | **Retained — ownership uncertain, deliberately not deleted** |

### H. AI / Chatbot Test Inputs

The AI surfaces were verified against **real** authenticated user data. The examples below are the
**shapes** of the prompts exercised in the manual multi-turn verification; **no expected figure is
hardcoded anywhere** in the frontend or the tests, and the amounts a reader would see come from the
seeded data at read time.

| Turn | Prompt (sanitised) | What it exercises |
|---|---|---|
| 1 | "How much did I spend this month?" | Current-month total, from the user's own data |
| 2 | "What about Food?" | Context carry-over to one category |
| 3 | "And last month?" | Multi-turn reference resolution |
| 4 | "Am I over budget anywhere?" | Budget status, not just totals |
| 5 | "What are my tips?" | The tips surface |
| 6 | A question outside the financial scope | Refusal behaviour |

Ownership is enforced by the backend: the context layer applies the authenticated user's id
automatically, so a student's questions can only draw on that student's own data, and the provider has
no database access at all.

## Cleanup Status

**Retained Demo/Seed Data** — all of §1 and the behaviour in §2. Both demo students remain fully
populated and immediately demoable; the 12 active shared categories, 2 student-owned/retired rows,
7 tip templates, 16 settings rows, 2 announcements, and all historical tips, insights, bookmarks,
notifications and recurring rules are intact.

**Temporary QA Data — Cleaned Up** — all post-seed QA transactions, the QA budget, QA-generated tips
and alert rows, the withdrawn probe announcements, the probe categories and accounts, and the extra
development accounts. Database counts were verified back to baseline:

| Table | Rows | Table | Rows |
|---|---|---|---|
| `users` | 4 | `notifications` | 3 |
| `transactions` | 131 | `announcements` | 2 |
| `categories` | 15 | `bookmarks` | 2 |
| `budgets` | 14 | `recurring_rules` | 4 |
| `user_tips` | 14 | `budget_alert_log` | 3 |
| `insights` | 9 | `tip_templates` | 7 |
| `system_settings` | 16 | `import_batches` / `import_rows` | 0 / 0 |

`budgets` is **14**, not the 10 the demo students account for: 5 belong to Alex (ids 1–5), 5 to Bella
(ids 6–10), and **4 (ids 14–17) belong to the project owner's own account (id 8)** — that owner's data,
left untouched. `announcements` is **2**, both the seeded student notices; a probe announcement created
by a causal test was found and removed on re-verification.

**Uncertain — Deliberately Not Removed** — two items, both disclosed rather than hidden:

1. **The retired default category id 15.** BR-09 makes the transaction that references it undelible, and
   dropping the category would orphan that record or violate the rule. It is retired, so it is invisible
   to students as a choosable category.
2. **The project owner's own account (id 8) and its data.** A real account whose ownership is not ours
   to assume; the governing rule is explicit that uncertain ownership means do not delete.

Neither affects the demo, and neither is visible in a student's choosable category list.

The current consolidated handoff — including the causal evidence for these claims — is
`docs/FINAL_QA_TEST_DATA_DEPLOYMENT_REPORT.md`.
