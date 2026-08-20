import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, tap } from 'rxjs';
import {
  Account, Card, CategorySuggestion, Composition, Coverage, DetailKey, DetailValue, Facets, Flows,
  ImportCapabilities, InstallmentPlan, IntegrityReport, InvestmentActivity, InvestmentDetail,
  InvestmentHistory, InvestmentSnapshot, Job, MpfBundlePreview,
  LedgerFilter, Money, Page, Position, QueryResult, StagePreview, StatementFreshness, SummaryRow,
  TotalRow, Txn,
} from './models';
import { ReadCache } from './read-cache';

/** Serialise a LedgerFilter into query params, omitting anything unset. */
export function filterToParams(f: LedgerFilter): HttpParams {
  let p = new HttpParams();
  const put = (k: string, v: unknown) => {
    if (v === undefined || v === null || v === '') return;
    if (Array.isArray(v)) {
      v.forEach((item) => (p = p.append(k, String(item))));
    } else {
      p = p.set(k, String(v));
    }
  };
  put('from', f.from);
  put('to', f.to);
  put('months', f.months);
  put('accounts', f.accounts);
  put('cards', f.cards);
  put('cardholders', f.cardholders);
  put('institutions', f.institutions);
  put('categories', f.categories);
  put('kinds', f.kinds);
  put('currency', f.currency);
  put('minAmount', f.minAmount);
  put('maxAmount', f.maxAmount);
  put('q', f.q);
  put('detail', f.detail);
  put('tags', f.tags);
  if (f.includeTransfers) put('includeTransfers', true);
  if (f.includeDuplicates) put('includeDuplicates', true);
  if (f.uncategorisedOnly) put('uncategorisedOnly', true);
  if (f.installmentsOnly) put('installmentsOnly', true);
  return p;
}

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);
  private base = '/api';
  private reads = new ReadCache();
  private cacheVersion = Number(localStorage.getItem('finto.cacheVersion') || 0);

  private readonly activityTtl = 5 * 60_000;
  private readonly computedTtl = 30 * 60_000;
  private readonly referenceTtl = 60 * 60_000;

  /** Reuse identical route reads inside a warm app session. Expired entries are
   * emitted once while a fresh request runs, preventing route revisits from
   * collapsing into a loading state. Mutations and user changes clear the map. */
  private cached<T>(url: string, ttlMs = 30_000): Observable<T> {
    const separator = url.includes('?') ? '&' : '?';
    return this.reads.get(url, ttlMs, () =>
      this.http.get<T>(`${url}${separator}_cv=${this.cacheVersion}`));
  }

  /** Drop every cached read. Mutations call this; so does a manual refresh. */
  invalidateReads(): void {
    this.reads.clear();
    this.cacheVersion += 1;
    localStorage.setItem('finto.cacheVersion', String(this.cacheVersion));
  }

  login(identifier: string, password: string): Observable<{ ok: boolean; user: AuthUser }> {
    return this.http.post<{ ok: boolean; user: AuthUser }>(
      `${this.base}/auth/login`, { identifier, password }).pipe(
        tap(() => this.invalidateReads()),
      );
  }

  me(): Observable<AuthUser> { return this.http.get<AuthUser>(`${this.base}/auth/me`); }

  updatePreferences(preferences: Partial<UserPreferences>): Observable<AuthUser> {
    return this.http.patch<AuthUser>(`${this.base}/auth/preferences`, preferences);
  }

  apiKeys(): Observable<{ keys: ApiKeyMeta[] }> {
    return this.http.get<{ keys: ApiKeyMeta[] }>(`${this.base}/auth/api-keys`);
  }

  createApiKey(name = 'Agent access'): Observable<ApiKeyMeta & { key: string }> {
    return this.http.post<ApiKeyMeta & { key: string }>(`${this.base}/auth/api-keys`, { name });
  }

  revokeApiKey(id: string): Observable<{ ok: boolean }> {
    return this.http.delete<{ ok: boolean }>(`${this.base}/auth/api-keys/${id}`);
  }

  logout(): Observable<{ ok: boolean }> {
    this.invalidateReads();
    return this.http.post<{ ok: boolean }>(`${this.base}/auth/logout`, {});
  }

  transactions(
    f: LedgerFilter,
    opts: { limit?: number; offset?: number; sort?: string; direction?: string; convertTo?: string } = {},
  ): Observable<Page<Txn>> {
    let p = filterToParams(f);
    if (opts.limit != null) p = p.set('limit', String(opts.limit));
    if (opts.offset != null) p = p.set('offset', String(opts.offset));
    if (opts.sort) p = p.set('sort', opts.sort);
    if (opts.direction) p = p.set('direction', opts.direction);
    if (opts.convertTo) p = p.set('convert_to', opts.convertTo);
    const url = `${this.base}/transactions?${p.toString()}`;
    // Appended pages must not replay a stale cached emit — concat(old, fresh)
    // would duplicate rows and leave the sentinel looking stuck.
    if (opts.offset) return this.http.get<Page<Txn>>(url);
    return this.cached<Page<Txn>>(url, this.activityTtl);
  }

  transaction(id: string): Observable<Txn> {
    return this.cached<Txn>(`${this.base}/transactions/${id}`, this.computedTtl);
  }

  categorySuggestion(id: string): Observable<{
    available: boolean;
    suggestion: CategorySuggestion | null;
  }> {
    return this.http.get<{ available: boolean; suggestion: CategorySuggestion | null }>(
      `${this.base}/transactions/${id}/category-suggestion`,
    );
  }

