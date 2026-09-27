# Decision log — corrective causal QA pass

Every entry below is a point where the application, the API guide, the security documentation and an
earlier QA report did not all say the same thing, and a choice had to be made about which source
governs. The choices are recorded here rather than made silently, because a QA pass that quietly
redefines what "pass" means is worth nothing.

**Source priority used throughout** (per the task's §1): SRS / approved business requirements → use
case and business-flow specification → the API contract in `docs/api/` → the current backend → the
current frontend → the database schema, procedures and runtime → the previous QA reports. The two
previous reports were treated as **evidence, not truth**.

---

## D-01 — REL-14: insight generation is explicit, and a read never generates

**The conflict.** The baseline report records REL-14 as `Financial state → Month closed/read →
GET /insights` with a PASS, which reads as though reading an insight produces one. `docs/api/ai-and-insights.md`
§6 says the opposite in as many words: "**Reading does not generate and does not call any AI
provider.** A month whose insight was never produced answers `404`".

**Which source governs.** The API contract, and behind it the schema. `InsightViewDao` reads the
`insights` table with no generation step; the generation path is a separate operation (`POST
/api/v1/insights/generate?month=`) that calls `sp_generate_monthly_insight`. Nothing in
`GET /api/v1/insights` reaches a procedure or a provider.

**Decision.** The relationship is corrected, not the application. It is now stated as the four hops
the architecture actually has:

1. **Financial state** — transactions exist for a month.
2. **Insight generation** — `POST /api/v1/insights/generate?month=YYYY-MM`, an explicit request.
3. **Insight snapshot** — `insights` gains one row for that (user, month).
4. **Insight read** — `GET /api/v1/insights?month=YYYY-MM` returns it, and answers `404` before step 2.

**What was deliberately NOT done.** No generation was moved onto the read path to make the old
relationship description literally true. That would have been "artificially making GET /insights
live", which the task forbids and which would also make a retry or a prefetch write to the database —
the exact property `docs/api/advanced.md` protects for the same kind of read.

**Consequence for the report.** The old one-hop row is removed and the four-hop chain is recorded
under REL-14 with the generation hop HOP-DEPENDENT (it is the cause; the read is the dependent read).

---

## D-02 — REL-17: the transaction feed and the recent-activity history are two relationships

**The conflict.** The baseline records REL-17 as `POST /transactions → Recent activity / feed` PASS.
The slash conflates two different reads of two different tables: the *feed* is `GET
/api/v1/transactions` reading `transactions`; *recent activity* is `GET /api/v1/recent-activity`
reading `recent_activity`. Nothing in the transaction write path writes `recent_activity`.

**Which source governs.** The API contract. `docs/api/advanced.md` §"recent activity" states that
recording an entry "is an explicit request rather than something a read does for you" and that the
record "is the client's own; nothing else writes it". A grep of `db/03_procedures.sql` and
`db/04_triggers.sql` finds no trigger, no procedure and no view that writes `recent_activity` from a
transaction event; the only writer is `sp_touch_recent_activity`, called by `POST
/api/v1/recent-activity` and by nothing else.

**Decision.** Split into two, and neither is allowed to borrow the other's evidence:

- **REL-17a — transaction creation → transaction feed.** `POST /api/v1/transactions` then
  `GET /api/v1/transactions` returns the new record. This is the hop the baseline actually measured.
- **REL-17b — activity recording → recent-activity history.** `POST /api/v1/recent-activity` with
  `VIEWED` or `EDITED`, then `GET /api/v1/recent-activity` returns the entry. This is what REL-38
  measured, and it is a *separate* relationship because its cause is a different endpoint.

**What must not be claimed.** That creating a transaction creates a recent-activity entry. It does
not, by design. See D-03 for whether that design is right.

---

## D-03 — Recent Activity is manual by design; the scope decision is recorded, not assumed

**The question.** Is automatic instrumentation of recent activity required by the business
specification, or is the deliberate manual recording the intended behaviour?

**What the sources say.** Every available source agrees on manual:

- `docs/api/advanced.md`: recording "is an explicit request rather than something a read does for
  you… Opening a transaction does not record anything on its own — the transaction read stays a pure
  read, so a retry or a prefetch never writes."
- `docs/api/FRONTEND_API_GUIDE.md` §(75, 76): "Call `POST /recent-activity` with `VIEWED` after
  opening a transaction screen and `EDITED` after a successful change… **This is the client's own
  record; nothing else writes it.**"
- `sp_touch_recent_activity`'s own comment describes it as record-or-refresh for a caller-named
  transaction, and `RecentActivityService` corroborates.

No SRS or use-case document is present in this repository (OB-001) so the authoritative requirement
cannot be quoted directly; the API contract is the highest available source and it is unambiguous.

**Decision — the behaviour is correct; a frontend usability gap is recorded separately.**

1. **The behaviour is CORRECT-BY-DESIGN, not a defect.** Automatic instrumentation would contradict
   three separate documented statements, one of them in a procedure's own comment. Nothing is changed
   on this account.
2. **There is a real gap, and it is in the client, not the contract.** The screen ships a "Log an
   action" form where the student types an *entry number* and picks the action. The guide describes
   the call being fired by the client as a consequence of opening or editing an entry. Typing a
   primary key is not that. The gap is a UX defect in this build's screen, and it does not make the
   backend relationship fail.
3. **Consequence for REL-17b.** It is tested through the endpoints (`POST` then `GET`), which is the
   contract. It is *not* described as "opening a transaction records an entry", because that is false
   in this build.
4. **The change to the screen is scoped, not made here.** Rewiring the calls into the transaction
   view and edit paths touches screens the user is concurrently editing, and the task requires
   surgical, merge-friendly changes. It is recorded as remaining work with its exact shape rather than
   done unilaterally; see the report's residual list.

---

## D-04 — REL-27: insight bookmarking is a scope gate, not a missing feature

**The conflict.** The baseline marks REL-27 (Insight → Bookmark) NOT APPLICABLE. That is right, but
the reason matters and changed during module 12.

**What the code does.** `BookmarkService` refuses an `INSIGHT` bookmark by name, with `400
VALIDATION_ERROR` and a field error reading: "Insights (UC-17) are not enabled yet, so only `TIP` can
be saved. The column accepts `INSIGHT` and the endpoint will serve it once UC-17 is approved." The
behaviour is pinned by `BookmarksApiIT#anInsightIsRefusedByName`. It is recorded as OB-015, and
`docs/api/FRONTEND_API_GUIDE.md:1173` instructs clients not to offer it.

**Decision — Case 1: out of scope until UC-17 is enabled.**

- The refusal is deliberate, tested, named in a blocker and documented to clients. It is not a gap
  found by QA; it is a scope gate that the application enforces and explains.
- **The relationship remains NOT APPLICABLE**, and expands to state *why*: it is refused by name with
  a documented reason, so "not applicable" means "gated by an approved scope decision", not "not
  tested".
- **Nothing is implemented and no test is written to expect a success.** Implementing it would mean
  enabling UC-17, which is a project-owner decision recorded in OB-015 and outside this pass.
- **The gate is verified, not assumed:** the pass includes a negative test that an `INSIGHT` bookmark
  is refused by name, so the out-of-scope verdict rests on observed behaviour.

---

## D-05 — Dashboard budget strip: rank by the server's own severity before capping

**The finding, and why it is a real defect.** The dashboard strip rendered
`budgets.slice(0, 4)`. `GET /api/v1/budgets` orders by `categoryName` ascending and then `budgetId`
ascending (`BudgetConsumptionDao`), so the four rows shown were whichever categories sort first
alphabetically. A student whose "Books" budget was `EXCEEDED` would see four on-track categories and
no indication of the one that was over its limit — on the screen whose stated purpose is "Monthly
Budget Status".

**Why this is not "inventing a ranking rule".** The severity already exists, computed by the server:
`consumptionStatus` is a published field of the response, it comes from `v_budget_consumption`'s
`CASE`, and it is the same comparison `sp_check_budget_alerts` makes. The change orders the rows by a
classification the API already sends rather than by a rule the client makes up. The tie-break inside
a severity is `consumedPct` descending, then name ascending, so the order is total and stable.

**Decision.**
- `rankBudgetsBySeverity(budgets)` is applied **before** the `.slice(0, 4)`.
- Because the order is not obvious from the endpoint, it is now stated in `docs/api/budgets.md`
  (§ Response `200 OK`): the order is by name, it says nothing about severity, and a client showing a
  subset must rank on `consumptionStatus` first. The endpoint was **not** re-ordered: other clients
  depend on the stable order, and the guide documents it as stable.
- A second defect was found and fixed in the same file: `BudgetService.mapBackendBudget` compared
  `raw.consumptionStatus === 'WARNING'`, which can never be true (`WARNING` is an `alertStatus`
  member, not a `consumptionStatus` one), and would then write `'DANGER'` into the `consumptionStatus`
  field the server had populated. The server's value is now read first and the derivation is the
  fallback for a response that omits it.

**Verification.** The strip's ordering is verified in the browser against a month containing an
`EXCEEDED` budget that sorts last by name.

---

## D-06 — CSV + AI categorisation: in scope, and advisory only

**The question.** Is an AI category suggestion during CSV import part of the approved scope, and if
so does it do what the requirements say?

**Finding — in scope, implemented, and correctly advisory.** The suggestion is computed at preview
time by `ImportPreviewer.withSuggestion` through the same `CategorySuggester` the manual categorisation
path uses; it is stored in `import_rows.ai_suggested_category_id`; it is surfaced in the preview as a
"Use suggestion" affordance; and it is **never** used by `sp_apply_csv_batch` to choose a row's
category. The procedure resolves the category from the student's own override, then the file's
category name, then the type's default — in that order, with no step that reads
`ai_suggested_category_id`.

**Decision.** BR-13 ("the system only suggests") is honoured, and the relationship is recorded as
advisory: the suggestion influences what the student may choose, and files nothing by itself. The
suggestion is tested for presence and for its non-effect on the committed category, because claiming
it "works" without proving it does not file would be claiming the opposite of what BR-13 requires.

**Scope limit recorded.** `CategorySuggester` is rule-based in this build (learned `category_rules`
plus keyword matching). It is *not* an AI provider call. No response field in the import contract
publishes an AI score or confidence, and none is invented here.

---

## D-07 — OB-018: which write path owns `transactions.description`'s storage form

**The conflict.** `docs/SECURITY.md` §12.2 lists `transactions.description` as **Encrypted** and
`db/01_schema.sql` labels the column the same way, but `sp_apply_csv_batch` inserted the file's text
into it unencrypted. Two rows of one batch could therefore disagree: the row an API write had touched
held an envelope, its neighbour held plaintext. The blocker recorded three candidate answers.

**Which source governs.** `docs/SECURITY.md`, which is the authoritative security position, and §12.3
which places encryption at the **service boundary**. The schema comment on the column says the same
thing.

**Decision — answer 2: the encrypting writer owns the column's storage form.**

- Not answer 1 (the procedure owns it): that leaves the documented "Encrypted" row false for every
  imported record and leaves OB-012's residual exposure open indefinitely.
- Not answer 3 (stop calling it an encrypted column): that reverses a recorded security position to
  accommodate an implementation limitation.
- Answer 2 restores the state the documentation already describes and moves no security position.

**Implementation.** `ImportService#commit` calls a new private `reencryptImportedDescriptions(...)`
after `sp_apply_csv_batch` returns and before anything reads the rows back, inside the transaction the
commit already opened. The statement is one `UPDATE ... SET description = CASE id WHEN … END` guarded
by `user_id`, `source = 'CSV'` and the row ids the procedure just wrote. The plaintext it encrypts
comes from `import_rows.parsed_description`, not from the transaction, so a re-run cannot encrypt an
envelope.

**Why the procedure could not be fixed instead.** It has no access to the key and never will — the key
is deliberately never sent to MySQL. This is the only layer that can do the job.

**Migration and normalization.** Rows committed before the fix keep the form they already have; both
forms read back correctly because every read path uses `decryptStored`. They are normalized through
the product's own write path (any `PATCH /api/v1/transactions/{id}` re-encrypts that row, which
`SECURITY.md` §12.7 already documents as the operator's route). **No SQL was run against the database
to convert rows and no ciphertext is hard-coded anywhere.**

**Recorded side effect, not hidden.** The repair's `UPDATE` fires `trg_transactions_after_update`,
appending one `transaction_history` row per imported transaction recording a `description` change.
`sp_check_budget_alerts`, which the same trigger calls, re-reads only `amount` — untouched — and its
`INSERT IGNORE` was already consumed by the insert trigger, so no second notification is produced.
`transaction_history` is not exposed by any endpoint, so no response changes.

---

## D-08 — Scheduler testability: REL-21/22 are observable, and the earlier BLOCKED verdict was environmental

**The baseline.** REL-21 (recurring rule due → transaction posted) and REL-22 (the scheduler's
idempotence) are marked BLOCKED, recorded as unobservable without waiting for a real midnight.

**What the investigation found.** They are observable, by two routes that are documented and use no
invented production endpoint:

1. **Make a rule already due.** `PATCH /api/v1/recurring-rules/{id}` with a `nextRunDate` in the past
   is explicitly accepted by the contract; the procedure's due predicate is
   `next_run_date <= IFNULL(p_as_of, CURDATE())`, so the rule is due the moment the real timer next
   fires. This is a normal API write, not a test hook.
2. **Move the timer.** `RECURRING_SCHEDULER_CRON` is an existing configuration property
   (`application.yml:110-122`, default `0 5 0 * * *`); setting it to a near-future minute and
   restarting the backend fires the real `@Scheduled` method. This is a deployment configuration
   change, and `docs/testing/manual/MODULE_05_MANUAL_TEST.md` §3.4 documents both routes as supported
   observation methods.

Additionally, `RecurringRuleApiIT` already exercises the same DAO chain
(`recurringProcedureDao.postDueOccurrences(asOf)`) deterministically, including
`aDueRuleIsPosted`, `catchUpPostsEveryMissedPeriod` and `concurrentSchedulerRunsAreIdempotent`.

**Decision.** The relationships are **testable and are tested**, not left BLOCKED. The earlier
verdict is recorded as an environmental limitation of that run (the jar had been deleted and the
default cron is 00:05 Asia/Ho_Chi_Minh), not as a property of the system. **No production endpoint
was invented for QA**, and the scheduler's own trigger was left as it is.

**Honesty constraint.** Evidence from the `@Scheduled` path and evidence from the suite's direct DAO
call are labelled differently in the report; the former is what proves the timer is wired, the latter
is what proves the posting logic. Neither is described as the other.

---

## D-09 — DB procedure drift: no drift exists; the earlier finding is retracted

**The earlier finding.** The M12 implementation report's §7 Finding 3 claimed drift between the
procedures in `db/*.sql` and the live container.

**What the comparison found.** A byte-for-byte comparison of all 26 routines and all 14 triggers —
names, parameter names, modes, types and order, and bodies — between `db/01_schema.sql` +
`db/02_views.sql` + `db/03_procedures.sql` + `db/04_triggers.sql` (and the merged
`db/merged/campuscoin_full.sql`) and the live `campuscoin-mysql` container, read from
`information_schema.ROUTINES` and `information_schema.TRIGGERS`, found **zero differences**.

**Decision.** Finding 3 is **RETRACTED** in the implementation report. There is nothing to reconcile
and no migration to apply. The canonical reload path is recorded for completeness:
`docker compose down -v && docker compose up -d` (destructive — it drops the volume);
`db/03_procedures.sql` and `db/04_triggers.sql` are individually safe to re-run because every object
is `DROP … IF EXISTS` first; `db/01_schema.sql` and `db/merged/campuscoin_full.sql` both begin with
`DROP DATABASE IF EXISTS campuscoin` and are not safe to re-run in isolation. There is no
Flyway/Liquibase; `ddl-auto: validate`.

**Why this is recorded rather than quietly dropped.** A retracted finding is the most important kind
to write down: a reader of the earlier report would otherwise act on a DB migration that does not
exist.

---

## D-10 — UC-08 / REL-32: category suggestion is post-filing, and the guide contradicts itself

**The conflict.** `docs/api/FRONTEND_API_GUIDE.md` §(68) describes categorisation "as the student
types" while the same section requires a `transactionId`, which does not exist until after the record
is created. `SuggestCategoryRequest`'s own javadoc states plainly that create-time suggestion is "a
different operation this build does not offer" and that UC-08 B6 "learns from a filing, so the request
is about a record that exists".

**Which source governs.** The API contract as implemented, because it is internally consistent and the
guide's "as the student types" phrasing is not: a request that requires a `transactionId` cannot be
about a record that does not exist yet. The SRS and use-case documents are absent from this repository
(OB-001), so the authoritative requirement cannot be quoted and the ambiguity cannot be resolved by
reading the requirement itself.

**Decision.**
- The relationship is stated as it is: **suggestion for an existing record → student accepts →
  the record's category changes**, with the assistant's proposal and the student's override both
  recorded (`ai_suggested_category_id`, `ai_overridden`).
- **A create-time suggestion is NOT implemented.** Adding it would be inventing a field pairing the
  contract does not have, and the task forbids inventing request/response fields.
- **The documentation defect is recorded** so that the next reader does not implement the
  "as the student types" reading: `FRONTEND_API_GUIDE.md` line ~1689 should be worded as
  post-filing, matching its own request schema.
- **The blocker is recorded, not worked around**: whether UC-08 requires a create-time suggestion
  cannot be answered from this repository, and it is listed as remaining manual review.

---

## D-11 — Frontend still pins September 2026 in three places

**The finding.** `home-feed.component.ts` hardcodes `'2026-09'` for the budget and transaction reads,
`'September 2026'` for the period label, and `'2026-09-24'` / `'2026-09-23'` as the "Today" and
"Yesterday" strings in `groupTransactionsByDay`.

**Decision — recorded, not changed in this pass.** The dashboard's own `periodMonth` is already read
from the response and used for the label, so the hardcoded label only shows before the call resolves;
but the `from`/`to` window and the day labels are genuinely pinned. Changing them is a behaviour
change to a screen the user is concurrently editing, and the QA pass is required to make surgical,
merge-friendly changes only. It is therefore listed as a residual defect with its exact location, and
it is explicitly **not** described as "the dashboard shows the current month" anywhere in the report.

---

## D-12 — Status vocabulary used in the final report

To keep the final report honest, every item carries exactly one of these, and none of them is used
loosely:

| Status | Means |
|---|---|
| **FIXED** | Behaviour was wrong; it has been changed, and the change is verified. |
| **VERIFIED PASS** | Tested end to end with before/after evidence; no change was needed. |
| **VERIFIED FAIL** | Tested and still wrong. |
| **BLOCKED BY ENVIRONMENT** | Cannot be observed here; what evidence is missing is stated. |
| **OUT OF SCOPE** | Deliberately not offered, with the gate named. |
| **REMAINING MANUAL REVIEW** | Needs a human or a resource automated QA cannot supply. |

The pass count in the final report is **derived from the final matrix** by counting rows, not carried
over from the previous report. The previous report's executive summary ("33 PASS", "33/37 testable =
89.2%") disagrees with its own matrix, which contains 35 PASS rows; that discrepancy is recorded as a
defect of the earlier report and is not propagated.

---

# Decisions added in the final pass (D-13 … D-20)

The entries above were written before or during the investigation. The entries below record what the
final pass **established by measurement**, and they supersede any earlier tentative wording.

---

## D-13 — D-08 is upgraded: the scheduler was observed on the real timer, not just argued to be observable

**What D-08 established.** REL-21/22 are observable by two documented routes and no endpoint needs to be
invented. That was an argument about testability.

**What the final pass did.** It executed the argument. Rule 1 was made due through the documented
`PATCH /api/v1/recurring-rules/1 {"nextRunDate":"2026-09-01"}` (200), the backend was restarted with the
existing `RECURRING_SCHEDULER_CRON` property set to a near-future time, and **the real `@Scheduled`
timer was watched firing**: `2026-09-27T03:16:00.044+07:00` on thread `scheduling-1`, logging
`CALL sp_post_recurring_transactions(?)` and "Recurring scheduler run completed".

**Exact before/after.** Occurrence 3 (rule 1, `period_key` 2026-09) went POSTED and produced transaction
**121**; rule 1's `next_run_date` moved `2026-09-01 → 2026-10-01` with `last_run_date` set; rules 2–4
were untouched; Alex's transaction count went 71 → 72 and the RECURRING count 1 → 2. Dependent reads:
`GET /transactions/121` returned Allowance/INCOME/200.00/`source:"RECURRING"`, and
dashboard/report/forecast all agreed at 692.78 / 1286.89 / −594.11 with income up by exactly the 200.00.

**Idempotence (REL-22), on the timer.** With an every-minute cron and the already-posted period
re-offered, two further real ticks fired (03:18:00.049, 03:19:00.012) and occurrence count, transaction
count, RECURRING count and `transaction_history` were all **unchanged** — zero duplicates — with the
rule advancing back to 2026-10-01.

**A fallback plan is retracted.** An earlier draft proposed proving REL-22 by manually re-PATCHing the
rule. That plan was **not used**: the stronger, real-timer version above replaced it. The backend was
returned to its default cron afterwards.

**Status.** REL-21 = **VERIFIED PASS**; REL-22 = **VERIFIED PASS**. What remains genuinely human is a
firing of the **default** schedule without a cron override — MANUAL-RECURRING-01. That distinction is
stated in both the report and the manual checklist and is not blurred.

---

## D-14 — The reset token's single-use was verified automatically; the earlier "unobservable" claim was wrong

**The earlier claim.** An earlier draft of the causal report listed live reset-token reuse as
**REMAINING MANUAL REVIEW**, on the grounds that the raw token exists only inside the delivered email and
therefore cannot be observed from the API.

**Why that was wrong.** The `dev` profile installs `FilePasswordResetNotifier`
(`application-dev.yml:39` — `RESET_SINK_ENABLED: true`), which writes the same reset link it would have
mailed to `backend/target/password-reset-dev.log`. The token is therefore readable through **the
application's own development sink** — no SQL, no mock, no fabricated state.

**Which account.** The test deliberately used a **freshly registered throwaway account**
(`qa.tokenreuse.<epoch>@student.campuscoin.edu`), not a demo account, so no demo credential was put
through a password change. Its password is left at the Section H result, which is **not** the seed
value — it is a QA account, it is listed as such in the residual-data table, and its value is deliberately
not recorded here. The three demo accounts were re-verified at the end of the pass and all still
authenticate with the passwords documented in `docs/CREDENTIALS.md`, which remains accurate and is the
only place those values are written down.

**What was measured**, on that fresh account:

| Step | Result |
|---|---|
| Request reset | `200` generic message; token appears in the dev sink |
| `verify` with the token | `200 {"valid":true}` |
| `verify` again, still unused | `200 {"valid":true}` |
| `complete` with the token | `200` "Your password has been reset." |
| **Replay `complete` with the same token** | **`400 INVALID_RESET_TOKEN`** |
| **Replay `verify` with the same token** | **`400 INVALID_RESET_TOKEN`** |
| Sign in with the new password | `200` |
| Sign in with the pre-reset password | `401` |
| Sign in with the password the **replayed** call tried to set | `401` — the replay changed nothing |

**Decision.** The control is **VERIFIED PASS** and is moved out of manual review. It is restated as a
propagated consequence: because the replayed call was refused, the credential it attempted to set was
never applied — proved by a login attempt, not inferred from the status code.

**A product question, recorded without changing anything.** Verification (`verify`) does not consume the
token; completion does. So a token is usable more than once for a **read-only** validity check. That is
defensible — a page may re-verify on reload — and the token still expires on `expires_at`. It is recorded
as a design observation, **not** as a defect, and no behaviour was changed.

**What remains manual is delivery only** (MANUAL-EMAIL-01): that a real SMTP server accepts and delivers
the message. The control is verified; the transport is not. Neither is inferred from the other.

---

## D-15 — M12 report Finding 3 is retracted: there is no procedure drift, so there is nothing to migrate

**The finding.** `CAMPUS_COIN_M12_FRONTEND_IMPLEMENTATION_REPORT.md` §7 Finding 3 asserted that the live
database still carried pre-M12 routine signatures without `p_actor_id` — citing
`sp_soft_delete_transaction(IN p_txn_id BIGINT UNSIGNED)` as taking a single parameter — and urged that
nobody read `db/03_procedures.sql` as a description of the deployed schema.

**Measurement.** Every routine's parameters were read from `information_schema.PARAMETERS` (name,
ordinal position, type) and every body compared with the repository files and the merged file.

