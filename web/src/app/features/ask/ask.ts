import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { describeFilter, filterToParams } from '../../core/filter-state';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { QueryResult, SummaryRow } from '../../core/models';

function requestError(error: any): string {
  const body = error?.error;
  const detail = typeof body === 'string'
    ? body
    : body?.detail ?? body?.error;
  if (typeof detail === 'string' && detail.trim()) return detail.trim();
  if (error?.status === 401) return 'Session expired. Sign in again.';
  if (error?.status === 429) return 'Analysis is rate-limited. Try again shortly.';
  if ([502, 503, 504].includes(error?.status)) return 'Analysis is unavailable.';
  return error?.status ? `Request failed (HTTP ${error.status}).` : 'Request failed.';
}

/** Read-only ledger analysis with visible tool filters. */
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
  showAllRows = signal(false);
  /** A broad query can group into hundreds of buckets; lead with the movers. */
  readonly rowCap = 12;

  rankedRows(): SummaryRow[] {
    const rows = [...(this.result()?.rows ?? [])]
      .sort((a, b) => Math.abs(b.spend.amount) - Math.abs(a.spend.amount));
    return this.showAllRows() ? rows : rows.slice(0, this.rowCap);
  }

  ask(): void {
    const q = this.question().trim();
    if (!q || this.asking()) return;
    this.asking.set(true);
    this.result.set(null);
    this.api.ask(q).subscribe({
      next: (res) => {
        this.result.set(res);
        this.showAllRows.set(false);
        this.asking.set(false);
        if (res.ok) this.history.update((h) => [q, ...h.filter((x) => x !== q)].slice(0, 8));
      },
      error: (err) => {
        this.result.set({
          ok: false,
          question: q,
          error: requestError(err),
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

  toolLabel(name: string): string {
    return name.replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
  }
}
