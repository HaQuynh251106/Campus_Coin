import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AdminUser,
  AdminAnnouncement,
  AdminTipTemplate,
  SystemSetting,
  AdminUsageStats,
  AdminTopCategory
} from '../models/admin.model';
import { Category } from '../models/category.model';

export interface AdminKpis {
  totalUsers: number;
  activeUsers: number;
  totalTransactionsLogged: number;
  totalVolumeTracked: number;
  avgStudentMonthlySpend: number;
}

export interface TipTemplate {
  id: string | number;
  code?: string;
  title: string;
  categoryTag: string;
  content: string;
  isActive: boolean;
  lastUpdated?: string;
}

/**
 * Every value `tip_templates.condition_type` accepts, in the order the database declares them.
 *
 * This is the field that decides *which rule* a template belongs to — the procedure
 * `sp_generate_tips` evaluates the template's condition before it renders the advice — so a
 * template created with the wrong condition never fires. The list is the backend enum
 * `com.campuscoin.admin.entity.TipConditionType` verbatim.
 */
export const TIP_CONDITION_TYPES = [
  'OVER_BUDGET',
  'NEAR_BUDGET',
  'CATEGORY_SPIKE',
  'NO_BUDGET_SET',
  'SAVINGS_GOAL_AT_RISK',
  'LOW_SAVINGS_RATE',
  'GENERIC'
] as const;

export type TipConditionType = (typeof TIP_CONDITION_TYPES)[number];

@Injectable({
  providedIn: 'root'
})
export class AdminService {
  private http = inject(HttpClient);
  private baseUrl = `${environment.apiUrl}/v1/admin`;

  // --- Statistics & KPIs (UC-23) ---

  getStats(): Observable<AdminUsageStats> {
    return this.http.get<AdminUsageStats>(`${this.baseUrl}/stats`);
  }

  getKpiMetrics(): Observable<AdminKpis> {
    return this.getStats().pipe(
      map(stats => {
        const avg = stats.activeStudents > 0
          ? Math.round(stats.totalExpenseLogged / stats.activeStudents)
          : 0;
        return {
          totalUsers: stats.totalStudents,
          activeUsers: stats.activeUsers30d,
          totalTransactionsLogged: stats.totalTransactions,
          totalVolumeTracked: stats.totalExpenseLogged,
          avgStudentMonthlySpend: avg
        };
      })
    );
  }

  getTopCategories(): Observable<AdminTopCategory[]> {
    return this.http.get<AdminTopCategory[]>(`${this.baseUrl}/stats/top-categories`);
  }

  // --- User Management (UC-22) ---

  getUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>(`${this.baseUrl}/users`);
  }

  setUserStatus(id: number | string, status: 'ACTIVE' | 'DISABLED'): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.baseUrl}/users/${id}/status`, { status });
  }

  sendPasswordReset(id: number | string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.baseUrl}/users/${id}/password-reset`, {});
  }

  // --- Default Categories (UC-20) ---

  getDefaultCategories(): Observable<Category[]> {
    return this.http.get<any[]>(`${this.baseUrl}/categories`).pipe(
      map(list => list.map(c => ({
        id: c.id,
        name: c.name,
        type: c.type,
        icon: c.icon || 'tag',
        color: c.color || '#EAB308',
        isDefault: true,
        isActive: c.isActive !== false,
        description: c.description
      })))
    );
  }

  createDefaultCategory(payload: {
    name: string;
    type: 'INCOME' | 'EXPENSE';
    icon?: string;
    color?: string;
    description?: string;
    sortOrder?: number;
    isActive?: boolean;
  }): Observable<Category> {
    return this.http.post<any>(`${this.baseUrl}/categories`, payload).pipe(
      map(c => this.toCategory(c))
    );
  }

  /**
   * A default category's own fields, matching `UpdateDefaultCategoryRequest`
   * (`docs/api/administration.md` §51). `icon`, `color` and `description` accept `''` to clear a
   * nullable column; omitting a key leaves it unchanged.
   *
   * `id`, `isDefault` and `isActive` were removed from this signature: none of the three is a field
   * of the request record, so sending them was a silent no-op rather than an edit. Retirement goes
   * through `setDefaultCategoryStatus` below, which has a route of its own.
   */
  updateDefaultCategory(id: number | string, updates: {
    name?: string;
    type?: 'INCOME' | 'EXPENSE';
    icon?: string;
    color?: string;
    description?: string;
    sortOrder?: number;
  }): Observable<Category> {
    return this.http.patch<any>(`${this.baseUrl}/categories/${id}`, updates).pipe(
      map(c => this.toCategory(c))
    );
  }

  /** Retires or restores a default category (BR-07). `isActive` is the only writer here. */
  setDefaultCategoryStatus(id: number | string, isActive: boolean): Observable<Category> {
    return this.http.patch<any>(`${this.baseUrl}/categories/${id}`, { isActive }).pipe(
      map(c => this.toCategory(c))
    );
  }

  private toCategory(c: any): Category {
    return {
      id: c.id,
      name: c.name,
      type: c.type,
      icon: c.icon || 'tag',
      color: c.color || '#EAB308',
      isDefault: c.isDefault !== false,
      isActive: c.isActive !== false,
      description: c.description
    };
  }

  // --- Tip Templates (UC-21) ---

  getTipTemplates(): Observable<TipTemplate[]> {
    return this.http.get<AdminTipTemplate[]>(`${this.baseUrl}/tip-templates`).pipe(
      map(list => list.map(t => ({
        id: t.id,
        code: t.code,
        title: t.titleTemplate,
        categoryTag: t.conditionType,
        content: t.bodyTemplate,
        isActive: t.isActive,
        lastUpdated: t.updatedAt?.split('T')[0] ?? t.createdAt?.split('T')[0] ?? '—'
      })))
    );
  }

  /**
   * Creates a tip template (`docs/api/administration.md` §53).
   *
   * `code` is required by the contract, is immutable afterwards, and is stored upper case — so the
   * caller supplies it rather than a timestamp being smuggled in, which is what this method used to
   * do. `conditionType` is the field that decides which rule the template fires on; it used to be
   * hard-coded to `GENERIC`, which made the form's other choices unable to affect behaviour.
   */
  addTipTemplate(tip: {
    code: string;
    title: string;
    conditionType?: TipConditionType;
    content: string;
    defaultPriority?: number;
    isActive?: boolean;
  }): Observable<AdminTipTemplate> {
    const payload = {
      code: tip.code.trim().toUpperCase(),
      titleTemplate: tip.title,
      bodyTemplate: tip.content,
      conditionType: tip.conditionType ?? 'GENERIC',
      defaultPriority: tip.defaultPriority ?? 100,
      isActive: tip.isActive ?? true
    };
    return this.http.post<AdminTipTemplate>(`${this.baseUrl}/tip-templates`, payload);
  }

  updateTipTemplate(id: number | string, updates: {
    title?: string;
    content?: string;
    isActive?: boolean;
  }): Observable<any> {
    const payload: any = {};
    if (updates.title) payload.titleTemplate = updates.title;
    if (updates.content) payload.bodyTemplate = updates.content;
    if (updates.isActive !== undefined) payload.isActive = updates.isActive;

    return this.http.patch(`${this.baseUrl}/tip-templates/${id}`, payload);
  }

  // --- Announcements (UC-21) ---

  getAnnouncements(): Observable<AdminAnnouncement[]> {
    return this.http.get<AdminAnnouncement[]>(`${this.baseUrl}/announcements`);
  }

  createAnnouncement(payload: {
    title: string;
    body: string;
    audience?: 'ALL' | 'STUDENTS' | 'ADMINS';
    // `announcements.severity` is a three-value ENUM. `CRITICAL` is not a member and was never
    // accepted by the database, so it is not offered here.
    severity?: 'INFO' | 'WARNING' | 'SUCCESS';
    startsAt?: string;
    endsAt?: string;
  }): Observable<AdminAnnouncement> {
    return this.http.post<AdminAnnouncement>(`${this.baseUrl}/announcements`, payload);
  }

  updateAnnouncement(id: number | string, isActive: boolean): Observable<AdminAnnouncement> {
    return this.http.patch<AdminAnnouncement>(`${this.baseUrl}/announcements/${id}`, { isActive });
  }

  // --- System Settings (UC-23) ---

  getSettings(): Observable<SystemSetting[]> {
    return this.http.get<SystemSetting[]>(`${this.baseUrl}/settings`);
  }

  updateSetting(key: string, value: string): Observable<SystemSetting> {
    return this.http.patch<SystemSetting>(`${this.baseUrl}/settings/${key}`, { value });
  }
}
