import {
  Component, ElementRef, HostListener, Input, forwardRef, inject, signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

let instances = 0;

@Component({
  selector: 'finto-select',
  host: { '[class.sheet-open]': 'open()' },
  template: `
    <div class="select-root" [class.open]="open()" [class.up]="dropUp()">
      <button type="button" class="select-trigger" role="combobox"
              [disabled]="disabled()" [attr.aria-label]="ariaLabel"
              [attr.aria-expanded]="open()" aria-haspopup="listbox"
              [attr.aria-controls]="id + '-list'"
              [attr.aria-activedescendant]="open() ? id + '-opt-' + activeIndex() : null"
              (click)="toggle()" (keydown)="onKeydown($event)">
        <span>{{ selectedLabel() }}</span><i aria-hidden="true"></i>
      </button>
      @if (open()) {
        <button type="button" class="select-scrim" aria-label="Close {{ ariaLabel }}" (click)="close()"></button>
        <div class="select-menu" role="listbox" [id]="id + '-list'" [attr.aria-label]="ariaLabel">
          <div class="sheet-head"><strong>{{ ariaLabel }}</strong><button type="button" aria-label="Close" (click)="close()">×</button></div>
          @for (option of options; track optionValue(option); let index = $index) {
            <button type="button" role="option" class="select-option" [id]="id + '-opt-' + index"
                    [class.active]="index === activeIndex()"
                    [class.selected]="optionValue(option) === value()"
                    [attr.aria-selected]="optionValue(option) === value()"
                    (mouseenter)="activeIndex.set(index)" (click)="choose(option)">
              <span>{{ optionLabel(option) }}</span>
              @if (optionValue(option) === value()) { <b aria-hidden="true">✓</b> }
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; }
    .select-root { position: relative; min-width: 0; }
    .select-trigger {
      display: grid; grid-template-columns: minmax(0, 1fr) 12px; align-items: center;
      gap: 10px; width: 100%; min-height: 34px; padding: 6px 9px;
      background: var(--bg); border: 1px solid var(--line-strong); color: var(--fg);
      text-align: left; letter-spacing: 0;
    }
    .select-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .select-trigger i { width: 7px; height: 7px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(45deg) translateY(-2px); transition: transform var(--motion-fast) var(--ease); }
    .open .select-trigger { border-color: var(--fg-3); background: var(--panel-2); }
    .open .select-trigger i { transform: rotate(225deg) translate(-2px, -1px); }
    .select-menu {
      position: absolute; z-index: var(--z-popover); top: calc(100% + 4px); left: 0; min-width: 100%;
      width: max-content; max-width: min(320px, 88vw); max-height: 260px; overflow-y: auto;
      padding: 4px; border: 1px solid var(--line-2); background: var(--panel);
      box-shadow: 0 8px 22px #0005;
    }
    .select-scrim, .sheet-head { display: none; }
    /* Opens upward near the foot of the screen, so it never lands under the
       fixed mobile nav (which reads as the nav vanishing behind it). */
    .up .select-menu { top: auto; bottom: calc(100% + 4px); }
    .select-option {
      display: grid; grid-template-columns: minmax(0, 1fr) 14px; gap: 12px;
      width: 100%; min-height: 34px; padding: 6px 8px; border: 0;
      background: transparent; color: var(--fg-2); text-align: left; letter-spacing: 0;
    }
    .select-option span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .select-option.active { background: var(--panel-3); color: var(--fg); }
    .select-option.selected { color: var(--fg); }
    .select-option b { color: var(--pos); font-weight: 500; }
    @media (max-width: 880px) {
      .select-trigger, .select-option { min-height: 44px; }
      .select-scrim { display: block; position: fixed; z-index: var(--z-popover); inset: 0; width: 100%; height: 100%; padding: 0; border: 0; background: #000a; animation: sheet-fade var(--motion-fast) var(--ease-out) both; }
      .select-menu, .up .select-menu {
        position: fixed; z-index: calc(var(--z-popover) + 1); inset: auto 0 0; width: 100%; max-width: none;
        max-height: min(70dvh, 620px); padding: 0 12px calc(12px + env(safe-area-inset-bottom));
        border: 0; border-top: 1px solid var(--line-2); background: var(--panel);
        box-shadow: 0 -16px 42px #0008; animation: sheet-up var(--motion-base) var(--ease-out) both;
      }
      .sheet-head { position: sticky; z-index: 1; top: 0; display: flex; justify-content: space-between; align-items: center; min-height: 54px; margin: 0 -12px 4px; padding: 0 12px; border-bottom: 1px solid var(--line); background: var(--panel); }
      .sheet-head::before { content: ''; position: absolute; top: 6px; left: 50%; width: 32px; height: 3px; border-radius: 3px; background: var(--fg-4); transform: translateX(-50%); opacity: .6; }
      .sheet-head strong { font: 550 var(--t-data)/1 var(--sans); }
      .sheet-head button { min-width: 44px; min-height: 44px; padding: 0; border: 0; background: transparent; color: var(--fg-3); font-size: 24px; }
      .select-option { padding-inline: 4px; border-bottom: 1px solid var(--line); }
      .select-option:last-child { border-bottom: 0; }
      @keyframes sheet-up { from { transform: translateY(100%); } }
      @keyframes sheet-fade { from { opacity: 0; } }
    }
    @media (prefers-reduced-motion: reduce) { .select-trigger i { transition: none; } .select-menu, .select-scrim { animation: none; } }
  `],
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => FintoSelect),
    multi: true,
  }],
})
export class FintoSelect implements ControlValueAccessor {
  private host = inject(ElementRef<HTMLElement>);
  @Input() options: readonly unknown[] = [];
  @Input() valueKey = '';
  @Input() labelKey = '';
  @Input() placeholder = 'Select';
  @Input() ariaLabel = 'Select';

