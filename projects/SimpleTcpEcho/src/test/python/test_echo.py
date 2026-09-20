#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise the actual run scripts using bounded, local TCP exchanges."""

from contextlib import contextmanager
from pathlib import Path
import errno
import os
import pty
import re
import signal
import socket
import subprocess
import threading
import time
import tty


PROJECT = Path(__file__).resolve().parents[3]
OUT = PROJECT / "target/test"
MAX_MESSAGE_BYTES = 1024


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
    assert result.stdout == f"SENT: {message}\nGOT: =[{message}]=\n".encode(), result.stdout
    assert result.stderr == b"", result.stderr


def server_output(name, messages):
    actual = (OUT / f"{name}-stdout.log").read_bytes()
    # The real server announces readiness on stdout; the allocation probe uses stderr.
    actual = re.sub(rb"\AListening on port \d+\n", b"", actual, count=1)
    encoded = [message.encode() if isinstance(message, str) else message for message in messages]
    expected = b"".join(b"GOT: " + message + b"\nREPLIED: =[" + message + b"]=\n" for message in encoded)
    assert actual == expected, f"Unexpected server output in {name}-stdout.log"


def raw_exchange(port, message):
    with socket.create_connection(("127.0.0.1", port), timeout=10) as peer:
        peer.sendall(message)
        peer.shutdown(socket.SHUT_WR)
        response = bytearray()
        while chunk := peer.recv(65536):
            response.extend(chunk)
        assert response == b"=[" + message + b"]=", f"Incorrect echo for {len(message)} bytes"


@contextmanager
def terminal_output(path):
    # Keep stdout line-buffered as in an interactive run. Raw mode preserves binary
    # echo bytes, including newlines, and the reader drains output throughout the test.
    master, slave = pty.openpty()
    tty.setraw(slave)
    failures = []
    with path.open("wb", buffering=0) as output:
        def drain():
            try:
                while chunk := os.read(master, 65536):
                    output.write(chunk)
            except OSError as error:
                # Linux signals the last slave's close with EIO; macOS returns EOF.
                if error.errno != errno.EIO:
                    failures.append(error)

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        try:
            yield slave
        finally:
            os.close(slave)
            reader.join(timeout=5)
            os.close(master)
            assert not reader.is_alive(), "Server stdout reader did not stop"
            assert not failures, f"Cannot capture server stdout: {failures}"


