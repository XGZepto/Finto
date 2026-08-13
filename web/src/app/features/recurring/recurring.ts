import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { InstallmentPlan, Money, Txn } from '../../core/models';
import { Preferences } from '../../core/preferences.service';
import { FintoIcon } from '../../shared/finto-icon';
import { FintoPills } from '../../shared/finto-pills';
import { FintoSelect } from '../../shared/finto-select';

export type Cadence = 'weekly' | 'monthly' | 'quarterly' | 'yearly' | 'irregular';

/** Median interval in days → cadence, and how many of them make a month. */
const CADENCE_BANDS: Array<{ cadence: Cadence; min: number; max: number; perMonth: number }> = [
  { cadence: 'weekly', min: 5, max: 9, perMonth: 30 / 7 },
  { cadence: 'monthly', min: 24, max: 38, perMonth: 1 },
  { cadence: 'quarterly', min: 80, max: 100, perMonth: 1 / 3 },
  { cadence: 'yearly', min: 340, max: 400, perMonth: 1 / 12 },
];

export interface Commitment {
  key: string;
  merchant: string;
  category: string | null;
  cadence: Cadence;
  /** Typical charge, taken as the median so one odd month cannot move it. */
  amount: Money;
  /** What the cadence costs per month, for the committed total. */
  monthly: Money | null;
  charges: Txn[];
  count: number;
  lastCharge: string;
  nextDue: string | null;
  /**
   * No charge arrived when one was due. Stated as observed rather than as a
   * debt: nothing is owed, the charge simply stopped, so it is listed but kept
   * out of the committed total.
   */
  inactive: boolean;
  totalPaid: Money;
}

function median(values: number[]): number {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2);
}

function addDays(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + Math.round(days));
  return d.toISOString().slice(0, 10);
}

function daysBetween(a: string, b: string): number {
  return Math.abs(
    (new Date(`${b}T00:00:00Z`).getTime() - new Date(`${a}T00:00:00Z`).getTime()) / 86_400_000);
}

/**
 * Recurring.
 *
 * What leaves before any decision is made. A commitment is recognised by the
 * interval between its charges, not by how many there are — a supermarket
 * visited forty times is frequent, not recurring — so a charge set whose
 * intervals do not cluster is reported as irregular and kept out of the
 * monthly total rather than averaged into a figure it does not belong in.
 */
@Component({
  selector: 'app-recurring',
  imports: [FintoSkeleton, FormsModule, MoneyPipe, ShortDatePipe, FintoIcon, FintoPills, FintoSelect],
  templateUrl: './recurring.html',
  styleUrl: './recurring.css',
})
export class RecurringPage {
  private api = inject(Api);
  private router = inject(Router);
  private preferences = inject(Preferences);

  readonly reportingCurrencies = ['USD', 'HKD', 'GBP', 'EUR', 'JPY', 'CNY', 'SGD', 'AUD', 'CAD'];
  readonly kinds = ['All', 'Subscriptions', 'Instalments'];
  readonly cadences = ['Any', 'weekly', 'monthly', 'quarterly', 'yearly', 'irregular'];

  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  search = signal('');
  kind = signal('All');
  cadence = signal('Any');
  charges = signal<Txn[]>([]);
  plans = signal<InstallmentPlan[]>([]);
  selected = signal<Commitment | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const convert = this.convertTo() || undefined;

    // Cadence needs the dates of individual charges, which a rollup discards.
    // The tag filter is conjunctive, so each marker is asked for separately and
    // the results merged; a charge carrying both must not be counted twice.
    const opts = { limit: 500, sort: 'date', direction: 'desc', convertTo: convert };
    let pending = 2;
    const merged = new Map<string, Txn>();
    const absorb = (rows: Txn[]) => {
      for (const row of rows) merged.set(row.id, row);
      if (--pending === 0) {
        this.charges.set([...merged.values()]);
        this.loading.set(false);
      }
    };
    for (const tag of ['subscription', 'recurring']) {
      this.api.transactions({ tags: [tag] }, opts).subscribe({
        next: (page) => absorb(page.items ?? []),
        error: () => absorb([]),
      });
    }

