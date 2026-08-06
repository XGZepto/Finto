import { Component, inject, signal } from '@angular/core';
import {
  NavigationCancel, NavigationEnd, NavigationError, NavigationStart, Router,
  RouterLink, RouterLinkActive, RouterOutlet,
} from '@angular/router';
import { Api } from './core/api.service';
import { Preferences } from './core/preferences.service';
import { scrollPane } from './core/scroll';
import { NavIcon } from './shared/nav-icon';

/**
 * App shell.
 *
 * The two badges are the only ambient state worth interrupting for: how much is
 * sitting in the review queues, and whether the ledger currently reconciles. Both
 * are questions you want answered without navigating to ask them.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NavIcon],
  template: `
    <div class="shell">
      <nav class="sidebar">
        <div class="brand">
          <div class="brand-row"><img class="brand-logo" src="/favicon.svg?v=3" alt=""><div class="name">Finto@if (navigating()) { <span class="caret">▌</span> }</div></div>
        </div>

        <div class="nav" aria-label="Primary navigation">
          @for (group of desktopGroups; track group.label) {
            <section class="nav-group">
              <h2>{{ preferences.text(group.label, group.labelZh) }}</h2>
              @for (item of group.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active">
                  <span class="idx"><finto-nav-icon [name]="item.icon" /></span>
                  <span class="label">{{ preferences.text(item.label, item.labelZh) }}</span>
                  @if (item.path === '/review' && reviewCount() > 0) {
                    <span class="badge">{{ reviewCount() }}</span>
                  }
                </a>
              }
            </section>
          }
        </div>

        <div class="spacer"></div>
        <div class="nav nav-settings">
          <a routerLink="/tools" routerLinkActive="active">
            <span class="idx"><finto-nav-icon name="settings" /></span><span class="label">{{ preferences.text('Tools', '工具') }}</span>
          </a>
        </div>
        @if (!online()) {
          <div class="status">
            <span class="dot bad"></span>
            <span>{{ preferences.text('api unreachable', '無法連接 API') }}</span>
          </div>
        }
      </nav>

      <div class="status-scrim" aria-hidden="true"></div>
      <main class="content">
        <router-outlet />
      </main>

      <nav class="mobile-nav" aria-label="Primary navigation">
        @for (item of primaryNav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active">
            <span class="mobile-icon"><finto-nav-icon [name]="item.icon" /></span>
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
  private api = inject(Api);
  private router = inject(Router);
  preferences = inject(Preferences);

  /**
   * One ordered list. The first four are the mobile tab bar and the desktop
   * "Money" group; everything after is the desktop "More" group and lives
   * behind the mobile More tab. The group boundary is the fold, so the two
   * platforms cannot drift apart.
   */
  readonly nav = [
    { path: '/summary', label: 'Summary', labelZh: '總覽', icon: 'summary' },
    { path: '/blotter', label: 'Blotter', labelZh: '帳目', icon: 'blotter' },
    { path: '/reports', label: 'Reports', labelZh: '報表', icon: 'timeline' },
    { path: '/accounts', label: 'Accounts', labelZh: '帳戶', icon: 'accounts' },
    { path: '/recurring', label: 'Recurring', labelZh: '定期', icon: 'installments' },
    { path: '/ask', label: 'Ask', labelZh: '查詢', icon: 'ask' },
  ];

  readonly primaryNav = this.nav.slice(0, 4);
  readonly desktopGroups = [
    { label: 'Money', labelZh: '財務', items: this.nav.slice(0, 4) },
    { label: 'More', labelZh: '更多', items: this.nav.slice(4) },
  ];

  reviewCount = signal(0);
  online = signal(true);
  navigating = signal(false);
  pendingPath = signal<string | null>(null);
  private lastPath = '';

  constructor() {
    this.preferences.loadUser();
    this.api.health().subscribe({
      next: () => this.online.set(true),
      error: () => this.online.set(false),
    });
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) this.navigating.set(true);
      if (event instanceof NavigationEnd || event instanceof NavigationCancel || event instanceof NavigationError) {
        this.navigating.set(false);
        this.pendingPath.set(null);
      }
      if (event instanceof NavigationEnd) this.scrollOnPathChange(event.urlAfterRedirects);
    });
    this.api.stats().subscribe({
      next: (s) =>
        this.reviewCount.set(
          (s.open_duplicate_candidates ?? 0) +
            (s.open_transfer_candidates ?? 0) +
            (s.open_installment_candidates ?? 0),
        ),
      error: () => undefined,
    });
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
    scrollPane()?.scrollTo(0, 0);
  }


  isActive(path: string): boolean {
    return this.router.url === path || this.router.url.startsWith(`${path}/`) || this.router.url.startsWith(`${path}?`);
  }
}
