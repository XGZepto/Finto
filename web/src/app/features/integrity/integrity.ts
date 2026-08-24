import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { PageStatus } from '../../core/page-status';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { BalanceCheck, IntegrityReport } from '../../core/models';

/**
 * Integrity.
 *
 * The question here is not "are there duplicates?" but "did we capture every
 * transaction?" — dedup and transfer linking can both be perfect while the
 * ledger is quietly wrong because a parser skipped rows. Statements print a
 * running balance, which is the bank's number and independent of our parsing, so
 * comparing our movements against it is the one check that can prove a gap.
 *
 * Accounts with no balance assertion are listed separately as unverified rather
 * than counted as healthy. Nothing checked is not the same as nothing wrong.
 */
@Component({
  selector: 'app-integrity',
  imports: [FintoSkeleton, MoneyPipe, ShortDatePipe],
  templateUrl: './integrity.html',
  styleUrl: './integrity.css',
})
export class IntegrityPage {
  private api = inject(Api);
  private router = inject(Router);

  status = signal<PageStatus>('loading');
  private loadId = 0;
  report = signal<IntegrityReport | null>(null);
  showAllChecks = signal(false);

  checks = computed(() => {
    const all = this.report()?.balance_checks ?? [];
    const real = all.filter((c) => c.status !== 'insufficient_data');
    return this.showAllChecks() ? real : real.filter((c) => c.status === 'discrepancy');
  });

  okCount = computed(
    () => (this.report()?.balance_checks ?? []).filter((c) => c.status === 'ok').length,
  );

  constructor() {
    this.load();
  }

  load(): void {
    const id = ++this.loadId;
    this.status.set('loading');
    this.api.integrity().subscribe({
      next: (r) => {
        if (id !== this.loadId) return;
        this.report.set(r);
        // If everything reconciles there is nothing to triage, so show the
        // passing checks rather than an empty table.
        this.showAllChecks.set(r.summary.discrepancy_count === 0);
        this.status.set('ok');
      },
      error: () => {
        if (id !== this.loadId) return;
        this.report.set(null);
        this.status.set('failed');
      },
    });
  }

  /**
   * A discrepancy always leads to the same next question — which row is
   * missing? — so send the reader straight to the rows it covers. Transfers and
   * duplicates are included because the balance check counts them too.
   */
  inspect(check: BalanceCheck): void {
    this.router.navigate(['/blotter'], {
      queryParams: {
        accounts: check.account_id,
        from: check.period_start,
        to: check.period_end,
        includeTransfers: true,
        includeDuplicates: true,
      },
    });
  }

  inspectAccount(accountId: string): void {
    this.router.navigate(['/blotter'], {
      queryParams: { accounts: accountId, includeTransfers: true },
    });
  }
}
