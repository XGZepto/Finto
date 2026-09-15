/** Hide HSBC One ref soup under a title the user can already read. */

const ISSUER_NOISE =
  /\bHC\d{8,}\b|\bN[A-Z]?\d{8,}|\bT\d{6,}|\d{4}-\d{4}-\d{4}-\d{4}|838383|GOLD\/EXCHANGE/i;

export function showRawDescription(txn: {
  merchant?: string | null;
  description?: string | null;
}): boolean {
  const merchant = (txn.merchant || '').trim();
  const description = (txn.description || '').trim();
  if (!description || description === merchant) {
    return false;
  }
  if (merchant && ISSUER_NOISE.test(description)) {
    return false;
  }
  return true;
}
