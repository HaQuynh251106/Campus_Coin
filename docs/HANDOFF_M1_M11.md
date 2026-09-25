# M1–M11 Final Handoff

**Produced by:** the §32 FINAL M1–M11 RELEASE GATE and §33 FINAL M1–M11 HANDOFF OUTPUT.
**Date:** 2026-09-25.
**Scope:** modules 1–11 of the Spring Boot backend. **Module 12 is not included and is not started.**

---

## 1. Module status

| Module | Scope | Use cases | Status |
|---|---|---|---|
| M1 | Authentication & sessions | UC-01, UC-02, UC-03, UC-05 | COMPLETE |
| M2 | Profile & preferences | UC-04, UC-27 | COMPLETE |
| M3 | Personal categories | UC-06 | COMPLETE |
| M4 | Transactions | UC-07, UC-10 | COMPLETE |
| M5 | Recurring expenses | UC-09 | COMPLETE |
| M6 | Budget & notifications | UC-13, UC-14 | COMPLETE |
| M7 | Dashboard | UC-12 | COMPLETE |
| M8 | Reports & export | UC-15, UC-16 | COMPLETE |
| M9 | Saving tips | UC-18 | COMPLETE |
| M10 | Bookmarks / notes | UC-19 | COMPLETE |
| M11 | Administration | UC-20, UC-21, UC-22, UC-23 | COMPLETE |

**Use cases deliberately not in M1–M11:** UC-08 (AI categorisation), UC-11 (CSV import),
UC-17 (monthly insights), UC-24 (anomaly flagging) and UC-25's trend-alert surface belong to the
locked module 12. They have no route, no settings key and no Java type in this build.

---

## 2. §32 release gate — 22 review items

| # | Review item | Result | Evidence |
|---|---|---|---|
| 1 | M1–M11 status table | PASS | §1 above; `docs/modules/MODULE_02…11_*.md` |
| 2 | UC coverage | PASS | UC-01…UC-23 except the five module-12 use cases listed above |
| 3 | BR coverage | PASS | `docs/DB_DESIGN.md` BR mapping; `TipsRuleCoverageIT`, `SecurityHardeningIT` |
| 4 | Endpoint inventory | PASS | `docs/api/API_INVENTORY.md` — 61 rows, numbered 1–61 |
| 5 | OpenAPI contract verification | PASS | live document 43 paths / 61 operations; `OpenApiContractIT` 14 tests, `hasSize(43)` |
| 6 | Security review | PASS | `docs/SECURITY.md`; `SecurityHardeningIT`; `AdminSecurityIT` |
| 7 | Ownership review | PASS | every module scopes reads by the caller; M10's not-yours / not-found policy pinned by test |
| 8 | Role review | PASS | `AdminSecurityIT` sweeps all 16 admin routes: student → 403, no token → 401 |
| 9 | Database regression | PASS | 23 tables, 14 views, 24 procedures + 1 function, 14 triggers, 38 FKs, 15 UNIQUE — unchanged |
| 10 | Full automated test suite | PASS | **736 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (fresh `clean test`) |
| 11 | Cross-module regression | PASS | one shared Testcontainers MySQL; whole suite green in one JVM run |
| 12 | Recurring scheduler regression | PASS | `RecurringScheduler*` suite; live override to a minute cron posted a `source='RECURRING'` row |
| 13 | Budget / alert regression | PASS | `budget` bucket 77 tests; live flow wrote `budget_alert_log` NEAR + EXCEEDED and a `BUDGET_EXCEEDED` notification |
| 14 | Report regression | PASS | `reports` bucket 40 tests; live `GET /reports?month=yyyy-MM` |
| 15 | Admin regression | PASS | `admin` bucket 167 tests; live flow 4 end to end |
| 16 | Docker build | PASS | DB stack builds from `db/merged/campuscoin_full.sql`; **the project has no backend Dockerfile by design — "Docker" here means the MySQL 8 + Adminer stack** |
| 17 | Docker compose startup | PASS | `docker compose down -v && up -d`; `campuscoin-mysql` healthy, `campuscoin-adminer` up |
| 18 | Environment-variable verification | PASS | `.env` gitignored and untracked; every secret in `.env.example` is empty; no hard-coded key in source |
| 19 | Health / actuator review | PASS | `/actuator/health` UP; `/actuator/metrics` 401 unauthenticated (OB-008 closed) |
| 20 | Blocker review | PASS | `docs/OVERNIGHT_BLOCKERS.md` — 15 entries, all OPEN / READY FOR REVIEW / RESOLVED, none silent |
| 21 | Documentation synchronization | PASS | README, API inventory, per-module docs, manual test procedures all updated; test-name audit found **0 stale or misfiled citations** |
| 22 | Manual UAT checklist alignment | PASS | `docs/testing/manual/` — one procedure per module, `${JWT}`-style placeholders only |

### Required business flows

| Flow | Path exercised | Result |
|---|---|---|
| FLOW 1 | register → login → profile → preferences → categories → transaction → dashboard | **PASS** |
| FLOW 2 | transaction → budget → alert → report | **PASS** |
| FLOW 3 | recurring rule → scheduler → generated transaction → budget → alert → report | **PASS** |
| FLOW 4 | admin → manage category → manage user → disable student → session revoked | **PASS** |
| FLOW 5 | dashboard → tips → pin / dismiss → bookmark | **PASS** |

Flow 5 note: the tip state enum is `NEW | PINNED | DISMISSED` — there is no `SKIPPED`, and dismissal
is one-way by design (`TipService`). Pin, dismiss, the refusal to leave `DISMISSED`, bookmark create,
note update and bookmark delete were each exercised against the live stack.

