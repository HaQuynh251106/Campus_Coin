import { Injectable, signal, computed, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthResponse, User, UserSummary, ProfileResponse } from '../models/user.model';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private http = inject(HttpClient);
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);
  private baseUrl = `${environment.apiUrl}/v1`;

  readonly accessToken = signal<string | null>(null);
  readonly currentUser = signal<User | null>(null);

  readonly isLoggedIn = computed(() => !!this.accessToken());
  readonly isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');
  readonly isStudent = computed(() => this.currentUser()?.role === 'STUDENT');

  constructor() {
    this.restoreSession();
  }

  private restoreSession(): void {
    if (!this.isBrowser) return;
    const token = localStorage.getItem('campus_coin_token');
    const storedUser = localStorage.getItem('campus_coin_user');

    if (token) {
      this.accessToken.set(token);
    }
    if (storedUser) {
      try {
        const u = JSON.parse(storedUser);
        this.currentUser.set(u);
      } catch (e) {
        console.error('Failed to parse stored user:', e);
      }
    }
  }

  private persistSession(authRes: AuthResponse): void {
    this.accessToken.set(authRes.accessToken);
    const u: User = {
      id: authRes.user.id,
      name: authRes.user.fullName,
      email: authRes.user.email,
      role: authRes.user.role,
      status: 'ACTIVE'
    };
    this.currentUser.set(u);

    if (this.isBrowser) {
      localStorage.setItem('campus_coin_token', authRes.accessToken);
      localStorage.setItem('campus_coin_user', JSON.stringify(u));
    }
  }

  login(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.baseUrl}/auth/login`, { email, password }).pipe(
      tap(res => this.persistSession(res))
    );
  }

  loginAsDemo(): Observable<AuthResponse> {
    return this.login('an.nguyen@student.campuscoin.edu', 'Student@123');
  }

  adminLogin(email: string, password: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.baseUrl}/admin/auth/login`, { email, password }).pipe(
      tap(res => this.persistSession(res))
    );
  }

  register(payload: {
    fullName: string;
    email: string;
    password: string;
    confirmPassword: string;
  }): Observable<any> {
    return this.http.post(`${this.baseUrl}/auth/register`, payload);
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/auth/logout`, {}).pipe(
      tap(() => this.logoutLocally())
    );
  }

  logoutLocally(): void {
    this.accessToken.set(null);
    this.currentUser.set(null);
    if (this.isBrowser) {
      localStorage.removeItem('campus_coin_token');
      localStorage.removeItem('campus_coin_user');
    }
  }

  requestPasswordReset(email: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.baseUrl}/auth/password-reset/request`, { email });
  }

  verifyResetToken(token: string): Observable<{ valid: boolean }> {
    return this.http.post<{ valid: boolean }>(`${this.baseUrl}/auth/password-reset/verify`, { token });
  }

  completePasswordReset(token: string, newPassword: string, confirmPassword: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.baseUrl}/auth/password-reset/complete`, {
      token,
      newPassword,
      confirmPassword
    });
  }

  getProfile(): Observable<ProfileResponse> {
    return this.http.get<ProfileResponse>(`${this.baseUrl}/profile/me`).pipe(
      tap(p => {
        const curr = this.currentUser();
        if (curr) {
          const updated: User = {
            ...curr,
            name: p.fullName,
            academicYear: p.academicYear || undefined,
            monthlyAllowance: p.monthlyAllowanceBaseline,
            savingsGoal: p.monthlySavingsGoal
          };
          this.currentUser.set(updated);
          if (this.isBrowser) {
            localStorage.setItem('campus_coin_user', JSON.stringify(updated));
          }
        }
      })
    );
  }

  updateProfile(updates: {
    fullName?: string;
    name?: string;
    academicYear?: string;
    major?: string;
    monthlyAllowanceBaseline?: number;
    monthlyAllowance?: number;
    monthlySavingsGoal?: number;
    savingsGoal?: number;
  }): Observable<ProfileResponse> {
    const body = {
      fullName: updates.fullName || updates.name,
      academicYear: updates.academicYear,
      monthlyAllowanceBaseline: updates.monthlyAllowanceBaseline ?? updates.monthlyAllowance,
      monthlySavingsGoal: updates.monthlySavingsGoal ?? updates.savingsGoal
    };
    return this.http.patch<ProfileResponse>(`${this.baseUrl}/profile/me`, body).pipe(
      tap(p => {
        const curr = this.currentUser();
        if (curr) {
          const updated: User = {
            ...curr,
            name: p.fullName,
            academicYear: p.academicYear || undefined,
            monthlyAllowance: p.monthlyAllowanceBaseline,
            savingsGoal: p.monthlySavingsGoal
          };
          this.currentUser.set(updated);
          if (this.isBrowser) {
            localStorage.setItem('campus_coin_user', JSON.stringify(updated));
          }
        }
      })
    );
  }

  updatePreferences(preferences: {
    themePreference?: 'LIGHT' | 'DARK' | 'SYSTEM';
    fontScale?: 'SMALL' | 'MEDIUM' | 'LARGE' | 'XLARGE';
  }): Observable<ProfileResponse> {
    return this.http.patch<ProfileResponse>(`${this.baseUrl}/profile/me/preferences`, preferences);
  }
}
