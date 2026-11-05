/** One server-sent event: its name and parsed JSON payload. */
export interface StreamEvent {
  event: string;
  data: any;
}

/**
 * Incremental parser for a text/event-stream body.
 *
 * Network chunks split events anywhere, so text is buffered until a blank line
 * closes an event. Comment lines (":") are keep-alives and are skipped.
 */
export class SseParser {
  private buffer = '';

  push(chunk: string): StreamEvent[] {
    this.buffer += chunk.replace(/\r\n?/g, '\n');
    const events: StreamEvent[] = [];
    let end: number;
    while ((end = this.buffer.indexOf('\n\n')) !== -1) {
      const block = this.buffer.slice(0, end);
      this.buffer = this.buffer.slice(end + 2);
      let event = 'message';
      const data: string[] = [];
      for (const line of block.split('\n')) {
        if (line.startsWith(':')) continue;
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      if (!data.length) continue;
      try {
        events.push({ event, data: JSON.parse(data.join('\n')) });
      } catch {
        /* A malformed event is dropped; the closing `done` still arrives. */
      }
    }
    return events;
  }
}

/**
 * POST a question and deliver its events as they arrive.
 *
 * Resolves once the stream ends. Rejects when the stream cannot be opened at
 * all, so the caller can fall back to the plain request.
 */
export async function streamAsk(
  body: unknown, onEvent: (event: StreamEvent) => void, signal?: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/query/stream', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok || !response.body) throw new Error(`stream unavailable (${response.status})`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = new SseParser();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    for (const event of parser.push(decoder.decode(value, { stream: true }))) onEvent(event);
  }
  for (const event of parser.push(decoder.decode() + '\n\n')) onEvent(event);
}
