# Categories API

Module 3 of the Campus Coin backend. Covers **UC-06 (manage personal categories)**.

The Angular developer should be able to integrate this module from this document alone.

**Base path** `/api/v1` · **Content type** `application/json` · **Authentication** bearer token on
every endpoint · **Required role** `STUDENT`

---

## Contents

1. [Scope and what is deliberately absent](#1-scope-and-what-is-deliberately-absent)
2. [Endpoints at a glance](#2-endpoints-at-a-glance)
3. [Field reference](#3-field-reference)
4. [Default categories and personal categories](#4-default-categories-and-personal-categories)
5. [GET /api/v1/categories](#5-get-apiv1categories)
6. [GET /api/v1/categories/{id}](#6-get-apiv1categoriesid)
7. [POST /api/v1/categories](#7-post-apiv1categories)
8. [PATCH /api/v1/categories/{id}](#8-patch-apiv1categoriesid)
9. [DELETE /api/v1/categories/{id}](#9-delete-apiv1categoriesid)
10. [The three rules the database enforces](#10-the-three-rules-the-database-enforces)
11. [Status codes](#11-status-codes)
12. [Angular integration notes](#12-angular-integration-notes)
13. [Security properties](#13-security-properties)
14. [Traceability](#14-traceability)

---

## 1. Scope and what is deliberately absent

UC-06 lets a student maintain **their own** categories and use the shared ones. This module
implements exactly that.

| Not implemented | Why |
|---|---|
| `PUT` for the whole category | UC-06 describes editing values, not replacing a record. `PATCH` is the method that matches, and one endpoint is enough |
| `GET /profile/me/categories` | Unlike the profile, a category is one of many and is addressed by id. Putting the list under `/profile/me` would imply a single owned record, which is the opposite of what it is |
| `?type=EXPENSE` on the list | The client needs one set of choices for a picker, and the two types are already distinguished by `type` in the response. A filter would be a second way to ask the same question, and the tabs in the UI can filter the one list themselves |
| Administering a **default** category | That is UC-20, in module 11, under `/api/v1/admin/**`. A student can use a default category but not edit or delete it (BR-06) |
| `user_id` in any request | The owner comes from the token. Accepting it would make BR-02 a check that could be forgotten instead of a structural property |
| A category-per-user limit | No such limit exists in the SRS, the Use Case document or the schema. None was invented |
| Category rules for AI auto-categorisation | That is `category_rules`, UC-08, in module 12 |

---

## 2. Endpoints at a glance

| Method | Endpoint | UC | Purpose | Success |
|---|---|---|---|---|
| `GET` | `/api/v1/categories` | UC-06 | List the categories I can use | `200` |
| `GET` | `/api/v1/categories/{id}` | UC-06 | Read one of my own | `200` |
| `POST` | `/api/v1/categories` | UC-06 | Create a personal category | `201` |
| `PATCH` | `/api/v1/categories/{id}` | UC-06 | Change some fields of one of mine | `200` |
| `DELETE` | `/api/v1/categories/{id}` | UC-06 | Delete one of mine | `204` |

All five require `Authorization: Bearer <accessToken>` and an account whose role is `STUDENT`. An
administrator token is refused with `403` — see §13.

Both write endpoints return the category **as it now is in the database**, so the client can
replace its cached copy rather than guess what the server did with the values it sent.

---

## 3. Field reference

The response of every endpoint, and the request fields of the two write endpoints.

| Field | Type | In request | Validation when supplied | Source |
|---|---|---|---|---|
| `id` | number | read-only | — | `categories.id` |
| `name` | string | required on create, optional on update | 1–80 characters after trimming, must contain a non-space character | `categories.name` |
| `type` | string enum | required on create, optional on update | `INCOME` \| `EXPENSE` | `categories.type` |
| `icon` | string | optional | at most 50 characters after trimming | `categories.icon` |
| `color` | string | optional | `#` followed by exactly six hex digits, e.g. `#F59E0B` | `categories.color` |
| `description` | string | optional | at most 255 characters after trimming, newlines permitted | `categories.description` |
| `sortOrder` | number | optional | 0–32767 | `categories.sort_order` |
| `isActive` | boolean | optional | `true` \| `false` | `categories.is_active` |
| `isDefault` | boolean | read-only | — | derived from `categories.user_id` |

### Fields that are absent rather than null

`icon`, `color` and `description` are **omitted from the response** when they have no value, rather
than sent as `null`. The Angular model already declares all three as optional (`icon?:`), so this
matches; a client checking `if (cat.description)` works either way, and a client that renders
`cat.color` unconditionally will render an empty string rather than the text `"null"`.

Every other field is always present.

### Whitespace and case

`name`, `icon`, `color` and `description` are trimmed before the length rule is applied and before
storage, so the documented length is the length of the value that is actually saved. A name of 80
characters padded with spaces is accepted; the padding is not stored.

A whitespace-only `icon`, `color` or `description` is treated as "not set" and stored as `null`.
A whitespace-only `name` is rejected.

`color` is **normalised to upper case**: sending `#f59e0b` stores and returns `#F59E0B`. This
matches every hex colour in the seed data. `name` is **not** case-changed — `"Coffee"` and
`"coffee"` are stored as sent.

### Enum values are member names, never numbers

`type` accepts the member name the database stores, in upper case: `"INCOME"` or `"EXPENSE"`. It
does **not** accept a number. `0` is rejected with a field error rather than read as the ordinal
`INCOME` — an ordinal encoding would silently change meaning if the Java constants were reordered.
Lower case (`"expense"`) is also rejected. Send the member name exactly.

**Defaults for a new category**, as the database declares them: `sortOrder` is `0`, `isActive` is
`true`, and the three optional text fields are `null`.

---

## 4. Default categories and personal categories

Two kinds of row live in the same table and are distinguished by `isDefault`. Both are returned by
the list, because a picker offers one set of choices.

| | Default (`isDefault: true`) | Personal (`isDefault: false`) |
|---|---|---|
| Who owns it | Nobody — it is shared by every student | The student who created it |
| In the list | Yes | Yes |
| Readable by id | No — `GET /{id}` answers `404` | Yes |
| Editable / deletable | No — `PATCH` and `DELETE` answer `404` | Yes |

Twelve default categories are seeded, 5 income and 7 expense:

| Type | Names |
|---|---|
| `INCOME` | Allowance, Part-time Job, Scholarship, Gift, Other Income |
| `EXPENSE` | Food, Transport, Hostel/Rent, Academics, Subscriptions, Entertainment, Miscellaneous |

They are **not copied per student**. Every student sees the same twelve rows, so a newly registered
account has categories immediately without a setup step (UC-01 B5).

A default category reads back as `404` from `GET /{id}` rather than `403`. That is deliberate: the
student's route to it does not exist, and answering `403` would tell a caller that the id names a
real row, which is the beginning of an enumeration probe. The same answer covers another student's
category.

---

## 5. `GET /api/v1/categories`

Returns every category the caller can choose from: the shared defaults plus their own.

### Request

No body.

```bash
curl -X GET http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

A JSON **array**, ordered by `type` and then by `sortOrder`.

```json
[
  {
    "id": 1,
    "name": "Allowance",
    "type": "INCOME",
    "icon": "wallet",
    "color": "#22C55E",
    "isDefault": true,
    "isActive": true,
    "sortOrder": 1
  },
  {
    "id": 13,
    "name": "Campus Cafe",
    "type": "INCOME",
    "isDefault": false,
    "isActive": true,
    "sortOrder": 0
  },
  {
    "id": 6,
    "name": "Food",
    "type": "EXPENSE",
    "icon": "utensils",
    "color": "#F97316",
    "isDefault": true,
    "isActive": true,
    "sortOrder": 10
  }
]
```

The order is stable and is the order to display in. Note that within a type the student's own
categories usually come **first**, because a new category defaults to `sortOrder` 0 while the
seeded defaults start at 1 (income) and 10 (expense). On a tie of both type and `sortOrder`, the
shared default is listed before the personal one.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | No token, or it is malformed, tampered with, expired, revoked, or the account was disabled after the token was issued (BR-03) |
| `403` | `ACCESS_DENIED` | The token carries a role other than `STUDENT` |

On these endpoints every one of those `401` causes is reported as `UNAUTHENTICATED`. A disabled
account is **not** given the distinct `ACCOUNT_DISABLED` code here: the token filter rejects it
before the request reaches the controller, and its entry point deliberately reports no-token,
bad-signature, expired, revoked and disabled identically, so the caller cannot use the difference
to discover anything. `ACCOUNT_DISABLED` belongs to the **sign-in** endpoints, where the account
being disabled is the caller's own message to receive — see
[authentication.md](authentication.md). The client's action is the same in both cases: clear the
session and return to sign-in.

---

## 6. `GET /api/v1/categories/{id}`

Reads one of the caller's **own** categories, so a client can refresh a single row after a change
without reloading the list.

### Request

| Parameter | In | Type | Required |
|---|---|---|---|
| `id` | path | number | yes |

```bash
curl -X GET http://localhost:8080/api/v1/categories/13 \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `200 OK`

One category in the shape of §3.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `{id}` is not a number, or is too large for the column |
| `401` | `UNAUTHENTICATED` | As §5 — no token, invalid, expired, revoked, or the account is disabled |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such category **of the caller's** — including a default category and another student's |

---

## 7. `POST /api/v1/categories`

Creates a category owned by the caller.

### Request

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | **yes** | 1–80 characters after trimming |
| `type` | string enum | **yes** | `INCOME` or `EXPENSE` |
| `icon` | string | optional | At most 50 characters |
| `color` | string | optional | `#RRGGBB` |
| `description` | string | optional | At most 255 characters |
| `sortOrder` | number | optional | 0–32767; defaults to `0` |
| `isActive` | boolean | optional | Defaults to `true` |

```bash
curl -X POST http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "name": "Campus Cafe",
        "type": "EXPENSE",
        "icon": "coffee",
        "color": "#F59E0B",
        "description": "Coffee and snacks between classes"
      }'
```

### Response `201 Created`

The category that was created, in the shape of §3, including its assigned `id`.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` | A field failed validation; `fieldErrors` names it |
| `400` | `MALFORMED_REQUEST` | The body is missing, truncated, or not JSON |
| `401` | `UNAUTHENTICATED` | As §5 — no token, invalid, expired, revoked, or the account is disabled |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `409` | `CATEGORY_NAME_TAKEN` | The caller already has this name for this type, or a default category uses it (BR-06) |

---

## 8. `PATCH /api/v1/categories/{id}`

Changes the supplied fields of one of the caller's own categories and leaves the rest as they are.

### Request

A JSON object containing **only the fields to change**. Every field in §3 except `id` is accepted, and
`isDefault` is ignored.

```bash
curl -X PATCH http://localhost:8080/api/v1/categories/13 \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "name": "Cafeteria", "color": "#0EA5E9" }'
```

### Partial update semantics

| Body | Effect |
|---|---|
| `{}` | Nothing changes. No `UPDATE` statement is issued |
| `{ "name": "New" }` | Only `name` changes |
| `{ "icon": "" }` | `icon` is **cleared** (set to `null`) |
| `{ "icon": null }` | `icon` is **left as it is** |
| `{ "isActive": false }` | The category is retired, keeping its history (BR-07) |
| `{ "name": "New", "type": "INCOME" }` | Both change, and the name is checked against the **new** type |

The asymmetry is deliberate. `icon`, `color` and `description` are nullable, so they need a way to
be unset — an empty string does that. `name` is not nullable, so an empty string is rejected rather
than treated as "clear". For every field, `null` means "unchanged"; omitting the key means the same.

`null` and `""` are therefore **not** interchangeable on any field.

### Response `200 OK`

The updated category in the shape of §3.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `400` | `VALIDATION_ERROR` / `MALFORMED_REQUEST` | As §7 |
| `401` | `UNAUTHENTICATED` | As §5 — no token, invalid, expired, revoked, or the account is disabled |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such category of the caller's |
| `409` | `CATEGORY_NAME_TAKEN` | The new name collides (BR-06) |
| `409` | `CATEGORY_IN_USE` | `type` is changing and a transaction, budget or recurring rule already uses the category (BR-05) |

A validation failure writes **nothing**: a body with one valid and one invalid field is rejected
whole, and the valid half is not applied.

---

## 9. `DELETE /api/v1/categories/{id}`

Deletes a category the caller owns, provided nothing refers to it.

### Request

```bash
curl -X DELETE http://localhost:8080/api/v1/categories/13 \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Response `204 No Content`

No body.

### Failures

| Status | `errorCode` | When |
|---|---|---|
| `401` | `UNAUTHENTICATED` | As §5 — no token, invalid, expired, revoked, or the account is disabled |
| `403` | `ACCESS_DENIED` | The token's role is not `STUDENT` |
| `404` | `NOT_FOUND` | No such category of the caller's |
| `409` | `CATEGORY_IN_USE` | A transaction, a budget or a recurring rule uses the category (BR-07) |

**This is a hard delete.** There is no soft-delete for categories: BR-07's "keep the history" is
served by refusing the delete while records point at the row, and by retiring it with
`isActive: false` instead. A category that has ever been used therefore cannot be deleted, and the
records filed under it keep their category.

When the answer is `409`, retire the category rather than deleting it:

```ts
this.http.patch(`/api/v1/categories/${id}`, { isActive: false });
```

---

## 10. The three rules the database enforces

This module does not decide these rules and does not restate them in Java. They belong to the
schema, where they hold for every caller — including a hand-run SQL statement — and they are
translated into the errors above on the way out.

| Rule | Enforced by | Surfaced as |
|---|---|---|
| A personal category may not take a default category's name and type (BR-06) | `trg_categories_before_insert` | `409 CATEGORY_NAME_TAKEN` |
| A student may not have two categories with the same name and type (BR-06) | unique key `uk_categories_scope_type_name` on `(scope_key, type, name)` | `409 CATEGORY_NAME_TAKEN` |
| A category referenced by a transaction, budget or recurring rule may not change type (BR-05) | `trg_categories_before_update` | `409 CATEGORY_IN_USE` |
| A category may not change scope between default and personal | `trg_categories_before_update` | Unreachable from this API — no field writes `user_id` |
| A category with a budget may not be deleted (BR-07) | `trg_categories_before_delete` | `409 CATEGORY_IN_USE` |
| A category with transactions or recurring rules may not be deleted | `fk_txn_category`, `fk_recurring_category`, `fk_budget_category`, all `ON DELETE RESTRICT` | `409 CATEGORY_IN_USE` |

**Why `type` is fixed once used.** `transactions` has no `type` column: a transaction's type **is**
its category's type (BR-05). The same is true of budgets and recurring rules. Changing a category's
type after it has been used would therefore silently rewrite history — every recorded expense would
read back as income in the reports. To genuinely move a category to the other type, create a new
category and move the data across.

**One check the application adds.** The insert trigger refuses a personal category that shadows a
default one, but the update trigger checks only scope and type — so a *rename* could reach exactly
the state the insert rule exists to prevent. `CategoryService.update` applies the same check on the
rename path. That is the only rule in this module that Java decides rather than the database, and
it needs no schema change.

---

## 11. Status codes

| Status | Meaning | Client action |
|---|---|---|
| `200` | Success, body present | Read the body and replace the cached category |
| `201` | Created | Add the returned category to the cached list |
| `204` | Deleted, no body | Remove the category from the cached list |
| `400` | Validation failed, or the body is malformed | If `fieldErrors` is present, show each `message` beside its `field`. Otherwise show `message` |
| `401` | Token missing, invalid, expired or revoked, or the account is disabled | Clear the session and return to sign-in |
| `403` | The token's role is not `STUDENT` | Send the user to their own area; this is not a sign-in problem |
| `404` | No such category of the caller's | Refresh the list; the row is gone or was never the caller's |
| `409` | `CATEGORY_NAME_TAKEN` or `CATEGORY_IN_USE` | Show `message` beside the field; for `CATEGORY_IN_USE` offer "disable instead" |
| `500` | Unexpected failure | Show a generic error; the detail is in the server log only |

`fieldErrors[].field` is the canonical property name project-wide. It is `field`, never `path`.

### Error codes in full

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "CATEGORY_NAME_TAKEN",
  "message": "You already have a category named \"Coffee\" of this type.",
  "path": "/api/v1/categories"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 409,
  "errorCode": "CATEGORY_IN_USE",
  "message": "This category is still used by a transaction, a budget or a recurring rule. Disable it instead of deleting it, so the existing records keep their category.",
  "path": "/api/v1/categories/13"
}
```

```json
{
  "timestamp": "2026-09-25T03:15:30.123Z",
  "status": 400,
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "path": "/api/v1/categories",
  "fieldErrors": [
    { "field": "color", "message": "Colour must be a hex value such as #F59E0B." }
  ]
}
```

**Switch on `errorCode`, not on `message`.** The code is the stable part of the contract; the
message is human-readable text that may be reworded. The two `CATEGORY_*` codes are distinct
because the remedy differs — pick another name, versus stop deleting and disable instead.

---

## 12. Angular integration notes

### 12.1 The current frontend must be rewired

`frontend/src/app/core/models/category.model.ts` declares:

```ts
export interface Category {
  id: string;
  name: string;
  type: CategoryType;
  icon: string;
  color: string;
  isDefault: boolean;
  userId?: string;
  description?: string;
}
```

Three changes are needed:

| Frontend today | API field | Change needed |
|---|---|---|
| `id: string` | `id: number` | The API returns the numeric primary key, not a generated `"cat-usr-…"` string. Anything comparing ids must compare numbers, and the mock generator that built string ids goes away |
| `icon: string` | `icon?: string` | The API omits `icon` when there is none, rather than sending `""` |
| `color: string` | `color?: string` | Same |
| `userId?: string` | — | Not in the contract. Ownership is implied by the token; the API never says who owns a row |
| — | `isActive: boolean` | **New.** Whether the category is offered for new records (BR-07). A retired category still appears in the list and should be shown as disabled |
| — | `sortOrder: number` | **New.** The API already returns the list in the right order, so the client does not need to sort by it |

`category.service.ts` is **entirely mock** — it keeps a `signal` seeded from `MOCK_CATEGORIES` and
never calls `HttpClient`. Every method keeps its name and its `Observable` return type, so the
rewiring is about 25 lines:

```ts
@Injectable({ providedIn: 'root' })
export class CategoryService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/v1/categories';
  private readonly categories = signal<Category[]>([]);

  readonly all = this.categories.asReadonly();

  getCategories(): Observable<Category[]> {
    return this.http.get<Category[]>(this.base)
      .pipe(tap(list => this.categories.set(list)));
  }

  getCategoryById(id: number): Observable<Category> {
    return this.http.get<Category>(`${this.base}/${id}`);
  }

  createCategory(body: CreateCategoryRequest): Observable<Category> {
    return this.http.post<Category>(this.base, body)
      .pipe(tap(created => this.categories.update(list => [...list, created])));
  }

  updateCategory(id: number, body: UpdateCategoryRequest): Observable<Category> {
    return this.http.patch<Category>(`${this.base}/${id}`, body)
      .pipe(tap(updated => this.categories.update(
        list => list.map(c => c.id === updated.id ? updated : c))));
  }

  deleteCategory(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`)
      .pipe(tap(() => this.categories.update(list => list.filter(c => c.id !== id))));
  }
}
```

Two methods the current service has are **not** in this contract and should be dropped rather than
reimplemented: `getExpenseCategories()` / `getIncomeCategories()` (filter the one list yourself, or
add a `computed`) and the client-side `isDefault` guard in `deleteCategory` (the server refuses it;
do not duplicate the rule in the UI, or the UI will refuse for the wrong reason and the error the
API returns will never be seen).

### 12.2 Which call to make

- Entering the categories screen: `GET /api/v1/categories`. One call fills both tabs.
- Opening the edit modal: the row is already in the list — no call needed. Use
  `GET /api/v1/categories/{id}` only if the list may be stale.
- Saving a new category: `POST /api/v1/categories` with `name` and `type` at minimum.
- Saving an edit: `PATCH /api/v1/categories/{id}` with only the changed fields.
- Deleting: `DELETE /api/v1/categories/{id}`.

Send the token as `Authorization: Bearer <accessToken>`. No endpoint in this module works without
one.

### 12.3 Sending only what changed

Build the body from the form's **dirty** fields, as in module 2:

```ts
const dirty = Object.fromEntries(
  Object.entries(form.controls)
    .filter(([, control]) => control.dirty)
    .map(([name, control]) => [name, control.value ?? '']),   // null → '' clears a nullable field
);
if (Object.keys(dirty).length === 0) { return; }
this.categoryService.updateCategory(id, dirty).subscribe(...);
```

The `?? ''` matters. The modal's colour and description inputs hold `''` when empty, but an
untouched `color` control reads as `null`; sending `null` means "unchanged" while `''` means
"clear". Mapping `null` to `''` on the **dirty** fields only — never on undirtied ones — gives the
behaviour a user expects from emptying a colour box.

### 12.4 Handling each status

```ts
this.categoryService.createCategory(body).subscribe({
  next: created => this.toast.show(`"${created.name}" added.`),
  error: (response: HttpErrorResponse) => {
    switch (response.error?.errorCode) {
      case 'VALIDATION_ERROR':
        response.error.fieldErrors?.forEach((e: { field: string; message: string }) =>
          this.form.get(e.field)?.setErrors({ server: e.message }));
        break;
      case 'CATEGORY_NAME_TAKEN':
        this.form.get('name')?.setErrors({ server: response.error.message });
        break;
      case 'CATEGORY_IN_USE':
        // Offer the alternative rather than just reporting the failure.
        this.confirmDisable.set({ id, message: response.error.message });
        break;
      case 'UNAUTHENTICATED':
        // Covers every reason this module answers 401 - including a disabled account, which the
        // token filter reports as UNAUTHENTICATED rather than as its own code (see §5).
        this.auth.signOutLocally();
        this.router.navigate(['/login']);
        break;
      default:
        this.toast.show(response.error?.message ?? 'Something went wrong.');
    }
  },
});
```

`CATEGORY_IN_USE` deserves its own branch: the only way forward is to retire the category, so the
client should offer that instead of leaving the user stuck on a failed delete.

### 12.5 Filtering into tabs

The list contains both types, so filter locally:

```ts
readonly income  = computed(() => this.categories().filter(c => c.type === 'INCOME'));
readonly expense = computed(() => this.categories().filter(c => c.type === 'EXPENSE'));
```

Retired categories (`isActive === false`) stay in the list — that is how a student finds one to
restore. Offer them in the pickers for **new** records behind a visual "disabled" state, or omit
them there and keep them on the management screen; the API does not decide that for you, it only
tells you the state.

---

## 13. Security properties

| Property | How it is enforced |
|---|---|
| **Ownership (BR-02)** | Structural. `CategoryRepository.findByIdAndUserId(id, callerId)` is the only single-row lookup, so no service method can reach another student's row. There is no separate comparison to forget |
| **No identifier probing** | Another student's category and a default category both answer `404`, identically, so the endpoints cannot be used to discover which ids exist |
| **Default categories are read-only for students** | A default has `user_id IS NULL`, which `findByIdAndUserId` can never match |
| **Role enforced server-side** | `/api/v1/categories/**` requires `hasRole("STUDENT")`. An administrator is refused with `403`, because the administrator's route to the same table is UC-20 under `/api/v1/admin/**` and letting them through here would silently create a personal category owned by an administrator |
| **No client-supplied ownership** | No request type has a `userId`, `user_id`, `createdBy`, `isDefault` or `id` field. `CategoryApiIT.categoryRequestCannotSetOwnership` sends all of them and asserts the row is owned by the caller |
| **No scope escalation** | `Category` exposes no setter for `user_id`, so a request cannot turn a personal category into a shared default (which every student would see) |
| **No sensitive fields** | `CategoryMapper` is the single place that decides what leaves the server. `user_id` and `created_by` are not mapped to the response |
| **Disabled account** | Rejected by the token filter before the request reaches the controller, as `401 UNAUTHENTICATED` (BR-03) |
| **Revoked session / stale token** | Rejected by the token filter on every request, as `401 UNAUTHENTICATED` (UC-02 B5) |
| **No account-state oracle** | A disabled account, a revoked session and a stale `token_version` are all `401 UNAUTHENTICATED` with one message, so a token holder cannot use the code to learn an account's state |
| **No lost update** | `Category` is annotated `@DynamicUpdate`, so an UPDATE names only the changed columns |
| **No SQL, constraint name or trigger text in a response** | The refusal is translated by SQLSTATE and constraint name, never forwarded. Asserted by `CategoryApiIT` for every refusal path |
| **No schema identifiers in the module's own log lines** | A refused write logs the operation and the user id, deliberately not the exception — MySQL's duplicate-key message carries the constraint name and the scope key. Asserted against the real log stream in `SecurityHardeningIT.categoryRefusalsLogNoSchemaIdentifiers` |
| **What is *not* claimed** | Hibernate's own `SqlExceptionHelper` logs the raw driver message at `ERROR` whenever a constraint or trigger actually fires, so on the rare concurrent path the driver's text does reach the log through Hibernate. The module cannot suppress that without turning Hibernate's SQL-error logging off, which would hide genuine faults. It is not a data exposure — the text names a schema object and a category name the caller already sent — but the guarantee is exactly "our lines are clean", not "the log is clean". Recorded so no later module inherits an untrue assurance |
| **Audit** | Creates, updates and deletes are logged with the account id and the category id, with no field values |
| **Duplicate submissions** | The second identical create is a `409`, so a double-clicked save produces one row. Six simultaneous identical creates produce one `201`, five `409`s and no `500` |
| **Atomicity** | Each write is one transaction. A refused write changes nothing |

---

## 14. Traceability

| UC | Step / rule | API | Controller | Service | Database object | Test |
|---|---|---|---|---|---|---|
| UC-06 | List usable categories (defaults + own) | `GET /categories` | `CategoryController.listCategories` | `CategoryService.listCategories` | `categories`, filtered by `user_id` | `CategoryApiIT.newStudentSeesTheDefaultsAndNoPersonalCategories`, `CategoryApiIT.createdCategoryIsListedWithTheDefaults` |
| UC-06 | Read one own category | `GET /categories/{id}` | `CategoryController.getCategory` | `CategoryService.getCategory` | `findByIdAndUserId` | `CategoryApiIT.ownCategoryCanBeReadById` |
| UC-06 | Create a personal category | `POST /categories` | `CategoryController.createCategory` | `CategoryService.create` | `categories` INSERT, `scope_key` generated | `CategoryApiIT.personalCategoryPersistsEveryField`, `CategoryApiIT.minimalBodyUsesTheColumnDefaults` |
| UC-06 | Edit a category | `PATCH /categories/{id}` | `CategoryController.updateCategory` | `CategoryService.update` | `categories` UPDATE | `CategoryApiIT.partialUpdateLeavesTheOtherFieldsAlone` |
| UC-06 | Clear an optional field | `PATCH /categories/{id}` | as above | as above | nullable `icon`, `color`, `description` | `CategoryApiIT.emptyStringClearsANullableField` |
| UC-06 | Delete a category | `DELETE /categories/{id}` | `CategoryController.deleteCategory` | `CategoryService.delete` | `categories` DELETE | `CategoryApiIT.unusedCategoryIsDeleted` |
| UC-06 | Reject invalid input | both writes | `CreateCategoryRequest`, `UpdateCategoryRequest` | — | column widths: 80 / 50 / 7 / 255 / `SMALLINT` | `CategoryApiIT.nameLengthBoundaryMatchesTheColumn`, `CategoryApiIT.malformedColourIsRejected`, `CategoryApiIT.unknownTypeIsRejectedPerField`, `CategoryApiIT.missingRequiredFieldsAreReported` |
| UC-06 | A multi-line `description` is valid; the length bound still applies | both writes | `@Pattern` on the DTOs | `trimToNull` | `categories.description VARCHAR(255)` | `CategoryApiIT.multiLineDescriptionIsAccepted` |
| UC-06 | An empty update body is a no-op, not an error | `PATCH /categories/{id}` | — | `CategoryService.update` (no dirty field) | `@DynamicUpdate` | `CategoryApiIT.emptyUpdateBodyChangesNothing` |
| BR-02 | Students act only on their own data | all five | `@AuthenticationPrincipal` | `requireOwnCategory` | `findByIdAndUserId` | `CategoryApiIT.anotherStudentsCategoryIsUnreachable` |
| BR-03 | A revoked session stops working at once | all five | `JwtAuthenticationFilter` | `SessionService` | `user_sessions.revoked_at` | `CategoryApiIT.revokedSessionIsRefused` |
| BR-03 | A disabled account is stopped before the controller | all five | `JwtAuthenticationFilter` | `SessionService` | `users.status` | `CategoryApiIT.disabledAccountIsRefusedAsUnauthenticated` |
| BR-05 | A category's type is the source of truth | `PATCH` | — | translates the refusal | `trg_categories_before_update` | `CategoryApiIT.typeChangeOnAReferencedCategoryIsRefused`, `CategoryApiIT.typeChangeIsAllowedWhenUnreferenced`, `CategoryApiIT.renameOfAReferencedCategoryIsAllowed` |
| BR-06 | Unique name per student and type | `POST`, `PATCH` | — | `requireNameIsFree` | `uk_categories_scope_type_name` | `CategoryApiIT.duplicateNameForTheSameTypeIsRefused`, `CategoryApiIT.sameNameIsAllowedForADifferentType`, `CategoryApiIT.renameHonoursUniqueness` |
| BR-06 | A personal category may not shadow a default | `POST`, `PATCH` | — | `requireNameIsFree` | `trg_categories_before_insert`; the update path is the application's addition | `CategoryApiIT.personalCategoryCannotShadowADefaultOne`, `CategoryApiIT.defaultNameRuleIsEnforcedByTheDatabase`, `CategoryApiIT.renameCannotTakeADefaultName` |
| BR-06 | Default categories are read-only for students | `GET/{id}`, `PATCH`, `DELETE` | — | `requireOwnCategory` | `user_id IS NULL` | `CategoryApiIT.defaultCategoriesAreReadOnlyForStudents` |
| BR-07 | A category in use may not be deleted; retire instead | `DELETE`, `PATCH` | — | translates the refusal | `trg_categories_before_delete`, `fk_txn_category`, `fk_budget_category` | `CategoryApiIT.categoryWithABudgetCannotBeDeleted`, `CategoryApiIT.categoryWithATransactionCannotBeDeleted`, `CategoryApiIT.retireAndRestoreThroughIsActive` |
| §7.5 | No client-supplied identity or role | all five | DTO field set, `SecurityConfig` role rule | entity setter set | `users.role` | `CategoryApiIT.categoryRequestCannotSetOwnership`, `CategoryApiIT.scopeCannotBeChangedByRequest`, `CategoryApiIT.administratorTokenIsRefused`, `CategoryApiIT.categoryEndpointsRejectAnonymousCallers` |
| §7.6 | No schema identifier in the log | all writes | — | `translateWriteFailure` | — | `SecurityHardeningIT.categoryRefusalsLogNoSchemaIdentifiers` |
| §7.7 | No internals in a response | all five | — | `CategoryWriteFailure` | — | `CategoryApiIT.refusalsDoNotWriteDatabaseInternalsToTheLog`, `CategoryApiIT.malformedBodyIsRejectedWithoutLeakingInternals` |
| §13 P5 | Concurrency | `POST` | — | — | `uk_categories_scope_type_name` | `CategoryApiIT.simultaneousIdenticalCreatesAreSerialisedByTheUniqueKey` |
| §13 P5 | Refusal classification | — | — | `CategoryWriteFailure` | — | `CategoryWriteFailureTest` |
| UC-06 | UAT: an empty state is usable | `GET /categories` | — | — | twelve seeded defaults, `user_id IS NULL` | `CategoryApiIT.newStudentSeesTheDefaultsAndNoPersonalCategories` |

`CategoryWriteFailureTest` is a unit test rather than an integration test on purpose: it verifies the
SQLSTATE-and-constraint-name classification with the exact exceptions MySQL and Spring produce. The
unique-key branch is nearly unreachable through the API — the service checks the name first — so
leaving it to an integration test would leave it unverified.

---

## Related documentation

- [API_INVENTORY.md](API_INVENTORY.md) — the authoritative endpoint list
- [authentication.md](authentication.md) — how to obtain the token these endpoints need
- [profile.md](profile.md) — module 2, whose `PATCH` semantics this module follows
- [../SECURITY.md](../SECURITY.md) — the security decisions behind these endpoints
- [../modules/MODULE_03_CATEGORIES.md](../modules/MODULE_03_CATEGORIES.md) — the module report
