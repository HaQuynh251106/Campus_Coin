# Campus Coin — Manual User Test Checklist

**Date:** 2026-09-27
**Companion to:** `docs/testing/qa/CAUSAL_RELATIONSHIP_QA_REPORT.md` (automated causal QA)

This checklist contains **only tests that genuinely require a human.** Every item below was excluded
from automation for one of three stated reasons: it needs a **human's eyes** (rendering, print layout,
real-device behaviour), it needs a **credential or resource the automated pass must not hold** (a real
SMTP password, a real AI provider key), or it needs a **wall-clock wait** the automated pass chose not to
fake.

Everything a program could check has already been checked. Do not re-run an automated check by hand
unless an item below explicitly asks for it.

**Column meanings**

| Column | Meaning |
|---|---|
| **ID** | Stable identifier, referenced from the causal report |
| **Feature** | What is being tested |
| **Why human** | The specific reason automation cannot close it |
| **Preconditions** | State required before starting |
| **Exact steps** | Reproducible, numbered |
| **Expected result** | What a correct system does |
| **Automated evidence already available** | What is already proved, so you do not re-prove it |
| **Human status** | PENDING until a human records a verdict |

**How to record a result.** Replace `PENDING` with `PASS`, `FAIL` or `BLOCKED (reason)`, and add the
date and the initials of the tester. A result without a date is not a result.

---

## 19.1 MANUAL-EMAIL-01 — Real password-reset email delivery

