import { Component, ElementRef, HostListener, OnDestroy, ViewChild, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { Api } from '../../core/api.service';
import { Refresh } from '../../core/refresh.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { FilterState } from '../../core/filter-state';
import { PageStatus } from '../../core/page-status';
import { DetailKeyPipe, MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { CategorySuggestion, Money, TotalRow, Txn } from '../../core/models';
import { scrollPane } from '../../core/scroll';
import { FilterBar } from '../../shared/filter-bar';
import { FintoIcon } from '../../shared/finto-icon';
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
  imports: [FintoSkeleton, FormsModule, MoneyPipe, ShortDatePipe, DetailKeyPipe, FilterBar, FintoSelect, FintoIcon],
  templateUrl: './blotter.html',
  styleUrl: './blotter.css',
})
export class BlotterPage implements OnDestroy {
  private api = inject(Api);
  private refreshes = inject(Refresh);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private host = inject(ElementRef<HTMLElement>);
  filters = inject(FilterState);

  /**
   * Publish the sticky filter bar's height so the day headers can pin directly
   * beneath it. Two sticky layers at top:0 would overlap; measuring keeps the
   * date visible under the search rather than behind it.
   */
  @ViewChild('filterBar', { read: ElementRef }) set filterBar(el: ElementRef<HTMLElement> | undefined) {
    this.filterObserver?.disconnect();
    if (!el) return;
    const node = el.nativeElement;
    const publish = () =>
      this.host.nativeElement.style.setProperty('--filter-h', `${node.offsetHeight}px`);
    publish();
    this.filterObserver = new ResizeObserver(publish);
    this.filterObserver.observe(node);
  }
  private filterObserver?: ResizeObserver;

  /**
   * Append when the tail comes into view.
   *
   * The scroll pane is the shell's content element, not the document, so the
   * observer has to be told that — with the default root it would compare
   * against the viewport, which never scrolls, and fire once forever.
   */
  @ViewChild('sentinel') set sentinel(el: ElementRef<HTMLElement> | undefined) {
    this.tailObserver?.disconnect();
    this.sentinelEl = el?.nativeElement ?? null;
    if (!el) return;
    const root = scrollPane() ?? this.host.nativeElement.closest('.content');
    this.tailObserver = new IntersectionObserver(
      (entries) => { if (entries.some((e) => e.isIntersecting)) this.loadMore(); },
      { root: root instanceof HTMLElement ? root : null, rootMargin: '240px 0px' },
    );
    this.tailObserver.observe(el.nativeElement);
  }
  private sentinelEl: HTMLElement | null = null;
  private tailObserver?: IntersectionObserver;
  private inflight?: Subscription;

  status = signal<PageStatus>('loading');
  rows = signal<Txn[]>([]);
  total = signal(0);
  scopeTotals = signal<TotalRow[]>([]);
  normalised = signal<{ net: Money; spend: Money; income: Money; unconvertible_currencies: string[] } | null>(null);
  showNative = signal(false);
  optionsOpen = signal(false);
  convertTo = signal('USD');
  currencies = signal<string[]>(['USD']);
  /* A phone shows ~8 rows, so 100 was 12 screens of scroll bought up front and
     paid for on every filter change. 50 keeps the first paint quick and the
     sentinel does the rest. */
  limit = signal(50);
  offset = signal(0);
  loadingMore = signal(false);
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
  private optionsTrigger: HTMLElement | null = null;
  private amountOptions?: ElementRef<HTMLElement>;
  @ViewChild('amountOptions') set amountOptionsElement(value: ElementRef<HTMLElement> | undefined) {
    this.amountOptions = value;
    if (value) setTimeout(() => value.nativeElement.focus());
  }
  private inspectorHistoryActive = false;
  private afterClose: (() => void) | null = null;
  private scrollLockOffset = 0;

  /**
   * Rows banded by day, each band carrying its own total.
   *
   * Only meaningful while the ledger is in date order — under any other sort
   * the bands would not be contiguous, so the rows stay ungrouped instead.
   * A day that mixes currencies gets no subtotal rather than a summed lie.
   */
  dayGroups = computed<Array<{ date: string | null; total: Money | null; rows: Txn[] }>>(() => {
    const rows = this.rows();
    if (this.sort() !== 'date') return [{ date: null, total: null, rows }];
    const bands: Array<{ date: string | null; total: Money | null; rows: Txn[] }> = [];
    for (const txn of rows) {
      const last = bands[bands.length - 1];
      if (last && last.date === txn.date) last.rows.push(txn);
      else bands.push({ date: txn.date, total: null, rows: [txn] });
    }
    for (const band of bands) {
      const currencies = new Set(band.rows.map((r) => r.booked.currency));
      if (band.rows.length < 2 || currencies.size !== 1) continue;
      band.total = {
        amount: band.rows.reduce((sum, r) => sum + r.booked.amount, 0),
        currency: band.rows[0].booked.currency,
      };
    }
    return bands;
  });

