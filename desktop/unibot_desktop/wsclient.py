"""A small WebSocket client on the standard library (RFC 6455, text frames).

Enough for the hub: connect with headers over ws:// or wss://, send text, read
text, answer pings, close cleanly. No extensions, no proxies, no fragments on
the way out; fragmented incoming messages are reassembled.
"""

from __future__ import annotations

import base64
import hashlib
import os
import socket
import ssl
import struct
import threading
import urllib.parse

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OP_CONT, OP_TEXT, OP_BINARY, OP_CLOSE, OP_PING, OP_PONG = 0x0, 0x1, 0x2, 0x8, 0x9, 0xA


class ConnectionClosed(Exception):
    def __init__(self, code: int = 1006, reason: str = ""):
        super().__init__(f"websocket closed ({code}) {reason}".rstrip())
        self.code = code
        self.reason = reason


class WebSocket:
    def __init__(self, url: str, headers: dict[str, str] | None = None, timeout: float = 20.0, max_message: int = 16 * 1024 * 1024):
        self.url = url
        self.headers = headers or {}
        self.timeout = timeout
        self.max_message = max_message
        self._sock: socket.socket | None = None
        self._send_lock = threading.Lock()
        self._buf = b""
        self.closed = False

    # -- connect -----------------------------------------------------------------

    def connect(self) -> None:
        u = urllib.parse.urlsplit(self.url)
        scheme = {"ws": "ws", "wss": "wss", "http": "ws", "https": "wss"}.get(u.scheme or "")
        if scheme is None or not u.hostname:
            raise ValueError(f"not a WebSocket URL: {self.url}")
        port = u.port or (443 if scheme == "wss" else 80)
        path = (u.path or "/") + (f"?{u.query}" if u.query else "")
        raw = socket.create_connection((u.hostname, port), timeout=self.timeout)
        raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        if scheme == "wss":
            ctx = ssl.create_default_context()
            raw = ctx.wrap_socket(raw, server_hostname=u.hostname)
        key = base64.b64encode(os.urandom(16)).decode()
        host_header = u.hostname if port in (80, 443) else f"{u.hostname}:{port}"
        lines = [
            f"GET {path} HTTP/1.1",
            f"Host: {host_header}",
            "Upgrade: websocket",
            "Connection: Upgrade",
            f"Sec-WebSocket-Key: {key}",
            "Sec-WebSocket-Version: 13",
        ]
        for k, v in self.headers.items():
            lines.append(f"{k}: {v}")
        raw.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = raw.recv(4096)
            if not chunk:
                raise ConnectionClosed(1006, "closed during handshake")
            head += chunk
            if len(head) > 64 * 1024:
                raise ConnectionClosed(1002, "handshake too long")
        head, self._buf = head.split(b"\r\n\r\n", 1)
        status_line, *header_lines = head.decode("latin-1").split("\r\n")
        parts = status_line.split(" ", 2)
        if len(parts) < 2 or parts[1] != "101":
            raise ConnectionClosed(1002, f"handshake refused: {status_line}")
        hdrs = {k.strip().lower(): v.strip() for k, _, v in (h.partition(":") for h in header_lines)}
        want = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
        if hdrs.get("sec-websocket-accept") != want:
            raise ConnectionClosed(1002, "bad Sec-WebSocket-Accept")
        raw.settimeout(None)
        self._sock = raw

    # -- send --------------------------------------------------------------------

    def _frame(self, opcode: int, payload: bytes) -> bytes:
        head = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += struct.pack("!H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack("!Q", n)
        mask = os.urandom(4)
        head += mask
        masked = bytes(b ^ mask[i & 3] for i, b in enumerate(payload)) if n < 4096 else _mask_fast(payload, mask)
        return bytes(head) + masked

    def _send_frame(self, opcode: int, payload: bytes) -> None:
        if self._sock is None or self.closed:
            raise ConnectionClosed(1006, "not connected")
        data = self._frame(opcode, payload)
        with self._send_lock:
            try:
                self._sock.sendall(data)
            except OSError as e:
                self.closed = True
                raise ConnectionClosed(1006, str(e)) from e

    def send_text(self, text: str) -> None:
        self._send_frame(OP_TEXT, text.encode("utf-8"))

    def ping(self, payload: bytes = b"") -> None:
        self._send_frame(OP_PING, payload)

    def close(self, code: int = 1000, reason: str = "") -> None:
        if self.closed:
            return
        try:
            self._send_frame(OP_CLOSE, struct.pack("!H", code) + reason.encode("utf-8")[:120])
        except ConnectionClosed:
            pass
        self.closed = True
        try:
            if self._sock:
                self._sock.close()
        except OSError:
            pass

    # -- receive -----------------------------------------------------------------

    def _read(self, n: int) -> bytes:
        assert self._sock is not None
        while len(self._buf) < n:
            try:
                chunk = self._sock.recv(max(65536, n - len(self._buf)))
            except OSError as e:
                self.closed = True
                raise ConnectionClosed(1006, str(e)) from e
            if not chunk:
                self.closed = True
                raise ConnectionClosed(1006, "connection lost")
            self._buf += chunk
        out, self._buf = self._buf[:n], self._buf[n:]
        return out

    def _read_frame(self) -> tuple[bool, int, bytes]:
        b1, b2 = self._read(2)
        fin = bool(b1 & 0x80)
        opcode = b1 & 0x0F
        masked = bool(b2 & 0x80)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack("!H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack("!Q", self._read(8))[0]
        if n > self.max_message:
            self.close(1009, "message too big")
            raise ConnectionClosed(1009, "message too big")
        mask = self._read(4) if masked else None
        payload = self._read(n)
        if mask:
            payload = _mask_fast(payload, mask)
        return fin, opcode, payload

    def recv_text(self) -> str:
        """The next text message; pings are answered, a close frame raises."""
        parts: list[bytes] = []
        first_op = None
        while True:
            fin, opcode, payload = self._read_frame()
            if opcode == OP_PING:
                try:
                    self._send_frame(OP_PONG, payload)
                except ConnectionClosed:
                    pass
                continue
            if opcode == OP_PONG:
                continue
            if opcode == OP_CLOSE:
                code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1005
                reason = payload[2:].decode("utf-8", "replace")
                self.close(code)
                raise ConnectionClosed(code, reason)
            if opcode in (OP_TEXT, OP_BINARY):
                first_op = opcode
                parts = [payload]
            elif opcode == OP_CONT:
                parts.append(payload)
            else:
                continue
            if sum(map(len, parts)) > self.max_message:
                self.close(1009, "message too big")
                raise ConnectionClosed(1009, "message too big")
            if fin:
                data = b"".join(parts)
                if first_op == OP_BINARY:
                    return data.decode("utf-8", "replace")
                return data.decode("utf-8")


def _mask_fast(payload: bytes, mask: bytes) -> bytes:
    """XOR with a 4-byte mask, in big-int strides (no numpy, no C)."""
    n = len(payload)
    if n == 0:
        return b""
    reps = (n + 3) // 4
    big_mask = int.from_bytes(mask * reps, "big") >> ((reps * 4 - n) * 8)
    return (int.from_bytes(payload, "big") ^ big_mask).to_bytes(n, "big")
