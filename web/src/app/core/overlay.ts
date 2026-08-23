/** Position helpers for menus rendered on the viewport overlay host. */

const COMPACT = '(max-width: 880px)';

export function isCompactViewport(): boolean {
  return typeof window !== 'undefined' && window.matchMedia(COMPACT).matches;
}

export function placeOverlay(
  trigger: DOMRect,
  opts: { minWidth?: number; maxWidth?: number; maxHeight?: number; width?: number; gap?: number } = {},
): { dropUp: boolean; style: Record<string, string> } | null {
  if (isCompactViewport()) return null;
  const gap = opts.gap ?? 4;
  const vw = window.innerWidth;
  const vh = window.innerHeight;
  const maxWidth = Math.min(opts.maxWidth ?? 360, vw - 16);
  const width = Math.min(Math.max(opts.width ?? trigger.width, opts.minWidth ?? trigger.width), maxWidth);
  let left = trigger.left;
  if (left + width > vw - 8) left = trigger.right - width;
  if (left < 8) left = 8;
  const spaceBelow = vh - trigger.bottom - gap;
  const spaceAbove = trigger.top - gap;
  const dropUp = spaceBelow < 280 && spaceAbove > spaceBelow;
  const maxHeight = Math.min(opts.maxHeight ?? 360, Math.max(160, dropUp ? spaceAbove : spaceBelow));
  return {
    dropUp,
    style: {
      position: 'absolute',
      top: dropUp ? 'auto' : `${Math.round(trigger.bottom + gap)}px`,
      bottom: dropUp ? `${Math.round(vh - trigger.top + gap)}px` : 'auto',
      left: `${Math.round(left)}px`,
      right: 'auto',
      width: `${Math.round(width)}px`,
      minWidth: `${Math.round(width)}px`,
      maxWidth: `${Math.round(width)}px`,
      maxHeight: `${Math.round(maxHeight)}px`,
    },
  };
}

export function watchOverlay(anchor: () => void): () => void {
  window.addEventListener('resize', anchor);
  document.addEventListener('scroll', anchor, true);
  return () => {
    window.removeEventListener('resize', anchor);
    document.removeEventListener('scroll', anchor, true);
  };
}
