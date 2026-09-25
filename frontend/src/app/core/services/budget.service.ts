import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Budget, BudgetAlertStatus } from '../models/budget.model';

export interface BudgetAlertNotification {
  budgetId: string;
  categoryName: string;
  monthlyLimit: number;
  spent: number;
  percent: number;
  status: 'WARNING' | 'DANGER';
  message: string;
}

@Injectable({
  providedIn: 'root'
})
export class BudgetService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/budgets`;

  readonly budgets = signal<Budget[]>([]);

  private mapBackendBudget(raw: any): Budget {
    let alertStatus: BudgetAlertStatus = 'SAFE';
    if (raw.consumptionStatus === 'EXCEEDED' || raw.consumedPct >= 100) {
      alertStatus = 'DANGER';
    } else if (raw.consumptionStatus === 'WARNING' || raw.consumedPct >= 80) {
      alertStatus = 'WARNING';
    }

    return {
      id: raw.id,
      categoryId: raw.categoryId,
      categoryName: raw.categoryName || 'Category',
      categoryIcon: raw.categoryIcon || 'tag',
      categoryColor: raw.categoryColor || '#0EA5E9',
      categoryIsActive: raw.categoryIsActive,
      monthlyLimit: Number(raw.limitAmount ?? raw.monthlyLimit ?? 0),
      limitAmount: Number(raw.limitAmount ?? raw.monthlyLimit ?? 0),
      spent: Number(raw.spentAmount ?? raw.spent ?? 0),
      spentAmount: Number(raw.spentAmount ?? raw.spent ?? 0),
      remainingAmount: Number(raw.remainingAmount ?? 0),
      consumedPct: Number(raw.consumedPct ?? 0),
      period: raw.periodMonth || raw.period || '2026-09',
      periodMonth: raw.periodMonth || raw.period || '2026-09',
      alertStatus,
      consumptionStatus: raw.consumptionStatus || alertStatus,
      createdAt: raw.createdAt
    };
  }

  getBudgets(periodMonth = '2026-09'): Observable<Budget[]> {
    let params = new HttpParams();
    if (periodMonth) {
      params = params.set('month', periodMonth);
    }

    return this.http.get<any[]>(this.baseUrl, { params }).pipe(
      map(rawList => rawList.map(raw => this.mapBackendBudget(raw))),
      tap(list => this.budgets.set(list))
    );
  }

  getBudgetById(id: string | number): Observable<Budget> {
    return this.http.get<any>(`${this.baseUrl}/${id}`).pipe(
      map(raw => this.mapBackendBudget(raw))
    );
  }

  addBudget(categoryId: string | number, limitAmount: number, periodMonth = '2026-09'): Observable<Budget> {
    const payload = {
      categoryId: Number(categoryId),
      periodMonth,
      limitAmount: Number(limitAmount)
    };

    return this.http.post<any>(this.baseUrl, payload).pipe(
      map(raw => this.mapBackendBudget(raw)),
      tap(newBgt => this.budgets.update(curr => [...curr, newBgt]))
    );
  }

  updateBudgetLimit(budgetId: string | number, limitAmount: number): Observable<Budget> {
    const payload = {
      limitAmount: Number(limitAmount)
    };

    return this.http.patch<any>(`${this.baseUrl}/${budgetId}`, payload).pipe(
      map(raw => this.mapBackendBudget(raw)),
      tap(updated => {
        this.budgets.update(curr => curr.map(b => String(b.id) === String(budgetId) ? updated : b));
      })
    );
  }

  deleteBudget(id: string | number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      tap(() => {
        this.budgets.update(curr => curr.filter(b => String(b.id) !== String(id)));
      })
    );
  }

  getBudgetAlerts(periodMonth = '2026-09'): Observable<BudgetAlertNotification[]> {
    return this.getBudgets(periodMonth).pipe(
      map(budgets => {
        const alerts: BudgetAlertNotification[] = [];
        for (const b of budgets) {
          const percent = Math.round(b.consumedPct || (b.monthlyLimit > 0 ? (b.spent / b.monthlyLimit) * 100 : 0));
          if (b.alertStatus === 'DANGER') {
            alerts.push({
              budgetId: String(b.id),
              categoryName: b.categoryName || 'Category',
              monthlyLimit: b.monthlyLimit,
              spent: b.spent,
              percent,
              status: 'DANGER',
              message: `Exceeded ${b.categoryName} budget! Spent $${b.spent} of $${b.monthlyLimit}`
            });
          } else if (b.alertStatus === 'WARNING') {
            alerts.push({
              budgetId: String(b.id),
              categoryName: b.categoryName || 'Category',
              monthlyLimit: b.monthlyLimit,
              spent: b.spent,
              percent,
              status: 'WARNING',
              message: `Approaching limit for ${b.categoryName}: ${percent}% spent ($${b.spent} of $${b.monthlyLimit})`
            });
          }
        }
        return alerts;
      })
    );
  }
}
