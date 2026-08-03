import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Api } from '../../core/api.service';
import { FilterState } from '../../core/filter-state';
import { MoneyPipe } from '../../core/money.pipe';
import { Position, SummaryRow, TotalRow } from '../../core/models';
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
  private money = new MoneyPipe();
  filters = inject(FilterState);

  readonly dimensions = [
    'month', 'quarter', 'year', 'category', 'subcategory', 'merchant',
    'account', 'institution', 'card', 'kind', 'currency',
  ];

  groupBy = signal('month');
  convertTo = signal('');
  loading = signal(true);
  rows = signal<SummaryRow[]>([]);
  totals = signal<TotalRow[]>([]);
  positions = signal<Position[]>([]);
  conversion = signal<{ to: string; unconvertible_currencies: string[] } | null>(null);
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

  maxSpend = computed(() =>
    Math.max(1, ...this.rows().map((r) => Math.abs(r.spend.amount))),
  );

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
  trend = computed(() => {
    if (!this.isTimeDimension()) return [];
    const ccy = this.shownCurrency();
    const rows = this.rows()
      .filter((r) => r.currency === ccy)
      .sort((a, b) => a.bucket.localeCompare(b.bucket));
    const peak = Math.max(1, ...rows.map((r) => Math.abs(r.spend.amount)));
    return rows.map((r) => ({
      bucket: r.bucket,
      row: r,
      height: `${Math.max(1, (Math.abs(r.spend.amount) / peak) * 100)}%`,
    }));
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
        this.conversion.set(res.conversion ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.api.positions(convert).subscribe({
      next: (res) => this.positions.set(res.positions),
    });

    this.api.installments(true).subscribe({
      next: (res) => this.outstanding.set(res.outstanding_by_currency),
    });
  }

  setGroupBy(value: string): void {
    this.groupBy.set(value);
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
