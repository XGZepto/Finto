import { Component, ElementRef, NgZone, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { StreamEvent, streamAsk } from '../../core/ask-stream';
import { describeFilter, filterToParams } from '../../core/filter-state';
import { Haptics } from '../../core/haptics';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { LedgerFilter, QueryResult, SummaryRow } from '../../core/models';
import { Bar, FintoBars } from '../../shared/finto-bars';
import { AnswerBlock, parseAnswerMarkdown } from './answer-format';

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

interface Step { id: string; label: string; state: 'running' | 'done' | 'error' }

/** One question and everything that came back for it. */
interface Turn {
  id: number;
  question: string;
  state: 'asking' | 'done' | 'error';
  steps: Step[];
  /** The answer as it streams; replaced by the final text on `done`. */
  text: string;
  /** Figures as the tools return them, then the complete result. */
  data: Partial<QueryResult>;
  error?: string;
  startedAt: number;
  seconds?: number;
}

const STORE = 'finto.ask.thread';
const MONTH = /^\d{4}-\d{2}$/;

/**
 * Ask: a conversation with the ledger.
 *
 * Each answer streams in: the steps the model takes, the figures each step
 * returns (shown as soon as they exist, before any prose), then the answer as
 * it is written. Follow-ups carry the last few turns, so "and September?"
 * means what it says. The thread survives navigating away for the session.
 */
@Component({
  selector: 'app-ask',
  imports: [FormsModule, MoneyPipe, ShortDatePipe, FintoBars],
  templateUrl: './ask.html',
  styleUrl: './ask.css',
})
export class AskPage {
  private api = inject(Api);
  private router = inject(Router);
  private haptics = inject(Haptics);
  private zone = inject(NgZone);
  private input = viewChild<ElementRef<HTMLInputElement>>('questionInput');

  readonly examples = [
    'how much did I spend on dining last month',
    'what did I spend at supermarkets this year, by month',
    'biggest purchases in October',
    'uncategorised transactions over 1,000',
  ];

  question = signal('');
  turns = signal<Turn[]>(this.restore());
  asking = computed(() => this.turns().some((t) => t.state === 'asking'));
  private seq = this.turns().reduce((n, t) => Math.max(n, t.id), 0);
  private abort: AbortController | null = null;
  /** A broad query can group into hundreds of buckets; lead with the movers. */
  readonly rowCap = 8;
  expanded = signal<Set<number>>(new Set());

  ask(text?: string): void {
    const q = (text ?? this.question()).trim();
    if (!q || this.asking()) return;
    const history = this.turns()
      .filter((t) => t.state === 'done' && t.text)
      .slice(-3)
      .map((t) => ({ question: t.question, answer: t.text.slice(0, 800) }));
    const turn: Turn = {
      id: ++this.seq, question: q, state: 'asking', steps: [], text: '', data: {},
      startedAt: Date.now(),
    };
    this.turns.update((list) => [...list, turn]);
    this.question.set('');
    this.reveal(turn.id);

    this.abort = new AbortController();
    let opened = false;
    streamAsk({ question: q, history }, (event) => {
      opened = true;
      this.zone.run(() => this.apply(turn.id, event));
    }, this.abort.signal).then(() => {
      // A stream that closed without its `done` (a dropped connection) still
      // ends the turn, with what arrived.
      if (this.turn(turn.id)?.state === 'asking') {
        this.zone.run(() => this.finish(turn.id, null, 'The answer was cut off. Try again.'));
      }
    }).catch((error) => {
      if (error?.name === 'AbortError') return;
      if (opened) {
        this.zone.run(() => this.finish(turn.id, null, 'The answer was cut off. Try again.'));
        return;
      }
      // No stream at all (an older server, a proxy): ask the plain way.
      this.api.ask(q).subscribe({
        next: (result) => this.finish(turn.id, result),
        error: (err) => this.finish(turn.id, null, requestError(err)),
      });
    });
  }

  stop(): void {
    this.abort?.abort();
    const current = this.turns().find((t) => t.state === 'asking');
    if (current) this.finish(current.id, null, 'Stopped.');
  }

  clear(): void {
    this.stop();
    this.turns.set([]);
    this.persist();
    this.input()?.nativeElement.focus();
  }

  private apply(id: number, { event, data }: StreamEvent): void {
    switch (event) {
      case 'tool':
        this.patch(id, (t) => {
          const steps = t.steps.filter((s) => s.id !== data.id);
          const at = t.steps.findIndex((s) => s.id === data.id);
          const step: Step = { id: data.id, label: data.label, state: data.state };
          steps.splice(at === -1 ? steps.length : at, 0, step);
          return { ...t, steps };
        });
        break;
      case 'data':
        this.patch(id, (t) => ({ ...t, data: { ...t.data, ...data } }));
        break;
      case 'text':
        this.patch(id, (t) => ({ ...t, text: t.text + (data.delta ?? '') }));
        break;
      case 'discard':
        this.patch(id, (t) => ({ ...t, text: '' }));
        break;
      case 'done':
        this.finish(id, data as QueryResult);
        break;
    }
  }

  private finish(id: number, result: QueryResult | null, error?: string): void {
    const failed = !result || !result.ok;
    this.patch(id, (t) => ({
      ...t,
      state: failed ? 'error' : 'done',
      error: failed ? (result?.error ?? error ?? 'Ask could not finish.') : undefined,
      text: result?.ok ? (result.answer ?? t.text) : t.text,
      data: result?.ok ? { ...t.data, ...result } : t.data,
      steps: t.steps.map((s) => s.state === 'running' ? { ...s, state: 'done' } : s),
      seconds: (Date.now() - t.startedAt) / 1000,
    }));
    this.abort = null;
    this.haptics.fire(failed ? 'error' : 'success');
    this.persist();
  }

  private turn(id: number): Turn | undefined {
    return this.turns().find((t) => t.id === id);
  }

  private patch(id: number, change: (t: Turn) => Turn): void {
    this.turns.update((list) => list.map((t) => (t.id === id ? change(t) : t)));
  }

  /** Bring a new question to the top of the view, so its answer grows below it. */
  private reveal(id: number): void {
    requestAnimationFrame(() => document.getElementById(`turn-${id}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  private restore(): Turn[] {
    try {
      const saved = JSON.parse(sessionStorage.getItem(STORE) ?? '[]') as Turn[];
      return saved.filter((t) => t.state !== 'asking');
    } catch {
      return [];
    }
  }

  private persist(): void {
    try {
      const done = this.turns().filter((t) => t.state !== 'asking').slice(-12);
      sessionStorage.setItem(STORE, JSON.stringify(done));
    } catch { /* storage full or unavailable: the thread just won't survive */ }
  }

  // --- Rendering ------------------------------------------------------------

  blocks(turn: Turn): AnswerBlock[] {
    return parseAnswerMarkdown(turn.text);
  }

  chips(filter: LedgerFilter | undefined): Array<{ key: string; label: string }> {
    return filter ? describeFilter(filter) : [];
  }

  /** Hand the filter to the blotter, where it becomes editable. */
  openInBlotter(filter: LedgerFilter | undefined): void {
    if (!filter) return;
    this.router.navigate(['/blotter'], { queryParams: filterToParams(filter) });
  }

  /** A month column opens that month's rows under the same filter. */
  openMonth(turn: Turn, month: string): void {
    const [y, m] = month.split('-').map(Number);
    const last = new Date(y, m, 0).getDate();
    this.openInBlotter({
      ...(turn.data.filter ?? {}),
      from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}`,
    });
  }

  rankedRows(turn: Turn): SummaryRow[] {
    const rows = [...(turn.data.rows ?? [])];
    if (this.isSeries(turn)) {
      rows.sort((a, b) => a.bucket.localeCompare(b.bucket) || a.currency.localeCompare(b.currency));
    } else {
      rows.sort((a, b) => Math.abs(b.spend.amount) - Math.abs(a.spend.amount));
    }
    return this.expanded().has(turn.id) ? rows : rows.slice(0, this.rowCap);
  }

  toggleRows(turn: Turn): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(turn.id)) next.delete(turn.id); else next.add(turn.id);
      return next;
    });
  }

  /** Monthly buckets draw as columns, one chart per currency. */
  isSeries(turn: Turn): boolean {
    const rows = turn.data.rows ?? [];
    return rows.length > 1 && rows.every((r) => MONTH.test(r.bucket));
  }

  series(turn: Turn): Array<{ currency: string; bars: Bar[]; total: number }> {
    const byCurrency = new Map<string, Map<string, number>>();
    for (const row of turn.data.rows ?? []) {
      const months = byCurrency.get(row.currency) ?? new Map<string, number>();
      months.set(row.bucket, (months.get(row.bucket) ?? 0) + Math.abs(row.spend.amount));
      byCurrency.set(row.currency, months);
    }
    return [...byCurrency.entries()]
      .map(([currency, months]) => {
        const sorted = [...months.entries()].sort(([a], [b]) => a.localeCompare(b));
        return {
          currency,
          total: sorted.reduce((sum, [, v]) => sum + v, 0),
          bars: sorted.map(([month, value]) => ({
            label: month,
            value,
            sub: `${currency} ${(value / 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`,
          })),
        };
      })
      .filter((s) => s.total > 0)
      .sort((a, b) => b.total - a.total)
      .slice(0, 3);
  }

  stepIcon(step: Step): string {
    return step.state === 'error' ? '×' : step.state === 'done' ? '✓' : '';
  }
}
