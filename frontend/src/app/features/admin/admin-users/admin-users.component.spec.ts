import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AdminUsersComponent } from './admin-users.component';
import { AdminService } from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { AdminUser } from '../../../core/models/admin.model';

describe('AdminUsersComponent — newest-first display order', () => {

  const ascending: AdminUser[] = [
    { id: 1, fullName: 'System Administrator', email: 'admin@campuscoin.edu', role: 'ADMIN', status: 'ACTIVE', academicYear: null, createdAt: '2026-09-01T00:00:00', lastLoginAt: null },
    { id: 2, fullName: 'Alex Nguyen', email: 'an.nguyen@student.campuscoin.edu', role: 'STUDENT', status: 'ACTIVE', academicYear: 'Year 3', createdAt: '2026-09-02T00:00:00', lastLoginAt: null },
    { id: 3, fullName: 'Bella Tran', email: 'binh.tran@student.campuscoin.edu', role: 'STUDENT', status: 'ACTIVE', academicYear: 'Year 1', createdAt: '2026-09-03T00:00:00', lastLoginAt: null },
    { id: 11, fullName: 'Newly Registered', email: 'new@student.campuscoin.edu', role: 'STUDENT', status: 'ACTIVE', academicYear: null, createdAt: '2026-09-27T00:00:00', lastLoginAt: null }
  ];

  let component: AdminUsersComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminUsersComponent],
      providers: [
        { provide: AdminService, useValue: { getUsers: () => of(ascending.map(u => ({ ...u }))) } },
        {
          provide: ToastService,
          useValue: { error: () => {}, success: () => {}, confirm: async () => true }
        }
      ]
    }).compileComponents();

    component = TestBed.createComponent(AdminUsersComponent).componentInstance;
  });

  it('renders an ascending response in descending id order', () => {
    component.loadUsers();

    expect(component.users.map(u => u.id)).toEqual([11, 3, 2, 1]);
  });

  it('puts the most recently created account on the first page', () => {

    component.loadUsers();

    expect(component.paginatedUsers[0].id).toBe(11);
    expect(component.currentPage).toBe(1);
  });

  it('does not mutate the array the service returned', () => {

    const before = ascending.map(u => u.id);
    component.loadUsers();

    expect(before).toEqual([1, 2, 3, 11]);
  });

  it('keeps paging coherent with the new order', () => {
    component.pageSize = 2;
    component.loadUsers();

    expect(component.totalPages).toBe(2);
    expect(component.paginatedUsers.map(u => u.id)).toEqual([11, 3]);

    component.nextPage();
    expect(component.paginatedUsers.map(u => u.id)).toEqual([2, 1]);
  });
});
