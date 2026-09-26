# Campus Coin — Student Expense Management System

Campus Coin lets students record their daily income and spending, set budget limits per category,
view visual reports, and receive saving tips derived from their own spending habits.

> **Current status.** The database is complete, has passed its final review, and is verified on
> MySQL 8 (clean rebuild plus 54 regression checks, all passing). It is containerised and loads
> from the merged `campuscoin_full.sql`. The Spring Boot backend has completed **modules 1–11** —
> Authentication (UC-01, UC-02, UC-03, UC-05), Profile & Preferences (UC-04, UC-27), Personal
> Categories (UC-06), Transactions (UC-07, UC-10), Recurring Expenses (UC-09), Budget &
> Notifications (UC-13, UC-14), Dashboard (UC-12), Reports & Export (UC-15, UC-16), Saving
> Tips (UC-18), Bookmarks / Notes (UC-19) and Administration (UC-20 – UC-23): those eleven modules
> are 61 endpoints and 736 tests passing against a real MySQL 8 (the totals in `docs/HANDOFF_M1_M11.md`).
>
> **Module 12 (Optional / Advanced) is implemented and tested but remains locked pending the
> project owner's approval.** It adds fifteen operations — CSV import (UC-11), AI categorisation
> (UC-08), monthly insights (UC-17), anomaly flagging (UC-24), forecast (UC-25) and recent activity
> (UC-26) — taking the backend to **76 endpoints on 56 paths**, with the whole suite at **1090
> tests passing**. The Angular frontend must **not** be wired to any of them until the project owner
> unlocks the module; see [`docs/modules/MODULE_12_ADVANCED.md`](docs/modules/MODULE_12_ADVANCED.md).
>
> Free-text fields are protected by **Application-Level Field Encryption using AES-256-GCM**: a
> transaction or recurring-rule description is written to MySQL as ciphertext, so a direct `SELECT`
> does not reveal what the student typed. Amounts are deliberately **not** encrypted — MySQL cannot
> sum ciphertext and most views aggregate amounts — and that gap is recorded rather than hidden; see
> [`docs/SECURITY.md`](docs/SECURITY.md) §12 and blockers OB-012/OB-013. The key comes from
> `CAMPUSCOIN_ENCRYPTION_KEY` and the application refuses to start without it.
>
> **The Angular frontend is still mock-only and has not yet been wired to the API.**

---

## 1. System architecture

Three tiers, as described in SRS §1.4:

| Tier | Component | Status |
|---|---|---|
| Presentation | Angular web application | In progress (mock data only) |
| Application — API | Spring Boot REST service (Java 21) | Modules 1–11 complete |
| Data | Relational database (MySQL 8) | ✅ **Complete** |

The locked stack is **Java + Spring Boot, Angular, MySQL 8, Docker**. No other backend framework
is used.

---

## 2. Database

### 2.1 Components

| Component | Count | File |
|---|---|---|
| Tables | 23 | `db/01_schema.sql` |
| Reporting views | 14 | `db/02_views.sql` |
| Procedures & functions | 25 procedures + 1 function | `db/03_procedures.sql` |
| Triggers | 14 | `db/04_triggers.sql` |
| Foreign keys | 38 | `db/01_schema.sql` |
| UNIQUE / CHECK constraints | 15 / 14 | `db/01_schema.sql` |
| Seed data | — | `db/05_seed.sql` |
| Demo data | — | `db/06_demo.sql` (optional) |
| **Merged file** | the 6 files above | `db/merged/campuscoin_full.sql` |

> **All stored content is in English:** table and column names, ENUM values, constraint names,
> procedure/function/trigger/view names, seed data (default categories, tip templates,
> notifications, setting descriptions) and every error message raised by a procedure or trigger.
> All 23 tables were scanned to confirm no non-ASCII characters remain in stored data. The
> exception is user-entered text, such as `transactions.description`, which may be in any language.

