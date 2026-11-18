import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  BaseRouteReuseStrategy,
  RouteReuseStrategy,
  provideRouter,
} from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';

import { routes } from './app.routes';

class ArenaRouteReuseStrategy extends BaseRouteReuseStrategy {
  override shouldReuseRoute(
    future: ActivatedRouteSnapshot,
    current: ActivatedRouteSnapshot,
  ): boolean {
    return (
      super.shouldReuseRoute(future, current) &&
      (future.routeConfig?.path !== 'games/:id' ||
        future.paramMap.get('id') === current.paramMap.get('id'))
    );
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    { provide: RouteReuseStrategy, useClass: ArenaRouteReuseStrategy },
    provideHttpClient(),
    provideAnimations(),
  ],
};
