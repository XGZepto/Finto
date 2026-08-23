import { Component, EventEmitter, HostListener, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api.service';
import { FilterState } from '../core/filter-state';
import { Facets } from '../core/models';
import { FintoSelect } from './finto-select';
import { FintoDate } from './finto-date';

/**
 * Shared filter controls.
 *
 * Writes through FilterState, which keeps everything in the URL — so a filtered
 * view is bookmarkable and the back button works.
 *
 * `includeTransfers` defaults to off.
 */
@Component({
  selector: 'app-filter-bar',
  imports: [FormsModule, FintoSelect, FintoDate],
  template: `
    <div class="filter-bar card" [class.sheet-open]="expanded()">
      <div class="filter-primary">
        <div class="field search">
          <label for="q">Search</label>
          <input
            id="q"
            type="search"
            placeholder="Merchant or description"
            [ngModel]="filters.filter().q ?? ''"
            (ngModelChange)="onSearch($event)"
          />
        </div>

        <button type="button" class="filter-toggle ghost" [class.on]="expanded()"
                (click)="expanded.set(!expanded())" [attr.aria-expanded]="expanded()">
          <span class="chev" aria-hidden="true">›</span>
          Filters @if (filters.chips().length) { <span class="count">{{ filters.chips().length }}</span> }
        </button>
      </div>

      @if (expanded()) {
        <button type="button" class="sheet-scrim" (click)="expanded.set(false)"
                aria-label="Close filters"></button>
      }

      <div class="advanced" data-scroll-surface [class.open]="expanded()">
        <div class="advanced-inner">
        <div class="controls">

        <div class="field narrow">
          <label for="from">From</label>
          <finto-date id="from" [ngModel]="filters.filter().from ?? ''"
                      (ngModelChange)="patch({ from: $event || undefined })" />
        </div>

        <div class="field narrow">
          <label for="to">To</label>
          <finto-date id="to" [ngModel]="filters.filter().to ?? ''"
                      (ngModelChange)="patch({ to: $event || undefined })" />
        </div>

        <div class="field">
          <label for="account">Account</label>
          <finto-select id="account" ariaLabel="Account" placeholder="All accounts"
                        [options]="accountOptions()" valueKey="id" labelKey="display_name"
                        [ngModel]="single('accounts')" (ngModelChange)="patchArray('accounts', $event)" />
        </div>

        <div class="field">
          <label for="category">Category</label>
          <finto-select id="category" ariaLabel="Category" placeholder="All categories"
                        [options]="categoryOptions()" valueKey="value" labelKey="label"
                        [ngModel]="single('categories')"
                        (ngModelChange)="patchArray('categories', $event)" />
        </div>

        <div class="field narrow">
          <label for="ccy">Currency</label>
          <finto-select id="ccy" ariaLabel="Currency" placeholder="Any"
                        [options]="currencyOptions()" valueKey="value" labelKey="label"
                        [ngModel]="filters.filter().currency ?? ''"
                        (ngModelChange)="patch({ currency: $event || undefined })" />
        </div>
        </div>

        <div class="toggles">
        <label class="check">
          <input type="checkbox" [ngModel]="filters.filter().includeTransfers ?? false"
                 (ngModelChange)="patch({ includeTransfers: $event || undefined })" />
          <span>Include transfers</span>
        </label>
        <label class="check">
          <input type="checkbox" [ngModel]="filters.filter().uncategorisedOnly ?? false"
                 (ngModelChange)="patch({ uncategorisedOnly: $event || undefined })" />
          <span>Uncategorised only</span>
        </label>
        <label class="check">
          <input type="checkbox" [ngModel]="filters.filter().installmentsOnly ?? false"
                 (ngModelChange)="patch({ installmentsOnly: $event || undefined })" />
          <span>Instalments only</span>
        </label>
        </div>

        <!-- How the scope total is denominated. A preference set once, not a
             thing you steer with, so it lives with the other set-once controls
             instead of above the ledger. -->
        <ng-content select="[filterExtras]" />
        </div>
      </div>

      @if (filters.isActive()) {
        <div class="chips">
          @for (chip of filters.chips(); track chip.key) {
            <span class="chip accent">
              {{ chip.label }}
              <button type="button" (click)="clear(chip.key)" aria-label="Remove filter">×</button>
            </span>
          }
          <button type="button" class="ghost small clear-all" (click)="clear()">Clear all</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .filter-bar { margin-bottom: var(--s3); padding: var(--s3) 14px; }
    @media (min-width: 881px) {
      .filter-bar {
        position: sticky;
        top: var(--offline-h, 0px);
        z-index: var(--z-sticky);
        background: var(--panel);
      }
    }
    .filter-primary { display: flex; align-items: end; gap: 10px; }
    .filter-primary .search { flex: 1; }
    .filter-primary input { width: 100%; }
    .filter-toggle { display: block; min-width: 92px; }
    .filter-toggle .chev {
      display: inline-block;
      margin-right: 6px;
      color: var(--fg-4);
      transition: transform var(--motion) var(--ease);
    }
    .filter-toggle.on .chev { transform: rotate(90deg); color: var(--fg-2); }
    .filter-toggle .count {
      display: inline-grid; place-items: center; min-width: 16px; height: 16px;
      margin-left: 4px; background: var(--fg); color: var(--bg); font-size: var(--t-micro);
    }
    /* Filters stay folded until asked for; the chips below keep active
       state visible, so nothing is hidden that is currently in effect. */
    /* Height animates from zero without knowing the content height; the inner
       wrapper is what clips while the row track grows. */
    .advanced {
      display: grid;
      grid-template-rows: 0fr;
      opacity: 0;
      transition: grid-template-rows var(--motion) var(--ease),
                  opacity var(--motion-fast) linear, margin-top var(--motion) var(--ease);
    }
    .advanced.open { grid-template-rows: 1fr; opacity: 1; margin-top: var(--s3); overflow: visible; }
    .advanced-inner { overflow: hidden; min-height: 0; }
    .advanced.open .advanced-inner { overflow: visible; }
    .filter-bar:has(finto-select.sheet-open) .advanced,
    .filter-bar:has(finto-date.sheet-open) .advanced { overflow: visible; }
    .controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(148px, 1fr)); gap: 10px; }
    .field.narrow { max-width: 150px; }
    .field input, .field finto-select, .field finto-date { width: 100%; }
    .toggles {
      display: flex; gap: 16px; flex-wrap: wrap;
      margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line);
    }
    .check {
      display: flex; align-items: center; gap: 6px; margin: 0;
      font-family: var(--sans); font-size: var(--t-meta); letter-spacing: 0;
      text-transform: none; color: var(--fg-2); cursor: pointer;
    }
    .check input { width: auto; }
    .check small { font-size: var(--t-label); }
    .chips {
      display: flex; gap: 6px; flex-wrap: wrap; align-items: center;
      margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--line);
    }
    .chips > .chip, .chips > .clear-all {
      height: var(--control-h);
      min-height: var(--control-h);
      padding-block: 0;
    }
    .chips > .chip { padding-inline: var(--s3); }
    .chips .chip button {
      position: relative;
      width: 18px;
      height: 18px;
      min-height: 18px;
      padding: 0;
    }
    .chips .chip button::after { content: ''; position: absolute; inset: -13px; }
    /* Inline on desktop, so nothing to dim. */
    .sheet-scrim { display: none; }
    @media (prefers-reduced-motion: reduce) {
      .advanced, .filter-toggle .chev { transition: none; }
    }
    @media (max-width: 880px) {
      /* Sticky search; the day headers pin below it using the height the
         blotter measures into --filter-h. */
      /* The sheet and its scrim are children of this bar, so they cannot rise
         above the tab bar on their own — a positioned ancestor with a z-index
         traps them in its stacking context, and z-index 40 inside a context at
         25 still lands under a nav at 30. The bar itself has to move. */
      .filter-bar.sheet-open { z-index: var(--z-overlay); }
      .filter-bar {
        position: sticky;
        top: calc(-1 * var(--s3) + var(--offline-h, 0px));
        z-index: var(--z-sticky);
        margin-inline: calc(-1 * var(--s3));
        padding: var(--s3);
        border-left: 0;
        border-right: 0;
        background: var(--glass-strong);
      }
      /* Inline expansion would push the ledger off screen, so on a phone the
         panel arrives as a sheet over it and leaves the rows where they were. */
      .advanced {
        position: fixed;
        left: 0; right: 0; bottom: 0;
        z-index: var(--z-overlay);
        display: block;
        max-height: 78vh;
        overflow-y: auto;
        margin-top: 0;
        padding: var(--s4) var(--s3) calc(var(--s5) + env(safe-area-inset-bottom));
        background: var(--glass-surface);
        -webkit-backdrop-filter: var(--glass-filter);
        backdrop-filter: var(--glass-filter);
        border-top: 1px solid var(--line-2);
        transform: translateY(100%);
        transition: transform var(--motion-slow) var(--ease), opacity var(--motion-fast) linear;
      }
      .advanced.open { transform: translateY(0); margin-top: 0; }
      /* A nested control becomes the active sheet. Dropping the transform
         restores the viewport as its fixed-position containing block, and
         visible overflow keeps the nested header and scrim intact. */
      .advanced.open:has(finto-date.sheet-open),
      .advanced.open:has(finto-select.sheet-open) { transform: none; overflow: visible; }
      /* A sheet over the ledger has to take the taps the ledger would have got,
         and be dismissable by the gesture people already use for a sheet. */
      .sheet-scrim {
        display: block;
        position: fixed;
        inset: 0;
        z-index: var(--z-scrim);
        min-height: 0;
        padding: 0;
        border: 0;
        background: var(--modal-scrim);
        -webkit-backdrop-filter: var(--modal-scrim-filter);
        backdrop-filter: var(--modal-scrim-filter);
      }
      .advanced-inner { overflow: visible; }
      .controls { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .field.narrow { max-width: none; }
      .toggles { display: grid; grid-template-columns: 1fr; gap: 9px; }
      /* A compact chip row keeps active filters visible without repeating the form. */
      .chips {
        display: flex;
        flex-wrap: nowrap;
        overflow-x: auto;
        scrollbar-width: none;
      }
      .chips::-webkit-scrollbar { display: none; }
      .check { min-height: var(--touch-h); }
    }
  `],
})
export class FilterBar {
  private api = inject(Api);
  filters = inject(FilterState);
  facets = signal<Facets | null>(null);
  expanded = signal(false);

