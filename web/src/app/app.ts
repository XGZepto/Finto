import { Component, inject, signal } from '@angular/core';
import {
  NavigationCancel, NavigationEnd, NavigationError, NavigationStart, Router,
  RouterLink, RouterLinkActive, RouterOutlet,
} from '@angular/router';
import { Api } from './core/api.service';
import { Preferences } from './core/preferences.service';

/**
 * App shell.
 *
 * The two badges are the only ambient state worth interrupting for: how much is
 * sitting in the review queues, and whether the ledger currently reconciles. Both
 * are questions you want answered without navigating to ask them.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <nav class="sidebar">
        <div class="brand">
          <div class="brand-row"><img class="brand-logo" src="/favicon.svg" alt=""><div class="name">Finto@if (navigating()) { <span class="caret">▌</span> }</div></div>
        </div>

        <div class="nav" aria-label="Workspace navigation">
          @for (group of desktopGroups; track group.label) {
            <section class="nav-group">
              <h2>{{ preferences.text(group.label, group.labelZh) }}</h2>
              @for (item of group.items; track item.path) {
                <a [routerLink]="item.path" routerLinkActive="active">
                  <span class="idx" aria-hidden="true">{{ item.icon }}</span>
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
          <a routerLink="/profile" routerLinkActive="active">
            <span class="idx" aria-hidden="true">⚙</span><span class="label">{{ preferences.text('Settings', '設定') }}</span>
          </a>
        </div>
        <div class="status">
          <span class="dot" [class.ok]="online()" [class.bad]="!online()"></span>
          <span>{{ online() ? preferences.text('postgres · secured', 'postgres · 已保護') : preferences.text('api unreachable', '無法連接 API') }}</span>
        </div>
      </nav>

      <header class="mobile-head">
        <div class="mobile-brand"><img class="brand-logo" src="/favicon.svg" alt="">Finto@if (navigating()) { <span class="caret">▌</span> }</div>
        <div class="mobile-state">
          @if (reviewCount() > 0) { <span class="badge">{{ reviewCount() }} review</span> }
          <span class="dot" [class.ok]="online()" [class.bad]="!online()"></span>
        </div>
      </header>

      <main class="content">
        <router-outlet />
      </main>

      @if (mobileMenu()) {
        <button class="menu-scrim" aria-label="Close menu" (click)="mobileMenu.set(false)"></button>
        <nav class="mobile-sheet" aria-label="More destinations">
          <div class="sheet-head">
            <span>{{ preferences.text('Workspace', '工作區') }}</span>
            <button class="bare close" (click)="mobileMenu.set(false)" aria-label="Close menu">×</button>
          </div>
          @for (item of secondaryNav; track item.path) {
            <a [attr.href]="item.path" [class.active]="isActive(item.path)"
               [class.pending]="pendingPath() === item.path"
               (click)="navigateMobile($event, item.path)">
              <span class="mobile-icon">{{ item.icon }}</span>
              <span>{{ preferences.text(item.label, item.labelZh) }}</span>
              @if (item.path === '/review' && reviewCount() > 0) {
                <span class="badge">{{ reviewCount() }}</span>
              }
            </a>
          }
        </nav>
      }

      <nav class="mobile-nav" aria-label="Primary navigation">
        @for (item of primaryNav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active">
            <span class="mobile-icon">{{ item.icon }}</span>
            <span>{{ preferences.text(item.mobile, item.mobileZh) }}</span>
          </a>
        }
        <button type="button" [class.active]="mobileMenu()" (click)="mobileMenu.set(!mobileMenu())">
          <span class="mobile-icon">•••</span>
          <span>{{ preferences.text('More', '更多') }}</span>
        </button>
      </nav>
    </div>
  `,
  styleUrl: './app.css',
})
export class App {
  private api = inject(Api);
  private router = inject(Router);
  preferences = inject(Preferences);

  readonly nav = [
    { path: '/summary', label: 'Summary', labelZh: '總覽', mobile: 'Overview', mobileZh: '總覽', icon: '⌁' },
    { path: '/blotter', label: 'Blotter', labelZh: '帳目', mobile: 'Activity', mobileZh: '帳目', icon: '≡' },
    { path: '/timeline', label: 'Timeline', labelZh: '時間軸', mobile: 'Timeline', mobileZh: '時間軸', icon: '⌇' },
    { path: '/accounts', label: 'Accounts', labelZh: '帳戶', mobile: 'Accounts', mobileZh: '帳戶', icon: '▣' },
    { path: '/import', label: 'Import', labelZh: '匯入', mobile: 'Import', mobileZh: '匯入', icon: '↥' },
    { path: '/installments', label: 'Instalments', labelZh: '分期', mobile: 'Plans', mobileZh: '分期', icon: '◫' },
    { path: '/investments', label: 'Investments', labelZh: '投資', mobile: 'Invest', mobileZh: '投資', icon: '↗' },
    { path: '/review', label: 'Review', labelZh: '審核', mobile: 'Review', mobileZh: '審核', icon: '◇' },
    { path: '/integrity', label: 'Integrity', labelZh: '完整性', mobile: 'Integrity', mobileZh: '完整性', icon: '✓' },
    { path: '/ask', label: 'Ask', labelZh: '查詢', mobile: 'Ask', mobileZh: '查詢', icon: '?' },
    { path: '/profile', label: 'Profile & settings', labelZh: '個人及設定', mobile: 'Settings', mobileZh: '設定', icon: '⚙' },
  ];

  readonly primaryNav = this.nav.slice(0, 4);
  readonly secondaryNav = this.nav.slice(4);
  readonly desktopGroups = [
    { label: 'Overview', labelZh: '概覽', items: this.nav.filter((item) => ['/summary', '/accounts', '/timeline'].includes(item.path)) },
    { label: 'Ledger', labelZh: '帳務', items: this.nav.filter((item) => ['/blotter', '/import', '/installments', '/investments'].includes(item.path)) },
    { label: 'Control', labelZh: '管理', items: this.nav.filter((item) => ['/review', '/integrity', '/ask'].includes(item.path)) },
  ];

  reviewCount = signal(0);
  online = signal(true);
  mobileMenu = signal(false);
  navigating = signal(false);
  pendingPath = signal<string | null>(null);

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

  navigateMobile(event: MouseEvent, path: string): void {
    event.preventDefault();
    event.stopPropagation();
    this.pendingPath.set(path);
    this.mobileMenu.set(false);
    void this.router.navigateByUrl(path);
  }

  isActive(path: string): boolean {
    return this.router.url === path || this.router.url.startsWith(`${path}/`) || this.router.url.startsWith(`${path}?`);
  }
}
