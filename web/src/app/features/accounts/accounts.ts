import { Component, computed, effect, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FintoIcon } from '../../shared/finto-icon';
import { Api } from '../../core/api.service';
import { FintoSkeleton } from '../../shared/finto-skeleton';
import { MoneyPipe, ShortDatePipe } from '../../core/money.pipe';
import { Account, Card, Flows, Money, Position, SummaryRow, Txn } from '../../core/models';
import { forkJoin } from 'rxjs';
import { Preferences } from '../../core/preferences.service';

interface AccountRow {
  account: Account;
  positions: Position[];
  cards: number;
}

@Component({
  selector: 'app-accounts',
  imports: [FintoSkeleton, FintoIcon, MoneyPipe, ShortDatePipe],
  templateUrl: './accounts.html',
  styleUrl: './accounts.css',
})
export class AccountsPage {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  preferences = inject(Preferences);

  loading = signal(true);
  accounts = signal<Account[]>([]);
  cards = signal<Card[]>([]);
  positions = signal<Position[]>([]);
  netWorth = signal<Money | null>(null);
  positionTypes = signal<Array<{ account_type: string; balance: Money }>>([]);
  unconvertible = signal<string[]>([]);
  flows = signal<Flows>({ internal: [], external: [], external_accounts: [], normalised: { currency: 'USD', unconvertible_currencies: [], external_accounts: [], internal: [], external_nodes: [] } });
  selected = signal<string | null>(null);
  detailLoading = signal(false);
  byKind = signal<SummaryRow[]>([]);
  byHolder = signal<SummaryRow[]>([]);
  byMonth = signal<SummaryRow[]>([]);
  recent = signal<Txn[]>([]);
  flowView = signal<'chart' | 'list'>(this.savedView('finto.accounts.flowView'));
  detailFlowView = signal<'chart' | 'list'>(this.savedView('finto.accounts.detailFlowView'));

