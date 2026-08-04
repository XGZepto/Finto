import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FilterState } from '../../core/filter-state';
import { DetailKeyPipe, MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { Txn } from '../../core/models';
import { FilterBar } from '../../shared/filter-bar';

/**
 * The blotter.
 *
 * Row affordances reflect the domain rather than the table: transfer legs link
 * to their counterpart, instalments show n/N and link to the plan, refunds link
 * to the purchase they reverse, and the detail drawer shows the raw source row.
 *
 * That last one is what makes the ledger trustworthy — every number traces back
 * to a line in a file you downloaded.
 */
@Component({
  selector: 'app-blotter',
  imports: [FormsModule, MoneyPipe, ShortDatePipe, DetailKeyPipe, FilterBar],
  templateUrl: './blotter.html',
  styleUrl: './blotter.css',
})
export class BlotterPage {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  filters = inject(FilterState);

  loading = signal(true);
  rows = signal<Txn[]>([]);
  total = signal(0);
  limit = signal(100);
  offset = signal(0);
  sort = signal('date');
  direction = signal<'asc' | 'desc'>('desc');

  selected = signal<Txn | null>(null);
  detailLoading = signal(false);
  editCategory = signal('');
  editNotes = signal('');
  saving = signal(false);

  pageStart = computed(() => (this.total() ? this.offset() + 1 : 0));
  pageEnd = computed(() => Math.min(this.offset() + this.limit(), this.total()));
  hasPrev = computed(() => this.offset() > 0);
  hasNext = computed(() => this.offset() + this.limit() < this.total());

  constructor() {
    this.route.queryParams.subscribe((params) => {
      this.filters.hydrate(params);
      this.offset.set(0);
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    this.api
      .transactions(this.filters.filter(), {
        limit: this.limit(),
        offset: this.offset(),
        sort: this.sort(),
        direction: this.direction(),
      })
      .subscribe({
        next: (page) => {
          this.rows.set(page.items);
          this.total.set(page.total);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  sortBy(column: string): void {
    if (this.sort() === column) {
      this.direction.set(this.direction() === 'asc' ? 'desc' : 'asc');
    } else {
      this.sort.set(column);
      this.direction.set('desc');
    }
    this.load();
  }

  page(delta: number): void {
    const next = this.offset() + delta * this.limit();
    if (next < 0 || next >= this.total()) return;
    this.offset.set(next);
    this.load();
  }

  open(txn: Txn): void {
    this.detailLoading.set(true);
    this.selected.set(txn);
    this.api.transaction(txn.id).subscribe({
      next: (full) => {
        this.selected.set(full);
        this.editCategory.set(full.category ?? '');
        this.editNotes.set(full.notes ?? '');
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
  }

  /**
   * Follow a link out of the drawer — a transfer's other leg, or the purchase a
   * refund reverses. A leg that names its counterpart but won't take you there
   * is only half the story.
   */
  openById(id: string): void {
    this.detailLoading.set(true);
    this.api.transaction(id).subscribe({
      next: (full) => {
        this.selected.set(full);
        this.editCategory.set(full.category ?? '');
        this.editNotes.set(full.notes ?? '');
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
  }

  openPlan(): void {
    this.router.navigate(['/installments']);
  }

  close(): void {
    this.selected.set(null);
  }

  /** The counterpart legs — everything in the group except this row. */
  otherLegs(txn: Txn) {
    return (txn.transfer_legs ?? []).filter((leg) => leg.id !== txn.id);
  }

  save(): void {
    const txn = this.selected();
    if (!txn) return;
    this.saving.set(true);
    this.api
      .patchTransaction(txn.id, {
        category: this.editCategory() || undefined,
        notes: this.editNotes() || undefined,
      } as Partial<Txn>)
      .subscribe({
        next: (updated) => {
          this.selected.set(updated);
          this.saving.set(false);
          this.load();
        },
        error: () => this.saving.set(false),
      });
  }

  detailEntries(txn: Txn): Array<{ key: string; value: string }> {
    return Object.entries(txn.details ?? {})
      .filter(([k]) => !k.startsWith('raw.'))
      .map(([key, value]) => ({ key, value }));
  }

  rawEntries(txn: Txn): Array<{ key: string; value: string }> {
    return Object.entries(txn.provenance?.raw_row ?? {}).map(([key, value]) => ({
      key,
      value: String(value ?? ''),
    }));
  }

  /**
   * The rail a charge was routed through, when it went through one.
   *
   * Alipay, WeChat Pay and UnionPay reach the card under their own name. When
   * the acquirer passes no merchant through, that is a fact the statement
   * records — not a row we failed to read — so it is shown as such rather than
   * left looking unclassified.
   */
  filterByDetail(key: string, value: string): void {
    this.close();
    this.filters.patch({ detail: [`${key}=${value}`] });
    this.offset.set(0);
    this.load();
  }

  gateway(txn: Txn): string | null {
    return txn.details?.['payment.gateway'] ?? null;
  }

  merchantHidden(txn: Txn): boolean {
    return txn.details?.['merchant.disclosed'] === 'no';
  }

  isTravel(txn: Txn): boolean {
    return Object.keys(txn.details ?? {}).some(
      (k) => k.startsWith('travel.') || k.startsWith('lodging.') || k.startsWith('rental.'),
    );
  }
}
