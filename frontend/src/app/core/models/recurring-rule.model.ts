/**
 * Module 5 (UC-09) — recurring rules.
 *
 * A rule is a schedule, not a transaction. The two are genuinely separate records: the
 * transaction happened once on a date, the rule decides that it happens again. Nothing about a
 * rule is published on a transaction — `source === 'RECURRING'` is the only hint that a
 * transaction came from one, so a badge that must name the frequency needs the rule list.
 *
 * These enums are the member names the database stores, in upper case. The API rejects a number
 * (an ordinal would change meaning if the Java constants were reordered) and rejects lower case.
 */

export type RecurringFrequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'YEARLY';

/**
 * ACTIVE and PAUSED are the two live states and a rule may move between them freely.
 * ENDED is final — the API answers 409 RECURRING_RULE_ENDED to any attempt to revive one.
 */
export type RecurringStatus = 'ACTIVE' | 'PAUSED' | 'ENDED';

/** The shape `GET /recurring-rules` and its siblings return. */
export interface RecurringRule {
  id: number;
  categoryId: number;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  amount: number;
  description: string;
  frequency: RecurringFrequency;
  intervalCount: number;
  /** Plain `YYYY-MM-DD` strings, no time. Compare and sort them as strings — see the doc's §14.5. */
  startDate: string;
  endDate: string | null;
  nextRunDate: string;
  /** Absent for a rule that has never run. That is how a new rule is told from a running one. */
  lastRunDate: string | null;
  status: RecurringStatus;
}

/**
 * Body of `POST /recurring-rules`. There is no `type` (the category decides, BR-05), no `userId`
 * (the token decides), no `status` (every rule is created ACTIVE) and no `lastRunDate` (the
 * scheduler owns it).
 */
export interface CreateRecurringRuleRequest {
  categoryId: number;
  amount: number;
  description?: string;
  frequency: RecurringFrequency;
  intervalCount?: number;
  startDate: string;
  /** Empty string clears it — the one field on this resource that can be removed. */
  endDate?: string;
  nextRunDate?: string;
}

/**
 * Body of `PATCH /recurring-rules/{id}`. Every field is optional and a field left out is not
 * changed, so a caller sends only what it edited. `description` and `endDate` are the two fields
 * with an empty state, so they are the two that map a cleared value to `''`.
 */
export interface UpdateRecurringRuleRequest {
  categoryId?: number;
  amount?: number;
  description?: string;
  frequency?: RecurringFrequency;
  intervalCount?: number;
  endDate?: string;
  nextRunDate?: string;
  status?: RecurringStatus;
}
