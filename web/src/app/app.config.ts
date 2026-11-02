import {
  ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ActivatedRouteSnapshot, PreloadAllModules, provideRouter, withComponentInputBinding,
  withInMemoryScrolling, withPreloading, withRouterConfig, withViewTransitions,
} from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth-interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding(), withPreloading(PreloadAllModules),
      withInMemoryScrolling({ scrollPositionRestoration: 'disabled', anchorScrolling: 'enabled' }),
      withRouterConfig({ onSameUrlNavigation: 'reload' }),
      withViewTransitions({
        skipInitialTransition: true,
        // Filters live in the query string, so typing in a search box is a
        // navigation too. Only a change of page earns the cross-fade.
        onViewTransitionCreated: ({ transition, from, to }) => {
          if (pageKey(from) === pageKey(to)) transition.skipTransition();
        },
      })),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
  ],
};

/** Route pattern plus its path params: what distinguishes one page from another. */
function pageKey(snapshot: ActivatedRouteSnapshot): string {
  let node = snapshot;
  while (node.firstChild) node = node.firstChild;
  return `${node.routeConfig?.path ?? ''}|${JSON.stringify(node.params)}`;
}
