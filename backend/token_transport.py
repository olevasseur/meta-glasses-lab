"""One reusable token HTTP transport for this personal backend worker.

Cache connections, never single-use tokens or their memory/persona constraints.
"""
from contextlib import ExitStack, contextmanager
from threading import RLock

import httpx
from google import genai
from google.genai import types


class TokenTransport:
    def __init__(self):
        self._lock = RLock()
        self._stack = ExitStack()
        self._client = None
        self._key = None

    @contextmanager
    def client(self, api_key, timeout_ms):
        # Serialize creation/rotation/close with requests; never close an in-flight client.
        with self._lock:
            key = (api_key, timeout_ms)
            if self._client is None or self._key != key:
                self.close()
                self._client = self._stack.enter_context(genai.Client(
                    api_key=api_key, vertexai=False,
                    http_options=types.HttpOptions(api_version='v1beta', timeout=timeout_ms,
                        client_args={'limits': httpx.Limits(max_connections=2, max_keepalive_connections=2, keepalive_expiry=120)},
                        retry_options=types.HttpRetryOptions(attempts=1)),
                ))
                self._key = key
            yield self._client

    def close(self):
        with self._lock:
            try:
                self._stack.close()
            finally:
                self._client = None
                self._key = None


token_transport = TokenTransport()
