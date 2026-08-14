import { Component, ElementRef, OnDestroy, inject, signal } from '@angular/core';
import { Refresh } from '../core/refresh.service';
import { scrollPane } from '../core/scroll';

/** Travel, in px, at which the gesture arms. */
const THRESHOLD = 72;
/** Past this the indicator stops moving, so a long drag cannot fling it away. */
const MAX = 108;

/**
 * Pull to refresh.
 *
 * The shell pins the viewport and scrolls an inner pane, and both have
 * `overscroll-behavior` set so the page cannot rubber-band — which is what makes
 * it feel like an app rather than a web page, and also what removes the
 * browser's own pull-to-refresh. This puts it back on our terms.
 *
 * Resistance is exponential rather than linear: the sheet tracks your thumb
 * closely at first and then visibly stiffens, so the arming point can be felt
 * instead of guessed at.
 */
@Component({
  selector: 'finto-pull-to-refresh',
  template: `
    <div class="ptr" [class.armed]="armed()" [class.running]="refresh.running()"
         [style.transform]="'translateY(' + offset() + 'px)'"
         [style.opacity]="progress()"
         role="status"
         [attr.aria-label]="refresh.running() ? 'Refreshing' : null">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <circle class="track" cx="12" cy="12" r="9" />
        <circle class="arc" cx="12" cy="12" r="9"
                [style.stroke-dasharray]="dash()" />
      </svg>
    </div>
  `,
  styles: [`
    :host { display: block; position: absolute; top: 0; left: 0; right: 0; z-index: var(--z-pull); pointer-events: none; }
    .ptr {
      display: grid;
      place-items: center;
      width: 32px;
      height: 32px;
      margin: 0 auto;
      /* Parked above the pane's top edge; the pull is what brings it down. */
      margin-top: -40px;
      border: 1px solid var(--line-strong);
      background: var(--panel);
      color: var(--fg-3);
    }
    /* While dragging the offset is the thumb's, so following it must not lag.
       The snap back after release is the only part that animates. */
    .ptr:not(.dragging) { transition: transform var(--motion) var(--ease-out), opacity var(--motion-fast) linear; }
    .ptr svg { width: 18px; height: 18px; overflow: visible; }
    circle { fill: none; stroke-width: 2.5; transform-origin: 50% 50%; transform: rotate(-90deg); }
    .track { stroke: var(--line-2); }
    .arc { stroke: var(--fg-3); stroke-linecap: square; }
    .armed { border-color: var(--fg-3); color: var(--fg); }
    .armed .arc { stroke: var(--fg); }
    .running .arc { stroke: var(--fg); stroke-dasharray: 14 43 !important; animation: ptr-spin .7s linear infinite; }
    @keyframes ptr-spin { to { transform: rotate(270deg); } }
    @media (prefers-reduced-motion: reduce) {
      .ptr { transition: none; }
      .running .arc { animation: none; }
    }
  `],
})
export class PullToRefresh implements OnDestroy {
  refresh = inject(Refresh);
  private host = inject(ElementRef<HTMLElement>);

  offset = signal(0);
  private startY = 0;
  private tracking = false;

  /** 0 → 1 as the pull approaches the arming threshold. */
  progress = () => Math.min(1, this.offset() / THRESHOLD);
  armed = () => this.offset() >= THRESHOLD;
  /** Circumference is 2πr ≈ 56.5; the arc draws in step with the pull. */
  dash = () => `${this.progress() * 56.5} 56.5`;

  private pane: HTMLElement | null = null;

  constructor() {
    // Deferred: the pane is a sibling that may not exist when this constructs.
    queueMicrotask(() => {
      this.pane = scrollPane();
      this.pane?.addEventListener('touchstart', this.onStart, { passive: true });
      this.pane?.addEventListener('touchmove', this.onMove, { passive: false });
      this.pane?.addEventListener('touchend', this.onEnd, { passive: true });
      this.pane?.addEventListener('touchcancel', this.onEnd, { passive: true });
    });
  }

  ngOnDestroy(): void {
    this.pane?.removeEventListener('touchstart', this.onStart);
    this.pane?.removeEventListener('touchmove', this.onMove as EventListener);
    this.pane?.removeEventListener('touchend', this.onEnd);
    this.pane?.removeEventListener('touchcancel', this.onEnd);
  }

  private onStart = (event: TouchEvent): void => {
    // Only from a resting top. Starting mid-scroll would hijack a flick back up.
    if (this.refresh.running() || (this.pane?.scrollTop ?? 0) > 0) return;
    const origin = event.target instanceof Element ? event.target : null;
    // A modal surface owns its vertical gesture, including a downward drag at
    // its own top edge. Refresh belongs only to bare page content.
    if (origin?.closest('[data-scroll-surface], [role="dialog"], [role="listbox"], [aria-modal="true"], .amount-options')) return;
    this.startY = event.touches[0].clientY;
    this.tracking = true;
  };

  private onMove = (event: TouchEvent): void => {
    if (!this.tracking) return;
    const delta = event.touches[0].clientY - this.startY;
    if (delta <= 0) {
      // Turned into an upward scroll: hand the gesture back to the pane.
      this.tracking = false;
      this.offset.set(0);
      return;
    }
    // Stop the pane treating the same drag as a scroll.
    event.preventDefault();
    this.indicator?.classList.add('dragging');
    this.offset.set(MAX * (1 - Math.exp(-delta / MAX)));
  };

  private onEnd = (): void => {
    if (!this.tracking) return;
    this.tracking = false;
    this.indicator?.classList.remove('dragging');
    if (!this.armed()) {
      this.offset.set(0);
      return;
    }
    // Hold at the threshold while the work happens, then retract.
    this.offset.set(THRESHOLD);
    void this.refresh.run().then(() => this.offset.set(0));
  };

  private get indicator(): HTMLElement | null {
    return this.host.nativeElement.querySelector('.ptr');
  }
}
