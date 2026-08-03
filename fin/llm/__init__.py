"""LLM assist layer.

Strictly optional and strictly bounded. The ledger is correct without it; the
LLM only improves categorisation quality and resolves genuinely ambiguous
matches. Enable with:

    python -m fin.cli config set llm_enabled 1
    export ANTHROPIC_API_KEY=...

What the LLM is NEVER allowed to do:
  * change an amount, currency, date or account
  * merge or unmerge transactions directly (it adjusts a score; the
    deterministic threshold still decides)
  * override a rule you wrote or a decision you made by hand
  * invent a category outside the closed taxonomy
"""

from .provider import (  # noqa: F401
    AnthropicProvider, EchoProvider, LLMProvider, LLMUnavailable, NullProvider,
    build_provider,
)
