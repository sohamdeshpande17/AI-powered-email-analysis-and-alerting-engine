"""
LLM provider factory — returns the correct provider instance
based on config.LLM_PROVIDER.
"""

from typing import TYPE_CHECKING

import config

if TYPE_CHECKING:
    from providers.base import BaseLLMProvider


def get_provider() -> "BaseLLMProvider":
    """Return a configured LLM provider instance (singleton per type)."""
    name = config.LLM_PROVIDER.lower()

    if name == "claude":
        from providers.claude import ClaudeProvider
        return ClaudeProvider()
    elif name == "openai":
        from providers.openai import OpenAIProvider
        return OpenAIProvider()
    else:
        raise ValueError(
            f"Unknown LLM_PROVIDER: '{name}'. Use 'claude' or 'openai'."
        )
