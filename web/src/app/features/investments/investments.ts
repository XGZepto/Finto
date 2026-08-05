import { Component, computed, inject, signal } from '@angular/core';
import { Api } from '../../core/api.service';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { InvestmentDetail, InvestmentSnapshot } from '../../core/models';
import { FormsModule } from '@angular/forms';
import { FintoSelect } from '../../shared/finto-select';

/**
 * MPF positions.
 *
 * Units, not cash. The contributions that bought these left a bank account as
 * ordinary transactions and reconcile there; what is valued here moves with the
 * market, so it is shown as a dated snapshot and never rolls into a balance.
 */
@Component({
  selector: 'app-investments',
  imports: [FormsModule, MoneyPipe, ShortDatePipe, FintoSelect],
  templateUrl: './investments.html',
  styleUrl: './investments.css',
})
export class InvestmentsPage {
  private api = inject(Api);

  loading = signal(true);
  snapshots = signal<InvestmentSnapshot[]>([]);
  current = signal<InvestmentDetail | null>(null);

  /** Largest holding, so the bars have something to scale against. */
  private peak = computed(() =>
    Math.max(1, ...(this.current()?.holdings ?? []).map((h) => Math.abs(h.market_value.amount))),
  );

  constructor() {
    this.api.investments().subscribe({
      next: (r) => {
        this.snapshots.set(r.snapshots);
        if (r.snapshots.length) this.select(r.snapshots[0].id);
        else this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  select(id: string): void {
    this.loading.set(true);
    this.api.investment(id).subscribe({
      next: (d) => {
        this.current.set(d);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  width(amount: number): string {
    return `${(Math.abs(amount) / this.peak()) * 100}%`;
  }

  /** The issuer states an allocation as a fraction; show it as a percentage. */
  percent(allocation: string | null): string {
    if (!allocation) return '';
    const n = Number(allocation);
    return Number.isFinite(n) ? `${(n * 100).toFixed(1)}%` : allocation;
  }
}