    this.api.installments(true).subscribe({
      next: (res) => this.plans.set(res.plans ?? []),
      error: () => this.plans.set([]),
    });
  }

  setConvertTo(currency: string): void {
    this.preferences.setBaseCurrency(currency);
    this.load();
  }

  /** Charges grouped per merchant, each read for its cadence. */
  commitments = computed<Commitment[]>(() => {
    const groups = new Map<string, Txn[]>();
    for (const txn of this.charges()) {
      const key = (txn.merchant || txn.description || '').trim().toLowerCase();
      if (!key) continue;
      (groups.get(key) ?? groups.set(key, []).get(key)!).push(txn);
    }

    const today = new Date().toISOString().slice(0, 10);
    const out: Commitment[] = [];

    for (const [key, rows] of groups) {
      const dates = [...new Set(rows.map((r) => r.date))].sort();
      const amounts = rows.map((r) => Math.abs(r.booked.amount));
      const currency = rows[0].booked.currency;

      const gaps: number[] = [];
      for (let i = 1; i < dates.length; i++) gaps.push(daysBetween(dates[i - 1], dates[i]));
      const interval = median(gaps);
      const band = dates.length < 2
        ? undefined
        : CADENCE_BANDS.find((b) => interval >= b.min && interval <= b.max);
      const cadence: Cadence = band?.cadence ?? 'irregular';

      const typical = median(amounts);
      const lastCharge = dates[dates.length - 1];
      const nextDue = band ? addDays(lastCharge, interval) : null;

      out.push({
        key,
        merchant: rows[0].merchant || rows[0].description || key,
        category: rows[0].category ?? null,
        cadence,
        amount: { amount: -typical, currency },
        monthly: band
          ? { amount: -Math.round(typical * band.perMonth), currency }
          : null,
        charges: rows,
        count: rows.length,
        lastCharge,
        nextDue,
        inactive: !!nextDue && nextDue < today && daysBetween(nextDue, today) > interval,
        totalPaid: { amount: -amounts.reduce((s, n) => s + n, 0), currency },
      });
    }

    return out.sort((a, b) =>
      Math.abs(b.monthly?.amount ?? 0) - Math.abs(a.monthly?.amount ?? 0)
      || Math.abs(b.amount.amount) - Math.abs(a.amount.amount));
  });

  /** The monthly instalment: principal spread across the term. */
  perMonth(plan: InstallmentPlan): Money {
    const each = Math.round(Math.abs(plan.principal.amount) / Math.max(1, plan.term_months));
    return { amount: -each, currency: plan.principal.currency };
  }

  private matches(name: string): boolean {
    const q = this.search().trim().toLowerCase();
    return !q || name.toLowerCase().includes(q);
  }

  visibleSubs = computed(() => {
    if (this.kind() === 'Instalments') return [];
    const wanted = this.cadence();
    return this.commitments().filter((c) =>
      this.matches(c.merchant) && (wanted === 'Any' || c.cadence === wanted));
  });

  visiblePlans = computed(() => {
    if (this.kind() === 'Subscriptions') return [];
    if (this.cadence() !== 'Any' && this.cadence() !== 'monthly') return [];
    return this.plans().filter((p) => this.matches(p.merchant || p.description || ''));
  });

  /** Cadences with a known period, summed with instalments still running. */
  monthlyTotal = computed<Money>(() => {
    const ccy = this.convertTo() || 'USD';
    const subs = this.visibleSubs()
      .filter((c) => !c.inactive)
      .reduce((sum, c) => sum + (c.monthly?.amount ?? 0), 0);
    const plans = this.visiblePlans().reduce((sum, p) => sum + this.perMonth(p).amount, 0);
    return { amount: subs + plans, currency: ccy };
  });

  irregularCount = computed(() =>
    this.visibleSubs().filter((c) => c.cadence === 'irregular').length);

  inactiveCount = computed(() => this.visibleSubs().filter((c) => c.inactive).length);

  open(commitment: Commitment): void {
    this.selected.set(commitment);
  }

  close(): void {
    this.selected.set(null);
  }

  averageOf(commitment: Commitment): Money {
    const total = Math.abs(commitment.totalPaid.amount);
    return { amount: -Math.round(total / Math.max(1, commitment.count)),
             currency: commitment.totalPaid.currency };
  }

  firstSeen(commitment: Commitment): string {
    return [...commitment.charges].map((c) => c.date).sort()[0];
  }

  openInBlotter(commitment: Commitment): void {
    this.router.navigate(['/blotter'], {
      queryParams: { q: commitment.merchant, tags: 'subscription' },
    });
  }

  openPlans(): void {
    this.router.navigate(['/installments']);
  }
}