- `sp_soft_delete_transaction` live takes **two** parameters (`p_txn_id`, `p_user_id`) — identical to
  `db/03_procedures.sql:...`. **The specific example the finding cites is false.**
- `p_actor_id` **is present** on `sp_admin_create_announcement`, `sp_admin_set_announcement_active`,
  `sp_admin_upsert_default_category`, `sp_admin_upsert_tip_template`, `sp_admin_set_threshold` and
  `sp_admin_send_password_reset`.
- `sp_flag_transaction` matches the repository exactly.
- No body differs, other than `fn_render_template`'s header, which is MySQL's own canonical rendering of
  the same declaration.

**Decision.** Finding 3 is **RETRACTED** in place — kept visible with the correction attached, so anyone
who read the original sees the correction rather than a silently edited document. `db/03_procedures.sql`
and `db/merged/campuscoin_full.sql` describe the deployed schema **accurately**. **There is nothing to
reconcile and no migration to apply**, and no operator action is outstanding. This supersedes D-09's
narrower retraction of the same class of claim.

**Consequence for the deliverables.** The implementation/change summary contains no migration section and
no schema change, because there was none to make.

---

## D-16 — REL-32's post-filing contract is confirmed, including that the suggestion never writes

**Decision, confirmed end to end.** A category suggestion is **advisory**; it is asserted by a test rather
than assumed from the guide's wording:

