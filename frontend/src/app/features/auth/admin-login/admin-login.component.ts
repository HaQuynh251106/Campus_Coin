import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-admin-login',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, RouterModule, IconComponent],
  template: `
    <!-- Distinct Classic Enterprise / Academic Portal Aesthetic -->
    <div class="bg-white dark:bg-neutral-900 rounded-xl border border-slate-200 dark:border-neutral-800 shadow-xl p-8 max-w-md mx-auto">

      <div class="flex items-center gap-3 mb-6 pb-4 border-b border-slate-100 dark:border-neutral-800">
        <div class="w-10 h-10 rounded-lg bg-slate-900 dark:bg-white text-white dark:text-neutral-900 flex items-center justify-center font-bold text-lg shadow-sm">
          🛡️
        </div>
        <div>
          <h2 class="text-xl font-bold text-slate-900 dark:text-white tracking-tight">
            Administrative Access
          </h2>
          <p class="text-xs text-slate-500 dark:text-neutral-400">
            Campus Coin Financial Governance System
          </p>
        </div>
      </div>

      <!-- Quick Fill for Evaluation -->
      <div class="mb-5 p-3 rounded-lg bg-slate-50 dark:bg-neutral-800 border border-slate-200 dark:border-neutral-700 flex items-center justify-between">
        <div>
          <span class="text-xs font-semibold text-slate-700 dark:text-neutral-300 block">Default Admin</span>
          <span class="text-[11px] text-slate-500">admin&#64;campuscoin.edu</span>
        </div>
        <button
          type="button"
          (click)="fillAdminCredentials()"
          class="text-xs font-medium px-2.5 py-1 bg-slate-900 hover:bg-slate-800 text-white rounded transition-colors"
        >
          Auto-fill
        </button>
      </div>

      @if (errorMessage) {
        <div class="mb-4 p-3 bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-300 text-xs rounded-lg flex items-center gap-2">
          <app-icon name="alert-triangle" [size]="16"></app-icon>
          <span>{{ errorMessage }}</span>
        </div>
      }

      <form [formGroup]="adminForm" (ngSubmit)="onSubmit()" class="space-y-4">
        <div>
          <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
            Admin Identity Email
          </label>
          <input
            type="email"
            formControlName="email"
            placeholder="admin@campuscoin.edu"
            class="w-full px-3 py-2 bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-slate-900 dark:focus:ring-slate-400 transition-all"
          />
        </div>

        <div>
          <label class="block text-xs font-semibold text-slate-700 dark:text-neutral-300 mb-1">
            Security Password
          </label>
          <input
            type="password"
            formControlName="password"
            placeholder="••••••••••••"
            class="w-full px-3 py-2 bg-slate-50 dark:bg-neutral-800 border border-slate-300 dark:border-neutral-700 rounded-md text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-slate-900 dark:focus:ring-slate-400 transition-all"
          />
        </div>

        <button
          type="submit"
          [disabled]="adminForm.invalid || isLoading"
          class="w-full py-2.5 px-4 bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-100 text-white dark:text-neutral-900 font-semibold text-sm rounded-md shadow-sm transition-all flex items-center justify-center gap-2 disabled:opacity-50 cursor-pointer"
        >
          @if (isLoading) {
            <svg class="animate-spin h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
            </svg>
          }
          <span>Authenticate to Dashboard →</span>
        </button>
      </form>

      <div class="mt-6 pt-4 border-t border-slate-100 dark:border-neutral-800 text-center">
        <a routerLink="/auth/login" class="text-xs text-slate-500 hover:text-slate-800 dark:text-neutral-400 dark:hover:text-white transition-colors">
          ← Return to Student Portal Login
        </a>
      </div>

    </div>
  `
})
export class AdminLoginComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  isLoading = false;
  errorMessage = '';

  adminForm = this.fb.group({
    email: ['admin@campuscoin.edu', [Validators.required, Validators.email]],
    password: ['Admin@123', [Validators.required]]
  });

  fillAdminCredentials(): void {
    this.adminForm.patchValue({
      email: 'admin@campuscoin.edu',
      password: 'Admin@123'
    });
  }

  onSubmit(): void {
    if (this.adminForm.invalid) return;

    this.isLoading = true;
    this.errorMessage = '';

    const { email, password } = this.adminForm.value;
    this.auth.adminLogin(email!, password!).subscribe({
      next: () => {
        this.isLoading = false;
        this.router.navigate(['/admin/dashboard']);
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || err.message || 'Admin authentication failed.';
      }
    });
  }
}
