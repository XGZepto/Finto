import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { MoneyPipe } from '../../core/money.pipe';
import { Money, StatementFreshness, SummaryRow, TotalRow } from '../../core/models';
import { FintoSelect } from '../../shared/finto-select';
import { FintoPills } from '../../shared/finto-pills';
import { FintoStat } from '../../shared/finto-stat';
import { FintoTimeseries, SeriesPoint } from '../../shared/finto-timeseries';
import { FintoDonut, FintoShareBar, Slice } from '../../shared/finto-viz';
import { Preferences } from '../../core/preferences.service';

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
  imports: [FormsModule, MoneyPipe, FintoSelect, FintoStat, FintoShareBar, FintoDonut,
            FintoTimeseries, FintoPills],
  templateUrl: './summary.html',
  styleUrl: './summary.css',
})
export class SummaryPage {
  private api = inject(Api);
  private router = inject(Router);
  private preferences = inject(Preferences);
  readonly money = new MoneyPipe();

  readonly dimensions = [
    'month', 'quarter', 'year', 'category', 'subcategory', 'tag', 'merchant',
    'account', 'institution', 'card', 'cardholder', 'kind', 'currency',
  ];

  groupBy = signal(this.savedGroupBy());
  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  rows = signal<SummaryRow[]>([]);
  totals = signal<TotalRow[]>([]);
  netWorth = signal<Money | null>(null);
  positionTypes = signal<Array<{ account_type: string; balance: Money }>>([]);
  headline = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  freshness = signal<StatementFreshness | null>(null);
  readonly reportingCurrencies = ['USD', 'HKD', 'GBP', 'EUR', 'JPY', 'CNY', 'SGD', 'AUD', 'CAD'];

  positionChart = computed(() => {
    const rows = this.positionTypes().filter((row) => row.balance.amount !== 0);
    const peak = Math.max(1, ...rows.map((row) => Math.abs(row.balance.amount)));
    return rows
      .map((row) => ({ ...row, label: this.humanize(row.account_type),
        width: `${Math.max(1.5, Math.abs(row.balance.amount) / peak * 50)}%` }))
      .sort((a, b) => Math.abs(b.balance.amount) - Math.abs(a.balance.amount));
  });


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

  /** How far back the net worth chart reaches, in months per pill. */
  readonly rangeOptions = ['3M', '6M', '1Y', '2Y'];
  readonly rangeMonths: Record<string, number> = { '3M': 3, '6M': 6, '1Y': 12, '2Y': 24 };
  range = signal('1Y');
  seriesMonths = computed(() => this.rangeMonths[this.range()] ?? 12);
  netWorthPoints = signal<Array<{ bucket: string; as_of: string; balance: Money }>>([]);

  series = computed<SeriesPoint[]>(() =>
    this.netWorthPoints().map((p) => ({ label: p.bucket, value: p.balance.amount })));

  /** Movement across the charted window, which is what the pills select. */
  netWorthChange = computed<Money | null>(() => {
    const pts = this.netWorthPoints();
    if (pts.length < 2) return null;
    const first = pts[0].balance;
    const last = pts[pts.length - 1].balance;
    return { amount: last.amount - first.amount, currency: last.currency };
  });

  netWorthPercent = computed<number | null>(() => {
    const pts = this.netWorthPoints();
    if (pts.length < 2 || !pts[0].balance.amount) return null;
    const first = pts[0].balance.amount;
    return ((pts[pts.length - 1].balance.amount - first) / Math.abs(first)) * 100;
  });

  setRange(value: string): void {
    this.range.set(value);
    const convert = this.convertTo();
    if (!convert) return;
    this.api.netWorthSeries(convert, this.seriesMonths()).subscribe({
      next: (res) => this.netWorthPoints.set(res.points),
      error: () => this.netWorthPoints.set([]),
    });
  }

  /** What the net worth is made of, largest holding first. */
  assetSlices = computed<Slice[]>(() =>
    this.positionTypes()
      .filter((row) => row.balance.amount > 0)
      .map((row) => ({ label: this.humanize(row.account_type), value: row.balance.amount }))
      .sort((a, b) => b.value - a.value));

  liabilitySlices = computed<Slice[]>(() =>
    this.positionTypes()
      .filter((row) => row.balance.amount < 0)
      .map((row) => ({ label: this.humanize(row.account_type), value: -row.balance.amount }))
      .sort((a, b) => b.value - a.value));

  /** Where the money went, on whichever dimension is selected. */
  spendSlices = computed<Slice[]>(() =>
    this.ranked().map((row) => ({ label: row.bucket, value: Math.abs(row.spend.amount) })));

  /** Kept out of the ledger as a stored figure: it is a ratio of two others. */
  savingsRate = computed(() => {
    const h = this.headline();
    if (!h || h.income.amount <= 0) return null;
    return ((h.income.amount - Math.abs(h.spend.amount)) / h.income.amount) * 100;
  });