  constructor() {
    this.route.paramMap.subscribe((params) => {
      const id = params.get('id');
      this.selected.set(id);
      if (id) this.loadDetail(id);
    });
    this.api.accounts().subscribe({ next: (r) => this.accounts.set(r.accounts) });
    this.api.cards().subscribe({ next: (r) => this.cards.set(r.cards) });
    effect(() => {
      const currency = this.preferences.baseCurrency();
      this.api.flows({}, currency).subscribe({
        next: (r) => this.flows.set(r),
      });
      this.api.positions(currency).subscribe({
        next: (r) => {
          this.positions.set(r.positions);
          this.netWorth.set(r.normalised?.net_worth ?? null);
          this.positionTypes.set(r.normalised?.by_type ?? []);
          this.unconvertible.set(r.normalised?.unconvertible_currencies ?? []);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
    });
  }

  assets = computed<Money | null>(() => {
    const worth = this.netWorth();
    if (!worth) return null;
    return { amount: this.positionTypes().filter((row) => row.balance.amount > 0)
      .reduce((sum, row) => sum + row.balance.amount, 0), currency: worth.currency };
  });

  liabilities = computed<Money | null>(() => {
    const worth = this.netWorth();
    if (!worth) return null;
    return { amount: this.positionTypes().filter((row) => row.balance.amount < 0)
      .reduce((sum, row) => sum + row.balance.amount, 0), currency: worth.currency };
  });

  rows = computed<AccountRow[]>(() => {
    const held = new Map<string, Position[]>();
    for (const p of this.positions()) held.set(p.account_id, [...(held.get(p.account_id) ?? []), p]);
    return this.accounts().map((account) => ({
      account,
      positions: held.get(account.id) ?? [],
      cards: this.cards().filter((c) => c.account_id === account.id).length,
    }));
  });

  groups = computed(() => {
    const grouped = new Map<string, AccountRow[]>();
    for (const row of this.rows()) {
      const key = row.account.balance_group || `account:${row.account.id}`;
      grouped.set(key, [...(grouped.get(key) ?? []), row]);
    }
    return [...grouped.entries()].map(([key, children]) => {
      const totals = new Map<string, number>();
      for (const child of children) for (const p of child.positions) {
        totals.set(p.currency, (totals.get(p.currency) ?? 0) + p.balance.amount);
      }
      return {
        key,
        name: key.startsWith('account:') ? children[0].account.display_name : this.humanize(key),
        institution: children[0].account.institution_id,
        children,
        totals: [...totals].map(([currency, amount]) => ({ amount, currency })),
      };
    }).sort((a, b) => a.institution.localeCompare(b.institution) || a.name.localeCompare(b.name));
  });

  current = computed(() => this.rows().find((r) => r.account.id === this.selected()));
  siblings = computed(() => {
    const current = this.current()?.account;
    if (!current) return [];
    if (!current.balance_group) return [this.current()!];
    return this.rows().filter((r) => r.account.balance_group === current.balance_group);
  });

  lineages = computed(() => {
    const mine = this.cards().filter((c) => c.account_id === this.selected());
    const groups = new Map<string, Card[]>();
    for (const c of mine) groups.set(c.lineage_root ?? c.id, [...(groups.get(c.lineage_root ?? c.id) ?? []), c]);
    return [...groups.values()].map((chain) => ({
      holder: chain[0].is_supplementary ? chain[0].cardholder_name : 'You',
      supplementary: chain[0].is_supplementary,
      numbers: chain.map((c) => c.last4).filter(Boolean),
      reissued: chain.length > 1,
    }));
  });

  accountFlows = computed(() => {
    const id = this.selected();
    return this.flows().internal.filter((f) => f.from_account === id || f.to_account === id);
  });

  flowRows = computed(() => {
    const names = new Map(this.accounts().map((a) => [a.id, a.display_name]));
    const rows = this.flows().normalised.external_accounts
      .filter((e) => e.in.amount || e.out.amount)
      .sort((a, b) => Math.max(b.in.amount, -b.out.amount) - Math.max(a.in.amount, -a.out.amount))
      .slice(0, 14);
    const max = Math.max(1, ...rows.flatMap((r) => [r.in.amount, -r.out.amount]));
    return rows.map((r, index) => ({
      ...r,
      name: names.get(r.account_id) ?? r.account_id,
      y: 45 + index * 52,
      inWidth: Math.max(r.in.amount ? 1.5 : 0, Math.sqrt(r.in.amount / max) * 20),
      outWidth: Math.max(r.out.amount ? 1.5 : 0, Math.sqrt(-r.out.amount / max) * 20),
    }));
  });
  flowHeight = computed(() => Math.max(150, 90 + this.flowRows().length * 52));
  allInternalFlows = computed(() => {
    const names = new Map(this.accounts().map((a) => [a.id, a.display_name]));
    return (this.flows().normalised.internal ?? [])
      .map((r) => ({ ...r, fromName: names.get(r.from_account) ?? r.from_account,
        toName: names.get(r.to_account) ?? r.to_account }))
      .sort((a, b) => b.amount.amount - a.amount.amount);
  });
  internalFlowRows = computed(() => {
    const positions = new Map(this.flowRows().map((r) => [r.account_id, r.y]));
    const rows = this.allInternalFlows()
      .filter((r) => positions.has(r.from_account) && positions.has(r.to_account));
    const peak = Math.max(1, ...rows.map((r) => r.amount.amount));
    return rows.map((r) => ({
      ...r,
      fromY: positions.get(r.from_account)!, toY: positions.get(r.to_account)!,
      width: Math.max(1.2, Math.sqrt(r.amount.amount / peak) * 9),
    }));
  });

  detailFlow = computed(() => {
    const id = this.selected();
    if (!id) return { incoming: [], outgoing: [] };
    const names = new Map(this.accounts().map((a) => [a.id, a.display_name]));
    const incoming = [
      ...(this.flows().normalised.external_nodes ?? [])
        .filter((node) => node.account_id === id && node.in.amount > 0)
        .map((node) => ({ key: `ext-in-${node.bucket}`, label: this.humanize(node.bucket),
          kind: 'external', amount: node.in.amount, moves: node.moves })),
      ...(this.flows().normalised.internal ?? [])
        .filter((flow) => flow.to_account === id)
        .map((flow) => ({ key: `int-in-${flow.from_account}`, label: names.get(flow.from_account) ?? flow.from_account,
          kind: 'account', amount: flow.amount.amount, moves: flow.moves })),
    ].sort((a, b) => b.amount - a.amount).slice(0, 7);
    const outgoing = [
      ...(this.flows().normalised.external_nodes ?? [])
        .filter((node) => node.account_id === id && node.out.amount < 0)
        .map((node) => ({ key: `ext-out-${node.bucket}`, label: this.humanize(node.bucket),
          kind: 'external', amount: -node.out.amount, moves: node.moves })),
      ...(this.flows().normalised.internal ?? [])
        .filter((flow) => flow.from_account === id)
        .map((flow) => ({ key: `int-out-${flow.to_account}`, label: names.get(flow.to_account) ?? flow.to_account,
          kind: 'account', amount: flow.amount.amount, moves: flow.moves })),
    ].sort((a, b) => b.amount - a.amount).slice(0, 7);
    const peak = Math.max(1, ...incoming.map((node) => node.amount), ...outgoing.map((node) => node.amount));
    const place = <T extends { amount: number }>(nodes: T[]) => nodes.map((node, index) => ({
      ...node,
      y: nodes.length === 1 ? 180 : 48 + index * (264 / Math.max(1, nodes.length - 1)),
      width: Math.max(1.5, Math.sqrt(node.amount / peak) * 13),
    }));
    return { incoming: place(incoming), outgoing: place(outgoing) };
  });

  maxMonthSpend = computed(() => Math.max(1, ...this.byMonth().map((m) => Math.abs(m.spend.amount))));
  monthWidth(row: SummaryRow): string {
    return `${Math.max(2, Math.abs(row.spend.amount) / this.maxMonthSpend() * 100)}%`;
  }

  loadDetail(id: string): void {
    const scope = { accounts: [id] };
    this.detailLoading.set(true);
    forkJoin({
      byKind: this.api.summary('kind', scope),
      byHolder: this.api.summary('cardholder', scope),
      byMonth: this.api.summary('month', scope),
      recent: this.api.transactions(scope, { limit: 12, sort: 'date', direction: 'desc' }),
    }).subscribe({
      next: (result) => {
        this.byKind.set(result.byKind.rows);
        this.byHolder.set(result.byHolder.rows);
        this.byMonth.set(result.byMonth.rows);
        this.recent.set(result.recent.items);
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
  }

  open(id: string): void { this.router.navigate(['/accounts', id]); }
  back(): void { this.router.navigate(['/accounts']); }
  openInBlotter(id: string): void {
    this.router.navigate(['/blotter'], { queryParams: { accounts: id } });
  }
  humanize(value: string): string {
    return value.replace(/[_-]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
      .replace(
        /\b(Hsbc|Mpf|Amex|Hk|Us|Uk|Sg|Cn|Jp|Au|Nz|Mo|Tw|Hkd|Usd|Cny|Rmb|Gbp|Eur|Jpy|Sgd|Aud|Nzd|Cad)\b/g,
        (word) => word.toUpperCase());
  }

  setFlowView(view: 'chart' | 'list', detail = false): void {
    (detail ? this.detailFlowView : this.flowView).set(view);
    sessionStorage.setItem(detail ? 'finto.accounts.detailFlowView' : 'finto.accounts.flowView', view);
  }

  private savedView(key: string): 'chart' | 'list' {
    const saved = sessionStorage.getItem(key);
    if (saved === 'chart' || saved === 'list') return saved;
    // A 1000px flow diagram on a phone is a pan-and-scan puzzle. The list says
    // the same thing in one column, so that is the default where it has to fit.
    return matchMedia('(max-width: 880px)').matches ? 'list' : 'chart';
  }
}
