import { Component, ElementRef, HostListener, forwardRef, inject, signal } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

interface CalendarDay { iso: string; day: number; inMonth: boolean; }

@Component({
  selector: 'finto-date',
  host: { '[class.sheet-open]': 'open()' },
  template: `
    <div class="date-root" [class.open]="open()">
      <button type="button" class="date-trigger" [disabled]="disabled()"
              [attr.aria-label]="ariaLabel" [attr.aria-expanded]="open()"
              aria-haspopup="dialog" (click)="toggle()">
        <span [class.placeholder]="!value()">{{ value() || 'YYYY-MM-DD' }}</span>
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 3v3m12-3v3M3 7h14M4 5h12v12H4z" /></svg>
      </button>
      @if (open()) {
        <button type="button" class="date-scrim" aria-label="Close date picker" (click)="close()"></button>
        <div class="calendar" data-scroll-surface role="dialog" [attr.aria-label]="ariaLabel">
          <div class="sheet-head"><strong>Choose date</strong><button type="button" aria-label="Close" (click)="close()">×</button></div>
          <header>
            <button type="button" class="month-step" (click)="move(-1)" aria-label="Previous month">←</button>
            <strong>{{ monthLabel() }}</strong>
            <button type="button" class="month-step" (click)="move(1)" aria-label="Next month">→</button>
          </header>
          <div class="weekdays" aria-hidden="true">
            @for (day of weekdays; track day) { <span>{{ day }}</span> }
          </div>
          <div class="days">
            @for (day of days(); track day.iso) {
              <button type="button" [class.outside]="!day.inMonth" [class.selected]="day.iso === value()"
                      [class.today]="day.iso === today" (click)="choose(day.iso)">{{ day.day }}</button>
            }
          </div>
          <footer>
            <button type="button" class="bare" (click)="clear()">Clear</button>
            <button type="button" class="bare" (click)="choose(today)">Today</button>
          </footer>
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .date-root { position: relative; }
    .date-trigger { display: grid; grid-template-columns: 1fr 17px; align-items: center; gap: 8px; width: 100%; min-height: 34px; padding: 6px 8px; border: 1px solid var(--line); background: var(--bg); color: var(--fg); text-align: left; letter-spacing: 0; }
    .date-trigger .placeholder { color: var(--fg-4); }
    .date-trigger svg { width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-width: 1.2; }
    .open .date-trigger { border-color: var(--fg-3); background: var(--panel-2); }
    .calendar { position: absolute; z-index: var(--z-popover); top: calc(100% + 4px); left: 0; width: 264px; padding: 9px; border: 1px solid var(--line-2); background: var(--panel); box-shadow: 0 8px 22px #0005; }
    .date-scrim, .sheet-head { display: none; }
    header { display: grid; grid-template-columns: 34px 1fr 34px; align-items: center; margin-bottom: 7px; }
    header strong { color: var(--fg-2); font: 500 var(--t-label)/1 var(--mono); letter-spacing: .08em; text-align: center; text-transform: uppercase; }
    .month-step { padding: 0; border: 0; background: transparent; }
    .weekdays, .days { display: grid; grid-template-columns: repeat(7, 1fr); }
    .weekdays span { padding: 4px 0; color: var(--fg-4); font: var(--t-micro)/1 var(--mono); text-align: center; }
    .days button { min-height: 34px; padding: 0; border-color: transparent; background: transparent; color: var(--fg-2); font-size: var(--t-label); letter-spacing: 0; }
    .days button:hover { border-color: var(--line-2); }
    .days button.outside { color: var(--fg-4); opacity: .55; }
    .days button.today { border-color: var(--fg-3); }
    .days button.selected { border-color: var(--fg); background: var(--fg); color: var(--bg); }
    footer { display: flex; justify-content: space-between; margin-top: 7px; padding-top: 7px; border-top: 1px solid var(--line); }
    footer button { font-family: var(--sans); font-size: var(--t-label); letter-spacing: 0; text-transform: none; }
    @media (max-width: 880px) {
      .date-trigger { min-height: 44px; }
      .date-scrim { display: block; position: fixed; z-index: var(--z-popover); inset: 0; width: 100%; height: 100%; padding: 0; border: 0; background: var(--modal-scrim); -webkit-backdrop-filter: var(--modal-scrim-filter); backdrop-filter: var(--modal-scrim-filter); animation: date-fade var(--motion-fast) var(--ease-out) both; }
      .calendar { position: fixed; z-index: calc(var(--z-popover) + 1); inset: auto 0 0; width: auto; padding: 0 12px calc(12px + env(safe-area-inset-bottom)); border: 0; border-top: 1px solid var(--line-2); background: var(--glass-surface); -webkit-backdrop-filter: var(--glass-filter); backdrop-filter: var(--glass-filter); box-shadow: 0 -16px 42px #0008; animation: date-up var(--motion) var(--ease-out) both; }
      .sheet-head { position: relative; display: flex; justify-content: space-between; align-items: center; min-height: 54px; margin: 0 -12px 4px; padding: 0 12px; border-bottom: 1px solid var(--line); }
      .sheet-head::before { content: ''; position: absolute; top: 6px; left: 50%; width: 32px; height: 3px; border-radius: 3px; background: var(--fg-4); transform: translateX(-50%); opacity: .6; }
      .sheet-head strong { font: 550 var(--t-data)/1 var(--sans); }
      .sheet-head button { min-width: 44px; min-height: 44px; padding: 0; border: 0; background: transparent; color: var(--fg-3); font-size: 24px; }
      .days button { min-height: 44px; }
      @keyframes date-up { from { transform: translateY(100%); } }
      @keyframes date-fade { from { opacity: 0; } }
    }
    @media (prefers-reduced-motion: reduce) { .calendar, .date-scrim { animation: none; } }
  `],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => FintoDate), multi: true }],
})
export class FintoDate implements ControlValueAccessor {
  private host = inject(ElementRef<HTMLElement>);
  readonly ariaLabel = 'Date';
  readonly weekdays = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
  readonly today = toIso(new Date());
  readonly value = signal('');
  readonly open = signal(false);
  readonly disabled = signal(false);
  readonly cursor = signal(monthStart(new Date()));
  private onChange: (value: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: unknown): void {
    const iso = typeof value === 'string' ? value : '';
    this.value.set(iso);
    if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) this.cursor.set(monthStart(new Date(`${iso}T00:00:00`)));
  }
  registerOnChange(fn: (value: string) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(value: boolean): void { this.disabled.set(value); }
  toggle(): void { if (!this.disabled()) this.open.update((value) => !value); }
  move(delta: number): void { this.cursor.update((date) => new Date(date.getFullYear(), date.getMonth() + delta, 1)); }
  monthLabel(): string { return new Intl.DateTimeFormat('en', { month: 'short', year: 'numeric' }).format(this.cursor()); }
  days(): CalendarDay[] {
    const month = this.cursor();
    const first = new Date(month.getFullYear(), month.getMonth(), 1);
    const offset = (first.getDay() + 6) % 7;
    return Array.from({ length: 42 }, (_, index) => {
      const date = new Date(month.getFullYear(), month.getMonth(), index - offset + 1);
      return { iso: toIso(date), day: date.getDate(), inMonth: date.getMonth() === month.getMonth() };
    });
  }
  choose(iso: string): void { this.value.set(iso); this.onChange(iso); this.onTouched(); this.open.set(false); }
  clear(): void { this.choose(''); }
  close(): void { this.open.set(false); this.onTouched(); }

  @HostListener('document:pointerdown', ['$event'])
  closeOutside(event: PointerEvent): void { if (!this.host.nativeElement.contains(event.target as Node)) this.open.set(false); }
  @HostListener('document:keydown.escape')
  closeEscape(): void { this.open.set(false); }
}

function monthStart(date: Date): Date { return new Date(date.getFullYear(), date.getMonth(), 1); }
function toIso(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
