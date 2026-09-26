# Campus Coin — Overnight Blockers

Decisions that genuinely require the project owner's input, missing credentials, or external
configuration, and that could not be resolved safely from the authoritative sources.

Each entry states what is blocked, why it cannot be inferred, what was implemented safely in the
meantime, and what happens if the blocker is never resolved. Work that can be completed without
these answers continues regardless; a blocker here never stops unrelated modules.

**Status vocabulary:** `OPEN` — awaiting input. `READY FOR REVIEW` — work is complete and only a
confirmation is outstanding. `RESOLVED` — the input arrived and the work is done and tested.

---

## OB-001 — Missing authoritative source documents

| Field | Value |
|---|---|
| **Priority** | HIGH |
| **Module** | All modules |
| **Related UC** | UC-01 … UC-27 |
| **Related BR** | BR-01 … BR-18, VĐ-01 … VĐ-13, UAT-01 … UAT-17 |
| **Status** | OPEN |

**Blocked task.** The three authoritative sources named in the project brief —
`Campus Coin End-to-End Web Solutions_SRS.pdf`,
`CampusCoin_DacTa_UseCase(1).docx` and `campuscoin_full.sql` — are not present in the repository
or anywhere on this machine. Only `campuscoin_full.sql` (`db/merged/`) is available.

**Why it is blocked.** Every module is required to trace `UC → BR → DB → API → Test →
Documentation`. The use-case and business-rule detail for modules 2–12 cannot be read from the
source documents, only inferred from what the database schema and its comments already encode.

**Required input from me.** Place the SRS PDF and the Use Case `.docx` in the repository (or tell
me where they are) so requirements can be quoted rather than reconstructed.

**Current safe state.** Nothing has been guessed at. The database is the source of truth and is
fully available, so every module is being derived from the schema, its procedure and trigger
bodies, its column comments and the existing `docs/ERD.md` / `docs/DB_DESIGN.md`, which already
record a UC/BR → database-object mapping produced while the documents were available.

**Temporary action.** Recon for each module reads the relevant schema objects, procedures,
triggers and views directly, and cites them. Where the schema does not settle a question, the
smallest implementation consistent with the schema is used and the assumption is written into the
module's API document. Anything that looks like a genuine business rule not visible in the schema
is raised here rather than invented.

**Impact.** Medium. Field names, status codes and ownership rules are derivable from the schema
with high confidence. Narrative details (exact error wording a UC specifies, exact screen flow,
wording of a UAT) can only be reconstructed, not verified. Module 1's wording was already taken
from the earlier session where the documents were readable.

**Suggested options.** (a) Add the two documents to the repo — preferred, it removes this blocker
entirely. (b) Confirm that deriving requirements from the schema and existing design docs is
acceptable for the remaining modules. (c) Accept the reconstruction and review each module's API
document for wording.

**Recommended next action.** Add the two files to the repository. Until then, modules continue to
be derived from the database and the assumption is recorded per module.

---

## OB-002 — No production email provider for password reset

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | 1 — Authentication (UC-03) |
| **Related UC** | UC-03 B2 |
| **Related BR** | BR-04 |
| **Status** | OPEN — implementation complete, waiting on external credentials |

**Blocked task.** Supplying the credentials that let a reset link reach the account owner in
production.

**Why it is blocked.** No provider, credentials or verified sender address has been supplied, and
inventing them is forbidden. This is an external-service dependency, not missing work.

**Required input from me.** An SMTP host, port, username and password (or an API key for a
transactional mail service), plus a sender address the account is allowed to send as.

**What was built while waiting.** `SmtpPasswordResetNotifier` now exists and is selected
automatically when `MAIL_HOST` and `MAIL_FROM_ADDRESS` are both set — **no code change is needed
once the values arrive**, only environment variables. The class sits behind the existing
`PasswordResetNotifier` port, alongside `FilePasswordResetNotifier` (development: writes the link
under `backend/target/`, gitignored, never to the log) and `NoopPasswordResetNotifier` (the
fallback: warns and sends nothing rather than writing a reset token to disk on a server). UC-03's
generic response is identical in all three cases, so account enumeration is not possible whichever
is installed.

The connection is configured under `campuscoin.security.password-reset.smtp` rather than Spring's
own `spring.mail`. That is deliberate: Spring Boot's `MailSenderAutoConfiguration` treats a
*present* `spring.mail.host` as "configure a mail server", including an empty string, so a blank
`${MAIL_HOST:}` default would build a sender pointed at no host. Owning the property means a blank
host reads as "no mail server" and installs the no-op — the same shape `AiConfig` uses for a blank
API key. The decision is recorded in `application.yml`'s comment and pinned by
`PasswordResetConfigTest#aBlankHostYieldsTheNoop`.

**Verification already done without credentials.** `SmtpPasswordResetDeliveryTest` runs a minimal
SMTP server on a loopback socket and asserts on the bytes a receiver actually gets — the envelope
sender and recipient, and the link inside both decoded body parts. So the MIME encoding and the
SMTP dialogue are proven rather than assumed; what the credentials add is a real host.

**Impact.** Password reset cannot deliver in production until the values are supplied. **The
service must not be treated as production-ready for UC-03**, and this entry is the reason.

**Recommended next action.** Supply the SMTP values (or a transactional-mail API key and sender
address). The keys are listed as commented placeholders in `.env.example`; nothing else changes.

---

## OB-003 — Production secrets management

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | All modules |
| **Related UC** | — |
| **Related BR** | Section 7.9 |
| **Status** | OPEN |

**Blocked task.** Moving `JWT_SECRET` and `DB_PASSWORD` from environment variables into a managed
secret store.

**Why it is blocked.** Which secret manager to use (AWS Secrets Manager, Vault, Kubernetes
Secrets, …) is a deployment decision that has not been stated. There is no cloud account or
cluster configuration available to test against.

**Required input from me.** The target deployment platform and its secret mechanism.

**Current safe state.** Both values are read from the environment with **no default**, so the
application refuses to start rather than falling back to a weak or empty value. No secret is
committed: `.env` is gitignored and `.env.example` holds sample values only.

**Temporary action.** Documented in `docs/SECURITY.md` §9 and `README.md` §3.7. Environment
variables are an acceptable interim mechanism.

**Impact.** Low to medium. Secrets are not in source control, but they currently live in a
process environment and a local `.env` file rather than a rotating store.

**Suggested options.** Introduce Spring Cloud Vault, or inject from the platform's native secret
store at deploy time.

**Recommended next action.** State the deployment platform; the integration is then a
configuration change, not a code change.

