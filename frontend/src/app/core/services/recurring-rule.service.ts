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

@Injectable({
  providedIn: 'root'
})
export class RecurringRuleService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/recurring-rules`;

  readonly rules = signal<RecurringRule[]>([]);

  listRules(): Observable<RecurringRule[]> {
    return this.http.get<RecurringRule[]>(this.baseUrl).pipe(
      map(list => (list || []).map(r => this.normalise(r))),
      tap(list => this.rules.set(list))
    );
  }

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

  deleteRule(id: number | string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      tap(() => this.rules.update(prev => prev.filter(r => String(r.id) !== String(id))))
    );
  }

  setStatus(id: number | string, status: RecurringRule['status']): Observable<RecurringRule> {
    return this.updateRule(id, { status });
  }

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