> `db/merged/campuscoin_full.sql` is the merge of all six files, with identical content, for
> loading in one step:
>
> ```bash
> mysql -u root -p --default-character-set=utf8mb4 < db/merged/campuscoin_full.sql
> ```
>
> **Why it lives in the `merged/` subdirectory:** Docker automatically runs every `.sql` file at
> the top level of `db/` during initialisation. Leaving the merged file there would build the
> whole schema twice on the first run. In a subdirectory Docker ignores it, and the file still
> sits alongside the rest of the database.

### 2.2 Table groups

| Group | Tables |
|---|---|
| Account & security | `users`, `user_sessions`, `password_reset_tokens` |
| Configuration & time | `system_settings`, `dim_month` |
| Categories | `categories` |
| Transactions | `transactions`, `transaction_history`, `recurring_rules`, `recurring_occurrences`, `category_rules`, `import_batches`, `import_rows`, `recent_activity` |
| Budgets | `budgets`, `budget_alert_log` |
| Engagement | `notifications`, `announcements`, `insights`, `tip_templates`, `user_tips`, `bookmarks` |
| Administration | `admin_audit_log` |

Design detail, the reasoning behind each decision, and the mapping to the requirements documents:
see [`docs/DB_DESIGN.md`](docs/DB_DESIGN.md) and [`docs/ERD.md`](docs/ERD.md).

---

## 3. Setup

### 3.1 Requirements

- **Docker Desktop** (20.10 or later) — the quickest route, and no manual MySQL install
- Or **MySQL 8.0 or later** to run directly on the machine
- For the backend: **JDK 21** (the wrapper `./mvnw` handles Maven itself)

### 3.2 Docker setup (recommended)

Step 1 — create the environment file from the template and fill in the passwords:

```bash
cp .env.example .env
```

Open `.env` and set `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD`. `.env` is gitignored — never
commit it.

Step 2 — start the stack:

```bash
docker compose up -d
```

On the first run (empty volume) MySQL loads **a single file**, `db/merged/campuscoin_full.sql`,
mounted at `/docker-entrypoint-initdb.d/01-campuscoin.sql`. The merged file already contains all
six parts, so one load is enough. After roughly 30–60 seconds the database is ready with all
tables, views, procedures, triggers and seed data.

Check the status:

```bash
docker compose ps
```

When `STATUS` shows `healthy`, the database is ready.

> **Later runs do not reload.** MySQL only runs the init directory when the volume is **empty**.
> Once the volume holds data, `up -d`, `stop`/`start` and `restart` all preserve it — even if the
> `.sql` file has been edited. See §3.6 to reload from scratch, and §6 for why the
> `DROP DATABASE` in the merged file is safe.

### 3.3 Setup with an existing MySQL

Without Docker, run these files in order through MySQL Workbench, DBeaver or the command line:

```bash
mysql -u root -p < db/01_schema.sql
mysql -u root -p < db/02_views.sql
mysql -u root -p < db/03_procedures.sql
mysql -u root -p < db/04_triggers.sql
mysql -u root -p < db/05_seed.sql
mysql -u root -p < db/06_demo.sql
```

> **Order is mandatory.** `01_schema.sql` creates the tables first, `02_views.sql` references them,
> `03_procedures.sql` is called by triggers in file 04, and `05_seed.sql` must run before
> `06_demo.sql`.
>
> On the command line, add `--default-character-set=utf8mb4`. All stored content is English, but
> students may enter descriptions in any language, so the character set must still be `utf8mb4`.

### 3.4 Connecting to the database

| Parameter | Value |
|---|---|
| Host | `localhost` |
| Port | `3306` |
| Database | `campuscoin` |
| Application account | `campuscoin_app` (password in `.env`) |
| MySQL administrative account | `root` (password in `.env`) |

> The API does **not** use `root`. `campuscoin_app` has privileges on the `campuscoin` database
> only (`GRANT ALL PRIVILEGES ON campuscoin.*`), not server-wide.

