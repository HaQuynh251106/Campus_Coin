import { Injectable, signal, inject } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { Budget, BudgetAlertStatus } from '../models/budget.model';
import { MOCK_BUDGETS } from '../../mock-data/budgets.mock';
import { TransactionService } from './transaction.service';
import { CategoryService } from './category.service';

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
  private transactionService = inject(TransactionService);
  private categoryService = inject(CategoryService);

  private budgetsList = signal<Budget[]>([...MOCK_BUDGETS]);

  getBudgets(periodCode = '2026-09'): Observable<Budget[]> {
    // Recompute live spent amount from transactions
    const activeTxs = this.transactionService.activeTransactions();
    const currentMonthTxs = activeTxs.filter(t => t.date.startsWith(periodCode) && t.type === 'EXPENSE');

    const updated = this.budgetsList().map(b => {
      const categorySpent = currentMonthTxs
        .filter(t => t.categoryId === b.categoryId)
        .reduce((sum, t) => sum + t.amount, 0);

      const spent = Math.round(categorySpent * 100) / 100;
      const ratio = b.monthlyLimit > 0 ? (spent / b.monthlyLimit) : 0;

      let alertStatus: BudgetAlertStatus = 'SAFE';
      if (ratio >= 1.0) {
        alertStatus = 'DANGER';
      } else if (ratio >= 0.8) {
        alertStatus = 'WARNING';
      }

      return {
        ...b,
        spent,
        alertStatus
      };
    });

    return of(updated);
  }

  updateBudgetLimit(budgetId: string, newLimit: number): Observable<Budget> {
    const list = this.budgetsList();
    const target = list.find(b => b.id === budgetId);
    if (!target) {
      return throwError(() => new Error('Budget not found'));
    }

    const updated: Budget = {
      ...target,
      monthlyLimit: newLimit
    };

    this.budgetsList.update(curr => curr.map(b => b.id === budgetId ? updated : b));
    return of(updated);
  }

  addBudget(categoryId: string, monthlyLimit: number, periodCode = '2026-09'): Observable<Budget> {
    const cat = this.categoryService.getCategoryById(categoryId);
    const newBudget: Budget = {
      id: `bgt-${Date.now()}`,
      categoryId,
      categoryName: cat?.name || 'Category',
      categoryIcon: cat?.icon || 'tag',
      categoryColor: cat?.color || '#FFE600',
      monthlyLimit,
      spent: 0,
      period: periodCode,
      alertStatus: 'SAFE'
    };

    this.budgetsList.update(curr => [...curr, newBudget]);
    return of(newBudget);
  }

  getBudgetAlerts(periodCode = '2026-09'): Observable<BudgetAlertNotification[]> {
    return new Observable(subscriber => {
      this.getBudgets(periodCode).subscribe(budgets => {
        const alerts: BudgetAlertNotification[] = [];

        for (const b of budgets) {
          const percent = Math.round((b.spent / b.monthlyLimit) * 100);
          if (b.alertStatus === 'DANGER') {
            alerts.push({
              budgetId: b.id,
              categoryName: b.categoryName,
              monthlyLimit: b.monthlyLimit,
              spent: b.spent,
              percent,
              status: 'DANGER',
              message: `Over budget! You have spent $${b.spent} of your $${b.monthlyLimit} limit (${percent}%) on ${b.categoryName}.`
            });
          } else if (b.alertStatus === 'WARNING') {
            alerts.push({
              budgetId: b.id,
              categoryName: b.categoryName,
              monthlyLimit: b.monthlyLimit,
              spent: b.spent,
              percent,
              status: 'WARNING',
              message: `Heads up: You have reached ${percent}% of your monthly ${b.categoryName} budget ($${b.spent}/$${b.monthlyLimit}).`
            });
          }
        }

        subscriber.next(alerts);
        subscriber.complete();
      });
    });
  }
}
