import { HttpInterceptorFn } from '@angular/common/http';
import { delay } from 'rxjs/operators';

export const mockDelayInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req).pipe(delay(250));
};
