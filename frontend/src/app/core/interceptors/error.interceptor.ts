import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      const isSignIn = req.url.includes('/auth/login') || req.url.includes('/admin/auth/login');

      if (err.status === 401 && !isSignIn) {
        auth.logoutLocally();
        router.navigate(['/auth/login']);
      }

      return throwError(() => err);
    })
  );
};
