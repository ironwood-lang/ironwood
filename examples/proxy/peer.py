# SPDX-License-Identifier: MIT OR Apache-2.0
"""Local demonstration peers only. No DNS, Internet connection, or real credentials."""

from pathlib import Path
import socket
import subprocess
import threading


def exact(peer, length):
    result = b""
    while len(result) < length:
        data = peer.recv(length - len(result))
        if not data:
            raise RuntimeError("incomplete demonstration request")
        result += data
    return result


def demonstrate(kind):
    errors = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        listener.settimeout(5)

        def serve():
            try:
                peer, _ = listener.accept()
                with peer:
                    peer.settimeout(5)
                    if kind == "socks":
                        assert exact(peer, 3) == b"\x05\x01\x02"
                        peer.sendall(b"\x05\x02")
                        assert exact(peer, 11) == b"\x01\x04demo\x04pass"
                        peer.sendall(b"\x01\0")
                        assert exact(peer, 22) == b"\x05\x01\0\x03\x0fservice.invalid\0\x50"
                        peer.sendall(b"\x05\0\0\x01\0\0\0\0\0\0")
                    else:
                        head = b""
                        while not head.endswith(b"\r\n\r\n"):
                            head += exact(peer, 1)
                        assert head == (b"CONNECT service.invalid:80 HTTP/1.1\r\nHost: service.invalid:80\r\n"
                                        b"Proxy-Authorization: Basic ZGVtbzpwYXNz\r\n\r\n")
                        peer.sendall(b"HTTP/1.1 103 Early\r\n\r\nHTTP/1.1 200 Connected\r\n\r\n")
                    assert exact(peer, 1) == b"\x2a"
                    peer.sendall(b"\x2a")
                    assert peer.recv(1) == b""
            except Exception as error:
                errors.append(repr(error))

        worker = threading.Thread(target=serve)
        worker.start()
        command = [str(Path(__file__).parent / "target/ProxyTunnel"), kind, str(listener.getsockname()[1])]
        print("RUN - " + kind + " authenticated tunnel", flush=True)
        result = subprocess.run(command, timeout=10)
        worker.join(6)
        assert not worker.is_alive() and not errors, errors
        print("exit status:", result.returncode, flush=True)
        assert result.returncode == 42


if __name__ == "__main__":
    demonstrate("socks")
    demonstrate("http")
