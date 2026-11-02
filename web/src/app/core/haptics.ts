import { Injectable, signal } from '@angular/core';

export type HapticKind = 'selection' | 'impact' | 'success' | 'warning' | 'error';

/** Vibration patterns, in ms. Short: a ledger is read, not played. */
export const PATTERNS: Record<HapticKind, number | number[]> = {
  selection: 6,
  impact: 12,
  success: [10, 60, 14],
  warning: [18, 70, 18],
  error: [26, 50, 26, 50, 26],
};

const STORAGE_KEY = 'finto.haptics';

/**
 * Touch feedback for the installed app.
 *
 * Android exposes `navigator.vibrate`. iOS Safari does not, but since 17.4 it
 * plays the system selection tick when a `<input type="checkbox" switch>`
 * toggles from a user gesture, so a hidden one stands in. That path has one
 * strength only, which is why every kind maps to a single tick there.
 *
 * Feedback marks a committed change of state (a tab, a period, a refresh
 * arming), never a plain press, or it stops meaning anything.
 */
@Injectable({ providedIn: 'root' })
export class Haptics {
  readonly enabled = signal(readEnabled());
  private iosSwitch: HTMLLabelElement | null = null;

  /** Whether this device can produce any feedback at all. */
  readonly supported = typeof navigator !== 'undefined'
    && (typeof navigator.vibrate === 'function' || isAppleTouch());

  setEnabled(on: boolean): void {
    this.enabled.set(on);
    try { localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off'); } catch { /* private mode */ }
    if (on) this.fire('success');
  }

  fire(kind: HapticKind = 'selection'): void {
    if (!this.enabled()) return;
    if (typeof navigator.vibrate === 'function') {
      try { navigator.vibrate(PATTERNS[kind]); } catch { /* blocked without activation */ }
      return;
    }
    if (isAppleTouch()) this.tickIos();
  }

  private tickIos(): void {
    if (!this.iosSwitch) {
      const label = document.createElement('label');
      label.setAttribute('aria-hidden', 'true');
      label.dataset['haptic'] = 'ios';
      label.style.cssText = 'position:fixed;width:1px;height:1px;overflow:hidden;opacity:0;pointer-events:none;clip-path:inset(50%)';
      const input = document.createElement('input');
      input.type = 'checkbox';
      input.setAttribute('switch', '');
      input.tabIndex = -1;
      label.appendChild(input);
      document.body.appendChild(label);
      this.iosSwitch = label;
    }
    this.iosSwitch.click();
  }
}

function readEnabled(): boolean {
  try { return localStorage.getItem(STORAGE_KEY) !== 'off'; } catch { return true; }
}

function isAppleTouch(): boolean {
  if (typeof navigator === 'undefined') return false;
  // iPadOS reports itself as a Mac; touch points give it away.
  return /iPhone|iPad|iPod/.test(navigator.userAgent)
    || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
}

/**
 * What a tap on `target` should feel like, or null for nothing.
 *
 * Read in the capture phase, before the click handler re-renders, so `.on`
 * still describes the state the tap is leaving.
 */
export function hapticFor(target: Element | null): HapticKind | null {
  if (!target) return null;
  if (target.closest('.mobile-nav a:not(.active)')) return 'selection';
  if (target.closest('.pill:not(.on), .seg button:not(.on)')) return 'selection';
  if (target.closest('input[type="checkbox"], [role="option"], [role="tab"]:not([aria-selected="true"])')) return 'selection';
  if (target.closest('button.primary:not(:disabled)')) return 'impact';
  return null;
}
