import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { Refresh } from '../../core/refresh.service';
import { MoneyPipe } from '../../core/money.pipe';
import { LedgerFilter, Money, SummaryRow } from '../../core/models';
import { Preferences } from '../../core/preferences.service';
import { FilterState } from '../../core/filter-state';
import { FintoPills } from '../../shared/finto-pills';
import { FintoSelect } from '../../shared/finto-select';
import { FintoBars, Bar } from '../../shared/finto-bars';
import { FintoFlow, FlowNode } from '../../shared/finto-flow';
import { RevealOnView } from '../../shared/reveal-on-view';

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
  imports: [FormsModule, MoneyPipe, FintoSelect, FintoPills, FintoFlow, FintoBars, RevealOnView],
  templateUrl: './reports.html',
  styleUrl: './reports.css',
})
export class ReportsPage {
  private api = inject(Api);
  private refreshes = inject(Refresh);
  private filters = inject(FilterState);
  private preferences = inject(Preferences);
  readonly money = new MoneyPipe();
  private loadVersion = 0;

  readonly dimensions = ['category', 'subcategory', 'tag', 'merchant',
                         'cardholder', 'account', 'kind'];

  readonly reportingCurrencies = ['USD', 'HKD', 'GBP', 'EUR', 'JPY', 'CNY', 'SGD', 'AUD', 'CAD'];
  readonly periods = ['1M', '3M', '6M', '1Y', 'YTD'];
  readonly periodMonths: Record<string, number> = { '1M': 1, '3M': 3, '6M': 6, '1Y': 12 };

  splitBy = signal('category');
  period = signal('3M');
  selectedMonths = signal<string[]>([]);
  accountId = signal('');
  accounts = signal<Array<{ id: string; display_name: string }>>([]);
  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  failed = signal(false);
  rows = signal<SummaryRow[]>([]);
  monthRows = signal<SummaryRow[]>([]);
  headline = signal<{ net: Money; spend: Money; income: Money } | null>(null);
  priorHeadline = signal<{ net: Money; spend: Money; income: Money } | null>(null);

  accountOptions = computed(() =>
    ['All accounts', ...this.accounts().map((a) => a.display_name)]);

  constructor() {
    // Token starts at 0, so this arms the reload without firing one now.
    effect(() => { if (this.refreshes.token()) this.load(); });
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

  /** from/to for a single YYYY-MM month. */
  private monthRange(month: string): { from: string; to: string } {
    const [y, m] = month.split('-').map(Number);
    const from = `${month}-01`;
    const to = new Date(y, m, 0).toISOString().slice(0, 10);
    return { from, to };
  }

  /** The month before a given YYYY-MM. */
  private priorMonth(month: string): { from: string; to: string } {
    const [y, m] = month.split('-').map(Number);
    const d = new Date(y, m - 2, 1);
    return this.monthRange(d.toISOString().slice(0, 7));
  }

  private scope(range?: { from: string; to: string }, includeSelection = true): LedgerFilter {
    const months = includeSelection ? this.selectedMonths() : [];
    const filter: LedgerFilter = months.length ? { months } : { ...range };
    const chosen = this.accounts().find((a) => a.display_name === this.accountId());
    if (chosen) filter.accounts = [chosen.id];
    return filter;
  }

  load(): void {
    const version = ++this.loadVersion;
    this.loading.set(true);
    this.failed.set(false);
    const convert = this.convertTo() || undefined;
    const { current, prior } = this.windows();
    const selected = this.selectedMonths();
    const view = selected.length ? undefined : current;
    const priorView = selected.length === 1 ? this.priorMonth(selected[0]) : prior;

    this.api.summary(this.splitBy(), this.scope(view) as any, convert).subscribe({
      next: (res) => {
        if (version !== this.loadVersion) return;
        this.rows.set(res.rows);
        this.headline.set(res.normalised?.total ?? null);
        this.loading.set(false);
      },
      error: () => { if (version === this.loadVersion) { this.loading.set(false); this.failed.set(true); } },
    });

    if (selected.length <= 1) {
      this.api.summary('kind', this.scope(priorView, false) as any, convert).subscribe({
        next: (res) => {
          if (version === this.loadVersion) this.priorHeadline.set(res.normalised?.total ?? null);
        },
        error: () => { if (version === this.loadVersion) this.priorHeadline.set(null); },
      });
    } else {
      this.priorHeadline.set(null);
    }

    this.api.summary('month', this.scope(current, false) as any, convert).subscribe({
      next: (res) => { if (version === this.loadVersion) this.monthRows.set(res.rows); },
      error: () => { if (version === this.loadVersion) this.monthRows.set([]); },
    });
  }

  setSplitBy(value: string): void {
    this.splitBy.set(value);
    this.load();
  }

  setPeriod(value: string): void {
    this.period.set(value);
    this.selectedMonths.set([]);
    this.load();
  }

  setConvertTo(currency: string): void {
    this.preferences.setBaseCurrency(currency);
    this.load();
  }

  setAccount(value: string): void {
    this.accountId.set(value === 'All accounts' ? '' : value);
    this.load();
  }

  humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (character) => character.toLocaleUpperCase());
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
    // Net per bucket, so a bucket that took in more than it paid out — income,
    // rewards, a refunded category — is not listed as somewhere money went.
    const net = new Map<string, { out: number; in: number }>();
    for (const row of this.rows()) {
      const spend = row.spend_converted?.ok ? row.spend_converted.amount : null;
      if (spend === null) continue;
      const income = row.income_converted?.ok ? row.income_converted.amount : 0;
      const acc = net.get(row.bucket) ?? { out: 0, in: 0 };
      acc.out += Math.abs(spend);
      acc.in += Math.abs(income);
      net.set(row.bucket, acc);
    }
    const totals = new Map<string, number>();
    for (const [bucket, acc] of net) {
      if (acc.out <= acc.in) continue;
      totals.set(bucket, acc.out - acc.in);
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

  monthlySpend = computed<Bar[]>(() => {
    const byMonth = new Map<string, number>();
    for (const r of this.monthRows()) {
      const spend = r.spend_converted?.ok ? r.spend_converted.amount : null;
      if (spend === null) continue;
      byMonth.set(r.bucket, (byMonth.get(r.bucket) ?? 0) + Math.abs(spend));
    }
    return [...byMonth.entries()].sort((a, b) => a[0].localeCompare(b[0]))
      .map(([label, value]) => ({ label, value }));
  });

  monthlyMean = computed(() => {
    const rows = this.monthlySpend();
    if (!rows.length) return '';
    const mean = rows.reduce((s, r) => s + r.value, 0) / rows.length;
    return this.money.transform({ amount: -mean, currency: this.convertTo() }, 'bare');
  });

  focus(month: string): void {
    this.selectedMonths.update((selected) => selected.includes(month)
      ? selected.filter((value) => value !== month)
      : [...selected, month].sort());
    this.load();
  }

  clearFocus(): void {
    this.selectedMonths.set([]);
    this.load();
  }

  selectionLabel = computed(() => {
    const selected = this.selectedMonths();
    if (selected.length === 1) return selected[0];
    return selected.length ? `${selected.length} months` : '';
  });

  /** How the delta is labelled for comparable contiguous windows. */
  comparisonLabel = computed(() =>
    this.selectedMonths().length === 1 ? 'vs prior month' : `vs prior ${this.period()}`);

  /** The blotter, scoped to the same window and accounts this figure came from. */
  drill(bucket: string): void {
    this.filters.drillInto(this.splitBy(), bucket, this.scope(this.windows().current));
  }
}
