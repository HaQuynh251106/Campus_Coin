import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap, map, of, catchError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Transaction, TransactionType } from '../models/transaction.model';
import {
  ReportResponse,
  SpendingSeriesResponse,
  MonthlyTrendItem,
  CategoryBreakdownItem,
  ReportCategoryItem
} from '../models/report.model';

export interface MonthlyBalance {
  income: number;
  expense: number;
  net: number;
  savingsRate: number;
}

export type { MonthlyTrendItem, CategoryBreakdownItem };

@Injectable({
  providedIn: 'root'
})
export class TransactionService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1`;

  // Master transactions list signal
  private transactions = signal<Transaction[]>([]);

  // Active (non-deleted) transactions
  readonly activeTransactions = computed(() =>
    this.transactions().filter(t => !t.isDeleted)
  );

  private mapBackendTx(raw: any): Transaction {
    const txnDate = raw.txnDate || raw.date || new Date().toISOString().split('T')[0];
    const type: TransactionType = raw.categoryType || raw.type || 'EXPENSE';
    return {
      id: raw.id,
      userId: raw.userId,
      type,
      categoryType: type,
      amount: Number(raw.amount),
      categoryId: raw.categoryId,
      categoryName: raw.categoryName || 'General',
      categoryIcon: raw.categoryIcon || 'tag',
      categoryColor: raw.categoryColor || '#EAB308',
      date: txnDate,
      txnDate: txnDate,
      description: raw.description || '',
      source: raw.source || 'MANUAL',
      recurringRuleId: raw.recurringRuleId,
      isDeleted: raw.isDeleted || false,
      deletedAt: raw.deletedAt,
      createdAt: raw.createdAt,
      recurringFrequency: raw.recurringFrequency || 'NONE'
    };
  }

  getTransactions(filter?: {
    type?: TransactionType;
    categoryId?: string | number;
    from?: string;
    to?: string;
    dateFrom?: string;
    dateTo?: string;
    includeDeleted?: boolean;
  }): Observable<Transaction[]> {
    let params = new HttpParams();
    const from = filter?.from || filter?.dateFrom;
    const to = filter?.to || filter?.dateTo;
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    if (filter?.includeDeleted) params = params.set('includeDeleted', 'true');

    return this.http.get<any[]>(`${this.baseUrl}/transactions`, { params }).pipe(
      map(rawList => rawList.map(raw => this.mapBackendTx(raw))),
      tap(txs => this.transactions.set(txs))
    );
  }

  getRecentTransactions(limit = 10, filter?: { from?: string; to?: string; includeDeleted?: boolean }): Observable<Transaction[]> {
    return this.getTransactions(filter).pipe(
      map(txs => txs.slice(0, limit))
    );
  }

  getTransactionById(id: string | number): Observable<Transaction> {
    return this.http.get<any>(`${this.baseUrl}/transactions/${id}`).pipe(
      map(raw => this.mapBackendTx(raw))
    );
  }

  addTransaction(data: {
    categoryId: string | number;
    amount: number;
    date?: string;
    txnDate?: string;
    description: string;
  }): Observable<Transaction> {
    const payload = {
      categoryId: Number(data.categoryId),
      amount: Number(data.amount),
      txnDate: data.txnDate || data.date || new Date().toISOString().split('T')[0],
      description: data.description
    };

    return this.http.post<any>(`${this.baseUrl}/transactions`, payload).pipe(
      map(raw => this.mapBackendTx(raw)),
      tap(newTx => this.transactions.update(prev => [newTx, ...prev]))
    );
  }

  updateTransaction(id: string | number, updates: {
    categoryId?: string | number;
    amount?: number;
    date?: string;
    txnDate?: string;
    description?: string;
  }): Observable<Transaction> {
    const payload: any = {};
    if (updates.categoryId !== undefined) payload.categoryId = Number(updates.categoryId);
    if (updates.amount !== undefined) payload.amount = Number(updates.amount);
    if (updates.txnDate || updates.date) payload.txnDate = updates.txnDate || updates.date;
    if (updates.description !== undefined) payload.description = updates.description;

    return this.http.patch<any>(`${this.baseUrl}/transactions/${id}`, payload).pipe(
      map(raw => this.mapBackendTx(raw)),
      tap(updated => {
        this.transactions.update(prev => prev.map(t => String(t.id) === String(id) ? updated : t));
      })
    );
  }

  deleteTransaction(id: string | number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/transactions/${id}`).pipe(
      tap(() => {
        this.transactions.update(prev => prev.filter(t => String(t.id) !== String(id)));
      })
    );
  }

  restoreTransaction(id: string | number): Observable<Transaction> {
    return this.http.post<any>(`${this.baseUrl}/transactions/${id}/restore`, {}).pipe(
      map(raw => this.mapBackendTx(raw)),
      tap(restored => {
        this.transactions.update(prev => [restored, ...prev]);
      })
    );
  }

  getMonthlyBalance(periodCode = '2026-09'): MonthlyBalance {
    const monthTxs = this.activeTransactions().filter(t => (t.date || t.txnDate || '').startsWith(periodCode));
    let income = 0;
    let expense = 0;

    for (const t of monthTxs) {
      if (t.type === 'INCOME') income += t.amount;
      if (t.type === 'EXPENSE') expense += t.amount;
    }

    const roundedIncome = Math.round(income * 100) / 100;
    const roundedExpense = Math.round(expense * 100) / 100;
    const net = Math.round((roundedIncome - roundedExpense) * 100) / 100;
    const savingsRate = roundedIncome > 0 ? Math.round((net / roundedIncome) * 100) : 0;

    return { income: roundedIncome, expense: roundedExpense, net, savingsRate };
  }

  getReport(periodMonth?: string): Observable<ReportResponse> {
    let params = new HttpParams();
    if (periodMonth && periodMonth !== 'ALL_6M') {
      params = params.set('month', periodMonth);
    }
    return this.http.get<ReportResponse>(`${this.baseUrl}/reports`, { params });
  }

  getCategoryBreakdown(periodCode?: string, flowType: 'ALL' | 'EXPENSE' | 'INCOME' = 'EXPENSE'): Observable<CategoryBreakdownItem[]> {
    return this.getReport(periodCode).pipe(
      map(rep => {
        let items: ReportCategoryItem[] = [];
        if (flowType === 'INCOME') {
          items = rep.incomeByCategory || [];
        } else if (flowType === 'EXPENSE') {
          items = rep.expenseByCategory || [];
        } else {
          items = [...(rep.expenseByCategory || []), ...(rep.incomeByCategory || [])];
        }

        return items.map(cb => ({
          categoryId: String(cb.categoryId),
          categoryName: cb.categoryName,
          categoryIcon: cb.categoryIcon || 'tag',
          categoryColor: cb.categoryColor || '#0EA5E9',
          total: Number(cb.total || 0),
          percentage: Number(cb.percentage || 0),
          transactionCount: Number(cb.transactionCount || 0)
        }));
      }),
      catchError(() => of([]))
    );
  }

  get6MonthTrend(): Observable<MonthlyTrendItem[]> {
    return this.getReport().pipe(
      map(rep => {
        return (rep.sixMonthTrend || []).map(st => {
          const parts = st.periodMonth.split('-');
          const monthNum = parseInt(parts[1], 10);
          const monthNames = ['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
          const label = `${monthNames[monthNum] || parts[1]} '${parts[0].slice(2)}`;
          return {
            periodCode: st.periodMonth,
            monthLabel: label,
            income: Math.round(Number(st.income || 0)),
            expense: Math.round(Number(st.expense || 0)),
            net: Math.round(Number(st.net || 0))
          };
        });
      }),
      catchError(() => of([]))
    );
  }

  getDailySpending(periodCode = '2026-09'): Observable<Array<{ day: string; amount: number }>> {
    let params = new HttpParams().set('granularity', 'DAILY');
    if (periodCode && periodCode !== 'ALL_6M') {
      params = params.set('month', periodCode);
    }
    return this.http.get<SpendingSeriesResponse>(`${this.baseUrl}/reports/spending`, { params }).pipe(
      map(res => {
        return (res.points || []).map(p => {
          const dayParts = p.intervalStart ? p.intervalStart.split('-') : [];
          const dayNumber = dayParts[2] ? parseInt(dayParts[2], 10) : p.intervalStart;
          return {
            day: String(dayNumber),
            amount: Number(p.totalExpense || 0)
          };
        });
      }),
      catchError(() => of([]))
    );
  }
}
