"""
Abstract base class for LLM providers.

v3: the processor analyses each circular in real time as it arrives from
Kafka — the v2 async batch interface was removed with the polling pipeline.
"""

from abc import ABC, abstractmethod
from typing import Dict, Any, Tuple


class BaseLLMProvider(ABC):
    """Interface that every LLM provider must satisfy."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Short provider name (e.g. 'claude', 'openai')."""

    @property
    @abstractmethod
    def model(self) -> str:
        """Model identifier currently in use."""

    @abstractmethod
    def process_realtime(self, email_text: str) -> Tuple[Dict[str, Any], float]:
        """
        Process a single document in real-time.
        Returns (parsed_result, elapsed_seconds).
        """
