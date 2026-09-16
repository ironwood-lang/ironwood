#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Explicit proxy contracts against local scripted peers; no external services."""

import argparse
import base64
import errno
import json
import os
from pathlib import Path
import platform
import re
import shutil
import socket
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "integration-tests/target/networking-m4"
CASES = ROOT / "integration-tests/cases/proxy_connections"
CLI = ROOT / "bin/ironwoodc"
REPORT = {"platform": platform.platform(), "cases": {}}


def llvm_tool(name):
    home = os.environ.get("IRONWOOD_LLVM_HOME", "").strip()
    tool = str(Path(home) / "bin" / name) if home else shutil.which(name + "-23") or shutil.which(name)
    assert tool and os.access(tool, os.X_OK), f"{name} is required; set IRONWOOD_LLVM_HOME"
    return tool


def run(name, command, expected=0, env=None):
    result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env, capture_output=True, text=True, timeout=90)
    (OUT / (name + ".log")).write_text(result.stdout + result.stderr)
    assert result.returncode == expected, f"{name}: exit {result.returncode}; see {OUT / (name + '.log')}"
    return result


def prepare():
    OUT.mkdir(parents=True, exist_ok=True)
    classes = OUT / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    run("compile", [CLI, CASES / "Client.iron", CASES / "Arguments.iron", CASES / "Factory.iron", CASES / "Checks.iron", "-d", classes, "--unfreed=error"])
    run("link", [CLI, "--link", "-cp", classes, "--main-class", "ProxyClient", "--unfreed=error",
                 "-O3", "-o", OUT / "ProxyClient", "--emit-llvm", OUT / "ProxyClient.ll"])


def evidence():
    clang = llvm_tool("clang")
    objdump = llvm_tool("llvm-objdump")
    darwin = platform.system() == "Darwin"
    library = OUT / ("proxy_interpose.dylib" if darwin else "proxy_interpose.so")
    command = [clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC",
               "-dynamiclib" if darwin else "-shared", ROOT / "integration-tests/native/proxy_interpose.c", "-o", library]
    if not darwin:
        command.append("-ldl")
    run("interposer-build", command)
    REPORT["tools"] = {"clang": clang, "llvm-objdump": objdump}
    for kind in ("socks-auth", "http-basic"):
        exercise("untraced-benchmark-" + kind, {"kind": kind, "rounds": 1000, "size": 255})
        case = {"kind": kind, "rounds": 1000, "size": 255}
        exercise("benchmark-" + kind, case, diagnostic=library)
        observed = REPORT["cases"]["benchmark-" + kind]["native"]["benchmark"]
        assert observed["send"] == 1000 and observed["recv"] >= 1000
        assert observed["in"] == observed["out"] == 257000
        assert all(observed[key] == 0 for key in ("peek", "poll", "clock", "control")), observed
        # The client independently requires zero managed allocations in this loop.
        REPORT["cases"]["benchmark-" + kind]["steady_state_managed_allocations"] = 0
    for kind in ("socks-auth", "http-basic"):
        for mode in ("read-eintr", "read-would-block", "short-read", "short-write", "write-eintr", "write-would-block", "poll-eintr"):
            exercise(kind + "-" + mode, {"kind": kind, "native_mode": mode}, diagnostic=library)
        exercise(kind + "-native-error", {"kind": kind, "native_mode": "read-error", "expected": "failure",
                                         "error_message": "Connection reset"}, diagnostic=library)
        exercise(kind + "-restore-error", {"kind": kind, "native_mode": "restore-error", "expected": "failure"}, diagnostic=library)
        exercise(kind + "-write-deadline", {"kind": kind, "native_mode": "write-deadline", "expected": "timeout",
                                           "timeout": 250}, diagnostic=library)
        for mode, expected in (("proxy-dns", "success"), ("resolver-deadline", "timeout"), ("resolver-error", "failure")):
            exercise(kind + "-" + mode, {"kind": kind, "native_mode": mode, "expected": expected,
                                        "proxy_host": "proxy.invalid", "timeout": 250}, diagnostic=library)
    REPORT["rollback"] = {}
    for kind in ("socks4-user", "socks-auth", "http-basic"):
        case = {"kind": kind, "size": 255}
        if kind == "socks4-user":
            case.update(host="127.0.0.1", resolved=True)
        first_measured = None
        for limit in range(100):
            result = exercise(f"oom-{kind}-{limit}", case, allocation_limit=limit, diagnostic=library)
            if first_measured is None and "PROXY_READY" in result.stdout:
                first_measured = limit
            if "PROXY_COMPLETE" in result.stdout:
                REPORT["rollback"][kind] = {"limits": [first_measured, limit], "cases": limit - first_measured + 1,
                                           "warmup_failure_cases": first_measured, "live_delta": 0}
                break
        else:
            raise AssertionError(f"{kind}: allocation sweep never completed")
    source = (CASES / "Arguments.iron").read_text().replace("ironwood.net.", "java.net.")
    source = re.sub(r"    destructor\s*\{[^}]*\}", "", source)
    source = re.sub(r"\bfree\s+[^;]+;", "", source)
    source = source.replace("public static int main", "public static void main").replace("return 0;", "return;")
    java = OUT / "ProxyArguments.java"
    java.write_text(source)
    run("java-compile", ["javac", "--release", "21", "-d", OUT, java])
    expected = run("java-arguments", ["java", "-cp", OUT, "ProxyArguments"])
    for main in ("ProxyArguments", "ProxyFactoryCheck", "ironwood.net.ProxyChecks"):
        run("link-" + main, [CLI, "--link", "-cp", OUT / "classes", "--main-class", main, "--unfreed=error",
                            "-O3", "-o", OUT / main])
        actual = run("run-" + main, [OUT / main])
        if main == "ProxyArguments":
            assert actual.stdout == expected.stdout, (actual.stdout, expected.stdout)
    REPORT["java_argument_contracts"] = len(expected.stdout.splitlines())
    archive = OUT / "proxy-cases.ironjar"
    run("archive-create", [ROOT / "bin/ironjar", "--create", "--file", archive, OUT / "classes"])
    run("archive-link", [CLI, "--link", "-cp", archive, "--main-class", "ProxyClient", "--unfreed=error",
                         "-O3", "-o", OUT / "ProxyClient-archive"])
    exercise("archive-authenticated", {"kind": "socks-auth"}, executable=OUT / "ProxyClient-archive")
    run("disassembly", [objdump, "--disassemble", "--no-show-raw-insn", OUT / "ProxyClient"])
    run("native-assembly", [clang, "-std=c11", "-O3", "-S", ROOT / "runtime/src/ironwood_tcp.c", "-o", OUT / "tcp.s"])
    REPORT["machine_code"] = ["disassembly.log", "tcp.s"]


