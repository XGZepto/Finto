export interface AnswerPart {
  text: string;
  strong: boolean;
}

export type AnswerBlock =
  | { kind: 'paragraph' | 'heading'; parts: AnswerPart[] }
  | { kind: 'list'; ordered: boolean; items: AnswerPart[][] };

export function parseAnswerMarkdown(source: string): AnswerBlock[] {
  const blocks: AnswerBlock[] = [];
  const lines = source.replace(/\r\n?/g, '\n').trim().split('\n');
  let paragraph: string[] = [];
  let list: Extract<AnswerBlock, { kind: 'list' }> | null = null;

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

  for (const raw of lines) {
    const line = raw.trim();
    if (!line) {
      flushParagraph();
      flushList();
      continue;
    }

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
