import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

/**
 * A 401 means the session is gone, not that one request failed.
 *
 * Only a document navigation passes through the edge middleware, so an expired
 * session inside an already-open app surfaced as panels that silently stayed
 * empty — indistinguishable from a ledger with no data — until something
 * reloaded the page. Sending it to the sign-in screen names what happened, and
 * carries the current URL so the user comes back to the view they were on.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const router = inject(Router);
  return next(request).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !router.url.startsWith('/login')) {
        router.navigate(['/login'], { queryParams: { next: router.url } });
      }
      return throwError(() => error);
    }),
  );
};
