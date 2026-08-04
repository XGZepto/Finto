import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api.service';
import { FilterState } from '../core/filter-state';
import { Facets } from '../core/models';

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
  imports: [FormsModule],
  template: `
    <div class="filter-bar card">
      <div class="controls">
        <div class="field">
          <label for="q">Search</label>
          <input
            id="q"
            type="search"
            placeholder="merchant, description, passenger…"
            [ngModel]="filters.filter().q ?? ''"
            (ngModelChange)="onSearch($event)"
          />
        </div>

        <div class="field narrow">
          <label for="from">From</label>
          <input id="from" type="date" [ngModel]="filters.filter().from ?? ''"
                 (ngModelChange)="patch({ from: $event || undefined })" />
        </div>

        <div class="field narrow">
          <label for="to">To</label>
          <input id="to" type="date" [ngModel]="filters.filter().to ?? ''"
                 (ngModelChange)="patch({ to: $event || undefined })" />
        </div>

        <div class="field">
          <label for="account">Account</label>
          <select id="account" [ngModel]="single('accounts')"
                  (ngModelChange)="patchArray('accounts', $event)">
            <option value="">All accounts</option>
            @for (a of facets()?.accounts ?? []; track a.id) {
              <option [value]="a.id">{{ a.display_name }}</option>
            }
          </select>
        </div>

        <div class="field">
          <label for="category">Category</label>
          <select id="category" [ngModel]="single('categories')"
                  (ngModelChange)="patchArray('categories', $event)">
            <option value="">All categories</option>
            @for (c of facets()?.categories ?? []; track c) {
              <option [value]="c">{{ c }}</option>
            }
          </select>
        </div>

        <div class="field narrow">
          <label for="ccy">Currency</label>
          <select id="ccy" [ngModel]="filters.filter().currency ?? ''"
                  (ngModelChange)="patch({ currency: $event || undefined })">
            <option value="">Any</option>
            @for (c of facets()?.currencies ?? []; track c) {
              <option [value]="c">{{ c }}</option>
            }
          </select>
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
    .controls { display: grid; grid-template-columns: repeat(auto-fit, minmax(148px, 1fr)); gap: 10px; }
    .field.narrow { max-width: 150px; }
    .field input, .field select { width: 100%; }
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
  `],
})
export class FilterBar {
  private api = inject(Api);
  filters = inject(FilterState);
  facets = signal<Facets | null>(null);

  @Output() changed = new EventEmitter<void>();

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.api.facets().subscribe({ next: (f) => this.facets.set(f) });
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
