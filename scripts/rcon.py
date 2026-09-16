#!/usr/bin/env python3
"""极简 RCON 客户端：给开发服务器发命令，用于跨服跑本里的脚本化操作。

    python3 scripts/rcon.py 25575 'transerver status'
"""
import socket
import struct
import sys


def packet(request_id, kind, body):
    payload = struct.pack('<ii', request_id, kind) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(payload)) + payload


def read(sock):
    size = struct.unpack('<i', sock.recv(4))[0]
    data = b''
    while len(data) < size:
        data += sock.recv(size - len(data))
    request_id, kind = struct.unpack('<ii', data[:8])
    return request_id, kind, data[8:-2].decode('utf-8', 'replace')


def run(port, command, host='127.0.0.1', password='distantstock'):
    with socket.create_connection((host, port), timeout=10) as sock:
        sock.sendall(packet(1, 3, password))
        if read(sock)[0] == -1:
            raise SystemExit('rcon 密码不对')
        sock.sendall(packet(2, 2, command))
        return read(sock)[2]


if __name__ == '__main__':
    print(run(int(sys.argv[1]), ' '.join(sys.argv[2:])))
