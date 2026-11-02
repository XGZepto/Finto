import { Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { PageStatus } from '../../core/page-status';
import { MoneyPipe } from '../../core/money.pipe';
import { Composition, Coverage } from '../../core/models';
import { FilterState, bucketRange } from '../../core/filter-state';
import { FintoSelect } from '../../shared/finto-select';
import { Preferences } from '../../core/preferences.service';

/**
 * A band's colour, cycling through the categorical ramp. Only the --c series:
 * --info and --warn share values with --c1 and --c3, which drew two bands in
 * one colour.
 */
const RAMP = ['--c1', '--c2', '--c3', '--c4', '--c5', '--c6', '--c7', '--c8'];

@Component({
  selector: 'app-timeline',
  imports: [FintoSkeleton, FormsModule, MoneyPipe, FintoSelect],
  templateUrl: './timeline.html',
  styleUrl: './timeline.css',
})
export class TimelinePage {
  private api = inject(Api);
  private filters = inject(FilterState);
  private preferences = inject(Preferences);

  status = signal<PageStatus>('loading');
  private loadId = 0;
  comp = signal<Composition | null>(null);
  coverage = signal<Coverage | null>(null);

  dimension = signal('category');
  /**
   * Opens in the reporting currency the rest of the app already uses, and keeps
   * following it (the saved preference can land after this page does) until
   * the picker here is used.
   */
  convertTo = linkedSignal(() => this.preferences.baseCurrency() || 'HKD');
  mode = signal<'share' | 'amount'>('share');
  hovered = signal<number | null>(null);

  readonly dimensions = ['category', 'subcategory', 'merchant', 'account', 'kind', 'cardholder'];
  currencies = computed(() => [...new Set([this.convertTo(), 'HKD', 'USD', 'CNY', 'EUR', 'GBP'])]);

  constructor() {
    this.api.coverage().subscribe({
      next: (c) => this.coverage.set(c),
      error: () => this.coverage.set(null),
    });
    effect(() => {
      this.convertTo();
      this.dimension();
      untracked(() => this.reload());
    });
  }

  reload(): void {
    const id = ++this.loadId;
    this.status.set('loading');
    // The same spending scope as Summary, so the two never disagree about
    // what a category cost.
    this.api.composition(this.convertTo(), this.dimension(), { spendingOnly: true }).subscribe({
      next: (c) => {
        if (id !== this.loadId) return;
        this.comp.set(c);
        this.status.set('ok');
      },
      error: () => { if (id === this.loadId) this.status.set('failed'); },
    });
  }

  set<T>(sig: { set: (v: T) => void }, v: T): void {
    sig.set(v);
  }

  colour(bucket: string, i: number): string {
    // "other" is a residual, not a category — a neutral grey keeps it from
    // reading as one and from colliding when the ramp wraps.
    return bucket === 'other' ? 'var(--fg-4)' : `var(${RAMP[i % RAMP.length]})`;
  }

  /** Each month's total across all series, for the share denominator. */
  private columnTotals = computed(() => {
    const c = this.comp();
    if (!c) return [];
    return c.months.map((_, i) => c.series.reduce((s, b) => s + b.values[i], 0));
  });

  /** Peak month total, so amount-mode columns share one scale. */
  private peak = computed(() => Math.max(1, ...this.columnTotals()));

  /**
   * One stacked column per month: each band's height and its running offset
   * from the bottom, in percent. Share divides by the column; amount by the
   * tallest column, so the two modes answer different questions off one chart.
   */
  columns = computed(() => {
    const c = this.comp();
    if (!c) return [];
    const totals = this.columnTotals();
    return c.months.map((month, mi) => {
      const denom = this.mode() === 'share' ? totals[mi] || 1 : this.peak();
      let offset = 0;
      const bands = c.series.map((b, bi) => {
        const h = (b.values[mi] / denom) * 100;
        const band = { bucket: b.bucket, colour: this.colour(b.bucket, bi),
                       amount: b.values[mi], height: h, bottom: offset };
        offset += h;
        return band;
      });
      const step = Math.max(1, Math.ceil(c.months.length / 6));
      const last = c.months.length - 1;
      // Every step-th month plus the last, never two labels close enough to overlap.
      const show = mi === last || (mi % step === 0 && last - mi >= Math.ceil(step / 2));
      return { month, label: month.replace(/^\d{2}(\d{2})-/, '$1-'), show, total: totals[mi], bands };
    });
  });

  legend = computed(() =>
    (this.comp()?.series ?? []).map((b, i) => ({
      bucket: b.bucket, name: this.humanize(b.bucket), total: b.total, colour: this.colour(b.bucket, i),
    })),
  );

  humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  }

  /** A cell is a dimension value inside one month, so the drill carries both. */
  drill(month: string, bucket: string): void {
    this.filters.drillInto(this.dimension(), bucket,
      { ...(bucketRange('month', month) ?? {}), spendingOnly: true });
  }
}
