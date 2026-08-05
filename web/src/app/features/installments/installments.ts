import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../../core/api.service';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { InstallmentPlan, Money } from '../../core/models';

interface ScheduleRow {
  key: string;
  seq: number | null;
  date: string;
  amount: Money;
  paid: boolean;
  settlement?: boolean;
  description?: string;
  txnId?: string;
}

/**
 * Instalment plans.
 *
 * The ledger stores what actually hit the account, one charge at a time — that
 * is forced, because the balance check proves we captured every transaction and
 * an accrual row would break it. The consequence is that the two numbers that
 * matter about a plan, what you still owe and what is already committed for next
 * month, exist nowhere in the rows themselves. This page is where they live.
 *
 * Outstanding is never summed across currencies. A HKD plan and a USD plan do
 * not add up to a liability figure.
 */
@Component({
  selector: 'app-installments',
  imports: [FormsModule, MoneyPipe, ShortDatePipe],
  templateUrl: './installments.html',
  styleUrl: './installments.css',
})
export class InstallmentsPage {
  private api = inject(Api);

  loading = signal(true);
  activeOnly = signal(true);
  plans = signal<InstallmentPlan[]>([]);
  outstanding = signal<Money[]>([]);
  monthly = signal<Money[]>([]);
  expanded = signal<string | null>(null);
  detail = signal<InstallmentPlan | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.installments(this.activeOnly()).subscribe({
      next: (res) => {
        this.plans.set(res.plans);
        this.outstanding.set(res.outstanding_by_currency);
        this.monthly.set(res.committed_monthly_by_currency);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  setActiveOnly(value: boolean): void {
    this.activeOnly.set(value);
    this.load();
  }

  toggle(plan: InstallmentPlan): void {
    if (this.expanded() === plan.id) {
      this.expanded.set(null);
      return;
    }
    this.expanded.set(plan.id);
    this.detail.set(null);
    this.api.installment(plan.id).subscribe({ next: (p) => this.detail.set(p) });
  }

  progress(plan: InstallmentPlan): string {
    if (plan.status === 'completed') return '100%';
    return `${(plan.paid_count / Math.max(1, plan.term_months)) * 100}%`;
  }

  /**
   * The full term: charges we hold, then the ones still to come.
   *
   * Remaining instalments are the only genuinely predictable part of a spending
   * forecast — the amount and the date are both already agreed.
   */
  schedule(plan: InstallmentPlan): ScheduleRow[] {
    const charges = plan.charges ?? [];
    const bySeq = new Map<number, (typeof charges)[number]>();
    charges.filter((c) => c.installment_seq != null)
      .forEach((c) => bySeq.set(c.installment_seq!, c));

    const rows: ScheduleRow[] = [];
    for (let seq = 1; seq <= plan.term_months; seq++) {
      const charge = bySeq.get(seq);
      if (charge) {
        rows.push({
          key: `charge-${charge.id}`,
          seq,
          date: charge.txn_date,
          amount: { amount: charge.amount_booked, currency: charge.currency_booked },
          paid: true,
          description: charge.description_raw,
          txnId: charge.id,
        });
      } else {
        if (plan.status === 'completed') continue;
        rows.push({
          key: `due-${seq}`,
          seq,
          date: addMonths(plan.start_date, seq - 1),
          amount: plan.per_installment,
          paid: false,
        });
      }
    }
    for (const charge of charges.filter((c) => c.is_settlement)) {
      rows.push({
        key: `settlement-${charge.id}`,
        seq: null,
        date: charge.txn_date,
        amount: { amount: charge.amount_booked, currency: charge.currency_booked },
        paid: true,
        settlement: true,
        description: charge.description_raw,
        txnId: charge.id,
      });
    }
    return rows;
  }

  /** Below this, the grouping was a judgement call rather than a certainty. */
  isUncertain(plan: InstallmentPlan): boolean {
    return !plan.is_confirmed && plan.confidence < 0.95;
  }
}

/** Same day-of-month, clamped to the length of the target month. */
function addMonths(iso: string, months: number): string {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  const target = new Date(y, m - 1 + months, 1);
  const lastDay = new Date(target.getFullYear(), target.getMonth() + 1, 0).getDate();
  target.setDate(Math.min(d, lastDay));
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${target.getFullYear()}-${pad(target.getMonth() + 1)}-${pad(target.getDate())}`;
}
