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
| **Status** | OPEN |

**Blocked task.** Wiring an actual outbound mail provider so a reset link reaches the account
owner in production.

**Why it is blocked.** No provider, credentials, sender address or SMTP/API configuration has been
supplied, and inventing credentials is forbidden. This is an external-service dependency.

**Required input from me.** The provider and its credentials — SMTP host/port/user/password, or an
API key for a transactional mail service — plus the verified sender address.

**Current safe state.** The delivery mechanism is a port: `PasswordResetNotifier` with two
implementations. Development uses `FilePasswordResetNotifier` (writes the link under
`backend/target/`, gitignored; never to the log). Production uses `NoopPasswordResetNotifier`,
which warns and sends nothing rather than writing a reset token to disk on a server. UC-03's
generic response is identical in both cases, so account enumeration is not possible either way.

**Temporary action.** Implemented and tested against the file sink. Adding a real provider means
adding one bean; no UC-03 code changes.

**Impact.** Password reset cannot work in production until this is resolved. **The service must
not be treated as production-ready for UC-03.**

**Suggested options.** Implement a `SmtpPasswordResetNotifier` once credentials exist, or wire a
transactional mail API client. Either is a single new class behind the existing port.

**Recommended next action.** Supply provider credentials, or confirm the deployment will add the
implementation itself.

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
| **Status** | OPEN |

**Blocked task.** Adding a trigger to reject an `import_rows.ai_suggested_category_id` that
belongs to another student.

**Why it is blocked.** This is an acknowledged remaining gap, recorded in `DB_DESIGN.md` §4.8. The
fix would be a new `BEFORE INSERT/UPDATE` trigger on `import_rows`. Adding a trigger is a schema
change, which section 2 forbids without explicit approval.

**Required input from me.** Approval to add the trigger, or a decision to handle it entirely in
the application layer when UC-11 is implemented.

**Current safe state.** The column is written only by the application during the UC-11 preview
step. No procedure, trigger or view reads it back, and `sp_apply_csv_batch` — the real write path
for CSV import — **does** verify ownership. The application layer can therefore reject an invalid
value before it is stored, and this is the plan for module 12.

**Temporary action.** Deferred to module 12, where UC-11 is implemented. The application will
validate ownership before writing the column.

**Impact.** Low. Exploiting it requires writing the column directly, and the value has no effect
on any transaction or report.

**Suggested options.** (a) Application-side validation in module 12 — no schema change, sufficient
because the column is application-only. (b) A trigger matching
`trg_bookmarks_before_insert/update`, if defence in depth is wanted.

**Recommended next action.** Confirm option (a) is sufficient.

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

## Summary

| ID | Priority | Module | Status |
|---|---|---|---|
| OB-001 | HIGH | All | OPEN — source PDF and `.docx` not on disk |
| OB-002 | MEDIUM | 1 — Authentication | OPEN — no production mail provider |
| OB-003 | MEDIUM | All | OPEN — secret store not chosen |
| OB-004 | LOW | 1 — Authentication | READY FOR REVIEW — throttle is per instance |
| OB-005 | MEDIUM | 11 — Administration | READY FOR REVIEW — DB admins bypass the admin gate |
| OB-006 | LOW | 12 — Advanced | OPEN — `import_rows` ownership check at app layer |
| OB-007 | LOW | Infrastructure | RESOLVED — `docker-compose.yml` translated |
| OB-008 | LOW | Cross-cutting | RESOLVED — unauthenticated `/actuator` paths closed |
| OB-009 | MEDIUM | 5 — Recurring (with 3) | READY FOR REVIEW — retired category freezes its rules |
| OB-010 | MEDIUM | 5 — Recurring | READY FOR REVIEW — a pause defers its periods instead of skipping them |
| OB-011 | MEDIUM | 6 — Budget (with 3) | READY FOR REVIEW — retired category freezes its budgets |

**Nothing in this list blocks modules 2–12 from proceeding.** Every item is either an external
dependency, a deployment decision, or already-solved work recorded for review. The blocker that
affects the *quality* of every module is OB-001, and it is the one most worth resolving: with the
SRS and Use Case documents available, requirements can be quoted rather than derived from the
schema.
