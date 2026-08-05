# Statement formats

Finto discovers transaction statement support from two registries:

- CSV parsers registered with `@register` in `fin/parsers/`
- active PDF templates from `fin/pdf/templates/` and the `pdf_template` table

`GET /api/imports/capabilities` and the Import page read those registries. A new
parser or active template appears in the product without editing the frontend.

## Add a PDF format

1. Remove names, addresses, account numbers, and transaction details from the
   sample statement. Do not commit real financial data.
2. Copy an existing JSON file in `fin/pdf/templates/` and change its identifiers,
   match rules, sections, columns, date rules, and balance verification.
3. Add a synthetic PDF regression fixture covering the same layout.
4. Run:

   ```bash
   .venv/bin/finto sniff synthetic-statement.pdf
   .venv/bin/pytest -q tests/test_pdf_templates.py
   ```

A deployment may also store an active template in `pdf_template`. Database
templates override bundled templates with the same `template_id` and are used
by both preview and import.

## Add a delimited-text format

1. Add a `StatementParser` subclass under `fin/parsers/`.
2. Set `parser_id`, `display_name`, `institution_ids`, `file_format`, and
   `extensions`.
3. Register it with `@register`.
4. Add a synthetic fixture and parser test.

Keep `sniff()` specific enough that the generic CSV parser cannot claim the
format accidentally. `parse()` must preserve raw rows and use signed minor-unit
amounts: negative for money out, positive for money in.

## Submit or request support

Open a pull request with the template or parser and its synthetic regression
fixture. If implementation is not available, open an issue with the institution,
product type, export format, country, and a list of column headings or PDF section
names. Never attach an unredacted statement.