- **T1 — a novel description.** The suggestion returned `source: NONE` with
  `learned: {ACCEPTED, Food}`, and **T1 itself was unchanged afterwards** — `source` still `NONE`, the
  category unmodified. BR-13 holds: the system proposes, it does not act.
- **T2 — the same description again.** `source: RULE`, `categoryId: 6`, `confidence: 1.0` — a
  rule-engine match, **not** AI, and the response is reported as such.
- **Negatives.** Empty description → `NONE` with no `learned` entry; id `999999` → `404`; `{}` → `400`;
  no token → `401`.

**Acceptance is an ordinary write.** Accepting a proposal is a normal transaction update, which is why
REL-32 is testable without any special endpoint: the suggestion is one call and the acceptance is the
documented `PATCH`.

---

## D-17 — Three observed scope/listing behaviours are correct as-is and are not defects

Recorded so they are not "fixed" later by someone reading them as bugs.

1. **Retired categories are still listed, with `isActive:false`, for the client to filter.** The guide
   (§ line 1272 region) documents this: the list is the caller's inventory, including retired entries,
   and the client decides what to render. The observed FLOW 6 behaviour — a retired default category
   still appearing in the student's list — matches the documented contract and is **correct**. It is
   distinct from a retired category being **refused on a new transaction**, which is also correct and is
   verified separately.
