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
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any


@dataclass
class LLMResponse:
    data: Any            # parsed JSON
    model: str
    raw: str
    metadata: dict[str, Any] = field(default_factory=dict)


class LLMUnavailable(RuntimeError):
    """Raised when no provider is configured. Callers must degrade gracefully."""


class LLMProvider(ABC):
    name: str = "abstract"

    @abstractmethod
    def complete_json(self, system: str, user: str, *, max_tokens: int = 16000) -> LLMResponse:
        """Return parsed JSON. Must raise on unparseable output, never guess."""

    def complete_structured(
        self, system: str, user: str, schema: dict[str, Any], *, max_tokens: int = 16000,
    ) -> LLMResponse:
        """Return JSON constrained to ``schema``.

        Providers without native structured output fall back to prompted JSON;
        callers validate the shape either way.
        """
        return self.complete_json(system, user, max_tokens=max_tokens)

    def complete_with_tools(
        self,
        system: str,
        user: str,
        tools: Sequence[dict[str, Any]],
        execute_tool: Callable[[str, dict[str, Any]], Any],
        *,
        max_turns: int = 4,
        max_tokens: int = 1600,
    ) -> LLMResponse:
        """Run a bounded client-tool loop."""
        raise LLMUnavailable(f"{self.name} does not support tool use")


def _extract_json(text: str) -> Any:
    """Pull JSON out of a response that may be wrapped in prose or fences."""
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", text, re.DOTALL)
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


# Models that take `output_config.effort`. Haiku 4.5 and older reject it.
_EFFORT_MODELS = re.compile(r"^claude-(?:opus|sonnet|haiku|fable|mythos)-5(?:$|-)")
# Models with the server-side refusal fallback ("default" routing). On a
# policy decline the API re-runs the request on a fallback model in the same
# call, so a false positive on a bank statement does not fail the feature.
_FALLBACK_MODELS = re.compile(r"^claude-(?:opus-5-5|sonnet-5-5|fable-5-1)(?:$|-)")
_FALLBACK_BETA = "server-side-fallback-2026-07-01"

#: Defaults per purpose. Classification is many small merchant batches where
#: judgement about abbreviated statement strings matters; analysis (Ask, the
#: import review) is a few longer, tool-using requests.
DEFAULT_MODELS = {"classification": "claude-sonnet-5-5", "analysis": "claude-opus-5-5"}
DEFAULT_EFFORT = {"classification": "low", "analysis": "medium"}


class AnthropicProvider(LLMProvider):
    """Claude via the official SDK.

    No sampling parameters are sent: current models accept only the defaults,
    and API version 2026-10-21 (anthropic 2.x) rejects them outright. Thinking
    runs adaptively on current models, so ``max_tokens`` budgets for it and
    only ``text`` blocks are read back.
    """

    name = "anthropic"

    def __init__(self, model: str = DEFAULT_MODELS["classification"],
                 api_key: str | None = None, effort: str | None = None):
        try:
            import anthropic
        except ImportError as e:
            raise LLMUnavailable(
                "pip install anthropic to enable the LLM layer") from e
        key = api_key or os.environ.get("ANTHROPIC_API_KEY")
        if not key:
            raise LLMUnavailable("ANTHROPIC_API_KEY is not set")
        self._anthropic = anthropic
        self._client = anthropic.Anthropic(api_key=key)
        self.model = model
        self.effort = effort

    def _create_message(self, **kwargs):
        """Call Anthropic without leaking vendor exceptions through the HTTP API."""
        try:
            if _FALLBACK_MODELS.match(self.model):
                msg = self._client.beta.messages.create(
                    betas=[_FALLBACK_BETA], fallbacks="default", **kwargs)
            else:
                msg = self._client.messages.create(**kwargs)
        except self._anthropic.AuthenticationError as exc:
            raise LLMUnavailable("Analysis credentials were rejected.") from exc
        except self._anthropic.RateLimitError as exc:
            raise LLMUnavailable("Analysis is rate-limited. Try again shortly.") from exc
        except self._anthropic.BadRequestError as exc:
            raise LLMUnavailable("The configured analysis model rejected the request.") from exc
        except self._anthropic.APIConnectionError as exc:
            raise LLMUnavailable("The analysis service is unreachable.") from exc
        except self._anthropic.APIStatusError as exc:
            raise LLMUnavailable("The analysis service returned an error.") from exc
        # A decline is an HTTP 200; its content is not an answer.
        if getattr(msg, "stop_reason", None) == "refusal":
            raise LLMUnavailable("The model declined this request.")
        return msg

    def _output_config(self, schema: dict[str, Any] | None = None) -> dict[str, Any]:
        config: dict[str, Any] = {}
        if self.effort and _EFFORT_MODELS.match(self.model):
            config["effort"] = self.effort
        if schema is not None:
            config["format"] = {"type": "json_schema", "schema": schema}
        return {"output_config": config} if config else {}

    def _system(self, system: str) -> list[dict[str, Any]]:
        return [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]

    def complete_json(self, system: str, user: str, *, max_tokens: int = 16000) -> LLMResponse:
        msg = self._create_message(
            model=self.model,
            max_tokens=max_tokens,
            system=self._system(system),
            **self._output_config(),
            messages=[{"role": "user", "content": user}],
        )
        text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
        return LLMResponse(
            data=_extract_json(text), model=self.model, raw=text,
            metadata={"usage": _usage(msg)},
        )

    def complete_structured(
        self, system: str, user: str, schema: dict[str, Any], *, max_tokens: int = 16000,
    ) -> LLMResponse:
        msg = self._create_message(
            model=self.model,
            max_tokens=max_tokens,
            system=self._system(system),
            **self._output_config(schema),
            messages=[{"role": "user", "content": user}],
        )
        text = "".join(b.text for b in msg.content if getattr(b, "type", "") == "text")
        return LLMResponse(
            data=json.loads(text), model=self.model, raw=text,
            metadata={"usage": _usage(msg)},
        )

    def complete_with_tools(
        self,
        system: str,
        user: str,
        tools: Sequence[dict[str, Any]],
        execute_tool: Callable[[str, dict[str, Any]], Any],
        *,
        max_turns: int = 4,
        max_tokens: int = 16000,
    ) -> LLMResponse:
        if not tools:
            raise ValueError("at least one tool is required")

        cached_tools = [dict(tool) for tool in tools]
        cached_tools[-1]["cache_control"] = {"type": "ephemeral"}
        cached_system = self._system(system)
        messages: list[dict[str, Any]] = [{"role": "user", "content": user}]
        usage: dict[str, int] = {}

        for turn in range(max_turns + 1):
            final_turn = turn == max_turns
            msg = self._create_message(
                model=self.model,
                max_tokens=max_tokens,
                system=cached_system,
                **self._output_config(),
                messages=messages,
                tools=cached_tools,
                **({"tool_choice": {"type": "none"}} if final_turn else {}),
            )
            _merge_usage(usage, _usage(msg))
            tool_blocks = [
                block for block in msg.content
                if getattr(block, "type", "") == "tool_use"
            ]
            if not tool_blocks or final_turn:
                text = "".join(
                    block.text for block in msg.content
                    if getattr(block, "type", "") == "text"
                ).strip()
                if not text:
                    raise ValueError("model returned no final answer")
                return LLMResponse(
                    data={"answer": text}, model=self.model, raw=text,
                    metadata={"usage": usage, "turns": turn + 1},
                )

            messages.append({"role": "assistant", "content": msg.content})
            results = []
            for block in tool_blocks:
                try:
                    value = execute_tool(block.name, dict(block.input or {}))
                    content = json.dumps(value, default=str, separators=(",", ":"))
                    is_error = False
                except (KeyError, TypeError, ValueError) as exc:
                    content = json.dumps({"error": str(exc)})
                    is_error = True
                results.append({
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": content,
                    "is_error": is_error,
                })
            messages.append({"role": "user", "content": results})

        raise ValueError("tool loop exceeded its turn limit")


