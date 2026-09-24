import { Injectable, signal, computed } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { Transaction, TransactionType } from '../models/transaction.model';
import { MOCK_TRANSACTIONS } from '../../mock-data/transactions.mock';

export interface MonthlyBalance {
  income: number;
  expense: number;
  net: number;
  savingsRate: number;
}

export interface CategoryBreakdownItem {
  categoryId: string;
  categoryName: string;
  categoryIcon: string;
  categoryColor: string;
  total: number;
  percentage: number;
  transactionCount: number;
}

export interface MonthlyTrendItem {
  periodCode: string; // '2026-09'
  monthLabel: string; // 'Sep 26'
  income: number;
  expense: number;
  net: number;
}

@Injectable({
  providedIn: 'root'
})
export class TransactionService {
  // Master transactions list signal
  private transactions = signal<Transaction[]>([...MOCK_TRANSACTIONS]);

  // Active (non-deleted) transactions
  readonly activeTransactions = computed(() =>
    this.transactions().filter(t => !t.isDeleted)
  );

  getTransactions(filter?: {
    type?: TransactionType;
    categoryId?: string;
    dateFrom?: string;
    dateTo?: string;
  }): Observable<Transaction[]> {
    let list = this.activeTransactions();

    if (filter) {
      if (filter.type) {
        list = list.filter(t => t.type === filter.type);
      }
      if (filter.categoryId && filter.categoryId !== 'ALL') {
        list = list.filter(t => t.categoryId === filter.categoryId);
      }
      if (filter.dateFrom) {
        list = list.filter(t => t.date >= filter.dateFrom!);
      }
      if (filter.dateTo) {
        list = list.filter(t => t.date <= filter.dateTo!);
      }
    }

    // Sort descending by date
    const sorted = [...list].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime());
    return of(sorted);
  }

  getRecentTransactions(limit = 10): Observable<Transaction[]> {
    const sorted = [...this.activeTransactions()]
      .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
      .slice(0, limit);
    return of(sorted);
  }

  addTransaction(data: Omit<Transaction, 'id' | 'createdAt'>): Observable<Transaction> {
    const newTx: Transaction = {
      ...data,
      id: `tx-${Date.now()}`,
      createdAt: new Date().toISOString(),
      isDeleted: false
    };

    this.transactions.update(prev => [newTx, ...prev]);
    return of(newTx);
  }

  updateTransaction(id: string, updates: Partial<Transaction>): Observable<Transaction> {
    const list = this.transactions();
    const idx = list.findIndex(t => t.id === id);
    if (idx === -1) {
      return throwError(() => new Error('Transaction not found'));
    }

    const updated: Transaction = { ...list[idx], ...updates };
    this.transactions.update(prev => prev.map(t => t.id === id ? updated : t));
    return of(updated);
  }

  /**
   * Soft-deletes a transaction from visual presentation.
   * NOTE: The record remains in audit history with `isDeleted: true`
   * rather than a destructive hard SQL delete.
   */
  deleteTransaction(id: string): Observable<boolean> {
    const list = this.transactions();
    const target = list.find(t => t.id === id);
    if (!target) {
      return throwError(() => new Error('Transaction not found'));
    }

    // Mark as deleted in state
    this.transactions.update(prev =>
      prev.map(t => t.id === id ? { ...t, isDeleted: true } : t)
    );
    return of(true);
  }

  /**
   * Computes balance for a given month (default: current month 2026-09)
   */
  getMonthlyBalance(periodCode = '2026-09'): MonthlyBalance {
    const monthTxs = this.activeTransactions().filter(t => t.date.startsWith(periodCode));
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

  /**
   * Computes category expense breakdown for donut/pie charts
   */
  getCategoryBreakdown(periodCode?: string): Observable<CategoryBreakdownItem[]> {
    let txs = this.activeTransactions().filter(t => t.type === 'EXPENSE');
    if (periodCode) {
      txs = txs.filter(t => t.date.startsWith(periodCode));
    }

    const totalExpense = txs.reduce((sum, t) => sum + t.amount, 0);
    const categoryMap = new Map<string, CategoryBreakdownItem>();

    for (const t of txs) {
      if (!categoryMap.has(t.categoryId)) {
        categoryMap.set(t.categoryId, {
          categoryId: t.categoryId,
          categoryName: t.categoryName,
          categoryIcon: t.categoryIcon,
          categoryColor: t.categoryColor,
          total: 0,
          percentage: 0,
          transactionCount: 0
        });
      }
      const item = categoryMap.get(t.categoryId)!;
      item.total += t.amount;
      item.transactionCount += 1;
    }

    const breakdown = Array.from(categoryMap.values()).map(item => ({
      ...item,
      total: Math.round(item.total * 100) / 100,
      percentage: totalExpense > 0 ? Math.round((item.total / totalExpense) * 100) : 0
    })).sort((a, b) => b.total - a.total);

    return of(breakdown);
  }

  /**
   * Computes 6-Month Income vs Expense Trend
   */
  get6MonthTrend(): Observable<MonthlyTrendItem[]> {
    const months = [
      { code: '2026-04', label: 'Apr 26' },
      { code: '2026-05', label: 'May 26' },
      { code: '2026-06', label: 'Jun 26' },
      { code: '2026-07', label: 'Jul 26' },
      { code: '2026-08', label: 'Aug 26' },
      { code: '2026-09', label: 'Sep 26' }
    ];

    const result: MonthlyTrendItem[] = months.map(m => {
      const txs = this.activeTransactions().filter(t => t.date.startsWith(m.code));
      const income = txs.filter(t => t.type === 'INCOME').reduce((acc, t) => acc + t.amount, 0);
      const expense = txs.filter(t => t.type === 'EXPENSE').reduce((acc, t) => acc + t.amount, 0);
      return {
        periodCode: m.code,
        monthLabel: m.label,
        income: Math.round(income),
        expense: Math.round(expense),
        net: Math.round(income - expense)
      };
    });

    return of(result);
  }

  /**
   * Computes daily spending for current month bar chart
   */
  getDailySpending(periodCode = '2026-09'): Observable<Array<{ day: string; amount: number }>> {
    const txs = this.activeTransactions().filter(
      t => t.date.startsWith(periodCode) && t.type === 'EXPENSE'
    );

    const dailyMap = new Map<string, number>();
    for (let day = 1; day <= 24; day++) {
      const dayStr = day < 10 ? `0${day}` : `${day}`;
      dailyMap.set(dayStr, 0);
    }

    for (const t of txs) {
      const dayPart = t.date.split('-')[2];
      if (dailyMap.has(dayPart)) {
        dailyMap.set(dayPart, (dailyMap.get(dayPart) || 0) + t.amount);
      }
    }

    const res = Array.from(dailyMap.entries()).map(([day, amount]) => ({
      day: `Sep ${day}`,
      amount: Math.round(amount * 100) / 100
    }));

    return of(res);
  }

  /**
   * Batch imports parsed transactions from CSV
   */
  importBatch(importedList: Omit<Transaction, 'id' | 'createdAt'>[]): Observable<number> {
    const now = Date.now();
    const formatted: Transaction[] = importedList.map((item, index) => ({
      ...item,
      id: `tx-csv-${now}-${index}`,
      createdAt: new Date().toISOString(),
      isDeleted: false
    }));

    this.transactions.update(curr => [...formatted, ...curr]);
    return of(formatted.length);
  }
}