  @Output() changed = new EventEmitter<void>();

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  @HostListener('document:keydown.escape')
  collapse(): void { this.expanded.set(false); }

  constructor() {
    this.api.facets().subscribe({ next: (f) => this.facets.set(f) });
  }

  accountOptions() {
    return [{ id: '', display_name: 'All accounts' }, ...(this.facets()?.accounts ?? [])];
  }
  categoryOptions() {
    return [{ value: '', label: 'All categories' },
      ...(this.facets()?.categories ?? []).map((value) => ({ value, label: value }))];
  }
  currencyOptions() {
    return [{ value: '', label: 'Any' },
      ...(this.facets()?.currencies ?? []).map((value) => ({ value, label: value }))];
  }

  single(key: 'accounts' | 'categories'): string {
    const v = this.filters.filter()[key];
    return v?.length === 1 ? v[0] : '';
  }

  patch(change: Record<string, unknown>): void {
    this.filters.patch(change as any);
    this.changed.emit();
  }

  patchArray(key: 'accounts' | 'categories', value: string): void {
    this.patch({ [key]: value ? [value] : undefined });
  }

  /** Debounced so typing doesn't fire a request per keystroke. */
  onSearch(value: string): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.patch({ q: value || undefined }), 250);
  }

  clear(key?: string): void {
    this.filters.clear(key);
    this.changed.emit();
  }
}
