/** Desktop popovers pin to the viewport so a scroll pane cannot clip them. */

const COMPACT = '(max-width: 880px)';

export const OVERLAY_VARS = [
  '--overlay-top',
  '--overlay-bottom',
  '--overlay-left',
  '--overlay-width',
  '--overlay-max-h',
] as const;

export function isCompactViewport(): boolean {
  return typeof window !== 'undefined' && window.matchMedia(COMPACT).matches;
}

export function placeOverlay(
  trigger: DOMRect,
  opts: { minWidth?: number; maxWidth?: number; maxHeight?: number; width?: number; gap?: number } = {},
): { dropUp: boolean; vars: Record<string, string> } | null {
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
  const maxHeight = Math.min(opts.maxHeight ?? 320, Math.max(120, dropUp ? spaceAbove : spaceBelow));
  return {
    dropUp,
    vars: {
      '--overlay-top': dropUp ? 'auto' : `${Math.round(trigger.bottom + gap)}px`,
      '--overlay-bottom': dropUp ? `${Math.round(vh - trigger.top + gap)}px` : 'auto',
      '--overlay-left': `${Math.round(left)}px`,
      '--overlay-width': `${Math.round(width)}px`,
      '--overlay-max-h': `${Math.round(maxHeight)}px`,
    },
  };
}

export function writeOverlayVars(el: HTMLElement, vars: Record<string, string> | null): void {
  for (const key of OVERLAY_VARS) {
    if (vars?.[key]) el.style.setProperty(key, vars[key]);
    else el.style.removeProperty(key);
  }
}

export function watchOverlay(anchor: () => void): () => void {
  window.addEventListener('resize', anchor);
  document.addEventListener('scroll', anchor, true);
  return () => {
    window.removeEventListener('resize', anchor);
    document.removeEventListener('scroll', anchor, true);
  };
}