  /* The active option is announced through the trigger, which never gives up
     focus, so both ends of that reference need a stable id. */
  readonly id = `finto-select-${++instances}`;

  value = signal('');
  open = signal(false);
  dropUp = signal(false);
  disabled = signal(false);
  activeIndex = signal(0);
  private onChange: (value: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: unknown): void { this.value.set(value == null ? '' : String(value)); }
  registerOnChange(fn: (value: string) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(disabled: boolean): void { this.disabled.set(disabled); }

  optionValue(option: unknown): string {
    if (option != null && typeof option === 'object' && this.valueKey) {
      return String((option as Record<string, unknown>)[this.valueKey] ?? '');
    }
    return String(option ?? '');
  }

  optionLabel(option: unknown): string {
    if (option != null && typeof option === 'object') {
      const key = this.labelKey || this.valueKey;
      return String((option as Record<string, unknown>)[key] ?? '');
    }
    return String(option ?? '');
  }

  selectedLabel(): string {
    const selected = this.options.find((option) => this.optionValue(option) === this.value());
    return selected === undefined ? this.placeholder : this.optionLabel(selected);
  }

  toggle(): void {
    if (this.disabled()) return;
    this.open.update((open) => !open);
    if (this.open()) {
      const rect = this.host.nativeElement.getBoundingClientRect();
      const below = window.innerHeight - rect.bottom;
      // Flip up when the space below can't hold the menu and there's more above.
      this.dropUp.set(below < 280 && rect.top > below);
    }
    const selected = this.options.findIndex((option) => this.optionValue(option) === this.value());
    this.activeIndex.set(Math.max(0, selected));
  }

  choose(option: unknown): void {
    const value = this.optionValue(option);
    this.value.set(value);
    this.onChange(value);
    this.onTouched();
    this.open.set(false);
  }

  close(): void { this.open.set(false); this.onTouched(); }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') { this.open.set(false); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!this.open()) this.open.set(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      const count = this.options.length;
      if (count) this.activeIndex.set((this.activeIndex() + step + count) % count);
      return;
    }
    if ((event.key === 'Enter' || event.key === ' ') && this.open()) {
      event.preventDefault();
      const option = this.options[this.activeIndex()];
      if (option !== undefined) this.choose(option);
    }
  }

  @HostListener('document:pointerdown', ['$event'])
  closeOutside(event: PointerEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
}
