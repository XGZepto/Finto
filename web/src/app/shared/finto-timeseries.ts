import { Component, computed, input } from '@angular/core';

export interface SeriesPoint {
  label: string;
  value: number;
}

/**
 * A balance over time.
 *
 * The fill is what separates a balance from a rate: area reads as an amount
 * accumulated, a bare line reads as a measurement. The endpoint is marked
 * because "where it stands now" is the question the chart is asked.
 */
@Component({
  selector: 'finto-timeseries',
  template: `
    <svg [attr.viewBox]="'0 0 ' + w + ' ' + h" preserveAspectRatio="none"
         role="img" [attr.aria-label]="label()">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" [attr.stop-color]="stroke()" stop-opacity=".26" />
          <stop offset="1" [attr.stop-color]="stroke()" stop-opacity="0" />
        </linearGradient>
      </defs>
      @for (y of gridlines; track y) {
        <line class="grid" x1="0" [attr.y1]="y" [attr.x2]="w" [attr.y2]="y" />
      }
      @if (geometry(); as g) {
        <polygon [attr.points]="g.area" [attr.fill]="'url(#' + gradientId + ')'" />
        <polyline [attr.points]="g.line" fill="none" [attr.stroke]="stroke()" stroke-width="2"
                  vector-effect="non-scaling-stroke" />
      }
    </svg>
    <div class="axis">
      <span class="mono">{{ first() }}</span>
      <span class="mono">{{ last() }}</span>
    </div>
  `,
  styles: [`
    :host { display: block; }
    svg { display: block; width: 100%; height: 120px; }
    .grid { stroke: var(--line); stroke-width: 1; vector-effect: non-scaling-stroke; }
    .axis {
      display: flex; justify-content: space-between;
      margin-top: var(--s2); color: var(--fg-4); font-size: var(--t-micro);
      letter-spacing: .1em; text-transform: uppercase;
    }
  `],
})
export class FintoTimeseries {
  points = input.required<SeriesPoint[]>();
  label = input('Balance over time');
  stroke = input('var(--info)');

  readonly w = 100;
  readonly h = 40;
  readonly gridlines = [8, 20, 32];
  readonly gradientId = `ts-${Math.random().toString(36).slice(2, 9)}`;

  first = computed(() => this.points()[0]?.label ?? '');
  last = computed(() => this.points()[this.points().length - 1]?.label ?? '');

  geometry = computed(() => {
    const pts = this.points();
    if (pts.length < 2) return null;
    const values = pts.map((p) => p.value);
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min || Math.abs(max) || 1;
    const pad = 4;
    const usable = this.h - pad * 2;
    const step = this.w / (pts.length - 1);
    const coords = pts.map((p, i) => {
      const x = i * step;
      const y = pad + usable - ((p.value - min) / span) * usable;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    });
    return {
      line: coords.join(' '),
      area: `0,${this.h} ${coords.join(' ')} ${this.w},${this.h}`,
    };
  });
}