**JDBC URL for Spring Boot** — read from the environment, never written into source:

```
jdbc:mysql://localhost:3306/campuscoin?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true&useSSL=false
```

The backend reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER` and `DB_PASSWORD`, with the first
four defaulting to the values above and `DB_PASSWORD` having **no default**. See §3.7.

### 3.5 Data viewer

After starting the stack, open **http://localhost:8081**

| Adminer login | Value |
|---|---|
| System | MySQL |
| Server | `mysql` |
| Username | `campuscoin_app` |
| Password | the value of `MYSQL_PASSWORD` in `.env` |
| Database | `campuscoin` |

> Adminer is mapped to host port **8081**, not 8080, because the Spring Boot API uses 8080 (its
> default) and the Angular dev proxy targets that port. See the FAQ in §6.

### 3.6 Common Docker commands

```bash
docker compose up -d
```

```bash
docker compose ps
```

```bash
docker compose logs -f mysql
```

```bash
docker compose down
```

```bash
docker compose down -v
```

> `down` keeps the data. `down -v` deletes the entire volume, and the next `up -d` reloads
> `campuscoin_full.sql` from scratch — use it to rebuild a clean database, for example after
> editing a file in `db/`.

### 3.7 Running the backend

The backend is a Spring Boot service under `backend/`. It needs the database running and three
environment variables.

Step 1 — start the database (§3.2) and confirm it is `healthy`.

Step 2 — set the environment variables. There is **no default for any of the three**, and the
application refuses to start without them rather than falling back to a weak value:

| Variable | Required | Purpose |
|---|---|---|
| `JWT_SECRET` | yes | HS256 signing key, at least 32 bytes. Generate with `openssl rand -base64 48` |
| `CAMPUSCOIN_ENCRYPTION_KEY` | yes | AES-256-GCM key for application-level field encryption. Base64 of **exactly 32 random bytes** — generate with `openssl rand -base64 32` |
| `DB_PASSWORD` | yes | The `campuscoin_app` password — the same `MYSQL_PASSWORD` from `.env` |
| `DB_HOST` | no | Defaults to `localhost` |
| `DB_PORT` | no | Defaults to `3306` |
| `DB_NAME` | no | Defaults to `campuscoin` |
| `DB_USER` | no | Defaults to `campuscoin_app` |
| `CORS_ALLOWED_ORIGINS` | no | Defaults to `http://localhost:4200` |
| `RESET_LINK_BASE_URL` | no | Defaults to `http://localhost:4200/reset-password` |

> **`CAMPUSCOIN_ENCRYPTION_KEY` is a symmetric secret, not a private key.** It encrypts the
> free-text columns `transactions.description`, `recurring_rules.description` and
> `bookmarks.note`, so a direct `SELECT` on the database shows ciphertext instead of what the
> student wrote. It stays in the environment only — never in git, never in MySQL, never in a JWT,
> never sent to Angular.
>
> **Back it up, and do not lose it.** Data encrypted with one key cannot be read by a build
> configured with a different one. Losing this value makes every encrypted description and note
> permanently unreadable — there is no recovery path that does not involve the key. Do not derive
> it from `JWT_SECRET` and do not reuse an existing key from elsewhere.
>
> Amounts are **not** encrypted, because MySQL cannot sum ciphertext and twelve of the fourteen
> views read an amount. See `docs/SECURITY.md` §12 and blocker OB-013.

Step 3 — run it:

```bash
cd backend && DB_PASSWORD=<MYSQL_PASSWORD from .env> JWT_SECRET=<your secret> CAMPUSCOIN_ENCRYPTION_KEY=<your key> ./mvnw spring-boot:run
```

Or, reusing the values already in `.env` so the shared secrets are not retyped:

