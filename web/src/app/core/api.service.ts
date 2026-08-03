import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Account, Card, Facets, InstallmentPlan, IntegrityReport, Job, LedgerFilter,
  Page, Position, QueryResult, StagePreview, SummaryRow, TotalRow, Txn,
} from './models';

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
  put('accounts', f.accounts);
  put('cards', f.cards);
  put('institutions', f.institutions);
  put('categories', f.categories);
  put('kinds', f.kinds);
  put('currency', f.currency);
  put('minAmount', f.minAmount);
  put('maxAmount', f.maxAmount);
  put('q', f.q);
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

  transactions(
    f: LedgerFilter,
    opts: { limit?: number; offset?: number; sort?: string; direction?: string } = {},
  ): Observable<Page<Txn>> {
    let p = filterToParams(f);
    if (opts.limit != null) p = p.set('limit', String(opts.limit));
    if (opts.offset != null) p = p.set('offset', String(opts.offset));
    if (opts.sort) p = p.set('sort', opts.sort);
    if (opts.direction) p = p.set('direction', opts.direction);
    return this.http.get<Page<Txn>>(`${this.base}/transactions`, { params: p });
  }

  transaction(id: string): Observable<Txn> {
    return this.http.get<Txn>(`${this.base}/transactions/${id}`);
  }

  patchTransaction(id: string, patch: Partial<Txn>): Observable<Txn> {
    return this.http.patch<Txn>(`${this.base}/transactions/${id}`, patch);
  }

  summary(
    groupBy: string,
    f: LedgerFilter,
    convertTo?: string,
  ): Observable<{ group_by: string; rows: SummaryRow[]; totals: TotalRow[]; conversion?: any }> {
    let p = filterToParams(f).set('group_by', groupBy);
    if (convertTo) p = p.set('convert_to', convertTo);
    return this.http.get<any>(`${this.base}/summary`, { params: p });
  }

  positions(convertTo?: string, asOf?: string): Observable<{
    positions: Position[];
    declared_currencies: Record<string, string[]>;
    conversion?: any;
  }> {
    let p = new HttpParams();
    if (convertTo) p = p.set('convert_to', convertTo);
    if (asOf) p = p.set('as_of', asOf);
    return this.http.get<any>(`${this.base}/positions`, { params: p });
  }

  stats(): Observable<any> {
    return this.http.get(`${this.base}/stats`);
  }

  facets(): Observable<Facets> {
    return this.http.get<Facets>(`${this.base}/facets`);
  }

  accounts(): Observable<{ accounts: Account[] }> {
    return this.http.get<{ accounts: Account[] }>(`${this.base}/accounts`);
  }

  cards(): Observable<{ cards: Card[] }> {
    return this.http.get<{ cards: Card[] }>(`${this.base}/cards`);
  }

  // --- Import -------------------------------------------------------------

  stage(file: File, meta: { institution_id?: string; account_id?: string; currency?: string }):
    Observable<StagePreview> {
    const form = new FormData();
    form.append('file', file, file.name);
    if (meta.institution_id) form.append('institution_id', meta.institution_id);
    if (meta.account_id) form.append('account_id', meta.account_id);
    if (meta.currency) form.append('currency', meta.currency);
    return this.http.post<StagePreview>(`${this.base}/imports/stage`, form);
  }

  confirmImport(stagedId: string, meta: { institution_id?: string; account_id?: string; currency?: string }):
    Observable<Job> {
    const form = new FormData();
    if (meta.institution_id) form.append('institution_id', meta.institution_id);
    if (meta.account_id) form.append('account_id', meta.account_id);
    if (meta.currency) form.append('currency', meta.currency);
    return this.http.post<Job>(`${this.base}/imports/${stagedId}/confirm`, form);
  }

  discardStaged(stagedId: string): Observable<any> {
    return this.http.delete(`${this.base}/imports/${stagedId}`);
  }

  importHistory(): Observable<{ files: any[] }> {
    return this.http.get<{ files: any[] }>(`${this.base}/imports/history`);
  }

  reconcile(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/reconcile`, {});
  }

  reattribute(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/reattribute`, {});
  }

  harvestFx(): Observable<Job> {
    return this.http.post<Job>(`${this.base}/fx/harvest`, {});
  }

  job(id: string): Observable<Job> {
    return this.http.get<Job>(`${this.base}/jobs/${id}`);
  }

  // --- Review -------------------------------------------------------------

  reviewQueue(queue: 'duplicates' | 'transfers' | 'installments'):
    Observable<{ items: any[]; total: number }> {
    return this.http.get<any>(`${this.base}/review/${queue}`);
  }

  resolve(queue: string, id: string, action: 'accept' | 'reject'): Observable<any> {
    return this.http.post(`${this.base}/review/${queue}/${id}`, { action });
  }

  // --- Other --------------------------------------------------------------

  integrity(): Observable<IntegrityReport> {
    return this.http.get<IntegrityReport>(`${this.base}/integrity`);
  }

  installments(activeOnly = false): Observable<{
    plans: InstallmentPlan[];
    outstanding_by_currency: Array<{ currency: string; amount: number }>;
    committed_monthly_by_currency: Array<{ currency: string; amount: number }>;
  }> {
    const p = new HttpParams().set('active_only', String(activeOnly));
    return this.http.get<any>(`${this.base}/installments`, { params: p });
  }

  installment(id: string): Observable<InstallmentPlan> {
    return this.http.get<InstallmentPlan>(`${this.base}/installments/${id}`);
  }

  ask(question: string, convertTo?: string): Observable<QueryResult> {
    return this.http.post<QueryResult>(`${this.base}/query`, {
      question, convert_to: convertTo,
    });
  }
}
