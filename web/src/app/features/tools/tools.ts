import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api.service';
import { NavIcon } from '../../shared/nav-icon';

/**
 * Tools.
 *
 * Maintenance destinations, reached when something needs doing rather than on
 * the way to an answer. Keeping them off the primary navigation leaves it to
 * the views that answer a question about money.
 */
@Component({
  selector: 'app-tools',
  imports: [RouterLink, NavIcon],
  template: `
    <div class="page-head"><h1>More</h1></div>

    @for (group of groups; track group.label) {
      <section class="tool-group">
        <h2>{{ group.label }}</h2>
        <div class="tool-grid">
          @for (tool of group.items; track tool.path) {
            <a class="tool" [routerLink]="tool.path">
              <span class="tool-icon"><finto-nav-icon [name]="tool.icon" /></span>
              <span class="tool-body">
                <span class="tool-name">
                  {{ tool.name }}
                  @if (tool.path === '/review' && reviewCount() > 0) {
                    <span class="badge">{{ reviewCount() }}</span>
                  }
                </span>
                <span class="tool-note">{{ tool.note }}</span>
              </span>
            </a>
          }
        </div>
      </section>
    }
  `,
  styles: [`
    .tool-group { margin-bottom: var(--s5); }
    .tool-group h2 { margin-bottom: var(--s3); }
    .tool-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: var(--s3); }
    .tool {
      display: flex; align-items: flex-start; gap: var(--s3);
      padding: var(--s4);
      border: 1px solid var(--line);
      background: var(--panel);
      text-decoration: none;
      transition: border-color 90ms linear, background 90ms linear;
    }
    .tool:hover { border-color: var(--line-2); background: var(--panel-2); }
    .tool-icon {
      display: grid; place-items: center; flex: none;
      width: 30px; height: 30px;
      border: 1px solid var(--line-2); color: var(--fg-3);
    }
    .tool-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .tool-name { display: flex; align-items: center; gap: var(--s2); color: var(--fg); font-size: 14px; font-weight: 600; }
    .tool-note { color: var(--fg-3); font-size: 12.5px; }
  `],
})
export class ToolsPage {
  private api = inject(Api);
  reviewCount = signal(0);

  readonly groups = [
    {
      label: 'Money',
      items: [
        { path: '/recurring', icon: 'installments', name: 'Recurring', note: 'Subscriptions and instalment plans committed each month' },
        { path: '/ask', icon: 'ask', name: 'Ask', note: 'Question the ledger in plain language' },
      ],
    },
    {
      label: 'Ledger',
      items: [
        { path: '/import', icon: 'import', name: 'Import', note: 'Add a statement and preview it before it commits' },
        { path: '/installments', icon: 'installments', name: 'Instalments', note: 'Plans and what remains on each' },
        { path: '/investments', icon: 'investments', name: 'Investments', note: 'Holdings and valuation snapshots' },
      ],
    },
    {
      label: 'Checks',
      items: [
        { path: '/review', icon: 'review', name: 'Review', note: 'Duplicate, transfer and instalment candidates' },
        { path: '/integrity', icon: 'integrity', name: 'Integrity', note: 'Statement reconciliation and coverage' },
        { path: '/timeline', icon: 'timeline', name: 'Timeline', note: 'Month by month movement' },
      ],
    },
    {
      label: 'Settings',
      items: [
        { path: '/profile', icon: 'settings', name: 'Settings', note: 'Reporting currency, language, API keys, sign out' },
      ],
    },
  ];

  constructor() {
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
}
