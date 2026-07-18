"""
OpenAI LLM provider — real-time processing.
Depends on: core.rate_limiter, core.prompt_builder, core.response_parser, config.
"""

import time
from typing import Dict, Any, Optional, Tuple

from openai import OpenAI

import config
from core.rate_limiter import RateLimiter
from core.prompt_builder import build_system_prompt
from core.response_parser import parse_json_response
from providers.base import BaseLLMProvider


class OpenAIProvider(BaseLLMProvider):
    """OpenAI provider — singleton-style via class-level state."""

    _client: Optional[OpenAI] = None
    _rate_limiter = RateLimiter(config.OPENAI_RPM_LIMIT)

    @property
    def name(self) -> str:
        return "openai"

    @property
    def model(self) -> str:
        return config.OPENAI_MODEL

    def _get_client(self) -> OpenAI:
        if OpenAIProvider._client is None:
            if not config.OPENAI_API_KEY:
                raise ValueError("OPENAI_API_KEY is not set.")
            OpenAIProvider._client = OpenAI(api_key=config.OPENAI_API_KEY)
        return OpenAIProvider._client

    def process_realtime(self, email_text: str) -> Tuple[Dict[str, Any], float]:
        client = self._get_client()
        self._rate_limiter.wait_if_needed()
        start = time.time()

        response = client.chat.completions.create(
            model=config.OPENAI_MODEL,
            messages=[
                {"role": "system", "content": build_system_prompt()},
                {"role": "user", "content": f"Email Content:\n{email_text}"},
            ],
            max_tokens=config.OPENAI_MAX_TOKENS,
            temperature=config.OPENAI_TEMPERATURE,
            response_format={"type": "json_object"},
        )

        elapsed = time.time() - start
        raw_text = response.choices[0].message.content.strip()
        return parse_json_response(raw_text), elapsed