2. **Default categories have no delete route.** Only retire. Verified by acting:
   `DELETE /api/v1/admin/categories/15` → `400`; `DELETE /api/v1/categories/15` → `403` for an admin and
   `404` for a student. The QA-created default category 15 ("QA Flow6 20506") is therefore left
   **retired-only**, which is the intended lifecycle for a default, not a stuck row.
3. **Announcement rows have no delete route** — only `PATCH isActive` (relied on in D-19 as the cleanup
   mechanism).

**Decision.** None of these is a defect and none was changed. Each is recorded because a QA footprint
that cannot be deleted is indistinguishable, to a later reader, from a QA footprint that was hidden.

---

## D-18 — Import duplicate detection is deliberately conservative; the trade-off is documented, not "fixed"

**Observation.** Re-uploading a file whose rows had already been imported flagged 2 of 4 rows as
`DUPLICATE` and committed the other 2, producing two genuine duplicates. A row whose category could not
be resolved is never flagged.

**Why.** `ImportDuplicateDetector`'s javadoc states the rule directly: a row whose category cannot be
resolved to an id "is never a duplicate, because there is nothing to compare it against", and the
detector "errs toward not flagging".

**Decision.** This is a **deliberate trade-off in favour of never discarding a student's data**, not a
defect. Changing the threshold would silently drop rows a student expected to import. It is documented as
an observed limitation in the report's business-rules section and as **REMAINING MANUAL REVIEW** for a
product decision. No detector behaviour was changed and no test assertion was weakened.

---

## D-19 — Announcement and import QA footprints: withdrawn, labelled, and left in place

