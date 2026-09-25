import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('Auth & Guest Guards', () => {
  let authService: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        AuthService,
        {
          provide: Router,
          useValue: {
            createUrlTree: (commands: string[]) => ({ toString: () => commands.join('/') } as UrlTree)
          }
        }
      ]
    });

    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    authService.logout();
  });

  describe('authGuard', () => {
    it('should redirect unauthenticated users to /auth/login', () => {
      authService.currentUser.set(null);
      const result = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));
      expect(result.toString()).toBe('/auth/login');
    });

    it('should permit authenticated users', () => {
      authService.currentUser.set({ id: 'user-001' } as any);
      const result = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));
      expect(result).toBe(true);
    });
  });

  describe('guestGuard', () => {
    it('should permit unauthenticated visitors to view the landing page', () => {
      authService.currentUser.set(null);
      const result = TestBed.runInInjectionContext(() => guestGuard({} as any, {} as any));
      expect(result).toBe(true);
    });

    it('should redirect authenticated visitors from landing page straight to /app/home', () => {
      authService.currentUser.set({ id: 'user-001' } as any);
      const result = TestBed.runInInjectionContext(() => guestGuard({} as any, {} as any));
      expect(result.toString()).toBe('/app/home');
    });
  });
});
