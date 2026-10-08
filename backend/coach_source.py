"""Bounded public HTTPS fetch. Pin validated DNS addresses to prevent SSRF rebinding."""

import http.client
import ipaddress
import socket
import ssl
from time import monotonic
from html.parser import HTMLParser
from urllib.parse import urlsplit


class PageText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hidden = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style", "noscript"}:
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript"}:
            self.hidden = max(0, self.hidden - 1)

    def handle_data(self, data):
        if not self.hidden and data.strip():
            self.parts.append(data.strip())


def public_address(url: str):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Use a public HTTPS URL without credentials or a custom port")
    addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError("URL must resolve only to public addresses")
    return parsed, addresses[0][4][0]


class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self, host, address):
        super().__init__(host, timeout=8, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        sock = socket.create_connection((self.address, 443), self.timeout)
        try:
            self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
            self.read_socket = self.sock
        except BaseException:
            sock.close()
            raise


def fetch_source(url: str) -> str:
    parsed, address = public_address(url)
    connection = PinnedHTTPS(parsed.hostname, address)
    deadline = monotonic() + 8
    try:
        target = parsed.path or "/"
        if parsed.query:
            target += "?" + parsed.query
        connection.request("GET", target, headers={"User-Agent": "GlassesCoach/1.0", "Accept": "text/html,text/plain", "Accept-Encoding": "identity"})
        response = connection.getresponse()
        # No redirect, cookie, auth, JS or subresource fetching.
        if response.status != 200:
            raise ValueError("Page unavailable or redirects; supply its final HTTPS URL or read the recipe aloud")
        mime = response.getheader("Content-Type", "").split(";")[0].strip().lower()
        if mime not in {"text/html", "text/plain"} or response.getheader("Content-Encoding", "identity") != "identity":
            raise ValueError("Only uncompressed HTML and plain text pages are supported")
        chunks = []
        size = 0
        while True:
            remaining = deadline - monotonic()
            if remaining <= 0:
                raise TimeoutError("Page fetch timed out")
            # read1 returns after one buffered/socket read, so slow streams cannot evade the overall deadline.
            connection.read_socket.settimeout(remaining)
            chunk = response.read1(min(65536, 1_000_001 - size))
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
            if size > 1_000_000:
                raise ValueError("Page is too large; read the recipe aloud instead")
            if response.isclosed():
                break
        content = b"".join(chunks)
        text = content.decode("utf-8", errors="replace")
        if mime == "text/html":
            parser = PageText()
            parser.feed(text)
            text = "\n".join(parser.parts)
        if not text.strip() or len(text) > 50_000:
            raise ValueError("Page text is empty or too long; read the instructions aloud instead")
        return text
    finally:
        connection.close()
