"""Lossless PDF statement extraction.

A statement PDF is a *table*, and a table is two-dimensional. `pypdf`'s
`extract_text()` flattens it into reading order, which destroys exactly the
information that gives a number its meaning: an amount under "Withdrawal" and
the same amount under "Deposit" arrive as identical text. That is why the
line-oriented parser this replaces extracted nothing from ten of thirteen real
statement formats.

The pipeline here keeps the geometry:

    extract  -> words with coordinates, grouped into lines      (extract.py)
    layout   -> words assigned to named columns by x-position   (layout.py)
    template -> a declarative description of one issuer's table (template.py)
    verify   -> reconcile against the statement's own totals    (verify.py)

Extraction is lossless and issuer-agnostic: it is stored verbatim so a
statement can be re-parsed after a template improves without needing the
original file again. Interpretation lives entirely in templates, which are
data, not code — so a template can be written by a human, revised by an agent,
or derived from a sample page by an LLM (llm_derive.py) without touching the
parser.
"""

from .extract import PdfDocument, PdfPage, TextLine, Word, extract_document

__all__ = ["PdfDocument", "PdfPage", "TextLine", "Word", "extract_document"]