**The problem.** The announcement API has no DELETE — only `PATCH isActive`. Crashed browser runs during
the REL-35 investigation left active QA announcements (ids 31–36) that would have been **visible to real
students**, which is not an acceptable side effect of a test.

**What was done.** Each leaked announcement was **withdrawn through the product's own route**,
`PATCH /api/v1/admin/announcements/{id} {"isActive": false}` → `200` for every one. A re-run after the
cleanup produced exactly 3 banners and 11/11 PASS, confirming the leak was the cause of the earlier
inflated count rather than a rendering bug. (An earlier assertion failure — expected 3 banners, saw
more — was my own test counting leaked rows, and is recorded as a test bug, not a product bug.)

**Decision.** The rows are kept, withdrawn, and **documented** in the residual-data table. The API offers
no way to remove them, and hiding the footprint would be worse than labelling it. A demo shows nothing:
withdrawn notices render nothing and deleted transactions do not appear. The one visible consequence —
a few demo figures (e.g. Alex's September expense of 1286.89) higher than a pristine seed would produce —
is stated rather than concealed.

**The destructive alternative is the user's call.** `docker compose down -v` plus a SQL reload would give
a pristine dataset, but it **drops the volume and every row** and requires explicit approval. Two traps
are recorded: `db/03_procedures.sql` and `db/04_triggers.sql` are individually safe to re-run (each object
is `DROP … IF EXISTS` first), while `db/01_schema.sql` and `db/merged/campuscoin_full.sql` both **begin
with `DROP DATABASE IF EXISTS campuscoin`** and are **not** safe to re-run in isolation.

---

## D-20 — Operational findings that are not product defects, but will look like them

Recorded because each one presents as a product failure and is not.

1. **The `:4200` dev server was serving stale code.** The running `ng serve` process's Angular build
   cache predated the REL-35 fix: the `home-feed` module it served contained **zero** references to the
   announcement banner, so the fixed UI could not appear on it. The source is correct and the production
   build contains the banner. Anyone who sees no banners on `:4200` should **restart the dev server**
   before reporting a regression.
2. **The frontend must be served on `:4200`.** Backend CORS allowlists only `http://localhost:4200`;
   serving on any other port makes login fail with **403** while the API is healthy — the Angular dev
   proxy forwards the browser's real `Origin`. This cost real time during the pass (25-second
   `waitForURL` timeouts that looked like selector bugs) and is not a defect.
3. **`recurring_rules.description` is plaintext in the seed data** while the column is documented
   Encrypted, and the scheduler copies it verbatim, so a scheduler-posted transaction also carries a
   plaintext description. Same class as OB-018 on the recurring path. **Deliberately not fixed:** a
   `PATCH` would fire the update trigger and the budget-alert check, risking spurious student
   notifications to re-encrypt a cosmetic string. `SECURITY.md` §12.7 documents the transitional state
   and the remedy.

**Decision.** All three are recorded as operational/residual findings in the report and the change
summary, not as product defects. None is described as a PASS or a FAIL of any relationship, and none is
used to explain away an observed failure.

---

## D-21 — Provider reconnection: SMTP verified, AI root-caused, and a `.gitignore` gap closed

The user supplied real provider credentials (`MAIL_*` for a Gmail app password, `GEMINI_API_KEY`) and
asked for them to be connected and the affected features re-tested. Three things came out of it: one
verified working integration, one real product defect found and fixed, and one real leak risk closed.

### 1. SMTP — connected and verified (no stub, no sink)

`RESET_SINK_ENABLED=false` plus `MAIL_HOST=smtp.gmail.com` makes `PasswordResetConfig` install
`SmtpPasswordResetNotifier` in place of `FilePasswordResetNotifier`. Verified end to end:

| Step | Observation |
|---|---|
| `POST /auth/password-reset/request` | `200` |
| Startup/log | `SmtpPasswordResetNotifier : Password reset link sent through SMTP` |
| `backend/target/password-reset-dev.log` | Not created at all — so the sink path is genuinely off, not merely bypassed |

This **closes the automated half of MANUAL-EMAIL-01**. The remaining half — that the message actually
arrives in a mailbox and is readable — is irreducible and stays a human test (Section 19 / the handoff).
The API returning `200` is *not* evidence of delivery on its own: with neither SMTP nor the sink
configured the app installs `NoopPasswordResetNotifier` and still answers `200` by design (UC-03 B3).
The distinguishing evidence is the notifier name in the log, which is why it is recorded here.

### 2. AI — a real defect in the token cap, found by measurement

**The symptom.** AI output was silently falling back to `RULE_BASED` on an intermittent basis.

**Root cause (two independent causes, both proved).**

1. **`max-tokens` too small for Gemini 3.x.** Gemini 3.x bills *thought* tokens against
   `maxOutputTokens` (`usageMetadata.thoughtsTokenCount` counts toward the cap). Measured: a
   **one-word** reply ("OK") consumed **84 thought tokens**. At a cap of `1024` the budget is
   occasionally consumed by thinking alone, truncating the reply mid-JSON. Reproduced with a direct
   SDK probe: `finishReason=MAX_TOKENS` with a truncated body
   (`{"summary": "In September 2026, your total income was 692.78 VND and your total…`). `parse()`
   then returns null and the adapter logs *"Monthly narrative unusable (malformed reply)"* — the same
   message a genuinely malformed answer produces, so the two are indistinguishable from the log.
   At `8192` the same request finished `STOP`. **This is why the AI looked intermittent rather than
   broken**, and it is the reason it was easy to misdiagnose as a provider or quota problem.
2. **Free-tier quota.** The key is on the free tier: `generate_content_free_tier_requests`,
   `limit: 20`, per model per day. Proved external to the application by calling the REST endpoint
   directly with no Java involved → `429 RESOURCE_EXHAUSTED`. Also observed concurrently:
   `503 UNAVAILABLE` ("currently experiencing high demand") after a 21-second wait.

**The fix (code, not just environment).** `AiProperties.DEFAULT_MAX_TOKENS` was raised `1024 → 8192`,
and — the part that actually takes effect — the `application.yml` placeholder
`max-tokens: ${AI_MAX_TOKENS:1024}` was raised to `8192`. **The YAML placeholder is what governs:** it
resolves a number even when `AI_MAX_TOKENS` is unset, so editing the Java constant alone has no
effect. This was caught by inspecting what the application actually put on the wire to a local stub
(`maxOutputTokens: 1024`), not by reading the code. Both values are now aligned, and `.env.example`
carries the same guidance so the trap is not reintroduced.

**Verified after the fix**, through the product's own API:

| Path | Before | After |
|---|---|---|
| `POST /api/v1/insights/generate` (UC-17) | `generatedBy=RULE_BASED` | `generatedBy=AI`, `model=gemini-3.5-flash` |
| `POST /api/v1/ai/suggest-category` (UC-08) | `source=NONE` | `source=AI`, `confidence=0.91` |

**How the AI path was verified without spending the real quota.** The adapter honours `AI_BASE_URL`
for exactly this purpose (it exists so a test can point at a stub). With the real key present but a
local stub at that address, the application's own wiring could be observed end to end — request built,
provider called, reply parsed, `generatedBy=AI` stored — and the outbound request inspected. **This is
explicitly not a substitute for a real provider call, and no claim about Gemini's answer quality rests
on it.** It verifies *the application*, which is what was in question. The residual that matters: the
insight prose now stored for Alex's 2026-09 is stub text (see the residual table in the causal report),
and no real-provider narrative has yet been captured — that remains a human step once quota allows.