---

## OB-004 — Throttle counters are per instance

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 1 — Authentication (UC-02, UC-03) |
| **Related UC** | UC-02 A1, UC-03 A2 |
| **Related BR** | Section 7.10 |
| **Status** | READY FOR REVIEW |

**Blocked task.** Moving brute-force and reset-flood counters to shared storage.

**Why it is blocked.** Section 2 forbids adding a table without approval, and no Redis or other
shared store is provisioned. Whether the deployment runs more than one instance is unknown.

**Required input from me.** Confirmation of whether the service runs as a single instance. If it
runs multiple, approval to add shared storage (Redis) or to add a `system_settings`-backed table.

**Current safe state.** Counters are in a `ConcurrentHashMap`, keyed by normalised email, pruned
on use, with the limit read from `auth.max_login_attempts`. Thread-safe and correct for a single
instance. Already implemented, tested, and documented as instance-local.

**Temporary action.** Documented in `docs/SECURITY.md` §11 with the migration path.

**Impact.** With N instances, an attacker gets up to N× the configured attempt limit. For a
single-instance deployment the limit is exact.

**Suggested options.** (a) Single instance — no action, close this. (b) Redis with a TTL key.
(c) A dedicated table, which needs schema approval.

**Recommended next action.** Confirm the instance count. If one, this blocker can be closed.

---

## OB-005 — Administrator access to the database bypasses `sp_require_admin`

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | 11 — Administration (UC-20…UC-23) |
| **Related UC** | UC-20, UC-21, UC-22 |
| **Related BR** | BR-06 |
| **Status** | READY FOR REVIEW |

**Blocked task.** Enforcing administrator authorization for direct table writes.

**Why it is blocked.** By design, and recorded in `DB_DESIGN.md` §4.4: MySQL cannot know who is
executing a statement, and the session-variable approach was deliberately removed because a
pooled connection could leak the flag to another user's request. A direct `UPDATE`/`INSERT` from
Adminer or Workbench therefore does not pass through the admin gate. Closing this requires a
schema or connection-privilege change, which section 2 forbids without approval.

**Required input from me.** Approval to change database privileges so the application account
holds only `EXECUTE` on procedures plus `SELECT` on views, with no direct table write grants.

**Current safe state.** Every administrative path through the API goes through
`sp_require_admin`, which verifies both `role = 'ADMIN'` and `status = 'ACTIVE'` against the
`users` table. Documented as a known, deliberate trade-off.

**Module 11 confirms this claim in the API as well as in the design.** The module's sixteen
endpoints were built so that **no administrative write reaches a table except through one of the
eight `sp_admin_*` procedures** — the application account holds direct `INSERT`/`UPDATE` grants on
every table the module touches, so a JPA `save` would have succeeded while skipping
`sp_require_admin` and leaving no `admin_audit_log` row, which is exactly the bypass this blocker
records. Concretely, `CategoryRepository`, `UserRepository` and `SystemSettingRepository` are
**read-only** in this module, and the one decision that preserves the property is that
`PATCH /admin/announcements/{id}` writes `isActive` only: `announcements` has no content-update
procedure, so a content-update endpoint would have had to write through Hibernate, where neither the
gate nor the audit row runs. That refusal is a §13 scope decision recorded in
`docs/modules/MODULE_11_ADMINISTRATION.md` §2.1 and §5, and manual case M11-06 step 12 is where a
tester sees it. **It closes the API half of this blocker; the direct-credential half is unchanged**,
and the required grant change below is still what would close the blocker itself.

**Temporary action.** Documented in `DB_DESIGN.md` §4.4, §6 and `docs/SECURITY.md`.

**Impact.** Someone with direct database credentials can bypass the admin gate. This is a
privileged-account concern, not an unauthenticated attack path.

**Suggested options.** Restrict the application account's grants as described above — the
cleanest fix, and it is a grant change rather than a schema change.

**Recommended next action.** Decide whether to narrow the `campuscoin_app` grants before
deployment.

---

## OB-006 — `import_rows.ai_suggested_category_id` has no SQL-level ownership check

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 12 — Optional / Advanced (UC-11) |
| **Related UC** | UC-11 |
| **Related BR** | BR-02, BR-13 |
| **Status** | RESOLVED — closed at the application layer by module 12 (option (a)) |

**Blocked task.** Adding a trigger to reject an `import_rows.ai_suggested_category_id` that
belongs to another student.

**Why it was blocked.** This was an acknowledged remaining gap, recorded in `DB_DESIGN.md` §4.8. The
fix would be a new `BEFORE INSERT/UPDATE` trigger on `import_rows`. Adding a trigger is a schema
change, which section 2 forbids without explicit approval.

**Required input from me.** Approval to add the trigger, or a decision to handle it entirely in
the application layer when UC-11 is implemented.

**How it was resolved.** Module 12 took option (a): the application layer is the only writer of the
column, and it resolves every value against the caller's own visible categories before writing. The
resolution has one entry point, `ImportCategoryResolver`, whose answer *is* the value that reaches
`ai_suggested_category_id` — there is no other computation and no path that takes a category id from
the request. The chain is:

| Writer | What it does with the column |
|---|---|
| `ImportCategoryResolver` | Matches the file's category name against the caller's own visible categories (their own rows plus the shared defaults) and, as a last line of defence, refuses any proposed id that is not in that visible set. A name that matches nothing resolves to `null`, so a foreign id cannot be produced |
| `ImportPreviewer` | Carries that resolved value into `ImportRowDraft.aiSuggestedCategoryId()`; it does not compute one |
| `ImportWriteDao` | Writes the draft's value behind `NULLIF(:aiSuggestedCategoryId, '')`, so there is no default and no fallback that could name a row the caller cannot see |

`sp_apply_csv_batch` remains the real write path for imported *transactions* and continues to verify
ownership itself. `ImportApiIT#aLearnedMappingSuggestsACategoryWithoutFilingTheRow` asserts the
positive half end to end through HTTP — a learned mapping produces an `aiSuggestedCategoryId` drawn
from the caller's own visible set — and the last-line refusal is what a future change to
`CategorySuggester` would have to defeat before a foreign id could appear. The refusal branch itself
has no test that forces it, because nothing in the current code can reach the column with an id from
outside the visible set; that is recorded here rather than claimed as covered.

**Impact of the resolution.** Low, and unchanged from the assessment above: the column has no effect
on any transaction or report, and there is no API surface that accepts a category id for it.

