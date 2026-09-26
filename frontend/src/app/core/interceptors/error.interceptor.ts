import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError, timeout, TimeoutError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

let isRedirecting = false;

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  // Apply 12-second timeout to prevent requests from hanging indefinitely
  return next(req).pipe(
    timeout(12000),
    catchError((err: unknown) => {
      if (err instanceof TimeoutError) {
        console.warn(`[HTTP Timeout] Request to ${req.url} timed out after 12s.`);
        return throwError(() => new HttpErrorResponse({
          error: { message: 'Request timed out. Please check your connection and try again.' },
          status: 504,
          statusText: 'Gateway Timeout',
          url: req.url
        }));
      }

      const httpErr = err as HttpErrorResponse;
      const isSignIn = req.url.includes('/auth/login') || req.url.includes('/admin/auth/login');

      if (httpErr.status === 401 && !isSignIn) {
        if (!isRedirecting) {
          isRedirecting = true;
          auth.logoutLocally();

          const isAdminRoute = router.url.startsWith('/admin') || req.url.includes('/admin');
          const targetLogin = isAdminRoute ? '/auth/admin-login' : '/auth/login';

          router.navigate([targetLogin]).finally(() => {
            setTimeout(() => {
              isRedirecting = false;
            }, 1000);
          });
        }
      }

      return throwError(() => httpErr);
    })
  );
};

