#!/usr/bin/env python3
"""Minimal Minecraft RCON client (stdlib only). Usage: rcon.py <cmd>"""
import socket, struct, sys

HOST, PORT, PASSWORD = "127.0.0.1", 25575, "testpw"
s = socket.create_connection((HOST, PORT), timeout=5)

def pkt(req_id, out, payload):
    body = struct.pack("<ii", req_id, out) + payload.encode() + b"\x00\x00"
    s.sendall(struct.pack("<i", len(body)) + body)
    n = struct.unpack("<i", s.recv(4))[0]
    data = b""
    while len(data) < n:
        data += s.recv(n - len(data))
    rid, out = struct.unpack("<ii", data[:8])
    return rid, data[8:-2].decode(errors="replace")

rid, _ = pkt(1, 3, PASSWORD)
if rid == -1:
    sys.exit("auth failed")
_, resp = pkt(2, 2, sys.argv[1])
print(resp)
