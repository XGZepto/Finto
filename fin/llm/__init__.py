"""Optional model-assisted categorisation, query, and adjudication.

The layer cannot modify monetary fields or resolve matches directly. Category
output is restricted to the configured taxonomy and all applied results retain
their source metadata.
"""

from .provider import (  # noqa: F401
    AnthropicProvider,
    EchoProvider,
    LLMProvider,
    LLMUnavailable,
    NullProvider,
    build_provider,
)
