import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { Refresh } from '../../core/refresh.service';
import { MoneyPipe } from '../../core/money.pipe';
import { Money, StatementFreshness, SummaryRow, TotalRow } from '../../core/models';
import { FintoSelect } from '../../shared/finto-select';
import { FintoPills } from '../../shared/finto-pills';
import { FintoDonut, Slice } from '../../shared/finto-viz';
import { Preferences } from '../../core/preferences.service';
import { FilterState } from '../../core/filter-state';
import { forkJoin } from 'rxjs';
import { PageStatus } from '../../core/page-status';
import { RevealOnView } from '../../shared/reveal-on-view';

/**
 * Summary.
 *
 * One `group_by` control drives every aggregation level, so month / category /
 * merchant / account / card is one screen rather than five. Clicking any row
 * pushes that dimension onto the blotter filter — which is why both views share
 * one LedgerFilter type.
 *
 * Positions are shown per (account, currency) and never summed across
 * currencies. The optional "show in" conversion adds a companion figure and
 * labels it; the native balance stays authoritative.
 */
@Component({
  selector: 'app-summary',
  imports: [FormsModule, MoneyPipe, FintoSelect, FintoDonut, FintoPills, RevealOnView],
  templateUrl: './summary.html',
  styleUrl: './summary.css',
})
export class SummaryPage {
  private api = inject(Api);
  private refreshes = inject(Refresh);
  private router = inject(Router);
  private filters = inject(FilterState);
  private preferences = inject(Preferences);
  readonly money = new MoneyPipe();

  /**
   * Entity dimensions only.
   *
   * Time is the period control's decision, so offering "month" here alongside
   * "merchant" would present two unrelated choices as one.
   */
  readonly dimensions = [
    'category', 'subcategory', 'tag', 'merchant',
    'account', 'institution', 'card', 'cardholder', 'kind', 'currency',
  ];

  groupBy = signal(this.savedGroupBy());
  convertTo = this.preferences.baseCurrency;
  status = signal<PageStatus>('loading');
  private loadId = 0;
  rows = signal<SummaryRow[]>([]);
  monthRows = signal<SummaryRow[]>([]);
  totals = signal<TotalRow[]>([]);
  monthToDate = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  previousMonthToDate = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  freshness = signal<StatementFreshness | null>(null);
  readonly reportingCurrencies = ['USD', 'HKD', 'GBP', 'EUR', 'JPY', 'CNY', 'SGD', 'AUD', 'CAD'];

  /**
   * The same buckets collapsed into one currency and ranked by spend.
   *
   * Spending across five currencies cannot be ordered natively — HKD 1,000 and
   * USD 1,000 are not comparable — so "what did I spend most on" is only
   * answerable once everything is in one unit. Available whenever a conversion
   * currency is picked; the native rows stay underneath.
   */
  ranked = computed(() => {
    if (!this.convertTo()) return [];
    const map = new Map<string, { spend: number; income: number; rows: number }>();
    for (const r of this.rows()) {
      const spend = r.spend_converted?.ok ? r.spend_converted.amount : null;
      const income = r.income_converted?.ok ? r.income_converted.amount : null;
      if (spend === null) continue;   // unconvertible: excluded, and said so
      const acc = map.get(r.bucket) ?? { spend: 0, income: 0, rows: 0 };
      acc.spend += Math.abs(spend);
      acc.income += Math.abs(income ?? 0);
      acc.rows += r.txn_count;
      map.set(r.bucket, acc);
    }
    const total = [...map.values()].reduce((s, b) => s + b.spend, 0) || 1;
    const peak = Math.max(1, ...[...map.values()].map((b) => b.spend));
    return [...map.entries()]
      .map(([bucket, b]) => ({
        bucket,
        spend: { amount: -b.spend, currency: this.convertTo() },
        income: { amount: b.income, currency: this.convertTo() },
        net: { amount: b.income - b.spend, currency: this.convertTo() },
        rows: b.rows,
        share: b.spend / total,
        width: `${(b.spend / peak) * 100}%`,
      }))
      .sort((a, b) => Math.abs(b.spend.amount) - Math.abs(a.spend.amount));
  });
  rankedTop = computed(() => this.ranked().slice(0, 10));

  /** Rows a missing rate kept out of the ranking. */
  unranked = computed(() =>
    this.convertTo()
      ? [...new Set(this.rows().filter((r) => !r.spend_converted?.ok).map((r) => r.currency))]
      : [],
  );

  rankedTotal = computed(() => ({
    amount: -this.ranked().reduce((s, b) => s + Math.abs(b.spend.amount), 0),
    currency: this.convertTo(),
  }));

