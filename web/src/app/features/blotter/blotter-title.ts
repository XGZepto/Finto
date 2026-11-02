/** Hide HSBC One ref soup under a title the user can already read. */

const ISSUER_NOISE =
  /\bHC\d{8,}\b|\bN[A-Z]?\d{8,}|\bT\d{6,}|\d{4}-\d{4}-\d{4}-\d{4}|838383|GOLD\/EXCHANGE/i;

export function showRawDescription(txn: {
  merchant?: string | null;
  description?: string | null;
}): boolean {
  const merchant = (txn.merchant || '').trim();
  const description = (txn.description || '').trim();
  // Without a merchant the title already is the description; and a statement
  // that shouts the same words in capitals is not a second line of information.
  if (!description || !merchant || comparable(description) === comparable(merchant)) {
    return false;
  }
  if (merchant && ISSUER_NOISE.test(description)) {
    return false;
  }
  return true;
}

function comparable(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9\u00c0-\uffff]+/g, '');
}

/**
 * The readable title for a transaction, wherever it is listed.
 *
 * Statements shout: long all-capital words are brought down to title case,
 * short ones (codes like HK, EPS, CBK) are left alone.
 */
export function transactionTitle(txn: { merchant?: string | null; description?: string | null }): string {
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
