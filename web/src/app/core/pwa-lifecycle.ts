/** Below this, the tab bar still paints but the pane has collapsed. */
export const COLLAPSED_PANE_PX = 32;
/** Long enough to be an app switch, short enough to recover before the user stares. */
export const REMOUNT_AFTER_MS = 8_000;

export type RecoverAction = 'reload' | 'remount' | 'refresh';

/**
 * What to do when an installed PWA returns to the foreground.
 *
 * The shell lives in the main bundle, so a dead lazy route or a zero-height
 * pane still shows the nav. Reload only when the layout itself is gone;
 * remount when the outlet never attached or the freeze was long; otherwise
 * just drop hung reads and ask the current page to fetch again.
 */
export function recoverAction(opts: {
  contentHeight: number;
  outletActivated: boolean | null;
  hiddenMs: number;
}): RecoverAction {
  if (isCollapsedContentPane(opts.contentHeight)) return 'reload';
  if (opts.outletActivated === false) return 'remount';
  if (opts.hiddenMs > REMOUNT_AFTER_MS) return 'remount';
  return 'refresh';
}

export function isCollapsedContentPane(height: number): boolean {
  return height < COLLAPSED_PANE_PX;
}

export function isDeadChunkError(error: unknown): boolean {
  const message = String((error as Error | undefined)?.message ?? error ?? '');
  return /chunk|dynamically imported|Loading module|Failed to fetch/i.test(message);
}

/** Keep in step with public/sw.js — WebKit often leaves destination empty. */
export function isHashedAssetRequest(destination: string, pathname: string): boolean {
  return ['script', 'style', 'font'].includes(destination)
    || /\.(?:js|css|woff2?)$/.test(pathname);
}
