import { Component, computed, input } from '@angular/core';

export interface Slice {
  label: string;
  value: number;
  /** Formatted amount, shown beside the share when the absolute matters. */
  display?: string;
}

const SERIES = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)',
                'var(--c5)', 'var(--c6)', 'var(--c7)', 'var(--c8)'];

/** Composition as one bar. Reads faster than a pie and stacks in a narrow column. */
@Component({
  selector: 'finto-share-bar',
  template: `
    <div class="share" role="img" [attr.aria-label]="label()">
      @for (s of shares(); track s.label) {
        <span [style.width.%]="s.pct" [style.background]="s.colour" [title]="s.title"></span>
      }
    </div>
    @if (legend()) {
      <div class="legend">
        @for (s of shares(); track s.label) {
          <div class="legend-row">
            <span class="dot" [style.background]="s.colour"></span>
            <span class="name">{{ s.label }}</span>
            @if (s.display) { <span class="amount mono">{{ s.display }}</span> }
            <span class="pct mono">{{ s.pct.toFixed(1) }}%</span>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .share { display: flex; height: 8px; width: 100%; overflow: hidden; background: var(--panel-3); }
    .share span { display: block; height: 100%; }
    .legend { display: flex; flex-direction: column; gap: var(--s2); margin-top: var(--s3); }
    .legend-row { display: flex; align-items: center; gap: var(--s2); font-size: var(--t-data); }
    .dot { width: 8px; height: 8px; flex: none; }
    .name { flex: 1; color: var(--fg-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .amount { font-variant-numeric: tabular-nums; color: var(--fg); }
    .pct { font-variant-numeric: tabular-nums; color: var(--fg-4); width: 48px; text-align: right; }
  `],
})
export class FintoShareBar {
  slices = input.required<Slice[]>();
  label = input('Composition');
  legend = input(true);

  shares = computed(() => {
    const rows = this.slices().filter((s) => s.value > 0);
    const total = rows.reduce((sum, s) => sum + s.value, 0) || 1;
    return rows.map((s, i) => ({
      label: s.label,
      display: s.display,
      colour: SERIES[i % SERIES.length],
      pct: (s.value / total) * 100,
      title: `${s.label} — ${((s.value / total) * 100).toFixed(1)}%`,
    }));
  });
}

/** Share of a whole, when the whole is the point — spending by category or tag. */
@Component({
  selector: 'finto-donut',
  template: `
    <div class="donut-wrap">
      <svg viewBox="0 0 100 100" role="img" [attr.aria-label]="label()">
        @for (a of arcs(); track a.label) {
          <circle cx="50" cy="50" r="38" fill="none" stroke-width="16"
                  [attr.stroke]="a.colour" [attr.stroke-dasharray]="a.dash"
                  [attr.stroke-dashoffset]="a.offset" transform="rotate(-90 50 50)">
            <title>{{ a.label }} — {{ a.pct.toFixed(1) }}%</title>
          </circle>
        }
      </svg>
      <div class="legend">
        @for (a of arcs(); track a.label) {
          <div class="legend-row">
            <span class="dot" [style.background]="a.colour"></span>
            <span class="name">{{ a.label }}</span>
            <span class="pct mono">{{ a.pct.toFixed(1) }}%</span>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .donut-wrap { display: flex; align-items: center; gap: var(--s4); flex-wrap: wrap; }
    svg { width: 116px; height: 116px; flex: none; }
    .legend { display: flex; flex-direction: column; gap: var(--s2); flex: 1; min-width: 140px; }
    .legend-row { display: flex; align-items: center; gap: var(--s2); font-size: var(--t-data); }
    .dot { width: 8px; height: 8px; flex: none; }
    .name { flex: 1; color: var(--fg-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pct { font-variant-numeric: tabular-nums; color: var(--fg-3); }
  `],
})
export class FintoDonut {
  slices = input.required<Slice[]>();
  label = input('Breakdown');
  /** Beyond this, arcs are too thin to read; the rest collapses into one. */
  max = input(6);

  arcs = computed(() => {
    const rows = this.slices().filter((s) => s.value > 0)
      .sort((a, b) => b.value - a.value);
    const head = rows.slice(0, this.max());
    const tail = rows.slice(this.max());
    if (tail.length) {
      head.push({ label: `${tail.length} more`, value: tail.reduce((s, r) => s + r.value, 0) });
    }
    const total = head.reduce((sum, s) => sum + s.value, 0) || 1;
    const circumference = 2 * Math.PI * 38;
    let used = 0;
    return head.map((s, i) => {
      const pct = (s.value / total) * 100;
      const len = (s.value / total) * circumference;
      const arc = {
        label: s.label,
        pct,
        colour: SERIES[i % SERIES.length],
        dash: `${len} ${circumference - len}`,
        offset: -used,
      };
      used += len;
      return arc;
    });
  });
}

/** Shape of a balance over time, at row scale. No axes — the trend is the point. */
@Component({
  selector: 'finto-sparkline',
  template: `
    <svg [attr.viewBox]="'0 0 ' + width() + ' ' + height()" role="img" [attr.aria-label]="label()">
      <polyline [attr.points]="points()" fill="none" stroke="var(--fg-4)" stroke-width="1.5" />
    </svg>
  `,
  styles: [`
    :host { display: block; }
    svg { display: block; width: 100%; height: 100%; }
  `],
})
export class FintoSparkline {
  values = input.required<number[]>();
  label = input('Trend');
  width = input(80);
  height = input(22);

  points = computed(() => {
    const v = this.values();
    if (v.length < 2) return '';
    const min = Math.min(...v);
    const max = Math.max(...v);
    const span = max - min || 1;
    const stepX = this.width() / (v.length - 1);
    const pad = 2;
    const usable = this.height() - pad * 2;
    return v
      .map((n, i) => `${(i * stepX).toFixed(1)},${(pad + usable - ((n - min) / span) * usable).toFixed(1)}`)
      .join(' ');
  });
}