**Graceful-degradation behaviour is correct and worth recording:** with the real provider selected and
its quota exhausted, `POST /insights/generate` still answers `200` with a complete rule-based
insight. The student never sees a provider error.

### 3. `.gitignore` — a real leak risk, closed

A hand-made backup of `.env.local` was found **not ignored** (`.env.local.bak` matched neither `.env`
nor `.env.local` nor `.env.*.local`), and `.env.example` sat next to it as the one env file that *must*
stay tracked. The rule is now `.env*` with `!.env.example`, which covers every backup spelling
(`.env.local.bak`, `.env.bak`, `.env.old`, `.env.dev.local`) and is verified by
`git check-ignore -v`. `.playwright-mcp/` and `.claude/settings.local.json` were also added.

**A scan for the supplied secrets across all tracked files and all non-env files on disk found no
leakage** before or after the change. The secrets live only in `.env` / `.env.local`. This was checked
with literal-value `git grep`, not by pattern.

**Decision.** SMTP is recorded as **VERIFIED PASS**. The AI token cap is recorded as a **product defect
— FIXED**, with the root cause and the measurement that proves it. The stub-backed AI verification is
recorded as verification **of the application's wiring**, explicitly not of the provider's answers.
The `.env.local` provider description was corrected in place to say Gemini rather than Anthropic, and
`AI_MODEL` was left commented out: setting a Claude model name would send it to the Gemini SDK and
fail silently, which is worse than the comment being absent.

**Operational note.** The Gmail app password and the Gemini key were pasted into a chat transcript to
supply them. Both should be rotated once testing is finished, and the app password revoked.

---

## D-22 — Resolving the stub-written insight (the only student-visible QA residue)

**Decision context.** D-21 §2 recorded that verifying the AI wiring against a local stub left
`insights` row 3 holding `STUB narrative: …` marked `generated_by='AI'`, and that `GET /insights`
served that text to Alex. That was the one piece of the pass's footprint a student could actually see.
The user directed that it be fixed before the demo.

**The problem had no API solution.** `InsightController` exposes only `GET`, `GET /months` and
`POST /generate` — nothing that deletes or edits an insight — and regenerating alone cannot work
either, because `sp_generate_monthly_insight`'s `ON DUPLICATE KEY UPDATE` guard
(`summary_text = IF(insights.generated_by = 'AI', insights.summary_text, v_summary)`) preserves text
on any row already marked `AI`. So *some* database statement was unavoidable, and the question became
which one.

**Three options were put to the user; the least-hand-written one was chosen.**

| Option | Why it was or was not chosen |
|---|---|
| **Delete row 3, then rebuild through `POST /insights/generate`** | **Chosen.** Only the removal of our own residue is SQL; every visible value is produced by the application. Works immediately, needs no provider quota |
| Wait for provider quota and let a real AI narrative overwrite the stub | Rejected: `writeAiNarrative` has no `generated_by` guard so it *would* overwrite, but it needs available quota, and if quota stayed exhausted the broken text would remain on screen |
| `UPDATE` the prose directly to rule-based text | Rejected: it would mean hand-writing the content the student reads — demonstrating the application rather than testing it, the failure mode §0 and §18 exist to prevent |

**Executed and verified.**

| Step | Evidence |
|---|---|
| BEFORE | `GET /insights?month=2026-09` → `200`, `generatedBy=AI`, summary begins `STUB narrative:` |
| Pre-check | `bookmarks.insight_id` is the only FK to `insights` (`CASCADE`); no bookmark referenced row 3, so the delete was safe |
| 1 — delete | `DELETE FROM insights WHERE id=3` → `ROW_COUNT()=1`; table 9 → 8 rows; `SELECT COUNT(*) … WHERE summary_text LIKE 'STUB%'` → `0` |
| 2 — rebuild | `POST /insights/generate?month=2026-09` → `200`; the procedure inserted row 28 |
| AFTER | `GET /insights?month=2026-09` → `200`, `generatedBy=RULE_BASED`, `model=null` |
| BR-13 cross-check | Insight figures vs `GET /reports` totals: income `692.78`, expense `1286.89`, net `-594.11` — **match**; top category `Food (655.64)` in both; `flagged_categories` retains all 5 entries |
| Blast radius | User 2's other months (May, July, August) unchanged; database-wide `STUB` count `0`; insights with a non-null `model_name` `0` |

**Decision.** Recorded as **RESOLVED — verified**. The row id changed from 3 to 28, which is expected:
the procedure inserts a new row and `uk_insight_user_month` keys on `(user_id, period_month)`, not on
the id. The user's September insight is now ordinary rule-based output, and the historical QA record in
D-21 is left intact rather than rewritten — the stub incident is part of what this pass found.

**Note for a future pass.** Two separate guards protect a stored narrative, and they are not the same
guard: the *procedure* preserves text when `generated_by='AI'`, while `InsightWriteDao.writeAiNarrative`
unconditionally overwrites. A stub can therefore be cleared by a successful provider call but never by
re-running the rule-based generator — which is exactly why the delete was necessary here.

---

## D-23 — The frontend unit-test project stopped compiling; recorded, not repaired

**What was observed.** Re-running `npx ng test --watch=false` during this segment fails to build:

```
✘ [ERROR] TS2339: Property 'fillDemoStudent' does not exist on type 'LoginComponent'.
    src/app/features/auth/login/login.component.spec.ts:54:14
✘ [ERROR] TS2339: Property 'fillDemoAdmin' does not exist on type 'LoginComponent'.
    src/app/features/auth/login/login.component.spec.ts:60:14
```

This contradicts the "13 files, 62 tests passed" figure recorded in §K of the causal report — a figure
that was true when it was taken and is **not** true now.

**The evidence says it is not this pass's doing.**

| Check | Result |
|---|---|
| `git status --porcelain -- frontend/.../login/` | Only `login.component.ts` is `M`. **`login.component.spec.ts` is unmodified** |
| `git diff --stat HEAD -- frontend/.../login/` | `login.component.ts`: `18 insertions(+), 48 deletions(-)` |
| `git show HEAD:.../login.component.ts \| grep -c fillDemoStudent\|fillDemoAdmin` | `4` — `HEAD` **does** define both |
| `git show HEAD:.../login.component.spec.ts \| grep -c …` | `2` — the spec calls both, and matches `HEAD` exactly |
| mtimes | component `2026-09-27 04:58`, spec `2026-09-26 16:38` — the component moved later |

The working tree moved out from under an unchanged spec. No file this pass touched is involved.

**Why it was not repaired.** Both files are part of the concurrent frontend UI work that §0 puts out of
bounds (`do not reformat unrelated frontend files`; `prefer a small child component … when
architecturally reasonable`). The repair is either to restore the two helpers or to delete the two test
cases, and the correct one depends on where that UI work is heading — which its author knows and this
pass does not. Guessing would mean editing a file mid-edit and could produce exactly the collision §0
exists to avoid.

**Scope of the damage, precisely.** The **application** is unaffected: `npx tsc --noEmit -p
tsconfig.app.json` → `EXIT=0` and `npx ng build` → `EXIT=0`. Only the test project (`tsconfig.spec.json`)
fails to compile. The backend suite (1091 tests at that point) is unaffected and green.

