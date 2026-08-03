import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { describeFilter, filterToParams } from '../../core/filter-state';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { QueryResult } from '../../core/models';

/**
 * Ask.
 *
 * The model translates the question into the same LedgerFilter the blotter uses;
 * the database answers it. Two things follow from that split, and they are the
 * reason it is built this way rather than as text-to-SQL.
 *
 * The filter comes back with the result and is rendered as chips, so a misreading
 * is visible and correctable instead of arriving as a wrong number with a
 * confident sentence attached. And because the filter is deterministic, the same
 * question gives the same answer forever — a figure that moves because a model
 * was updated underneath it is not a figure you can use.
 *
 * The model never produces the number. It produces the query.
 */
@Component({
  selector: 'app-ask',
  imports: [FormsModule, MoneyPipe, ShortDatePipe],
  templateUrl: './ask.html',
  styleUrl: './ask.css',
})
export class AskPage {
  private api = inject(Api);
  private router = inject(Router);

  readonly examples = [
    'how much did I spend on dining last quarter',
    'biggest purchases in March, excluding transfers',
    'what did I spend at supermarkets this year, by month',
    'uncategorised transactions over 1,000',
  ];

  question = signal('');
  asking = signal(false);
  result = signal<QueryResult | null>(null);
  history = signal<string[]>([]);

  ask(): void {
    const q = this.question().trim();
    if (!q || this.asking()) return;
    this.asking.set(true);
    this.result.set(null);
    this.api.ask(q).subscribe({
      next: (res) => {
        this.result.set(res);
        this.asking.set(false);
        if (res.ok) this.history.update((h) => [q, ...h.filter((x) => x !== q)].slice(0, 8));
      },
      error: (err) => {
        this.result.set({
          ok: false,
          question: q,
          error: err?.error?.detail ?? 'The query could not be run.',
        });
        this.asking.set(false);
      },
    });
  }

  use(example: string): void {
    this.question.set(example);
    this.ask();
  }

  chips(): Array<{ key: string; label: string }> {
    const f = this.result()?.filter;
    return f ? describeFilter(f) : [];
  }

  /** Hand the filter to the blotter, where it becomes editable. */
  openInBlotter(): void {
    const f = this.result()?.filter;
    if (!f) return;
    this.router.navigate(['/blotter'], { queryParams: filterToParams(f) });
  }

  confidenceBand(): 'high' | 'mid' | 'low' {
    const c = this.result()?.confidence ?? 0;
    return c >= 0.8 ? 'high' : c >= 0.55 ? 'mid' : 'low';
  }
}
