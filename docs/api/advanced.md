# Anomalies, forecast and recent activity — endpoints 72–76

Three features that read the student's own records and say something about them without an external
service: UC-24 marks records that look wrong, UC-25 projects the next month, and UC-26 remembers what
the student recently opened or changed.

Base path `/api/v1`. Every endpoint needs a bearer token with the `STUDENT` role and touches only the
caller's own data.

## Table of contents

1. [Scope](#1-scope)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [Anomaly flagging — 72–73](#4-anomaly-flagging--7273)
5. [Forecast — 74](#5-forecast--74)
6. [Recent activity — 75–76](#6-recent-activity--7576)
7. [Status codes](#7-status-codes)
8. [Security properties](#8-security-properties)
9. [Angular integration notes](#9-angular-integration-notes)
10. [Traceability](#10-traceability)

## 1. Scope

Five operations on four paths. What they share is that none of them calls an external service — each is
the student's own data examined by a rule this backend applies. That is why they are documented apart
from the AI surfaces even though the SRS groups them in the same module.

- **UC-24** marks a record `DUPLICATE` or `UNUSUAL_AMOUNT`; the mark is decided by the server and can
  never be set by a client.
- **UC-25** projects the next month as the average of the student's last three complete months.
- **UC-26** records that the student opened or changed a transaction, and lists those entries.

## 2. Endpoints at a glance

| # | Method | Path | UC | Success |
|---|--------|------|----|---------|
| 72 | GET | `/api/v1/anomalies` | UC-24 | `200` the flagged records |
| 73 | POST | `/api/v1/anomalies/scan` | UC-24 | `200` the scan's tally and the list |
| 74 | GET | `/api/v1/forecast` | UC-25 | `200` the current month and projection |
| 75 | GET | `/api/v1/recent-activity` | UC-26 | `200` the caller's activity |
| 76 | POST | `/api/v1/recent-activity` | UC-26 | `201` the recorded entry |

### Parameters

| Endpoint | Parameter | In | Default | Max | Notes |
|---|---|---|---|---|---|
| 72 | `limit` | query | `20` | `100` | A larger value is `400`, not a clamp |
| 75 | `limit` | query | `10` | `50` | A larger value is `400`, not a clamp |

The bounds differ because the two lists answer different questions: a flagged list is a review queue, a
recent list is a glance. Each response echoes the limit it applied, which is why a larger value is
refused rather than silently reduced — a reduced answer would make the echoed field untrue.

### Authentication

Bearer token, role `STUDENT`. No token or an invalid one → `401 UNAUTHENTICATED`.

## 3. Field reference

### `FlaggedTransactionListResponse` (72)

| Field | Type | Notes |
|---|---|---|
| `limit` | number | The limit the server applied |
| `entries` | array | The flagged records, most recent first. Empty is the ordinary case |

### `FlaggedTransactionResponse` (72, and inside 73)

| Field | Type | Notes |
|---|---|---|
| `transactionId` | number | The transaction's id — the same identifier the transactions endpoints use |
| `categoryId` | number | |
| `categoryName` | string | The detector's own note names it too, because the note is read on its own |
| `categoryType` | `CategoryType` | Read from the category rather than stored on the record |
| `amount` | number | How much moved. Always positive; `categoryType` says the direction |
| `txnDate` | date | |
| `description` | string | The student's own description, decrypted. Omitted when there is none |
| `flagType` | `AnomalyFlagType` | `DUPLICATE` \| `UNUSUAL_AMOUNT` |
| `flagNote` | string | The detector's explanation, in the student's terms |

### `AnomalyScanResponse` (73)

| Field | Type | Notes |
|---|---|---|
| `examined` | number | How many live records were examined |
| `flagged` | number | Marks added by this scan that were not there before |
| `cleared` | number | Marks removed, because the student corrected the record or its twin was trashed |
| `unchanged` | number | Records examined and left exactly as they were |
| `entries` | array | The flagged records as they stand after the scan — the same list 72 returns |

**`examined = flagged + cleared + unchanged`** holds on every scan, and a second scan over unchanged
data reports every record under `unchanged` and writes nothing.

### `ForecastResponse` (74)

| Field | Type | Notes |
|---|---|---|
| `nextMonth` | string | The month the projection is for — the month after the one in progress. `yyyy-MM` |
| `currentMonth` | string | The month in progress, `yyyy-MM` |
| `basedOnMonths` | number | How many complete months the projection averaged over. `0` when there is none |
| `recentMonths` | array | The student's recent complete months, oldest first — the evidence |
| `currentMonthTotals` | object | Totals so far this month. **Omitted** when nothing is recorded yet |
| `projected` | object | The projection for next month. **Omitted** when there is no complete month to average |

`ForecastMonthResponse`: `periodMonth`, `income`, `expense`, `net`.
`CurrentMonthTotalsResponse`: `income`, `expense`, `net`.
`ProjectedMonthResponse`: `income`, `expense`, `savings`. `savings` may be negative.

### `RecentActivityListResponse` (75)

| Field | Type | Notes |
|---|---|---|
| `limit` | number | The limit the server applied |
| `entries` | array | Most recent first. Empty when the student has not opened anything |

### `RecentActivityResponse` (75, 76)

| Field | Type | Notes |
|---|---|---|
| `transactionId` | number | |
| `action` | `RecentAction` | `VIEWED` \| `EDITED` |
| `occurredAt` | datetime | When the action happened, in the application's zone. The list orders by this |
| `categoryId` | number | |
| `categoryType` | `CategoryType` | Read from the category rather than sent with the transaction |
| `amount` | number | |
| `description` | string | Decrypted. Omitted when there is none |
| `txnDate` | date | The date the transaction was recorded against |

### `RecordRecentActivityRequest` (76)

| Field | Type | Required | Notes |
|---|---|---|---|
| `transactionId` | number | yes | One of the caller's own; another student's is a `404` |
| `action` | `RecentAction` | yes | `VIEWED` or `EDITED` |

## 4. Anomaly flagging — 72–73

### 72 — `GET /api/v1/anomalies?limit=20`

The caller's records that the check has marked, most recent first, each carrying the record's own
details beside the mark and the detector's explanation.

**Reading does not examine anything.** This reports the flags as they stand, so opening the screen
cannot change what is on it. The request that recomputes them is the scan (73).

**Two kinds of mark.** `DUPLICATE` means another of the student's own records has the same amount in the
same category within a few days. `UNUSUAL_AMOUNT` means the amount is several times what the student
usually spends in that category. **Both thresholds are deployment settings, not values this API lets a
client choose.**

**Nothing flagged is the ordinary case.** An empty array means the records were examined and none look
wrong — which is why this endpoint does not answer `404`.

### 73 — `POST /api/v1/anomalies/scan`

Examines the caller's own records, works out which look wrong, applies the difference, and returns what
it did along with the resulting list.

**The client states nothing.** There is no request body: the caller asks for the check and the server
decides each record's mark. **A client cannot mark a record**, and in particular cannot mark its own
record as reviewed — which is the whole point of the feature, and the UC-24 instruction "do not allow
the client to arbitrarily set anomaly flags" made structural. The only write path is
`sp_flag_transaction`, which the detector calls with a value it computed.

**Safe to call repeatedly, and provably so.** Only differences are written: a second scan over unchanged
records reaches the same conclusion for each one, writes **nothing at all**, and reports every record
under `unchanged`. Refreshing on every screen visit cannot accumulate history rows. The mechanism is
`trg_transactions_after_update`, which appends a history row only when one of the three flag columns
actually changed (BR-09).

**It also clears marks.** A record the student has since corrected, or whose twin has been put in the
trash, loses its mark on the next scan — so `cleared` is reported separately from `flagged`.

**What "wrong" means.**
- `DUPLICATE`: another of the student's own records has the same amount and category within the
  configured number of days.
- `UNUSUAL_AMOUNT`: the record is at least the configured multiple of the student's own average in that
  category, **counting their other records and not this one** — so a single large record cannot raise
  the average it is measured against.

Both figures are deployment settings and are not parameters here.

**Trashed records take no part.** A record in the trash is not examined, not counted and not a baseline.
Putting one of a duplicate pair in the trash therefore clears the other on the next scan, with no
separate step.

```json
POST /api/v1/anomalies/scan
```

```json
{
  "examined": 31,
  "flagged": 2,
  "cleared": 1,
  "unchanged": 28,
  "entries": [ { "transactionId": 31, "flagType": "DUPLICATE", "...": "..." } ]
}
```

## 5. Forecast — 74

### 74 — `GET /api/v1/forecast`

The caller's totals for the month in progress and an estimate for the next month, computed as the
average of their last three **complete** months.

**The estimate is a trailing average, not a model.** It is the simplest thing that answers "roughly what
will next month cost", every figure in it can be checked against the student's own history, and it
introduces no machine-learning platform. `recentMonths` carries the months that were averaged so a
client can show or check the evidence.

**The month in progress is excluded from the baseline.** Its figures are partial and would fall as the
month went on, so averaging it in would make the projection depend on the day it was asked. It is
reported separately under `currentMonthTotals`.

**Absent blocks are meaningful, and are not zeroes.**
- A student who has recorded nothing this month has **no `currentMonthTotals`** — a block of zeroes
  would state that they had activity that netted to nothing.
- A student with no complete month behind them has **no `projected` block** and `basedOnMonths` is `0` —
  not "0.00 projected", which would read as a confident prediction of no spending.

`projected.savings` may be negative, meaning spending is on course to outrun income.

The month in progress comes from the **server's clock**, not from the request, so the response is the
same for every caller asking at the same moment in the configured zone (VĐ-10).

There is **no `{month}` path and no `?month=` parameter**: the month is the one after the month in
progress, which is what UC-25 asks for. The forecast is in the caller's own records and calls no
external service, which is why there is no `ai.enabled` switch to consult here.

## 6. Recent activity — 75–76

### 75 — `GET /api/v1/recent-activity?limit=10`

The transactions the caller most recently viewed or edited, most recent first, each entry carrying
enough to render a line without a second request.

**One transaction can appear twice.** An entry is identified by the transaction **and** the action, so a
record the student both read and changed has one `VIEWED` entry and one `EDITED` entry. They are
different facts and the list keeps them apart.

**A transaction in the trash is not listed.** Moving a record to the trash takes it off this list, and
restoring it puts it back — the activity itself is never deleted, so the entry reappears rather than
being re-recorded.

### 76 — `POST /api/v1/recent-activity`

Records that the caller has just opened or changed one of their own transactions, and returns the entry
as the list would show it.

**This is an explicit request rather than something a read does for you.** Opening a transaction does
not record anything on its own — the transaction read stays a pure read, so a retry or a prefetch never
writes. The client sends this request when the student actually acts.

**Recording the same view twice moves the entry rather than adding one.** The list holds one entry per
transaction and action, so re-opening something brings it back to the top with a new time — which is
what "recently viewed" means.

`occurredAt` is **not accepted and is not sent**. The time comes from the database's clock, so the order
of the list is the order the requests arrived in rather than the order of a client's clock.

```json
POST /api/v1/recent-activity
{ "transactionId": 31, "action": "VIEWED" }
```

## 7. Status codes

| Code | Error code | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | 72, 75: `limit` is out of range. 76: an id or action is missing, or the action is not `VIEWED`/`EDITED` |
| `401` | `UNAUTHENTICATED` | No token, or an invalid, expired or revoked one |
| `404` | `NOT_FOUND` | 76: no such transaction of the caller's |
| `500` | `INTERNAL_ERROR` | 73: the scan read a record it could not write a mark for — the student's own data changed mid-request. Reload and try again |

## 8. Security properties

- **A flag is never client-supplied.** 73 has no request body, and 72 is a read. The client cannot mark
  a record, cannot unmark one, and cannot mark its own record as reviewed. The marks are written only
  by `sp_flag_transaction`, called with a value the detector computed.
- **Ownership is enforced by the procedure.** `sp_flag_transaction` checks the transaction belongs to
  the caller (`BR-02`) before writing, because `fk_txn_user` proves the row exists, not whose it is —
  without the check one student could flag another's record. 76's `RecordRecentActivityRequest` is
  refused by the same rule.
- **Nothing here calls an external service.** There is no AI provider in these three features, so there
  is no context to prepare and no data leaving the server. That is why the forecast needs no
  `ai.enabled` gate.
- **The forecast's evidence is published.** `recentMonths` carries the months the average rests on, so
  the projection can be checked against the student's own history rather than trusted.
- **A `404` covers "does not exist" and "belongs to someone else"** on 76, so the endpoint cannot be
  used to discover other students' transaction identifiers.

## 9. Angular integration notes

- **Split "list" from "rescan".** 72 is cheap and changes nothing; 73 writes. Call 73 when the student
  asks for a check (or on an explicit refresh action), not on every render — even though a repeat write
  is provably free, the *first* scan of new data is not.
- **Show `flagNote` verbatim.** It is written in the student's terms and names the category, so it
  stands alone where the flag appears.
- **Treat an empty flagged list as a good result**, not as "not loaded". There is no `404` to
  distinguish; an empty `entries` array is a real answer.
- **Render absent blocks as absent, not as 0.00.** When `projected` is missing, say the projection needs
  a complete month first; when `currentMonthTotals` is missing, say nothing has been recorded this
  month. A zero would be a claim about the student's month rather than a statement about the data.
- **Use `basedOnMonths` and `recentMonths` for the "how was this worked out?" disclosure.** They are
  published for exactly that.
- **Call 76 on the student's action**, not on the transaction fetch. A screen that fetches a
  transaction to render it should not record a view; a screen where the student opens the detail
  should.
- **Do not send `occurredAt`.** It is ignored; the server's clock decides the order.

## 10. Traceability

| UC | Endpoint | Database / service |
|---|---|---|
| UC-24 list flagged records | 72 | `transactions.is_flagged` / `flag_type` / `flag_note`, `ix_txn_flagged` |
| UC-24 scan | 73 | `sp_flag_transaction` (the sole write path), `trg_transactions_after_update` (BR-09 history) |
| UC-25 forecast | 74 | `v_monthly_summary`-style reads over `transactions`, in `ForecastService` |
| UC-26 list activity | 75 | `recent_activity` |
| UC-26 record activity | 76 | `sp_touch_recent_activity` |

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list, and the two `db/` changes this
  module carries.
- [transactions.md](transactions.md) — the transactions these endpoints read.
- [../modules/MODULE_12_ADVANCED.md](../modules/MODULE_12_ADVANCED.md) — the module report.
