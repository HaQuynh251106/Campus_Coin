import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError, timeout, TimeoutError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

let isRedirecting = false;

/**
 * How long an ordinary API call may take before it is abandoned.
 *
 * Every endpoint answers from a local database in well under a second, so twelve seconds is already
 * generous - it is a guard against a hung connection, not a latency budget.
 */
const DEFAULT_TIMEOUT_MS = 12000;

/**
 * How long the chat request may take.
 *
 * `POST /api/v1/chat` is not one database read: the backend may call the provider up to five times,
 * running a tool between each, and each provider call has its own twenty-second backend bound. A
 * multi-step question was measured at 5.5s against the live provider, which fits inside the default -
 * but only just, and a question needing more tool rounds, or a provider answering slowly, would be
 * abandoned by the client while the backend was still working. The student would be told the
 * assistant could not be reached while it was in fact still answering.
 *
 * Only this one path is raised; every other request keeps the twelve-second guard above.
 */
const CHAT_TIMEOUT_MS = 60000;

/** The chat endpoint, matched by path so a trailing slash or a query string does not miss it. */
const CHAT_PATH = '/api/v1/chat';

export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const isChat = req.url.includes(CHAT_PATH);
  const timeoutMs = isChat ? CHAT_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;

  // Apply a timeout so a request cannot hang indefinitely. The chat path gets a longer one because
  // it may wait on several provider round-trips; see CHAT_TIMEOUT_MS.
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