  readonly timeDimensions = ['day', 'month', 'quarter', 'year'];
  isTimeDimension = computed(() => this.timeDimensions.includes(this.groupBy()));

  /** Currency the trend is drawn in — a chart mixing HKD and USD bars is a lie. */
  chartCurrency = signal('');
  chartCurrencies = computed(() => [...new Set(this.rows().map((r) => r.currency))]);

  /** Falls back when a filter change removes the currency that was selected. */
  shownCurrency = computed(() => {
    const available = this.chartCurrencies();
    const chosen = this.chartCurrency();
    return chosen && available.includes(chosen) ? chosen : available[0];
  });

  /**
   * Spend per period, oldest first.
   *
   * Only one currency at a time. Bars are comparable because they share a scale,
   * and two currencies on one scale would imply an exchange rate nobody chose.
   */
  /**
   * Out and in per period, on a shared scale so the two are comparable and a
   * month that earned more than it spent is visible as such.
   */
  trend = computed(() => {
    if (!this.isTimeDimension()) return [];
    if (this.convertTo()) {
      const grouped = new Map<string, SummaryRow>();
      for (const row of this.rows()) {
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
    const rows = this.rows()
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
    if (this.convertTo()) {
      return { amount: Math.max(0, ...this.trend().map(
        (item) => Math.max(Math.abs(item.row.spend.amount), item.row.income.amount))),
        currency: this.convertTo() };
    }
    const ccy = this.shownCurrency();
    const rows = this.rows().filter((r) => r.currency === ccy);
    return {
      amount: Math.max(0, ...rows.map(
        (r) => Math.max(Math.abs(r.spend.amount), r.income.amount))),
      currency: ccy,
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
        show: items.length <= 8 || i === 0 || i === items.length - 1 || i % Math.ceil(items.length / 6) === 0,
      })),
    };
  });

  constructor() {
    this.api.statementFreshness().subscribe({ next: (r) => this.freshness.set(r) });
    effect(() => {
      this.convertTo();
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    const convert = this.convertTo() || undefined;

    this.api.summary(this.groupBy(), {}, convert).subscribe({
      next: (res) => {
        this.rows.set(res.rows);
        this.totals.set(res.totals);
        this.headline.set(res.normalised?.total ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.api.positions(convert).subscribe({
      next: (res) => {
        this.netWorth.set(res.normalised?.net_worth ?? null);
        this.positionTypes.set(res.normalised?.by_type ?? []);
      },
    });

    if (convert) {
      this.api.netWorthSeries(convert, this.seriesMonths()).subscribe({
        next: (res) => this.netWorthPoints.set(res.points),
        error: () => this.netWorthPoints.set([]),
      });
    }
  }

  drillAccount(id: string): void {
    this.router.navigate(['/blotter'], { queryParams: { accounts: id } });
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
    return saved && this.dimensions.includes(saved) ? saved : 'month';
  }

  go(path: string): void { this.router.navigate([path]); }

  humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }

  /** Drill into the blotter, filtered to the clicked dimension value. */
  drill(bucket: string): void {
    const dimension = this.groupBy();
    const queryParams: Record<string, string> = {};
    if (dimension === 'day') queryParams['from'] = queryParams['to'] = bucket;
    else if (dimension === 'month') {
      queryParams['from'] = `${bucket}-01`;
      const [year, month] = bucket.split('-').map(Number);
      queryParams['to'] = `${bucket}-${new Date(year, month, 0).getDate()}`;
    } else if (dimension === 'quarter') {
      const [year, q] = bucket.split('-Q').map(Number);
      const first = (q - 1) * 3 + 1;
      const last = first + 2;
      queryParams['from'] = `${year}-${String(first).padStart(2, '0')}-01`;
      queryParams['to'] = `${year}-${String(last).padStart(2, '0')}-${new Date(year, last, 0).getDate()}`;
    } else if (dimension === 'year') {
      queryParams['from'] = `${bucket}-01-01`; queryParams['to'] = `${bucket}-12-31`;
    } else {
      const map: Record<string, string> = {
        account: 'accounts', institution: 'institutions', category: 'categories',
        card: 'cards', cardholder: 'cardholders', kind: 'kinds', currency: 'currency',
      };
      const key = map[dimension];
      if (key) queryParams[key] = bucket;
      else if (dimension === 'merchant' || dimension === 'subcategory') queryParams['q'] = bucket;
    }
    this.router.navigate(['/blotter'], { queryParams });
  }

  /** Tooltip for a trend column. Goes through MoneyPipe like every other figure. */
  barTitle(row: SummaryRow, bucket: string): string {
    const out = this.money.transform(row.spend);
    const inn = this.money.transform(row.income);
    return `${bucket} · out ${out} · in ${inn} · ${row.txn_count} rows`;
  }
}