```bash
set -a && . ./.env && set +a && cd backend && DB_PASSWORD="$MYSQL_PASSWORD" JWT_SECRET="$JWT_SECRET" CAMPUSCOIN_ENCRYPTION_KEY="$CAMPUSCOIN_ENCRYPTION_KEY" ./mvnw spring-boot:run
```

> `.env` holds the Docker Compose variables (`MYSQL_*`), while the Spring Boot process reads
> `DB_*`. They describe the same account, so `DB_PASSWORD` is `MYSQL_PASSWORD`. `JWT_SECRET` and
> `CAMPUSCOIN_ENCRYPTION_KEY` are genuinely separate secrets; both are listed (empty) in
> `.env.example`, which is committed, so fill them there or export them from a secret manager —
> never commit real values.

The API starts on **http://localhost:8080**. Swagger UI is at
**http://localhost:8080/swagger-ui.html**.

Step 4 — verify:

```bash
curl -s http://localhost:8080/actuator/health
```

> **`ddl-auto` is `validate` in every profile.** Hibernate checks that the JPA entities match the
> existing schema and never creates or alters it: `create`, `update` and `create-drop` are
> forbidden for this project, because `campuscoin_full.sql` is the single source of truth. If the
> application fails to start with a schema-validation error, the entities and the database have
> drifted — fix the mapping, not the schema.

#### Running the tests

```bash
cd backend && ./mvnw test
```

The tests start their own MySQL 8 container via Testcontainers and load the real
`db/merged/campuscoin_full.sql` into it, so the development database is never touched and no
running stack is required. Docker must be available. See
[`docs/api/authentication.md`](docs/api/authentication.md),
[`docs/api/profile.md`](docs/api/profile.md),
[`docs/api/categories.md`](docs/api/categories.md),
[`docs/api/FRONTEND_API_GUIDE.md`](docs/api/FRONTEND_API_GUIDE.md) (the entry point for a frontend
developer), [`docs/api/transactions.md`](docs/api/transactions.md),
[`docs/api/recurring.md`](docs/api/recurring.md),
[`docs/api/budgets.md`](docs/api/budgets.md),
[`docs/api/notifications.md`](docs/api/notifications.md),
[`docs/api/dashboard.md`](docs/api/dashboard.md),
[`docs/api/reports.md`](docs/api/reports.md),
[`docs/api/tips.md`](docs/api/tips.md),
[`docs/api/bookmarks.md`](docs/api/bookmarks.md) and
[`docs/api/administration.md`](docs/api/administration.md) for the API contracts, and
[`docs/SECURITY.md`](docs/SECURITY.md) for the security decisions. The endpoint list is
[`docs/api/API_INVENTORY.md`](docs/api/API_INVENTORY.md).

#### Password reset in development

There is no mail provider. The reset link is appended to
`backend/target/password-reset-dev.log` instead, and the raw token is **never** written to the
application log. Open the link from that file to complete a reset. In production the notifier is a
stub that sends nothing — a real provider must be wired in first (§Security).

---

## 4. Demo credentials

SRS §1.9 requires credentials for **every user type**:

| User type | Display name | Email | Password |
|---|---|---|---|
| Administrator | System Administrator | `admin@campuscoin.edu` | `Admin@123` |
| Student | Alex Nguyen | `an.nguyen@student.campuscoin.edu` | `Student@123` |
| Student | Bella Tran | `binh.tran@student.campuscoin.edu` | `Student@123` |

Passwords are stored in the database as bcrypt hashes (cost 10), never as plain text (BR-01).

> ⚠ **Change every one of these passwords before any real deployment.**

Detail: [`docs/CREDENTIALS.md`](docs/CREDENTIALS.md)

---

## 5. Directory layout

