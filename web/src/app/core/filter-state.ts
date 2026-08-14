import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { LedgerFilter } from './models';

/**
 * Filter state, backed by the URL.
 *
 * Keeping the filter in query params rather than in memory means every view is
 * bookmarkable and shareable, browser back/forward works as users expect, and
 * "different filtering views" needs no separate saved-state machinery — a saved
 * view is just a stored URL.
 */

const ARRAY_KEYS = [
  'months', 'accounts', 'cards', 'cardholders', 'institutions', 'categories', 'kinds',
  'detail', 'tags',
] as const;
const BOOL_KEYS = [
  'includeTransfers', 'includeDuplicates', 'uncategorisedOnly', 'installmentsOnly',
] as const;
const NUM_KEYS = ['minAmount', 'maxAmount'] as const;

type ListKey = 'accounts' | 'cards' | 'cardholders' | 'institutions' | 'categories' | 'kinds' | 'tags';

/** Where each groupable dimension lands in a filter. */
const DIMENSION_KEYS: Record<string, ListKey> = {
  account: 'accounts', institution: 'institutions', category: 'categories',
  card: 'cards', cardholder: 'cardholders', kind: 'kinds', tag: 'tags',
};

/** Dimensions whose bucket is a date range rather than a value. */
export function bucketRange(dimension: string, bucket: string): { from: string; to: string } | null {
  const day = (y: number, m: number) => new Date(y, m, 0).getDate();
  const [a, b] = bucket.split(/-Q|-/).map(Number);
  switch (dimension) {
    case 'day': return { from: bucket, to: bucket };
    case 'month': return { from: `${bucket}-01`, to: `${bucket}-${day(a, b)}` };
    case 'quarter': {
      const first = (b - 1) * 3 + 1;
      return { from: `${a}-${String(first).padStart(2, '0')}-01`,
               to: `${a}-${String(first + 2).padStart(2, '0')}-${day(a, first + 2)}` };
    }
    case 'year': return { from: `${bucket}-01-01`, to: `${bucket}-12-31` };
    default: return null;
  }
}

export function filterFromParams(params: Params): LedgerFilter {
  const f: LedgerFilter = {};
  if (params['from']) f.from = params['from'];
  if (params['to']) f.to = params['to'];
  if (params['q']) f.q = params['q'];
  if (params['currency']) f.currency = params['currency'];

  for (const key of ARRAY_KEYS) {
    const v = params[key];
    if (v) (f as any)[key] = Array.isArray(v) ? v : [v];
  }
  for (const key of BOOL_KEYS) {
    if (params[key] === 'true') (f as any)[key] = true;
  }
  for (const key of NUM_KEYS) {
    if (params[key] != null && params[key] !== '') {
      const n = Number(params[key]);
      if (!Number.isNaN(n)) (f as any)[key] = n;
    }
  }
  return f;
}

export function filterToParams(f: LedgerFilter): Params {
  const p: Params = {};
  const set = (k: string, v: unknown) => {
    if (v === undefined || v === null || v === '' ||
        (Array.isArray(v) && v.length === 0) || v === false) {
      p[k] = null; // null removes the param
    } else {
      p[k] = v;
    }
  };
  set('from', f.from);
  set('to', f.to);
  set('q', f.q);
  set('currency', f.currency);
  for (const key of ARRAY_KEYS) set(key, (f as any)[key]);
  for (const key of BOOL_KEYS) set(key, (f as any)[key]);
  for (const key of NUM_KEYS) set(key, (f as any)[key]);
  return p;
}

/** Human-readable chips describing an active filter. */
export function describeFilter(f: LedgerFilter): Array<{ key: string; label: string }> {
  const chips: Array<{ key: string; label: string }> = [];
  if (f.from || f.to) {
    chips.push({ key: 'date', label: `${f.from ?? 'start'} → ${f.to ?? 'now'}` });
  }
  if (f.months?.length) {
    chips.push({ key: 'months', label: f.months.length === 1 ? f.months[0] : `${f.months.length} months` });
  }
  for (const key of ARRAY_KEYS) {
    if (key === 'months') continue;
    const v = (f as any)[key] as string[] | undefined;
    if (!v?.length) continue;
    chips.push({
      key,
      label: key === 'detail' ? v.join(', ') : `${key}: ${v.join(', ')}`,
    });
  }
  if (f.currency) chips.push({ key: 'currency', label: f.currency });
  if (f.q) chips.push({ key: 'q', label: `“${f.q}”` });
  if (f.minAmount != null) chips.push({ key: 'minAmount', label: `min ${f.minAmount}` });
  if (f.maxAmount != null) chips.push({ key: 'maxAmount', label: `max ${f.maxAmount}` });
  if (f.includeTransfers) chips.push({ key: 'includeTransfers', label: 'incl. transfers' });
  if (f.uncategorisedOnly) chips.push({ key: 'uncategorisedOnly', label: 'uncategorised only' });
  if (f.installmentsOnly) chips.push({ key: 'installmentsOnly', label: 'instalments only' });
  return chips;
}

@Injectable({ providedIn: 'root' })
export class FilterState {
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  readonly filter = signal<LedgerFilter>({});
  readonly chips = computed(() => describeFilter(this.filter()));
  readonly isActive = computed(() => this.chips().length > 0);

  /** Sync from the URL. Call from a route component's constructor. */
  hydrate(params: Params): void {
    this.filter.set(filterFromParams(params));
  }

  /** Merge a partial change and push it to the URL. */
  patch(change: Partial<LedgerFilter>): void {
    const next = { ...this.filter(), ...change };
    this.filter.set(next);
    this.pushToUrl(next);
  }

  set(next: LedgerFilter): void {
    this.filter.set(next);
    this.pushToUrl(next);
  }

  clear(key?: string): void {
    if (!key) {
      this.set({});
      return;
    }
    const next = { ...this.filter() };
    if (key === 'date') {
      delete next.from;
      delete next.to;
    } else {
      delete (next as any)[key];
    }
    this.set(next);
  }

  /**
   * Open the blotter on one value of one dimension — what a summary row click
   * does, from every page that has summary rows.
   *
   * `scope` is the period and account the clicked figure was computed under.
   * Without it the blotter answers a wider question than the one asked, and the
   * total on screen no longer matches the figure that was clicked.
   */
  drillInto(dimension: string, value: string, scope: LedgerFilter = {}): void {
    const range = bucketRange(dimension, value);
    const key = DIMENSION_KEYS[dimension];
    const next: LedgerFilter = { ...scope, ...range };

    if (key) next[key] = [...new Set([...(scope[key] ?? []), value])];
    else if (dimension === 'currency') next.currency = value;
    // No field of their own: merchant and subcategory are searchable text only.
    else if (!range) next.q = value;

    this.router.navigate(['/blotter'], { queryParams: filterToParams(next) });
  }

  private pushToUrl(f: LedgerFilter): void {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: filterToParams(f),
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
