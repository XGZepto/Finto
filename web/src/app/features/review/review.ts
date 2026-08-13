import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { Api } from '../../core/api.service';
import { Refresh } from '../../core/refresh.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { Money } from '../../core/models';

type Queue = 'duplicates' | 'transfers' | 'installments';

/** Review queues with J/K navigation and A/R decisions. */
@Component({
  selector: 'app-review',
  imports: [FintoSkeleton, MoneyPipe, ShortDatePipe],
  templateUrl: './review.html',
  styleUrl: './review.css',
})
export class ReviewPage {
  private api = inject(Api);
  private refreshes = inject(Refresh);

  readonly queues: Array<{ id: Queue; label: string }> = [
    { id: 'duplicates', label: 'Duplicates' },
    { id: 'transfers', label: 'Transfers' },
    { id: 'installments', label: 'Instalments' },
  ];

  queue = signal<Queue>('duplicates');
  items = signal<any[]>([]);
  counts = signal<Record<string, number>>({});
  cursor = signal(0);
  loading = signal(true);
  busy = signal(false);

  current = computed(() => this.items()[this.cursor()] ?? null);

  constructor() {
    // Token starts at 0, so this arms the reload without firing one now.
    effect(() => { if (this.refreshes.token()) this.load(); });
    this.loadCounts();
    this.load();
  }

  /** Tab counts for the queues we aren't showing; `load` covers the current one. */
  loadCounts(): void {
    for (const q of this.queues) {
      if (q.id === this.queue()) continue;
      this.api.reviewQueue(q.id).subscribe({
        next: (r) => this.counts.update((c) => ({ ...c, [q.id]: r.total })),
      });
    }
  }

  select(queue: Queue): void {
    this.queue.set(queue);
    this.cursor.set(0);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.reviewQueue(this.queue()).subscribe({
      next: (r) => {
        this.items.set(r.items);
        this.counts.update((c) => ({ ...c, [this.queue()]: r.total }));
        this.cursor.set(Math.min(this.cursor(), Math.max(0, r.items.length - 1)));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  move(delta: number): void {
    const next = this.cursor() + delta;
    if (next < 0 || next >= this.items().length) return;
    this.cursor.set(next);
  }

  resolve(action: 'accept' | 'reject'): void {
    const item = this.current();
    if (!item || this.busy()) return;
    this.busy.set(true);
    this.api.resolve(this.queue(), item.id, action).subscribe({
      next: () => {
        // Drop it from the list rather than reloading, so the cursor keeps its
        // place and a batch stays a batch.
        this.items.update((list) => list.filter((i) => i.id !== item.id));
        this.counts.update((c) => ({
          ...c,
          [this.queue()]: Math.max(0, (c[this.queue()] ?? 1) - 1),
        }));
        this.cursor.set(Math.min(this.cursor(), Math.max(0, this.items().length - 1)));
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  @HostListener('window:keydown', ['$event'])
  onKey(event: KeyboardEvent): void {
    const target = event.target as HTMLElement;
    if (target?.matches('input, textarea, select')) return;
    switch (event.key.toLowerCase()) {
      case 'j': this.move(1); break;
      case 'k': this.move(-1); break;
      case 'a': this.resolve('accept'); break;
      case 'r': this.resolve('reject'); break;
      default: return;
    }
    event.preventDefault();
  }

  // --- Shaping -------------------------------------------------------------

  asMoney(amount: number, currency: string): Money {
    return { amount, currency };
  }

  /** The two sides of a candidate, whatever the queue calls them. */
  sides(item: any): Array<{ role: string; id: string; date: string; account: string; description: string; amount: Money }> {
    if (this.queue() === 'duplicates') {
      return [
        { role: 'keep', id: item.keep_id, date: item.keep_date, account: item.keep_account,
          description: item.keep_desc, amount: this.asMoney(item.keep_amount, item.keep_currency) },
        { role: 'drop', id: item.dupe_id, date: item.dupe_date, account: item.dupe_account,
          description: item.dupe_desc, amount: this.asMoney(item.dupe_amount, item.dupe_currency) },
      ];
    }
    if (this.queue() === 'transfers') {
      return [
        { role: 'out', id: item.out_id, date: item.out_date, account: item.out_account,
          description: item.out_desc, amount: this.asMoney(item.out_amount, item.out_currency) },
        { role: 'in', id: item.in_id, date: item.in_date, account: item.in_account,
          description: item.in_desc, amount: this.asMoney(item.in_amount, item.in_currency) },
      ];
    }
    return [];
  }

  scoreBand(score: number): 'high' | 'mid' | 'low' {
    if (score >= 0.85) return 'high';
    if (score >= 0.7) return 'mid';
    return 'low';
  }

  /** What accepting this candidate actually does to the ledger. */
  consequence(): string {
    switch (this.queue()) {
      case 'duplicates':
        return 'Hides the second row as a duplicate. Reversible.';
      case 'transfers':
        return 'Links both legs into one transfer.';
      default:
        return 'Groups these charges into one instalment plan.';
    }
  }
}
