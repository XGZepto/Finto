import {
  Component, ElementRef, HostListener, OnDestroy, ViewChild, computed, inject, signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { FilterState } from '../../core/filter-state';
import { DetailKeyPipe, MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { Money, TotalRow, Txn } from '../../core/models';
import { FilterBar } from '../../shared/filter-bar';
import { FintoSelect } from '../../shared/finto-select';

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
  imports: [FormsModule, MoneyPipe, ShortDatePipe, DetailKeyPipe, FilterBar, FintoSelect],
  templateUrl: './blotter.html',
  styleUrl: './blotter.css',
})
export class BlotterPage implements OnDestroy {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  filters = inject(FilterState);

  loading = signal(true);
  rows = signal<Txn[]>([]);
  total = signal(0);
  scopeTotals = signal<TotalRow[]>([]);
  normalised = signal<{ net: Money; spend: Money; income: Money; unconvertible_currencies: string[] } | null>(null);
  showNative = signal(false);
  convertTo = signal('USD');
  currencies = signal<string[]>(['USD']);
  limit = signal(100);
  offset = signal(0);
  sort = signal('date');
  direction = signal<'asc' | 'desc'>('desc');

  selected = signal<Txn | null>(null);
  detailLoading = signal(false);
  editCategory = signal('');
  newTag = signal('');
  editNotes = signal('');
  saving = signal(false);
  editMode = signal(false);
  drawer?: ElementRef<HTMLElement>;
  @ViewChild('drawer') set drawerElement(value: ElementRef<HTMLElement> | undefined) {
    this.drawer = value;
    if (value) setTimeout(() => value.nativeElement.focus());
  }
  private lastTrigger: HTMLElement | null = null;
  private inspectorHistoryActive = false;
  private afterClose: (() => void) | null = null;

  pageStart = computed(() => (this.total() ? this.offset() + 1 : 0));
  pageEnd = computed(() => Math.min(this.offset() + this.limit(), this.total()));
  hasPrev = computed(() => this.offset() > 0);
  hasNext = computed(() => this.offset() + this.limit() < this.total());

  constructor() {
    this.api.facets().subscribe({
      next: (facets) => this.currencies.set([...new Set(['USD', ...facets.currencies])]),
    });
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
        convertTo: this.showNative() ? undefined : this.convertTo(),
      })
      .subscribe({
        next: (page) => {
          this.rows.set(page.items);
          this.total.set(page.total);
          this.scopeTotals.set(page.totals ?? []);
          this.normalised.set(page.normalised ?? null);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  setAggregation(mode: 'normalised' | 'native'): void {
    this.showNative.set(mode === 'native');
    this.load();
  }

  setConvertTo(currency: string): void {
    this.convertTo.set(currency);
    this.load();
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

  openFromRow(event: MouseEvent, txn: Txn): void {
    const target = event.target as HTMLElement;
    if (target.closest('button, a, input, select, textarea')) return;
    this.open(txn, event.currentTarget as HTMLElement);
  }

  openFromKeyboard(event: Event, txn: Txn): void {
    if ((event as KeyboardEvent).key === ' ') event.preventDefault();
    this.open(txn, event.currentTarget as HTMLElement);
  }

  open(txn: Txn, trigger?: HTMLElement): void {
    if (!this.selected() && !this.inspectorHistoryActive) {
      history.pushState({ ...history.state, fintoInspector: true }, '', location.href);
      this.inspectorHistoryActive = true;
    }
    this.lastTrigger = trigger ?? this.lastTrigger;
    this.detailLoading.set(true);
    this.editMode.set(false);
    this.selected.set(txn);
    document.body.style.overflow = 'hidden';
    this.api.transaction(txn.id).subscribe({
      next: (full) => {
        if (this.selected()?.id !== txn.id) return;
        this.editMode.set(false);
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
    this.editMode.set(false);
    this.api.transaction(id).subscribe({
      next: (full) => {
        if (!this.selected()) return;
        this.selected.set(full);
        this.editCategory.set(full.category ?? '');
        this.editNotes.set(full.notes ?? '');
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
  }

  openPlan(): void {
    this.close(() => this.router.navigate(['/installments']));
  }

  close(afterClose?: () => void): void {
    if (this.inspectorHistoryActive) {
      this.inspectorHistoryActive = false;
      this.afterClose = afterClose ?? null;
      this.closeImmediately();
      history.back();
      return;
    }
    this.closeImmediately();
    afterClose?.();
  }

  private closeImmediately(): void {
    this.selected.set(null);
    this.editMode.set(false);
    document.body.style.overflow = '';
    queueMicrotask(() => this.lastTrigger?.focus());
  }

  @HostListener('window:popstate')
  closeOnBack(): void {
    if (this.selected()) this.closeImmediately();
    this.inspectorHistoryActive = false;
    const action = this.afterClose;
    this.afterClose = null;
    action?.();
  }

  @HostListener('document:keydown.escape')
  closeOnEscape(): void {
    if (this.selected()) this.close();
  }

  trapFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab' || !this.drawer) return;
    const focusable = [...this.drawer.nativeElement.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )];
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
  }

  ngOnDestroy(): void {
    document.body.style.overflow = '';
    this.afterClose = null;
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
          this.editMode.set(false);
          this.load();
        },
        error: () => this.saving.set(false),
      });
  }

  cancelEdit(): void {
    const txn = this.selected();
    if (txn) {
      this.editCategory.set(txn.category ?? '');
      this.editNotes.set(txn.notes ?? '');
    }
    this.newTag.set('');
    this.editMode.set(false);
  }

  addTag(): void {
    const txn = this.selected();
    const tag = this.newTag().trim();
    if (!txn || !tag) return;
    this.api.addTag(txn.id, tag).subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.newTag.set('');
        this.load();
      },
    });
  }

  removeTag(tag: string): void {
    const txn = this.selected();
    if (!txn) return;
    this.api.removeTag(txn.id, tag).subscribe({
      next: (updated) => {
        this.selected.set(updated);
        this.load();
      },
    });
  }

  filterByTag(tag: string): void {
    this.close(() => {
      this.filters.patch({ tags: [tag] });
      this.offset.set(0);
    });
  }

  detailEntries(txn: Txn): Array<{ key: string; value: string }> {
    return Object.entries(txn.details ?? {})
      .filter(([k]) => !k.startsWith('raw.'))
      .map(([key, value]) => ({ key, value }));
  }

  friendlyTaxonomy(value: string | null | undefined): string {
    if (!value) return '—';
    return value.split(/[._/-]+/).filter(Boolean)
      .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
      .join(' / ');
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
    this.close(() => {
      this.filters.patch({ detail: [`${key}=${value}`] });
      this.offset.set(0);
    });
  }

  filterByCardholder(name: string): void {
    this.close(() => {
      this.filters.patch({ cardholders: [name] });
      this.offset.set(0);
    });
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
