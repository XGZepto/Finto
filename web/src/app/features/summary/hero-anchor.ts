/** Where the Summary hero measures "this month" to. Pure, so it is unit-testable. */

export function isoDate(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/**
 * Where "now" is for the hero: today, unless the ledger stops earlier.
 *
 * Prefers the API's `ledger_through`; an older API lacks it, so the last month
 * with any spending stands in, measured to that month's end.
 */
export function anchorDate(
  through: string | null | undefined, months: Array<{ bucket: string; txn_count: number; spend?: { amount: number } }>,
  today = new Date(),
): { date: Date; lagging: boolean; exact: boolean } {
  let latest: Date | null = null;
  let exact = false;
  if (through && /^\d{4}-\d{2}-\d{2}$/.test(through)) {
    const [y, m, d] = through.split('-').map(Number);
    latest = new Date(y, m - 1, d);
    exact = true;
  } else {
    // A month with only an interest credit is not a month with spending in it.
    const last = months
      .filter((row) => row.txn_count > 0 && (row.spend === undefined || row.spend.amount !== 0))
      .map((row) => row.bucket).sort().pop();
    if (last && /^\d{4}-\d{2}$/.test(last)) {
      const [y, m] = last.split('-').map(Number);
      latest = new Date(y, m, 0);
    }
  }
  const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  if (!latest || latest >= midnight) return { date: today, lagging: false, exact: true };
  return { date: latest, lagging: true, exact };
}
