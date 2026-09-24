import { Injectable, signal } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { delay, map } from 'rxjs/operators';
import { Category, CategoryType } from '../models/category.model';
import { MOCK_CATEGORIES } from '../../mock-data/categories.mock';

@Injectable({
  providedIn: 'root'
})
export class CategoryService {
  private categories = signal<Category[]>([...MOCK_CATEGORIES]);

  getCategories(): Observable<Category[]> {
    return of(this.categories());
  }

  getCategoriesByType(type: CategoryType): Observable<Category[]> {
    return of(this.categories().filter(c => c.type === type));
  }

  getExpenseCategories(): Observable<Category[]> {
    return this.getCategoriesByType('EXPENSE');
  }

  getIncomeCategories(): Observable<Category[]> {
    return this.getCategoriesByType('INCOME');
  }

  getCategoryById(id: string): Category | undefined {
    return this.categories().find(c => c.id === id);
  }

  addCategory(categoryData: Omit<Category, 'id'>): Observable<Category> {
    const newCategory: Category = {
      ...categoryData,
      id: `cat-usr-${Date.now()}`,
      isDefault: false
    };

    this.categories.update(current => [...current, newCategory]);
    return of(newCategory);
  }

  updateCategory(id: string, updates: Partial<Category>): Observable<Category> {
    const current = this.categories().find(c => c.id === id);
    if (!current) {
      return throwError(() => new Error('Category not found'));
    }

    const updated: Category = { ...current, ...updates };
    this.categories.update(list => list.map(c => c.id === id ? updated : c));
    return of(updated);
  }

  deleteCategory(id: string): Observable<boolean> {
    const target = this.categories().find(c => c.id === id);
    if (!target) {
      return throwError(() => new Error('Category not found'));
    }

    if (target.isDefault) {
      return throwError(() => new Error('Default system categories cannot be deleted'));
    }

    this.categories.update(list => list.filter(c => c.id !== id));
    return of(true);
  }
}