  hasMore = computed(() => this.rows().length < this.total());

  /* Swipe-to-categorise ---------------------------------------------------
     Correcting a category was: tap row, scroll the drawer past four blocks of
     forensic detail, tap Edit, type, Save. On a phone that is the one thing you
     actually do routinely, so it gets a gesture. */
  swipeId = signal<string | null>(null);
  swipeX = signal(0);
  picking = signal<Txn | null>(null);
  suggestion = signal<CategorySuggestion | null>(null);
  suggestionLoading = signal(false);
  categories = signal<string[]>([]);
  private touch = { x: 0, y: 0, axis: '' as '' | 'x' | 'y', moved: false };

  /** Reveal width; the action button is exactly this wide. */
  private readonly REVEAL = 96;

  constructor() {
    // Token starts at 0; queryParams already loads on first paint.
    effect(() => { if (this.refreshes.token()) this.load(); });
    this.api.facets().subscribe({
      next: (facets) => {
        this.currencies.set([...new Set(['USD', ...facets.currencies])]);
        this.categories.set(facets.categories ?? []);
      },
      error: () => {
        this.currencies.set(['USD']);
        this.categories.set([]);
      },
    });
    this.route.queryParams.subscribe((params) => {
      this.filters.hydrate(params);
      this.offset.set(0);
      this.load();
    });
  }

  /** Start again from the top — a filter, sort or currency change. */
  load(): void {
    this.offset.set(0);
    this.status.set('loading');
    this.loadingMore.set(false);
    this.fetch(true);
  }

  /** Append the next page. Driven by the sentinel, never by a button. */
  loadMore(): void {
    if (this.status() === 'loading' || this.loadingMore() || !this.hasMore()) return;
    this.offset.set(this.rows().length);
    this.loadingMore.set(true);
    this.fetch(false);
  }

  private fetch(reset: boolean): void {
    if (reset) this.status.set('loading');
    this.inflight?.unsubscribe();
    this.inflight = this.api
      .transactions(this.filters.filter(), {
        limit: this.limit(),
        offset: this.offset(),
        sort: this.sort(),
        direction: this.direction(),
        convertTo: this.showNative() ? undefined : this.convertTo(),
      })
      .subscribe({
        next: (page) => {
          this.rows.update((rows) => (reset ? page.items : [...rows, ...page.items]));
          if (page.total != null) this.total.set(page.total);
          else if (!reset && !page.items.length) this.total.set(this.rows().length);
          if (page.totals) this.scopeTotals.set(page.totals);
          if (page.normalised !== undefined) this.normalised.set(page.normalised ?? null);
          this.status.set('ok');
          this.loadingMore.set(false);
          this.queueTailCheck();
        },
        error: () => {
          this.loadingMore.set(false);
          if (reset) this.status.set('failed');
        },
      });
  }

