/** How far above the fold the next page should start loading. */
export const BLOTTER_PREFETCH_PX = 240;

/**
 * Whether the blotter should append another page after layout has settled.
 *
 * Infinite scroll itself is an IntersectionObserver on a sentinel (MDN;
 * Angular prefers observers over afterRenderEffect for visibility). The
 * observer only notifies when intersection *changes*, so after a page of rows
 * lands this one-shot check covers a sentinel that stayed in the prefetch
 * zone. It is not a scroll listener.
 */
export function blotterTailDue(input: {
  hasMore: boolean;
  busy: boolean;
  sentinelTop: number | null;
  rootBottom: number;
  scrollRemain: number | null;
  prefetchPx?: number;
}): boolean {
  if (!input.hasMore || input.busy) return false;
  const prefetch = input.prefetchPx ?? BLOTTER_PREFETCH_PX;
  if (input.scrollRemain != null && input.scrollRemain <= prefetch) return true;
  if (input.sentinelTop == null) return false;
  return input.sentinelTop < input.rootBottom + prefetch;
}
