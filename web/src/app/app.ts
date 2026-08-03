import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './core/api.service';

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
          <div class="name">Finto<span class="caret">▌</span></div>
          <span class="tagline">local ledger</span>
        </div>

        <div class="nav">
          @for (item of nav; track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active">
              <span class="idx">{{ item.idx }}</span>
              <span class="label">{{ item.label }}</span>
              @if (item.path === '/review' && reviewCount() > 0) {
                <span class="badge">{{ reviewCount() }}</span>
              }
              @if (item.path === '/integrity' && !healthy()) {
                <span class="badge alert">!</span>
              }
            </a>
          }
        </div>

        <div class="spacer"></div>
        <div class="status">
          <span class="dot" [class.ok]="online()" [class.bad]="!online()"></span>
          <span>{{ online() ? 'local · offline-only' : 'api unreachable' }}</span>
        </div>
      </nav>

      <main class="content">
        <router-outlet />
      </main>
    </div>
  `,
  styleUrl: './app.css',
})
export class App {
  private api = inject(Api);

  readonly nav = [
    { path: '/summary', label: 'Summary', idx: '01' },
    { path: '/blotter', label: 'Blotter', idx: '02' },
    { path: '/import', label: 'Import', idx: '03' },
    { path: '/installments', label: 'Instalments', idx: '04' },
    { path: '/review', label: 'Review', idx: '05' },
    { path: '/integrity', label: 'Integrity', idx: '06' },
    { path: '/ask', label: 'Ask', idx: '07' },
  ];

  reviewCount = signal(0);
  healthy = signal(true);
  online = signal(true);

  constructor() {
    this.api.stats().subscribe({
      next: (s) =>
        this.reviewCount.set(
          (s.open_duplicate_candidates ?? 0) +
            (s.open_transfer_candidates ?? 0) +
            (s.open_installment_candidates ?? 0),
        ),
      error: () => this.online.set(false),
    });
    this.api.integrity().subscribe({
      next: (r) => this.healthy.set(r.healthy),
      error: () => this.online.set(false),
    });
  }
}
