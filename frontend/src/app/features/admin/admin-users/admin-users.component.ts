import { Component, OnInit, inject, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdminService } from '../../../core/services/admin.service';
import { ToastService } from '../../../core/services/toast.service';
import { AdminUser } from '../../../core/models/admin.model';
import { IconComponent } from '../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-admin-users',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  template: `
    <div class="space-y-6">

      <!-- Header -->
      <div class="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-slate-200 dark:border-neutral-800">
        <div>
          <h2 class="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
            User Account Management
          </h2>
          <p class="text-xs text-[var(--color-text-muted)]">
            Search, audit profiles, disable access, or trigger password recovery for campus students.
          </p>
        </div>

        <div class="text-xs font-semibold text-[var(--color-text-muted)]">
          Total in system: {{ users.length }} accounts
        </div>
      </div>

      <!-- Search & Filters Strip -->
      <div class="bg-white dark:bg-neutral-900 p-4 rounded-lg border border-slate-200 dark:border-neutral-800 shadow-xs flex flex-wrap items-center justify-between gap-3">
        <div class="relative flex-1 min-w-[240px] max-w-md">
          <input
            type="text"
            [(ngModel)]="searchQuery"
            (ngModelChange)="onSearchChange()"
            placeholder="Search by student name, ID or email..."
            class="w-full pl-9 pr-3 py-1.5 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md focus:outline-none focus:ring-1 focus:ring-slate-900 dark:focus:ring-white"
          />
          <span class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
            <app-icon name="search" [size]="14"></app-icon>
          </span>
        </div>

        <div class="flex items-center gap-2">
          <label class="text-xs font-medium text-[var(--color-text-muted)]">Status:</label>
          <select [(ngModel)]="statusFilter" (ngModelChange)="onStatusChange()" class="text-xs py-1.5 px-2 bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md text-neutral-900 dark:text-neutral-100">
            <option value="ALL">All Statuses</option>
            <option value="ACTIVE">Active Only</option>
            <option value="DISABLED">Disabled Only</option>
          </select>
        </div>
      </div>

      <!-- Users Table -->
      <div class="bg-white dark:bg-neutral-900 rounded-lg border border-slate-200 dark:border-neutral-800 overflow-hidden shadow-xs">
        <div class="overflow-x-auto">
          <table class="w-full text-left text-xs border-collapse">
            <thead>
              <tr class="table-head-row bg-slate-50 dark:bg-neutral-800/80 border-b border-[var(--color-border)] text-[var(--color-text-muted)] uppercase tracking-wider font-semibold text-[10px]">
                <th class="p-3">Student Name & ID</th>
                <th class="p-3">Role & Year</th>
                <th class="p-3">Last Sign-in</th>
                <th class="p-3">Joined</th>
                <th class="p-3">Account Status</th>
                <th class="p-3 text-right">Administrative Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 dark:divide-neutral-800">
              @for (user of paginatedUsers; track user.id) {
                <tr class="hover:bg-slate-50/80 dark:hover:bg-neutral-800/40 transition-colors">
                  <!-- Name & ID -->
                  <td class="p-3">
                    <div class="flex items-center gap-2.5">
                      <div class="w-8 h-8 rounded-full bg-slate-200 dark:bg-neutral-700 shrink-0 border border-slate-300 dark:border-neutral-600 flex items-center justify-center text-[11px] font-bold text-slate-600 dark:text-neutral-200">
                        {{ initials(user.fullName) }}
                      </div>
                      <div>
                        <div class="font-bold text-slate-900 dark:text-white">{{ user.fullName }}</div>
                        <div class="text-[11px] text-[var(--color-text-muted)] font-mono">#{{ user.id }} &bull; {{ user.email }}</div>
                      </div>
                    </div>
                  </td>

                  <!-- Role & Year -->
                  <td class="p-3 text-slate-600 dark:text-neutral-300">
                    <div>{{ user.role === 'ADMIN' ? 'Administrator' : 'Student' }}</div>
                    <div class="text-[11px] text-[var(--color-text-muted)]">
                      {{ user.academicYear || 'Year not set' }}
                    </div>
                  </td>

                  <!-- Last Sign-in -->
                  <td class="p-3 text-slate-600 dark:text-neutral-300">
                    {{ formatTimestamp(user.lastLoginAt) }}
                  </td>

                  <!-- Joined -->
                  <td class="p-3 text-slate-600 dark:text-neutral-300">
                    {{ formatTimestamp(user.createdAt) }}
                  </td>

                  <!-- Status (Restrained text label with status dot) -->
                  <td class="p-3">
                    @if (user.status === 'ACTIVE') {
                      <span class="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                        <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span> Active
                      </span>
                    } @else {
                      <span class="inline-flex items-center gap-1.5 text-xs font-medium text-rose-600 dark:text-rose-400">
                        <span class="w-1.5 h-1.5 rounded-full bg-rose-500"></span> Disabled
                      </span>
                    }
                  </td>

                  <!-- Actions -->
                  <td class="p-3 text-right">
                    <div class="flex items-center justify-end gap-1.5">
                      <button
                        type="button"
                        (click)="viewUserModal(user)"
                        class="px-2.5 py-1 text-slate-600 hover:text-slate-900 dark:text-neutral-400 dark:hover:text-neutral-100 rounded-md border border-slate-200 dark:border-neutral-700 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
                        title="View Full Profile"
                      >
                        Details
                      </button>

                      <button
                        type="button"
                        (click)="toggleStatus(user)"
                        class="px-2.5 py-1 rounded-md border text-[11px] font-medium transition-colors cursor-pointer"
                        [class.border-rose-200]="user.status === 'ACTIVE'"
                        [class.dark:border-rose-900]="user.status === 'ACTIVE'"
                        [class.text-rose-600]="user.status === 'ACTIVE'"
                        [class.dark:text-rose-400]="user.status === 'ACTIVE'"
                        [class.hover:bg-rose-50]="user.status === 'ACTIVE'"
                        [class.dark:hover:bg-rose-950/40]="user.status === 'ACTIVE'"
                        [class.border-emerald-200]="user.status === 'DISABLED'"
                        [class.dark:border-emerald-900]="user.status === 'DISABLED'"
                        [class.text-emerald-600]="user.status === 'DISABLED'"
                        [class.dark:text-emerald-400]="user.status === 'DISABLED'"
                        [class.hover:bg-emerald-50]="user.status === 'DISABLED'"
                        [class.dark:hover:bg-emerald-950/40]="user.status === 'DISABLED'"
                      >
                        {{ user.status === 'ACTIVE' ? 'Disable' : 'Enable' }}
                      </button>

                      <button
                        type="button"
                        (click)="triggerPasswordReset(user)"
                        class="px-2.5 py-1 text-slate-600 hover:text-slate-900 dark:text-neutral-400 dark:hover:text-neutral-100 rounded-md border border-slate-200 dark:border-neutral-700 hover:bg-slate-100 dark:hover:bg-neutral-800 transition-colors cursor-pointer"
                        title="Reset Student Password"
                      >
                        Reset Password
                      </button>
                    </div>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>

        <!-- Pagination & Footer Stats Toolbar -->
        <div class="p-3.5 bg-slate-50 dark:bg-neutral-800/80 border-t border-slate-200 dark:border-neutral-800 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-600 dark:text-neutral-300">
          <div>
            Showing <span class="font-bold text-slate-900 dark:text-white">{{ startIndex }}</span> to <span class="font-bold text-slate-900 dark:text-white">{{ endIndex }}</span> of <span class="font-bold text-slate-900 dark:text-white">{{ filteredUsers.length }}</span> accounts
          </div>

          <div class="flex items-center gap-4">
            <div class="flex items-center gap-1.5">
              <label class="text-[11px] text-slate-400">Rows per page:</label>
              <select
                [(ngModel)]="pageSize"
                (ngModelChange)="onPageSizeChange()"
                class="bg-white dark:bg-neutral-900 border border-slate-300 dark:border-neutral-700 rounded px-2 py-0.5 text-xs text-slate-700 dark:text-neutral-200"
              >
                @for (opt of pageSizeOptions; track opt) {
                  <option [value]="opt">{{ opt }}</option>
                }
              </select>
            </div>

            <div class="flex items-center gap-1">
              <button
                type="button"
                (click)="prevPage()"
                [disabled]="currentPage <= 1"
                class="px-2.5 py-1 rounded border border-slate-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 hover:bg-slate-100 dark:hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors cursor-pointer"
              >
                Previous
              </button>
              <span class="px-2 font-mono text-[11px]">
                {{ currentPage }} / {{ totalPages }}
              </span>
              <button
                type="button"
                (click)="nextPage()"
                [disabled]="currentPage >= totalPages"
                class="px-2.5 py-1 rounded border border-slate-200 dark:border-neutral-700 bg-white dark:bg-neutral-900 hover:bg-slate-100 dark:hover:bg-neutral-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors cursor-pointer"
              >
                Next
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- User Details Modal -->
      @if (selectedUser) {
        <div class="fixed inset-0 z-50 bg-black/40 backdrop-blur-xs flex items-center justify-center p-4">
          <div class="bg-white dark:bg-neutral-900 rounded-xl border border-slate-200 dark:border-neutral-800 max-w-md w-full p-6 shadow-xl space-y-4">
            <div class="flex items-center justify-between pb-3 border-b border-slate-100 dark:border-neutral-800">
              <h3 class="font-bold text-base text-slate-900 dark:text-white">
                Student Account Details
              </h3>
              <button (click)="selectedUser = null" class="text-slate-400 hover:text-slate-700">✕</button>
            </div>

            <div class="flex items-center gap-3">
              <div class="w-12 h-12 rounded-full border border-slate-300 dark:border-neutral-700 bg-slate-100 dark:bg-neutral-800 flex items-center justify-center font-bold text-slate-600 dark:text-neutral-300">
                {{ initials(selectedUser.fullName) }}
              </div>
              <div>
                <h4 class="font-bold text-slate-900 dark:text-white">{{ selectedUser.fullName }}</h4>
                <p class="text-xs text-slate-500">
                  {{ selectedUser.role === 'ADMIN' ? 'Administrator account' : 'Student account' }}
                </p>
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3 text-xs">
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Account ID</span>
                <span class="font-mono font-bold">{{ selectedUser.id }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Email</span>
                <span class="font-mono">{{ selectedUser.email }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Academic Year</span>
                <span class="font-bold">{{ selectedUser.academicYear || 'Not set' }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Last Sign-in</span>
                <span class="font-bold">{{ formatTimestamp(selectedUser.lastLoginAt) }}</span>
              </div>
            </div>

            <div class="flex justify-end pt-3">
              <button
                type="button"
                (click)="selectedUser = null"
                class="px-4 py-2 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-semibold rounded-lg text-xs shadow-xs transition-colors cursor-pointer"
              >
                Close Profile
              </button>
            </div>
          </div>
        </div>
      }

    </div>
  `
})
export class AdminUsersComponent implements OnInit {
  private adminService = inject(AdminService);
  private toast = inject(ToastService);
  private cdr = inject(ChangeDetectorRef);

