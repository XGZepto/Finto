import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import {
  InvestmentActivity, InvestmentDetail, InvestmentHistory, InvestmentSnapshot, MpfBundlePreview,
} from '../../core/models';
import { FormsModule } from '@angular/forms';
import { FintoSelect } from '../../shared/finto-select';
import { FintoTimeseries, SeriesPoint } from '../../shared/finto-timeseries';

/**
 * MPF positions.
 *
 * Units, not cash. The contributions that bought these left a bank account as
 * ordinary transactions and reconcile there; what is valued here moves with the
 * market, so it is shown as a dated snapshot and never rolls into a balance.
 */
@Component({
  selector: 'app-investments',
  imports: [FintoSkeleton, FormsModule, MoneyPipe, ShortDatePipe, FintoSelect, FintoTimeseries],
  templateUrl: './investments.html',
  styleUrl: './investments.css',
})
export class InvestmentsPage {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  loading = signal(true);
  failed = signal(false);
  snapshots = signal<InvestmentSnapshot[]>([]);
  accountNames = signal<Record<string, string>>({});
  current = signal<InvestmentDetail | null>(null);
  history = signal<InvestmentHistory | null>(null);
  scheme = signal(this.route.snapshot.queryParamMap.get('scheme') ?? '');
  accountId = signal(this.route.snapshot.queryParamMap.get('account') ?? '');
  activities = signal<InvestmentActivity[]>([]);
  mpfFiles = signal<File[]>([]);
  mpfPreview = signal<MpfBundlePreview | null>(null);
  mpfBusy = signal(false);
  mpfError = signal('');
  mpfResult = signal<any>(null);

  scopedSnapshots = computed(() => {
    const scheme = this.scheme();
    return this.snapshots().filter((snapshot) => !scheme || snapshot.scheme === scheme);
  });

  selectedSubaccount = computed(() => {
    const account = this.accountId();
    return this.current()?.subaccounts.find((item) => item.account_id === account) ?? null;
  });

  displayedValue = computed(() => this.selectedSubaccount()?.balance ?? this.current()?.total ?? null);

  visibleHistory = computed(() => {
    const date = this.current()?.as_of_date;
    return (this.history()?.points ?? []).filter((point) => !date || point.as_of_date <= date);
  });

  historyPoints = computed<SeriesPoint[]>(() => this.visibleHistory().map((point) => ({
    label: point.as_of_date,
    value: point.value.amount,
  })));

  valueChange = computed(() => {
    const points = this.visibleHistory();
    if (points.length < 2) return null;
    const current = points[points.length - 1].value;
    const previous = points[points.length - 2].value;
    const amount = current.amount - previous.amount;
    return {
      amount: { amount, currency: current.currency },
      percent: previous.amount ? amount / Math.abs(previous.amount) * 100 : null,
    };
  });

  pageTitle = computed(() => {
    const subaccount = this.selectedSubaccount();
    if (subaccount) return this.accountLabel(subaccount.account_id);
    const current = this.current();
    return current ? this.friendly(current.scheme) : 'Investments';
  });

  /** Largest holding, so the bars have something to scale against. */
  private peak = computed(() =>
    Math.max(1, ...(this.current()?.holdings ?? []).map((h) => Math.abs(h.market_value.amount))),
  );

  constructor() {
    this.api.accounts().subscribe({
      next: (response) => this.accountNames.set(Object.fromEntries(
        response.accounts.map((account) => [account.id, account.display_name]),
      )),
      error: () => undefined,
    });
    this.loadSnapshots();
    this.loadActivities();
  }

  private loadSnapshots(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.api.investments().subscribe({
      next: (r) => {
        this.snapshots.set(r.snapshots);
        const first = this.scopedSnapshots()[0];
        if (first) {
          if (!this.scheme()) this.scheme.set(first.scheme);
          this.select(first.id);
          this.loadHistory();
        }
        else this.loading.set(false);
      },
      error: () => { this.loading.set(false); this.failed.set(true); },
    });
  }

  private loadActivities(): void {
    this.api.investmentActivities(this.accountId() || undefined).subscribe({
      next: (result) => this.activities.set(result.activities),
      error: () => this.activities.set([]),
    });
  }

  onMpfPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length) return;
    this.mpfFiles.set(files);
    this.mpfPreview.set(null);
    this.mpfResult.set(null);
    this.mpfError.set('');
    this.mpfBusy.set(true);
    this.api.previewMpfBundle(files).subscribe({
      next: (preview) => {
        this.mpfPreview.set(preview);
        this.mpfBusy.set(false);
      },
      error: (error) => {
        this.mpfError.set(error?.error?.detail ?? 'MPF bundle could not be parsed.');
        this.mpfBusy.set(false);
      },
    });
  }

  confirmMpf(): void {
    const preview = this.mpfPreview();
    if (!preview || !this.mpfFiles().length) return;
    this.mpfBusy.set(true);
    this.mpfError.set('');
    this.api.confirmMpfBundle(this.mpfFiles(), preview.bundle_sha256).subscribe({
      next: (result) => {
        this.mpfResult.set(result);
        this.mpfBusy.set(false);
        this.loadSnapshots();
        this.loadActivities();
      },
      error: (error) => {
        this.mpfError.set(error?.error?.detail ?? 'MPF bundle import failed.');
        this.mpfBusy.set(false);
      },
    });
  }

  private loadHistory(): void {
    this.api.investmentHistory(this.scheme() || undefined, this.accountId() || undefined).subscribe({
      next: (history) => this.history.set(history),
      error: () => this.history.set(null),
    });
  }

  select(id: string): void {
    this.loading.set(true);
    this.api.investment(id).subscribe({
      next: (d) => {
        this.current.set(d);
        this.loading.set(false);
      },
      error: () => { this.loading.set(false); if (!this.current()) this.failed.set(true); },
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

  friendly(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase())
      .replace(/\b(Hsbc|Mpf|Tdvc|Hkd|Usd|Amex)\b/g, (word) => word.toUpperCase());
  }

  accountLabel(accountId: string): string {
    return this.accountNames()[accountId] ?? this.friendly(accountId);
  }

  back(): void {
    this.router.navigate(['/accounts']);
  }

  openAccount(scheme: string, accountId: string): void {
    this.scheme.set(scheme);
    this.accountId.set(accountId);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { scheme, account: accountId },
    });
    this.loadHistory();
    this.loadActivities();
  }
}
