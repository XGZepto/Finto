/**
 * Shared types.
 *
 * `Money` is an integer amount in minor units plus a currency code — never a
 * JavaScript number of major units. The ledger is built on integer minor units
 * to avoid float error, and `0.1 + 0.2 !== 0.3` would reintroduce exactly that
 * error the moment we divided by 100 and kept the result around. Division
 * happens once, at render time, inside MoneyPipe.
 */

export interface Money {
  amount: number; // minor units, signed. Negative = money out.
  currency: string;
}

export interface ConvertedMoney {
  amount: number;
  currency: string;
  source: Money;
  rate: string | null;
  rate_date: string | null;
  converted: boolean;
  ok: boolean;
}

/**
 * The one filter type. The blotter, the summary and the natural-language query
 * all speak it, which is what makes drill-down work: clicking a summary row
 * pushes that dimension onto the blotter filter.
 */
export interface LedgerFilter {
  from?: string;
  to?: string;
  accounts?: string[];
  cards?: string[];
  institutions?: string[];
  categories?: string[];
  kinds?: string[];
  currency?: string;
  minAmount?: number;
  maxAmount?: number;
  q?: string;
  /** Structured facts, each "key" or "key=value". */
  detail?: string[];
  /** Transfers between your own accounts are not spending. Default false. */
  includeTransfers?: boolean;
  includeDuplicates?: boolean;
  uncategorisedOnly?: boolean;
  installmentsOnly?: boolean;
}

export interface Txn {
  id: string;
  date: string;
  posted_date: string | null;
  account_id: string;
  account_name: string;
  institution_id: string;
  card_id: string | null;
  cardholder_name: string | null;
  card_last4: string | null;
  description: string;
  merchant: string | null;
  counterparty: string | null;
  booked: Money;
  native: Money | null;
  fx_rate: string | null;
  fx_fee: Money | null;
  kind: string;
  category: string | null;
  subcategory: string | null;
  status: string;
  transfer_group_id: string | null;
  installment_plan_id: string | null;
  installment_seq: number | null;
  refund_of_id: string | null;
  external_ref: string | null;
  review_state: string;
  notes: string | null;
  details: Record<string, string>;
  provenance?: {
    source_path: string;
    parser_id: string;
    imported_at: string;
    raw_row: Record<string, unknown>;
  };
  transfer_legs?: Array<{
    role: string;
    id: string;
    description_raw: string;
    amount_booked: number;
    currency_booked: string;
    account_id: string;
    txn_date: string;
  }>;
}

export interface Page<T> {
  total: number;
  /** What every matching row comes to, not just this page. */
  totals?: TotalRow[];
  limit: number;
  offset: number;
  items: T[];
}

export interface SummaryRow {
  bucket: string;
  currency: string;
  txn_count: number;
  net: Money;
  spend: Money;
  income: Money;
  net_converted?: ConvertedMoney;
  spend_converted?: ConvertedMoney;
  income_converted?: ConvertedMoney;
}

export interface TotalRow {
  currency: string;
  txn_count: number;
  net: Money;
  spend: Money;
  income: Money;
  uncategorised: number;
  net_converted?: ConvertedMoney;
}

export interface Position {
  account_id: string;
  account_name: string;
  institution_id: string;
  account_type: string;
  currency: string;
  txn_count: number;
  balance: Money;
  net: Money;
  inflow: Money;
  outflow: Money;
  balance_converted?: ConvertedMoney;
  inflow_converted?: ConvertedMoney;
  outflow_converted?: ConvertedMoney;
  /** 'statement' when the bank's own closing figure was available. */
  basis: 'statement' | 'movements';
  basis_date: string | null;
  first_date: string;
  last_date: string;
  balance_converted?: ConvertedMoney;
}

export interface Account {
  id: string;
  institution_id: string;
  display_name: string;
  account_type: string;
  primary_currency: string;
  settlement_currencies: string[];
  balance_group: string | null;
  masked_number: string | null;
}

export interface Card {
  id: string;
  account_id: string;
  cardholder_name: string;
  last4: string | null;
  is_supplementary: boolean;
  issued_on: string | null;
  closed_on: string | null;
  replaces_card_id: string | null;
  lineage_root: string;
}

