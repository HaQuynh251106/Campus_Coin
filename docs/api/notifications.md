# Notifications API

Module 6 of the Campus Coin backend. Covers **UC-14 (be told when a budget is close to or over its
limit, and read that message)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint · **Required role** `STUDENT`

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [How an alert comes to exist](#4-how-an-alert-comes-to-exist)
5. [BR-12: one message per threshold, per category, per month](#5-br-12-one-message-per-threshold-per-category-per-month)
6. [The seven types](#6-the-seven-types)
7. [`GET /api/v1/notifications`](#7-get-apiv1notifications)
8. [`GET /api/v1/notifications/{id}`](#8-get-apiv1notificationsid)
9. [`POST /api/v1/notifications/{id}/read`](#9-post-apiv1notificationsidread)
10. [What this module does not offer](#10-what-this-module-does-not-offer)
11. [Status codes](#11-status-codes)
12. [Angular integration notes](#12-angular-integration-notes)
13. [Security properties](#13-security-properties)
14. [Traceability](#14-traceability)

---

## 1. Scope and what is deliberately absent

UC-14 tells a student when they are approaching a spending limit and when they have passed it, and
lets them read the message. This module implements exactly the *reading* half — because the writing
half is not this module's to do.

| Not implemented | Why |
|---|---|
| `POST /notifications` — create one | Every notification is written by the procedure that owns it: a budget alert by `sp_check_budget_alerts`, an announcement by the administrator procedure (module 11), a tip or insight by later modules. None of them is the student's to author, and an endpoint able to write one would be a way to write one *to somebody else* |
| `DELETE /notifications/{id}` | A notification is a record that the student **was told**. Deleting it would erase the fact of the warning, not just the message |
| A way to mark one **unread** | The transition is one-way and the database owns it. `sp_mark_notification_read` has `AND is_read = 0`, and `ck_notif_read` treats the pair as read-with-a-timestamp or unread-without-one. A message that has been seen has been seen |
| `PATCH /notifications/{id}` with `{"isRead": ...}` | The read-state change is a statement that *also* proves ownership, in one `UPDATE`. A body able to set the field would move the row without that predicate, and would additionally allow un-reading |
| `POST /notifications/read-all` | Not a UC-14 step, and it would need a bulk statement the schema does not provide — the only procedure is per-notification. A client that wants it issues the per-row calls it already has the ids for |
| A `?unread=true` filter | The response carries `isRead` and the client already knows what it wants to show — the same decision modules 3, 4, 5 and 6's budget half recorded about filters |
| A `?type=` filter | Same reason. The client has the flag and the list is bounded |
| Pagination | A student has a bounded number of messages: one per alert per category per month (BR-12), plus announcements. UC-14 does not ask for it |
| An endpoint that raises an alert | The alert belongs to the **transaction** that crossed a threshold — see §4 |
| An endpoint that sends an email or push | UC-14 defines the in-app notification. Delivery outside the app is not a requirement this project has, and no provider is configured |
| The announcement, tip and insight generators | Modules 9, 11 and 12. Their types are already readable here — see §6 |

---

## 2. Endpoints at a glance

| # | Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|---|
| 32 | `GET` | `/api/v1/notifications` | UC-14 | List my messages, newest first | `200` |
| 33 | `GET` | `/api/v1/notifications/{id}` | UC-14 | Read one of mine (**does not** mark it read) | `200` |
| 34 | `POST` | `/api/v1/notifications/{id}/read` | UC-14 | Mark one of mine read | `200` |

All three require `Authorization: Bearer <accessToken>` and an account whose role is `STUDENT`. An
administrator token is refused with `403` — see §13.

**Three endpoints, and the two GETs are not a duplicate.** The list answers "what have I missed" and
is what a bell icon or a notifications screen calls. Reading one by id answers a deep link or a
refresh of a single row, and it deliberately does **not** mark it read: opening a message and
acknowledging it are different actions, and a client that merely links to one should not silently
clear the unread marker. The `POST` to `/read` is that acknowledgement.

**Marking read is a `POST` to a `/read` sub-path, not a `PATCH` of a field.** See §9.

---

## 3. Field reference

Every field is read-only: there is no request body anywhere in this module.

| Field | Type | Always present | Source |
|---|---|---|---|
| `id` | number | yes | `notifications.id` |
| `type` | string enum | yes | `notifications.type` — one of seven, see §6 |
| `title` | string | yes | `notifications.title` |
| `body` | string | **omitted when unset** | `notifications.body` |
| `linkUrl` | string | **omitted when unset** | `notifications.link_url` |
| `refEntityType` | string | **omitted when unset** | `notifications.ref_entity_type` |
| `refEntityId` | number | **omitted when unset** | `notifications.ref_entity_id` |
| `isRead` | boolean | yes | `notifications.is_read` |
| `readAt` | string datetime | **omitted when unset** | `notifications.read_at` |
| `createdAt` | string datetime | yes | `notifications.created_at` |

`userId` is **absent**, deliberately: it is the field a client would send to read somebody else's
notifications, and it has no other use. That matters more here than on most tables, because a
notification's title and body are readable prose about the student's own spending — a single missing
filter would leak a sentence rather than a number.

### Fields that are absent rather than null

Five fields are **omitted from the response** when they have no value, rather than sent as `null`:
`body`, `linkUrl`, `refEntityType`, `refEntityId` and `readAt`. That is the convention the earlier
modules set, and there is one rule to apply — "no value means the key is absent" — rather than two.

```json
{
  "id": 17,
  "type": "BUDGET_NEAR",
  "title": "Approaching budget limit: Food & Drinks",
  "body": "You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).",
  "linkUrl": "/budgets",
  "refEntityType": "BUDGET",
  "refEntityId": 3,
  "isRead": false,
  "createdAt": "2026-09-24T18:02:11"
}
```

That message is unread, so `readAt` is missing.

### `isRead` and `readAt` always agree

`ck_notif_read` requires it: a notification is unread with **no** timestamp, or read **with** one.
So:

| `isRead` | `readAt` |
|---|---|
| `false` | absent |
| `true` | present |

A client needs only `isRead` to style a row, but `readAt` answers "when", which a "you were warned on
the 24th" line needs. Publishing the pair makes the database's own invariant visible rather than
implied — and a client should not have to guess which of the two is authoritative for the unread
count. It is `isRead`.

### Timestamps are local times with no zone

`createdAt` and `readAt` are serialised as `2026-09-24T18:02:11` — a local date-time with **no `Z`
and no offset**. They are the database's `NOW()` in the session's zone, which the application pins to
`+07:00` (`Asia/Ho_Chi_Minh`, VĐ-10), so they are Vietnam wall-clock times.

This is worth stating because it is a trap for a client. `new Date('2026-09-24T18:02:11')` — with no
zone designator — is parsed by ECMAScript as **local time**, which is what a student in Vietnam wants
but is *not* what a browser set to another zone would show. If the app must display these in the
student's own zone rather than the campus's, append the offset explicitly
(`new Date('2026-09-24T18:02:11+07:00')`) rather than leaving `Date` to guess. The API does not claim
a timezone guarantee beyond "these are `Asia/Ho_Chi_Minh` wall-clock times"; it publishes no offset.

### `refEntityType` and `refEntityId`

The pointer back to whatever raised the message. For a budget alert they are `"BUDGET"` and the
budget's id, and a client uses them to open the right screen. Publishing them is safe for the same
reason the notification itself is: a row only ever exists for its own owner, so the reference can
only ever name something the caller can already reach.

**A budget alert's reference can dangle.** Deleting a budget removes its alert-log rows but leaves the
notifications that were already sent — see [budgets.md §10](budgets.md#10-delete-apiv1budgetsid). A
client following a `refEntityId` should be ready for `404` and show "that budget is no longer set"
rather than an error.

---

## 4. How an alert comes to exist

**This module writes nothing.** Every notification row is created by a stored procedure, and a budget
alert is created by a *transaction*:

```
POST /api/v1/transactions  (module 4)
        │
        ▼
trg_transactions_after_insert  /  trg_transactions_after_update
        │  (whenever NEW.is_deleted = 0)
        ▼
sp_check_budget_alerts(user_id, category_id, first-of-month)
        │
        ├── reads the limit for that category and month
        ├── sums the month's live spending for that category
        ├── computes the percentage, against the two system_settings thresholds
        ├── INSERT IGNORE into budget_alert_log (budget_id, threshold_type)
        └── if that insert was new → INSERT into notifications + record its id
```

Three consequences for a client:

| Consequence | Detail |
|---|---|
| **Recording an expense is what raises the alert** | Not setting a budget, not editing one, not opening a screen. A student who sets a 300 limit in a month where they have already spent 250 gets **no** alert — see [budgets.md §5](budgets.md#5-setting-a-limit-raises-no-alert) |
| **The alert count is bounded by BR-12** | At most two messages per category per month: one `BUDGET_NEAR`, one `BUDGET_EXCEEDED`. See §5 |
| **Nothing in this module can raise one** | There is no endpoint that calls `sp_check_budget_alerts`. Giving one would be a second trigger for the same procedure, one that could write the alert-log row BR-12's unique key has already, correctly, decided against |

Because alerts travel with transactions, `trg_transactions_after_update` means an **edited**
transaction can also raise one — raising an expense's amount from 200 to 280 can cross the near
threshold on an update, not only on an insert.

---

## 5. BR-12: one message per threshold, per category, per month

BR-12 says a threshold alerts **at most once** per category per month. The mechanism is
`budget_alert_log`, whose unique key is `uk_alert_budget_threshold (budget_id, threshold_type)`, and
the procedure's `INSERT IGNORE` into it:

| What happens | Result |
|---|---|
| Spending crosses 80% for the first time this month | The `NEAR` log row is new → a `notifications` row is written |
| Spending crosses 80% again (after a soft delete and restore, say) | The `NEAR` log row already exists → `INSERT IGNORE` changes nothing → **no** second message |
| Spending crosses 100% | The `EXCEEDED` log row is new → a second message, distinct from the near one |
| The limit is lowered below what is already spent | The status recomputes to `EXCEEDED` on every read, and if the threshold never fired there is no message at all — the crossing was not caused by a transaction |
| The limit is removed and set again | The `DELETE` cascades to `budget_alert_log`, so the next crossing **does** alert again. This is the one way to get a second alert for a category in a month, and it is a genuine new limit |

So a student sees **at most two** budget messages per category per month, and each names a threshold
that was crossed exactly once. This is why "my budget says EXCEEDED but I only have one notification"
is not a bug: the second threshold may never have been crossed by a transaction.

### The two messages, verbatim

The prose is written by the procedure, not by this API. These are its literal strings:

```json
{
  "id": 17,
  "type": "BUDGET_NEAR",
  "title": "Approaching budget limit: Food & Drinks",
  "body": "You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).",
  "linkUrl": "/budgets",
  "refEntityType": "BUDGET",
  "refEntityId": 3,
  "isRead": false,
  "createdAt": "2026-09-24T18:02:11"
}
```

```json
{
  "id": 21,
  "type": "BUDGET_EXCEEDED",
  "title": "Budget exceeded: Food & Drinks",
  "body": "You have spent 320.00 of 300.00 (106.67%) on Food & Drinks.",
  "linkUrl": "/budgets",
  "refEntityType": "BUDGET",
  "refEntityId": 3,
  "isRead": false,
  "createdAt": "2026-09-25T09:14:03"
}
```

Two notes on rendering them:

- **The amounts in the body are the procedure's `CONCAT`, not formatted by the client.** They carry
  the column's scale (`320.00`), and the message is a sentence — a client should display `body` as
  text, not try to restyle the numbers inside it.
- **`linkUrl` is a server-provided route** (`/budgets`). It is the API telling the client where the
  message belongs, and it is the same string on every budget alert. A client may prefer to derive its
  own route from `refEntityType` and `refEntityId`; both are published for that reason.

---

## 6. The seven types

`notifications.type` is a MySQL `ENUM` with seven members, and **all seven are published** — not only
the two this module produces. A client switching on `type` should handle each, and a value it does
not recognise should be rendered generically rather than crashing the list.

| Type | Produced by | Module |
|---|---|---|
| `BUDGET_NEAR` | `sp_check_budget_alerts` | 6, this module |
| `BUDGET_EXCEEDED` | `sp_check_budget_alerts` | 6, this module |
| `RECURRING_POSTED` | the recurring scheduler | 5 |
| `TIP` | `sp_generate_tips` | 9 (not yet built) |
| `ANNOUNCEMENT` | the administrator announcement procedure | 11 (not yet built) |
| `SYSTEM` | written outside any single use case | — |
| `INSIGHT_READY` | the monthly insight generator | 12 (not yet built) |

This is why the enum is mapped in full even though only two members are produced today: the column is
a MySQL `ENUM`, so an **unmapped** member would be a value the application cannot deserialise — a row
written by a later module would fail to load and take the whole notification list down with it,
rather than simply appearing as an unfamiliar kind. Mapping the complete set costs nothing and makes
the read path total.

---

## 7. `GET /api/v1/notifications`

List every notification addressed to the caller, **newest first**.

### Request

No parameters. No filter, no pagination — see §1.

```
GET /api/v1/notifications
Authorization: Bearer <accessToken>
```

### Response `200 OK`

An array, ordered by `createdAt` descending then `id` descending. **Read and unread are returned
together**: the screen this serves is a "what have I missed" list, where hiding the read ones would
remove the context for the unread ones.

The `id` tiebreak makes the order total: `sp_check_budget_alerts` uses `NOW()`, and two alerts written
in one database transaction genuinely can share a timestamp. Without the tiebreak MySQL could return
equal rows in either order, making the list appear to shuffle between two identical calls.

```json
[
  {
    "id": 21,
    "type": "BUDGET_EXCEEDED",
    "title": "Budget exceeded: Food & Drinks",
    "body": "You have spent 320.00 of 300.00 (106.67%) on Food & Drinks.",
    "linkUrl": "/budgets",
    "refEntityType": "BUDGET",
    "refEntityId": 3,
    "isRead": false,
    "createdAt": "2026-09-25T09:14:03"
  },
  {
    "id": 17,
    "type": "BUDGET_NEAR",
    "title": "Approaching budget limit: Food & Drinks",
    "body": "You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).",
    "linkUrl": "/budgets",
    "refEntityType": "BUDGET",
    "refEntityId": 3,
    "isRead": true,
    "readAt": "2026-09-24T18:30:00",
    "createdAt": "2026-09-24T18:02:11"
  }
]
```

A student with no messages gets `200` and `[]`, never `404`.

**Counting unread is a client-side `filter`.** There is no unread count endpoint: the list is
bounded, so the client counts `isRead === false` from the rows it already has. A separate endpoint
would be a second way to ask a question the list already answers.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |

---

## 8. `GET /api/v1/notifications/{id}`

Read one of the caller's own notifications, so a client can open a single message without loading the
whole list.

### Request

| Parameter | In | Type | Notes |
|---|---|---|---|
| `id` | path | number | The notification's identifier |

```
GET /api/v1/notifications/17
Authorization: Bearer <accessToken>
```

### Response `200 OK`

One notification object, the same shape as a list element. **It is not marked read.** Opening a
message and acknowledging it are different actions — see §9.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `id` is not a number |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such notification **of the caller's** |

Another student's notification is not reachable here, and neither is one that does not exist. **Both
answer `404`, identically**, so the endpoint cannot be used to discover which notification identifiers
exist.

---

## 9. `POST /api/v1/notifications/{id}/read`

Mark one of the caller's own notifications as read, and record when.

### Request

No body. The identity of the message being read is the path, and the owner is the token.

```
POST /api/v1/notifications/17/read
Authorization: Bearer <accessToken>
```

### Response `200 OK`

The notification as it now is: `isRead: true` and `readAt` set to the moment it was **first** read.

```json
{
  "id": 17,
  "type": "BUDGET_NEAR",
  "title": "Approaching budget limit: Food & Drinks",
  "body": "You have used 81.5% of your Food & Drinks budget (244.50 of 300.00).",
  "linkUrl": "/budgets",
  "refEntityType": "BUDGET",
  "refEntityId": 3,
  "isRead": true,
  "readAt": "2026-09-25T14:18:12",
  "createdAt": "2026-09-24T18:02:11"
}
```

### Marking an already-read notification is not an error

Re-sending the request answers `200` with the same body, and **`readAt` does not move**. The
statement is `UPDATE ... SET is_read = 1, read_at = NOW() WHERE id = ? AND user_id = ? AND is_read = 0`,
and the `AND is_read = 0` makes it one-way at the database: an already-read row matches nothing and is
left untouched.

So a retry, or two devices marking the same message, settle on the **first** time it was read rather
than the most recent attempt. This mirrors the recurring-rule module's handling of a status already
set: a request that asks for the state the row is already in changes nothing and is not refused.

`markingReadTwiceKeepsTheFirstTimestamp` pins exactly this.

### Why a POST sub-path rather than `PATCH {"isRead": true}`

The change is a single statement that **also proves ownership** — the `user_id` predicate is in the
same `UPDATE` that writes. Three things follow:

1. A request body able to set `isRead` would move the row without that predicate, which is the one
   thing the design exists to prevent.
2. It would additionally allow **un-reading**, which `ck_notif_read`'s pair and the meaning of a
   notification both treat as not something that happens.
3. The field is not the caller's to choose — there is one transition and it has one direction. A
   sub-path named `/read` says that; a boolean field implies two values are both writable.

There is **no** endpoint that marks a notification unread. The one-way nature is asserted by
`thereIsNoUnreadEndpoint`, which probes both a `DELETE .../read` and a `PUT` with a body.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `BAD_REQUEST` | `id` is not a number. An unsupported method on this path also answers `4xx` — the framework reports it as a bad request rather than a 405 |
| `401` | `UNAUTHENTICATED` | No token, or the token is invalid, expired or revoked |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such notification of the caller's |

---

## 10. What this module does not offer

A checklist of the things a client might expect and will not find, with the reason, so nobody
builds a screen against a route that does not exist:

| Not offered | Why | What to do instead |
|---|---|---|
| Mark all as read | Needs a bulk statement the schema does not provide | Issue the `POST .../read` calls for the ids the list already has |
| Delete a message | It is a record that the student was told | Leave it read |
| An unread **count** endpoint | The list is bounded and already carries `isRead` | `notifications.filter(n => !n.isRead).length` |
| An unread-only list | Same | Filter client-side |
| Pagination | UC-14 does not ask for it | Load the list |
| Real-time push (websocket/SSE) | Not a stated requirement, and no transport is configured | Re-fetch the list when the client wants fresh data — the list is small |
| Email or push delivery | Not a stated requirement; no provider is configured | The in-app list is the notification |
| Raising a test alert | Would be a second trigger for `sp_check_budget_alerts` | Record a real expense through `POST /transactions` and watch the alert appear — this is what the manual test procedure does |

---

## 11. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body |
| `400` | `id` is malformed, or the method is not supported on the path | Treat as a client bug |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `403` | The token's role is not `STUDENT` | Send the user to their own area; this is not a sign-in problem |
| `404` | No such notification of the caller's | Reload the list; the message is gone, or was never the caller's. Also the right handling for a link whose referenced budget has been deleted |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

**This module has no `409` and no `400 VALIDATION_ERROR`**, and that is not an omission. There is no
request body to validate, no unique key to collide with, and no state machine a caller could reach
an invalid transition in — the only write is idempotent and one-way. `errorCode` is `UNAUTHENTICATED`,
`ACCESS_DENIED`, `BAD_REQUEST` or `NOT_FOUND`, in this module's paths.

**Switch on `errorCode`, not on `message`.** The code is the stable part of the contract.

---

## 12. Angular integration notes

### 12.1 There is no notification model or service in the frontend

`NotificationApiIT` and this document describe a resource the frontend does not have. What exists
today is **not** a notification model but a locally-invented alert type inside the budget service:

```ts
export interface BudgetAlertNotification {
  budgetId: string;
  categoryName: string;
  monthlyLimit: number;
  spent: number;
  percent: number;
  status: 'WARNING' | 'DANGER';
  message: string;
}
```

and a method, `BudgetService.getBudgetAlerts()`, that **synthesises** these by mapping over the budget
list and building a `message` string in the client:

```ts
message: `Over budget! You have spent $${b.spent} of your $${b.monthlyLimit} limit (${percent}%) on ${b.categoryName}.`
```

**That whole mechanism should be deleted and replaced with `GET /api/v1/notifications`.** Three
reasons it cannot be reconciled:

1. **The text will not match.** The real messages are produced server-side by
   `sp_check_budget_alerts`, in the wording §5 shows. Two sources of user-facing prose for one event
   is two things to keep in sync, and they will drift.
2. **The client cannot model "was told once".** The synthesised list re-appears on every reload
   because it is recomputed from current state, whereas a real notification is a row that was written
   once and is then read or unread. BR-12 is not representable client-side.
3. **The status vocabulary differs.** The frontend uses `'WARNING' | 'DANGER'`; the API uses
   `BUDGET_NEAR` / `BUDGET_EXCEEDED`. The API's is the contract.

The budget model's own divergence (`SAFE|WARNING|DANGER`, `monthlyLimit`, `spent`, `period`) is
documented in [budgets.md §14.1](budgets.md#141-the-current-frontend-must-be-rewired).

### 12.2 The model to build

```ts
export type NotificationType =
  | 'BUDGET_NEAR' | 'BUDGET_EXCEEDED' | 'RECURRING_POSTED'
  | 'TIP' | 'ANNOUNCEMENT' | 'SYSTEM' | 'INSIGHT_READY';

export interface AppNotification {
  id: number;                        // not string
  type: NotificationType;
  title: string;
  body?: string;                     // absent, not null
  linkUrl?: string;
  refEntityType?: string;
  refEntityId?: number;
  isRead: boolean;
  readAt?: string;                   // absent unless isRead
  createdAt: string;
}
```

`body`, `linkUrl`, `refEntityType`, `refEntityId` and `readAt` are **optional**, because the API omits
them rather than sending `null`. A non-optional field would make TypeScript lie about the wire shape.

### 12.3 Which call to make

- Showing the bell or the list: `GET /api/v1/notifications`. One call; the unread count is a local
  `filter`. Re-fetch when the client wants fresh data.
- Opening one message: the row is already in the list — no call needed. Use
  `GET /api/v1/notifications/{id}` only if the list may be stale or for a deep link.
- Marking read: `POST /api/v1/notifications/{id}/read`, with **no body**, and replace the cached row
  with the response. Safe to call twice; the second call is a `200` that does not move `readAt`.
- Following a message's link: use `refEntityType` / `refEntityId`. Be ready for the target to be gone
  (`404`).

### 12.4 Handling each status

```ts
this.notificationService.markRead(id).subscribe({
  next: updated => this.replaceRow(updated),   // isRead and readAt come from the server
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'NOT_FOUND':
        // The message was removed, or the cached id is stale. Reload rather than show an error.
        this.reload();
        break;
      case 'UNAUTHENTICATED':
        this.auth.signOutLocally();
        this.router.navigate(['/login']);
        break;
      case 'ACCESS_DENIED':
        this.router.navigate(['/forbidden']);
        break;
      default:
        this.toast.show(response.error?.message ?? 'Something went wrong.');
    }
  },
});
```

Two UI consequences worth designing for:

- **Do not optimistically set `isRead` without the server's `readAt`.** The response is the
  authoritative pair, and the timestamp is the first read rather than the latest tap. Replace the row
  with the response instead of mutating it locally.
- **Render an unknown `type` generically.** If a later module ships a new type before the client is
  updated, the row should still appear with its `title` and `body` rather than being dropped or
  breaking the list.

### 12.5 Timestamps

`createdAt` and `readAt` are local wall-clock strings with no zone (see §3). For "2 hours ago" style
display, parse them as Vietnam time explicitly rather than relying on the browser's zone:

```ts
const at = new Date(createdAt.replace(' ', 'T') + '+07:00');
```

Do not compare these strings lexicographically against a client-generated ISO timestamp — the shapes
differ (`2026-09-24T18:02:11` vs `...Z`) and the comparison would be meaningless. Compare parsed
`Date` objects.

---

## 13. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. Every query takes the caller's id as well as the notification's (`NotificationRepository`), so no service method can read another student's message. The mark-read statement carries the `user_id` predicate **inside the same `UPDATE`** that writes, so the ownership decision and the write are one atomic statement |
| **No identifier probing** | Another student's notification and an unknown id both answer `404`, identically, on both read paths |
| **No client-supplied identity** | There is no request body anywhere in this module, so there is no field to carry a user id. A message's owner is always the token's account |
| **No client-supplied read state** | Read-state is set by a procedure that fixes the transition, not by a body field. A caller cannot un-read, and cannot set `readAt` — `NOW()` decides it |
| **No way to write a notification** | There is no create and no delete endpoint. Every row is written by the procedure that owns it, so a student cannot author a message — which would be a way to write one to somebody else |
| **Role enforced server-side** | `/api/v1/notifications/**` requires `hasRole("STUDENT")`. An administrator is refused with `403`: an administrator has no notifications of their own here, and UC-14 is the student's view of their own spend |
| **No sensitive fields** | `NotificationMapper` is the single place that decides what leaves the server. `user_id` is not mapped to the response, and the entity's owner column exists only for the ownership queries |
| **No prose leak** | The list is filtered by owner in the query, so another student's `title` and `body` — readable sentences about their spending — are unreachable. This is the leak that matters most on this table, and the query is the enforcement |
| **Disabled account** | Rejected by the token filter before the request reaches the controller, as `401 UNAUTHENTICATED` (BR-03) |
| **Revoked session / stale token** | Rejected by the token filter on every request, as `401 UNAUTHENTICATED` (UC-02 B5) |
| **No stale read after the write** | Ownership is checked with a `COUNT` (`existsByIdAndUserId`), not by loading the entity. Loading it first would leave a copy in the persistence context and the later read would be served from it, still showing the row as unread — the procedure changes the row with SQL Hibernate never sees. A `COUNT` populates nothing, so the row is loaded once, after the write, and shows its new state |
| **The entity cannot write at all** | `Notification` is `@Immutable` and the repository is read-only except for the ownership count, so an accidental `save` cannot interleave with the procedure |
| **No SQL or schema identifiers in a response** | There is no write through this API to refuse, so no refusal text can reach a client. `404` is a plain message |
| **What is *not* claimed** | This module cannot control what `sp_check_budget_alerts` writes into `title` and `body`: those are the procedure's prose, and they name the student's own category and amounts. That is the point of the message, and it is safe because the row is delivered only to its owner |
| **Atomicity** | Marking read is one statement. A failed call changes nothing |

---

## 14. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-14 | List the caller's messages | `GET /notifications` | `NotificationController.listNotifications` | `NotificationService.listNotifications` | `findForStudent`, `ix_notif_user_unread` | `theListShowsOnlyTheCallersMessages` |
| UC-14 | Newest first | `GET /notifications` | — | — | `ORDER BY created_at DESC, id DESC` | `theListIsNewestFirst` |
| UC-14 | Read and unread together | `GET /notifications` | — | — | no read-state predicate | `markingOneReadLeavesTheOthersUnread` |
| UC-14 | A student with no messages gets an empty array | `GET /notifications` | — | — | — | `emptyStateIsAnEmptyArray` |
| UC-14 | Read one message by id | `GET /notifications/{id}` | `getNotification` | `getNotification` | `findByIdAndUserId` | `readingOneMessageMatchesTheList` |
| UC-14 | Reading one does **not** mark it read | `GET /notifications/{id}` | — | — | — | `readingOneMessageDoesNotMarkItRead` |
| UC-14 B4 | Mark one read, setting the flag and the timestamp together | `POST /notifications/{id}/read` | `markRead` | `markRead` | `sp_mark_notification_read` | `markingReadSetsFlagAndTimestampTogether` |
| UC-14 B4 | Marking twice keeps the first timestamp | `POST .../read` | — | `markRead` | the procedure's `AND is_read = 0` | `markingReadTwiceKeepsTheFirstTimestamp` |
| UC-14 B4 | Marking one leaves the others unread | `POST .../read` | — | `markRead` | per-row statement | `markingOneReadLeavesTheOthersUnread` |
| UC-14 B5 | Read-state is one-way; there is no unread route | — | `NotificationController` route set | — | `ck_notif_read` | `thereIsNoUnreadEndpoint` |
| UC-14 | A message has the documented shape, and only those fields | every read | `NotificationResponse` | `NotificationMapper` | — | `aMessageHasTheDocumentedShape` |
| UC-14, BR-12 | Crossing the near threshold raises exactly one alert | — | — | — | `sp_check_budget_alerts`, `trg_transactions_after_insert`, `budget_alert_log` | `crossingTheNearThresholdRaisesOneAlert` |
| UC-14, BR-12 | Crossing the exceeded threshold raises exactly one alert | — | — | — | `sp_check_budget_alerts`, `uk_alert_budget_threshold` | `crossingTheExceededThresholdRaisesOneAlert` |
| UC-14 | Spending with no limit raises nothing | — | — | — | the procedure's `IF v_budget_id IS NOT NULL` guard | `spendingWithoutALimitRaisesNothing` |
| UC-14 | Setting, changing or removing a budget raises nothing | `POST`, `PATCH`, `DELETE /budgets` | — | `BudgetService` | the trigger is on `transactions`, not `budgets` | `budgetWritesRaiseNoMessages` |
| UC-14 | Deleting the underlying record does not withdraw the alert | `DELETE /budgets/{id}` | — | — | `notifications` untouched | `deletingTheRecordDoesNotWithdrawTheAlert` |
| UC-14 | Reading notifications writes nothing | every read | — | — | `Notification` is `@Immutable` | `readingDoesNotWrite` |
| BR-02 | Another student's message is unreachable | `GET /{id}`, `POST .../read` | `@AuthenticationPrincipal` | `requireOwn`, `existsByIdAndUserId` | owner predicate in every query | `anotherStudentsMessageIsUnreachable` |
| BR-02 | No response carries the owner | every read | `NotificationResponse` | `NotificationMapper` | — | `aMessageHasTheDocumentedShape` |
| §7.5 | Every endpoint needs a token, and only `STUDENT` | all three | `SecurityConfig` role rule | `@AuthenticationPrincipal` | `users.role` | `noTokenIsRefused`, `anAdministratorTokenIsRefused` |
| BR-03 | A revoked session and a disabled account stop working | all three | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at`, `users.status`, `users.token_version` | asserted by the module-1 filters and `noTokenIsRefused` |
| — | A missing message is `404`; a non-numeric id is `400` | `GET /{id}`, `POST .../read` | `@PathVariable` | `requireOwn` | — | `aMissingMessageIsNotFound` |
| §26 | The endpoints are in the inventory and the OpenAPI document | all three | — | — | — | `OpenApiContractIT#documentMatchesTheInventory`, `#notificationSchemasMatchTheDocumentedContract` |

### Notes on the tests

- **Every UC-14 fixture is a transaction, not an inserted notification.** This module writes no
  notification rows — only `sp_check_budget_alerts` does — so the read-path tests double as proof of
  the flow §4 describes. A test that inserted a `notifications` row directly would prove the mapper
  works and nothing about UC-14.
- **The concurrency question here is about the trigger, not this API.** Two transactions crossing the
  same threshold at once are settled by `uk_alert_budget_threshold` and `INSERT IGNORE`, which is
  module 4's write path. Module 6 therefore has no concurrency test of its own; the guarantee is
  `crossingTheNearThresholdRaisesOneAlert` plus the unique key, and it is tested where the write
  happens.
- **`thereIsNoUnreadEndpoint` asserts `is4xxClientError()` rather than a specific code.** The
  framework answers `404` when nothing is mapped at a path and `405` when the path is known but the
  method is not — and `GlobalExceptionHandler` maps an unsupported method to a `400`. What matters is
  that none of them is a success, and asserting the exact code would be asserting the framework's
  routing rather than this module's contract. Module 5's tests use the same form.

---

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [budgets.md](budgets.md) — module 6's other half, UC-13, where the limits these alerts are about are set
- [transactions.md](transactions.md) — module 4, the writes that actually raise an alert
- [categories.md](categories.md) — module 3, whose retired-category behaviour the budget module inherits
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
- [../modules/MODULE_06_BUDGET.md](../modules/MODULE_06_BUDGET.md) — the module report