  /** How far back the cash-flow chart reaches, in months per pill. */
  readonly rangeOptions = ['3M', '6M', '1Y', '2Y'];
  readonly rangeMonths: Record<string, number> = { '3M': 3, '6M': 6, '1Y': 12, '2Y': 24 };
  range = signal(sessionStorage.getItem('finto.summary.range') ?? '1Y');
  seriesMonths = computed(() => this.rangeMonths[this.range()] ?? 12);

  setRange(value: string): void {
    this.range.set(value);
    sessionStorage.setItem('finto.summary.range', value);
    this.load();
  }

  /** Start of the window the pills select, as the ledger filter both use. */
  private periodFilter(): { from: string } {
    const start = new Date();
    start.setMonth(start.getMonth() - this.seriesMonths() + 1);
    start.setDate(1);
    const pad = (value: number) => String(value).padStart(2, '0');
    return { from: `${start.getFullYear()}-${pad(start.getMonth() + 1)}-01` };
  }

  /** Where the money went, on whichever dimension is selected. */
  spendSlices = computed<Slice[]>(() =>
    this.ranked().map((row) => ({ label: this.humanize(row.bucket), value: Math.abs(row.spend.amount) })));

  /** Kept out of the ledger as a stored figure: it is a ratio of two others. */
  savingsRate = computed(() => {
    const h = this.monthToDate();
    if (!h || h.income.amount <= 0) return null;
    return ((h.income.amount - Math.abs(h.spend.amount)) / h.income.amount) * 100;
  });

  spendComparison = computed(() => {
    const current = this.monthToDate();
    const previous = this.previousMonthToDate();
    if (!current) return null;
    const spent = Math.abs(current.spend.amount);
    const before = Math.abs(previous?.spend.amount ?? 0);
    return {
      spend: { amount: spent, currency: current.spend.currency },
      percent: before ? ((spent - before) / before) * 100 : null,
    };
  });


  /** Currency the trend is drawn in — a chart mixing HKD and USD bars is a lie. */
  chartCurrency = signal('');
  chartCurrencies = computed(() => [...new Set(this.monthRows().map((r) => r.currency))]);

  /** Falls back when a filter change removes the currency that was selected. */
  shownCurrency = computed(() => {
    const available = this.chartCurrencies();
    const chosen = this.chartCurrency();
    return chosen && available.includes(chosen) ? chosen : available[0];
  });

  /**
   * Out and in per period, on a shared scale so the two are comparable and a
   * month that earned more than it spent is visible as such.
   */
  trend = computed(() => {
    if (this.convertTo()) {
      const grouped = new Map<string, SummaryRow>();
      for (const row of this.monthRows()) {
        if (!row.spend_converted?.ok || !row.income_converted?.ok || !row.net_converted?.ok) continue;
        const current = grouped.get(row.bucket) ?? {
          bucket: row.bucket, currency: this.convertTo(), txn_count: 0,
          spend: {amount: 0, currency: this.convertTo()},
          income: {amount: 0, currency: this.convertTo()},
          net: {amount: 0, currency: this.convertTo()},
        };
        current.txn_count += row.txn_count;
        current.spend.amount += row.spend_converted.amount;
        current.income.amount += row.income_converted.amount;
        current.net.amount += row.net_converted.amount;
        grouped.set(row.bucket, current);
      }
      const normalised = [...grouped.values()].sort((a, b) => a.bucket.localeCompare(b.bucket));
      const peak = Math.max(1, ...normalised.map((r) => Math.max(Math.abs(r.spend.amount), r.income.amount)));
      return normalised.map((row) => ({ bucket: row.bucket, row,
        label: row.bucket.replace(/^\d{2}(\d{2})-/, '$1-'),
        out: `${Math.max(1, Math.abs(row.spend.amount) / peak * 100)}%`,
        in: `${Math.max(1, row.income.amount / peak * 100)}%` }));
    }
    const ccy = this.shownCurrency();
    const rows = this.monthRows()
      .filter((r) => r.currency === ccy)
      .sort((a, b) => a.bucket.localeCompare(b.bucket));
    const peak = Math.max(
      1, ...rows.map((r) => Math.max(Math.abs(r.spend.amount), r.income.amount)));
    return rows.map((r) => ({
      bucket: r.bucket,
      row: r,
      label: r.bucket.replace(/^\d{2}(\d{2})-/, "$1-"),
      out: `${Math.max(1, (Math.abs(r.spend.amount) / peak) * 100)}%`,
      in: `${Math.max(1, (r.income.amount / peak) * 100)}%`,
    }));
  });

  /** The scale the bars are drawn against. */
  trendPeak = computed(() => {
    return {
      amount: Math.max(0, ...this.trend().map(
        (item) => Math.max(Math.abs(item.row.spend.amount), item.row.income.amount))),
      currency: this.convertTo() || this.shownCurrency(),
    };
  });

