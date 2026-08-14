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
        <div class="select-menu" data-scroll-surface role="listbox" [id]="id + '-list'" [attr.aria-label]="ariaLabel">
          <div class="sheet-head">
            <strong>{{ ariaLabel }}</strong>
            <button type="button" aria-label="Close" (click)="close()">×</button>
          </div>
          @if (options.length >= 8) {
            <label class="select-search">
              <span class="search-icon" aria-hidden="true"></span>
              <input type="search" [value]="query()" (input)="onSearch($event)"
                     [attr.placeholder]="isCurrencyList() ? 'Search currencies' : 'Search options'"
                     [attr.aria-label]="isCurrencyList() ? 'Search currencies' : 'Search options'" />
            </label>
          }
          @for (option of filteredOptions(); track optionValue(option); let index = $index) {
            <button type="button" role="option" class="select-option" [id]="id + '-opt-' + index"
                    [class.active]="index === activeIndex()"
                    [class.selected]="optionValue(option) === value()"
                    [attr.aria-selected]="optionValue(option) === value()"
                    (mouseenter)="activeIndex.set(index)" (click)="choose(option)">
              @if (isCurrency(option)) { <span class="option-code mono">{{ optionLabel(option) }}</span> }
              <span class="option-copy">
                <strong>{{ isCurrency(option) ? currencyName(optionLabel(option)) : displayLabel(option) }}</strong>
              </span>
              <span class="selection-mark" aria-hidden="true">@if (optionValue(option) === value()) { ✓ }</span>
            </button>
          } @empty {
            <p class="no-options">No matching options</p>
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
      gap: 10px; width: 100%; min-height: 36px; padding: 7px 10px;
      background: var(--panel-2); border: 1px solid var(--line-2); color: var(--fg);
      text-align: left; letter-spacing: 0;
    }
    .select-trigger span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .select-trigger i { width: 7px; height: 7px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: rotate(45deg) translateY(-2px); transition: transform var(--motion-fast) var(--ease); }
    .select-trigger:hover { border-color: var(--fg-3); background: var(--panel-3); }
    .open .select-trigger { border-color: var(--fg-3); background: var(--panel-3); }
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
      display: grid; grid-template-columns: minmax(0, 1fr) 24px; gap: 12px; align-items: center;
      width: 100%; min-height: 38px; padding: 7px 9px; border: 0;
      background: transparent; color: var(--fg-2); text-align: left; letter-spacing: 0;
    }
    .select-option:has(.option-code) { grid-template-columns: 44px minmax(0, 1fr) 24px; }
    .option-code { display: grid; place-items: center; width: 42px; height: 30px; background: var(--panel-3); border: 1px solid var(--line); color: var(--fg-2); font-size: var(--t-label); }
    .option-copy { display: flex; flex-direction: column; min-width: 0; overflow: hidden; }
    .option-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--fg); font: 520 var(--t-meta)/1.2 var(--sans); }
    /* A trailing check is the platform convention for one selected row. A
       boxed mark reads as a checkbox and falsely promises multi-selection. */
    .selection-mark { display: grid; place-items: center; width: 22px; height: 22px; color: transparent; font-size: var(--t-data); font-weight: 650; }
    .select-option.active { background: var(--panel-3); color: var(--fg); }
    .select-option.selected { color: var(--fg); }
    .select-option.selected .selection-mark { color: var(--fg); }
    .select-search { display: flex; align-items: center; gap: 8px; margin: 4px; padding: 0 10px; border: 1px solid var(--line); background: var(--panel-2); color: var(--fg-4); }
    .select-search input { width: 100%; min-height: 38px; padding: 0; border: 0; outline: 0; background: transparent; color: var(--fg); font: var(--t-meta)/1 var(--sans); }
    .select-search input::placeholder { color: var(--fg-4); }
    .search-icon { position: relative; flex: none; width: 14px; height: 14px; border: 1.5px solid currentColor; border-radius: 50%; }
    .search-icon::after { content: ''; position: absolute; right: -4px; bottom: -2px; width: 5px; border-top: 1.5px solid currentColor; transform: rotate(45deg); transform-origin: left; }
    .no-options { margin: 18px 10px; color: var(--fg-4); font-size: var(--t-meta); text-align: center; }
    @media (max-width: 880px) {
      .select-trigger, .select-option { min-height: 44px; }
      .select-scrim { display: block; position: fixed; z-index: var(--z-popover); inset: 0; width: 100%; height: 100%; padding: 0; border: 0; background: var(--modal-scrim); -webkit-backdrop-filter: var(--modal-scrim-filter); backdrop-filter: var(--modal-scrim-filter); animation: sheet-fade var(--motion-fast) var(--ease-out) both; }
      .select-menu, .up .select-menu {
        position: fixed; z-index: calc(var(--z-popover) + 1); inset: auto 0 0; width: 100%; max-width: none;
        max-height: min(76dvh, 660px); padding: 0 14px calc(14px + env(safe-area-inset-bottom));
        border: 0; border-top: 1px solid var(--line-2); background: var(--glass-surface);
        -webkit-backdrop-filter: var(--glass-filter); backdrop-filter: var(--glass-filter);
        box-shadow: 0 -16px 42px #0008; animation: sheet-up var(--motion) var(--ease-out) both;
      }
      .sheet-head { position: sticky; z-index: 2; top: 0; display: flex; justify-content: space-between; align-items: center; min-height: 68px; margin: 0 -14px; padding: 10px 14px 8px; border-bottom: 1px solid var(--line); background: var(--glass-strong); -webkit-backdrop-filter: var(--glass-filter); backdrop-filter: var(--glass-filter); }
      .sheet-head::before { content: ''; position: absolute; top: 6px; left: 50%; width: 32px; height: 3px; border-radius: 3px; background: var(--fg-4); transform: translateX(-50%); opacity: .6; }
      .sheet-head strong { font: 600 var(--t-data)/1 var(--sans); }
      .sheet-head button { min-width: 44px; min-height: 44px; padding: 0; border: 0; background: transparent; color: var(--fg-3); font-size: 24px; }
      .select-search { position: sticky; z-index: 1; top: 68px; margin: 10px 0 6px; background: var(--panel-2); }
      .select-search input { min-height: 44px; }
      .select-option { min-height: 56px; padding-inline: 4px; border-bottom: 1px solid var(--line); }
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
  query = signal('');
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
    return selected === undefined ? this.placeholder : this.displayLabel(selected);
  }

  displayLabel(option: unknown): string {
    const label = this.optionLabel(option);
    if (this.isCurrency(option) || (option != null && typeof option === 'object')) return label;
    const words = label.replace(/[_-]+/g, ' ');
    return words ? words.charAt(0).toLocaleUpperCase() + words.slice(1) : words;
  }

  isCurrency(option: unknown): boolean {
    return /^[A-Z]{3}$/.test(this.optionLabel(option));
  }

  isCurrencyList(): boolean {
    return this.options.length > 0 && this.options.every((option) => this.isCurrency(option));
  }

  currencyName(code: string): string {
    try { return new Intl.DisplayNames([document.documentElement.lang || 'en'], { type: 'currency' }).of(code) ?? code; }
    catch { return code; }
  }

  filteredOptions(): readonly unknown[] {
    const query = this.query().trim().toLocaleLowerCase();
    if (!query) return this.options;
    return this.options.filter((option) =>
      `${this.optionLabel(option)} ${this.isCurrency(option) ? this.currencyName(this.optionLabel(option)) : ''}`
        .toLocaleLowerCase().includes(query));
  }

  onSearch(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
    this.activeIndex.set(0);
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
    this.query.set('');
    const selected = this.filteredOptions().findIndex((option) => this.optionValue(option) === this.value());
    this.activeIndex.set(Math.max(0, selected));
  }

  choose(option: unknown): void {
    const value = this.optionValue(option);
    this.value.set(value);
    this.onChange(value);
    this.onTouched();
    this.open.set(false);
    this.query.set('');
  }

  close(): void { this.open.set(false); this.query.set(''); this.onTouched(); }

  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') { this.open.set(false); return; }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      if (!this.open()) this.open.set(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      const count = this.filteredOptions().length;
      if (count) this.activeIndex.set((this.activeIndex() + step + count) % count);
      return;
    }
    if ((event.key === 'Enter' || event.key === ' ') && this.open()) {
      event.preventDefault();
      const option = this.filteredOptions()[this.activeIndex()];
      if (option !== undefined) this.choose(option);
    }
  }

  @HostListener('document:pointerdown', ['$event'])
  closeOutside(event: PointerEvent): void {
    if (!this.host.nativeElement.contains(event.target as Node)) this.open.set(false);
  }
}
