import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  RecurringRule,
  CreateRecurringRuleRequest,
  UpdateRecurringRuleRequest,
  RecurringFrequency
} from '../models/recurring-rule.model';

/**
 * Module 5 (UC-09). Before this service existed the frontend had no way to reach the module at
 * all — the Quick Add form collected a frequency into a control that was never sent anywhere,
 * and the backend contract for a transaction has no frequency field to send it in.
 */
@Injectable({
  providedIn: 'root'
})
export class RecurringRuleService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/recurring-rules`;

  readonly rules = signal<RecurringRule[]>([]);

  /** One call, no parameters. The list is already ordered by the API. */
  listRules(): Observable<RecurringRule[]> {
    return this.http.get<RecurringRule[]>(this.baseUrl).pipe(
      map(list => (list || []).map(r => this.normalise(r))),
      tap(list => this.rules.set(list))
    );
  }

  /** Only needed if the list may be stale — the row is ordinarily already in memory. */
  getRule(id: number | string): Observable<RecurringRule> {
    return this.http.get<RecurringRule>(`${this.baseUrl}/${id}`).pipe(
      map(r => this.normalise(r))
    );
  }

  createRule(body: CreateRecurringRuleRequest): Observable<RecurringRule> {
    return this.http.post<RecurringRule>(this.baseUrl, body).pipe(
      map(r => this.normalise(r)),
      tap(created => this.rules.update(prev => [created, ...prev]))
    );
  }

  updateRule(id: number | string, body: UpdateRecurringRuleRequest): Observable<RecurringRule> {
    return this.http.patch<RecurringRule>(`${this.baseUrl}/${id}`, body).pipe(
      map(r => this.normalise(r)),
      tap(updated => {
        this.rules.update(prev =>
          prev.map(r => (String(r.id) === String(id) ? updated : r))
        );
      })
    );
  }

  /**
   * Only a rule that has never posted can be deleted; one that has answers 409
   * RECURRING_RULE_IN_USE, because the transactions it generated point back at it. The caller is
   * expected to offer "end" instead when that comes back.
   */
  deleteRule(id: number | string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      tap(() => this.rules.update(prev => prev.filter(r => String(r.id) !== String(id))))
    );
  }

  /** PAUSED, ACTIVE or ENDED through the one field that carries all three. */
  setStatus(id: number | string, status: RecurringRule['status']): Observable<RecurringRule> {
    return this.updateRule(id, { status });
  }

  /**
   * `intervalCount` is published separately from `frequency`, never as a combined label, so the
   * text is built here. QUARTERLY with intervalCount 2 means every two quarters, i.e. every six
   * months — the API does not normalise the pair, so the label must not either.
   */
  static describeSchedule(rule: Pick<RecurringRule, 'frequency' | 'intervalCount'>): string {
    const n = rule.intervalCount && rule.intervalCount > 1 ? rule.intervalCount : 1;
    const unit: Record<RecurringFrequency, [string, string]> = {
      DAILY: ['day', 'days'],
      WEEKLY: ['week', 'weeks'],
      MONTHLY: ['month', 'months'],
      QUARTERLY: ['quarter', 'quarters'],
      YEARLY: ['year', 'years']
    };
    const [one, many] = unit[rule.frequency] ?? ['period', 'periods'];
    return n === 1 ? `Every ${one}` : `Every ${n} ${many}`;
  }

  static isInUseError(err: unknown): boolean {
    return err instanceof HttpErrorResponse && err.error?.errorCode === 'RECURRING_RULE_IN_USE';
  }

  static isEndedError(err: unknown): boolean {
    return err instanceof HttpErrorResponse && err.error?.errorCode === 'RECURRING_RULE_ENDED';
  }

  /**
   * Jackson publishes the enums as their member names and the dates as plain `YYYY-MM-DD`, so
   * there is nothing to coerce — but `amount` arrives as a JSON number and is worth pinning to a
   * number so a string from some future change cannot silently reach an arithmetic path.
   */
  private normalise(raw: any): RecurringRule {
    return {
      ...raw,
      amount: Number(raw.amount),
      intervalCount: Number(raw.intervalCount ?? 1),
      endDate: raw.endDate ?? null,
      lastRunDate: raw.lastRunDate ?? null
    };
  }
}
