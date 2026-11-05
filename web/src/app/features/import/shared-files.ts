/** Cache the service worker parks shared files in (see public/sw.js). */
export const SHARE_CACHE = 'finto-share-v1';

/**
 * Files another app shared to Finto through the OS share sheet.
 *
 * The manifest's share_target posts them to /share-target; the service worker
 * stores each one in SHARE_CACHE and opens /import. Each is read once, then
 * deleted, so a reload does not stage it twice.
 */
export async function takeSharedFiles(cacheName: string): Promise<File[]> {
  if (typeof caches === 'undefined') return [];
  try {
    const cache = await caches.open(cacheName);
    const requests = await cache.keys();
    const files: File[] = [];
    for (const request of requests) {
      const response = await cache.match(request);
      if (!response) continue;
      const blob = await response.blob();
      const name = decodeURIComponent(response.headers.get('X-Finto-Filename') ?? 'statement');
      files.push(new File([blob], name, { type: blob.type }));
      await cache.delete(request);
    }
    return files;
  } catch {
    return [];
  }
}
