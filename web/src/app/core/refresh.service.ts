import { Injectable, inject, signal } from '@angular/core';
import { Api } from './api.service';

/**
 * "Fetch it again, now."
 *
 * Reads are cached for five to sixty minutes, and an installed PWA has no
 * reload button — so without an explicit path a figure can be half an hour old
 * with no way to argue with it. Clearing the cache is not enough on its own,
 * because a page that already has rows will not ask for them a second time;
 * `token` is what tells it to.
 */
@Injectable({ providedIn: 'root' })
export class Refresh {
  private api = inject(Api);

  /** Bumped on every refresh. Read it inside an effect to reload with it. */
  readonly token = signal(0);
  readonly running = signal(false);

  /** Resolves when the page that opted in has had a chance to refetch. */
  async run(): Promise<void> {
    if (this.running()) return;
    this.running.set(true);
    this.api.invalidateReads();
    this.token.update((n) => n + 1);
    // The indicator has to outlast one frame or a warm cache makes it flicker.
    await new Promise((resolve) => setTimeout(resolve, 450));
    this.running.set(false);
  }

  /** Re-issue reads after a PWA freeze without flashing the pull indicator. */
  recover(): void {
    this.api.invalidateReads();
    this.token.update((n) => n + 1);
  }
}