  users: AdminUser[] = [];
  searchQuery = '';
  statusFilter: 'ALL' | 'ACTIVE' | 'DISABLED' = 'ALL';
  selectedUser: AdminUser | null = null;
  isLoading = false;

  currentPage = 1;
  pageSize = 10;
  readonly pageSizeOptions = [10, 25, 50];

  get filteredUsers(): AdminUser[] {
    return this.users.filter(u => {
      const q = this.searchQuery.toLowerCase();
      const matchSearch =
        u.fullName.toLowerCase().includes(q) ||
        u.email.toLowerCase().includes(q) ||
        String(u.id) === q.replace(/^#/, '');

      const matchStatus =
        this.statusFilter === 'ALL' || u.status === this.statusFilter;

      return matchSearch && matchStatus;
    });
  }

  get totalPages(): number {
    return Math.ceil(this.filteredUsers.length / this.pageSize) || 1;
  }

  get paginatedUsers(): AdminUser[] {
    const startIndex = (this.currentPage - 1) * this.pageSize;
    return this.filteredUsers.slice(startIndex, startIndex + this.pageSize);
  }

  get startIndex(): number {
    return this.filteredUsers.length === 0 ? 0 : (this.currentPage - 1) * this.pageSize + 1;
  }

  get endIndex(): number {
    return Math.min(this.currentPage * this.pageSize, this.filteredUsers.length);
  }

  onSearchChange(): void {
    this.currentPage = 1;
  }

  onStatusChange(): void {
    this.currentPage = 1;
  }

  onPageSizeChange(): void {
    this.currentPage = 1;
  }

  prevPage(): void {
    if (this.currentPage > 1) {
      this.currentPage--;
    }
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages) {
      this.currentPage++;
    }
  }

