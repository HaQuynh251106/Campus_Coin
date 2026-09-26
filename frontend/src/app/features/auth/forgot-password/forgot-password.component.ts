import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { Router, RouterModule, ActivatedRoute } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

type ResetStep = 'request' | 'sent' | 'reset-token';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, RouterModule, IconComponent],
  template: `
    <div class="card-brutal p-6 sm:p-8 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">

      <!-- Step 1: Request Email -->
      @if (step === 'request') {
        <div class="mb-5">
          <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tracking-wider uppercase block mb-1">
            Account Recovery
          </span>
          <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
            Reset Password
          </h2>
          <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mt-1">
            Enter your student campus email to receive a password reset token.
          </p>
        </div>

        <form [formGroup]="requestForm" (ngSubmit)="onRequestSubmit()" class="space-y-4">
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1.5">
              Campus Email
            </label>
            <input
              type="email"
              formControlName="email"
              placeholder="alex.morgan@campus.edu"
              class="input-brutal"
            />
          </div>

          <button
            type="submit"
            [disabled]="requestForm.invalid || isLoading"
            class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50"
          >
            @if (isLoading) {
              <svg class="animate-spin h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
              </svg>
            }
            <span>Send Reset Instructions →</span>
          </button>
        </form>

        <div class="mt-5 text-center text-xs">
          <a routerLink="/auth/login" class="text-neutral-500 hover:text-neutral-900 dark:hover:text-neutral-100 hover:underline">
            ← Return to Sign In
          </a>
        </div>
      }

      <!-- Step 2: "Check Your Email" Confirmation -->
      @if (step === 'sent') {
        <div class="text-center py-2">
          <div class="w-12 h-12 rounded-xl bg-amber-500/10 text-amber-600 dark:text-amber-400 border border-amber-500/25 flex items-center justify-center mx-auto mb-3 shadow-xs">
            <app-icon name="bell" [size]="22" strokeWidth="1.75"></app-icon>
          </div>
          <h2 class="text-xl font-semibold text-neutral-900 dark:text-neutral-50 mb-1.5 tracking-tight">
            Check Your Campus Inbox
          </h2>
          <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mb-5 leading-relaxed">
            We sent a secure recovery link with reset token to:
            <strong class="text-neutral-900 dark:text-white block mt-1 font-mono text-xs">{{ userEmail }}</strong>
          </p>

          <!-- Mock Token Link Trigger for Demo / Judging -->
          <div class="p-3 bg-neutral-50 dark:bg-neutral-800/50 border border-neutral-200 dark:border-neutral-700 rounded-lg mb-5 text-left">
            <span class="text-xs text-neutral-500 block mb-1">Demo Quick Jump:</span>
            <button
              type="button"
              (click)="simulateEmailClick()"
              class="w-full py-2 px-3 text-xs font-medium bg-amber-500 hover:bg-amber-600 text-neutral-950 rounded-md transition-colors cursor-pointer"
            >
              Simulate Clicking Email Link (?token=campus-demo-8842) ⚡
            </button>
          </div>

          <a routerLink="/auth/login" class="text-xs text-neutral-500 hover:underline">
            Back to Sign In
          </a>
        </div>
      }

      <!-- Step 3: Reset With Token Form -->
      @if (step === 'reset-token') {
        <div class="mb-5">
          <span class="text-xs font-semibold text-emerald-600 dark:text-emerald-400 tracking-wider uppercase block mb-1">
            Token Verified
          </span>
          <h2 class="text-2xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Create New Password
          </h2>
          <p class="text-xs font-mono text-emerald-600 dark:text-emerald-400 mt-1">
            Token: {{ token }}
          </p>
        </div>

        @if (resetSuccess) {
          <div class="p-3 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200 rounded-lg text-xs font-medium mb-4">
            ✓ Password reset successfully! Redirecting you to sign in...
          </div>
        } @else {
          @if (errorMessage) {
            <div class="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-semibold mb-4 flex items-center gap-1.5 animate-fade-in">
              <app-icon name="alert-triangle" size="14"></app-icon>
              <span>{{ errorMessage }}</span>
            </div>
          }
          <form [formGroup]="resetForm" (ngSubmit)="onResetSubmit()" class="space-y-4">
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                New Password (min 6 chars)
              </label>
              <input
                type="password"
                formControlName="newPassword"
                placeholder="••••••••"
                class="input-brutal"
              />
            </div>

            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
                Confirm New Password
              </label>
              <input
                type="password"
                formControlName="confirmNewPassword"
                placeholder="••••••••"
                class="input-brutal"
              />
            </div>

            <button
              type="submit"
              [disabled]="resetForm.invalid || isLoading"
              class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50"
            >
              @if (isLoading) {
                <svg class="animate-spin h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
                </svg>
              }
              <span>Update Password & Continue →</span>
            </button>
          </form>
        }
      }

    </div>
  `
})
export class ForgotPasswordComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  step: ResetStep = 'request';
  userEmail = 'an.nguyen@student.campuscoin.edu';
  token = '';
  isLoading = false;
  resetSuccess = false;
  errorMessage = '';

  requestForm = this.fb.group({
    email: ['an.nguyen@student.campuscoin.edu', [Validators.required, Validators.email]]
  });

  resetForm = this.fb.group({
    newPassword: ['', [Validators.required, Validators.minLength(8)]],
    confirmNewPassword: ['', [Validators.required, Validators.minLength(8)]]
  });

  constructor() {
    this.route.queryParams.subscribe(params => {
      if (params['token']) {
        this.token = params['token'];
        this.isLoading = true;
        this.auth.verifyResetToken(this.token).subscribe({
          next: () => {
            this.isLoading = false;
            this.step = 'reset-token';
          },
          error: (err) => {
            this.isLoading = false;
            this.errorMessage = err.error?.message || 'Invalid or expired reset token';
          }
        });
      }
    });
  }

  onRequestSubmit(): void {
    if (this.requestForm.invalid) return;
    this.isLoading = true;
    this.userEmail = this.requestForm.value.email!;

    this.auth.requestPasswordReset(this.userEmail).subscribe({
      next: () => {
        this.isLoading = false;
        this.step = 'sent';
      },
      error: () => {
        this.isLoading = false;
        this.step = 'sent';
      }
    });
  }

  simulateEmailClick(): void {
    this.step = 'reset-token';
    this.token = 'campus-demo-8842';
  }

  private toast = inject(ToastService);

  onResetSubmit(): void {
    if (this.resetForm.invalid) return;
    this.isLoading = true;
    this.errorMessage = '';
    const { newPassword, confirmNewPassword } = this.resetForm.value;

    this.auth.completePasswordReset(this.token, newPassword!, confirmNewPassword!).subscribe({
      next: () => {
        this.isLoading = false;
        this.resetSuccess = true;
        this.toast.success('Password reset successfully! Redirecting...');
        setTimeout(() => {
          this.router.navigate(['/auth/login']);
        }, 1500);
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || 'Password reset failed';
        this.toast.error(this.errorMessage);
      }
    });
  }
}
