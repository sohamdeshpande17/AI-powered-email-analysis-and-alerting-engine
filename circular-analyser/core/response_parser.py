"""
LLM response parsing — extract structured JSON from raw LLM text output.
No internal dependencies.
"""

import json
from typing import Dict, Any


def parse_json_response(raw_text: str) -> Dict[str, Any]:
    """Parse LLM response text into a JSON dict, handling code fences."""
    text = raw_text.strip()

    # Strip markdown code fences if present
    if text.startswith("```"):
        lines = text.split("\n")
        lines = [l for l in lines if not l.strip().startswith("```")]
        text = "\n".join(lines)

    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        print(f"   ⚠️  Failed to parse LLM response as JSON: {e}")
        print(f"   Raw response: {text[:200]}")
        return make_error_result(
            f"LLM response was not valid JSON: {text[:200]}"
        )


def make_error_result(msg: str) -> Dict[str, Any]:
    """Create a standard error result dict."""
    return {
        "categories": ["unknown"],
        "confidence": 0.0,
        "sentiment": "unknown",
        "urgency": "medium",
        "summary": msg,
        "required_action": "Manual review required — processing failed",
        "key_entities": [],
        "_parse_error": True,
    }
