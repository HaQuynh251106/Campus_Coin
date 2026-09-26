import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, RouterModule, IconComponent],
  template: `
    <div class="card-brutal p-6 sm:p-8 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
      <div class="mb-5">
        <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tracking-wider uppercase block mb-1">
          Campus Coin Portal
        </span>
        <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
          Welcome Back
        </h2>
        <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mt-1">
          Sign in with your campus credentials to access your account.
        </p>
      </div>

      <!-- Quick Demo Fill Helpers for Evaluation -->
      <div class="mb-5 p-3 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700 rounded-lg space-y-2">
        <div class="flex items-center justify-between text-xs">
          <div>
            <span class="font-medium text-neutral-900 dark:text-neutral-100">Student Account</span>
            <span class="text-neutral-500 font-mono block text-[11px]">an.nguyen&#64;student.campuscoin.edu</span>
          </div>
          <button
            type="button"
            (click)="fillDemoStudent()"
            class="text-xs font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 px-2 py-0.5 rounded transition-colors cursor-pointer"
          >
            Student Fill
          </button>
        </div>

        <div class="border-t border-neutral-200/60 dark:border-neutral-700/60 pt-2 flex items-center justify-between text-xs">
          <div>
            <span class="font-medium text-neutral-900 dark:text-neutral-100">Admin Account</span>
            <span class="text-neutral-500 font-mono block text-[11px]">admin&#64;campuscoin.edu</span>
          </div>
          <button
            type="button"
            (click)="fillDemoAdmin()"
            class="text-xs font-medium text-neutral-700 dark:text-neutral-300 bg-neutral-200/60 dark:bg-neutral-700/60 hover:bg-neutral-200 dark:hover:bg-neutral-700 px-2 py-0.5 rounded transition-colors cursor-pointer"
          >
            Admin Fill
          </button>
        </div>
      </div>

      @if (errorMessage) {
        <div class="mb-4 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-900/60 text-rose-800 dark:text-rose-200 text-xs font-medium rounded-lg flex items-center gap-2">
          <app-icon name="alert-triangle" [size]="15" strokeWidth="1.5"></app-icon>
          <span>{{ errorMessage }}</span>
        </div>
      }

      <form [formGroup]="loginForm" (ngSubmit)="onSubmit()" class="space-y-4">
        <!-- University Email -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1.5">
            Campus Email Address
          </label>
          <input
            type="email"
            formControlName="email"
            placeholder="username@campuscoin.edu"
            class="input-brutal"
            [class.border-rose-500]="loginForm.get('email')?.invalid && loginForm.get('email')?.touched"
          />
          @if (loginForm.get('email')?.invalid && loginForm.get('email')?.touched) {
            <span class="text-xs text-rose-500 mt-1 block">Please enter a valid campus email.</span>
          }
        </div>

        <!-- Password -->
        <div>
          <div class="flex items-center justify-between mb-1.5">
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500">
              Password
            </label>
            <a routerLink="/auth/forgot-password" class="text-xs font-medium text-amber-600 dark:text-amber-400 hover:underline">
              Forgot Password?
            </a>
          </div>
          <input
            type="password"
            formControlName="password"
            placeholder="••••••••"
            class="input-brutal"
            [class.border-rose-500]="loginForm.get('password')?.invalid && loginForm.get('password')?.touched"
          />
          @if (loginForm.get('password')?.invalid && loginForm.get('password')?.touched) {
            <span class="text-xs text-rose-500 mt-1 block">Password must be at least 6 characters.</span>
          }
        </div>

        <!-- Submit Button -->
        <button
          type="submit"
          [disabled]="loginForm.invalid || isLoading"
          class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50"
        >
          @if (isLoading) {
            <svg class="animate-spin h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
            </svg>
          }
          <span>Sign In to Campus Coin →</span>
        </button>
      </form>

      <div class="mt-6 pt-4 border-t border-neutral-200 dark:border-neutral-800 text-center text-xs text-neutral-500">
        New student on campus?
        <a routerLink="/auth/register" class="text-amber-600 dark:text-amber-400 font-medium hover:underline ml-1">
          Create Student Account
        </a>
      </div>
    </div>
  `
})
export class LoginComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  isLoading = false;
  errorMessage = '';

  loginForm = this.fb.group({
    email: ['an.nguyen@student.campuscoin.edu', [Validators.required, Validators.email]],
    password: ['Student@123', [Validators.required, Validators.minLength(6)]]
  });

  fillDemoStudent(): void {
    this.loginForm.patchValue({
      email: 'an.nguyen@student.campuscoin.edu',
      password: 'Student@123'
    });
  }

  fillDemoAdmin(): void {
    this.loginForm.patchValue({
      email: 'admin@campuscoin.edu',
      password: 'Admin@123'
    });
  }

  onSubmit(): void {
    if (this.loginForm.invalid) return;

    this.isLoading = true;
    this.errorMessage = '';

    const { email, password } = this.loginForm.value;
    this.auth.login(email!, password!).subscribe({
      next: (res) => {
        this.isLoading = false;
        // Role-based routing: Admin -> Admin Dashboard, Student -> Student Home Feed
        if (res.user?.role === 'ADMIN' || this.auth.isAdmin()) {
          this.router.navigate(['/admin/dashboard']);
        } else {
          this.router.navigate(['/app/home']);
        }
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || err.message || 'Login failed. Please check credentials.';
      }
    });
  }
}