addTag(id: string, tag: string): Observable<Txn> {
    this.invalidateReads();
    return this.http.post<Txn>(`${this.base}/transactions/${id}/tags`, { tag });
  }

  removeTag(id: string, tag: string): Observable<Txn> {
    this.invalidateReads();
    return this.http.delete<Txn>(
      `${this.base}/transactions/${id}/tags/${encodeURIComponent(tag)}`);
  }

  patchTransaction(id: string, patch: Partial<Txn>): Observable<Txn> {
    this.invalidateReads();
    return this.http.patch<Txn>(`${this.base}/transactions/${id}`, patch);
  }

  summary(
    groupBy: string,
    f: LedgerFilter,
    convertTo?: string,
  ): Observable<{
    group_by: string; rows: SummaryRow[]; totals: TotalRow[]; conversion?: any;
    normalised?: { total: { net: Money; spend: Money; income: Money } };
  }> {
    let p = filterToParams(f).set('group_by', groupBy);
    if (convertTo) p = p.set('convert_to', convertTo);
    return this.cached<any>(`${this.base}/summary?${p.toString()}`, this.computedTtl);
  }

  positions(convertTo?: string, asOf?: string): Observable<{
    positions: Position[];
    declared_currencies: Record<string, string[]>;
    conversion?: { to: string; unconvertible_currencies: string[] };
    normalised?: {
      to: string;
      net_worth: Money;
      by_type: Array<{ account_type: string; balance: Money }>;
      unconvertible_currencies: string[];
    };
  }> {
    let p = new HttpParams();
    if (convertTo) p = p.set('convert_to', convertTo);
    if (asOf) p = p.set('as_of', asOf);
    return this.cached<any>(
      `${this.base}/positions${p.keys().length ? `?${p.toString()}` : ''}`, this.computedTtl);
  }

  netWorthSeries(convertTo: string, months = 12): Observable<{
    to: string;
    points: Array<{ bucket: string; as_of: string; balance: Money }>;
  }> {
    const p = new HttpParams().set('convert_to', convertTo).set('months', months);
    return this.cached<any>(`${this.base}/networth-series?${p.toString()}`, this.computedTtl);
  }

  stats(): Observable<any> {
    return this.cached(`${this.base}/stats`, this.activityTtl);
  }

  facets(): Observable<Facets> {
    return this.cached<Facets>(`${this.base}/facets`, this.referenceTtl);
  }

  accounts(): Observable<{ accounts: Account[] }> {
    return this.cached<{ accounts: Account[] }>(`${this.base}/accounts`, this.referenceTtl);
  }

  cards(): Observable<{ cards: Card[] }> {
    return this.cached<{ cards: Card[] }>(`${this.base}/cards`, this.referenceTtl);
  }

  statementFreshness(): Observable<StatementFreshness> {
    return this.cached<StatementFreshness>(
      `${this.base}/statement-freshness`, this.computedTtl);
  }

  // --- Import -------------------------------------------------------------

  stage(file: File, meta: { institution_id?: string; account_id?: string; currency?: string }):
    Observable<StagePreview> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (meta.institution_id) form.append('institution_id', meta.institution_id);
    if (meta.account_id) form.append('account_id', meta.account_id);
    if (meta.currency) form.append('currency', meta.currency);
    return this.http.post<StagePreview>(`${this.base}/imports/preview`, form);
  }

  confirmImport(file: File, expectedSha256: string,
    meta: { institution_id?: string; account_id?: string; currency?: string }): Observable<any> {
    this.invalidateReads();
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('expected_sha256', expectedSha256);
    if (meta.institution_id) form.append('institution_id', meta.institution_id);
    if (meta.account_id) form.append('account_id', meta.account_id);
    if (meta.currency) form.append('currency', meta.currency);
    return this.http.post<any>(`${this.base}/imports/confirm`, form);
  }

  importHistory(): Observable<{ files: any[] }> {
    return this.cached<{ files: any[] }>(`${this.base}/imports/history`, this.activityTtl);
  }

  importCapabilities(): Observable<ImportCapabilities> {
    return this.cached<ImportCapabilities>(`${this.base}/imports/capabilities`, this.referenceTtl);
  }

  reconcile(): Observable<any> {
    this.invalidateReads();
    return this.http.post<any>(`${this.base}/reconcile`, {});
  }

  reattribute(): Observable<any> {
    this.invalidateReads();
    return this.http.post<any>(`${this.base}/reattribute`, {});
  }

  harvestFx(): Observable<any> {
    this.invalidateReads();
    return this.http.post<any>(`${this.base}/fx/harvest`, {});
  }

  job(id: string): Observable<Job> {
    return this.http.get<Job>(`${this.base}/jobs/${id}`).pipe(
      tap((job) => {
        if (job.status === 'done') this.invalidateReads();
      }),
    );
  }

  // --- Review -------------------------------------------------------------

  reviewQueue(queue: 'duplicates' | 'transfers' | 'installments'):
    Observable<{ items: any[]; total: number }> {
    return this.cached<any>(`${this.base}/review/${queue}`, this.activityTtl);
  }

  resolve(queue: string, id: string, action: 'accept' | 'reject'): Observable<any> {
    this.invalidateReads();
    return this.http.post(`${this.base}/review/${queue}/${id}`, { action });
  }

  // --- Other --------------------------------------------------------------

  investments(): Observable<{ snapshots: InvestmentSnapshot[] }> {
    return this.cached<{ snapshots: InvestmentSnapshot[] }>(
      `${this.base}/investments`, this.computedTtl);
  }

  investmentActivities(accountId?: string): Observable<{ activities: InvestmentActivity[] }> {
    const suffix = accountId ? `?account_id=${encodeURIComponent(accountId)}` : '';
    return this.cached<{ activities: InvestmentActivity[] }>(
      `${this.base}/investments/activities${suffix}`, this.activityTtl);
  }

  previewMpfBundle(files: File[]): Observable<MpfBundlePreview> {
    const form = new FormData();
    files.forEach((file) => form.append('files', file, file.name));
    return this.http.post<MpfBundlePreview>(
      `${this.base}/investments/imports/preview`, form);
  }

  confirmMpfBundle(files: File[], expectedBundleSha256: string): Observable<any> {
    this.invalidateReads();
    const form = new FormData();
    files.forEach((file) => form.append('files', file, file.name));
    form.append('expected_bundle_sha256', expectedBundleSha256);
    return this.http.post<any>(`${this.base}/investments/imports/confirm`, form);
  }

  investment(id: string): Observable<InvestmentDetail> {
    return this.cached<InvestmentDetail>(
      `${this.base}/investments/${id}`, this.computedTtl);
  }

  investmentHistory(scheme?: string, accountId?: string): Observable<InvestmentHistory> {
    let p = new HttpParams();
    if (scheme) p = p.set('scheme', scheme);
    if (accountId) p = p.set('account_id', accountId);
    return this.cached<InvestmentHistory>(
      `${this.base}/investments/history?${p.toString()}`, this.computedTtl);
  }

  detailKeys(): Observable<{ keys: DetailKey[] }> {
    return this.cached<{ keys: DetailKey[] }>(`${this.base}/details`, this.referenceTtl);
  }

  detailValues(key: string): Observable<{ key: string; values: DetailValue[] }> {
    return this.cached<{ key: string; values: DetailValue[] }>(
      `${this.base}/details/${encodeURIComponent(key)}`, this.computedTtl);
  }

  composition(convertTo: string, dimension: string, f: LedgerFilter = {}): Observable<Composition> {
    const p = filterToParams(f).set('convert_to', convertTo).set('dimension', dimension);
    return this.cached<Composition>(
      `${this.base}/composition?${p.toString()}`, this.computedTtl);
  }

  coverage(): Observable<Coverage> {
    return this.cached<Coverage>(`${this.base}/coverage`, this.computedTtl);
  }

  flows(f: LedgerFilter = {}, convertTo = 'USD'): Observable<Flows> {
    const query = filterToParams(f).set('convert_to', convertTo).toString();
    return this.cached<Flows>(
      `${this.base}/flows${query ? `?${query}` : ''}`, this.computedTtl);
  }

  integrity(): Observable<IntegrityReport> {
    return this.cached<IntegrityReport>(`${this.base}/integrity`, this.computedTtl);
  }

  installments(activeOnly = false): Observable<{
    plans: InstallmentPlan[];
    outstanding_by_currency: Array<{ currency: string; amount: number }>;
    committed_monthly_by_currency: Array<{ currency: string; amount: number }>;
  }> {
    const p = new HttpParams().set('active_only', String(activeOnly));
    return this.cached<any>(
      `${this.base}/installments?${p.toString()}`, this.computedTtl);
  }

  installment(id: string): Observable<InstallmentPlan> {
    return this.cached<InstallmentPlan>(
      `${this.base}/installments/${id}`, this.computedTtl);
  }

  ask(question: string, convertTo?: string): Observable<QueryResult> {
    return this.http.post<QueryResult>(`${this.base}/query`, {
      question, convert_to: convertTo,
    });
  }
}

export interface UserPreferences {
  theme?: 'system' | 'dark' | 'light';
  language?: 'en' | 'zh-Hant';
  base_currency?: string;
}
export interface AuthUser {
  id: string;
  username: string;
  email: string;
  preferences: UserPreferences;
}
export interface ApiKeyMeta {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  created_at: string;
  last_used_at: string | null;
}
