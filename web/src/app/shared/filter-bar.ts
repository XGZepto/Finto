import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
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
    <div class="filter-bar card">
      <div class="filter-primary">
        <div class="field search">
          <label for="q">Search</label>
          <input
            id="q"
            type="search"
            placeholder="merchant, description, passenger…"
            [ngModel]="filters.filter().q ?? ''"
            (ngModelChange)="onSearch($event)"
          />
        </div>

        <button type="button" class="filter-toggle ghost" [class.on]="expanded()"
                (click)="expanded.set(!expanded())" [attr.aria-expanded]="expanded()">
          Filters @if (filters.chips().length) { <span>{{ filters.chips().length }}</span> }
        </button>
      </div>

      <div class="advanced" [class.open]="expanded()">
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
      </div>

      @if (filters.isActive()) {
        <div class="chips">
          @for (chip of filters.chips(); track chip.key) {
            <span class="chip accent">
              {{ chip.label }}
              <button type="button" (click)="clear(chip.key)" aria-label="Remove filter">×</button>
            </span>
          }
          <button type="button" class="ghost small" (click)="clear()">Clear all</button>
        </div>
      }
    </div>
  `,
  styles: [`
    .filter-bar { margin-bottom: 14px; padding: 12px 14px; }
    .filter-primary { display: flex; align-items: end; gap: 10px; }
    .filter-primary .search { flex: 1; }
    .filter-primary input { width: 100%; }
    .filter-toggle { display: none; min-width: 92px; height: 31px; }
    .filter-toggle span {
      display: inline-grid; place-items: center; min-width: 16px; height: 16px;
      margin-left: 4px; background: var(--fg); color: var(--bg); font-size: 9px;
    }
    .advanced { margin-top: 10px; }
    .controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(148px, 1fr)); gap: 10px; }
    .field.narrow { max-width: 150px; }
    .field input, .field finto-select, .field finto-date { width: 100%; }
    .toggles {
      display: flex; gap: 16px; flex-wrap: wrap;
      margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line);
    }
    .check {
      display: flex; align-items: center; gap: 6px; margin: 0;
      font-family: var(--sans); font-size: 11.5px; letter-spacing: 0;
      text-transform: none; color: var(--fg-2); cursor: pointer;
    }
    .check input { width: auto; }
    .check small { font-size: 10.5px; }
    .chips {
      display: flex; gap: 6px; flex-wrap: wrap; align-items: center;
      margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--line);
    }
    @media (max-width: 700px) {
      .filter-bar { padding: 10px; }
      .filter-toggle { display: block; }
      .advanced { display: none; }
      .advanced.open { display: block; }
      .controls { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .field.narrow { max-width: none; }
      .toggles { display: grid; grid-template-columns: 1fr; gap: 9px; }
      .chips { margin-top: 8px; padding-top: 8px; }
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