**Option (b) remains open.** A `BEFORE INSERT/UPDATE` trigger on `import_rows` matching
`trg_bookmarks_before_insert/update` would still be defence in depth for a writer that bypasses the
application. It was not added, because it is a schema change with no use case requiring it and the
application-layer guarantee is complete for every path the API exposes.

---

## OB-007 — `docker-compose.yml` comments are Vietnamese

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | Infrastructure |
| **Related UC** | — |
| **Related BR** | Language requirement |
| **Status** | RESOLVED |

**Blocked task.** Translating the Vietnamese comments in `docker-compose.yml` to English.

**Why it was blocked.** `campuscoin_full.sql` and its companion database scripts are frozen
artifacts from a completed phase, and `docker-compose.yml` belongs to that same phase. Whether the
English-language requirement applied retroactively to these already-delivered files, or only to
backend module artifacts, was not stated. Editing them is harmless but is a change to a
declared-finished deliverable.

**Resolution.** The language requirement is explicit and unconditional — *"ALL PROJECT ARTIFACTS
AND IMPLEMENTATION OUTPUTS MUST BE IN ENGLISH"*, listing technical documentation and
implementation notes with no exception for earlier phases — so the scope question resolves itself
in favour of translating. `docker-compose.yml` was rewritten in English, and the one ASP.NET Core
reference it contained (contradicting the locked stack, and therefore a correctness defect as well
as a language one) was removed.

**Required input from me.** None. Closed.

**Current safe state.** `docker-compose.yml` is fully English. Its content is otherwise unchanged:
same services, same ports (MySQL 3306, Adminer 8081), same MySQL flags, same volume and health
check. Verified by re-running the full test suite and confirming the stack still starts.

**Temporary action.** None needed.

**Impact.** None functional. The file is behaviourally identical.

**Suggested options.** Not applicable.

**Recommended next action.** Confirm whether the comments inside the frozen `db/*.sql` files
should also be translated. They are stored database artifacts rather than documentation, no
deliverable reads them, and translating them would produce a new file hash for an artifact that
was signed off — so they were left untouched. This is now the only outstanding language question,
and it is cosmetic.

---

## OB-008 — Coverage of `/actuator` beyond the public allow-list

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | Infrastructure / cross-cutting |
| **Related UC** | — |
| **Related BR** | Section 7.7 |
| **Status** | RESOLVED |

**Blocked task.** None — recorded because a real defect was found and fixed here.

**What was found.** `management.endpoints.web.exposure.include` listed `metrics`, but
`SecurityConfig` ended its rule chain with `anyRequest().permitAll()`. `/actuator/metrics` is not
one of the permitted paths, so it fell through the catch-all and was **readable without a token** —
publishing internal counters to anyone who could reach the port. The same catch-all also made
`/actuator/env` reachable. No test covered an actuator path other than `/actuator/health`, so
nothing caught it.

**Why it was a defect, not a preference.** An unauthenticated endpoint that reports on the
application's internals is exactly what section 7.7 keeps out of responses. The configuration
declared the endpoint exposed, which means it was reachable, not merely declared.

**Fix applied.** Three changes, all tested:
1. `metrics` removed from `exposure.include`; only `health` and `info` remain, which is all the
   deployment needs.
2. `health.show-details` changed `always` → `never`, so the public probe returns the summary
   status without naming components or reporting the database and pool state.
3. An explicit `.requestMatchers("/actuator/**").authenticated()` rule added **before** the
   catch-all, so any actuator path not in the allow-list is closed rather than quietly readable.

**Verification.** `SecurityHardeningIT` gained two tests: the health probe answers 200 without a
token and its body contains neither component names nor the word `database`, and `/actuator/metrics`
and `/actuator/env` both answer 401. `SecurityHardeningIT` now runs 16 tests, all passing; the full
suite is green.

**Required input from me.** None. Closed. Recorded here so the reasoning is reviewable, and in
`docs/SECURITY.md` §2 and its production checklist.

---

## OB-009 — Retiring a category freezes every recurring rule filed under it

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | 5 — Recurring (UC-09), with module 3 (UC-06) |
| **Related UC** | UC-09, UC-06 |
| **Related BR** | BR-07, BR-05 |
| **Status** | READY FOR REVIEW |

**Blocked task.** Letting a student still *end* (or otherwise edit) a recurring rule whose category
was retired in module 3.

**Why it is blocked.** `trg_recurring_rules_before_update` calls
`sp_validate_recurring_rule(p_user_id, p_category_id, p_type)` unconditionally on **every** update,
and that procedure raises `'BR-07: category has been disabled'` for a retired category. Unlike
`sp_validate_transaction`, which takes a `p_require_active` parameter and lets the update trigger pass
`0`, `sp_validate_recurring_rule` has **no such parameter and no escape**. So once `categories.is_active`
is false, every write to every rule under that category is refused by the database — including
`PATCH {"status": "ENDED"}`, which is the one write a student most plausibly wants.

The application cannot work around it: no JPA statement reaches the table without firing the trigger,
and section 4 forbids changing the procedure's signature or the trigger body. This is not a defect in
the module; it is the schema's rule (BR-07 — a retired category cannot be filed against), applied to
a table that has no "move away from it" escape hatch of its own.

**Required input from me.** Choose a remedy:

1. **Add a `p_require_active` parameter to `sp_validate_recurring_rule`** and have
   `trg_recurring_rules_before_update` pass `0` — mirroring exactly what `sp_validate_transaction`
   already does. This is a schema change (procedure + trigger body) and needs approval under §4.
2. **Accept the limitation** as BR-07's intended reading: retiring a category is a deliberate act, and
   freezing its rules until it is re-enabled is an acceptable consequence. Then no change is needed
   and this blocker closes as documented behaviour.
3. **Retire the category only after ending its rules** — a process rule rather than a technical fix.

**Current safe state.** The module behaves correctly and safely, and the behaviour is pinned by two
tests (`aRetiredCategoryBlocksEveryUpdateToItsRules`, `retiringACategoryDoesNotChangeTheRule`) so it
cannot drift silently. `RecurringRuleService.update` pre-checks the case and answers
`409 CATEGORY_RETIRED` with an actionable message naming both remedies, rather than letting the
trigger's refusal surface as an opaque conflict. The scheduler already skips rules on an inactive
category, so a frozen rule is also a silent one — no posting is attempted and nothing fails in the
background.

**Temporary action.** Documented as first-class behaviour in `docs/api/recurring.md` §9 and §11, and
in the module report's deferred section. The two remedies a client can actually use are stated in the
error message: move the rule to a live category (which is still accepted, because the *new* category
is what gets re-checked), or re-enable the category, edit, and retire it again.

