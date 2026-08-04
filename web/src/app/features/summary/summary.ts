import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FilterState } from '../../core/filter-state';
import { MoneyPipe } from '../../core/money.pipe';
import { Money, Position, SummaryRow, TotalRow } from '../../core/models';
import { FilterBar } from '../../shared/filter-bar';

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
  imports: [FormsModule, MoneyPipe, FilterBar],
  templateUrl: './summary.html',
  styleUrl: './summary.css',
})
export class SummaryPage {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private money = new MoneyPipe();
  filters = inject(FilterState);

  readonly dimensions = [
    'month', 'quarter', 'year', 'category', 'subcategory', 'merchant',
    'account', 'institution', 'card', 'cardholder', 'kind', 'currency',
  ];

  groupBy = signal('month');
  convertTo = signal('');
  /** Positions as they stood on a chosen day, rather than now. */
  asOf = signal('');
  loading = signal(true);
  rows = signal<SummaryRow[]>([]);
  totals = signal<TotalRow[]>([]);
  positions = signal<Position[]>([]);
  conversion = signal<{ to: string; unconvertible_currencies: string[] } | null>(null);
  netWorth = signal<Money | null>(null);
  headline = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  outstanding = signal<Array<{ currency: string; amount: number }>>([]);

  /** Currencies present, so the conversion picker offers real options. */
  currencies = computed(() => [...new Set(this.positions().map((p) => p.currency))]);

  /** Buckets in display order, each with its per-currency rows. */
  buckets = computed(() => {
    const map = new Map<string, SummaryRow[]>();
    for (const r of this.rows()) {
      const list = map.get(r.bucket) ?? [];
      list.push(r);
      map.set(r.bucket, list);
    }
    return [...map.entries()].map(([bucket, rows]) => ({ bucket, rows }));
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

  maxSpend = computed(() =>
    Math.max(1, ...this.rows().map((r) => Math.abs(r.spend.amount))),
  );

  /**
   * In and out per account, normalised so the bars are comparable. Diverging
   * from a centre line — out to the left, in to the right — so you see at a
   * glance where money entered and left.
   */
  flow = computed(() => {
    const conv = !!this.convertTo();
    const agg = new Map<string, { name: string; inn: number; out: number }>();
    for (const p of this.positions()) {
      const inn = conv ? (p.inflow_converted?.ok ? p.inflow_converted.amount : null)
                       : p.inflow.amount;
      const out = conv ? (p.outflow_converted?.ok ? p.outflow_converted.amount : null)
                       : p.outflow.amount;
      if (inn === null || out === null) continue;   // no rate: excluded
      const a = agg.get(p.account_id) ?? { name: p.account_name, inn: 0, out: 0 };
      a.inn += inn;
      a.out += Math.abs(out);
      agg.set(p.account_id, a);
    }
    const peak = Math.max(1, ...[...agg.values()].map((a) => Math.max(a.inn, a.out)));
    return [...agg.entries()]
      .map(([id, a]) => ({
        account_id: id, name: a.name,
        inn: { amount: a.inn, currency: this.convertTo() },
        out: { amount: -a.out, currency: this.convertTo() },
        inW: `${(a.inn / peak) * 100}%`,
        outW: `${(a.out / peak) * 100}%`,
      }))
      .sort((x, y) => (Math.abs(y.out.amount) + y.inn.amount) - (Math.abs(x.out.amount) + x.inn.amount));
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
    const ccy = this.shownCurrency();
    const rows = this.rows().filter((r) => r.currency === ccy);
    return {
      amount: Math.max(0, ...rows.map(
        (r) => Math.max(Math.abs(r.spend.amount), r.income.amount))),
      currency: ccy,
    };
  });

  constructor() {
    this.route.queryParams.subscribe((params) => {
      this.filters.hydrate(params);
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    const convert = this.convertTo() || undefined;

    this.api.summary(this.groupBy(), this.filters.filter(), convert).subscribe({
      next: (res) => {
        this.rows.set(res.rows);
        this.totals.set(res.totals);
        this.headline.set(res.normalised?.total ?? null);
        this.conversion.set(res.conversion ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.api.positions(convert, this.asOf() || undefined).subscribe({
      next: (res) => {
        this.positions.set(res.positions);
        this.netWorth.set(res.normalised?.net_worth ?? null);
      },
    });

    this.api.installments(true).subscribe({
      next: (res) => this.outstanding.set(res.outstanding_by_currency),
    });
  }

  drillAccount(id: string): void {
    this.router.navigate(['/blotter'], { queryParams: { accounts: id } });
  }

  setGroupBy(value: string): void {
    this.groupBy.set(value);
    this.load();
  }

  setAsOf(value: string): void {
    this.asOf.set(value);
    this.load();
  }

  setConvert(value: string): void {
    this.convertTo.set(value);
    this.load();
  }

  /** Drill into the blotter, filtered to the clicked dimension value. */
  drill(bucket: string): void {
    this.filters.drillInto(this.groupBy(), bucket);
  }

  barWidth(row: SummaryRow): string {
    return `${(Math.abs(row.spend.amount) / this.maxSpend()) * 100}%`;
  }

  /** Tooltip for a trend column. Goes through MoneyPipe like every other figure. */
  barTitle(row: SummaryRow, bucket: string): string {
    const out = this.money.transform(row.spend);
    const inn = this.money.transform(row.income);
    return `${bucket} · out ${out} · in ${inn} · ${row.txn_count} rows`;
  }
}
