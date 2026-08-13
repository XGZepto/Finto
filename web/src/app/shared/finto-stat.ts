import { Component, input } from '@angular/core';
import { MoneyPipe } from '../core/money.pipe';
import { Money } from '../core/models';

/** Movement against the previous comparable period. */
@Component({
  selector: 'finto-delta',
  imports: [MoneyPipe],
  template: `
    <span class="delta" [class.pos]="up()" [class.neg]="!up()">
      <span aria-hidden="true">{{ up() ? '↑' : '↓' }}</span>
      <span>{{ magnitude() | money }}</span>
      @if (percent() !== null) { <span>({{ percentLabel() }})</span> }
      @if (period()) { <span class="period">{{ period() }}</span> }
    </span>
  `,
  styles: [`
    .delta {
      display: inline-flex;
      align-items: center;
      gap: var(--s2);
      font-family: var(--mono);
      font-size: var(--t-data);
      font-variant-numeric: tabular-nums;
    }
    .period { color: var(--fg-4); }
  `],
})
export class FintoDelta {
  change = input.required<Money>();
  percent = input<number | null>(null);
  period = input<string>('');

  up(): boolean {
    return this.change().amount >= 0;
  }

  magnitude(): Money {
    const c = this.change();
    return { ...c, amount: Math.abs(c.amount) };
  }

  percentLabel(): string {
    return `${Math.abs(this.percent() ?? 0).toFixed(1)}%`;
  }
}

/**
 * The one figure a view is about, with its movement.
 *
 * A balance answers "what"; the delta beside it answers "is that normal", which
 * is the question a person actually arrived with.
 */
@Component({
  selector: 'finto-stat',
  imports: [MoneyPipe, FintoDelta],
  template: `
    <div class="stat">
      <span class="label">{{ label() }}</span>
      <span class="stat-figure" [class.neg]="negative()" [class.pos]="positive()">
        {{ value() | money }}
      </span>
      @if (change(); as c) {
        <finto-delta [change]="c" [percent]="percent()" [period]="period()" />
      }
    </div>
  `,
  styles: [`
    .stat { display: flex; flex-direction: column; gap: var(--s1); }
    .stat-figure {
      font-family: var(--mono);
      font-size: var(--t-hero);
      font-variant-numeric: tabular-nums;
      letter-spacing: -.02em;
      line-height: 1.1;
    }
    @media (max-width: 880px) { .stat-figure { font-size: var(--t-fig-lg); } }
  `],
})
export class FintoStat {
  label = input.required<string>();
  value = input.required<Money | null>();
  change = input<Money | null>(null);
  percent = input<number | null>(null);
  period = input<string>('');
  /** Colour the hero only where sign is the point — a net figure, not a balance. */
  signed = input(false);

  negative(): boolean {
    return this.signed() && (this.value()?.amount ?? 0) < 0;
  }

  positive(): boolean {
    return this.signed() && (this.value()?.amount ?? 0) > 0;
  }
}
