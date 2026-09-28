import { Component, inject, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router, RouterModule, ActivatedRoute } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { ToastService } from '../../../core/services/toast.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

type ResetStep = 'request' | 'sent' | 'reset-token';

function passwordMatchValidator(control: AbstractControl): ValidationErrors | null {
  const password = control.get('newPassword')?.value;
  const confirm = control.get('confirmNewPassword')?.value;
  return password && confirm && password !== confirm ? { passwordMismatch: true } : null;
}

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
            Enter your student campus email to receive a password reset link.
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

          <!-- The throttle response is the one failure this step can report; without it the
               student saw the form reset to empty and no reason why. -->
          @if (errorMessage) {
            <div class="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-semibold flex items-center gap-1.5 animate-fade-in">
              <app-icon name="alert-triangle" size="14"></app-icon>
              <span>{{ errorMessage }}</span>
            </div>
          }

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
            We sent a secure recovery link to:
            <strong class="text-neutral-900 dark:text-white block mt-1 font-mono text-xs">{{ userEmail }}</strong>
          </p>
          <p class="text-xs text-neutral-400 dark:text-neutral-500 mb-6">
            Click the link in the email to set a new password. If you don't see it, check your spam folder.
          </p>

          <a routerLink="/auth/login" class="inline-flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400 font-medium hover:underline">
            ← Return to Sign In
          </a>
        </div>
      }

      <!-- Step 3: Reset With Token Form -->
      @if (step === 'reset-token') {
        <div class="mb-5">
          <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tracking-wider uppercase block mb-1">
            Account Recovery
          </span>
          <h2 class="text-2xl sm:text-3xl font-semibold text-neutral-900 dark:text-neutral-50 tracking-tight">
            Create New Password
          </h2>
          <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mt-1">
            Enter and confirm your new password to secure your account.
          </p>
        </div>

        @if (resetSuccess) {
          <div class="p-3 bg-emerald-50 dark:bg-emerald-950/40 border border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200 rounded-lg text-xs font-medium mb-4 flex items-center gap-1.5 animate-fade-in">
            <app-icon name="check-circle" size="16"></app-icon>
            <span>Password reset successfully! Redirecting you to sign in...</span>
          </div>
        } @else {
          @if (errorMessage) {
            <div class="p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-200 rounded-lg text-xs font-semibold mb-4 flex items-center gap-1.5 animate-fade-in">
              <app-icon name="alert-triangle" size="14"></app-icon>
              <span>{{ errorMessage }}</span>
            </div>
          }
          <!-- The server owns the password rules. The trigger is dirty-or-touched, not touched
               alone: the submit button stays disabled while the form is invalid, so a click can
               never mark the field touched and the student would get a dead button and no reason.
               Mirrors PasswordResetCompleteRequest. -->
          @if ((resetForm.controls.newPassword.dirty || resetForm.controls.newPassword.touched)
               && resetForm.controls.newPassword.invalid) {
            <p class="text-xs text-rose-600 dark:text-rose-400 font-semibold mb-4">
              Password must be 8-72 characters and include an upper-case letter, a lower-case letter and a digit.
            </p>
          }
          @if ((resetForm.controls.confirmNewPassword.dirty || resetForm.controls.confirmNewPassword.touched)
               && resetForm.hasError('passwordMismatch')) {
            <p class="text-xs text-rose-600 dark:text-rose-400 font-semibold mb-4">
              The two passwords do not match.
            </p>
          }
          <form [formGroup]="resetForm" (ngSubmit)="onResetSubmit()" class="space-y-4">
            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1.5">
                New Password
              </label>
              <input
                type="password"
                formControlName="newPassword"
                placeholder="••••••••"
                autocomplete="new-password"
                class="input-brutal"
              />
              <p class="text-[11px] text-neutral-500 dark:text-neutral-400 mt-1">
                Must be at least 8 characters with uppercase, lowercase, and a number.
              </p>
            </div>

            <div>
              <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1.5">
                Confirm New Password
              </label>
              <input
                type="password"
                formControlName="confirmNewPassword"
                placeholder="••••••••"
                autocomplete="new-password"
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
export class ForgotPasswordComponent implements OnDestroy {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private cdr = inject(ChangeDetectorRef);
  private toast = inject(ToastService);

  step: ResetStep = 'request';
  userEmail = '';
  private resetToken = '';
  isLoading = false;
  resetSuccess = false;
  errorMessage = '';

  requestForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]]
  });

  // The rules mirror PasswordResetCompleteRequest, which reuses the registration policy: a
  // password this form accepts must not be one the server refuses. Without the pattern checks the
  // form reported "valid" and the refusal only came back from the API.
  resetForm = this.fb.group({
    newPassword: ['', [
      Validators.required,
      Validators.minLength(8),
      Validators.maxLength(72),
      Validators.pattern(/.*[A-Z].*/),
      Validators.pattern(/.*[a-z].*/),
      Validators.pattern(/.*\d.*/)
    ]],
    confirmNewPassword: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]]
  }, { validators: passwordMatchValidator });

  constructor() {
    this.route.queryParams.subscribe(params => {
      const incomingToken = params['token'];
      if (incomingToken) {
        this.resetToken = incomingToken;

        // Security Hardening: Immediately strip the sensitive token parameter from the browser
        // address bar and history to prevent token leakage via history inspection, shoulder surfing,
        // or HTTP Referer headers.
        if (typeof window !== 'undefined' && window.history) {
          window.history.replaceState({}, document.title, window.location.pathname);
        }

        this.isLoading = true;
        this.auth.verifyResetToken(this.resetToken).subscribe({
          next: () => {
            this.isLoading = false;
            this.step = 'reset-token';
            this.cdr.markForCheck();
          },
          error: (err) => {
            this.isLoading = false;
            this.resetToken = '';
            this.errorMessage = err.error?.message || 'Invalid or expired reset link. Please request a new one.';
            // Zoneless: a token that fails verification kept the screen on "Reset Password" with
            // no explanation, because this state write did not schedule a render.
            this.cdr.markForCheck();
          }
        });
      }
    });
  }

  ngOnDestroy(): void {
    // Zero-out the sensitive token from memory upon component destruction
    this.resetToken = '';
  }

  onRequestSubmit(): void {
    if (this.requestForm.invalid) return;
    this.isLoading = true;
    this.errorMessage = '';
    this.userEmail = this.requestForm.value.email!.trim().toLowerCase();

    this.auth.requestPasswordReset(this.userEmail).subscribe({
      next: () => {
        this.isLoading = false;
        this.step = 'sent';
        // Zoneless: the POST succeeds but nothing re-renders without this, so the screen stayed on
        // the email form and the reset could never be completed. The response is deliberately
        // identical whether or not the address exists, so this branch always advances.
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.isLoading = false;
        // A 429 (TOO_MANY_ATTEMPTS, authentication.md §7.10) is not the anonymous success case:
        // no mail is sent, and the guide says to show the message. Advancing to "check your inbox"
        // there would send the student looking for a link that was never dispatched.
        if (err?.status === 429) {
          this.errorMessage = err.error?.message || 'Too many reset requests. Please wait a few minutes and try again.';
        } else {
          this.step = 'sent';
        }
        this.cdr.markForCheck();
      }
    });
  }

  onResetSubmit(): void {
    if (this.resetForm.invalid || !this.resetToken) return;
    this.isLoading = true;
    this.errorMessage = '';
    const { newPassword, confirmNewPassword } = this.resetForm.value;

    this.auth.completePasswordReset(this.resetToken, newPassword!, confirmNewPassword!).subscribe({
      next: () => {
        this.isLoading = false;
        this.resetSuccess = true;
        this.resetToken = ''; // Instantly wipe token in memory once consumed
        this.toast.success('Password reset successfully! Redirecting...');
        this.cdr.markForCheck();
        setTimeout(() => {
          this.router.navigate(['/auth/login']);
        }, 1500);
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || 'Password reset failed. The link may have expired.';
        this.toast.error(this.errorMessage);
        this.cdr.markForCheck();
      }
    });
  }
}
