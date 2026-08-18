/** Content pane shorter than this is treated as collapsed. */
export const COLLAPSED_PANE_PX = 32;
/** Hidden duration that remounts the current route on return. */
export const REMOUNT_AFTER_MS = 8_000;

export type RecoverAction = 'reload' | 'remount' | 'refresh';

/**
 * Foreground resume policy.
 *
 * `reload` when the content pane has no height.
 * `remount` when the outlet is inactive or the hide exceeded REMOUNT_AFTER_MS.
 * `refresh` otherwise.
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

/** Scripts, styles, fonts, and same-origin .js/.css/.woff2 paths. Matches public/sw.js. */
export function isHashedAssetRequest(destination: string, pathname: string): boolean {
  return ['script', 'style', 'font'].includes(destination)
    || /\.(?:js|css|woff2?)$/.test(pathname);
}
