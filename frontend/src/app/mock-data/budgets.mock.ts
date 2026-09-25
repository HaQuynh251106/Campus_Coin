import { Budget } from '../core/models/budget.model';

export const MOCK_BUDGETS: Budget[] = [
  {
    id: 'bgt-001',
    categoryId: 'cat-exp-1',
    categoryName: 'Food & Dining',
    categoryIcon: 'utensils',
    categoryColor: '#EA580C',
    monthlyLimit: 220,
    spent: 46.50,
    period: '2026-09',
    alertStatus: 'SAFE'
  },
  {
    id: 'bgt-002',
    categoryId: 'cat-exp-2',
    categoryName: 'Coffee & Snacks',
    categoryIcon: 'coffee',
    categoryColor: '#0D9488',
    monthlyLimit: 35,
    spent: 29.80,
    period: '2026-09',
    alertStatus: 'WARNING'
  },
  {
    id: 'bgt-003',
    categoryId: 'cat-exp-4',
    categoryName: 'Housing & Utilities',
    categoryIcon: 'home',
    categoryColor: '#8B5CF6',
    monthlyLimit: 300,
    spent: 280.00,
    period: '2026-09',
    alertStatus: 'WARNING'
  },
  {
    id: 'bgt-004',
    categoryId: 'cat-exp-6',
    categoryName: 'Entertainment & Social',
    categoryIcon: 'gamepad-2',
    categoryColor: '#F43F5E',
    monthlyLimit: 40,
    spent: 48.00,
    period: '2026-09',
    alertStatus: 'DANGER'
  },
  {
    id: 'bgt-005',
    categoryId: 'cat-exp-3',
    categoryName: 'Books & Supplies',
    categoryIcon: 'book-open',
    categoryColor: '#0EA5E9',
    monthlyLimit: 60,
    spent: 22.00,
    period: '2026-09',
    alertStatus: 'SAFE'
  },
  {
    id: 'bgt-006',
    categoryId: 'cat-exp-5',
    categoryName: 'Transport & Commute',
    categoryIcon: 'bus',
    categoryColor: '#06B6D4',
    monthlyLimit: 30,
    spent: 5.50,
    period: '2026-09',
    alertStatus: 'SAFE'
  }
];
