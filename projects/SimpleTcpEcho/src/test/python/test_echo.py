#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise the actual run scripts using bounded, local TCP exchanges."""

from contextlib import contextmanager
from pathlib import Path
import re
import signal
import socket
import subprocess
import time


PROJECT = Path(__file__).resolve().parents[3]
OUT = PROJECT / "target/test"


class PortInUse(RuntimeError):
    pass


def run(program, *args, expected=0):
    result = subprocess.run(
        [str(PROJECT / f"run-{program}.sh"), *map(str, args)],
        cwd=OUT, stdin=subprocess.DEVNULL, capture_output=True, timeout=15,
    )
    assert result.returncode == expected, (
        f"{program} exit {result.returncode}, expected {expected}: "
        f"{result.stderr.decode(errors='replace')}"
    )
    return result


def client(*args, message="HiThere!"):
    result = run("client", *args)
    assert result.stdout == ("Got: " + message + "\n").encode(), result.stdout
    assert result.stderr == b"", result.stderr


@contextmanager
def server(name, *args):
    log = OUT / f"{name}-server.log"
    with log.open("wb") as errors:
        process = subprocess.Popen(
            [str(PROJECT / "run-server.sh"), *map(str, args)], cwd=OUT,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=errors,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 10
            while True:
                status = process.poll()
                text = log.read_text()
                if status == 74 and "Address unavailable or already in use" in text:
                    raise PortInUse(text)
                assert status is None, f"Server failed to start: {text}"
                ready = re.search(r"Listening on port (\d+)\n", text)
                if ready:
                    yield int(ready.group(1)), process
                    break
                assert time.monotonic() < deadline, f"Server readiness timed out: {log}"
                time.sleep(0.02)
        finally:
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    print("RUN - default arguments and repeated clients on port 55555", flush=True)
    # Read the server's announcement instead of probing with a throwaway connection.
    # If 55555 is occupied, do not connect to an unrelated server or stop its owner.
    try:
        with server("defaults") as (port, process):
            assert port == 55555, port
            client()
            client("127.0.0.1")
            client()
            assert process.poll() is None, "Server exited after a client disconnected"
    except PortInUse:
        print("SKIP - default-port exchange: port 55555 is unavailable; testing a free port below", flush=True)

    print("RUN - custom port, quoted text, UTF-8, empty and multi-buffer messages", flush=True)
    with server("custom", 0) as (port, process):
        client("localhost", port)
        for message in ("Hello from Ironwood!", "Olá, 世界! 🦊", "", "one\ntwo",
                        "quotes ' \" and $HOME; stay literal", "abcç" * 1000):
            client("127.0.0.1", port, message, message=message)
        assert process.poll() is None

        print("RUN - fragmented request and EOF framing", flush=True)
        with socket.create_connection(("127.0.0.1", port), timeout=10) as peer:
            for chunk in (b"Hi", b"There", b"!"):
                peer.sendall(chunk)
                time.sleep(0.02)
            peer.shutdown(socket.SHUT_WR)
            response = bytearray()
            while chunk := peer.recv(1024):
                response.extend(chunk)
            assert response == b"Got: HiThere!", response

        print("RUN - stalled client times out; the next client still succeeds", flush=True)
        with socket.create_connection(("127.0.0.1", port), timeout=10) as peer:
            peer.sendall(b"unfinished")
            # Without shutdownOutput/SHUT_WR there is no request EOF.
            assert peer.recv(1024) == b"", "Expected the server's read timeout to close this peer"
        client("localhost", port, "Still running", message="Still running")

        print("RUN - Ctrl+C stops the listening server", flush=True)
        process.send_signal(signal.SIGINT)
        assert process.wait(timeout=5) == -signal.SIGINT

    print("RUN - argument errors and connection refusal", flush=True)
    invalid = {
        "server": [("-1",), ("65536",), ("not-a-port",), ("55555", "extra")],
        "client": [("",), ("localhost", "0"), ("localhost", "65536"),
                   ("localhost", "not-a-port"), ("localhost", "55555", "hi", "extra")],
    }
    for program, cases in invalid.items():
        for args in cases:
            result = run(program, *args, expected=64)
            assert result.stdout == b"" and b"usage:" in result.stderr
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        result = run("client", "127.0.0.1", reserved.getsockname()[1], expected=74)
        assert result.stdout == b"" and b"Client failed:" in result.stderr
    print("PASS - SimpleTcpEcho local checks", flush=True)


if __name__ == "__main__":
    main()
