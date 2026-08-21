import { Injectable, TemplateRef, signal } from '@angular/core';

/** Renders select/date panes beside the shell, not inside the scrolling pane. */
@Injectable({ providedIn: 'root' })
export class OverlayHost {
  readonly pane = signal<TemplateRef<unknown> | null>(null);

  attach(tpl: TemplateRef<unknown>): void { this.pane.set(tpl); }

  detach(tpl?: TemplateRef<unknown> | null): void {
    if (tpl && this.pane() === tpl) this.pane.set(null);
  }
}
