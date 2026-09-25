# Campus Coin — Security

The security decisions behind the authentication module, and what a deployer must still do.

Each section states what the system does, **where it is enforced**, and what remains an
operational responsibility. Enforcing a rule in one place only — ideally the database or the
security filter chain — is a deliberate choice throughout: a rule written twice drifts, and the
weaker copy tends to be the one that ends up protecting the data.

## Contents

1. [Transport security (HTTPS/TLS)](#1-transport-security-httpstls)
2. [Spring Security configuration](#2-spring-security-configuration)
3. [JWT handling](#3-jwt-handling)
4. [Password hashing](#4-password-hashing)
5. [Reset-token hashing and lifecycle](#5-reset-token-hashing-and-lifecycle)
6. [Role authorisation](#6-role-authorisation)
7. [Sensitive data in logs](#7-sensitive-data-in-logs)
8. [CORS](#8-cors)
9. [Secrets and configuration](#9-secrets-and-configuration)
10. [401 vs 403, and error disclosure](#10-401-vs-403-and-error-disclosure)
11. [Brute-force and flood protection](#11-brute-force-and-flood-protection)
12. [No field-level encryption](#12-no-field-level-encryption)

---

## 1. Transport security (HTTPS/TLS)

**Not enforced by the application.** `server.ssl.*` is unset, so the service speaks plain HTTP.
That is correct for local development and **unsafe for production**: without TLS, every password,
access token and reset token crosses the network in clear text and can be read or modified by
anyone on the path.

TLS is a deployment responsibility, and the recommended arrangement is to terminate it at a
reverse proxy (nginx, Caddy, a cloud load balancer) in front of this service, so certificates and
renewal are handled by software built for it:

```
Angular (https://app.example.com)  →  TLS termination  →  Spring Boot (http://127.0.0.1:8080)
```

Deployment requirements:

- Serve the API and the frontend over HTTPS only, with a valid certificate.
- Redirect HTTP to HTTPS, or refuse HTTP outright.
- Set `CORS_ALLOWED_ORIGINS` to the real HTTPS origin — not `http://localhost:4200`.
- Do not expose port 3306 or 8081 publicly.
- Run MySQL with `useSSL=true` and a verified server certificate if the database is on another
  host; the development configuration uses `useSSL=false` because both ends are on localhost.

**Encrypting the request body instead of using TLS is not an acceptable substitute** and is not
done here. Application-level encryption of a JSON payload leaves headers and metadata visible,
provides no integrity guarantee, requires key distribution to the browser, and would not fix the
underlying need for a secure channel.

---

## 2. Spring Security configuration

`auth/security/SecurityConfig.java` defines the one filter chain.

| Setting | Value | Why |
|---------|-------|-----|
| `csrf` | **disabled** | See below |
| `cors` | from `CorsConfig`, an explicit origin list | §8 of this document |
| `sessionManagement` | `STATELESS` | The server keeps no HTTP session; identity comes from the token on every request. A session cookie would reintroduce CSRF exposure |
| `httpBasic` / `formLogin` | disabled | There is no browsable login page and no Basic auth; leaving either on would add an authentication path nobody reviewed |
| Public endpoints | The 6 public `POST` paths | An explicit allow-list, not a pattern |
| Admin routes | `/api/v1/admin/**` requires `ROLE_ADMIN` | UC-05 E1, enforced before the controller |
| Everything else | `/api/**` requires authentication | A new endpoint is protected by default; forgetting an annotation is not a hole |
| API docs, health | `GET /api-docs/**`, `/swagger-ui/**`, `/actuator/health`, `/actuator/info` permitted | An explicit allow-list, so browsing Swagger and a readiness probe work without a token |
| Other actuator endpoints | `/actuator/**` requires authentication | `metrics` is the concrete case: it was reachable through the catch-all until this rule was added. Only the paths named above are public, and everything else under `/actuator` is now closed rather than quietly readable |

### Why CSRF protection is disabled

CSRF attacks work by making a browser attach credentials **the browser stores and sends
automatically** — a session cookie or Basic auth header — to a request the user did not intend.
This API issues none of those:

- Authentication is a JWT in an `Authorization` header, set by JavaScript. A browser does not
  attach it to a cross-site request, and a cross-site form cannot set that header at all.
- `SessionCreationPolicy.STATELESS` means Spring Security never creates a session, so there is no
  `JSESSIONID` to ride.
- No authentication path reads a cookie.

A CSRF token would therefore defend against an attack that cannot occur, at the cost of a
stateful handshake the API has deliberately avoided. **This reasoning depends on both facts
holding.** If a cookie-based session, a cookie-stored token, or `httpBasic`/`formLogin` is ever
introduced, CSRF protection must be enabled in the same change.

Relevant reading: [OWASP CSRF](https://owasp.org/www-community/attacks/csrf),
[Spring Security CSRF guidance](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html).

---

## 3. JWT handling

| Property | Value |
|----------|-------|
| Algorithm | HS256 (HMAC-SHA-256), symmetric |
| Library | JJWT 0.12.6 |
| Secret | `JWT_SECRET` environment variable — **no default in source** |
| Minimum strength | Enforced by JJWT: a key under 256 bits throws at start-up, so a weak secret fails fast rather than silently |
| Issuer | `campus-coin`; verified with `requireIssuer` so a token minted for another service is rejected |
| Lifetime | `system_settings.auth.session_ttl_minutes`, seeded at 120 minutes |
| Claims | `sub` (account id), `email`, `role`, `tv` (token version), `jti`, `iat`, `exp` |

**Revocation, not just expiry.** A JWT cannot be un-issued, so expiry alone would leave a stolen
token usable until it lapses. Instead:

- Sign-in inserts a row in `user_sessions` holding the token's **SHA-256 hash**, its expiry, and
  the client's address and user agent.
- Every authenticated request looks that row up, and rejects the request if the session is revoked
  or expired.
- The `tv` claim is compared against `users.token_version` on every request. That column is
  incremented by the database on a password reset (`sp_complete_password_reset`) and on an account
  disable (`sp_set_user_status`), which invalidates every token issued before it — immediately,
  and across all devices.

The token's own expiry and the session row's `expires_at` are derived from the same setting, so
they cannot disagree.

**What is deliberately not done:** no refresh token. `user_sessions.refresh_token_hash` exists in
the schema, but no use case defines a refresh flow, so the column stays `NULL` and no refresh
endpoint exists. Implementing one to match a column would create an unreviewed authentication
path.

**Verification failures are not distinguished.** An expired, tampered, malformed or wrongly signed
token all produce the same 401 `UNAUTHENTICATED`. Telling the caller *why* a token failed helps
an attacker and no legitimate client.

---

## 4. Password hashing

| Property | Value |
|----------|-------|
| Algorithm | bcrypt |
| Implementation | Spring Security `BCryptPasswordEncoder` |
| Cost | 10 (the default) |
| Where | `SecurityConfig.passwordEncoder()` bean, used by `AuthService` and `PasswordResetService` |
| Storage | `users.password_hash` |

- Passwords are hashed before they reach the entity. The plain value exists only in the request
  body and the local variable that hashes it.
- The plain password is never logged, never returned, and never included in an exception message —
  including the "passwords do not match" case, which reports the field, not the values.
- bcrypt salts implicitly (each hash carries its own salt), so two students with the same password
  have different hashes.
- Cost 10 is the Spring Security default — a deliberate balance for a university project. Raise it
  to 12 if the hardware allows; re-hashing on next sign-in is required to migrate existing hashes.
- A 72-byte limit is enforced at registration and reset because bcrypt ignores input beyond it;
  silently truncating a long password would make it weaker than the user believes.
- **Argon2** is a reasonable alternative (Spring Security ships `Argon2PasswordEncoder`). bcrypt
  was chosen because it needs no extra dependency here.

The seeded accounts in `db/05_seed.sql` use `$2y$10$` hashes. `BCryptPasswordEncoder` accepts the
`$2y$` variant unchanged, which is verified by a test — otherwise the demo accounts would be
unusable.

**Not done, deliberately:** no password history, no forced expiry, no complexity meter beyond the
documented rule, no breach-list check. None is required by a use case, and each adds failure modes
of its own. Password strength rules live in the request DTOs so they appear in the Swagger schema
and in the field errors the client renders.

---

## 5. Reset-token hashing and lifecycle

The reset flow's rules live **in the database**, in three stored procedures, so there is one
definition of when a token is usable:

| Procedure | Role |
|-----------|------|
| `sp_create_password_reset_token` | Issues a token hash, marks the account's earlier unused tokens used, sets `expires_at` from `auth.reset_token_ttl_minutes` (30) |
| `sp_verify_password_reset_token` | Read-only check of existence, unused and unexpired. Does not consume |
| `sp_complete_password_reset` | Validates **and** consumes in one `UPDATE`, sets the new hash, bumps `token_version`, revokes every open session, and `SIGNAL`s (SQLSTATE 45000) if nothing matched |

| Property | Value |
|----------|-------|
| Token generation | 32 cryptographically random bytes, URL-safe Base64 (`SecureRandom`) |
| Stored value | **SHA-256 hex only** — the raw token is never written to the database |
| Lifetime | 30 minutes |
| Uses | Exactly one |

**Why the raw token is never stored.** `password_reset_tokens.token_hash` holds a hash, so a
database dump yields nothing that can be replayed as a link. The raw value exists only inside
`PasswordResetService.requestReset` and in the link delivered to the account owner.

**One-time use is atomic.** `sp_complete_password_reset` proves the token usable and marks it used
in a single `UPDATE`. Two requests arriving together cannot both succeed: InnoDB locks the row, the
second caller re-reads the committed version, matches nothing, and is refused. Splitting "check"
and "consume" into two statements would introduce a race that produces two password resets from
one link.

**Delivery.** The link is written to `backend/target/password-reset-dev.log` by the development
`FilePasswordResetNotifier`. **Neither the link nor the recipient address is logged** — the link
contains the token, and the address would reveal which accounts exist. The file is gitignored and
must never be shipped. Production uses `NoopPasswordResetNotifier`, a stub that logs a warning
without the link; **a real mail provider must be wired in before deployment**, otherwise resets
silently do nothing.

**Account enumeration is prevented from both directions:** the request endpoint returns the same
message for a known and an unknown address (UC-03 B3, A2), and the throttle is keyed on the
submitted address whether or not it exists. A difference in either would let an attacker discover
registered addresses.

---

## 6. Role authorisation

| Role | How it is enforced |
|------|--------------------|
| `STUDENT` | Default for self-registration. **Cannot be chosen by the client**: `RegisterRequest` has no `role` field |
| `ADMIN` | The only role that may reach `/api/v1/admin/**`; granted by the administrator module, never by self-registration |

Three separate gates, all server-side:

1. **The route.** `/api/v1/admin/**` requires `ROLE_ADMIN` in the filter chain, which runs before
   the controller. An unmapped admin path is still refused, so the rule does not depend on a
   controller existing.
2. **The portal.** `POST /api/v1/admin/auth/login` passes `UserRole.ADMIN` as the expected role, so
   a student account with **correct** credentials is refused with 403 (UC-05 A1).
3. **The token's authority.** The filter grants `ROLE_<role>` from the `role` claim of a
   **verified** token, cross-checked against the account's current `users.role` on every request.
   A client cannot put a role in a request body and be believed — registration discards extra
   fields, and identity comes from the token, never a `userId`.

The role check in the service happens **after** the password check. If it came first, the
administrator portal would tell an anonymous caller that an address exists and is an admin.

**The frontend guard is not a security boundary.** Route guards only shape the UI. A user who
bypasses a guard still cannot read or change administrator data, because the API refuses the call.
Verified by test: a student token on an admin route gets 403, and the same route without a token
gets 401.

---

## 7. Sensitive data in logs

**Never logged:** passwords, JWTs, refresh tokens, reset tokens, session tokens, `password_hash`
values, `Authorization` headers, and any complete request body from a sign-in or reset call.

| Location | What is logged |
|----------|----------------|
| `AuthService.login` | `userId` and `role` of a successful sign-in — no credentials, no token |
| `AuthService.register` | The new `userId` — no email, no password |
| `PasswordResetService` | `userId` on issue, and a completion line with neither id nor token |
| `FilePasswordResetNotifier` | A neutral debug line; **not** the link and **not** the address |
| `JwtAuthenticationFilter` | The path and the exception class — **not** the token |
| `GlobalExceptionHandler` | Path, error code, and for a database constraint the driver's cause. It never logs the body |
| `LoginAttemptService` | That a throttle fired — not the attempted password |
| `CategoryService` | The account id, the operation and (where it names the row at all) the category's own id — never the exception, so a constraint name and a name key stay out |
| `TransactionService` | The account id and the transaction id, or the account id and the operation when a write is refused — never the exception, and never a trigger's `SIGNAL` text or a procedure name |

Startup is covered too. Spring Boot's `UserDetailsServiceAutoConfiguration` creates a default
in-memory account when the application defines no `UserDetailsService`, and logs its generated
password:

```
WARN ... Using generated security password: 8f13e0f2-...-...
```

That account could never authenticate against this API — no HTTP Basic or form login is enabled,
so the only way in is a signed bearer token — but a credential-shaped string in the application log
is exactly what this section keeps out, and on a shared log it invites the wrong investigation.
`CampusCoinApplication` therefore excludes the auto-configuration, which removes both the account
and the line. A test asserts the exclusion still holds (`noDefaultInMemoryUserIsProvisioned`),
because it is a one-word annotation change that nothing else would notice.

**One thing the application cannot keep out of the log.** When a `CHECK`, unique key, foreign key or
trigger actually refuses a statement, Hibernate's own `SqlExceptionHelper` writes the driver's raw
message at `ERROR` — for example
`Duplicate entry '44-EXPENSE-Raced' for key 'categories.uk_categories_scope_type_name'`. That is a
Hibernate line, not an application line, and it appears regardless of what the service logs. The
guarantee this section makes is therefore about the lines the application writes: a refused business
operation is logged with the operation and the account id, without the exception, so the application
itself never adds a constraint name, a scope key or a trigger's text to the log. The driver's text
that Hibernate may still emit names a schema object and a value the caller supplied — it is not a
credential and not data the caller did not already know — but it is present, and no module should
claim otherwise. Suppressing it would mean disabling Hibernate's SQL-error logging, which would also
hide genuine faults. The distinction matters because `docs/api/categories.md` §13,
`docs/api/transactions.md` and `MODULE_04_TRANSACTIONS.md` §5.3 state it explicitly rather than
inheriting a broader claim.

**The same bound applies to statement logging.** The dev profile sets `org.hibernate.SQL: DEBUG`, so
Hibernate prints every statement it issues — including the module's `CALL sp_soft_delete_transaction(?, ?)`.
The parameters are placeholders, so no value and no user data is written, but a stored procedure's
name is present. It is a development-only setting; production is at `INFO`. A test that asserts a
module's refusals keep the schema out of the log therefore filters to the module's own lines rather
than claiming the whole stream is clean — see `SecurityHardeningIT`.

Three configuration settings make this hold in practice:

- `org.hibernate.orm.jdbc.bind` is pinned to `WARN` in the dev profile. At `DEBUG`, Hibernate
  prints every bound parameter — including password hashes and token hashes — which would put
  exactly the material this section forbids into the log.
- `org.hibernate.SQL` is `DEBUG` in the dev profile only, which is why a statement and a procedure
  name appear during development. Production does not set it.
- Error handling logs at `DEBUG` for 4xx. Raising `com.campuscoin` above `DEBUG` in production
  (`application-prod.yml` uses `INFO`) is intentional and can only reduce what is written.

A test asserts this against the real log stream: after a full register → sign-in → reset cycle,
neither the access token nor the raw reset token appears in the captured output, while
`com.campuscoin` is at `DEBUG`.

---

## 8. CORS

`common/config/CorsConfig.java`:

| Setting | Value |
|---------|-------|
| Allowed origins | `CORS_ALLOWED_ORIGINS`, default `http://localhost:4200` — an explicit list |
| Allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed headers | `Authorization, Content-Type, Accept` |
| Exposed headers | `Location` |
| Credentials | `true` |
| Max age | 3600 s |
| Scope | `/api/**` |

**Never a wildcard.** `Access-Control-Allow-Origin: *` would let any website call this API from a
visitor's browser. The allowed list is explicit, and `Access-Control-Allow-Credentials` is
`true` — which is only legal, and only safe, alongside a specific origin. A request from an
unlisted origin is refused before it is served. Verified by test: `http://localhost:4200` is
echoed, `http://evil.example.com` is refused with no allow-origin header.

For production, set `CORS_ALLOWED_ORIGINS` to the real frontend origin(s), comma-separated. If the
frontend is served from the same origin as the API, CORS is not needed at all and the list can be
left minimal.

---

## 9. Secrets and configuration

**Nothing sensitive has a default in source.**

| Variable | Required | Notes |
|----------|----------|-------|
| `JWT_SECRET` | yes | No default. Must be at least 32 bytes; the app refuses to start otherwise |
| `DB_PASSWORD` | yes | No default. The application account, not root |
| `DB_USER` | no | Defaults to `campuscoin_app` |
| `DB_HOST`, `DB_PORT`, `DB_NAME` | no | Default to localhost / 3306 / campuscoin |
| `CORS_ALLOWED_ORIGINS` | production | Defaults to `http://localhost:4200`, correct for dev only |
| `RESET_LINK_BASE_URL` | production | Defaults to the local Angular route |

- `.env` is gitignored and holds the Docker Compose passwords. `.env.example` is committed and
  contains **only sample values**.
- `application.yml` references `${JWT_SECRET}` with no fallback, so a missing value is a startup
  failure rather than a silent weak key.
- The application connects as `campuscoin_app`, not `root`, so a compromise of the API does not
  grant administrative access to the server.
- Generating a production secret:
  `openssl rand -base64 48`
- **Rotating `JWT_SECRET` invalidates every access token at once**, because the signature no
  longer verifies. That is the intended emergency lever, and it signs every user out. Changing
  it requires no database change.
- Never commit a real secret. If one is committed, rotate it — removing it from the latest commit
  does not remove it from the history.

---

## 10. 401 vs 403, and error disclosure

**401 Unauthorized** — the caller is not identified: no token, or the token is invalid, expired,
revoked, or belongs to a disabled account. Codes: `INVALID_CREDENTIALS`, `UNAUTHENTICATED`,
`ACCOUNT_DISABLED`.

**403 Forbidden** — the caller **is** identified, and this identity may not use this endpoint.
Code: `ACCESS_DENIED`. This is the answer to a student calling an admin route (UC-05 E1) and to a
student using the administrator portal with correct credentials (UC-05 A1).

The distinction matters to the client: 401 means "sign in again", 403 means "signing in again will
not help".

**Error bodies never contain:** stack traces, SQL statements, driver messages, table or column
names, file paths, framework versions, database credentials, or the submitted request body. A
duplicate-email conflict reports the conflict and not `uk_users_email` — the constraint name is
useful for diagnosis and is written to the server log, not the response. An unexpected 500 returns
a fixed generic message while the real cause is logged server-side.

One deliberate exception: a wrong password and an **unknown** email are indistinguishable (both
401 `INVALID_CREDENTIALS`), but a **disabled** account is named (`ACCOUNT_DISABLED`). That is
UC-02 A2's requirement — the student must be told to contact the administrator — and it is only
reachable by someone who supplied the correct password, so it reveals nothing to a stranger.

---

## 11. Brute-force and flood protection

`auth/service/LoginAttemptService.java` throttles two abuses: repeated credential guessing, and
flooding the reset endpoint.

| Limit | Value | Source |
|-------|-------|--------|
| Sign-in failures before lockout | 5 | `system_settings.auth.max_login_attempts` (administrator-editable) |
| Reset requests before lockout | 3 | `campuscoin.security.login.max-reset-requests` |
| Cooling-off window | 15 minutes | `campuscoin.security.login.lockout-minutes` |

When a limit is reached the endpoint answers **429 `TOO_MANY_ATTEMPTS`** until the window closes.

- Counters are keyed by the **submitted email address**, and reset requests are counted whether or
  not the address exists. A throttle that behaved differently for a registered address would be an
  account-enumeration oracle.
- The check runs **before** the password is compared, so a locked identifier costs no bcrypt work.
- A successful sign-in clears the counter — the limit is on *consecutive* failures, not a lifetime
  total, so a student who mistypes twice over a term is not eventually locked out.
- A disabled account and a wrong-role rejection are **not** counted. Someone who supplied the
  correct password is not brute-forcing, and counting those would lock a student out of their own
  account for opening the wrong portal by mistake.

### Trade-off: the counters are in memory, per instance

| | |
|---|---|
| **What this means** | Each running instance keeps its own counters. With N instances behind a load balancer, an attacker gets up to N × limit attempts before being refused everywhere |
| **Why it was chosen** | It needs no schema change (which the requirements forbid) and no extra infrastructure. For a single-instance deployment it is exactly the intended behaviour |
| **When to change it** | Any deployment with more than one instance, or one that restarts often (a restart clears the counters) |
| **How to change it** | Move the `attempts` map behind a shared store — Redis with a TTL, or a table in the existing MySQL database. The service's public API (`assertLoginAllowed`, `recordLoginFailure`, `recordLoginSuccess`, `assertResetAllowed`, `recordResetRequest`) is the seam: only the private storage changes |
| **What is not a substitute** | A per-IP limit instead of per-address: a campus network puts hundreds of students behind one address, so it would lock out a whole building |

Lockout is a mitigation, not a solution. Real protection combines it with the hashing cost in §4
(bcrypt makes each guess expensive), a strong secret (§3), and monitoring of `TOO_MANY_ATTEMPTS`
volume, which is a useful signal that an attack is in progress.

---

## 12. No field-level encryption

**The schema is not modified to add encrypted columns, and the requirements forbid doing so.**

The reasoning, recorded so it is not "fixed" later by mistake:

- **Passwords and tokens are already hashed**, which is the correct treatment for values the
  server must verify but never read (§4, §5). Encryption would be *weaker* here: it is reversible,
  so a stolen key yields the plaintext.
- **AES-encrypting ordinary fields would break the schema's own logic.** Day/week/month views,
  budget aggregations, unique keys and the stored procedures all read those columns directly. An
  encrypted `amount` cannot be summed by MySQL, and an encrypted `email` cannot carry
  `uk_users_email`. The application would have to decrypt the whole dataset in Java to compute
  anything — the opposite of what the database layer is for.
- **It would break `ddl-auto=validate`** the moment a column's type changed, which the entity
  mapping would then have to misrepresent.
- **What actually protects data at rest** is disk or tablespace encryption (InnoDB tablespace
  encryption, or an encrypted volume, both transparent to SQL and to the schema) together with the
  access controls already in place: the application connects as a limited account, not root, and
  exposed ports are not published in production.

Field-level encryption would therefore add complexity, break the database logic, and defend
against a narrower threat than the mechanism already available at the storage layer.

---

## Summary of verification

Every claim above that can be checked by machine is checked by the test suite (214 tests, all
passing):

| Claim | Test |
|-------|------|
| Passwords stored only as bcrypt; seeded `$2y$` hashes verify | `SecurityHardeningIT` |
| No password, hash or token in any response body | `SecurityHardeningIT` |
| No stack trace, SQL or driver detail in an error body | `SecurityHardeningIT`, `CategoryApiIT`, `TransactionApiIT` |
| Raw reset token never stored; only its hash | `SecurityHardeningIT`, `PasswordResetApiIT` |
| Neither token ever appears in the log | `SecurityHardeningIT` |
| A client-supplied role/status/id is discarded | `SecurityHardeningIT` |
| A client cannot set a record's owner, provenance or soft-delete state | `TransactionApiIT` |
| Identity comes from the token, not the body | `SecurityHardeningIT` |
| 401 and 403 are used correctly | `SecurityHardeningIT`, `AdminAuthApiIT`, `CategoryApiIT`, `TransactionApiIT` |
| Brute-force throttle returns 429; success clears it | `SecurityHardeningIT` |
| Reset flood is throttled for unknown addresses too | `SecurityHardeningIT` |
| CORS echoes the allowed origin and refuses others | `SecurityHardeningIT` |
| A reset revokes every session and bumps `token_version` | `PasswordResetApiIT` |
| A student cannot use the admin portal or admin routes | `AdminAuthApiIT` |
| Ownership is enforced by the query, for every id-addressed verb | `CategoryApiIT`, `TransactionApiIT` |
| Concurrent writes cannot both report success | `TransactionApiIT` |

## Outstanding before production

These are deployment tasks, not code defects, and none is in this module's scope:

1. **Terminate TLS** in front of the service and set `CORS_ALLOWED_ORIGINS` to the HTTPS origin.
2. **Wire a real mail provider** into `PasswordResetNotifier`; the default `NoopPasswordResetNotifier`
   logs a warning and sends nothing.
3. **Set `JWT_SECRET`** and `DB_PASSWORD` from a secret manager, not a file.
4. **Move the throttle counters to a shared store** if running more than one instance (§11).
5. **Decide whether `springdoc` and the health probe stay public in production.** They are
   permitted for local use and are the only operational paths left open; `/actuator/metrics` and
   the rest of `/actuator/**` now require a token. `health` publishes only the summary status
   (`show-details: never`), so it discloses nothing internal.
6. **Enable InnoDB tablespace encryption** if data-at-rest protection is required (§12).
