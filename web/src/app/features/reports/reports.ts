import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { MoneyPipe } from '../../core/money.pipe';
import { Money, SummaryRow } from '../../core/models';
import { Preferences } from '../../core/preferences.service';
import { FintoPills } from '../../shared/finto-pills';
import { FintoSelect } from '../../shared/finto-select';
import { FintoFlow, FlowNode } from '../../shared/finto-flow';

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
  imports: [FormsModule, MoneyPipe, FintoSelect, FintoPills, FintoFlow],
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

  readonly periods = ['1M', '3M', '6M', '1Y', 'YTD'];
  readonly periodMonths: Record<string, number> = { '1M': 1, '3M': 3, '6M': 6, '1Y': 12 };

  splitBy = signal('category');
  period = signal('3M');
  accountId = signal('');
  accounts = signal<Array<{ id: string; display_name: string }>>([]);
  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  rows = signal<SummaryRow[]>([]);
  headline = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  priorHeadline = signal<{ net: Money; spend: Money; income: Money } | null>(null);

  accountOptions = computed(() =>
    ['All accounts', ...this.accounts().map((a) => a.display_name)]);

  constructor() {
    this.api.accounts().subscribe({
      next: (res: any) => this.accounts.set(res.accounts ?? res ?? []),
      error: () => undefined,
    });
    this.load();
  }

  /** The selected window, and the one immediately before it for comparison. */
  private windows(): { current: { from: string; to: string }; prior: { from: string; to: string } } {
    const today = new Date();
    const to = today.toISOString().slice(0, 10);
    const start = new Date(today);
    if (this.period() === 'YTD') start.setMonth(0, 1);
    else start.setMonth(start.getMonth() - (this.periodMonths[this.period()] ?? 3));
    const from = start.toISOString().slice(0, 10);

    const span = new Date(to).getTime() - new Date(from).getTime();
    const priorTo = new Date(new Date(from).getTime() - 86400000);
    const priorFrom = new Date(priorTo.getTime() - span);
    return {
      current: { from, to },
      prior: { from: priorFrom.toISOString().slice(0, 10), to: priorTo.toISOString().slice(0, 10) },
    };
  }

  private scope(range: { from: string; to: string }): Record<string, unknown> {
    const filter: Record<string, unknown> = { ...range };
    const chosen = this.accounts().find((a) => a.display_name === this.accountId());
    if (chosen) filter['accounts'] = [chosen.id];
    return filter;
  }

  load(): void {
    this.loading.set(true);
    const convert = this.convertTo() || undefined;
    const { current, prior } = this.windows();

    this.api.summary(this.splitBy(), this.scope(current) as any, convert).subscribe({
      next: (res) => {
        this.rows.set(res.rows);
        this.headline.set(res.normalised?.total ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.api.summary('kind', this.scope(prior) as any, convert).subscribe({
      next: (res) => this.priorHeadline.set(res.normalised?.total ?? null),
      error: () => this.priorHeadline.set(null),
    });
  }

  setSplitBy(value: string): void {
    this.splitBy.set(value);
    this.load();
  }

  setPeriod(value: string): void {
    this.period.set(value);
    this.load();
  }

  setAccount(value: string): void {
    this.accountId.set(value === 'All accounts' ? '' : value);
    this.load();
  }

  /** Change in outflow against the previous window of equal length. */
  spendDelta = computed<{ amount: Money; percent: number } | null>(() => {
    const now = this.headline(); const before = this.priorHeadline();
    if (!now || !before || !before.spend.amount) return null;
    const diff = Math.abs(now.spend.amount) - Math.abs(before.spend.amount);
    return {
      amount: { amount: diff, currency: now.spend.currency },
      percent: (diff / Math.abs(before.spend.amount)) * 100,
    };
  });

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

  savingsRate = computed(() => {
    const h = this.headline();
    if (!h || h.income.amount <= 0) return null;
    return ((h.income.amount - Math.abs(h.spend.amount)) / h.income.amount) * 100;
  });

  /** Destinations for the flow diagram: the top bands, each with its share. */
  incomeTotal = computed(() => Math.abs(this.headline()?.income.amount ?? 0));
  savedDisplay = computed(() => {
    const h = this.headline();
    if (!h) return '';
    const saved = Math.abs(h.income.amount) - Math.abs(h.spend.amount);
    return this.money.transform({ amount: saved, currency: h.income.currency }, 'bare');
  });

  flowNodes = computed<FlowNode[]>(() =>
    this.topBands().slice(0, 8).map((b) => ({
      label: b.label,
      value: Math.abs(b.amount.amount),
      display: this.money.transform(b.amount, 'bare'),
      colour: b.colour,
    })));

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
