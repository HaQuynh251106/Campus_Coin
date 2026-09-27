import { CategoryType } from './category.model';

/**
 * Module 12 (UC-08) — the category proposal for one record.
 *
 * The proposal is advice stored *beside* the record, never in it (BR-13). `POST /ai/suggest-category`
 * does not write the category: the student accepts or overrides it, and the transactions endpoint
 * performs the write. Nothing in this model is invented — in particular there is no "AI score" to
 * show, only the `confidence` the source itself reports, and `source` says whether that source was
 * the student's own learned rule or a provider.
 */

/** Where the proposal came from. `NONE` is a real answer: the student is choosing freely. */
export type SuggestionSource = 'AI' | 'RULE' | 'NONE';

/**
 * Where a learned mapping came from.
 *
 * `IMPORT` is the third member and the reason this is not a two-value flag: a rule can be taught by a
 * student filing a record (UC-08) or by an imported row (UC-11), and the API distinguishes them. The
 * interface previously omitted this field, so the backend was sending a value the model silently
 * dropped — nothing rendered wrong, because no screen shows it yet, but the model did not describe the
 * contract.
 */
export type RuleSource = 'ACCEPTED' | 'OVERRIDE' | 'IMPORT';

/** The keyword rule this call created or updated, if it taught one. */
export interface LearnedCategoryRule {
  /** The description as stored: trimmed and lower cased. */
  keyword: string;
  categoryId: number;
  categoryName: string;
  /**
   * `OVERRIDE` when the record sits in a different category from the one suggested, so the filing
   * corrected the system; `ACCEPTED` when it did not contradict a suggestion; `IMPORT` when the mapping
   * was learned from an imported row rather than a filing.
   */
  source?: RuleSource;
}

export interface CategorySuggestion {
  /** The record this answer is about — the id the request named. */
  transactionId: number;
  source: SuggestionSource;
  /** Present only when `source` is not `NONE`. */
  categoryId?: number;
  categoryName?: string;
  type?: CategoryType;
  /** How sure the source says it is, between 0 and 1. Advisory; it never decides anything. */
  confidence?: number;
  /** A short explanation, in the provider's own words. Never present for a `RULE` proposal. */
  reason?: string;
  learned?: LearnedCategoryRule;
}

export interface SuggestCategoryRequest {
  transactionId: number;
}