  /** IntersectionObserver only fires on a change. After a page lands, the
   * sentinel may still be on screen — especially on a phone — so check again. */
  private queueTailCheck(): void {
    requestAnimationFrame(() => {
      const el = this.sentinelEl;
      const pane = scrollPane() ?? this.host.nativeElement.closest('.content');
      if (!el || this.status() === 'loading' || this.loadingMore() || !this.hasMore()) return;
      const root = pane instanceof HTMLElement ? pane.getBoundingClientRect() : {
        bottom: window.innerHeight,
      };
      if (el.getBoundingClientRect().top < root.bottom + 240) this.loadMore();
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

  openOptions(event: Event): void {
    this.optionsTrigger = event.currentTarget as HTMLElement;
    this.optionsOpen.set(true);
    this.lockScroll();
  }

  closeOptions(): void {
    this.optionsOpen.set(false);
    this.unlockScroll();
    queueMicrotask(() => this.optionsTrigger?.focus());
  }

  trapOptionsFocus(event: KeyboardEvent): void {
    if (event.key !== 'Tab' || !this.amountOptions) return;
    const focusable = [...this.amountOptions.nativeElement.querySelectorAll<HTMLElement>(
      'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])',
    )].filter((node) => node !== this.amountOptions?.nativeElement);
    if (!focusable.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (document.activeElement === this.amountOptions.nativeElement || (event.shiftKey && document.activeElement === first)) {
      event.preventDefault(); (event.shiftKey ? last : first).focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
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

  openFromRow(event: MouseEvent, txn: Txn): void {
    const target = event.target as HTMLElement;
    if (target.closest('button, a, input, select, textarea')) return;
    // A swipe ends with a click event; opening the drawer on it would fight
    // the gesture the user just made.
    if (this.touch.moved) { this.touch.moved = false; return; }
    if (this.swipeId()) { this.closeSwipe(); return; }
    this.open(txn, event.currentTarget as HTMLElement);
  }

  onRowTouchStart(event: TouchEvent, txn: Txn): void {
    if (this.swipeId() && this.swipeId() !== txn.id) this.closeSwipe();
    this.touch = { x: event.touches[0].clientX, y: event.touches[0].clientY, axis: '', moved: false };
  }

  onRowTouchMove(event: TouchEvent, txn: Txn): void {
    const dx = event.touches[0].clientX - this.touch.x;
    const dy = event.touches[0].clientY - this.touch.y;
    // Decide the axis once, on the first movement that clears the noise floor,
    // and stay on it — re-deciding mid-gesture is what makes a list feel like
    // it is fighting your thumb.
    if (!this.touch.axis) {
      if (Math.abs(dx) < 8 && Math.abs(dy) < 8) return;
      this.touch.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
    }
    if (this.touch.axis !== 'x') return;
    event.preventDefault();
    this.touch.moved = true;
    this.swipeId.set(txn.id);
    // Leftward only, and it stiffens past the reveal so it cannot be flung.
    const open = Math.max(0, -dx);
    this.swipeX.set(open <= this.REVEAL ? open : this.REVEAL + (open - this.REVEAL) * 0.2);
  }

  onRowTouchEnd(): void {
    if (this.touch.axis !== 'x') return;
    this.swipeX.set(this.swipeX() > this.REVEAL / 2 ? this.REVEAL : 0);
    if (!this.swipeX()) this.swipeId.set(null);
  }

  closeSwipe(): void {
    this.swipeX.set(0);
    this.swipeId.set(null);
  }

  /** Open the picker for the swiped row. */
  pick(txn: Txn): void {
    this.closeSwipe();
    this.picking.set(txn);
    this.suggestion.set(null);
    this.suggestionLoading.set(true);
    this.api.categorySuggestion(txn.id).subscribe({
      next: (result) => {
        if (this.picking()?.id === txn.id) this.suggestion.set(result.suggestion);
        this.suggestionLoading.set(false);
      },
      error: () => this.suggestionLoading.set(false),
    });
  }

  /** Apply a category straight from the picker — no drawer, no edit mode. */
  applyCategory(category: string, subcategory?: string, merchant?: string | null): void {
    const txn = this.picking();
    if (!txn) return;
    this.picking.set(null);
    this.api.patchTransaction(txn.id, {
      category,
      // A top-level choice replaces the previous taxonomy pair. Omitting the
      // leaf would make the API validate it against the new parent.
      subcategory: subcategory ?? null,
      merchant: merchant ?? undefined,
      review_state: 'confirmed',
    } as Partial<Txn>).subscribe({
      next: (updated) => {
        const leavesQueue = !!this.filters.filter().uncategorisedOnly;
        this.rows.update((rows) => leavesQueue
          ? rows.filter((r) => r.id !== updated.id)
          : rows.map((r) => (r.id === updated.id ? updated : r)));
        if (leavesQueue) this.total.update((total) => Math.max(0, total - 1));
      },
    });
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
    this.lockScroll();
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
    this.unlockScroll();
    queueMicrotask(() => this.lastTrigger?.focus());
  }

  /** Locking a scroller drops its offset, so carry it across. */
  private lockScroll(): void {
    const pane = scrollPane();
    if (!pane || pane.style.overflowY === 'hidden') return;
    this.scrollLockOffset = pane.scrollTop;
    pane.style.overflowY = 'hidden';
  }

  private unlockScroll(): void {
    const pane = scrollPane();
    if (!pane || pane.style.overflowY !== 'hidden') return;
    pane.style.overflowY = '';
    pane.scrollTop = this.scrollLockOffset;
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
    else if (this.optionsOpen() && !this.host.nativeElement.querySelector('.amount-options finto-select.sheet-open')) this.closeOptions();
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
    this.inflight?.unsubscribe();
    this.filterObserver?.disconnect();
    this.tailObserver?.disconnect();
    const pane = scrollPane();
    if (pane) pane.style.overflowY = '';
    this.afterClose = null;
  }

  /** Monogram standing in for a merchant logo, which a statement never carries. */
  initials(txn: Txn): string {
    const source = (txn.merchant || txn.description || '').trim();
    const words = source.split(/[\s·|/-]+/).filter(Boolean);
    if (!words.length) return '—';
    const letters = words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2);
    return letters.toUpperCase();
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

  /**
   * A readable title from the merchant string.
   *
   * Some rows keep a raw run-on like MEITUANDIANPING; title-case long all-caps
   * words while leaving short acronyms (AWS) and already-cased names (DiDi,
   * Spotify) untouched.
   */
  displayTitle(txn: Txn): string {
    const source = (txn.merchant || txn.description || '').trim();
    return source
      .split(/\s+/)
      .map((word) =>
        /^[A-Z0-9]{5,}$/.test(word) && /[A-Z]/.test(word)
          ? word.charAt(0) + word.slice(1).toLowerCase()
          : word,
      )
      .join(' ');
  }

  isTravel(txn: Txn): boolean {
    return Object.keys(txn.details ?? {}).some(
      (k) => k.startsWith('travel.') || k.startsWith('lodging.') || k.startsWith('rental.'),
    );
  }
}
