import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { MoneyPipe } from '../../core/money.pipe';
import { InstallmentPlan, Money, SummaryRow } from '../../core/models';
import { Preferences } from '../../core/preferences.service';

interface Commitment {
  merchant: string;
  spend: Money;
  count: number;
  each: Money;
}

/**
 * Recurring.
 *
 * What is committed before the month begins: subscriptions and instalment
 * plans. Both are derived from marks already in the ledger — the subscription
 * tag and the instalment plans — rather than a separate forecast, so a
 * commitment shown here traces to real charges.
 */
@Component({
  selector: 'app-recurring',
  imports: [MoneyPipe],
  templateUrl: './recurring.html',
  styleUrl: './recurring.css',
})
export class RecurringPage {
  private api = inject(Api);
  private router = inject(Router);
  private preferences = inject(Preferences);

  convertTo = this.preferences.baseCurrency;
  loading = signal(true);
  subs = signal<Commitment[]>([]);
  plans = signal<InstallmentPlan[]>([]);

  /** The monthly instalment: principal spread across the term. */
  perMonth(plan: InstallmentPlan): Money {
    const each = Math.round(Math.abs(plan.principal.amount) / Math.max(1, plan.term_months));
    return { amount: -each, currency: plan.principal.currency };
  }

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const convert = this.convertTo() || undefined;

    this.api.summary('merchant', { tags: ['subscription'] } as any, convert).subscribe({
      next: (res) => {
        this.subs.set(this.commitments(res.rows));
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });

    this.api.installments(true).subscribe({
      next: (res: any) => this.plans.set(res.plans ?? res ?? []),
      error: () => this.plans.set([]),
    });
  }

  private commitments(rows: SummaryRow[]): Commitment[] {
    const ccy = this.convertTo();
    return rows
      .map((r) => {
        const spend = r.spend_converted?.ok ? Math.abs(r.spend_converted.amount) : Math.abs(r.spend.amount);
        const count = r.txn_count || 1;
        return {
          merchant: r.bucket,
          spend: { amount: -spend, currency: ccy || r.spend.currency },
          count,
          each: { amount: -Math.round(spend / count), currency: ccy || r.spend.currency },
        };
      })
      .sort((a, b) => Math.abs(b.spend.amount) - Math.abs(a.spend.amount));
  }

  monthlyTotal = computed<Money>(() => ({
    amount: this.subs().reduce((sum, s) => sum + s.each.amount, 0),
    currency: this.convertTo() || 'USD',
  }));

  openMerchant(merchant: string): void {
    this.router.navigate(['/blotter'], { queryParams: { q: merchant, tags: 'subscription' } });
  }

  openPlans(): void {
    this.router.navigate(['/installments']);
  }
}
