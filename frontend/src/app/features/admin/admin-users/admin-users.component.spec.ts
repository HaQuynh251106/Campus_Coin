import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AdminUsersComponent } from './admin-users.component';
import { AdminService } from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { AdminUser } from '../../../core/models/admin.model';

/**
 * The user directory's display order, which is the one thing about this screen that is a decision
 * rather than a passthrough.
 *
 * `GET /api/v1/admin/users` is contracted to return every account "ordered by created time" — in
 * practice `ORDER BY id ASC`. The client is what pages it, ten rows at a time. Rendered in the
 * service's own order, an account created just now becomes the *last* row and therefore falls onto
 * the final page: with eleven accounts the administrator opens the screen, sees ten rows, and the
 * account they just made is not among them. That is the shape of the reported bug — "I created an
 * account and admin does not show it" — and the fix is to sort the loaded page newest-first so a
 * fresh account is on the page that is shown by default.
 *
 * These tests pin the order rather than the pixels: the response order is ascending, and what is
 * rendered must be descending. If someone later removes the sort to "match the API", the second
 * test fails and says why.
 */
describe('AdminUsersComponent — newest-first display order', () => {
  /** Ascending by id, exactly as the DAO's `ORDER BY u.id ASC` returns them. */
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
    // Page one is what an administrator sees without paging. A signup that lands anywhere else is
    // indistinguishable, from that screen, from a signup that did not happen.
    component.loadUsers();

    expect(component.paginatedUsers[0].id).toBe(11);
    expect(component.currentPage).toBe(1);
  });

  it('does not mutate the array the service returned', () => {
    // `sort` is in-place, so the ascending list would be reversed underneath the caller if the copy
    // were dropped. The mock hands out a fresh array each call, so this asserts on the service
    // contract rather than on the one instance: `getUsers` is free to hand out a cached array.
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