def exact(peer, count):
    result = bytearray()
    while len(result) < count:
        data = peer.recv(count - len(result))
        if not data:
            raise EOFError(f"peer closed after {len(result)} of {count} bytes")
        result.extend(data)
    return bytes(result)


def until(peer, terminator, limit=1 << 24):
    result = bytearray()
    while not result.endswith(terminator):
        result.extend(exact(peer, 1))
        assert len(result) <= limit
    return bytes(result)


def credentials(size):
    return bytes(128 + i % 100 for i in range(size)), bytes(160 + i % 80 for i in range(size))


def send(peer, data, fragmented):
    if fragmented:
        for octet in data:
            peer.sendall(bytes([octet]))
    else:
        peer.sendall(data)


def socks(peer, case, target_port):
    kind = case["kind"]
    user, password = credentials(case.get("size", 7))
    if kind == "socks-auth-binary":
        user, password = bytes(range(len(user))), bytes(255 - i for i in range(len(password)))
    host = case.get("host", "target.invalid")
    mode = case.get("mode", "normal")
    fragmented = case.get("fragmented", False)
    if kind.startswith("socks4"):
        assert exact(peer, 4) == b"\x04\x01" + target_port.to_bytes(2, "big")
        assert exact(peer, 4) == socket.inet_aton(host)
        assert until(peer, b"\0") == (user if kind == "socks4-user" else b"") + b"\0"
        reply = case.get("reply", b"\0\x5a\0\0\0\0\0\0")
    else:
        method = 2 if kind.startswith("socks-auth") else 0
        assert exact(peer, 3) == bytes([5, 1, method])
        if mode == "greeting":
            send(peer, case["reply"], fragmented)
            return False
        if mode == "slow-greeting":
            time.sleep(0.16)
        send(peer, bytes([5, method]), fragmented)
        if method == 2:
            assert exact(peer, 2) == bytes([1, len(user)])
            assert exact(peer, len(user)) == user
            assert exact(peer, 1) == bytes([len(password)])
            assert exact(peer, len(password)) == password
            if mode == "auth":
                send(peer, case["reply"], fragmented)
                return False
            send(peer, b"\x01\0", fragmented)
        request = exact(peer, 4)
        assert request[:3] == b"\x05\x01\0"
        if case.get("resolved"):
            family = socket.AF_INET6 if ":" in host else socket.AF_INET
            assert request[3] == (4 if family == socket.AF_INET6 else 1)
            assert exact(peer, 16 if family == socket.AF_INET6 else 4) == socket.inet_pton(family, host)
        else:
            encoded = host.encode("latin1", "replace")
            assert request[3] == 3 and exact(peer, 1) == bytes([len(encoded)])
            assert exact(peer, len(encoded)) == encoded
        assert exact(peer, 2) == target_port.to_bytes(2, "big")
        reply = case.get("reply", b"\x05\0\0\x01\0\0\0\0\0\0")
    if mode == "slow-greeting":
        time.sleep(0.16)
    success = case.get("expected", "success") == "success"
    send(peer, reply + (b"\x2a\xff" if success else b""), fragmented)
    return success


