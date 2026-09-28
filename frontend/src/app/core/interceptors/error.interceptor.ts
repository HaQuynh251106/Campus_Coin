import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError, timeout, TimeoutError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

let isRedirecting = false;

const DEFAULT_TIMEOUT_MS = 12000;

const CHAT_TIMEOUT_MS = 60000;

const CHAT_PATH = '/api/v1/chat';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isChat = req.url.includes(CHAT_PATH);
  const timeoutMs = isChat ? CHAT_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;

  return next(req).pipe(
    timeout(timeoutMs),
    catchError((err: unknown) => {
      if (err instanceof TimeoutError) {
        console.warn(`[HTTP Timeout] Request to ${req.url} timed out after ${timeoutMs / 1000}s.`);
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

          router.navigate(['/auth/login']).finally(() => {
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