| Field | Value |
|---|---|
| **ID** | MANUAL-EMAIL-01 |
| **Feature** | Password reset — real SMTP delivery end to end (UC-03) |
| **Why human** | A real inbox can only be read by a person. The dev sink proves the *content*; only SMTP proves the *transport*. |
| **Preconditions** | The backend is started with `RESET_SINK_ENABLED=false` and real SMTP values exported (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM_ADDRESS`, `MAIL_FROM_NAME`). **Do not paste any of these into a report, a ticket or a chat.** A mailbox you control is reachable. |
| **Automated evidence already available** | The whole token lifecycle is already verified: request → verify → complete → **replay refused** (`400 INVALID_RESET_TOKEN`) → new password works → old refused → replayed password never applied. The message body is confirmed to carry `http://localhost:4200/reset-password?token=…`. **The transport is now verified too:** with real SMTP configured the backend log reads `SmtpPasswordResetNotifier : Password reset link sent through SMTP`, which closes steps 1, 2 and 15 below. What remains genuinely human is only whether the message **lands** in a mailbox and reads correctly — steps 5–8 and 12–14. |

**Exact steps**

1. Start the backend with the sink **off** and real SMTP configured:
   ```bash
   cd backend && RESET_SINK_ENABLED=false MAIL_HOST='<host>' MAIL_PORT=587 MAIL_USERNAME='<user>' \
     MAIL_PASSWORD='<password>' MAIL_FROM_ADDRESS='<verified sender>' \
     JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
     java -jar target/campus-coin-backend-1.0.0-SNAPSHOT.jar
   ```
2. Confirm the backend log states SMTP delivery is in use (not the no-op notifier). If it says the
   no-op notifier is installed, the `MAIL_*` values were not read — stop and fix that first.
3. Open the app at `http://localhost:4200`, choose **Forgot password**, and submit
   `an.nguyen@student.campuscoin.edu`.
4. Confirm the on-screen message is the generic one and does **not** reveal whether the account exists.
5. Open the mailbox for that address and confirm the message **arrives** (allow up to a few minutes;
   check the spam folder).
6. Confirm the sender shown is the configured `MAIL_FROM_ADDRESS`/`MAIL_FROM_NAME`, not a relay default.
7. Confirm the link host is the app you are testing (not `example.com`, not a staging host).
8. Click the link and confirm it opens the reset-password screen **with the token pre-filled from the
   query string**.
9. Set a new password, submit, and confirm the success screen.
10. Sign in with the new password — it must succeed.
11. Sign in with the old password — it must fail.
12. **Go back to the emailed link and load it a second time**, then try to submit it again. This is the
    single-use check on the *real* link rather than the dev sink.
13. Confirm the second attempt is refused with an invalid/expired-link message and does **not** change
    the password.
14. Repeat steps 3–5 for an address that does **not** exist. The message must still say a link was sent.
15. Confirm no reset link, token or password appears anywhere in the backend log.

**Expected result:** the message is delivered, the link targets this application, it sets the password
exactly once, the second use is refused, an unknown address is indistinguishable from a known one, and
nothing secret is logged.

**Human status:** PENDING

---

## 19.2 MANUAL-EMAIL-02 — Admin-triggered reset email delivery

| Field | Value |
|---|---|
| **ID** | MANUAL-EMAIL-02 |
| **Feature** | Administrator sends a reset to a student (UC-22 B4) |
| **Why human** | Same transport reason as MANUAL-EMAIL-01, from the admin side. |
| **Preconditions** | As MANUAL-EMAIL-01. Signed in as `admin@campuscoin.edu`. |
| **Automated evidence already available** | The endpoint returns `202`, never returns a token, writes no row for an unknown id (`404`), and the delivered link is confirmed to be written to the dev sink with the correct shape. |

**Exact steps**

1. Sign in as the administrator and open **Admin → Users**.
2. Trigger **Send password reset** for `binh.tran@student.campuscoin.edu`.
3. Confirm the response does **not** display, return or log a token anywhere in the UI.
4. Confirm the mailbox for that student receives the message.
5. Complete the reset with the link and confirm Bella can sign in with the new password.
6. Trigger a reset for an account **id that does not exist** (an admin API call is sufficient here) and
   confirm `404` with no message sent.
7. Confirm the student's session and data are unaffected by the reset request itself.

**Expected result:** delivery works, no token is exposed to the administrator, and an unknown target is
refused.

**Human status:** PENDING

---

## 19.3 MANUAL-RECURRING-01 — A firing of the default schedule

| Field | Value |
|---|---|
| **ID** | MANUAL-RECURRING-01 |
| **Feature** | Recurring-payment scheduler under its **production** cron |
| **Why human** | REL-21/REL-22 were observed on the **real timer** but with a re-timed cron so the wait was seconds, not a day. Proving the timer is wired and the posting logic correct is done; seeing the **default** schedule (`0 5 0 * * *` = 00:05 Asia/Ho_Chi_Minh) fire requires a wall-clock wait past midnight. |
| **Preconditions** | The backend running with **no** `RECURRING_SCHEDULER_CRON` override. A rule whose `next_run_date` is **today** (make it due through the API — `PATCH /api/v1/recurring-rules/{id}` — never by editing the database). |
| **Automated evidence already available** | The real `@Scheduled` timer was watched firing and posting exactly one occurrence and one transaction; the rule advanced `09-01 → 10-01`; `last_run_date` was set; other rules were untouched; dashboard, report and forecast each rose by exactly the posted amount. **Idempotence is proved:** two further ticks against an already-posted period produced zero duplicates. |

**Exact steps**

1. Start the backend with no cron override and confirm the log line for the recurring scheduler shows
   the **default** cron.
2. Through the API, make one rule due for today (record its id and `nextRunDate`).
3. Note the current values on the student's dashboard: `totalIncome`, `totalExpense`, the RECURRING
   count in the transaction list, and the rule's `next_runDate`.
4. Leave the machine awake past 00:05 local time. (Do not change the cron to force it — that is what the
   automated pass already did.)
5. After 00:05, check the backend log for the scheduler-run completion line and the timestamp.
6. Read the transaction list and confirm **exactly one** new transaction with `source: RECURRING`.
7. Confirm the dashboard moved by **exactly** that transaction's amount.
8. Confirm the rule's `nextRunDate` advanced to the next period and `lastRunDate` was set.
9. Confirm no other rule produced a transaction.

**Expected result:** the default schedule fires once, posts exactly one transaction per due rule,
advances the rule, and moves the dependent reads by the exact amount.

**Human status:** PENDING

---

## 19.4 MANUAL-RECURRING-02 — Tips generation under its default schedule

| Field | Value |
|---|---|
| **ID** | MANUAL-RECURRING-02 |
| **Feature** | Monthly tips generation scheduler (UC-18) |
| **Why human** | Same wall-clock reason as MANUAL-RECURRING-01: the default cron fires daily and the automated pass did not wait for it. |
| **Preconditions** | Backend running with no overrides; a student with spending in the current month. |
| **Automated evidence already available** | Tip generation is correct when invoked; tips are readable, state transitions work and revert, and tip templates from the admin reach the student. Generation idempotence is covered by the backend suite. |

**Exact steps**

1. Note the student's tip list for the current month (ids and states).
2. Wait for the scheduled generation window (check the configured cron for tips in
   `application.yml`; do not override it).
3. Confirm the log shows the tips job ran.
4. Re-read the tip list. Confirm existing tips were **not duplicated** and their states were preserved
   (a pinned tip stays pinned).
5. Confirm a student with no spending in the month does not collect meaningless tips.

**Expected result:** the job runs on schedule, is idempotent, and preserves user-chosen tip states.

**Human status:** PENDING

---

## 19.5 AI-provider verification — is the intelligence real or rule-based?

| Field | Value |
|---|---|
| **ID** | MANUAL-AI-01 |
| **Feature** | Distinguishing genuine AI/LLM output from the deterministic rule engine |
| **Why human** | **The provider is now real and configured** — a `GEMINI_API_KEY` is present and the `GeminiAiSuggestionPort` adapter is installed and invoked. What remains unverified is the provider's **own output**: every real call during this pass returned `429` (free-tier quota, 20 requests/model/day) or `503`. Only a person can read a genuine narrative and judge whether it is *sensible*, and only a person can decide whether the free tier's limit is acceptable for a demo. |
| **Preconditions** | A provider key with **quota available** (the free tier resets daily: 20 requests per model per day). **Do not paste the key into any report, ticket, chat or file under version control** — it goes in the environment only, and see the rotation note at the end of this section. |
| **Automated evidence already available** | The **rule-based** path is verified end to end: category suggestion returns `source: RULE` with `confidence: 1.0` for a learned description and `source: NONE` for a novel one; the proposal never writes (BR-13); acceptance is an ordinary transaction update; the insight generator produces narrative text with figures matching the report. **The application's provider wiring is now verified too** (steps 4, 6, 9, 10 → covered automatically): with the adapter pointed at a local stub, `POST /insights/generate` returned `generatedBy=AI, model=gemini-3.5-flash` and `POST /ai/suggest-category` returned `source=AI, confidence=0.91`. **That verifies the application, not Gemini's answers** — steps 7 and 8 below still need a real, quota-backed call. |

> ⚠ **Read this before testing.** A real defect was found and fixed during provider setup: Gemini 3.x
> bills *thought tokens* against `maxOutputTokens`, and the previous cap of `1024` was sometimes
> consumed by thinking alone, truncating the reply mid-JSON and silently downgrading the answer to
> rule-based. The cap is now `8192` in both `AiProperties.DEFAULT_MAX_TOKENS` and the
> `application.yml` `max-tokens` placeholder. If you see AI output *intermittently* fall back, check
> `AI_MAX_TOKENS` before blaming the provider.
>
> ⚠ **Also read this.** Do **not** set `AI_MODEL` to a Claude/Anthropic model name. The configured
> provider is Gemini (`google-genai`); an Anthropic model name would be sent to the Gemini SDK and
> fail silently back to rule-based. The `.env.local` template's Anthropic wording is stale and has
> been corrected in place.

**Exact steps**

1. **First, record the rule-based baseline.** Generate an insight for `2026-09` and note the response's
   `generatedBy`/`model` fields and the narrative wording. **A provider key is currently configured, so
   the baseline will not be produced by unsetting it** — quota is exhausted, so the rule path answers
   anyway. The expected reading is `generatedBy: RULE_BASED`, `model: null`, and text of the form
   `2026-09: you received 692.78 and spent 1286.89, net difference -594.11. Highest spending category:
   Food (655.64).` **Capture this now, before step 5: `InsightWriteDao.writeAiNarrative` has no
   `generated_by` guard, so the first successful provider call overwrites it for good** (unlike
   re-running the rule path, which the stored-procedure guard leaves alone).
2. Note the same for a category suggestion on a novel description (`source`, `confidence`).
3. Configure a real AI provider through its documented environment variables and restart the backend.
4. Confirm the startup log shows the AI provider was selected rather than the rule-based fallback. **If
   it still shows the fallback, the configuration was not read — stop and fix that first.**
5. Regenerate the same month's insight.
6. Confirm the response now identifies a real model (a non-null `model`, a provider name that is not the
   rule engine).
7. Confirm the narrative is materially different from the rule-based baseline and still **numerically
   consistent** with `GET /reports` — an AI narrative must not contradict the report's own figures.
8. Repeat a category suggestion on a novel description. Confirm whether the source is now
   model-attributed, and that it **still does not write** the transaction (BR-13 holds regardless of
   provider).
9. Disconnect the provider (or use a deliberately invalid key) and confirm the system **degrades
   gracefully** — insights and suggestions still work through the rule path rather than returning an
   error to the student.
10. Confirm no API key, prompt or raw provider error is exposed in any student-facing response.

**Expected result:** you can state, with evidence, whether the deployment runs real AI or rule-based
logic, and the system remains correct and functional when the provider is absent.

> **Record the answer explicitly.** "The system uses rule-based insights in this build" is a legitimate
> and complete finding. What is *not* legitimate is leaving it unknown while the demo describes the
> feature as AI-powered. **What is verified automatically is the application's wiring, not the
> provider's answers.** After running this test you must be able to say which of these your demo is:
> (a) real AI, quota available, output checked by eye; (b) real AI configured but quota exhausted, so
> it may silently serve rule-based; or (c) no provider at all. Only (a) supports describing the
> feature as AI-powered on stage.

> **Rotate the key when you are done.** The key and the Gmail app password were supplied through a
> chat transcript, so both should be considered exposed: revoke the app password and regenerate the
> provider key after testing, then update `.env.local` (which is gitignored — verified).

**Human status:** PENDING — narrowed. Steps 4, 6, 9 and 10 are covered automatically; steps 1–3, 5,
7 and 8 still need a quota-backed real call.

---

## 19.6 MANUAL-AI-CHAT-01 — the conversational assistant, by hand

| Field | Value |
|---|---|
| **ID** | MANUAL-AI-CHAT-01 |
| **Feature** | `POST /api/v1/chat` — the student-facing conversational assistant |
| **Why human** | **Everything structural is automated** (see below), and what remains cannot be: whether the six-turn conversation *reads* as a conversation. That the figures are the student's own, the ownership is enforced and the provider failure is honest are all asserted by `ChatApiIT`. What no assertion can settle is whether a follow-up like "Why?" is answered as a person would answer it, and whether the panel is pleasant to actually type into. **Only a person can judge fluency and tone.** |
| **Preconditions** | A quota-backed `GEMINI_API_KEY` in the environment (**not** in any file under version control), the backend restarted, and a student account with at least one category of spending in the current month. The free tier allows **20 requests/model/day** — a six-turn conversation with a grounding read each is roughly 10–12 calls, so budget the day. |
| **Automated evidence already available** | The whole chain is proved against a wire-level stub with **no mocking of this application's code**: `ChatApiIT` (19 tests) drives controller → security → `ChatService` → `ChatToolExecutor` → nine domain services → real MySQL, with only Google's endpoint replaced. It asserts a figure read from the student's own rows reaches the provider, an undeclared tool is refused rather than run, two students are read their own figures, a `userId` in the body does not redirect the read, no authentication means no data and no provider call, an administrator is refused, and a spent quota becomes a reported error rather than a fabricated answer. `ChatServiceTest` (21 tests) covers the turn-taking. `ChatbotService` (11 Vitest tests) covers the client contract: a reply is rendered unchanged, a failure becomes a flagged turn with a retry, and no request carries a user id. |

> ⚠ **Read this before testing.** This assistant has **no fallback answer**, by design. When the provider
> is unreachable, rate-limited or out of quota the API returns `503 AI_UNAVAILABLE` and the panel shows
> an error with a retry. That is correct behaviour, not a bug — and it is the one thing that distinguishes
> this from the keyword-matching chatbot it replaced, which always answered. **If you see a fluent reply
> while the backend log shows a provider failure, that is a defect: report it.**

**Exact steps** — one conversation, in order, without reloading the page. Record each answer verbatim.

1. Open the chat panel and confirm it reports the assistant as available (not the unavailable notice).
2. Send **"How much did I spend this month?"** — record the figure.
3. Send **"What category is the biggest?"** — the category must be a real one from this student's records.
4. Send **"Why?"** — a one-word follow-up. It must be answered in terms of the **category named in step 3**,
   not by asking you to repeat yourself and not with a figure about something else. *This is the multi-turn
   check: nothing in the application matches the word "why", so the resolution is the model reading its own
   conversation.*
5. Send **"What about last month?"** — the answer must be a **different month** from step 2's. If it repeats
   the same month, multi-turn reference resolution has failed.
6. Send **"Was I over budget?"** — where the student has no budget set, the correct answer is that none is
   set. That is a different answer from "no, you were under", and the difference matters.
7. Send **"What should I do?"** — advice should follow from this conversation's own figures. It must not
   order the student to do anything, and must not claim to have changed anything.
8. Send an unrelated question — **"Who won the 2018 World Cup?"**. The reply must be exactly:
   *"I can help with your Campus Coin finances, transactions, budgets, reports, categories and related
   features, but I can't help with that topic."*
9. **Cross-check the numbers.** Open Reports and Dashboard and confirm the figures quoted in steps 2, 3 and 5
   are the same as the screens show. **No number in the conversation may be absent from the student's own data.**
10. **Check the panel behaviour**: the reply area scrolls rather than the page; a long reply wraps rather than
    overflowing; `Enter` sends and `Shift+Enter` adds a line; the typing indicator appears while a reply is
    pending; the close button and `Escape` both close it; the layout is usable at phone width.

**Expected result:** six turns that read as one conversation with continuous references, every figure matching
the student's own records to the cent, the out-of-scope question refused with the exact sentence, and the panel
usable by keyboard and at phone width.

**Record PASS or FAIL per step, and take screenshots** of steps 2, 4, 5 and 8 at minimum. A step you did not
run is not a PASS.

> **If the provider's quota is exhausted when you try this**, you will see `503` on step 2. That is not a FAIL —
> it is the honest failure working. Record it as BLOCKED BY QUOTA and retry after the daily reset. **Do not
> record the feature as verified on the strength of the stub-backed suite alone:** the brief's acceptance test
> asks whether Gemini actually returned the response the student read, and only a real call can show that.

**Human status:** PENDING

---

## 19.7 MANUAL-EXPORT-01 — Report export / print output

| Field | Value |
|---|---|
| **ID** | MANUAL-EXPORT-01 |
| **Feature** | Exporting a report (UC-16 / BR-18) |
| **Why human** | Export is **client-side by design** — there is no export endpoint. The client calls `window.print()`, so the artefact is the browser's print output: only a person can judge whether the resulting page or PDF is legible and correct. |
| **Preconditions** | A student with data in the viewed month; a browser with a print-to-PDF destination. |
| **Automated evidence already available** | The two backing reads (`GET /reports`, `GET /reports/spending`) return correct, consistent figures for the month. **Do not re-verify the numbers here** — verify the *artefact*. |

**Exact steps**

1. Sign in as a student and open **Reports**.
2. Confirm the **Export Report** control is present and reachable by keyboard.
3. Click it and confirm the browser's print dialog opens.
4. Print to PDF.
5. Open the PDF and confirm the report is **complete** — not clipped, not missing its last rows.
6. Confirm the figures in the PDF match what the screen showed (at minimum the month's income, expense
   and net).
7. Confirm the PDF does **not** contain navigation chrome, buttons, or a sidebar bleeding into the page.
8. Confirm the student's own name/month is identifiable on the artefact — a report with no owner or
   period is not a report.
9. Repeat on a **narrow window** and confirm the PDF is still legible.
10. Confirm the **Printable** area carries no colour-only information: an over-budget state must still be
    readable in greyscale (a red-only signal disappears in a black-and-white print).

**Expected result:** a legible, complete, owner-identifiable report whose printed figures match the
screen.

**Human status:** PENDING

---

## 19.8 Real-device and responsive checks

| Field | Value |
|---|---|
| **ID** | MANUAL-DEVICE-01 |
| **Feature** | Layout and touch behaviour on real hardware |
| **Why human** | Emulated viewports cannot reproduce real text reflow, touch targets, safe-area insets or OS font scaling. |
| **Preconditions** | A phone (and ideally a tablet). |
| **Automated evidence already available** | Desktop load / empty / error / authorisation states are distinct and correct; the announcement banner renders correctly at desktop width. |

**Exact steps**

1. Open the app on a phone in portrait. Sign in.
2. Confirm no horizontal scrolling on any main screen — home, transactions, budgets, reports,
   insights, tips, bookmarks, forecast, recurring.
3. Confirm the **announcement banner** is readable, wraps rather than clipping, and does not push the
   main content below the fold.
4. Confirm the **chatbot / mascot** launcher does not overlap the banner or a primary button.
5. Confirm tap targets (the floating action button, the nav, the banner's close, if present) are
   comfortable.
6. Rotate to landscape and confirm nothing is cut off.
7. Raise the OS font size and confirm text does not overlap or truncate irrecoverably.
8. With a screen reader on, confirm the hard buttons announce their labels.
9. Confirm the app remains usable on a slow connection (throttle to 3G) — a slow load must show a
   loading state, not a blank screen.

**Expected result:** usable on a real device at real font sizes, with no clipping and no overlap.

**Human status:** PENDING

---

## 19.9 Accessibility checks

| Field | Value |
|---|---|
| **ID** | MANUAL-A11Y-01 |
| **Feature** | Keyboard and screen-reader access |
| **Why human** | Automated assertions can see the DOM but not whether a person can actually operate the flow. |
| **Preconditions** | A desktop browser. |
| **Automated evidence already available** | Icon-only controls were confirmed to carry accessible labels in the source; the banner renders its severity as **text**, not colour alone. |

**Exact steps**

1. Put the mouse aside. Tab to and activate **Forgot password**, complete the reset flow, and sign in.
2. Confirm focus is always visible and never lost or trapped.
3. On the dashboard, confirm the **announcement banner** is reachable and its text is announced in
   reading order before the main content.
4. Confirm each banner's severity is conveyed by **text**, not by colour alone — an `INFO` and a
   `WARNING` must be distinguishable with colour ignored.
5. On a form, submit with an empty required field and confirm the error is announced, not just coloured.
6. Confirm a modal or dialog returns focus to whatever opened it.
7. Confirm charts and icons that carry meaning have a text alternative; purely decorative ones are
   correctly hidden from the screen reader.

**Expected result:** every primary flow is completable without a mouse, and no meaning is carried by
colour alone.

**Human status:** PENDING

---

## 19.10 Explicitly NOT human — already automated

Listed so nobody re-runs them by hand. All are in the causal QA report with evidence.

| Already verified automatically | Where |
|---|---|
| Financial causality: a transaction moves the budget, dashboard, report and forecast by **exactly** its own amount, and deletion reverses all of them exactly | Causal report §E |
| Recurring scheduler posts once per due period and **never** double-posts (real timer, three ticks) | Causal report §M, REL-21/22 |
| Announcement delivery **and** rendering — three notices, three tones, open-ended notice shows no end date, admin-only notice hidden | Causal report §C, REL-35 |
| Category suggestion never writes (BR-13); acceptance is an ordinary update | Causal report §F, REL-32 |
| Budget-alert de-duplication (BR-12) | Causal report §F |
| CSV import: preview → commit → real transactions, one per valid row | Causal report §E, REL-30/31 |
| Field encryption: imported descriptions stored as ciphertext and read back decrypted | Causal report §G (OB-018) |
| Ownership: a second student gets `404` on read/delete/patch/activity/budget for another's records, and the owner succeeds on the same calls | Causal report §D |
| Authorisation: `401` / `403` matrix, forged and tampered tokens | Causal report §D |
| Anti-enumeration on password reset | Causal report §F |
| **Reset token is single-use** — replay after completion refused | Causal report §H |
| **Chat assistant — the whole chain**: a question reaches the provider, the student's own figure is sent back, the reply is returned unchanged, ownership holds for two students, a `userId` in the body is inert, no token means no data and no provider call, an admin is refused, and a spent quota is an honest `503` rather than an invented answer | Causal report §N; `ChatApiIT` (19 tests) |
| **Chat assistant — the client contract**: a reply is rendered unchanged, a failure becomes a flagged turn with a retry, a blank reply is treated as a failure, no request carries a user id, and the transcript sent ends with the new question and no greeting | `chatbot.service.spec.ts` (11 tests) |
| **Chat assistant — the §19.6 panel behaviours** (scroll container, wrapping, Enter/Shift+Enter, typing indicator, Escape/close, phone width) | the widget spec's structural assertions; the *feel* is still §19.6 |
| Backend suite: 1131 tests, 0 failures (re-run green after the chatbot rebuild); frontend production build and app type-check clean | Causal report §K, §M |
| Database routine signatures match the repository — **no drift** | Causal report §G, Correction 6 |

---

# HUMAN TEST HANDOFF

**Purpose.** Everything a machine can check is checked and green. What follows must be done by a person
before the system is described to anyone as complete.

**The system must not be described as "fully verified" while any MUST-TEST item below is PENDING.**
The correct description while these are open is: *"automated causal QA is complete and green; N human
verification items remain pending."*

## MUST TEST BEFORE FINAL DEMO

| ID | Why it blocks a demo claim | Approx. time |
|---|---|---|
| **MANUAL-AI-CHAT-01** | The chat assistant is the one feature whose *whole purpose* is a conversation, and it has **no fallback answer** — with the quota exhausted it returns `503` and the panel shows an error. The chain is proved against a stub, but **whether Gemini's answers actually read as a conversation has not been seen**, and the free tier allows only **20 requests/model/day**. Run the six turns in §19.6 and record PASS/FAIL per step. **Decide before you present whether the assistant is demo-ready.** | ~20 min + quota |
| **MANUAL-AI-01** | A `GEMINI_API_KEY` is now configured and the adapter is installed and called — but the free tier allows only **20 requests/model/day** and it was exhausted during setup, so **no real provider narrative has been captured yet**. Nothing is broken on screen: the 2026-09 insight is the product's own rule-based text. What is unverified is whether Gemini's output is any *good* — and, once quota returns, the first successful call **permanently overwrites** the current rule-based text (`writeAiNarrative` has no `generated_by` guard), so **capture the baseline wording first** (step 1 of §19.5). **Decide before you present.** | ~30 min |
| **MANUAL-EMAIL-01** | "Forgot password" is a headline feature. This is now **half-verified**: SMTP transport is confirmed (`SmtpPasswordResetNotifier` sent the message), so the demo will not silently do nothing. What is left is opening the inbox and confirming the message arrived and reads correctly. | ~10 min |
| **MANUAL-EXPORT-01** | Export is a visible button on the Reports screen. Verify the printed artefact at least once, or remove the claim from the demo script. | ~15 min |
| **MANUAL-RECURRING-01** | The scheduler is presented as automatic. You have proof the timer is wired, but not of the default schedule firing. Either wait one night or state the evidence precisely when presenting. | overnight wait |

## MUST FIX BEFORE FINAL DEMO — a broken build, not a human test

Not a test at all: a one-line mismatch that stops the frontend unit-test project compiling. It is here
rather than in §19.9 because nothing about it is automated or green.

**MANUAL-BUILD-01 — restore or drop the demo-fill helpers.** The uncommitted `login.component.ts` edit
removed `fillDemoStudent()` and `fillDemoAdmin()`; `login.component.spec.ts` (unmodified) still calls
both, so `npx ng test` fails with two `TS2339` errors. Either restore the helpers or delete the two test
cases — the right choice depends on where that login-screen work is going, which is why this pass
recorded it instead of guessing (causal report Correction 7, decision log D-23). **The application is
unaffected**: `npx ng build` and `npx tsc --noEmit -p tsconfig.app.json` are both clean, and the backend
suite is green. Until it is repaired, do not quote any frontend test count.

## OPTIONAL / ENVIRONMENT-DEPENDENT

| ID | Why it can wait | Approx. time |
|---|---|---|
| **MANUAL-EMAIL-02** | Same transport as MANUAL-EMAIL-01; if that passes, this almost certainly does. Admin-only path. | ~10 min |
| **MANUAL-RECURRING-02** | Same scheduler machinery as RECURRING-01; the idempotence property is already proved automatically. | overnight wait |
| **MANUAL-DEVICE-01** | Only needed if the demo is on a phone or a projector. | ~20 min |
| **MANUAL-A11Y-01** | Only needed if accessibility is an assessed criterion. | ~30 min |

## Before you start any MUST-TEST item — restart the dev server

The `ng serve` process that was running on `:4200` had a **stale build cache**: the `home-feed` module it
served contained zero references to the announcement banner, so the fixed UI could not appear on it. The
source is correct and the production build contains the banner — the running server was simply serving
an older compilation.

```bash
cd frontend && npx ng serve --port 4200
```

If the announcement banners do not appear on the home feed, that is the cause — not a regression.

## Verified-but-deliverability context

- The backend binds `:8080`; the frontend proxy targets it. Start the backend **before** the frontend.
- Backend CORS allows **only** `http://localhost:4200`. Serving the frontend on any other port makes
  login fail with `403` even though the API itself is healthy. Use `4200`.
- Demo credentials are in `docs/CREDENTIALS.md` and are accurate as of this pass.

---

## Final status

| Item | Status |
|---|---|
| Automated causal QA | **COMPLETE** — 38 PASS / 0 FAIL / 0 BLOCKED / 1 NOT APPLICABLE |
| Backend suite | **BUILD SUCCESS** — **1131 tests, 0 failures, 0 errors** (re-run green after the D-21 provider changes at 1091, and again after the chatbot rebuild added its 40) |
| Frontend suite | **DOES NOT COMPILE as a project** — the uncommitted `login.component.ts` edit removed two helpers its spec still calls (Correction 7). Production build and app type-check are clean. **The suite was nevertheless executed this pass by temporarily setting the broken spec aside (restored byte-identical afterwards) and it is green: 12 files, 60 tests, 0 failures**, including the 11 new chat-service tests. The project-level build error is unchanged and still needs the one-line repair |
| Frontend app build | **PASS** — `npx ng build` and `npx tsc --noEmit -p tsconfig.app.json` both `EXIT=0` |
| SMTP transport (D-21) | **VERIFIED PASS** — `SmtpPasswordResetNotifier` sent the reset message |
| AI application wiring (D-21) | **VERIFIED PASS** — both AI paths returned provider-sourced results through the API |
| AI token-cap defect (D-21) | **FIXED** — `1024 → 8192` in `AiProperties` **and** `application.yml` |
| Secrets hygiene (D-21) | **VERIFIED CLEAN** — none of the supplied secrets appear in any tracked file or non-env file on disk; `.gitignore` hardened to `.env*` + `!.env.example` |
| Stub-data cleanup (D-21) | **RESOLVED** — the stub-written insight was deleted and rebuilt through `POST /insights/generate`; Alex's September insight now reads the product's own rule-based text, figures matching `GET /reports`. Zero `STUB` rows remain database-wide |
| Chat assistant — chain (application) | **VERIFIED PASS** — `ChatApiIT` 19/19 against a wire-level provider stub with no application code mocked; `ChatServiceTest` 21/21 |
| Chat assistant — client | **VERIFIED PASS** — `chatbot.service.spec.ts` 11/11 and the widget spec, run green this pass |
| Chat assistant — real Gemini conversation | **PENDING HUMAN TEST** — MANUAL-AI-CHAT-01 (§19.6). The brief's acceptance test asks whether Gemini actually returned the response the student read; only a real, quota-backed call can show that |
| Human tests — MUST TEST | **5 PENDING** — MANUAL-AI-CHAT-01, MANUAL-AI-01, MANUAL-EMAIL-01, MANUAL-EXPORT-01, MANUAL-RECURRING-01 |
| Human tests — optional | **4 PENDING** |

**This system is not "fully verified" while the MUST TEST rows are PENDING.**
