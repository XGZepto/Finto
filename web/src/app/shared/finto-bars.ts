import { Component, computed, input, output } from '@angular/core';

export interface Bar {
  label: string;
  value: number;
  sub?: string;
}

/**
 * A value per period, as columns.
 *
 * Discrete months read better as separate bars than as a continuous line: the
 * question is "how much in June", not "the rate of change through June". The
 * tallest bar is emphasised and the mean is drawn so an unusual month stands
 * out against the ordinary ones.
 */
@Component({
  selector: 'finto-bars',
  template: `
    <div class="bars-wrap">
      <div class="bars" role="img" [attr.aria-label]="label()">
        @for (b of bars(); track b.label) {
          <button type="button" class="col" (click)="pick.emit(b.label)" [title]="b.title">
            <span class="track">
              <span class="fill" [class.peak]="b.peak" [style.height.%]="b.pct"></span>
            </span>
            <span class="x mono">{{ b.short }}</span>
          </button>
        }
      </div>
      @if (meanLabel()) { <div class="mean"><span>avg</span><b class="mono">{{ meanLabel() }}</b></div> }
    </div>
  `,
  styles: [`
    .bars-wrap { display: flex; flex-direction: column; gap: var(--s2); }
    .bars { display: flex; align-items: flex-end; gap: 4px; height: 140px; }
    .col { max-width: 84px; }
    .col {
      flex: 1; min-width: 0; height: 100%;
      display: flex; flex-direction: column; justify-content: flex-end; gap: var(--s2);
      padding: 0; border: 0; background: none;
    }
    .track { display: flex; align-items: flex-end; height: 100%; }
    .fill { width: 100%; background: var(--fg-4); min-height: 1px; transition: background 90ms linear; }
    .fill.peak { background: var(--info); }
    .col:hover .fill { background: var(--fg-2); }
    .col:hover .fill.peak { background: var(--info); }
    .x { font-size: 9px; color: var(--fg-4); text-align: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .mean { display: flex; align-items: baseline; gap: var(--s2); font-size: 10px; color: var(--fg-4); font-family: var(--mono); text-transform: uppercase; letter-spacing: .08em; }
    .mean b { color: var(--fg-3); font-variant-numeric: tabular-nums; }
  `],
})
export class FintoBars {
  data = input.required<Bar[]>();
  label = input('Values over time');
  meanLabel = input('');
  selected = input<string | null>(null);
  pick = output<string>();

  bars = computed(() => {
    const rows = this.data();
    const peak = Math.max(1, ...rows.map((r) => Math.abs(r.value)));
    const peakVal = Math.max(...rows.map((r) => Math.abs(r.value)));
    return rows.map((r) => ({
      label: r.label,
      short: r.label.replace(/^\d{2}(\d{2})-(\d{2})$/, '$2').replace(/^(\d{4})-(\d{2})$/, '$2'),
      pct: (Math.abs(r.value) / peak) * 100,
      peak: this.selected() ? r.label === this.selected() : Math.abs(r.value) === peakVal,
      title: `${r.label}${r.sub ? ' · ' + r.sub : ''}`,
    }));
  });
}
