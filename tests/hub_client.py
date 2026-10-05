"""Tiny stdlib HTTP + WebSocket client used by the mock hub tests."""
import base64
import json
import os
import socket
import struct
import urllib.error
import urllib.request


def call(base, method, path, body=None, token=None, headers=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + path, data=data, method=method)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        resp = urllib.request.urlopen(req, timeout=3.0)
        payload = resp.read()
        status, hdrs = resp.status, resp.headers
    except urllib.error.HTTPError as e:
        payload, status, hdrs = e.read(), e.code, e.headers
    try:
        return status, json.loads(payload.decode()), hdrs
    except ValueError:
        return status, payload, hdrs


def login(base, lrn, pin="1234"):
    status, body, _ = call(base, "POST", "/api/auth/login", {"lrn_or_id": lrn, "pin": pin})
    assert status == 200, body
    return body["token"]


class WsClient:
    def __init__(self, host, port, token=None):
        self.sock = socket.create_connection((host, port), timeout=3.0)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            head += self.sock.recv(1)
        assert b"101" in head.split(b"\r\n")[0], head
        if token:
            self.send({"event": "EVENT_HELLO", "token": token})
            ack = self.recv()
            assert ack["event"] == "EVENT_HELLO_ACK", ack

    def send(self, obj):
        payload = json.dumps(obj).encode()
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        n = len(payload)
        header = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n))
        self.sock.sendall(header + mask + masked)

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("closed")
            buf += chunk
        return buf

    def recv(self):
        b1, b2 = self._exact(2)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._exact(8))[0]
        data = self._exact(n)
        if (b1 & 0x0F) == 0x8:
            return {"event": "CLOSED", "code": struct.unpack(">H", data[:2])[0]}
        return json.loads(data.decode())

    def recv_until(self, event):
        for _ in range(50):
            msg = self.recv()
            if msg["event"] == event:
                return msg
        raise AssertionError("never received " + event)

    def close(self):
        self.sock.close()
