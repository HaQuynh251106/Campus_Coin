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
        <span class="inline-flex items-center text-[11px] font-medium px-2.5 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20 mb-2">
          Student Portal
        </span>
        <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
          Welcome Back
        </h2>
        <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mt-1">
          Sign in to track your campus expenses, allowances & budgets.
        </p>
      </div>

      <!-- Quick Demo Fill Helper -->
      <div class="mb-5 p-3 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700 rounded-lg flex items-center justify-between gap-2">
        <div class="text-xs">
          <span class="font-medium text-neutral-900 dark:text-neutral-100 block">Demo Student</span>
          <span class="text-neutral-500">alex.morgan&#64;campus.edu</span>
        </div>
        <button
          type="button"
          (click)="fillDemoStudent()"
          class="text-xs font-medium text-amber-700 dark:text-amber-400 bg-amber-500/10 border border-amber-500/20 hover:bg-amber-500/20 px-2.5 py-1 rounded-md transition-colors cursor-pointer"
        >
          Quick Fill ⚡
        </button>
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
            placeholder="student@campus.edu"
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
            <span class="inline-block animate-spin">⏳</span>
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
    email: ['alex.morgan@campus.edu', [Validators.required, Validators.email]],
    password: ['password123', [Validators.required, Validators.minLength(6)]]
  });

  fillDemoStudent(): void {
    this.loginForm.patchValue({
      email: 'alex.morgan@campus.edu',
      password: 'password123'
    });
  }

  onSubmit(): void {
    if (this.loginForm.invalid) return;

    this.isLoading = true;
    this.errorMessage = '';

    const { email, password } = this.loginForm.value;
    this.auth.login(email!, password!).subscribe({
      next: () => {
        this.isLoading = false;
        this.router.navigate(['/app/home']);
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.message || 'Login failed. Please check credentials.';
      }
    });
  }
}
