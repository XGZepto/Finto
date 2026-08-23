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
});
