import { HttpInterceptorFn } from '@angular/common/http';
import { delay } from 'rxjs/operators';

/**
 * Mock Delay Interceptor
 * Simulates real-world network latency (150ms-300ms) for HTTP requests
 * ensuring loading skeletons and spinners are properly testable.
 */
export const mockDelayInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req).pipe(delay(250));
};
