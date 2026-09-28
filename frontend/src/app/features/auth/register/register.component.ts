import { Component, inject, ChangeDetectorRef, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, Validators, AbstractControl, ValidationErrors } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../../../core/services/auth.service';
import { IconComponent } from '../../../shared/components/icon/icon.component';

function passwordMatchValidator(control: AbstractControl): ValidationErrors | null {
  const password = control.get('password')?.value;
  const confirm = control.get('confirmPassword')?.value;
  return password && confirm && password !== confirm ? { passwordMismatch: true } : null;
}

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, RouterModule, IconComponent],
  template: `
    <div class="card-brutal p-6 sm:p-8 bg-white dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 rounded-xl shadow-xs">
      <div class="mb-5">
        <span class="text-xs font-semibold text-amber-600 dark:text-amber-400 tracking-wider uppercase block mb-1">
          Join Campus Coin
        </span>
        <h2 class="text-2xl sm:text-3xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
          Create Account
        </h2>
        <p class="text-xs sm:text-sm text-neutral-500 dark:text-neutral-400 mt-1">
          Master your student finances with AI insights and smart budgeting.
        </p>
      </div>

      @if (errorMessage) {
        <div class="mb-4 p-3 bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-900/60 text-rose-800 dark:text-rose-200 text-xs font-medium rounded-lg flex items-center gap-2">
          <app-icon name="alert-triangle" [size]="15" strokeWidth="1.5"></app-icon>
          <span>{{ errorMessage }}</span>
        </div>
      }

      <form [formGroup]="registerForm" (ngSubmit)="onSubmit()" class="space-y-3.5" autocomplete="off">
        <!-- Hidden dummy inputs to capture aggressive browser autofill -->
        <input type="text" style="display:none" aria-hidden="true" tabindex="-1" autocomplete="false" />
        <input type="password" style="display:none" aria-hidden="true" tabindex="-1" autocomplete="false" />

        <!-- Full Name -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Full Name
          </label>
          <input
            type="text"
            formControlName="name"
            placeholder="e.g. Marcus Chen"
            autocomplete="off"
            class="input-brutal"
          />
        </div>

        <!-- Campus Email -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Campus Email (.edu)
          </label>
          <input
            type="email"
            formControlName="email"
            placeholder="student@campus.edu"
            autocomplete="off"
            class="input-brutal"
          />
          @if (registerForm.get('email')?.invalid && registerForm.get('email')?.touched) {
            <span class="text-xs text-rose-500 mt-0.5 block">Valid email required</span>
          }
        </div>

        <!--
          Year of study. Signup itself accepts only name, email and password, so this is saved to
          the profile straight after the account exists (PATCH /profile/me). Sending it as part of
          the registration body would be silently dropped — there is no such field there.
        -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Year
          </label>
          <select formControlName="academicYear" class="input-brutal">
            <option value="" disabled selected>Select Year of Study</option>
            <option value="Freshman (1st Year)">Freshman</option>
            <option value="Sophomore (2nd Year)">Sophomore</option>
            <option value="Junior (3rd Year)">Junior</option>
            <option value="Senior (4th Year)">Senior</option>
            <option value="Graduate / Master">Graduate</option>
          </select>
        </div>

        <!-- Password -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Password (min 6 chars)
          </label>
          <input
            type="password"
            formControlName="password"
            placeholder="••••••••"
            autocomplete="new-password"
            class="input-brutal"
          />
        </div>

        <!-- Confirm Password -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Confirm Password
          </label>
          <input
            type="password"
            formControlName="confirmPassword"
            placeholder="••••••••"
            autocomplete="new-password"
            class="input-brutal"
          />
          @if (registerForm.hasError('passwordMismatch') && registerForm.get('confirmPassword')?.touched) {
            <span class="text-xs text-rose-500 mt-0.5 block">Passwords do not match!</span>
          }
        </div>

        <button
          type="submit"
          [disabled]="registerForm.invalid || isLoading"
          class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-600 text-neutral-950 font-medium rounded-lg text-sm shadow-xs transition-colors cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50 mt-2"
        >
          @if (isLoading) {
            <svg class="animate-spin h-4 w-4 text-current" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
            </svg>
          }
          <span>Create Student Account →</span>
        </button>
      </form>

      <div class="mt-5 pt-4 border-t border-neutral-200 dark:border-neutral-800 text-center text-xs text-neutral-500">
        Already registered?
        <a routerLink="/auth/login" class="text-amber-600 dark:text-amber-400 font-medium hover:underline ml-1">
          Sign In
        </a>
      </div>
    </div>
  `
})
export class RegisterComponent implements OnInit {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  private cdr = inject(ChangeDetectorRef);

  isLoading = false;
  errorMessage = '';

  registerForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    academicYear: ['', [Validators.required]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    confirmPassword: ['', [Validators.required]]
  }, { validators: passwordMatchValidator });

  ngOnInit(): void {

    this.registerForm.reset({
      name: '',
      email: '',
      academicYear: '',
      password: '',
      confirmPassword: ''
    });
  }

  onSubmit(): void {
    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      return;
    }

    localStorage.removeItem('campus_coin_token');
    localStorage.removeItem('campus_coin_user');

    this.isLoading = true;
    this.errorMessage = '';

    const val = this.registerForm.value;
    this.auth.register({
      fullName: val.name!,
      email: val.email!,
      password: val.password!,
      confirmPassword: val.confirmPassword!
    }).subscribe({
      next: () => {

        this.auth.login(val.email!, val.password!).subscribe({
          next: () => {

            this.auth.updateProfile({ academicYear: val.academicYear! }).subscribe({
              next: () => this.finishSignup(),
              error: () => this.finishSignup()
            });
          },
          error: () => {
            this.isLoading = false;
            this.router.navigate(['/auth/login']);
            this.cdr.markForCheck();
          }
        });
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || err.message || 'Registration failed. Check requirements (password min 8 chars with uppercase, lowercase and digit).';

        this.cdr.markForCheck();
      }
    });
  }

  private finishSignup(): void {
    this.isLoading = false;
    this.router.navigate(['/app/home']);
  }
}