```
campus-coin/
├── db/                             # The entire database
│   ├── 01_schema.sql               # 23 tables, foreign keys, indexes, CHECK
│   ├── 02_views.sql                # 14 reporting views
│   ├── 03_procedures.sql           # 25 procedures + 1 function
│   ├── 04_triggers.sql             # 14 triggers enforcing business rules
│   ├── 05_seed.sql                 # Settings, accounts, categories, tip templates
│   ├── 06_demo.sql                 # Demo data (optional)
│   └── merged/
│       └── campuscoin_full.sql     # Merge of the six files above (load once)
│
├── docs/
│   ├── DB_DESIGN.md                # Detailed design & UC/BR mapping
│   ├── ERD.md                      # Entity-relationship diagram
│   ├── CREDENTIALS.md              # Demo accounts
│   ├── REVIEW_CHECKLIST.md         # Final review mapped back to the SQL
│   ├── SECURITY.md                 # Security decisions and deployment requirements
│   ├── OVERNIGHT_BLOCKERS.md       # Decisions awaiting the project owner's input
│   ├── HANDOFF_M1_M11.md           # M1–M11 release gate + handoff output
│   ├── api/
│   │   ├── FRONTEND_API_GUIDE.md   # START HERE: base URL, auth, interceptors, errors, enums, all 76 ops
│   │   ├── API_INVENTORY.md        # Every endpoint, with its use case
│   │   ├── authentication.md       # Module 1 contract + Angular integration
│   │   ├── profile.md              # Module 2 contract + Angular integration
│   │   ├── categories.md           # Module 3 contract + Angular integration
│   │   ├── transactions.md         # Module 4 contract + Angular integration
│   │   ├── recurring.md            # Module 5 contract + the scheduler
│   │   ├── budgets.md              # Module 6 contract: limits & consumption (UC-13)
│   │   ├── notifications.md        # Module 6 contract: budget alerts (UC-14)
│   │   ├── dashboard.md            # Module 7 contract: the home screen (UC-12)
│   │   ├── reports.md              # Module 8 contract: reports & export (UC-15, UC-16)
│   │   ├── tips.md                 # Module 9 contract: saving tips (UC-18)
│   │   ├── bookmarks.md            # Module 10 contract: bookmarks & notes (UC-19)
│   │   ├── administration.md       # Module 11 contract: the admin surface (UC-20 – UC-23)
│   │   ├── imports.md              # Module 12 (locked): CSV import (UC-11)
│   │   ├── ai-and-insights.md      # Module 12 (locked): AI categorisation & insights (UC-08, UC-17)
│   │   └── advanced.md             # Module 12 (locked): anomalies, forecast, activity (UC-24–UC-26)
│   ├── modules/
│   │   ├── MODULE_02_PROFILE.md        # Module report: tests, reviews, traceability
│   │   ├── MODULE_03_CATEGORIES.md     # Module report
│   │   ├── MODULE_04_TRANSACTIONS.md   # Module report
│   │   ├── MODULE_05_RECURRING.md      # Module report
│   │   ├── MODULE_06_BUDGET.md         # Module report
│   │   ├── MODULE_07_DASHBOARD.md      # Module report
│   │   ├── MODULE_08_REPORTS.md        # Module report
│   │   ├── MODULE_09_TIPS.md           # Module report
│   │   ├── MODULE_10_BOOKMARKS.md      # Module report
│   │   ├── MODULE_11_ADMINISTRATION.md # Module report
│   │   └── MODULE_12_ADVANCED.md       # Module report (built, locked pending approval)
│   └── testing/
│       └── manual/                 # Hand-run test procedures, one per module
│
├── backend/                        # Spring Boot API (Java 21)
│   └── src/main/java/com/campuscoin/
│       ├── auth/                   # Module 1: controller, service, repository, entity, dto, security
│       ├── profile/                # Module 2: profile & preferences (UC-04, UC-27)
│       ├── category/               # Module 3: personal categories (UC-06)
│       ├── transaction/            # Module 4: recording & managing transactions (UC-07, UC-10)
│       ├── recurring/              # Module 5: recurring expenses (UC-09)
│       ├── budget/                 # Module 6: limits, consumption & notifications (UC-13, UC-14)
│       ├── dashboard/              # Module 7: the home screen (UC-12)
│       ├── reports/                # Module 8: monthly report & spending series (UC-15, UC-16)
│       ├── tips/                   # Module 9: saving tips (UC-18)
│       ├── bookmark/               # Module 10: bookmarks / notes (UC-19)
│       ├── admin/                  # Module 11: administration (UC-20 – UC-23)
│       ├── imports/                # Module 12 (locked): CSV import (UC-11)
│       ├── categorisation/         # Module 12 (locked): AI suggestions & rule learning (UC-08)
│       ├── insight/                # Module 12 (locked): monthly insights (UC-17)
│       ├── anomaly/                # Module 12 (locked): duplicate/unusual flagging (UC-24)
│       ├── forecast/               # Module 12 (locked): the next month's projection (UC-25)
│       ├── recent/                 # Module 12 (locked): recent activity (UC-26)
│       └── common/                 # Errors, configuration, settings
├── frontend/                       # Angular web application (not yet wired to the API)
├── docker-compose.yml              # MySQL 8 + Adminer
├── .env.example                    # Environment template (committed)
└── .env                            # Real passwords (NOT committed — gitignored)
```