**Impact.** Medium and narrow. It affects only a rule whose category was retired *and* which the
student then wants to change. Retiring a category does not stop its rules posting (the scheduler
skips them) and does not change them — it only prevents edits. No data is lost, no security property
is weakened, and no other module is affected: module 3 can still retire a category, and module 4's
transactions on that category remain fully editable, because `sp_validate_transaction` *does* have the
escape.

**Suggested options.** Option 1 is the clean fix and is small — one procedure signature, one trigger,
plus re-running the suite. It is recommended unless the owner prefers to read BR-07 strictly, in which
case option 2 closes this with no change.

**Recommended next action.** Decide between option 1 (approve the schema change) and option 2 (accept
and close). Until then the module ships with the documented, tested behaviour.

**See also OB-011.** Module 6 found the identical defect in `sp_validate_budget` /
`trg_budgets_before_update`: a budget under a retired category is frozen exactly as a rule is. The same
`p_require_active` parameter, added to both procedures, closes both blockers — so the two are worth
deciding together rather than separately.

---

## OB-010 — Pausing a recurring rule defers its periods instead of skipping them

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | 5 — Recurring (UC-09) |
| **Related UC** | UC-09 |
| **Related BR** | BR-16 |
| **Status** | READY FOR REVIEW |

**Blocked task.** Making a pause skip the periods that fall inside it, which is how UC-09's wording
reads.

**Why it is blocked.** `sp_post_recurring_transactions` declares its cursor over rules that are
`ACTIVE` **at the moment the cursor opens**:

```sql
DECLARE cur CURSOR FOR
  SELECT ... FROM recurring_rules r JOIN categories c ON c.id = r.category_id
   WHERE r.status = 'ACTIVE' AND c.is_active = 1
     AND r.next_run_date <= IFNULL(p_as_of, CURDATE()) ...
```

Nothing selects a `PAUSED` rule, so nothing advances its `next_run_date` while it is paused. The run
after a resume therefore walks forward from the frozen cursor and posts **every** period whose date
passed during the pause. Pausing a monthly rule for three months and resuming it posts four periods at
once, all dated on their original scheduled dates. The periods are deferred, not skipped.

Matching the requirement's wording would require the procedure to advance `next_run_date` past the
paused interval — a change to a procedure in `db/`, which §4 freezes. Doing it in Java instead would
mean reproducing the procedure's period arithmetic (`DATE_FORMAT`/`WEEK`/`QUARTER` keys, the
`INSERT IGNORE` against `uk_occurrence_rule_period`) in the application, which this module is
specifically built not to do: a second implementation of the same rule, able to disagree with the first.

**Required input from me.** Choose a remedy:

1. **Accept the deferral** as the correct reading of UC-09 and close this. This is defensible on its own
   terms — a pause that silently discarded transactions would make the student's history wrong for the
   months they were away, and "resume from where you left off" is what the catch-up behaviour already
   does after downtime. No change needed; this blocker closes as documented behaviour.
2. **Approve a schema change** to `sp_post_recurring_transactions` that advances `next_run_date` past
   the paused interval on resume (or on pause), so the skipped periods are never posted. Needs approval
   under §4 and a re-run of the recurring suite.
3. **Change the frontend's wording** rather than the behaviour: label the action "stop posting until I
   resume" and warn that a resumed rule catches up. A product decision, no code change.

**Current safe state.** The module behaves correctly, and the behaviour is pinned by
`RecurringRuleApiIT#pauseDefersPeriodsRatherThanSkippingThem`, which asserts both halves — the cursor
does not move while paused, and resuming posts every deferred month key. It cannot drift silently. No
data is lost and no security property is weakened: the deferred transactions are ordinary expenses
subject to the same BR-09 budget accounting and BR-16 period keys as any other.

**Temporary action.** Documented as first-class behaviour in `docs/api/recurring.md` §9 (the state
table) and §11 (a dedicated "why a paused rule backfills rather than skips" section stating the three
consequences a client must handle), and in the module report (§5.12).

**Impact.** Medium and narrow. It affects only a rule that is paused and later resumed. The client-
visible consequence is a burst of transactions on resume, dated in the past, which will also move the
budget position for those months and can raise `BUDGET_NEAR`/`BUDGET_EXCEEDED` alerts retroactively —
that interlock is already tested for the normal path (UC-09 B4) and holds unchanged for the backfilled
ones, because they go through the same insert trigger. A client that wants to avoid the burst should
end the rule and create a new one, or move `nextRunDate` forward before resuming.

**Suggested options.** Option 1 is recommended: the deferral is consistent with A1's catch-up-after-
downtime behaviour, which the requirement *does* ask for, and the two are the same mechanism. Option 2
is the only way to get the literal reading, and it is a schema change the owner must approve. Option 3
is a one-line copy change if the literal reading is what the project intends.

**Recommended next action.** Decide between option 1 (accept and close), option 2 (approve the
procedure change), and option 3 (fix the wording). Until then the module ships with the documented,
tested behaviour.

---

## OB-011 — Retiring a category freezes every budget filed under it

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Module** | 6 — Budget & Notifications (UC-13), with module 3 (UC-06) |
| **Related UC** | UC-13, UC-06 |
| **Related BR** | BR-07, BR-11 |
| **Status** | READY FOR REVIEW |

**Blocked task.** Letting a student still *change or remove* a budget whose category was retired in
module 3 — specifically, whether changing the limit should stay refused.

**Why it is blocked.** `trg_budgets_before_update` calls `sp_validate_budget(p_user_id, p_category_id)`
unconditionally on **every** update, and that procedure raises `'BR-07: category has been disabled'`
for a retired category. Like `sp_validate_recurring_rule` (OB-009) and unlike
`sp_validate_transaction`, `sp_validate_budget` has **no `p_require_active` parameter and no escape**.
So once `categories.is_active` is false, every `PATCH` to every budget under that category is refused
by the database.

The application cannot work around it: no JPA statement reaches the table without firing the trigger,
and §4 forbids changing the procedure's signature or the trigger body. This is not a defect in the
module; it is BR-07 applied to a table whose update trigger has no exemption.

**This was found by reading the trigger, not by assuming the analogue.** The first draft of
`docs/api/budgets.md` claimed the opposite — that a budget stays editable the way module 4's
transactions do, on the reasoning that a limit change does not choose a category again. That
reasoning is about what the *request* does; the trigger cares about what the *row* holds. The claim
was corrected after a test was written to settle it: against the original code the test failed,
because the trigger refused the write and it surfaced as an opaque `409 DATA_CONFLICT` whose message
("refresh and try again") names a remedy that cannot work while the category stays retired.

