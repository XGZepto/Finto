import { Observable, concat, shareReplay, tap } from 'rxjs';

/**
 * In-session GET cache.
 *
 * Reuses a value that has already arrived and is still inside its TTL.
 * In-flight and failed requests start a new factory call.
 * After TTL, emits the previous value then the new request.
 */
export class ReadCache {
  private reads = new Map<string, { at: number; value: Observable<unknown>; settled: boolean }>();
  private readonly max: number;

  constructor(max = 200) {
    this.max = max;
  }

  get<T>(key: string, ttlMs: number, factory: () => Observable<T>): Observable<T> {
    const hit = this.reads.get(key);
    if (hit?.settled && Date.now() - hit.at < ttlMs) return hit.value as Observable<T>;
    const request = factory().pipe(
      tap({
        next: () => {
          const entry = this.reads.get(key);
          if (entry) entry.settled = true;
        },
        error: () => this.reads.delete(key),
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    const value = hit?.settled
      ? concat(hit.value as Observable<T>, request).pipe(
          shareReplay({ bufferSize: 1, refCount: false }),
        )
      : request;
    this.reads.set(key, { at: Date.now(), value, settled: !!hit?.settled });
    if (this.reads.size > this.max) this.reads.delete(this.reads.keys().next().value!);
    return value as Observable<T>;
  }

  clear(): void {
    this.reads.clear();
  }
}
