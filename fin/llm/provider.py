"""LLM provider abstraction.

The rest of the codebase talks to `LLMProvider`, never to a vendor SDK. Three
implementations ship: Anthropic, a deterministic Echo stub for tests, and a
Null provider that refuses politely so the whole pipeline still runs with the
LLM layer switched off.
"""

from __future__ import annotations

import json
import os
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any


@dataclass
class LLMResponse:
    data: Any            # parsed JSON
    model: str
    raw: str


class LLMUnavailable(RuntimeError):
    """Raised when no provider is configured. Callers must degrade gracefully."""


class LLMProvider(ABC):
    name: str = "abstract"

    @abstractmethod
    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> LLMResponse:
        """Return parsed JSON. Must raise on unparseable output, never guess."""


def _extract_json(text: str) -> Any:
    """Pull JSON out of a response that may be wrapped in prose or fences."""
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    if fence:
        text = fence.group(1).strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass
    # Fall back to the outermost bracketed span.
    for opener, closer in (("[", "]"), ("{", "}")):
        start, end = text.find(opener), text.rfind(closer)
        if start != -1 and end > start:
            try:
                return json.loads(text[start:end + 1])
            except json.JSONDecodeError:
                continue
    raise ValueError(f"no parseable JSON in response: {text[:200]!r}")


class AnthropicProvider(LLMProvider):
    """Claude via the official SDK.

    Defaults to Haiku: these are small, highly repetitive classification calls
    where latency and cost matter more than reasoning depth, and every answer
    is schema-validated and confidence-gated downstream anyway.
    """

    name = "anthropic"

    def __init__(self, model: str = "claude-haiku-4-5-20251001", api_key: str | None = None):
        try:
            import anthropic
        except ImportError as e:
            raise LLMUnavailable(
                "pip install anthropic to enable the LLM layer") from e
        key = api_key or os.environ.get("ANTHROPIC_API_KEY")
        if not key:
            raise LLMUnavailable("ANTHROPIC_API_KEY is not set")
        self._client = anthropic.Anthropic(api_key=key)
        self.model = model

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> LLMResponse:
        msg = self._client.messages.create(
            model=self.model,
            max_tokens=max_tokens,
            system=system,
            temperature=0,          # classification, not creativity
            messages=[{"role": "user", "content": user}],
        )
        text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
        return LLMResponse(data=_extract_json(text), model=self.model, raw=text)


class NullProvider(LLMProvider):
    """Used when the LLM layer is disabled. Always raises."""

    name = "null"

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> LLMResponse:
        raise LLMUnavailable("LLM layer is disabled (setting llm_enabled=0)")


class EchoProvider(LLMProvider):
    """Deterministic stub for tests. Returns a canned answer per task."""

    name = "echo"

    def __init__(self, canned: dict[str, Any] | None = None):
        self.canned = canned or {}
        self.calls: list[tuple[str, str]] = []

    def complete_json(self, system: str, user: str, *, max_tokens: int = 2000) -> LLMResponse:
        self.calls.append((system, user))
        for key, value in self.canned.items():
            if key in system or key in user:
                return LLMResponse(data=value, model="echo", raw=json.dumps(value))
        return LLMResponse(data=[], model="echo", raw="[]")


def build_provider(conn=None, *, model: str | None = None) -> LLMProvider:
    """Construct a provider from settings, degrading to Null when unavailable."""
    enabled, configured_model = True, model
    if conn is not None:
        rows = {r[0]: r[1] for r in conn.execute(
            "SELECT key, value FROM setting WHERE key IN ('llm_enabled','llm_model')")}
        enabled = rows.get("llm_enabled", "0") == "1"
        configured_model = model or rows.get("llm_model")
    if not enabled:
        return NullProvider()
    try:
        return AnthropicProvider(model=configured_model or "claude-haiku-4-5-20251001")
    except LLMUnavailable:
        return NullProvider()
