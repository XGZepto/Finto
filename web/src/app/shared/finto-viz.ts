import { Component, computed, input } from '@angular/core';

export interface Slice {
  label: string;
  value: number;
  /** Formatted amount, shown beside the share when the absolute matters. */
  display?: string;
}

export const SERIES = ['var(--c1)', 'var(--c2)', 'var(--c3)', 'var(--c4)',
                'var(--c5)', 'var(--c6)', 'var(--c7)', 'var(--c8)'];

/** Share of a whole, when the whole is the point — spending by category or tag. */
@Component({
  selector: 'finto-donut',
  template: `
    <div class="donut-wrap">
      <svg viewBox="0 0 100 100" role="img" [attr.aria-label]="label()">
        @for (a of arcs(); track a.label) {
          <circle class="arc" cx="50" cy="50" r="38" fill="none" stroke-width="16"
                  [attr.stroke]="a.colour" [attr.stroke-dasharray]="a.dash"
                  [attr.stroke-dashoffset]="a.offset" transform="rotate(-90 50 50)">
            <title>{{ a.label }} — {{ a.pct.toFixed(1) }}%</title>
          </circle>
        }
      </svg>
      @if (legend()) {
        <div class="legend">
          @for (a of arcs(); track a.label) {
            <div class="legend-row">
              <span class="dot" [style.background]="a.colour"></span>
              <span class="name">{{ a.label }}</span>
              <span class="pct mono">{{ a.pct.toFixed(1) }}%</span>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .donut-wrap { display: flex; align-items: center; gap: var(--s4); flex-wrap: wrap; }
    svg { width: 116px; height: 116px; flex: none; }
    .arc { opacity: 0; transform-origin: center; }
    :host(.finto-in-view) .arc { animation: donut-reveal var(--motion-slow) var(--ease-out) both; }
    @keyframes donut-reveal {
      from { opacity: 0; transform: rotate(-102deg) scale(.9); }
      to { opacity: 1; transform: rotate(-90deg); }
    }
    .legend { display: flex; flex-direction: column; gap: var(--s2); flex: 1 1 12rem; max-width: 20rem; min-width: 10rem; }
    .legend-row { display: flex; align-items: center; gap: var(--s2); font-size: var(--t-data); }
    .dot { width: 8px; height: 8px; flex: none; }
    .name { flex: 1; color: var(--fg-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pct { font-variant-numeric: tabular-nums; color: var(--fg-3); }
    @media (min-width: 1100px) {
      :host(.breakdown-donut) .donut-wrap { flex-direction: column; align-items: stretch; }
      :host(.breakdown-donut) .legend { max-width: none; flex: none; }
      :host(.breakdown-donut) svg { width: 132px; height: 132px; }
      :host(.breakdown-donut.bare) .donut-wrap { align-items: center; }
      :host(.breakdown-donut.bare) svg { width: 180px; height: 180px; }
    }
    @media (prefers-reduced-motion: reduce) { .arc { opacity: 1; animation: none; } }
  `],
})
export class FintoDonut {
  slices = input.required<Slice[]>();
  label = input('Breakdown');
  /** Beyond this, arcs are too thin to read; the rest collapses into one. */
  max = input(6);
  /** Off when a ranked list beside the donut already names every slice. */
  legend = input(true);

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