def http(peer, case, target_port):
    head = until(peer, b"\r\n\r\n")
    host = case.get("host", "target.invalid")
    if case.get("resolved") and ":" in host:
        raw = socket.inet_pton(socket.AF_INET6, host)
        host = ":".join(format(int.from_bytes(raw[i:i + 2], "big"), "x") for i in range(0, 16, 2))
    authority = (f"[{host}]" if ":" in host and not host.startswith("[") else host) + f":{target_port}"
    request = b"CONNECT " + authority.encode() + b" HTTP/1.1\r\nHost: " + authority.encode() + b"\r\n"
    if case["kind"] == "http-basic":
        user, password = credentials(case.get("size", 7))
        request += b"Proxy-Authorization: Basic " + base64.b64encode(user + b":" + password) + b"\r\n"
    assert head == request + b"\r\n", (head[:150], request[:150])
    success = case.get("expected", "success") == "success"
    reply = case.get("reply", b"HTTP/1.1 200 Connected\r\n\r\n")
    if case.get("mode") == "slow-heads":
        peer.sendall(b"HTTP/1.1 103 Early\r\n\r\n")
        time.sleep(0.16)
        peer.sendall(b"HTTP/1.1 199 Unknown\r\n\r\n")
        time.sleep(0.16)
    send(peer, reply + (b"\x2a\xff" if success else b""), case.get("fragmented", False))
    return success


