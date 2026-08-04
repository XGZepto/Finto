"""Lossless, layout-preserving text extraction.

The output of this module is deliberately *uninterpreted*. It reports where
every word sits on the page and nothing about what any word means. All the
issuer-specific judgement happens later, against templates, so that improving
our understanding of a statement never requires re-reading the original file.

Words are grouped into lines by vertical position rather than by the order they
appear in the content stream. PDF content streams are under no obligation to be
in reading order — Chase's statements interleave marketing copy with the balance
summary — so clustering on the y-coordinate is the only reliable way to recover
the rows a human sees.
"""

from __future__ import annotations

import hashlib
import json
import logging
from dataclasses import asdict, dataclass, field
from functools import lru_cache
from pathlib import Path

# pdfminer logs a warning per malformed font descriptor. Statement PDFs are full
# of them and they say nothing about whether extraction succeeded, so they would
# only bury the warnings that do matter.
logging.getLogger("pdfminer").setLevel(logging.ERROR)

# Words whose baselines differ by less than this are the same visual row. Two
# points is comfortably below single-line spacing at statement font sizes while
# still tolerating the sub-point jitter of justified text.
_LINE_TOLERANCE = 2.0


class PdfTextLayerMissing(RuntimeError):
    """The PDF has no extractable text — it is an image, and needs OCR.

    Raised rather than returning nothing, because a scan that silently yields
    zero transactions looks identical to a statement month with no activity.
    """


@dataclass(frozen=True)
class Word:
    """A single word and the box it occupies, in PDF points from top-left."""

    text: str
    x0: float
    x1: float
    top: float
    bottom: float

    @property
    def mid(self) -> float:
        """Horizontal centre — used to assign a word to a column."""
        return (self.x0 + self.x1) / 2


@dataclass
class TextLine:
    """One visual row of the page."""

    words: list[Word]
    top: float
    page_no: int
    line_no: int

    @property
    def text(self) -> str:
        """Words joined by single spaces, with no column information."""
        return " ".join(w.text for w in self.words)

    def layout_text(self, scale: float = 0.55) -> str:
        """Words padded to their real horizontal positions.

        Reproduces the visual alignment in a monospace string, which is what
        makes a statement legible to a human reader — and to an LLM asked to
        describe the layout. `scale` converts PDF points to character cells.
        """
        out = ""
        for w in self.words:
            col = int(w.x0 * scale)
            if col > len(out):
                out += " " * (col - len(out))
            elif out and not out.endswith(" "):
                out += " "
            out += w.text
        return out

    def words_between(self, x0: float, x1: float) -> list[Word]:
        """Words whose centre falls inside [x0, x1)."""
        return [w for w in self.words if x0 <= w.mid < x1]

    def text_between(self, x0: float, x1: float) -> str:
        return " ".join(w.text for w in self.words_between(x0, x1))


@dataclass
class PdfPage:
    page_no: int
    width: float
    height: float
    lines: list[TextLine] = field(default_factory=list)

    @property
    def text(self) -> str:
        return "\n".join(ln.text for ln in self.lines)

    @property
    def layout(self) -> str:
        return "\n".join(ln.layout_text() for ln in self.lines)


