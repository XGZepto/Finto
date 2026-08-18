import { Component, inject, signal, viewChild } from '@angular/core';
import { NavigationEnd, NavigationError, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Preferences } from './core/preferences.service';
import { isCollapsedContentPane, isDeadChunkError, recoverAction } from './core/pwa-lifecycle';
import { Refresh } from './core/refresh.service';
import { scrollPane } from './core/scroll';
import { NavIcon } from './shared/nav-icon';
import { PullToRefresh } from './shared/pull-to-refresh';

/**
 * App shell.
 *
 * Navigation stays quiet. Connectivity is the only ambient state that crosses
 * every route; other status belongs beside the data that explains it.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NavIcon, PullToRefresh],
  template: `
    <div class="shell">
      <nav class="sidebar">
        <div class="brand">
          <div class="brand-row"><img class="brand-logo" src="/favicon.svg?v=3" alt=""><div class="name">Finto</div></div>
        </div>

        <div class="nav" aria-label="Primary navigation">
          @for (group of desktopGroups; track group.label) {
            <section class="nav-group">
              <h2>{{ preferences.text(group.label, group.labelZh) }}</h2>
              @for (item of group.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active">
                  <span class="idx"><finto-nav-icon [name]="item.icon" /></span>
                  <span class="label">{{ preferences.text(item.label, item.labelZh) }}</span>
                </a>
              }
            </section>
          }
        </div>

        <div class="spacer"></div>
        <div class="nav nav-settings">
          <a routerLink="/tools" routerLinkActive="active">
            <span class="idx"><finto-nav-icon name="more" /></span><span class="label">{{ preferences.text('More', '更多') }}</span>
          </a>
        </div>
      </nav>

      <main class="content">
        <finto-pull-to-refresh />
        @if (offline()) {
          <p class="offline" role="status">{{ preferences.text('offline', '離線') }}</p>
        }
        <router-outlet />
      </main>
      <nav class="mobile-nav" aria-label="Primary navigation">
        @for (item of primaryNav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active">
            <span class="mobile-icon">
              <finto-nav-icon [name]="item.icon" />
            </span>
            <span>{{ preferences.text(item.label, item.labelZh) }}</span>
          </a>
        }
        <a routerLink="/tools" routerLinkActive="active">
          <span class="mobile-icon"><finto-nav-icon name="more" /></span>
          <span>{{ preferences.text('More', '更多') }}</span>
        </a>
      </nav>
    </div>
  `,
  styleUrl: './app.css',
})
export class App {
  private router = inject(Router);
  private refreshes = inject(Refresh);
  private outlet = viewChild(RouterOutlet);
  preferences = inject(Preferences);

  readonly nav = [
    { path: '/summary', label: 'Summary', labelZh: '總覽', icon: 'summary' },
    { path: '/blotter', label: 'Blotter', labelZh: '帳目', icon: 'blotter' },
    { path: '/reports', label: 'Reports', labelZh: '報表', icon: 'timeline' },
    { path: '/accounts', label: 'Accounts', labelZh: '帳戶', icon: 'accounts' },
    { path: '/recurring', label: 'Recurring', labelZh: '定期', icon: 'installments' },
    { path: '/ask', label: 'Ask', labelZh: '查詢', icon: 'ask' },
  ];

  /** Stable top-level destinations earn tabs; episodic utilities stay in More. */
  readonly primaryNav = this.nav.slice(0, 4);

  readonly desktopGroups = [
    { label: 'Money', labelZh: '財務', items: this.nav.slice(0, 4) },
    { label: 'More', labelZh: '更多', items: this.nav.slice(4) },
  ];

  /** Live, not a boot-time probe: connectivity is only worth showing while it changes. */
  offline = signal(!navigator.onLine);
  private lastPath = '';
  private hiddenAt = Date.now();

  constructor() {
    this.preferences.loadUser();
    this.syncViewport();
    for (const event of ['online', 'offline']) {
      window.addEventListener(event, () => this.offline.set(!navigator.onLine));
    }
    window.addEventListener('resize', this.syncViewport);
    window.addEventListener('orientationchange', this.syncViewport);
    document.addEventListener('visibilitychange', this.onVisibility);
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationEnd) {
        sessionStorage.removeItem('finto.chunkReload');
        this.scrollOnPathChange(event.urlAfterRedirects);
      }
      if (event instanceof NavigationError) this.reloadOnDeadChunk(event);
    });
  }

  /**
   * Resume after the document was hidden.
   */
  private onVisibility = (): void => {
    if (document.visibilityState === 'hidden') {
      this.hiddenAt = Date.now();
      return;
    }
    this.recoverForeground();
  };

  private recoverForeground(): void {
    this.syncViewport();
    const pane = document.querySelector<HTMLElement>('.content');
    const outlet = this.outlet();
    const action = recoverAction({
      contentHeight: pane?.clientHeight ?? 0,
      outletActivated: outlet ? outlet.isActivated : null,
      hiddenMs: Date.now() - this.hiddenAt,
    });
    if (action === 'reload') {
      requestAnimationFrame(() => {
        this.syncViewport();
        const height = document.querySelector<HTMLElement>('.content')?.clientHeight ?? 0;
        if (isCollapsedContentPane(height)) location.reload();
      });
      return;
    }
    this.refreshes.recover();
    if (action === 'remount') void this.router.navigateByUrl(this.router.url);
  }

  private syncViewport = (): void => {
    document.documentElement.style.setProperty('--app-height', `${window.innerHeight}px`);
  };

  /** Reload once on a failed lazy-chunk navigation. */
  private reloadOnDeadChunk(event: NavigationError): void {
    if (!isDeadChunkError(event.error)) return;
    if (sessionStorage.getItem('finto.chunkReload')) return;
    sessionStorage.setItem('finto.chunkReload', '1');
    location.reload();
  }

  /**
   * Reset scroll on a real page change only.
   *
   * Overlays push a same-URL history entry so the back gesture dismisses them;
   * the router's own scroller reads that as a navigation and jumps to the top.
   */
  private scrollOnPathChange(url: string): void {
    const path = url.split('?')[0];
    if (path === this.lastPath) return;
    this.lastPath = path;
    // Reset immediately and after the incoming view has laid out. A clicked
    // row can otherwise be focus-scrolled after NavigationEnd and leak the
    // list's offset into its detail page.
    const reset = () => scrollPane()?.scrollTo(0, 0);
    reset();
    requestAnimationFrame(() => requestAnimationFrame(reset));
  }
}
