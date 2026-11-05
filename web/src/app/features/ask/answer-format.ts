export interface AnswerPart {
  text: string;
  strong: boolean;
}

export type AnswerBlock =
  | { kind: 'paragraph' | 'heading'; parts: AnswerPart[] }
  | { kind: 'list'; ordered: boolean; items: AnswerPart[][] }
  | { kind: 'table'; header: AnswerPart[][]; rows: AnswerPart[][][]; numeric: boolean[] };

/** A cell that reads as a figure aligns right, like every other ledger column. */
const FIGURE = /^[-+(]?\s*(?:[A-Z]{3}\s*)?[-+]?[\d,]+(?:\.\d+)?%?\)?(?:\s*[A-Z]{3})?$|^[—–-]$/;

function splitRow(line: string): string[] {
  return line.replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim());
}

export function parseAnswerMarkdown(source: string): AnswerBlock[] {
  const blocks: AnswerBlock[] = [];
  const lines = source.replace(/\r\n?/g, '\n').trim().split('\n');
  let paragraph: string[] = [];
  let list: Extract<AnswerBlock, { kind: 'list' }> | null = null;
  let table: string[][] | null = null;

  const flushParagraph = () => {
    if (!paragraph.length) return;
    blocks.push({ kind: 'paragraph', parts: inlineParts(paragraph.join(' ')) });
    paragraph = [];
  };
  const flushList = () => {
    if (!list) return;
    blocks.push(list);
    list = null;
  };
  const flushTable = () => {
    if (!table) return;
    const rows = table.filter((cells) => !cells.every((c) => /^:?-{2,}:?$/.test(c)));
    const [header, ...body] = rows;
    if (header && body.length) {
      const width = header.length;
      const pad = (cells: string[]) => Array.from({ length: width }, (_, i) => cells[i] ?? '');
      const numeric = header.map((_, i) =>
        body.every((r) => FIGURE.test((r[i] ?? '').replace(/\*\*/g, '').trim())));
      blocks.push({
        kind: 'table',
        header: header.map(inlineParts),
        rows: body.map((r) => pad(r).map(inlineParts)),
        numeric,
      });
    } else if (header) {
      blocks.push({ kind: 'paragraph', parts: inlineParts(header.join(' · ')) });
    }
    table = null;
  };

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) {
      flushParagraph();
      flushList();
      flushTable();
      continue;
    }

    if (line.startsWith('|')) {
      flushParagraph();
      flushList();
      table = [...(table ?? []), splitRow(line)];
      continue;
    }
    flushTable();

    const heading = line.match(/^#{1,6}\s+(.+)$/);
    if (heading) {
      flushParagraph();
      flushList();
      blocks.push({ kind: 'heading', parts: inlineParts(heading[1]) });
      continue;
    }

    const bullet = line.match(/^[-*]\s+(.+)$/);
    const numbered = line.match(/^\d+[.)]\s+(.+)$/);
    const item = bullet?.[1] ?? numbered?.[1];
    if (item !== undefined) {
      flushParagraph();
      const ordered = numbered !== null;
      if (!list || list.ordered !== ordered) {
        flushList();
        list = { kind: 'list', ordered, items: [] };
      }
      list.items.push(inlineParts(item));
      continue;
    }

    flushList();
    paragraph.push(line);
  }

  flushParagraph();
  flushList();
  flushTable();
  return blocks;
}

function inlineParts(value: string): AnswerPart[] {
  const parts: AnswerPart[] = [];
  const pattern = /\*\*(.+?)\*\*/g;
  let cursor = 0;
  for (const match of value.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > cursor) parts.push({ text: value.slice(cursor, index), strong: false });
    parts.push({ text: match[1], strong: true });
    cursor = index + match[0].length;
  }
  if (cursor < value.length) parts.push({ text: value.slice(cursor), strong: false });
  return parts.length ? parts : [{ text: value, strong: false }];
}