**Required input from me.** Choose a remedy:

1. **Add a `p_require_active` parameter to `sp_validate_budget`** and have
   `trg_budgets_before_update` pass `0` — mirroring exactly what `sp_validate_transaction` already
   does and what OB-009 option 1 proposes for rulings. This is a schema change (procedure + trigger
   body) and needs approval under §4. The same change would close OB-009, and doing both together is
   cheaper than either alone.
2. **Accept the limitation** as BR-07's intended reading: retiring a category is a deliberate act, and
   freezing its budgets until it is re-enabled is an acceptable consequence. Then no change is needed
   and this blocker closes as documented behaviour.

**Current safe state.** The module behaves correctly and safely, and the behaviour is pinned by two
tests — `retiringTheCategoryFreezesTheExistingLimit` (which also asserts the refusal is named and the
row is unchanged, and that re-enabling restores editability) and
`retiringTheCategoryLeavesTheLimitReadable` (which asserts the delete asymmetry). `BudgetService.update`
pre-checks the case and answers `409 CATEGORY_RETIRED` with an actionable message naming both
remedies, rather than letting the trigger's refusal surface as an opaque conflict — the same treatment
module 5 gives `sp_validate_recurring_rule`.

**Temporary action.** Documented as first-class behaviour in `docs/api/budgets.md` §9 and its failure
table, in `docs/modules/MODULE_06_BUDGET.md` §5.2, and in
`docs/testing/manual/MODULE_06_MANUAL_TEST.md` §5.2 (cases M6-26…M6-28). The two remedies a client can
actually use are stated in the error message: **re-enable the category**, change the budget, retire it
again; or **remove the budget**.

**The asymmetry with delete is deliberate and load-bearing.** A `DELETE` fires no `BEFORE UPDATE`
trigger, so removal is **accepted** on a frozen budget while an edit is not. That is the one action
which leaves the category alone, and without it the only exit from the frozen state would be restoring
the category. Creating a *new* budget on the retired category is still refused — removing an existing
one is allowed, adding one is not. Both are correct and both are tested.

**Impact.** Medium and narrow. It affects only a budget whose category was retired *and* which the
student then wants to change. Retiring a category does not delete, change or hide any budget, does not
stop it being listed or read, and does not stop its consumption being computed — it only prevents
edits. No data is lost and no security property is weakened. Module 4's transactions on the same
category remain fully editable, because `sp_validate_transaction` *does* have the escape.

**Suggested options.** Option 1 is the clean fix and is small — one procedure signature, one trigger,
plus re-running the budget and recurring suites. It is recommended, and it is the same change OB-009
needs, so the two should be decided together. Option 2 closes this with no change.

**Recommended next action.** Decide between option 1 (approve the schema change, jointly with OB-009)
and option 2 (accept and close). Until then the module ships with the documented, tested behaviour.

---

## OB-012 — Values written by the locked M12 surface stay in plaintext

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 12 — Optional / Advanced (`insights`, `import_rows`) |
| **Related UC** | UC-17, UC-11 |
| **Related BR** | BR-12, BR-17 |
| **Status** | OPEN — still unencrypted, now with a live read path; re-scoped by module 12 |

**Blocked task.** Bringing `insights.summary_text`/`advice_text` and the amount aggregates UC-17
stores, and `import_rows.parsed_description`/`parsed_amount` (UC-11), under application-level field
encryption.

**What changed when module 12 was built.** The exposure is no longer latent. Both tables are now
reachable through the running API — `GET /api/v1/insights` (69) reads `insights`, and the CSV
import preview (62, 64) reads `import_rows` — so a direct `SELECT` on either table now reveals values
a student can also read through the API. That is the whole finding: the columns were unencrypted
before because nothing read them, and building the reader did not change the columns.

**Two further points module 12 recorded rather than fixed.**

1. **An imported row's description reaches `transactions.description` as ciphertext; the preview
   copy stays plaintext.** The two do not carry the same protection, because they are not the same
   data. `transactions.description` is written by `TransactionService` through the encryption
   boundary and is `VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin` — the Base64 envelope's
   column. `import_rows.parsed_description` is a `VARCHAR(255)` in the table's default `utf8mb4`, and
   it holds the row as the file wrote it, for the preview to display beside the values the importer
   read. So an import produces a transaction whose description is encrypted **and** a preview row
   that still holds the same text in the clear. A change that encrypts `parsed_description` without
   widening it to `ascii_bin` would fail the column's own encoding; the two changes are one piece of
   work, not two.
2. **`import_rows.raw_data` is a third plaintext copy.** It is the whole CSV line as JSON, so it
   contains the amount and the description again. It is named here so that the work above is scoped
   to three columns rather than two.

**Why it remains blocked.** The blocker's own resolution condition — "fold into module 12 when it is
approved" — has not been met. Module 12 is **implemented but still locked pending the project
owner's approval**, and the encryption work here would require changing the same two procedure
bodies the module's own procedures now depend on. `sp_generate_monthly_insight` still writes
`total_income`, `total_expense` and `net_amount` as `DECIMAL` sums and `sp_apply_csv_batch` still
writes `parsed_amount` as a `DECIMAL`; a procedure cannot encrypt, so making those columns
`VARBINARY` would stop the schema from loading at all. Doing it now would also be a schema change
beyond the module's approved scope. Claiming the columns are protected while their only writer still
inserts plaintext would be worse than leaving the gap documented.

**Required input from me.** Approval to close OB-012. The work is well-defined and the same shape
the transaction descriptions already have: the Java writer encrypts, the Java reader decrypts, the
procedure is narrowed to stop writing the free-text column directly, and the affected columns are
widened to the Base64 envelope's `VARCHAR ... ascii_bin`. It touches `db/01_schema.sql`,
`db/03_procedures.sql` and `db/merged/campuscoin_full.sql`, which is why it needs to be asked for
rather than assumed.

**Current safe state.** The columns are marked in `db/01_schema.sql` with a `KNOWN PLAINTEXT —
RESIDUAL EXPOSURE, DELIBERATE` comment pointing at this blocker, so a later reader finds the gap at
the schema rather than having to rediscover it. **No response publishes a value these columns hold
that the caller is not entitled to see:** UC-17's insight is the caller's own, and UC-11's preview
rows are the caller's own file. The exposure is a direct `SELECT` on the database, not an API leak,
and the API's ownership checks are the reason it stays that way.