def exercise(name, case, *, allocation_limit=None, executable=None, diagnostic=None):
    # A distinct local listener detects forbidden direct fallback. Every route,
    # including an explicit loopback route, must use the configured proxy.
    with socket.socket() as listener, socket.socket() as origin:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        listener.settimeout(0.05)
        origin.bind(("127.0.0.1", 0))
        origin.listen()
        origin.settimeout(0.01)
        target_port = origin.getsockname()[1]
        finished = threading.Event()
        errors = []
        state = {"connections": 0, "tunnel_bytes": 0}

        def serve():
            try:
                while not finished.is_set():
                    try:
                        peer, _ = listener.accept()
                        break
                    except socket.timeout:
                        continue
                else:
                    return
                state["connections"] += 1
                with peer:
                    peer.settimeout(5)
                    tunnel = http(peer, case, target_port) if case["kind"].startswith("http") else socks(peer, case, target_port)
                    if not tunnel:
                        try:
                            peer.shutdown(socket.SHUT_WR)
                        except OSError as error:
                            # Linux can already have disconnected after the
                            # client rejects a response or exhausts its deadline.
                            if error.errno != errno.ENOTCONN:
                                raise
                        assert peer.recv(1024) == b"", "retry or protocol downgrade after rejection"
                        return
                    payload = bytes(i & 255 for i in range(257))
                    for _ in range(case.get("rounds", 1)):
                        received = exact(peer, len(payload))
                        assert received == payload, "credentials or wrong bytes reached tunnel"
                        state["tunnel_bytes"] += len(received)
                        peer.sendall(received)
                    assert peer.recv(1) == b"", "extra tunneled bytes"
            except (EOFError, BrokenPipeError, ConnectionResetError) as error:
                if allocation_limit is None and case.get("expected", "success") == "success":
                    errors.append(repr(error))
            except Exception as error:
                errors.append(repr(error))

        worker = threading.Thread(target=serve)
        worker.start()
        env = os.environ.copy()
        for key in ("IRONWOOD_ALLOCATION_LIMIT", "DYLD_INSERT_LIBRARIES", "LD_PRELOAD", "IRONWOOD_TEST_PROXY_MODE"):
            env.pop(key, None)
        env.update(ALL_PROXY="socks5://127.0.0.1:1", HTTP_PROXY="http://127.0.0.1:1", NO_PROXY="*")
        if allocation_limit is not None:
            env["IRONWOOD_ALLOCATION_LIMIT"] = str(allocation_limit)
        if diagnostic:
            env["DYLD_INSERT_LIBRARIES" if platform.system() == "Darwin" else "LD_PRELOAD"] = str(diagnostic)
            env["IRONWOOD_TEST_FD_LOG"] = "1"
        if case.get("native_mode"):
            env["IRONWOOD_TEST_PROXY_MODE"] = case["native_mode"]
        command = [executable or OUT / "ProxyClient", case["kind"], listener.getsockname()[1], case.get("size", 7),
                   case.get("host", "target.invalid"), "resolved" if case.get("resolved") else "unresolved", target_port,
                   case.get("timeout", 2000), case.get("expected", "success"), case.get("rounds", 1)]
        command.extend([case.get("error_message", ""), case.get("proxy_host", "127.0.0.1")])
        started = time.monotonic()
        try:
            result = run(name, command, env=env)
        finally:
            finished.set()
            worker.join(6)
        assert not worker.is_alive() and not errors, (name, errors)
        # No second proxy connection or direct attempt is permitted.
        for route in (listener, origin):
            try:
                extra, _ = route.accept()
                extra.close()
                raise AssertionError(f"{name}: unexpected fallback/retry connection")
            except socket.timeout:
                pass
        if allocation_limit is None:
            assert "PROXY_COMPLETE" in result.stdout, (name, result.stdout)
            expected = case.get("expected", "success")
            if expected != "success":
                assert "PROXY_" + expected.upper() in result.stdout, (name, result.stdout)
            benchmark = re.search(r"BENCH (\d+)", result.stdout)
            REPORT["cases"][name] = {"transfer_ns": int(benchmark[1]) if benchmark else None, **state, "elapsed_seconds": time.monotonic() - started,
                                    "managed_allocations": int(result.stdout.strip().splitlines()[-1])}
        if diagnostic:
            matches = re.findall(r"PROXY_FDS opened=(\d+) closed=(\d+)", result.stderr)
            assert len(matches) == 1 and matches[0][0] == matches[0][1], (name, result.stderr)
            if allocation_limit is None:
                native = {phase: {key: int(value) for key, value in re.findall(r"(\w+)=(\d+)", counts)}
                          for phase, counts in re.findall(r"PROXY_COUNT (\w+) (.*)", result.stderr)}
                REPORT["cases"][name].update(native=native, descriptors=int(matches[0][0]))
        return result


