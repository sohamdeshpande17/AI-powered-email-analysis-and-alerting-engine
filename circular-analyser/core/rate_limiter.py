"""
Thread-safe token-bucket style rate limiter for API calls.
No internal dependencies — can be used by any module.
"""

import time
import threading


class RateLimiter:
    """Limit API calls to a configurable calls-per-minute ceiling."""

    def __init__(self, max_calls_per_minute: int):
        self.max_calls = max_calls_per_minute
        self.window = 60.0  # seconds
        self.calls: list[float] = []
        self._lock = threading.Lock()

    def wait_if_needed(self):
        """Block until a call is allowed under the rate limit."""
        with self._lock:
            now = time.time()
            self.calls = [t for t in self.calls if now - t < self.window]

            if len(self.calls) >= self.max_calls:
                sleep_time = self.window - (now - self.calls[0]) + 0.1
                print(f"   ⏳ Rate limit reached ({self.max_calls} RPM). "
                      f"Waiting {sleep_time:.1f}s ...")
                time.sleep(sleep_time)

            self.calls.append(time.time())
