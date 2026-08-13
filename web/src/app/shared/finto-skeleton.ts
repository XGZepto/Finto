import { Component, input } from '@angular/core';

/**
 * Placeholder rows for a list whose shape is known before its data is.
 *
 * The point is that nothing moves when the rows arrive. A spinner collapses the
 * panel to a line of text and then reflows the whole page, which on a phone
 * means the row under your thumb is not the row you were about to tap.
 */
@Component({
  selector: 'finto-skeleton',
  template: `
    <div role="status" [attr.aria-label]="label()">
      @for (width of widths(); track $index) {
        <div class="line"><i class="skeleton" [style.width]="width"></i></div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .line {
      display: flex;
      align-items: center;
      min-height: 46px;
      padding: var(--s2) 0;
      border-bottom: 1px solid var(--line);
    }
    .line:last-child { border-bottom: 0; }
    .line i { display: block; height: 10px; }
  `],
})
export class FintoSkeleton {
  rows = input(6);
  label = input('Loading');

  /* Uneven widths so it reads as pending content rather than a loading bar. */
  widths = () => Array.from({ length: this.rows() }, (_, i) => `${[88, 62, 74, 55, 80, 68][i % 6]}%`);
}
