"""
Claude (Anthropic) LLM provider — real-time processing.
Depends on: core.rate_limiter, core.prompt_builder, core.response_parser, config.
"""

import time
from typing import Dict, Any, Optional, Tuple

from anthropic import Anthropic

import config
from core.rate_limiter import RateLimiter
from core.prompt_builder import build_system_prompt
from core.response_parser import parse_json_response
from providers.base import BaseLLMProvider


class ClaudeProvider(BaseLLMProvider):
    """Anthropic Claude provider — singleton-style via class-level state."""

    _client: Optional[Anthropic] = None
    _rate_limiter = RateLimiter(config.CLAUDE_RPM_LIMIT)

    @property
    def name(self) -> str:
        return "claude"

    @property
    def model(self) -> str:
        return config.CLAUDE_MODEL

    def _get_client(self) -> Anthropic:
        if ClaudeProvider._client is None:
            if not config.CLAUDE_API_KEY:
                raise ValueError("CLAUDE_API_KEY is not set.")
            # max_retries: the SDK retries transient errors (429, timeouts, and
            # 5xx including 529 "overloaded") with exponential backoff + jitter,
            # honouring retry-after headers. Raised from the SDK default of 2 to
            # ride out longer API overloads.
            ClaudeProvider._client = Anthropic(
                api_key=config.CLAUDE_API_KEY,
                max_retries=config.CLAUDE_MAX_RETRIES,
            )
        return ClaudeProvider._client

    def process_realtime(self, email_text: str) -> Tuple[Dict[str, Any], float]:
        client = self._get_client()
        self._rate_limiter.wait_if_needed()
        start = time.time()

        system_prompt = build_system_prompt()
        system_prompt += "\n\nCRITICAL: You must output ONLY RAW JSON. No surrounding markdown, no explanations."

        message = client.messages.create(
            model=config.CLAUDE_MODEL,
            max_tokens=config.CLAUDE_MAX_TOKENS,
            temperature=config.CLAUDE_TEMPERATURE,
            system=system_prompt,
            messages=[
                {"role": "user", "content": f"Email Content:\n{email_text}"}
            ],
        )

        elapsed = time.time() - start
        raw_text = message.content[0].text.strip()
        return parse_json_response(raw_text), elapsed