  /** Smooth, scale-honest chart geometry in a 100×100 view box. */
  trendChart = computed(() => {
    const items = this.trend();
    const peak = Math.max(1, this.trendPeak().amount);
    const point = (value: number, index: number) => {
      const x = items.length === 1 ? 50 : (index / (items.length - 1)) * 100;
      const y = 92 - (Math.abs(value) / peak) * 78;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    };
    const out = items.map((item, i) => point(item.row.spend.amount, i)).join(' ');
    const inn = items.map((item, i) => point(item.row.income.amount, i)).join(' ');
    return {
      out,
      inn,
      outArea: items.length ? `0,94 ${out} 100,94` : '',
      labels: items.map((item, i) => ({
        ...item,
        show: items.length <= 8 || i === 0 || i === items.length - 1 ||
          (i % Math.ceil(items.length / 6) === 0 && i < items.length - Math.ceil(items.length / 6)),
      })),
    };
  });

  constructor() {
    this.api.statementFreshness().subscribe({ next: (r) => this.freshness.set(r) });
    effect(() => {
      this.convertTo();
      this.refreshes.token();
      this.load();
    });
  }

  load(): void {
    const id = ++this.loadId;
    this.status.set('loading');
    const convert = this.convertTo() || undefined;

    const period = this.periodFilter();
    this.api.summary(this.groupBy(), period, convert).subscribe({
      next: (res) => {
        if (id !== this.loadId) return;
        this.rows.set(res.rows);
        this.totals.set(res.totals);
        this.status.set('ok');
      },
      error: () => { if (id === this.loadId) this.status.set('failed'); },
    });

    // The trend is always per month: the dimension control picks what the
    // breakdown splits by, never how time is bucketed.
    this.api.summary('month', period, convert).subscribe({
      next: (res) => { if (id === this.loadId) this.monthRows.set(res.rows); },
      error: () => { if (id === this.loadId) this.monthRows.set([]); },
    });

    const comparison = this.comparisonFilters();
    forkJoin({
      current: this.api.summary('month', comparison.current, convert),
      previous: this.api.summary('month', comparison.previous, convert),
    }).subscribe({
      next: ({ current, previous }) => {
        if (id !== this.loadId) return;
        this.monthToDate.set(current.normalised?.total ?? null);
        this.previousMonthToDate.set(previous.normalised?.total ?? null);
      },
      error: () => {
        if (id !== this.loadId) return;
        this.monthToDate.set(null);
        this.previousMonthToDate.set(null);
      },
    });
  }

  private comparisonFilters(): {
    current: { from: string; to: string };
    previous: { from: string; to: string };
  } {
    const today = new Date();
    const currentStart = new Date(today.getFullYear(), today.getMonth(), 1);
    const previousStart = new Date(today.getFullYear(), today.getMonth() - 1, 1);
    const previousEnd = new Date(today.getFullYear(), today.getMonth(), 0);
    previousEnd.setDate(Math.min(today.getDate(), previousEnd.getDate()));
    const iso = (date: Date) => {
      const pad = (value: number) => String(value).padStart(2, '0');
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
    };
    return {
      current: { from: iso(currentStart), to: iso(today) },
      previous: { from: iso(previousStart), to: iso(previousEnd) },
    };
  }

  drillAccount(id: string): void {
    this.filters.drillInto('account', id, this.periodFilter());
  }

  setGroupBy(value: string): void {
    this.groupBy.set(value);
    sessionStorage.setItem('finto.summary.groupBy', value);
    this.load();
  }

  setConvertTo(currency: string): void {
    this.preferences.setBaseCurrency(currency);
  }

  private savedGroupBy(): string {
    const saved = sessionStorage.getItem('finto.summary.groupBy');
    return saved && this.dimensions.includes(saved) ? saved : 'category';
  }

  go(path: string): void { this.router.navigate([path]); }

  humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }

  /** Drill into the blotter, under the same period the figure was computed for. */
  drill(bucket: string): void {
    this.filters.drillInto(this.groupBy(), bucket, this.periodFilter());
  }

  /** A flow column is a month. The split-by control does not apply to it. */
  drillMonth(bucket: string): void {
    this.filters.drillInto('month', bucket);
  }

  /** Tooltip for a trend column. Goes through MoneyPipe like every other figure. */
  barTitle(row: SummaryRow, bucket: string): string {
    const out = this.money.transform(row.spend);
    const inn = this.money.transform(row.income);
    return `${bucket} · out ${out} · in ${inn} · ${row.txn_count} rows`;
  }
}
