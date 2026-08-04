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
  'accounts', 'cards', 'institutions', 'categories', 'kinds', 'detail', 'tags',
] as const;
const BOOL_KEYS = [
  'includeTransfers', 'includeDuplicates', 'uncategorisedOnly', 'installmentsOnly',
] as const;
const NUM_KEYS = ['minAmount', 'maxAmount'] as const;

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
  for (const key of ARRAY_KEYS) {
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

  /** Add a dimension value — this is what a summary row click does. */
  drillInto(dimension: string, value: string): void {
    const map: Record<string, keyof LedgerFilter> = {
      account: 'accounts', institution: 'institutions', category: 'categories',
      card: 'cards', kind: 'kinds',
    };
    if (dimension === 'month' || dimension === 'day') {
      const from = dimension === 'month' ? `${value}-01` : value;
      const to = dimension === 'month' ? monthEnd(value) : value;
      this.router.navigate(['/blotter'], {
        queryParams: { ...filterToParams({ ...this.filter(), from, to }) },
      });
      return;
    }
    if (dimension === 'currency') {
      this.router.navigate(['/blotter'], {
        queryParams: filterToParams({ ...this.filter(), currency: value }),
      });
      return;
    }
    const key = map[dimension];
    if (!key) return;
    const existing = ((this.filter() as any)[key] as string[]) ?? [];
    const next = { ...this.filter(), [key]: [...new Set([...existing, value])] };
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

function monthEnd(yyyymm: string): string {
  const [y, m] = yyyymm.split('-').map(Number);
  const last = new Date(y, m, 0).getDate();
  return `${yyyymm}-${String(last).padStart(2, '0')}`;
}
