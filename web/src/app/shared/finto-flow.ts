import { Component, computed, input, output } from '@angular/core';

export interface FlowNode {
  label: string;
  value: number;
  display: string;
  colour: string;
}

/**
 * How income was allocated.
 *
 * A single income source fanning to categories in matched order is only a
 * stacked bar wearing a sankey's clothes, so this states the real relationship
 * directly: the income bar divided into what each category consumed and what
 * was left over. It reads at any width because it is a proportional bar with a
 * wrapping legend, not a fixed-width diagram.
 */
@Component({
  selector: 'finto-flow',
  template: `
    <div class="alloc">
      <div class="bar" role="img" [attr.aria-label]="'Allocation of ' + sourceLabel()">
        @for (seg of segments(); track seg.label) {
          <button type="button" class="seg" [style.flex-grow]="seg.value"
                  [style.background]="seg.colour" [title]="seg.label + ' · ' + seg.pctLabel"
                  (click)="seg.saved ? null : pick.emit(seg.label)"></button>
        }
      </div>

      <div class="legend">
        @for (seg of segments(); track seg.label) {
          <button type="button" class="row" [class.static]="seg.saved"
                  (click)="seg.saved ? null : pick.emit(seg.label)">
            <span class="dot" [style.background]="seg.colour"></span>
            <span class="name">{{ seg.label }}</span>
            <span class="pct mono">{{ seg.pctLabel }}</span>
            <span class="val mono">{{ seg.display }}</span>
          </button>
        }
      </div>
    </div>
  `,
  styles: [`
    .alloc { display: flex; flex-direction: column; gap: var(--s4); }
    .bar { display: flex; height: 12px; width: 100%; overflow: hidden; background: var(--panel-3); }
    .seg { min-height: 0; padding: 0; border: 0; border-right: 1px solid var(--bg); }
    .seg:last-child { border-right: 0; }
    .legend { display: grid; grid-template-columns: 1fr; gap: 0; }
    .row {
      display: grid;
      grid-template-columns: 10px minmax(0, 1fr) auto auto;
      align-items: center;
      gap: var(--s3);
      min-height: 0;
      padding: var(--s2) 0;
      border: 0;
      border-bottom: 1px solid var(--line);
      background: none;
      text-align: left;
      color: inherit;
    }
    .row:last-child { border-bottom: 0; }
    .row:not(.static):hover { background: var(--panel-2); }
    .row.static { cursor: default; }
    .dot { width: 10px; height: 10px; }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: var(--fg); }
    .pct { font-size: 11px; color: var(--fg-4); font-variant-numeric: tabular-nums; text-align: right; min-width: 44px; }
    .val { font-size: 12px; color: var(--fg-2); font-variant-numeric: tabular-nums; white-space: nowrap; text-align: right; }
    @media (min-width: 720px) {
      .row { grid-template-columns: 10px minmax(0, 1fr) 64px 120px; }
    }
  `],
})
export class FintoFlow {
  sourceLabel = input('income');
  nodes = input.required<FlowNode[]>();
  /** Income total, so each category can be shown as a share of it and the
   *  unspent remainder can be labelled as saved. */
  income = input(0);
  savedLabel = input('Saved');
  savedDisplay = input('');
  pick = output<string>();

  segments = computed(() => {
    const base = this.income() || this.nodes().reduce((sum, n) => sum + n.value, 0) || 1;
    const spent = this.nodes().reduce((sum, n) => sum + n.value, 0);
    const rows = this.nodes().map((n) => ({
      label: n.label,
      value: n.value,
      colour: n.colour,
      display: n.display,
      saved: false,
      pctLabel: `${((n.value / base) * 100).toFixed(1)}%`,
    }));
    const remainder = this.income() - spent;
    if (this.income() > 0 && remainder > 0) {
      rows.push({
        label: this.savedLabel(),
        value: remainder,
        colour: 'var(--pos)',
        display: this.savedDisplay(),
        saved: true,
        pctLabel: `${((remainder / base) * 100).toFixed(1)}%`,
      });
    }
    return rows;
  });
}