**Consequence for the deliverables.** Every quoted frontend test count in the causal report (§K, §M, and
the final status) and in `IMPLEMENTATION_CHANGE_SUMMARY.md` §5 / `MANUAL_USER_TEST_CHECKLIST.md` has been
annotated as a point-in-time figure, with the current state stated alongside it. The figure was **not**
re-quoted as if still true, and the item was added to the checklist's already-automated table so it is
visible before a demo.

**Resolved as far as this pass can take it.** The chatbot rebuild (§M-bis) needed a real frontend result
and could not get one while this error stood, so that one spec was **set aside for the run and restored
byte-identical** (SHA-256 verified before and after) — the suite then ran green at **12 files, 60 tests**,
which is the first observation of the 11 new chat-service tests passing. The workaround is recorded in
full: it changed nothing on disk in the end, the error is still present, and this item is still open. What
was *not* done is the tempting shortcut — deleting the two test cases or restoring the two helpers, either
of which would have "fixed" a file its author is mid-edit, which §0 forbids.

---

## D-24 — Credentials removed from the QA deliverables

**What was found.** A literal-value scan (the only kind that cannot be fooled by a variable being
renamed — §D-21) over `docs/testing/qa/` turned up real demo passwords written in plain text: the student
and admin seed values, plus the QA test account's post-reset value. Nine occurrences across three of the
four deliverables.

**Why it matters here specifically.** These values are already in `docs/CREDENTIALS.md`, which is tracked
in git — so this is not a new disclosure, and it is not a rotation incident. What changed is **who the
file travels to**: the deliverables are zipped and handed around, and §0 of this pass forbids writing
passwords into the QA report. A QA report is read on a demo laptop, projected, and mailed; the credential
sheet is not.

**Decision.** Every literal value was replaced with a reference to `docs/CREDENTIALS.md` as the single
source of truth, keeping the *meaning* of each sentence intact:

| File | Before | After |
|---|---|---|
| `CAUSAL_RELATIONSHIP_QA_REPORT.md` §I | "returned to `Student@123` … `Student@123` → 200, `Student@456` → 401" | "returned to the seed value … the documented password → `200`, a wrong password → `401`" |
| `CAUSAL_RELATIONSHIP_QA_REPORT.md` §J | the QA account's password spelled out | "set to a non-seed value by that test and is not recorded in this report" |
| `DECISION_LOG.md` (D-19 area) | both demo passwords spelled out | "the passwords documented in `docs/CREDENTIALS.md`" |
| `IMPLEMENTATION_CHANGE_SUMMARY.md` §7 | both data points spelled out | same treatment |

**Verification.** Re-scanned all four files for every literal secret in the environment — Gemini key,
mail password, JWT secret, encryption key, MySQL passwords, both test-account passwords, and the four
demo/QA password values. **Zero occurrences remain.** The evidence is unchanged: `200` vs `401` is the
causal proof, and which string produced it is not part of the proof.

**Not changed.** `docs/CREDENTIALS.md` itself still holds them — that is its job, it is tracked, and it
was already true before this pass.

---

# Decisions added by the chatbot rebuild (D-25 … D-32)

These entries cover the fifteen-section rebuild of the chatbot as a genuine conversational AI assistant.
They are recorded here rather than in the feature's own documentation because each is a **decision with a
rejected alternative**, and the alternative is the one a later reader is most likely to reach for.

---

## D-25 — The provider is reached through an overridable endpoint, and that is what makes the feature testable