All five flows ran against a backend started from the packaged jar with `ddl-auto=validate`, which
also proves the one new entity (`Announcement`) matches the real table and that no schema change was
attempted.

---

## 3. Handoff output

```
M1  STATUS: COMPLETE
M2  STATUS: COMPLETE
M3  STATUS: COMPLETE
M4  STATUS: COMPLETE
M5  STATUS: COMPLETE
M6  STATUS: COMPLETE
M7  STATUS: COMPLETE
M8  STATUS: COMPLETE
M9  STATUS: COMPLETE
M10 STATUS: COMPLETE
M11 STATUS: COMPLETE

M1–M11 FULL REGRESSION : PASS   (736 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS)
OPENAPI                : PASS   (43 paths, 61 operations; live document matches the inventory)
SECURITY               : PASS   (AES-256-GCM field encryption; JWT; role/ownership sweeps green)
DATABASE               : PASS   (23 tables / 14 views / 24 procedures + 1 function / 14 triggers)
DOCKER                 : PASS   (MySQL 8 + Adminer stack healthy; no backend image by design)
CROSS-MODULE           : PASS   (single shared Testcontainers MySQL, whole suite green)

KNOWN BLOCKERS   : 13 open or awaiting review — OB-001, 002, 003, 004, 005, 006, 009, 010, 011, 012, 013, 014, 015 (OB-007, OB-008 resolved)
KNOWN ASSUMPTIONS: source PDF/.docx absent (OB-001); amounts stay plaintext (OB-013);
                   `insight.*` keys are adjustable because module 9 already ships spike detection;
                   announcement content is create-once, only `isActive` is editable
DEFERRED ITEMS   : OB-012 (M12 surfaces stay plaintext); OB-013 (amount encryption);
                   OB-015 (the insight branch of UC-19); `tip_templates.condition_params` not exposed
FILES CHANGED    : 39 tracked files (+1970 / −133) and 18 new paths;
                   new `com.campuscoin.admin` package (52 main + 9 test classes),
                   `com.campuscoin.common.crypto` (3 classes),
                   `docs/api/administration.md`, `docs/modules/MODULE_11_ADMINISTRATION.md`,
                   `docs/testing/manual/MODULE_11_MANUAL_TEST.md`
NEXT ACTION      : WAITING FOR EXPLICIT PROJECT-OWNER APPROVAL FOR M12
FINAL STATUS     : M1–M11 IMPLEMENTATION COMPLETE / M12 LOCKED / WAITING FOR PROJECT-OWNER APPROVAL
```

---

## 4. Blockers carried into the handoff

| ID | Severity | Area | State |
|---|---|---|---|
| OB-001 | HIGH | All | OPEN — authoritative source documents not on disk |
| OB-002 | MEDIUM | M1 | OPEN — no production email provider for password reset |
| OB-003 | MEDIUM | All | OPEN — production secret store not chosen |
| OB-004 | LOW | M1 | READY FOR REVIEW — throttle counters are per instance |
| OB-005 | MEDIUM | M11 | READY FOR REVIEW — DB admins bypass the admin gate; M11 confirms every **API** write goes through `sp_require_admin` |
| OB-006 | LOW | M12 | OPEN — `import_rows` ownership check is at the application layer |
| OB-009 | MEDIUM | M5 | READY FOR REVIEW — retiring a category freezes its recurring rules |
| OB-010 | MEDIUM | M5 | READY FOR REVIEW — pausing a recurring rule defers its periods |
| OB-011 | MEDIUM | M6 | READY FOR REVIEW — retiring a category freezes its budgets |
| OB-012 | LOW | M12 | OPEN — values written by the locked M12 surface stay plaintext |
| OB-013 | MEDIUM | M6–M9, M11 | OPEN — amounts deliberately not encrypted; M11 serves the plaintext aggregates |
| OB-014 | LOW | M5 | READY FOR REVIEW — the scheduler copies the rule's envelope into the posted transaction |
| OB-015 | LOW | M10 | OPEN — the insight branch of UC-19 waits for module 12 |

OB-007 and OB-008 are RESOLVED.

---

## 5. Encryption posture (unchanged by this gate)

Sensitive free text is protected by **Application-Level Field Encryption using AES-256-GCM**: a fresh
random nonce per operation, the authentication tag verified on decrypt, the key from
`CAMPUSCOIN_ENCRYPTION_KEY` and nowhere else — never in git, never in MySQL, never in a JWT, never in
Angular. Startup fails clearly when the key is missing or invalid.

Encrypted: `transactions.description`, `recurring_rules.description`, `transaction_history`
old/new values, `bookmarks.note`.

Deliberately **not** encrypted: every amount. MySQL cannot sum ciphertext and most views aggregate an
amount, so the choice was to keep the aggregates working and record the exposure (OB-013) rather than
hide it. No plaintext shadow column was introduced to preserve a query.

This is **not** "database encryption". The database stores ciphertext for these columns; the
encryption and decryption happen in the application.

---

## 6. How to re-verify

```bash
cd backend && ./mvnw clean test
```

The tests start their own MySQL 8 container and load `db/merged/campuscoin_full.sql`, so the
development database is untouched. Docker must be available.

**Database state left behind:** the Docker volume was rebuilt from scratch after the live-flow
verification, so the running database is the pristine seed — 3 users, 31 transactions, 31 history
rows, 2 recurring rules, 0 audit rows, and the seeded administrator `ACTIVE`.

---

*M1–M11 IMPLEMENTATION COMPLETE — M12 LOCKED PENDING PROJECT-OWNER APPROVAL*