**Impact.** Low, and unchanged in kind from before: it is a residual at-rest exposure for a person
with database credentials, not a live disclosure through the application. What module 12 changed is
that the exposure is no longer latent — the tables are now read by a running feature — so this entry
is now describing real data rather than an inert one.

**Recommended next action.** Leave OPEN. Fold into a follow-up approval when the project owner
unlocks module 12; the two `db/` changes module 12 already carries are the precedent for how such a
change is recorded and mirrored into `db/merged/campuscoin_full.sql`.

---

## OB-013 — Amounts are deliberately not encrypted

| Field | Value |
|---|---|
| **Priority** | MEDIUM |
| **Modules** | 6, 7, 8, 9, 11 — every module that aggregates an amount |
| **Related UC** | UC-12, UC-13, UC-14, UC-15, UC-16, UC-18, UC-21, UC-22 |
| **Related BR** | BR-05, BR-10, BR-13 |
| **Status** | OPEN — deferred by explicit decision; needs its own approval |

**Blocked task.** Encrypting `transactions.amount`, `recurring_rules.amount` and
`budget_alert_log.spent_amount`/`limit_amount` so that a direct `SELECT` does not reveal what a
student spent.

**Why it is blocked.** MySQL cannot decrypt. It has no AES-GCM, and putting the application key
inside the database is forbidden by the project's own security constraints, so an encrypted amount
could not be `SUM()`-ed, compared, ordered, or used in a `CHECK` by **any** view or stored
procedure. **Twelve of the fourteen views** do exactly that — `v_monthly_income_expense`,
`v_monthly_income_expense_6m`, `v_category_month_totals`, `v_category_spend_trend`,
`v_top_category_current_month`, `v_dashboard_summary`, `v_budget_consumption`,
`v_daily_spending_current_month`, `v_weekly_spending_current_month`, `v_user_recent_activity`,
`v_admin_usage_stats` and `v_admin_top_categories`; only `v_active_announcements` and
`v_dashboard_tips` are unaffected. Most read it indirectly, by joining `v_monthly_income_expense` or
`v_category_month_totals`, so they would break too. **Five procedures** touch it:
`sp_check_budget_alerts` (which compares `SUM(amount)` against a limit and writes
`budget_alert_log`), `sp_generate_tips`, `sp_generate_monthly_insight`,
`sp_post_recurring_transactions` and `sp_apply_csv_batch`.

Encrypting amounts is therefore not a column change. It means moving the entire reporting and
aggregation tier out of the database and into Java — modules 6, 7, 8, 9 and 11, across dozens of
files and every report test — because the database would no longer be able to answer a single
question about money. That is a piece of work in its own right, with its own approval.

Three shortcuts were considered and **rejected**, and the reasons are recorded so they are not
reintroduced later:

1. **Keep a plaintext `amount` column as well**, so reports keep working. This defeats the security
   goal outright — the whole point is that a direct `SELECT` reveals nothing.
2. **Encrypt only in the API and leave the column plaintext.** That is theatre: the data at rest is
   what the threat model protects, and it would be unchanged.
3. **Deterministic encryption** to make equality or `SUM` work. MySQL cannot sum ciphertext in any
   mode, so this does not solve the problem it is proposed for; and a deterministic scheme leaks
   which rows hold equal amounts, which is itself sensitive.

**Required input from me.** Approval to schedule the reporting-tier migration as a separate project.
Until then the decision recorded in `docs/SECURITY.md` §12.5 stands, stated plainly rather than
softened: **this build encrypts free text only; a direct `SELECT` on `transactions` still reveals
amounts, and `docs/SECURITY.md` says so.**

**Module 11 serves this exposure and does not change it.** `GET /api/v1/admin/stats` publishes
`totalExpenseLogged` and `totalIncomeLogged`, and `GET /api/v1/admin/stats/top-categories` publishes a
`totalAmount` per category — all three from `v_admin_usage_stats` and `v_admin_top_categories`, which
are `SUM(t.amount)` over the plaintext column. Serving them was a deliberate choice over the
alternative of hiding the figures: the view defines the column, UC-23 asks for the figures, and a
response that silently dropped them would be less honest than one that carries them and names the
gap. **No route in module 11 returns one student's amounts** — every money figure there is a sum over
many students — so the module widens the *number of readers* of the existing aggregate, not the
granularity of the data. The exposure is stated in the response DTO's javadoc
(`AdminUsageStatsResponse`), in `docs/api/administration.md` §7, in
`docs/modules/MODULE_11_ADMINISTRATION.md` §3.6 and §11, and in manual case M11-10 and §5.2.

**Current safe state.** The gap is stated in three places an operator will actually reach — the
`campuscoin.encryption` block in `application.yml`, the `EncryptionService` class javadoc, and
`docs/SECURITY.md` §12.5 — each naming this blocker. `EncryptionService.encryptAmount` /
`decryptAmount` already exist, are unit-tested (canonicalising to scale 2 so `12.0` and `12.00` are
interchangeable), and are **deliberately unused**: they are there so the deferred work does not also
have to invent an amount-encoding contract. `transactions.amount` carries a "NOT encrypted,
deliberately" comment in `db/01_schema.sql`.

**Impact.** Medium. It is the largest remaining gap in the "a stolen database reveals nothing"
guarantee: descriptions and notes are protected, but spending amounts — arguably the more sensitive
figure — are not. Anyone with the database file can see exactly what every student spent. Nothing is
*worse* than before this phase; the free-text fields are newly protected and the amounts are
unchanged from the original design.

**Recommended next action.** Leave OPEN and explicitly deferred. Revisit only as a scoped project
with the reporting-tier rewrite costed, not as a schema tweak. Until then, treat the tablespace
encryption in `docs/SECURITY.md` "Outstanding before production" item 9 as the mitigation for the
amount columns.

---

## OB-014 — The recurring scheduler copies the rule's envelope into the posted transaction

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 5 — Recurring (UC-09), with 4 (UC-07) |
| **Related UC** | UC-09, UC-07 |
| **Related BR** | BR-08, BR-16 |
| **Status** | READY FOR REVIEW — limitation documented and pinned by a test |

**Blocked task.** Nothing is blocked; this records a limitation discovered while encrypting
`recurring_rules.description`, and the repair that makes it harmless.

**What happens.** `sp_post_recurring_transactions` builds each posted transaction from the rule,
copying `r.description` into the new transaction's description. A stored procedure cannot decrypt,
so it copies the rule's **ciphertext envelope** through unchanged. The posted transaction therefore
holds the same envelope the rule holds, rather than a freshly encrypted one.

