import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService } from '../services/auth.service';

const PUBLIC_PATHS = [
  '/api/v1/auth/register',
  '/api/v1/auth/login',
  '/api/v1/admin/auth/login',
  '/api/v1/auth/password-reset/request',
  '/api/v1/auth/password-reset/verify',
  '/api/v1/auth/password-reset/complete'
];

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const token = authService.accessToken();

  const isPublic = PUBLIC_PATHS.some(path => req.url.includes(path));

  if (!token || isPublic) {
    return next(req);
  }

  return next(req.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  }));
};
