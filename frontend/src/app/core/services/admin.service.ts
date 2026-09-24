import { Injectable, signal } from '@angular/core';
import { Observable, of } from 'rxjs';
import { delay } from 'rxjs/operators';

export interface AdminKpis {
  totalUsers: number;
  activeUsers: number;
  totalTransactionsLogged: number;
  totalVolumeTracked: number;
  avgStudentMonthlySpend: number;
}

export interface TipTemplate {
  id: string;
  title: string;
  categoryTag: string;
  content: string;
  audience: 'ALL_STUDENTS' | 'FRESHMEN' | 'OFF_CAMPUS';
  isActive: boolean;
  lastUpdated: string;
}

@Injectable({
  providedIn: 'root'
})
export class AdminService {
  private tipTemplates = signal<TipTemplate[]>([
    {
      id: 'tip-01',
      title: 'Free Campus Shuttle Route & Schedule Sync',
      categoryTag: 'Transport',
      content: 'Remind off-campus commuters to utilize the North Campus shuttle rather than commercial ride-hailing.',
      audience: 'OFF_CAMPUS',
      isActive: true,
      lastUpdated: '2026-09-15'
    },
    {
      id: 'tip-02',
      title: 'Dining Hall Off-Peak Discount Window',
      categoryTag: 'Dining',
      content: 'Campus dining halls offer 15% bonus point credits between 2:00 PM and 4:30 PM on weekdays.',
      audience: 'ALL_STUDENTS',
      isActive: true,
      lastUpdated: '2026-09-02'
    },
    {
      id: 'tip-03',
      title: 'Freshman Textbook Reserve Borrowing',
      categoryTag: 'Academics',
      content: 'Remind 1st-year students that mandatory course literature can be checked out for 4-hour blocks from the Main Library.',
      audience: 'FRESHMEN',
      isActive: true,
      lastUpdated: '2026-08-28'
    }
  ]);

  getKpiMetrics(): Observable<AdminKpis> {
    const kpis: AdminKpis = {
      totalUsers: 582,
      activeUsers: 541,
      totalTransactionsLogged: 14280,
      totalVolumeTracked: 184500,
      avgStudentMonthlySpend: 425
    };
    return of(kpis);
  }

  getTipTemplates(): Observable<TipTemplate[]> {
    return of(this.tipTemplates());
  }

  addTipTemplate(tip: Omit<TipTemplate, 'id' | 'lastUpdated'>): Observable<TipTemplate> {
    const created: TipTemplate = {
      ...tip,
      id: `tip-${Date.now()}`,
      lastUpdated: new Date().toISOString().split('T')[0]
    };
    this.tipTemplates.update(curr => [created, ...curr]);
    return of(created);
  }

  updateTipTemplate(id: string, updates: Partial<TipTemplate>): Observable<TipTemplate> {
    const target = this.tipTemplates().find(t => t.id === id);
    if (!target) throw new Error('Tip template not found');
    const updated = { ...target, ...updates, lastUpdated: new Date().toISOString().split('T')[0] };
    this.tipTemplates.update(curr => curr.map(t => t.id === id ? updated : t));
    return of(updated);
  }

  deleteTipTemplate(id: string): Observable<boolean> {
    this.tipTemplates.update(curr => curr.filter(t => t.id !== id));
    return of(true);
  }
}