**Why it is not a defect.** The envelope it copies is a valid envelope under the current key, so it
decrypts back to exactly the rule's original plaintext — which is the correct description for the
posted transaction. Two properties make this safe rather than lucky: the value is authenticated, so
a corrupted copy fails loudly instead of surfacing as a wrong description; and the transaction's
description is then identical to the rule's, which is the intended semantics of "post this rule".

Ciphertext reuse across two rows is normally something to avoid, because it leaks that two values
are equal. It does not apply here: the two rows genuinely do hold the same description by design,
and the attacker who could compare them already holds the database the envelopes sit in. The
security property that matters — a fresh random IV for every *distinct* value the application
writes — is unaffected, because the procedure writes no new plaintext.

The procedure's local variable was widened to `VARCHAR(2048)` to match the column. At the original
`VARCHAR(255)` the envelope (up to 2048 characters for a 255-character note) was truncated on fetch
and **every** run failed with `Data too long for column 'v_desc'` — a real defect this work
introduced and immediately fixed, caught by six failing recurring tests.

**Required input from me.** Confirm the current behaviour is acceptable, or ask for the alternative:
have `TransactionService` re-encrypt the description after the procedure posts, so each transaction
row carries its own envelope. The alternative buys nothing security-wise (the plaintext is the same)
and costs a second write per posted transaction, which is why it was not taken.

**Current safe state.** The behaviour is correct and pinned by
`postedTransactionDescriptionSurvivesTheScheduler` in `RecurringRuleApiIT`, which posts a rule
through the scheduler path and asserts the description survives end to end: the raw transaction
column is not the plaintext, it decrypts to the rule's original text, and the HTTP response shows
it. The limitation and its reasoning are recorded in `db/03_procedures.sql` at the procedure that
performs the copy.

**Impact.** Very low. Student-visible behaviour is exactly right. The only observable trace is that
a posted transaction's `description` column equals the rule's rather than differing by IV — visible
only to someone reading raw columns, who by definition already has the database.

**Recommended next action.** Accept and close, unless the per-row envelope is wanted for its own
sake.

---

## OB-015 — The insight branch of UC-19 is refused until module 12 is approved

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 10 — Bookmarks / Notes (UC-19), with 12 — Optional / Advanced |
| **Related UC** | UC-19, UC-17 |
| **Related BR** | BR-02 |
| **Status** | OPEN — deferred with module 12 |

**Blocked task.** Serving `POST /api/v1/bookmarks` with `itemType: "INSIGHT"`, so a student can save
a monthly insight as well as a saving tip.

**Why it is blocked.** UC-19 B1 names "a tip or an insight", and `bookmarks.item_type` is
`ENUM('TIP','INSIGHT')` with `ck_bookmark_target` and a dedicated `insights` foreign key supporting
both shapes — so the schema is ready. But insights are **UC-17**, inside module 12, which is
**implemented but still locked pending the project owner's approval**. Serving the branch would mean
exposing a locked module's contract through this one, and faking it — returning a tip under an
`INSIGHT` label — would be worse.

**What changed when module 12 was built.** The factual basis of this blocker has moved. `insights`
now has an owner in the application: `InsightViewDao` reads it, `InsightService` serves it, and
endpoints 69–71 expose it. So the reason for refusing the branch is now **scope** — module 12 is
locked — and no longer the absence of a read path. The work described below is unchanged and still
worth doing at the same moment; it is simply no longer the only thing standing between UC-19 B1 and
its second act.

