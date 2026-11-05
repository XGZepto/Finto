import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { parseAnswerMarkdown } from './answer-format.ts';

describe('parseAnswerMarkdown', () => {
  it('renders emphasis and bullet lists as structure, not raw markers', () => {
    assert.deepEqual(
      parseAnswerMarkdown(
        'Dining totalled **HKD 119,065**.\n\n- HKD cards: 69,915\n- USD converted: 27,204',
      ),
      [
        {
          kind: 'paragraph',
          parts: [
            { text: 'Dining totalled ', strong: false },
            { text: 'HKD 119,065', strong: true },
            { text: '.', strong: false },
          ],
        },
        {
          kind: 'list',
          ordered: false,
          items: [
            [{ text: 'HKD cards: 69,915', strong: false }],
            [{ text: 'USD converted: 27,204', strong: false }],
          ],
        },
      ],
    );
  });

  it('supports headings and ordered lists without producing HTML', () => {
    assert.deepEqual(parseAnswerMarkdown('### Detail\n1. First\n2. **Second**'), [
      { kind: 'heading', parts: [{ text: 'Detail', strong: false }] },
      {
        kind: 'list',
        ordered: true,
        items: [
          [{ text: 'First', strong: false }],
          [{ text: 'Second', strong: true }],
        ],
      },
    ]);
  });

  it('renders a Markdown table as a table with figure columns aligned', () => {
    const blocks = parseAnswerMarkdown(
      'By month:\n\n| Month | HKD | USD |\n|---|---|---|\n| Jan | — | 47.00 |\n| Feb | 195.74 | 14.32 |',
    );
    assert.equal(blocks.length, 2);
    const table = blocks[1];
    assert.equal(table.kind, 'table');
    if (table.kind !== 'table') return;
    assert.deepEqual(table.header.map((cell) => cell[0].text), ['Month', 'HKD', 'USD']);
    assert.equal(table.rows.length, 2);
    assert.deepEqual(table.numeric, [false, true, true]);
  });

  it('keeps a table that is still streaming in from swallowing the text after it', () => {
    const blocks = parseAnswerMarkdown('| Month | HKD |\n|---|---|\n| Jan | 1.00 |\nThat was all.');
    assert.deepEqual(blocks.map((b) => b.kind), ['table', 'paragraph']);
  });
});