@contextmanager
def server(name, *args, executable=None):
    log = OUT / f"{name}-server.log"
    stdout_log = OUT / f"{name}-stdout.log"
    with log.open("wb") as errors, terminal_output(stdout_log) as output:
        process = subprocess.Popen(
            [str(executable or PROJECT / "run-server.sh"), *map(str, args)], cwd=OUT,
            stdin=subprocess.DEVNULL, stdout=output, stderr=errors,
            start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 10
            while True:
                status = process.poll()
                text = log.read_text() + stdout_log.read_text(errors="replace")
                if status == 74 and "Address unavailable or already in use" in text:
                    raise PortInUse(text)
                assert status is None, f"Server failed to start: {text}"
                ready = re.search(r"Listening on port (\d+)\n", text)
                if ready:
                    yield int(ready.group(1)), process
                    break
                assert time.monotonic() < deadline, f"Server readiness timed out: {log}, {stdout_log}"
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
    print("RUN - default arguments and repeated clients on port 55556", flush=True)
    # Read the server's announcement instead of probing with a throwaway connection.
    # If 55556 is occupied, do not connect to an unrelated server or stop its owner.
    try:
        with server("defaults") as (port, process):
            assert port == 55556, port
            client()
            client("127.0.0.1")
            client()
            assert process.poll() is None, "Server exited after a client disconnected"
        server_output("defaults", ["HiThere!"] * 3)
    except PortInUse:
        print("SKIP - default-port exchange: port 55556 is unavailable; testing a free port below", flush=True)

    print("RUN - custom port, quoted text, UTF-8, empty and longer messages", flush=True)
    messages = ["Hello from Ironwood!", "Olá, 世界! 🦊", "", "one\ntwo",
                "quotes ' \" and $HOME; stay literal", "abcç" * 200]
    # This 1,028-byte reply spans many 64-byte reads and includes multibyte UTF-8.
    messages.append("a" * 1021 + "€")
    with server("custom", 0) as (port, process):
        client("localhost", port)
        for message in messages:
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
            assert response == b"=[HiThere!]=", response

        print("RUN - request waits for EOF; the next client still succeeds", flush=True)
        with socket.create_connection(("127.0.0.1", port), timeout=10) as peer:
            peer.sendall(b"unfinished")
            peer.settimeout(0.2)
            try:
                peer.recv(1024)
                raise AssertionError("Server replied or closed before request EOF")
            except socket.timeout:
                pass
            peer.settimeout(10)
            peer.shutdown(socket.SHUT_WR)
            response = bytearray()
            while chunk := peer.recv(1024):
                response.extend(chunk)
            assert response == b"=[unfinished]=", response
        client("localhost", port, "Still running", message="Still running")

        print("RUN - Ctrl+C stops the listening server", flush=True)
        process.send_signal(signal.SIGINT)
        assert process.wait(timeout=5) == -signal.SIGINT

    print("RUN - exact server GOT and REPLIED output", flush=True)
    server_output("custom", ["HiThere!", *messages, "HiThere!", "unfinished", "Still running"])

    print("RUN - capacity boundary, oversized rejection and buffer reuse", flush=True)
    # Binary bytes and shrinking requests expose stale tails or accidental text conversion.
    maximum = bytes(range(256)) * (MAX_MESSAGE_BYTES // 256)
    reuse_messages = [maximum[:-1], maximum, b"", b"short", maximum, b"\x00\xff"]
    with server("capacity", 0) as (port, process):
        for message in reuse_messages:
            raw_exchange(port, message)
        with socket.create_connection(("127.0.0.1", port), timeout=10) as peer:
            peer.sendall(maximum + b"!")
            # Oversized input must be rejected without waiting for EOF or sending a partial echo.
            assert peer.recv(1024) == b"", "Expected oversized request to be closed without a reply"
        raw_exchange(port, b"after rejection")
        assert process.poll() is None, "Server exited after oversized input"
    server_output("capacity", [*reuse_messages, b"after rejection"])
    assert "ironwood.io.IOException: Message is too big!\n" in (OUT / "capacity-server.log").read_text()

    print("RUN - zero allocations or frees inside reply, including the first request", flush=True)
    probe = OUT / "ReplyProbe"
    subprocess.run([
        "ironwoodc", "-cp", str(PROJECT / "target/classes"),
        str(PROJECT / "src/test/ironwood/org/ironwood/simpletcpecho/ReplyProbe.iron"),
        "-d", str(PROJECT / "target/classes"), "--unfreed=error",
    ], cwd=PROJECT, check=True, timeout=120)
    subprocess.run([
        "ironwoodc", "--link", "-cp", str(PROJECT / "target/classes"),
        "--main-class", "org.ironwood.simpletcpecho.ReplyProbe",
        "-o", str(probe), "-O3", "--unfreed=error",
    ], cwd=PROJECT, check=True, timeout=120)
    with server("allocations", len(reuse_messages), executable=probe) as (port, process):
        for message in reuse_messages:
            raw_exchange(port, message)
        status = process.wait(timeout=10)
        assert status == 0, f"Reply allocation probe exited {status} (1: allocated, 2: reclaimed)"
    server_output("allocations", reuse_messages)

    print("RUN - argument errors and connection refusal", flush=True)
    invalid = {
        "server": [("-1",), ("65536",), ("55556", "extra")],
        "client": [("",), ("localhost", "0"), ("localhost", "65536"),
                   ("localhost", "55556", "hi", "extra")],
    }
    for program, cases in invalid.items():
        for args in cases:
            result = run(program, *args, expected=64)
            assert b"usage:" in result.stdout and result.stderr == b""
    # Both programs let parseInt's NumberFormatException reach the runtime reporter.
    for program, args in (("server", ("not-a-port",)), ("client", ("localhost", "not-a-port"))):
        result = run(program, *args, expected=1)
        assert result.stdout == b"" and b"uncaught Ironwood exception: ironwood.lang.NumberFormatException" in result.stderr
    print("RUN - listener bind failure reaches the status-74 handler", flush=True)
    with socket.socket() as occupied:
        occupied.bind(("127.0.0.1", 0))
        occupied.listen(1)
        result = run("server", occupied.getsockname()[1], expected=74)
        assert result.stdout == b""
        assert b"Address unavailable or already in use" in result.stderr
        assert b"\tat org.ironwood.simpletcpecho.Server.main(" in result.stderr
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        result = run("client", "127.0.0.1", reserved.getsockname()[1], expected=74)
        assert result.stdout == b""
        assert re.match(rb"ironwood\.net\.(?:ConnectException|SocketException): [^\n]+\n", result.stderr)
        assert b"\tat org.ironwood.simpletcpecho.Client.main(" in result.stderr
    print("PASS - SimpleTcpEcho local checks", flush=True)


if __name__ == "__main__":
    main()
