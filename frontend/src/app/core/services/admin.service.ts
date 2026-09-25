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
  audience?: string;
  isActive: boolean;
  lastUpdated?: string;
}

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
  }): Observable<any> {
    return this.http.post(`${this.baseUrl}/categories`, payload);
  }

  updateDefaultCategory(id: number | string, updates: {
    name?: string;
    icon?: string;
    color?: string;
    description?: string;
    isActive?: boolean;
  }): Observable<any> {
    return this.http.patch(`${this.baseUrl}/categories/${id}`, updates);
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
        audience: 'ALL_STUDENTS',
        isActive: t.isActive,
        lastUpdated: t.updatedAt ? t.updatedAt.split('T')[0] : t.createdAt.split('T')[0]
      })))
    );
  }

  addTipTemplate(tip: {
    title: string;
    categoryTag?: string;
    content: string;
    audience?: string;
    isActive?: boolean;
  }): Observable<any> {
    const code = `TIP_${Date.now()}`;
    const payload = {
      code,
      titleTemplate: tip.title,
      bodyTemplate: tip.content,
      conditionType: 'GENERIC',
      defaultPriority: 100,
      isActive: tip.isActive ?? true
    };
    return this.http.post(`${this.baseUrl}/tip-templates`, payload);
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
    severity?: 'INFO' | 'WARNING' | 'CRITICAL' | 'SUCCESS';
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
