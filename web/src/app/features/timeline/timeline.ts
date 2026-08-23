import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { MoneyPipe } from '../../core/money.pipe';
import { Composition, Coverage } from '../../core/models';
import { FilterState, bucketRange } from '../../core/filter-state';
import { FintoSelect } from '../../shared/finto-select';

/** A band's colour, cycling through the categorical ramp. */
const RAMP = ['--c1', '--c2', '--c3', '--c4', '--c5', '--c6', '--info', '--warn'];

@Component({
  selector: 'app-timeline',
  imports: [FintoSkeleton, FormsModule, MoneyPipe, FintoSelect],
  templateUrl: './timeline.html',
  styleUrl: './timeline.css',
})
export class TimelinePage {
  private api = inject(Api);
  private filters = inject(FilterState);

  loading = signal(true);
  failed = signal(false);
  comp = signal<Composition | null>(null);
  coverage = signal<Coverage | null>(null);

  dimension = signal('category');
  convertTo = signal('USD');
  mode = signal<'share' | 'amount'>('share');
  hovered = signal<number | null>(null);

  readonly dimensions = ['category', 'subcategory', 'merchant', 'account', 'kind', 'cardholder'];
  readonly currencies = ['HKD', 'USD', 'CNY', 'EUR', 'GBP'];

  constructor() {
    this.api.coverage().subscribe({ next: (c) => this.coverage.set(c) });
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api.composition(this.convertTo(), this.dimension()).subscribe({
      next: (c) => {
        this.comp.set(c);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); this.failed.set(true); },
    });
  }

  set<T>(sig: { set: (v: T) => void }, v: T): void {
    sig.set(v);
    this.reload();
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
      return { month, label: month.replace(/^\d{2}(\d{2})-/, '$1-'), total: totals[mi], bands };
    });
  });

  legend = computed(() =>
    (this.comp()?.series ?? []).map((b, i) => ({
      bucket: b.bucket, total: b.total, colour: this.colour(b.bucket, i),
    })),
  );

  /** A cell is a dimension value inside one month, so the drill carries both. */
  drill(month: string, bucket: string): void {
    this.filters.drillInto(this.dimension(), bucket, bucketRange('month', month) ?? {});
  }
}