  ngOnInit(): void {
    this.loadUsers();
  }

  /**
   * The list is rendered exactly as `AdminUserResponse` publishes it — id, name, address, role,
   * status, year, last sign-in, created. Nothing is embellished onto it.
   *
   * Two columns used to sit here showing a monthly allowance and a savings goal. There is no such
   * field in the response and no administrative action that needs one: the DTO omits a student's
   * financial position deliberately (VĐ-04), so those columns rendered a hardcoded `$0` for every
   * account. A figure nobody supplied is worse than no column.
   *
   * Sorted newest-first for display only. The endpoint's own contract is "ordered by created time"
   * — a bare `ORDER BY id ASC` — and it is the client that pages the result, ten rows at a time.
   * Rendered in the service's order, a newly registered account lands on the *last* page rather than
   * the first: with eleven accounts, the one just created is row 11 and the administrator opening
   * "User Directory" sees ten rows that do not include it, which reads as "the new account is
   * missing" when it is present and merely beyond the fold. Newest-first is what makes a freshly
   * created account visible on the page that is shown by default, which is the whole reason an
   * administrator opens this screen after a signup. Reversing here rather than in the query keeps
   * the documented ordering of the API intact — the response itself is unchanged, so nothing that
   * depends on it (the API tests assert ascending ids) is affected.
   */
  loadUsers(): void {
    this.isLoading = true;
    this.adminService.getUsers().subscribe({
      next: (list) => {
        this.isLoading = false;
        this.users = [...list].sort((a, b) => b.id - a.id);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isLoading = false;
        this.toast.error(err.error?.message || 'Failed to load user directory');
        this.cdr.markForCheck();
      }
    });
  }

