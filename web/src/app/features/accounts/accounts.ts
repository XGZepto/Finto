import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Api } from '../../core/api.service';
import { MoneyPipe } from '../../core/money.pipe';
import { Account, Card, Flows, Position, SummaryRow } from '../../core/models';

/**
 * Accounts and the cards on them.
 *
 * One account at a time: what it holds, who spends on it, and where its money
 * goes. Reissued cards are grouped by lineage so a renumbering does not read as
 * two people.
 */
@Component({
  selector: 'app-accounts',
  imports: [MoneyPipe],
  templateUrl: './accounts.html',
  styleUrl: './accounts.css',
})
export class AccountsPage {
  private api = inject(Api);
  private router = inject(Router);

  loading = signal(true);
  accounts = signal<Account[]>([]);
  cards = signal<Card[]>([]);
  positions = signal<Position[]>([]);
  flows = signal<Flows>({ internal: [], external: [] });
  selected = signal<string | null>(null);

  byKind = signal<SummaryRow[]>([]);
  byHolder = signal<SummaryRow[]>([]);

  constructor() {
    this.api.accounts().subscribe({ next: (r) => this.accounts.set(r.accounts) });
    this.api.cards().subscribe({ next: (r) => this.cards.set(r.cards) });
    this.api.flows().subscribe({ next: (r) => this.flows.set(r) });
    this.api.positions().subscribe({
      next: (r) => {
        this.positions.set(r.positions);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Accounts with their positions attached, heaviest balance first. */
  rows = computed(() => {
    const held = new Map<string, Position[]>();
    for (const p of this.positions()) {
      held.set(p.account_id, [...(held.get(p.account_id) ?? []), p]);
    }
    return this.accounts().map((a) => ({
      account: a,
      positions: held.get(a.id) ?? [],
      cards: this.cards().filter((c) => c.account_id === a.id).length,
    }));
  });

  current = computed(() => this.rows().find((r) => r.account.id === this.selected()));

  /**
   * Cards grouped by lineage, so a card and the numbers it replaced read as one
   * history rather than as separate people.
   */
  lineages = computed(() => {
    const mine = this.cards().filter((c) => c.account_id === this.selected());
    const groups = new Map<string, Card[]>();
    for (const c of mine) {
      const root = c.lineage_root ?? c.id;
      groups.set(root, [...(groups.get(root) ?? []), c]);
    }
    return [...groups.values()].map((chain) => ({
      holder: chain[0].cardholder_name,
      supplementary: chain[0].is_supplementary,
      numbers: chain.map((c) => c.last4).filter(Boolean),
      reissued: chain.length > 1,
    }));
  });

  /** Transfers with this account at either end. */
  accountFlows = computed(() => {
    const id = this.selected();
    return this.flows().internal.filter(
      (f) => f.from_account === id || f.to_account === id,
    );
  });

  select(id: string): void {
    this.selected.set(this.selected() === id ? null : id);
    if (!this.selected()) return;
    const scope = { accounts: [id] };
    this.api.summary('kind', scope).subscribe({ next: (r) => this.byKind.set(r.rows) });
    this.api.summary('cardholder', scope).subscribe({
      next: (r) => this.byHolder.set(r.rows),
    });
  }

  openInBlotter(id: string): void {
    this.router.navigate(['/blotter'], { queryParams: { accounts: id } });
  }
}
