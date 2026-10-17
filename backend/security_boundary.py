"""Bound request allocation and work before FastAPI parses attacker-controlled input."""

import asyncio
import os
import time
from collections import defaultdict, deque

from starlette.responses import JSONResponse


class SecurityBoundary:
    def __init__(self, app):
        self.app = app
        self.windows = defaultdict(deque)
        self.active = 0
        self.events = 0
        self.photos = 0

    def allow(self, lane, limit):
        now = time.monotonic()
        window = self.windows[lane]
        while window and window[0] <= now - 60:
            window.popleft()
        if len(window) >= limit:
            return False
        window.append(now)
        return True

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        path = scope['path']
        headers = dict(scope['headers'])
        browser = path == '/dashboard' or path.startswith('/dashboard/')
        # Host is a trust boundary even on localhost (browser DNS rebinding).
        allowed = {h.strip().lower() for h in os.getenv('BACKEND_ALLOWED_HOSTS', 'localhost,127.0.0.1,::1').split(',')}
        host = headers.get(b'host', b'').decode('latin1').lower()
        hostname = host[1:host.find(']')] if host.startswith('[') else host.split(':')[0]

        async def reject(status, detail):
            await JSONResponse({'detail': detail}, status_code=status,
                               headers={'Cache-Control': 'no-store'})(scope, receive, send)

        if hostname not in allowed:
            return await reject(400, 'Unrecognized backend host')
        # Authenticate before buffering API bodies, including chunked requests.
        if not browser:
            import hmac
            secret = os.getenv('APP_SHARED_SECRET', '').encode()
            supplied = headers.get(b'x-app-secret', b'')
            if not secret or not hmac.compare_digest(supplied, secret):
                status = (401 if secret else 503) if self.allow('auth', 60) else 429
                return await reject(status, 'Authentication failed')
        if path == '/dashboard/login' and not self.allow('login', 30):
            return await reject(429, 'Too many login attempts; wait a minute')
        expensive = path in {'/token', '/visual/frames', '/tools/start_project', '/dashboard/status/token', '/health/gemini', '/dashboard/status/gemini'} or path.endswith(('/end', '/process'))
        lane, limit = ('expensive', 30) if expensive else ('browser', 240) if browser else ('api', 240)
        if not self.allow(lane, limit):
            return await reject(429, 'Request rate exceeded; wait a minute')
        photo = path in {'/photos', '/pages'}
        if photo and (self.photos >= 1 or not self.allow('photos', 6)):
            return await reject(429, 'Photo rate exceeded; retry later')
        event = path == '/events'
        if self.active >= 16 or (event and self.events >= 4):
            return await reject(429, 'Backend busy; retry later')
        self.active += 1
        self.events += int(event)
        self.photos += int(photo)
        try:
            audio_chunk = path.startswith('/meetings/') and '/chunks/' in path
            bound = 12_000_000 if photo else 2_000_000 if audio_chunk else 64_000 if browser else 4_000_000 if path in {'/transcripts', '/visual/frames'} else 500_000
            body = bytearray()
            try:
                async with asyncio.timeout(15):
                    while True:
                        message = await receive()
                        if message['type'] == 'http.disconnect':
                            return
                        chunk = message.get('body', b'')
                        if len(body) + len(chunk) > bound:
                            return await reject(413, 'Request too large')
                        body.extend(chunk)
                        if not message.get('more_body', False):
                            break
            except TimeoutError:
                return await reject(408, 'Request body timed out')
            delivered = False

            async def bounded_receive():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {'type': 'http.request', 'body': bytes(body), 'more_body': False}
                return await receive()

            await self.app(scope, bounded_receive, send)
        finally:
            self.active -= 1
            self.events -= int(event)
            self.photos -= int(photo)
