import { Component, input, output } from '@angular/core';

/**
 * A small closed set of choices, all visible at once.
 *
 * A period switcher is read as often as it is clicked, so it stays spelled out
 * rather than collapsing into a dropdown that hides the current answer.
 */
@Component({
  selector: 'finto-pills',
  template: `
    <div class="pills" [class.stretch]="stretch()" role="group" [attr.aria-label]="ariaLabel()">
      @for (option of options(); track option) {
        <button type="button" class="pill" [class.on]="option === value()"
                [attr.aria-pressed]="option === value()" (click)="pick.emit(option)">
          {{ option }}
        </button>
      }
    </div>
  `,
  styles: [`
    :host { display: inline-flex; }
    .pills { display: inline-flex; border: 1px solid var(--line-strong); }
    .pills.stretch { display: flex; width: 100%; }
    .pills.stretch .pill { flex: 1; text-align: center; }
    .pill {
      display: grid;
      place-items: center;
      padding: var(--s1) var(--s3);
      border: 0;
      border-right: 1px solid var(--line-strong);
      background: none;
      color: var(--fg-3);
      font-family: var(--mono);
      font-size: var(--t-meta);
      letter-spacing: .08em;
    }
    .pill:last-child { border-right: 0; }
    .pill.on { background: var(--panel-3); color: var(--fg); }
  `],
})
export class FintoPills {
  options = input.required<string[]>();
  value = input.required<string>();
  ariaLabel = input('Options');
  stretch = input(false);
  pick = output<string>();
}
