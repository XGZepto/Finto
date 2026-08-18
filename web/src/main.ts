import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));

if ('serviceWorker' in navigator && location.protocol === 'https:') {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' }).catch(() => undefined);
  });
  // A waiting worker that then claims this client can mix old HTML with new
  // hashed chunks (or the reverse). Reload once so the shell and lazy routes
  // come from the same build.
  let reloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    if (reloading) return;
    reloading = true;
    location.reload();
  });
}

// Installed PWAs are frozen, not closed, when you leave them. WebKit may restore
// the page from bfcache with dead fetch() and dynamic import() promises; the
// nav (already in the main bundle) stays on screen and the outlet stays empty.
window.addEventListener('pageshow', (event) => {
  if (event.persisted) location.reload();
});