**The problem.** Section 12 K/L/M of the brief require testing provider failure, a long conversation and
numerical grounding. The provider's free tier allows **20 requests/model/day**, so a suite that called
the real endpoint could not be run twice, and could not be run at all in CI. The available alternatives
were both bad: mock `ChatCompletionPort` (which stubs the very boundary the tests exist to check, so it
proves the service's turn-taking and nothing about the wire), or don't test it.

**Decision.** `AiProperties.baseUrl` already existed and its javadoc already said *"Overridable so a test
can point at a stub server"*, and the suggestion adapter has honoured it since module 12. The chat
adapter honours it the same way. `ChatApiIT` therefore points the application at a **real HTTP server
that speaks the provider's protocol** and runs the entire production path — controller, security chain,
`ChatService`, `ChatToolExecutor`, nine domain services, real MySQL 8 via Testcontainers — with only
Google's endpoint replaced. **No class of this application is mocked, subclassed or spied.**

**Why this is not "testing a mock".** The distinction is what the stub replaces. A stubbed
`ChatCompletionPort` replaces *our* code and proves our code's logic in isolation. This replaces *Google's
server*, so the thing under test includes the SDK's request construction, our adapter's loop, the tool
callback, the response parse and the error taxonomy — every part of the chain the brief asks about.

**What it still does not prove.** Nothing about Gemini's own answers. That is stated in the report and is
the reason MANUAL-AI-CHAT-01 exists.

---

## D-26 — A failed conversation is an exception, not an `Optional`

**The decision, against the house pattern.** Every method on `AiSuggestionPort` returns `Optional`, and
that is right: UC-08 and UC-17 have a deterministic answer to fall back to, so an outage there is
invisible and harmless. `ChatCompletionPort.converse` deliberately **throws**
`AiProviderUnavailableException`.

**Why the reversal.** A conversation has **no** deterministic fallback. If the port returned empty, the
only thing the caller could do is compose a reply itself — and application-written text shown in the
assistant's voice is precisely what section 15 forbids. Returning `Optional.empty()` would therefore not
be neutral; it would be an invitation to fabricate. Throwing makes the honest outcome the *only*
available one: section 12's case K (a spent quota produces a graceful error and **no** fake answer) falls
out of the type rather than out of the caller remembering.

**Cross-check.** The Vitest suite asserts the same property from the other side: the failure text is
required to contain **no digit**, so no route exists by which an invented figure reaches the panel.

---

## D-27 — The tool layer is nine declared reads, and the declarations carry no data

**The decision.** Section 4 forbids dumping the database into the prompt. The assistant is given nine
declared tools — `getFinancialSummary`, `getMonthlySummary`, `getCategorySpending`, `getBudgetStatus`,
`getTransactions`, `getSavingTips`, `getForecast`, `getAnomalies`, `getRecentActivity` — each delegating
to the service whose screen already shows that figure, so no business logic is duplicated and a figure
the assistant quotes is the figure the screen shows.

**The security property is the callback's signature, not a validation.** `ChatCompletionPort.ToolInvoker`
is `(String, Map) -> Map`. It has **no identity parameter**, so the provider cannot ask for "student 4's
balance" — only for "a financial summary" — and `ChatService` decides whose, by binding the authenticated
principal into the closure. `ChatServiceTest` asserts the signature reflectively, because that is the
guarantee; an assertion that the serialised request "doesn't contain the id" would pass or fail on
whether a lambda's identity hash happened to contain those digits, which is not a test.

**Everything is a read.** No tool starts an anomaly scan, generates a tip or insight, or marks a
notification read. The brief says the default must be read-only and forbids implementing destructive
writes to make the assistant look impressive; the tool set is the enforcement.

---

## D-28 — The frontend sends the whole conversation, and there is no session

**The decision.** Section 5 requires "that", "it" and "last month" to resolve — and explicitly forbids
resolving them by regex. The transcript is therefore client-supplied and replayed on every call, and the
provider resolves references by reading its own conversation. The system instruction says so explicitly
and instructs the model to **ask** when a reference genuinely cannot be resolved rather than pick a
candidate.

**Why no server-side history.** Three reasons, all structural. There is no session row to leak or to
attribute to the wrong student; the server holds no state that could outlive the request; and a
conversation cannot bleed between users because there is no conversation object to share. The cost is
that the client resends the transcript, which is bounded (`MAX_HISTORY_TURNS = 30`, trimmed from the
front).

**What this rules out, deliberately.** A regex that maps "last month" to a date would be the obvious
implementation and is exactly what the brief forbids. The application does not know what "why?" refers
to — that resolution happens inside the model.

---

## D-29 — Correcting a false claim in five places: a `userId` in the body is ignored, not rejected

**What was claimed.** `docs/api/API_INVENTORY.md`, `docs/api/FRONTEND_API_GUIDE.md`,
`docs/api/chat-assistant.md` (twice, including its status-code table), `ChatController`'s javadoc and
`ChatRequest`'s javadoc all stated that a body containing `{"userId": 9}` is **rejected by the object
mapper as malformed**.

**Why it is false.** `application.yml` configures only `fail-on-numbers-for-enums: true`; there is no
custom `ObjectMapper` anywhere. Jackson's default for an unknown property is therefore to **ignore** it.
The repository's own precedent agrees and says so correctly at `CategoryApiIT` — *"Jackson ignores unknown
properties … asserting the outcome guards against both"* — so this was a claim contradicting a rule the
codebase had already established elsewhere.

**Corrected in all five locations**, each now stating what actually happens. The replacement is not
weaker, because the guarantee was never the `400`: it is that **the id is inert and the read follows the
bearer token**, which `ChatApiIT.aUserIdInTheBodyDoesNotRedirectTheRead` asserts on the *outcome* — it
sends another student's id and checks the caller is still read their own month. That assertion holds
whether the field is ignored or rejected, and it is the one that would fail if a later refactor started
honouring such a field.

**Why this is recorded rather than quietly edited.** The four documents were written *this pass* and the
claim came from the same reasoning that produced the test — so this is a case of the pass catching its
own error by checking a claim against configuration instead of against intent. The lesson is in the
method: "rejected as malformed" *sounds* like a stronger guarantee than "ignored", and it was written
because it sounded stronger.

---

## D-30 — A stub that emitted malformed JSON, and why the tests assert on the request

**What happened.** The first version of `StubGeminiProvider` built its tool-call response as
`"parts":[[{...}]]` — one array too deep, from passing an `ArrayNode` to `ArrayNode.add`. The SDK
rejected the body, the adapter correctly converted that to `AiProviderUnavailableException`, and **every
grounded-reply test failed as though the application had not run its tool**.

**How it was diagnosed.** Not by guessing. The failing assertion dumped the second request the stub had
received, which showed the application had sent `contents` with only the original question — no
`functionResponse`. A throwaway probe (written, run, deleted) drove the real `GeminiChatCompletionPort`
against the stub and printed the cause chain:
`GenAiIOException: Failed to deserialize the JSON node` ←
`MismatchedInputException: Cannot deserialize value of type Part$Builder from Array value`.
That is the provider's parser rejecting a nested array, and it located the fault in the stub rather than
in the application.

**The decision it produced.** The stub now has a separate `okParts(ArrayNode)` path with a comment naming
the trap, and — more importantly — the tests assert on **what the application sent**, not only on what the
student received. A malformed provider body and a broken tool loop are indistinguishable from the
response alone; they are only distinguishable by reading the second round's `contents`. Every grounded
test therefore asserts the `functionResponse` the application assembled, the tool's name, and the value
it carried.

**A second lesson, recorded against the tests themselves.** Three assertions initially compared a
serialised `BigDecimal` to formatted text (`"31.0"`, `"46.50"`) and failed on `31` vs `31.0` and `46.5`
vs `46.50`. The figures were correct; the assertions were wrong, because whether Jackson writes `46.50`
or `46.5` is a serialisation detail and not the claim. They now compare numerically
(`isEqualByComparingTo`), which states the actual claim — *this is the student's own 46.50* — exactly.

---

## D-31 — A failed turn is a distinct type on the client, not a reply with error text

**What happened.** The frontend's first failure path returned the error sentence through the
`ChatResponse` shape. `assistantMessage` then saw non-blank `reply` text and produced an ordinary
assistant bubble: **a `503` rendered as though the model had said it, with no retry offered.** Two
Vitest assertions caught it — the failed turn was expected to carry `failed: true`, and it was expected
to be excluded from the transcript replayed to the provider, and it was neither.

**Why it is a decision rather than a bug fix.** The backend already refuses to compose an answer when the
provider is unavailable (D-26), and the brief's section 15 forbids presenting application-written text as
a model reply in either direction. Funnelling the error through the reply shape reopened that door on the
client: the *text* was honest, but its *type* said "the assistant said this". The fix is structural — the
failure path now returns a `FailedTurn` discriminant (`{ failed: true, text, question }`), so
`'failed' in outcome` is the single branch that decides which of the two bubbles the panel gets. The
discrimination is on the type, so no future edit can accidentally route an error sentence into the reply
path without the compiler noticing.

**What it also bought.** A blank `reply` from a `200` is now treated as a failure with a retry rather
than rendered as an empty bubble — a second path the union type made expressible. And because a failed
turn is filtered out of `history()`, a line of error text can no longer be replayed into the model's
context as something the assistant previously said.

**How it was observed to pass.** This was the first time the frontend suite had ever been run green for
this feature: **12 files, 60 tests, 0 failures**. The run required setting aside the concurrently-edited
`login.component.spec.ts`, which does not compile against the working-tree `login.component.ts`; it was
restored byte-identical (SHA-256 verified) and is recorded as MANUAL-BUILD-01.

---

## D-32 — The backend total is read from Maven's summary, not from the report directory

**What happened.** The full backend suite printed `Tests run: 1131, Failures: 0, Errors: 0, Skipped: 0`.
Summing the same counts out of `backend/target/surefire-reports/*.txt` gave **1133**. Both were "read off
the disk" in the sense of not being guessed, so the discrepancy had to be resolved rather than one figure
picked because it looked nicer.

**Which was right, and why.** Maven's summary is computed from that run's actual executions. The directory
sum counts *whatever files are present*, and two of them — `ZProbeSdkTest`, `ZProbePathTest`, one test each
— belonged to throwaway probes from earlier in this session that had been deleted from `src/test/java`.
Their reports survived. **A report directory is a persisted artefact: it outlives the code that wrote it**,
and a stale one inflates any figure derived from listing it. Every report was then checked against a
matching test source; the two orphan pairs (`.txt` + `TEST-*.xml`) were deleted, and the recomputed tally
came to **1131, 0 failures, across 58 files** — the baseline's 56 plus the two new chat classes. Maven and
the directory now agree.

**The rule this produced.** A test total is quoted from **Maven's printed summary**, and a directory-derived
count is only admissible after every report has been matched to a source file that still exists. This is
the same lesson as the stale `ChatService` class files earlier in the pass, in a different guise: a build
output is not evidence about this run until it has been shown to come from this run. The figure in every
deliverable is **1131**.
