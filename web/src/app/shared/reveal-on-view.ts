import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, signal } from '@angular/core';
import { scrollPane } from '../core/scroll';

/** Starts explanatory motion when the visual actually enters the app's scroll
 * pane. It reveals once: replaying a chart while scrolling back is distraction,
 * not information. */
@Directive({
  selector: '[fintoReveal]',
  host: { '[class.finto-in-view]': 'visible()' },
})
export class RevealOnView implements AfterViewInit, OnDestroy {
  private host = inject(ElementRef<HTMLElement>);
  private observer?: IntersectionObserver;
  visible = signal(false);

  ngAfterViewInit(): void {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches || !('IntersectionObserver' in window)) {
      this.visible.set(true);
      return;
    }
    this.observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return;
      this.visible.set(true);
      this.observer?.disconnect();
    }, { root: scrollPane(), threshold: 0.14, rootMargin: '0px 0px -6% 0px' });
    this.observer.observe(this.host.nativeElement);
  }

  ngOnDestroy(): void { this.observer?.disconnect(); }
}