def _usage(message) -> dict[str, int]:
    usage = getattr(message, "usage", None)
    if usage is None:
        return {}
    raw = usage.model_dump() if hasattr(usage, "model_dump") else vars(usage)
    out: dict[str, int] = {}
    for key in (
        "input_tokens", "output_tokens", "cache_creation_input_tokens",
        "cache_read_input_tokens",
    ):
        value = raw.get(key)
        if isinstance(value, int):
            out[key] = value
    creation = raw.get("cache_creation")
    if isinstance(creation, dict):
        out["cache_creation_input_tokens"] = sum(
            value for value in creation.values() if isinstance(value, int)
        )
    return out


def _merge_usage(total: dict[str, int], item: dict[str, int]) -> None:
    for key, value in item.items():
        total[key] = total.get(key, 0) + value


class NullProvider(LLMProvider):
    """Used when the LLM layer is disabled. Always raises."""

    name = "null"

    def complete_json(self, system: str, user: str, *, max_tokens: int = 16000) -> LLMResponse:
        raise LLMUnavailable("LLM layer is disabled (setting llm_enabled=0)")


class EchoProvider(LLMProvider):
    """Deterministic stub for tests. Returns a canned answer per task."""

    name = "echo"

    def __init__(self, canned: dict[str, Any] | None = None):
        self.canned = canned or {}
        self.calls: list[tuple[str, str]] = []

    def complete_json(self, system: str, user: str, *, max_tokens: int = 16000) -> LLMResponse:
        self.calls.append((system, user))
        for key, value in self.canned.items():
            if key in system or key in user:
                return LLMResponse(data=value, model="echo", raw=json.dumps(value))
        return LLMResponse(data=[], model="echo", raw="[]")


def build_provider(
    conn=None, *, model: str | None = None, purpose: str = "classification",
) -> LLMProvider:
    """Construct a provider from settings, degrading to Null when unavailable."""
    enabled, configured_model = True, model
    if conn is not None:
        rows = {r["key"]: r["value"] for r in conn.execute(
            "SELECT key, value FROM setting WHERE key IN "
            "('llm_enabled','llm_model','llm_agent_model')")}
        enabled = rows.get("llm_enabled", "0") == "1"
        setting = "llm_agent_model" if purpose == "analysis" else "llm_model"
        configured_model = model or rows.get(setting)
    env_enabled = os.environ.get("FINTO_LLM_ENABLED")
    if env_enabled is not None:
        enabled = env_enabled.strip().lower() in {"1", "true", "yes", "on"}
    env_model = os.environ.get(
        "FINTO_LLM_AGENT_MODEL" if purpose == "analysis" else "FINTO_LLM_MODEL"
    )
    configured_model = model or env_model or configured_model
    if not enabled:
        return NullProvider()
    try:
        return AnthropicProvider(
            model=configured_model or DEFAULT_MODELS.get(purpose, DEFAULT_MODELS["classification"]),
            effort=DEFAULT_EFFORT.get(purpose, "low"),
        )
    except LLMUnavailable:
        return NullProvider()
