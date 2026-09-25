import { Component, inject } from '@angular/core';
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
        <span class="inline-flex items-center text-[11px] font-medium px-2.5 py-0.5 rounded-full bg-amber-500/10 text-amber-700 dark:text-amber-400 border border-amber-500/20 mb-2">
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

      <form [formGroup]="registerForm" (ngSubmit)="onSubmit()" class="space-y-3.5">
        <!-- Full Name -->
        <div>
          <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
            Full Name
          </label>
          <input
            type="text"
            formControlName="name"
            placeholder="e.g. Marcus Chen"
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
            class="input-brutal"
          />
          @if (registerForm.get('email')?.invalid && registerForm.get('email')?.touched) {
            <span class="text-xs text-rose-500 mt-0.5 block">Valid email required</span>
          }
        </div>

        <!-- Academic Year & Major -->
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Year
            </label>
            <select formControlName="academicYear" class="input-brutal">
              <option value="Freshman (1st Year)">Freshman</option>
              <option value="Sophomore (2nd Year)">Sophomore</option>
              <option value="Junior (3rd Year)">Junior</option>
              <option value="Senior (4th Year)">Senior</option>
              <option value="Graduate / Master">Graduate</option>
            </select>
          </div>
          <div>
            <label class="block text-xs font-medium uppercase tracking-wider text-neutral-500 mb-1">
              Major
            </label>
            <input
              type="text"
              formControlName="major"
              placeholder="e.g. CS, Bio"
              class="input-brutal"
            />
          </div>
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
            <span class="inline-block animate-spin">⏳</span>
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
export class RegisterComponent {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);

  isLoading = false;
  errorMessage = '';

  registerForm = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    academicYear: ['Freshman (1st Year)', [Validators.required]],
    major: ['', [Validators.required]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    confirmPassword: ['', [Validators.required]]
  }, { validators: passwordMatchValidator });

  onSubmit(): void {
    if (this.registerForm.invalid) {
      this.registerForm.markAllAsTouched();
      return;
    }

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
        // Auto sign-in after successful registration
        this.auth.login(val.email!, val.password!).subscribe({
          next: () => {
            this.isLoading = false;
            this.router.navigate(['/app/home']);
          },
          error: () => {
            this.isLoading = false;
            this.router.navigate(['/auth/login']);
          }
        });
      },
      error: (err) => {
        this.isLoading = false;
        this.errorMessage = err.error?.message || err.message || 'Registration failed. Check requirements (password min 8 chars with uppercase, lowercase and digit).';
      }
    });
  }
}
