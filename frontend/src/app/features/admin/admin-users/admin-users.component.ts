import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../../core/services/auth.service';
import { User } from '../../../core/models/user.model';
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
            placeholder="Search by student name, ID or email..."
            class="w-full pl-9 pr-3 py-1.5 text-xs bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md focus:outline-none focus:ring-1 focus:ring-slate-900 dark:focus:ring-white"
          />
          <span class="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">
            <app-icon name="search" [size]="14"></app-icon>
          </span>
        </div>

        <div class="flex items-center gap-2">
          <label class="text-xs font-medium text-[var(--color-text-muted)]">Status:</label>
          <select [(ngModel)]="statusFilter" class="text-xs py-1.5 px-2 bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md text-neutral-900 dark:text-neutral-100">
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
                <th class="p-3">Department & Year</th>
                <th class="p-3">Allowance</th>
                <th class="p-3">Goal</th>
                <th class="p-3">Account Status</th>
                <th class="p-3 text-right">Administrative Actions</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100 dark:divide-neutral-800">
              @for (user of filteredUsers; track user.id) {
                <tr class="hover:bg-slate-50/80 dark:hover:bg-neutral-800/40 transition-colors">
                  <!-- Name & ID -->
                  <td class="p-3">
                    <div class="flex items-center gap-2.5">
                      <div class="w-8 h-8 rounded-full overflow-hidden bg-slate-200 shrink-0 border border-slate-300">
                        <img [src]="user.avatar" [alt]="user.name" class="w-full h-full object-cover" />
                      </div>
                      <div>
                        <div class="font-bold text-slate-900 dark:text-white">{{ user.name }}</div>
                        <div class="text-[11px] text-[var(--color-text-muted)] font-mono">{{ user.studentId }} &bull; {{ user.email }}</div>
                      </div>
                    </div>
                  </td>

                  <!-- Major & Year -->
                  <td class="p-3 text-slate-600 dark:text-neutral-300">
                    <div>{{ user.major }}</div>
                    <div class="text-[11px] text-[var(--color-text-muted)]">{{ user.academicYear }}</div>
                  </td>

                  <!-- Allowance -->
                  <td class="p-3 font-semibold text-slate-700 dark:text-neutral-200">
                    \${{ user.monthlyAllowance }}/mo
                  </td>

                  <!-- Savings Goal -->
                  <td class="p-3 font-semibold text-slate-700 dark:text-neutral-200">
                    \${{ user.savingsGoal }}
                  </td>

                  <!-- Status -->
                  <td class="p-3">
                    @if (user.status === 'ACTIVE') {
                      <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-50 dark:bg-emerald-950/60 text-emerald-700 dark:text-emerald-300 border border-emerald-200 dark:border-emerald-800">
                        <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span> Active
                      </span>
                    } @else {
                      <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-rose-50 dark:bg-rose-950/60 text-rose-700 dark:text-rose-300 border border-rose-200 dark:border-rose-800">
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
              <img [src]="selectedUser.avatar" class="w-12 h-12 rounded-full border border-slate-300" />
              <div>
                <h4 class="font-bold text-slate-900 dark:text-white">{{ selectedUser.name }}</h4>
                <p class="text-xs text-slate-500">{{ selectedUser.university }}</p>
              </div>
            </div>

            <div class="grid grid-cols-2 gap-3 text-xs">
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Student ID</span>
                <span class="font-mono font-bold">{{ selectedUser.studentId }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Email</span>
                <span class="font-mono">{{ selectedUser.email }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Allowance Baseline</span>
                <span class="font-bold">\${{ selectedUser.monthlyAllowance }}</span>
              </div>
              <div class="p-2.5 bg-slate-50 dark:bg-neutral-800 rounded">
                <span class="text-slate-400 block text-[10px] uppercase font-bold">Savings Goal</span>
                <span class="font-bold">\${{ selectedUser.savingsGoal }}</span>
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
  private auth = inject(AuthService);

  users: User[] = [];
  searchQuery = '';
  statusFilter: 'ALL' | 'ACTIVE' | 'DISABLED' = 'ALL';
  selectedUser: User | null = null;

  get filteredUsers(): User[] {
    return this.users.filter(u => {
      const matchSearch =
        u.name.toLowerCase().includes(this.searchQuery.toLowerCase()) ||
        u.studentId.toLowerCase().includes(this.searchQuery.toLowerCase()) ||
        u.email.toLowerCase().includes(this.searchQuery.toLowerCase());

      const matchStatus =
        this.statusFilter === 'ALL' || u.status === this.statusFilter;

      return matchSearch && matchStatus;
    });
  }

  ngOnInit(): void {
    this.auth.getAllUsers().subscribe(list => {
      this.users = list;
    });
  }

  viewUserModal(user: User): void {
    this.selectedUser = user;
  }

  toggleStatus(user: User): void {
    const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    const actionLabel = nextStatus === 'DISABLED' ? 'disable' : 'activate';

    if (confirm(`Are you sure you want to ${actionLabel} account for ${user.name}?`)) {
      this.auth.setUserStatus(user.id, nextStatus).subscribe(updated => {
        user.status = updated.status;
      });
    }
  }

  triggerPasswordReset(user: User): void {
    if (confirm(`Send password reset security token to ${user.email}?`)) {
      this.auth.requestPasswordReset(user.email).subscribe(() => {
        alert(`Reset token successfully dispatched to ${user.email}`);
      });
    }
  }
}
