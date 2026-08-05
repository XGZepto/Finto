import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { MoneyPipe } from '../../core/money.pipe';
import { Money, SummaryRow } from '../../core/models';
import { Preferences } from '../../core/preferences.service';
import { FintoSelect } from '../../shared/finto-select';
import { FintoDonut, FintoShareBar, Slice } from '../../shared/finto-viz';

interface Band {
  label: string;
  amount: Money;
  share: number;
  width: string;
  colour: string;
}

const SERIES = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)',
                'var(--c5)', 'var(--c6)', 'var(--c7)', 'var(--c8)'];

/**
 * Reports.
 *
 * Answers where the money went for a period: what came in, what left, and how
 * the outflow divides on whichever entity dimension is chosen. The flow diagram
 * reads left to right — income, then the split it paid for.
 */
@Component({
  selector: 'app-reports',
  imports: [FormsModule, MoneyPipe, FintoSelect, FintoDonut, FintoShareBar],
  templateUrl: './reports.html',
  styleUrl: './reports.css',
})
export class ReportsPage {
  private api = inject(Api);
  private router = inject(Router);
  private preferences = inject(Preferences);
  readonly money = new MoneyPipe();

  readonly dimensions = ['category', 'subcategory', 'tag', 'merchant',
                         'cardholder', 'account', 'kind'];

  splitBy = signal('category');
  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  rows = signal<SummaryRow[]>([]);
  headline = signal<{ net: Money; spend: Money; income: Money } | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.summary(this.splitBy(), {}, this.convertTo() || undefined).subscribe({
      next: (res) => {
        this.rows.set(res.rows);
        this.headline.set(res.normalised?.total ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  setSplitBy(value: string): void {
    this.splitBy.set(value);
    this.load();
  }

  /** Outflow per bucket in one currency, largest first. */
  bands = computed<Band[]>(() => {
    const totals = new Map<string, number>();
    for (const row of this.rows()) {
      const spend = row.spend_converted?.ok ? row.spend_converted.amount : null;
      if (spend === null) continue;
      totals.set(row.bucket, (totals.get(row.bucket) ?? 0) + Math.abs(spend));
    }
    const all = [...totals.values()].reduce((sum, n) => sum + n, 0) || 1;
    const peak = Math.max(1, ...totals.values());
    return [...totals.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([label, amount], i) => ({
        label,
        amount: { amount: -amount, currency: this.convertTo() },
        share: amount / all,
        width: `${Math.max(1, (amount / peak) * 100)}%`,
        colour: SERIES[i % SERIES.length],
      }));
  });

  topBands = computed(() => this.bands().slice(0, 12));
  slices = computed<Slice[]>(() =>
    this.bands().map((b) => ({ label: b.label, value: Math.abs(b.amount.amount) })));

  savingsRate = computed(() => {
    const h = this.headline();
    if (!h || h.income.amount <= 0) return null;
    return ((h.income.amount - Math.abs(h.spend.amount)) / h.income.amount) * 100;
  });

  /**
   * Income on the left, the split it funded on the right.
   *
   * Band heights are shares of outflow, so a wide ribbon is a large share of
   * what was spent rather than of what was earned.
   */
  flow = computed(() => {
    const bands = this.topBands().slice(0, 7);
    const total = bands.reduce((sum, b) => sum + Math.abs(b.amount.amount), 0) || 1;
    const height = 240;
    let cursor = 0;
    return bands.map((b) => {
      const span = (Math.abs(b.amount.amount) / total) * height;
      const node = { ...b, y: cursor, height: Math.max(2, span) };
      cursor += span;
      return node;
    });
  });

  drill(bucket: string): void {
    const key = this.splitBy();
    const params: Record<string, string> = {};
    if (key === 'category') params['categories'] = bucket;
    else if (key === 'tag') params['tags'] = bucket;
    else if (key === 'merchant') params['q'] = bucket;
    else if (key === 'account') params['accounts'] = bucket;
    else if (key === 'cardholder') params['cardholders'] = bucket;
    else if (key === 'kind') params['kinds'] = bucket;
    this.router.navigate(['/blotter'], { queryParams: params });
  }
}
