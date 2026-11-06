import type { ChangeFields, ChangeProposal } from '../../core/models';

/** `card_payment` → `Card payment`, as the rest of the app labels taxonomy. */
export function label(value: string | null | undefined): string {
  if (!value) return '';
  const words = value.replace(/[_-]+/g, ' ').trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const KIND_LABELS: Record<string, string> = {
  cc_payment: 'Card payment',
  fx_conversion: 'Currency exchange',
  atm: 'Cash withdrawal',
};

function kindLabel(kind: string): string {
  return KIND_LABELS[kind] ?? label(kind);
}

function categoryLabel(category: string | null, subcategory: string | null): string {
  if (!category) return 'Uncategorised';
  return subcategory ? `${label(category)} › ${label(subcategory)}` : label(category);
}

/** What a proposal sets, in one line: "Card payment · Transfers › Card payment · +bill". */
export function describeChange(p: ChangeProposal): string {
  const parts: string[] = [];
  if (p.set.kind) parts.push(kindLabel(p.set.kind));
  if (p.set.category) parts.push(categoryLabel(p.set.category, p.set.subcategory ?? null));
  else if ('subcategory' in p.set) parts.push(p.set.subcategory ? label(p.set.subcategory) : 'No subcategory');
  if ('merchant' in p.set) parts.push(p.set.merchant ? `Merchant “${p.set.merchant}”` : 'No merchant');
  if (p.set.review_state) parts.push(label(p.set.review_state));
  parts.push(...p.add_tags.map((tag) => `+${tag}`), ...p.remove_tags.map((tag) => `−${tag}`));
  return parts.join(' · ');
}

/** One row's before → after, naming only what moves. */
export function describeRowChange(before: ChangeFields, after: ChangeFields): string {
  const moves: string[] = [];
  if (before.kind !== after.kind) moves.push(`${kindLabel(before.kind)} → ${kindLabel(after.kind)}`);
  if (before.category !== after.category || before.subcategory !== after.subcategory) {
    moves.push(`${categoryLabel(before.category, before.subcategory)} → ${categoryLabel(after.category, after.subcategory)}`);
  }
  if (before.merchant !== after.merchant) moves.push(`${before.merchant || 'no merchant'} → ${after.merchant || 'no merchant'}`);
  if (before.review_state !== after.review_state) moves.push(`${label(before.review_state)} → ${label(after.review_state)}`);
  const added = after.tags.filter((tag) => !before.tags.includes(tag));
  const removed = before.tags.filter((tag) => !after.tags.includes(tag));
  moves.push(...added.map((tag) => `+${tag}`), ...removed.map((tag) => `−${tag}`));
  return moves.join(' · ');
}

/** Proposals arrive one per streamed step; keep each once, latest copy winning. */
export function mergeProposals(
  current: ChangeProposal[] | undefined,
  incoming: ChangeProposal[] | undefined,
): ChangeProposal[] | undefined {
  if (!incoming?.length) return current;
  const byId = new Map((current ?? []).map((p) => [p.id, p]));
  for (const p of incoming) byId.set(p.id, p);
  return [...byId.values()];
}