export interface Facets {
  accounts: Array<{ id: string; display_name: string; institution_id: string; account_type: string; primary_currency: string }>;
  cards: Array<{ id: string; account_id: string; cardholder_name: string; last4: string | null }>;
  institutions: Array<{ id: string; display_name: string; country: string }>;
  categories: string[];
  kinds: string[];
  currencies: string[];
  detail_keys: string[];
  date_range: { min_date: string | null; max_date: string | null };
}

export interface InstallmentPlan {
  id: string;
  account_id: string;
  card_id: string | null;
  merchant: string | null;
  description: string;
  principal: Money;
  term_months: number;
  start_date: string;
  status: string;
  confidence: number;
  is_confirmed: boolean;
  paid_count: number;
  paid: Money;
  remaining_count: number;
  outstanding: Money;
  per_installment: Money;
  charges?: Array<{
    id: string;
    txn_date: string;
    description_raw: string;
    amount_booked: number;
    currency_booked: string;
    installment_seq: number | null;
  }>;
}

export interface Job {
  id: string;
  kind: string;
  status: 'queued' | 'running' | 'done' | 'error';
  progress: string;
  result: any;
  error: string | null;
  created_at: string;
  finished_at: string | null;
}

export interface StagePreview {
  staged_id: string;
  filename: string;
  size_bytes: number;
  parser: string | null;
  parser_version: string | null;
  header?: string[] | null;
  first_row?: Record<string, string> | null;
  txn_count?: number;
  balance_count?: number;
  period_start?: string | null;
  period_end?: string | null;
  warnings?: string[];
  sample?: Array<{
    date: string;
    description: string;
    amount: number;
    currency: string;
    installment: number[] | null;
    details: Record<string, string>;
  }>;
  error?: string;
}

export interface IntegrityReport {
  healthy: boolean;
  violations: Array<{ check: string; count: number; description: string }>;
  balance_checks: Array<any>;
  discrepancies: Array<any>;
  unverified_accounts: Array<{ account_id: string; display_name: string; txn_count: number }>;
  summary: {
    checks_run: number;
    discrepancy_count: number;
    violation_count: number;
    unverified_account_count: number;
  };
}

export interface QueryResult {
  ok: boolean;
  error?: string;
  question: string;
  filter?: LedgerFilter;
  group_by?: string | null;
  intent?: string;
  confidence?: number;
  explanation?: string;
  unsupported?: string | null;
  dropped_fields?: string[];
  cached?: boolean;
  totals?: TotalRow[];
  rows?: SummaryRow[];
  transactions?: Page<Txn>;
}


/** A fact a parser lifted off a statement, and how often it appears. */
export interface DetailKey {
  key: string;
  facts: number;
  transactions: number;
}

export interface DetailValue {
  value: string;
  transactions: number;
}

/**
 * An MPF valuation. These are units, not cash: contributions that left a bank
 * account are ordinary transactions and reconcile as such, while what is held
 * here moves with the market and never enters a balance check.
 */
export interface InvestmentSnapshot {
  id: string;
  as_of_date: string;
  scheme: string;
  total: Money;
  source: string;
  notes: string | null;
}

export interface InvestmentDetail extends InvestmentSnapshot {
  subaccounts: Array<{
    account_id: string;
    member_no: string | null;
    balance: Money;
    allocation: string | null;
  }>;
  holdings: Array<{
    instrument: string;
    units: string | null;
    unit_price: string | null;
    market_value: Money;
    allocation: string | null;
  }>;
}


/** Movement between accounts you own, and across the boundary. */
export interface Flows {
  internal: Array<{
    from_account: string;
    to_account: string;
    moves: number;
    amount: Money;
  }>;
  external: Array<{
    currency: string;
    moves: number;
    in: Money;
    out: Money;
    net: Money;
  }>;
}


/** Spend by a dimension over months, normalised to one currency. */
export interface Composition {
  dimension: string;
  currency: string;
  months: string[];
  series: Array<{ bucket: string; total: number; values: number[] }>;
  unconvertible_currencies: string[];
}

/** Per account, month by month, what data backs it. */
export interface Coverage {
  months: string[];
  accounts: Array<{
    account_id: string;
    account_name: string;
    cells: Array<'statement' | 'export' | 'none' | 'pre'>;
    statement_months: number;
    export_months: number;
    gap_months: number;
  }>;
}