**What was built instead.** `POST` accepts `itemType` and answers `INSIGHT` with
`400 VALIDATION_ERROR` and a field error on `itemType` whose message names UC-17. Refusing **by
name** rather than narrowing the enum matters: `INSIGHT` is a real value of the column, so a narrowed
enum would answer with a JSON parsing failure calling a genuine column value "invalid" — both untrue
and unhelpful. This is the treatment module 9 gives `LOW_SAVINGS_RATE` ("the value exists in the
schema and the module records what it does not serve"). It is pinned by
`BookmarksApiIT#anInsightIsRefusedByName`, which also asserts nothing was written.

`BookmarkResponse.itemType` is published even though it holds only `TIP` today, so that adding the
branch later is not a breaking response change.

**Required input from me.** Approval to start module 12 (or explicit approval to build the insight
read path ahead of it). When module 12 exists, the work here is small: `requireTipTarget` becomes a
two-branch resolver, `Bookmark.newTipBookmark` gains an insight counterpart, and
`BookmarkViewDao`'s two queries gain the `insights` join they already have a column for.

**Current safe state.** The refusal happens before the database is asked anything, so no partial row
can exist. The scope decision is recorded in `BookmarkService`'s class javadoc, in
`BookmarkController`'s class javadoc, in the inventory's Module 10 section, and in
[`docs/api/bookmarks.md` §7](api/bookmarks.md#7-the-insight-branch-is-refused-not-served).

**Impact.** Low. One act of UC-19 — saving an insight — is not available. Everything else in UC-19
(mark a tip, add a note, read the list, un-mark) is complete and tested. No student can currently
*create* an insight either, so nothing that exists is unreachable through the API.

**Recommended next action.** Leave OPEN; fold into module 12 when it is approved, together with
OB-012.

---

## OB-016 — Two `db/` changes carried by module 12, and the merge file that must follow them

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 12 — Optional / Advanced |
| **Related UC** | UC-11, UC-24 |
| **Related BR** | BR-02, BR-09, BR-18 |
| **Status** | RESOLVED — both changes applied and mirrored |

**What this records.** Module 12 is the first module since the PHASE 0 encryption work to change a
`db/` file, so the change and its mirroring are recorded here rather than left to a diff. Two
changes, both in `db/03_procedures.sql`:

1. **New — `sp_flag_transaction`.** The three flag columns on `transactions` (`is_flagged`,
   `flag_type`, `flag_note`), `ix_txn_flagged` and the two `anomaly.*` settings have existed since
   the schema was written, but **no procedure or view read or wrote them** — UC-24 is module 12 and
   had never been built. The procedure is the write path, and it is a procedure rather than an
   application `UPDATE` for the same reason `sp_touch_recent_activity` is: the ownership check
   belongs in the database. `fk_txn_user` proves the row exists, not whose it is, so without the
   check one student could flag another's record (BR-02). `p_flag_type = 'NONE'` is the clearing
   form, so "not flagged" has exactly one representation and a stale note cannot survive an unflag.
2. **Corrected — `sp_apply_csv_batch`'s counters.** The duplicate count was derived by subtraction
   (`v_total - v_imported - v_errors`). The procedure's cursor visits only rows that were already
   `VALID`, so a row the preview had refused was never seen, and subtraction reported every such row
   as "you already recorded this". Each counter is now counted from the rows themselves, which makes
   the preview's counter and the commit's agree by construction — one definition per counter, both
   paths using it.

**The mirroring rule, stated because it is easy to miss.** `db/merged/campuscoin_full.sql` is what
`AbstractMySqlIntegrationTest` loads into the Testcontainers MySQL, so **any `db/*.sql` change must be
mirrored into the merged file in the same edit** or the suite tests a schema the deployment will not
have. Both changes above are mirrored; `git diff --stat -- db/` shows the two files moving together
and nothing else.

**No table, view, trigger or column was added.** `sp_flag_transaction` uses columns that already
existed; the `sp_apply_csv_batch` change is inside an existing body. `ddl-auto: validate` still
holds, because no entity's mapping changed.

---

## OB-017 — The `anomaly.*` thresholds are not administration-tunable

| Field | Value |
|---|---|
| **Priority** | LOW |
| **Module** | 11 — Administration (UC-23, VĐ-05), with 12 — Optional / Advanced (UC-24) |
| **Related UC** | UC-23, UC-24 |
| **Related BR** | BR-15, VĐ-05 |
| **Status** | OPEN — recorded as a deliberate boundary, awaiting a decision |

**What was found.** `sp_admin_set_threshold`'s allow-list is exactly six keys:
`budget.near_threshold_pct`, `budget.exceeded_threshold_pct`, `insight.spike_threshold_pct`,
`insight.spike_baseline_months`, `tips.max_dashboard` and `auth.reset_token_ttl_minutes`. The two
`anomaly.*` keys — `anomaly.duplicate_window_days` (UC-24's duplicate window, default 3) and
`anomaly.unusual_multiplier` (the "several times the usual" multiple, default 3) — **exist as rows in
`system_settings` and are read by the module 12 detector, but are not in that list**, so
`PATCH /api/v1/admin/settings/{key}` refuses them with `409 THRESHOLD_NOT_ADJUSTABLE`.

**Why this is a blocker and not a bug fix.** Widening the allow-list means changing
`sp_admin_set_threshold`, which is a `db/` change outside module 12's approved scope, and it would
also make module 11's documentation (whose allow-list is stated as six keys, matching "six
adjustable, ten refused") wrong. So the boundary is recorded rather than moved. Module 12 reads both
values from `system_settings` through the shared `SettingReader`, with the defaults above as
constants, so **a deployment can still set them by updating the seeded row directly** — the values
are configuration, they are simply not configuration an *administrator* can reach through the API.

**Impact.** Low. UC-24 works and is tested with the seeded values. The gap is a tuning surface: an
administrator who wants a 5-day duplicate window or a 4× unusual multiple must ask for a code or
data change. Whether that is intended — anomaly sensitivity being a deployment decision rather than
an operational one — is a project-owner question, not a code defect.

**Recommended next action.** Confirm the boundary is intended, or approve adding the two keys to
`sp_admin_set_threshold`'s allow-list and to `AdminThresholds` together, in which case module 11's
documentation changes in the same edit.

---

## Summary

| ID | Priority | Module | Status |
|---|---|---|---|
| OB-001 | HIGH | All | OPEN — source PDF and `.docx` not on disk |
| OB-002 | MEDIUM | 1 — Authentication | OPEN — no production mail provider; the SMTP notifier is now implemented and waits on credentials |
| OB-003 | MEDIUM | All | OPEN — secret store not chosen |
| OB-004 | LOW | 1 — Authentication | READY FOR REVIEW — throttle is per instance |
| OB-005 | MEDIUM | 11 — Administration | READY FOR REVIEW — DB admins bypass the admin gate; module 11 confirms every API write goes through `sp_require_admin` |
| OB-006 | LOW | 12 — Advanced | **RESOLVED** — `import_rows` ownership closed at the application layer by module 12 |
| OB-007 | LOW | Infrastructure | RESOLVED — `docker-compose.yml` translated |
| OB-008 | LOW | Cross-cutting | RESOLVED — unauthenticated `/actuator` paths closed |
| OB-009 | MEDIUM | 5 — Recurring (with 3) | READY FOR REVIEW — retired category freezes its rules |
| OB-010 | MEDIUM | 5 — Recurring | READY FOR REVIEW — a pause defers its periods instead of skipping them |
| OB-011 | MEDIUM | 6 — Budget (with 3) | READY FOR REVIEW — retired category freezes its budgets |
| OB-012 | LOW | 12 — Advanced | OPEN — `insights`/`import_rows` stay plaintext; module 12 added a live read path and scoped the work to three columns |
| OB-013 | MEDIUM | 6, 7, 8, 9, 11, 12 — every aggregation | OPEN — amounts deliberately not encrypted; modules 11 and 12 serve the plaintext aggregates |
| OB-014 | LOW | 5 — Recurring | READY FOR REVIEW — scheduler copies the rule's envelope |
| OB-015 | LOW | 10 — Bookmarks (with 12) | OPEN — the insight branch of UC-19 waits for module 12 to be unlocked |
| OB-016 | LOW | 12 — Advanced | RESOLVED — the two `db/` changes, both mirrored into the merged file |
| OB-017 | LOW | 11 / 12 | OPEN — the `anomaly.*` thresholds are not administrator-tunable |

**Two items are now external dependencies rather than work in progress:** OB-002 needs mail
credentials, and the AI provider needs `GEMINI_API_KEY`. Both features are implemented and
tested; neither can be exercised against a real service until the values are supplied.

> **Update, 2026-09-26.** The provider behind `AiSuggestionPort` was changed from Anthropic's Claude
> to **Google's Gemini** at the project owner's request: `GeminiAiSuggestionPort` replaces
> `AnthropicAiSuggestionPort`, the credential moves from `ANTHROPIC_API_KEY` to `GEMINI_API_KEY`, and
> the default model from `claude-opus-5` to `gemini-3.5-flash`. Nothing above the port changed — the
> services still depend only on `AiSuggestionPort`. The AI item above is otherwise unchanged: it
> still needs a real key before it can be exercised against the live service.

**Nothing in this list blocks modules 2–12 from proceeding.** Every item is either an external
dependency, a deployment decision, or already-solved work recorded for review. The blocker that
affects the *quality* of every module is OB-001, and it is the one most worth resolving: with the
SRS and Use Case documents available, requirements can be quoted rather than derived from the
schema.
