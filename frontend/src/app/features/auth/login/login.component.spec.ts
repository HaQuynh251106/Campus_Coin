import { describe, it, expect, beforeEach, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from '../../../core/services/auth.service';
import { AuthResponse } from '../../../core/models/user.model';

describe('LoginComponent (Unified Login)', () => {
  let component: LoginComponent;
  let fixture: ComponentFixture<LoginComponent>;
  let authService: AuthService;
  let router: Router;

  const mockAuthResponse = (role: 'STUDENT' | 'ADMIN'): AuthResponse => ({
    accessToken: 'test-token',
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: {
      id: 1,
      email: role === 'ADMIN' ? 'admin@campuscoin.edu' : 'an.nguyen@student.campuscoin.edu',
      fullName: role === 'ADMIN' ? 'System Administrator' : 'An Nguyen',
      role
    }
  });

  beforeEach(async () => {
    const authMock = {
      login: vi.fn(),
      isAdmin: vi.fn()
    };

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authMock }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
  });

  it('should create the unified login component', () => {
    expect(component).toBeTruthy();
  });

  it('should fill demo student credentials', () => {
    component.fillDemoStudent();
    expect(component.loginForm.value.email).toBe('an.nguyen@student.campuscoin.edu');
    expect(component.loginForm.value.password).toBe('Student@123');
  });

  it('should fill demo admin credentials', () => {
    component.fillDemoAdmin();
    expect(component.loginForm.value.email).toBe('admin@campuscoin.edu');
    expect(component.loginForm.value.password).toBe('Admin@123');
  });

  it('should redirect to /app/home after successful student sign-in', () => {
    vi.mocked(authService.login).mockReturnValue(of(mockAuthResponse('STUDENT')));
    vi.mocked(authService.isAdmin).mockReturnValue(false);

    component.loginForm.setValue({
      email: 'an.nguyen@student.campuscoin.edu',
      password: 'Student@123'
    });

    component.onSubmit();

    expect(authService.login).toHaveBeenCalledWith('an.nguyen@student.campuscoin.edu', 'Student@123');
    expect(router.navigate).toHaveBeenCalledWith(['/app/home']);
  });

  it('should redirect to /admin/dashboard after successful admin sign-in', () => {
    vi.mocked(authService.login).mockReturnValue(of(mockAuthResponse('ADMIN')));
    vi.mocked(authService.isAdmin).mockReturnValue(true);

    component.loginForm.setValue({
      email: 'admin@campuscoin.edu',
      password: 'Admin@123'
    });

    component.onSubmit();

    expect(authService.login).toHaveBeenCalledWith('admin@campuscoin.edu', 'Admin@123');
    expect(router.navigate).toHaveBeenCalledWith(['/admin/dashboard']);
  });
});