---

## 6. FAQ

**How do I reload the database from scratch?**
```bash
docker compose down -v && docker compose up -d
```

**I edited a SQL file — now what?**
MySQL only runs the init directory on first initialisation, when the volume is empty. After
editing `db/merged/campuscoin_full.sql`, reload with
`docker compose down -v && docker compose up -d`.

**Why is `DROP DATABASE IF EXISTS campuscoin` in the merged file not dangerous?**
Because that file runs exactly once — during initialisation on an empty volume. Later `up -d`,
`stop`/`start` and `restart` do not re-run the init directory, so the statement never touches
existing data. It only guarantees that the first build always starts from a clean state.

**What if port 3306 or 8081 is already in use?**
Edit the `"3306:3306"` or `"8081:8080"` line in `docker-compose.yml` to a different port, for
example `"3406:3306"`, then run `docker compose up -d` again and update the port in the connection
settings in §3.4.

**Why is the API on 8080 and Adminer on 8081?**
The Angular dev proxy (`frontend/proxy.conf.json`) and `environment.development.ts` both target
`http://localhost:8080/api`, which is also Spring Boot's default port. Adminer therefore moved to
8081, because the API is what the frontend depends on.

**How do I remove the demo data and start from zero?**
Delete the 31 transactions and their related rows from `06_demo.sql`, or load only as far as
`05_seed.sql`. The cleanest route is to remove `06_demo.sql` from `db/` and reload from scratch.

**The backend fails to start with a schema-validation error.**
That is `ddl-auto=validate` doing its job: an entity no longer matches the database. Fix the entity
mapping. Do not switch `ddl-auto` to `update` — the schema is owned by `campuscoin_full.sql`.

---

## 7. AI Tools, Mascot & 3D Assets Attribution

- **Squirrel Mascot Animation**: Sourced vector motion asset with multi-state state machine (Idle, Walk, Deposit, Coin-Flip, Analyzing, Sleepy, Guest Roaming, Chat-Open) and interactive speech bubble, integrated via `lottie-web` under the **Lottie Simple License** (LottieFiles Community).
- **3D Falling Gold Coins Physics**: Interactive WebGL PBR rendering powered by `three` (Three.js r186, MIT License) and WebAssembly rigid-body physics via `@dimforge/rapier3d-compat` (Rapier 3D, Apache-2.0 License).
- **UI Iconography**: Standard functional UI icons powered by `lucide-angular` (ISC License) with custom bespoke vector squirrel brand identity marks (`squirrel-logo`, `favicon.svg`).
- **Production Performance**: Lazy-loaded heavy modules (`@defer (on idle)`), OnPush change detection, tree-shaking, and WebP asset optimization.