@dataclass
class PdfDocument:
    path: str
    pages: list[PdfPage]
    #: sha256 of the file, so a stored extraction can be tied to its source.
    content_hash: str = ""

    @property
    def text(self) -> str:
        return "\n".join(p.text for p in self.pages)

    @property
    def layout(self) -> str:
        return "\n\n".join(p.layout for p in self.pages)

    def all_lines(self) -> list[TextLine]:
        return [ln for p in self.pages for ln in p.lines]

    def to_json(self) -> str:
        """Serialise the full extraction, coordinates included.

        This is the archival form: enough to re-run any template against the
        statement without the original PDF.
        """
        return json.dumps(
            {
                "path": self.path,
                "content_hash": self.content_hash,
                "pages": [
                    {
                        "page_no": p.page_no,
                        "width": p.width,
                        "height": p.height,
                        "lines": [
                            {
                                "top": ln.top,
                                "line_no": ln.line_no,
                                "words": [asdict(w) for w in ln.words],
                            }
                            for ln in p.lines
                        ],
                    }
                    for p in self.pages
                ],
            },
            separators=(",", ":"),
        )

    @classmethod
    def from_json(cls, blob: str) -> PdfDocument:
        d = json.loads(blob)
        pages = []
        for p in d["pages"]:
            lines = [
                TextLine(
                    words=[Word(**w) for w in ln["words"]],
                    top=ln["top"],
                    page_no=p["page_no"],
                    line_no=ln["line_no"],
                )
                for ln in p["lines"]
            ]
            pages.append(PdfPage(p["page_no"], p["width"], p["height"], lines))
        return cls(path=d["path"], pages=pages, content_hash=d.get("content_hash", ""))


def extract_document(path: str | Path) -> PdfDocument:
    """Read a PDF into positioned words. Raises if there is no text layer.

    Cached on the file's identity, because every import reads each PDF twice —
    once for the parser to recognise it, once to extract it — and the parse is
    by far the most expensive step in an import.
    """
    path = Path(path)
    st = path.stat()
    return _extract_cached(str(path), st.st_mtime_ns, st.st_size)


@lru_cache(maxsize=8)
def _extract_cached(path_str: str, _mtime_ns: int, _size: int) -> PdfDocument:
    try:
        import pdfplumber
    except ImportError as e:  # pragma: no cover - depends on optional extra
        raise RuntimeError(
            "PDF support needs pdfplumber — install with: pip install 'finto[pdf]'"
        ) from e

    path = Path(path_str)
    content_hash = hashlib.sha256(path.read_bytes()).hexdigest()

    pages: list[PdfPage] = []
    total_words = 0
    with pdfplumber.open(str(path)) as pdf:
        for i, page in enumerate(pdf.pages):
            # x_tolerance is tight: statements set description columns in
            # narrow type, and a loose value glues adjacent columns into one
            # word, which would put an amount inside the description.
            raw = page.extract_words(
                x_tolerance=1.5, y_tolerance=2.0, keep_blank_chars=False
            )
            total_words += len(raw)
            pages.append(
                PdfPage(
                    page_no=i,
                    width=float(page.width),
                    height=float(page.height),
                    lines=_group_into_lines(raw, i),
                )
            )

    if total_words == 0:
        raise PdfTextLayerMissing(
            f"{path.name} has no text layer — it is probably a scan. "
            "Export a CSV from the issuer, or OCR the file first."
        )
    return PdfDocument(path=str(path), pages=pages, content_hash=content_hash)


def _group_into_lines(raw_words: list[dict], page_no: int) -> list[TextLine]:
    """Cluster words into visual rows by their vertical position."""
    words = [
        Word(
            text=w["text"],
            x0=float(w["x0"]),
            x1=float(w["x1"]),
            top=float(w["top"]),
            bottom=float(w["bottom"]),
        )
        for w in raw_words
        if w["text"].strip()
    ]
    words.sort(key=lambda w: (w.top, w.x0))

    lines: list[TextLine] = []
    current: list[Word] = []
    anchor: float | None = None
    for w in words:
        if anchor is None or abs(w.top - anchor) <= _LINE_TOLERANCE:
            current.append(w)
            # Track the topmost baseline so a tall glyph does not drag the
            # cluster downward and absorb the row beneath it.
            anchor = w.top if anchor is None else min(anchor, w.top)
        else:
            lines.append(_finish_line(current, page_no, len(lines)))
            current, anchor = [w], w.top
    if current:
        lines.append(_finish_line(current, page_no, len(lines)))
    return lines


def _finish_line(words: list[Word], page_no: int, line_no: int) -> TextLine:
    words = sorted(words, key=lambda w: w.x0)
    return TextLine(
        words=words, top=words[0].top, page_no=page_no, line_no=line_no
    )
