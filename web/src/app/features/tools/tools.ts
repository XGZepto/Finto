import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
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
                </span>
                <span class="tool-note">{{ tool.note }}</span>
              </span>
              <span class="tool-arrow" aria-hidden="true">›</span>
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
      display: grid; grid-template-columns: 32px minmax(0, 1fr) 16px; align-items: center; gap: var(--s3);
      min-height: 72px;
      padding: var(--s4);
      border: 1px solid var(--line);
      background: var(--panel);
      text-decoration: none;
      transition: border-color var(--motion-fast) linear, background var(--motion-fast) linear;
    }
    .tool:hover { border-color: var(--line-2); background: var(--panel-2); }
    .tool-icon {
      display: grid; place-items: center; flex: none;
      width: 32px; height: 32px;
      border: 1px solid var(--line-2); color: var(--fg-3);
    }
    .tool-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .tool-name { display: flex; align-items: center; gap: var(--s2); color: var(--fg); font-size: var(--t-body); font-weight: 600; }
    .tool-note { color: var(--fg-3); font-size: var(--t-data); }
    .tool-arrow { color: var(--fg-4); font-size: var(--t-lede); text-align: right; }
    @media (max-width: 880px) {
      .tool-group { margin: 0 0 var(--s5); }
      .tool-group h2 { margin: 0 0 var(--s2); color: var(--fg-4); font-size: var(--t-label); }
      .tool-grid { display: block; border: 1px solid var(--line); background: var(--panel); }
      .tool { min-height: 68px; padding: var(--s3); border: 0; border-bottom: 1px solid var(--line); background: transparent; }
      .tool:last-child { border-bottom: 0; }
      .tool-note { font-size: var(--t-meta); }
    }
  `],
})
export class ToolsPage {
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
      label: 'Ledger health',
      items: [
        { path: '/review', icon: 'review', name: 'Matching suggestions', note: 'Potential duplicates, transfers and instalments' },
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
}