  /** First letters of the two name parts, for the avatar placeholder. */
  initials(fullName: string): string {
    const parts = (fullName || '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    const first = parts[0][0];
    const last = parts.length > 1 ? parts[parts.length - 1][0] : '';
    return (first + last).toUpperCase();
  }

  /**
   * Renders one of the response's timestamps. `lastLoginAt` is absent when the account has never
   * signed in — the contract distinguishes that from a null date, so "Never" is shown rather than a
   * substitute date.
   */
  formatTimestamp(value: string | null | undefined): string {
    if (!value) return 'Never';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '—';
    return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
  }

  viewUserModal(user: AdminUser): void {
    this.selectedUser = user;
  }

  async toggleStatus(user: AdminUser): Promise<void> {
    const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    const actionLabel = nextStatus === 'DISABLED' ? 'disable' : 'activate';

    const confirmed = await this.toast.confirm(
      `${nextStatus === 'DISABLED' ? 'Disable' : 'Activate'} User Account`,
      `Are you sure you want to ${actionLabel} the account for ${user.fullName} (${user.email})?`,
      `${nextStatus === 'DISABLED' ? 'Disable Account' : 'Activate Account'}`,
      'Cancel',
      nextStatus === 'DISABLED'
    );

    if (confirmed) {
      this.adminService.setUserStatus(user.id, nextStatus).subscribe({
        next: (updated) => {
          user.status = updated.status;
          this.toast.success(`Account for ${user.fullName} has been ${actionLabel}d.`);
        },
        error: (err) => {
          this.toast.error(err.error?.message || 'Failed to update user status');
        }
      });
    }
  }

  async triggerPasswordReset(user: AdminUser): Promise<void> {
    const confirmed = await this.toast.confirm(
      'Reset Password',
      `Send password reset security link for ${user.email}?`,
      'Send Reset Link',
      'Cancel',
      false
    );

    if (confirmed) {
      this.adminService.sendPasswordReset(user.id).subscribe({
        next: (res) => {
          this.toast.success(res.message || `Password reset link dispatched for ${user.email}`);
        },
        error: (err) => {
          this.toast.error(err.error?.message || 'Failed to send password reset');
        }
      });
    }
  }
}
