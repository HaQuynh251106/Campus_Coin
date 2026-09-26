import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, map, shareReplay } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Category, CategoryType } from '../models/category.model';

@Injectable({
  providedIn: 'root'
})
export class CategoryService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/categories`;

  readonly categories = signal<Category[]>([]);
  private categoriesCache$?: Observable<Category[]>;

  private mapCategory(raw: any): Category {
    return {
      id: String(raw.id),
      name: raw.name || 'Category',
      type: raw.type,
      icon: raw.icon || 'tag',
      color: raw.color || '#EAB308',
      isDefault: raw.isDefault || false,
      userId: raw.userId ? String(raw.userId) : null,
      description: raw.description,
      isActive: raw.isActive !== false
    };
  }

  getCategories(forceRefresh = false): Observable<Category[]> {
    if (!this.categoriesCache$ || forceRefresh) {
      this.categoriesCache$ = this.http.get<any[]>(this.baseUrl).pipe(
        map(list => list.map(raw => this.mapCategory(raw))),
        tap({
          next: (cats) => this.categories.set(cats),
          error: () => this.invalidateCache()
        }),
        // refCount: true ensures the cache is torn down when no subscribers remain,
        // preventing a stale empty/error state from being replayed to the next tab.
        shareReplay({ bufferSize: 1, refCount: true })
      );
    }
    return this.categoriesCache$;
  }

  invalidateCache(): void {
    this.categoriesCache$ = undefined;
  }

  getCategoryById(id: string | number): Category | undefined {
    return this.categories().find(c => String(c.id) === String(id));
  }

  fetchCategoryById(id: string | number): Observable<Category> {
    return this.http.get<any>(`${this.baseUrl}/${id}`).pipe(
      map(raw => this.mapCategory(raw))
    );
  }

  getCategoriesByType(type: CategoryType): Observable<Category[]> {
    return this.getCategories().pipe(
      map(cats => cats.filter(c => c.type === type && c.isActive !== false))
    );
  }

  getExpenseCategories(): Observable<Category[]> {
    return this.getCategoriesByType('EXPENSE');
  }

  getIncomeCategories(): Observable<Category[]> {
    return this.getCategoriesByType('INCOME');
  }

  addCategory(payload: {
    name: string;
    type: CategoryType;
    icon?: string;
    color?: string;
    description?: string;
  }): Observable<Category> {
    return this.http.post<any>(this.baseUrl, payload).pipe(
      map(raw => this.mapCategory(raw)),
      tap(newCat => {
        this.invalidateCache();
        this.categories.update(curr => [...curr, newCat]);
      })
    );
  }

  updateCategory(id: string | number, updates: {
    name?: string;
    icon?: string;
    color?: string;
    description?: string;
  }): Observable<Category> {
    return this.http.patch<any>(`${this.baseUrl}/${id}`, updates).pipe(
      map(raw => this.mapCategory(raw)),
      tap(updated => {
        this.invalidateCache();
        this.categories.update(curr => curr.map(c => String(c.id) === String(id) ? updated : c));
      })
    );
  }

  deleteCategory(id: string | number): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`).pipe(
      tap(() => {
        this.invalidateCache();
        this.categories.update(curr => curr.filter(c => String(c.id) !== String(id)));
      })
    );
  }
}