def scenarios():
    cases = {
        "socks-auth-binary": {"kind": "socks-auth-binary", "size": 255},
        "socks5-domain": {"kind": "socks", "rounds": 32},
        "socks5-ipv4": {"kind": "socks", "host": "127.0.0.1", "resolved": True},
        "socks5-ipv6": {"kind": "socks", "host": "::1", "resolved": True},
        "socks5-fragmented": {"kind": "socks", "fragmented": True},
        "socks5-bound-domain": {"kind": "socks", "reply": b"\x05\0\0\x03\x03abc\0\x50"},
        "socks5-bound-ipv6": {"kind": "socks", "reply": b"\x05\0\0\x04" + bytes(18)},
        "socks4": {"kind": "socks4", "host": "127.0.0.1", "resolved": True},
        "socks4-user": {"kind": "socks4-user", "host": "127.0.0.1", "resolved": True, "fragmented": True},
        "http": {"kind": "http", "rounds": 32},
        "http-ipv6": {"kind": "http", "host": "::1", "resolved": True},
        "http-fragmented": {"kind": "http", "fragmented": True},
        "http-empty-basic": {"kind": "http-basic", "size": 0},
        "http-informational": {"kind": "http", "reply": b"HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 102 Working\r\n\r\nHTTP/1.1 103 Early\r\n\r\nHTTP/1.1 199 Unknown\r\n\r\nHTTP/1.0 299 Tunnel\r\nCoNtEnT-LeNgTh: 9000\r\ntRaNsFeR-EnCoDiNg: nonsense\r\n\r\n"},
        "http-16-interim": {"kind": "http", "reply": b"HTTP/1.1 103 Early\r\n\r\n" * 16 + b"HTTP/1.1 200 OK\r\n\r\n"},
        "socks-shared-deadline": {"kind": "socks", "mode": "slow-greeting", "timeout": 250, "expected": "timeout"},
        "http-shared-deadline": {"kind": "http", "mode": "slow-heads", "timeout": 250, "expected": "timeout"},
        "socks-untimed": {"kind": "socks", "timeout": 0},
        "http-untimed": {"kind": "http", "timeout": 0},
    }
    for size in (1, 7, 255):
        cases[f"socks-auth-{size}"] = {"kind": "socks-auth", "size": size, "fragmented": True}
    for size in (1, 2, 7, 513):
        cases[f"http-basic-{size}"] = {"kind": "http-basic", "size": size}
    for label, kind, mode, reply in (
            ("non-v5", "socks", "greeting", b"\x04\0"),
            ("no-method", "socks", "greeting", b"\x05\xff"),
            ("auth-unrequested", "socks", "greeting", b"\x05\x02"),
            ("auth-downgrade", "socks-auth", "greeting", b"\x05\0"),
            ("auth-rejected", "socks-auth", "auth", b"\x01\x01"),
            ("auth-version", "socks-auth", "auth", b"\x02\0"),
            ("reply-version", "socks", "normal", b"\x04\0\0\x01"),
            ("reply-reserved", "socks", "normal", b"\x05\0\x01\x01"),
            ("reply-type", "socks", "normal", b"\x05\0\0\x7f"),
            ("reply-status", "socks", "normal", b"\x05\xff\0\x01"),
            ("reply-empty-domain", "socks", "normal", b"\x05\0\0\x03\0")):
        cases[label] = {"kind": kind, "mode": mode, "reply": reply, "expected": "failure", "host": "127.0.0.1", "resolved": True}
    bad_http = {
        "407": b"HTTP/1.1 407 Authenticate\r\nProxy-Authenticate: Basic\r\n\r\n",
        "101": b"HTTP/1.1 101 Upgrade\r\n\r\n",
        "17-interim": b"HTTP/1.1 103 Early\r\n\r\n" * 17,
        "interim-transfer": b"HTTP/1.1 199 Unknown\r\nTransfer-Encoding: chunked\r\n\r\n",
        "interim-length": b"HTTP/1.1 103 Early\r\nContent-Length: 0\r\n\r\n",
        "bad-status": b"HTTP/1.1 20x Nope\r\n\r\n",
        "bad-version": b"HTTP/2.0 200 OK\r\n\r\n",
        "bad-name": b"HTTP/1.1 200 OK\r\nBad Name: value\r\n\r\n",
        "bad-value": b"HTTP/1.1 200 OK\r\nX: bad\0value\r\n\r\n",
        "bare-lf": b"HTTP/1.1 200 OK\n\n",
        "eof-interim": b"HTTP/1.1 103 Early\r\n\r\n",
    }
    for label, reply in bad_http.items():
        cases["http-" + label] = {"kind": "http-basic", "reply": reply, "expected": "failure"}
    for status in (91, 92, 93, 255):
        cases[f"socks4-status-{status}"] = {"kind": "socks4", "host": "127.0.0.1", "resolved": True,
                                            "reply": bytes([0, status]) + bytes(6), "expected": "failure"}
    cases["socks4-truncated"] = {"kind": "socks4", "host": "127.0.0.1", "resolved": True,
                                "reply": b"\0\x5a\0", "expected": "failure"}
    for count in range(10):
        cases[f"socks-truncated-{count}"] = {"kind": "socks", "reply": b"\x05\0\0\x01\0\0\0\0\0\0"[:count], "expected": "failure"}
    prefix = b"HTTP/1.1 200 OK\r\nX: "
    cases["http-head-limit"] = {"kind": "http", "reply": prefix + b"a" * (65536 - len(prefix) - 4) + b"\r\n\r\n"}
    cases["http-head-overflow"] = {"kind": "http", "reply": prefix + b"a" * 65536 + b"\r\n\r\n", "expected": "failure"}
    return cases


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", action="append")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--evidence-only", action="store_true")
    args = parser.parse_args()
    if not args.skip_build:
        prepare()
    cases = scenarios()
    for name in ([] if args.evidence_only else args.case or cases):
        assert name in cases, name
        print("RUN - " + name, flush=True)
        exercise(name, cases[name])
    if args.evidence_only or not args.case:
        evidence()
    (OUT / "report.json").write_text(json.dumps(REPORT, indent=2) + "\n")
    print(f"PASS: {len(REPORT['cases'])} proxy scenarios")


if __name__ == "__main__":
    main()
