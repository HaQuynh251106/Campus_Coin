import { Injectable, signal, computed, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { Observable, of, throwError } from 'rxjs';
import { delay, tap } from 'rxjs/operators';
import { User } from '../models/user.model';
import { MOCK_USERS } from '../../mock-data/users.mock';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private platformId = inject(PLATFORM_ID);
  private isBrowser = isPlatformBrowser(this.platformId);

  private usersList: User[] = [...MOCK_USERS];
  readonly currentUser = signal<User | null>(null);

  readonly isLoggedIn = computed(() => !!this.currentUser());
  readonly isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');
  readonly isStudent = computed(() => this.currentUser()?.role === 'STUDENT');

  constructor() {
    this.restoreSession();
  }

  private restoreSession(): void {
    if (!this.isBrowser) return;
    const stored = localStorage.getItem('campus_coin_user');
    if (stored) {
      try {
        const user = JSON.parse(stored) as User;
        this.currentUser.set(user);
        return;
      } catch (e) {
        console.error('Failed to parse cached session:', e);
      }
    }
    // Default student session for rapid demo navigation
    const defaultStudent = this.usersList.find(u => u.id === 'user-001') || this.usersList[0];
    this.currentUser.set(defaultStudent);
    localStorage.setItem('campus_coin_user', JSON.stringify(defaultStudent));
  }

  login(email: string, _pass: string): Observable<User> {
    const user = this.usersList.find(u => u.email.toLowerCase() === email.toLowerCase());
    if (user) {
      if (user.status === 'DISABLED') {
        return throwError(() => new Error('This student account is currently disabled. Please contact the administrator.'));
      }
      return of(user).pipe(
        tap(u => this.setCurrentUser(u))
      );
    }
    // If not found in mock, create a demo student session
    const mockStudent: User = {
      ...this.usersList[0],
      email: email,
      name: email.split('@')[0].replace('.', ' ').toUpperCase()
    };
    return of(mockStudent).pipe(
      tap(u => this.setCurrentUser(u))
    );
  }

  adminLogin(email: string, _pass: string): Observable<User> {
    const admin = this.usersList.find(u => u.role === 'ADMIN');
    if (admin) {
      return of(admin).pipe(
        tap(u => this.setCurrentUser(u))
      );
    }
    return throwError(() => new Error('Admin credentials invalid'));
  }

  register(payload: Partial<User> & { password?: string }): Observable<User> {
    const newUser: User = {
      id: `user-${Date.now()}`,
      studentId: `SV2026-${Math.floor(100 + Math.random() * 900)}`,
      name: payload.name || 'Student User',
      email: payload.email || 'student@campus.edu',
      avatar: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=256&q=80',
      role: 'STUDENT',
      university: payload.university || 'State University of Technology',
      major: payload.major || 'Undeclared',
      academicYear: payload.academicYear || 'Freshman (1st Year)',
      monthlyAllowance: payload.monthlyAllowance || 500,
      savingsGoal: payload.savingsGoal || 1000,
      settings: {
        darkMode: false,
        fontSize: 'medium',
        currency: '$'
      },
      status: 'ACTIVE',
      joinedDate: new Date().toISOString().split('T')[0]
    };

    this.usersList.push(newUser);
    return of(newUser).pipe(
      tap(u => this.setCurrentUser(u))
    );
  }

  requestPasswordReset(email: string): Observable<boolean> {
    // Simulate lookup and sending email
    return of(true);
  }

  resetPassword(_token: string, _newPass: string): Observable<boolean> {
    // Simulate updating password
    return of(true);
  }

  logout(): void {
    this.currentUser.set(null);
    if (this.isBrowser) {
      localStorage.removeItem('campus_coin_user');
    }
  }

  updateProfile(updates: Partial<User>): Observable<User> {
    const current = this.currentUser();
    if (!current) {
      return throwError(() => new Error('No user logged in'));
    }
    const updated: User = { ...current, ...updates };
    this.setCurrentUser(updated);

    // Update in memory list
    const idx = this.usersList.findIndex(u => u.id === current.id);
    if (idx !== -1) {
      this.usersList[idx] = updated;
    }

    return of(updated).pipe(delay(250));
  }

  private setCurrentUser(user: User): void {
    this.currentUser.set(user);
    if (this.isBrowser) {
      localStorage.setItem('campus_coin_user', JSON.stringify(user));
    }
  }

  getAllUsers(): Observable<User[]> {
    return of([...this.usersList]).pipe(delay(200));
  }

  setUserStatus(userId: string, status: 'ACTIVE' | 'DISABLED'): Observable<User> {
    const user = this.usersList.find(u => u.id === userId);
    if (user) {
      user.status = status;
      return of(user).pipe(delay(200));
    }
    return throwError(() => new Error('User not found'));
  }
}
