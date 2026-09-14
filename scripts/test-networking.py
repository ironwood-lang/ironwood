#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused Milestone 1 native verification. Never invokes the compiler full suite."""

import argparse
import json
import os
from pathlib import Path
import platform
import re
import resource
import selectors
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "integration-tests/target/networking-m1"
CASES = ROOT / "integration-tests/cases"
CLI = ROOT / "bin/ironwoodc"
REPORT = {}


def limited_descriptors():
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))


def environment(diagnostic=False, **extra):
    env = os.environ.copy()
    for name in ("DYLD_INSERT_LIBRARIES", "IRONWOOD_ALLOCATION_LIMIT",
                 "IRONWOOD_TEST_IPV4_ONLY", "IRONWOOD_TEST_FD_LOG", "IRONWOOD_TEST_CALLOC_FAILURE",
                 "IRONWOOD_TEST_HEAP_LOG", "IRONWOOD_TEST_HEAP_STACK"):
        env.pop(name, None)
    if diagnostic:
        env["DYLD_INSERT_LIBRARIES"] = str(OUT / "tcp_interpose.dylib")
    env.update(extra)
    return env


def run(name, command, *, diagnostic=False, limit=False, extra=None, timeout=60):
    result = subprocess.run([str(part) for part in command], cwd=ROOT, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            env=environment(diagnostic, **(extra or {})), timeout=timeout,
                            preexec_fn=limited_descriptors if limit else None)
    (OUT / f"{name}.log").write_text(result.stdout + result.stderr)
    if result.returncode:
        raise AssertionError(f"{name}: exit {result.returncode}; see {OUT / (name + '.log')}")
    return result


def compile_fixture(source, main, dependencies=None):
    classes = OUT / f"{main}-classes"
    if classes.exists():
        shutil.rmtree(classes)
    command = [CLI, CASES / source, "-d", classes, "--unfreed=error"]
    if dependencies:
        command += ["-cp", dependencies]
    run(f"compile-{main}", command)
    classpath = str(classes) + (os.pathsep + str(dependencies) if dependencies else "")
    run(f"link-{main}", [CLI, "--link", "--main-class", main, "-cp", classpath,
                          "-o", OUT / main, "-O3", "--emit-llvm", OUT / f"{main}.ll",
                          "--unfreed=error"])
    return classes


def prepare():
    compile_fixture("tcp_foundation/Peer.iron", "Peer")
    compile_fixture("tcp_foundation/Operations.iron", "Operations")
    dependencies = compile_fixture("tcp_delegation/Main.iron", "Main")
    compile_fixture("tcp_delegation/Metrics.iron", "Metrics", dependencies)
    compile_fixture("tcp_delegation/FactoryHooks.iron", "FactoryHooks", dependencies)
    compile_fixture("tcp_delegation/Cleanup.iron", "Cleanup", dependencies)
    compile_fixture("tcp_delegation/Borrowed.iron", "Borrowed")
    compile_fixture("tcp_delegation/Inventory.iron", "Inventory", dependencies)
    compile_fixture("tcp_foundation/Contracts.iron", "Contracts")
    compile_fixture("tcp_foundation/Messages.iron", "Messages")
    compile_fixture("tcp_foundation/Failures.iron", "Failures")
    compile_fixture("fresh_bulk_results/Partial.iron", "Partial")
    snapshot_classes = OUT / "Snapshot-classes"
    if snapshot_classes.exists():
        shutil.rmtree(snapshot_classes)
    run("compile-Snapshot", [CLI, CASES / "snapshot_borrows/Main.iron", "-d", snapshot_classes, "--unfreed=error"])
    compile_fixture("snapshot_borrows/Failure.iron", "SnapshotFailure", snapshot_classes)
    run("link-MessageFailure", [CLI, "--link", "--main-class", "MessageFailure", "-cp",
                                OUT / "Messages-classes", "-o", OUT / "MessageFailure", "-O3"])
    run("javac-peer", ["javac", "--release", "21", "-d", OUT / "java",
                       ROOT / "integration-tests/native/TcpJavaPeer.java"])
    if platform.system() == "Darwin":
        run("interposer-build", ["clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
                                  "-dynamiclib", ROOT / "integration-tests/native/tcp_interpose.c",
                                  "-o", OUT / "tcp_interpose.dylib"])


def counts(text):
    result = {}
    for name, values in re.findall(r"TCP_COUNT (\S+) (.*)", text):
        result[name] = {key: int(value) for key, value in re.findall(r"(\w+)=(-?\d+)", values)}
    return result


def descriptors(text):
    matches = re.findall(r"TCP_FDS opened=(\d+) closed=(\d+)", text)
    assert len(matches) == 1 and matches[0][0] == matches[0][1], text
    return int(matches[0][0])


def heap(text, expected):
    match = re.search(r"TCP_HEAP retained=(\d+) bytes=(\d+)", text)
    assert match and tuple(map(int, match.groups())) == expected, text
    return {"retained_blocks": expected[0], "retained_bytes": expected[1]}


def peers(server_kind, client_kind, family, *, rounds=32, timeout=0, mode="fragment", trace=False):
    name = f"peer-{server_kind}-{client_kind}-{family}-{rounds}-{timeout}-{mode}"
    def command(kind, role, port):
        executable = [str(OUT / "Peer")] if kind == "ironwood" else ["java", "-cp", str(OUT / "java"), "TcpJavaPeer"]
        selected_family = "4" if family == "dual4" else "6" if family == "dual6" else family
        if role == "server" and family.startswith("dual"):
            selected_family = "dual"
        return executable + [role, str(port), selected_family, str(rounds), str(timeout), mode]
    started = time.monotonic()
    server = subprocess.Popen(command(server_kind, "server", 0), cwd=ROOT, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=environment(trace and server_kind == "ironwood"))
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(server.stdout, selectors.EVENT_READ)
            assert selector.select(15), f"{name}: server did not announce port"
        first = server.stdout.readline()
        assert re.fullmatch(r"PORT \d+\n", first), (name, first, server.stderr.read() if server.poll() is not None else "")
        client = run(name + "-client", command(client_kind, "client", int(first.split()[1])),
                     diagnostic=trace and client_kind == "ironwood", timeout=30)
        output, error = server.communicate(timeout=30)
        (OUT / f"{name}-server.log").write_text(first + output + error)
        assert server.returncode == 0, (name, output, error)
        result = {"elapsed_wall_seconds": time.monotonic() - started}
        for role, kind, text, native in (("server", server_kind, output, error),
                                         ("client", client_kind, client.stdout, client.stderr)):
            if kind != "ironwood":
                continue
            measurement = re.search(r"BENCH (\d+) (\d+) (\d+) (\d+)", text)
            assert measurement, (name, text)
            measured_rounds, size, elapsed, allocations = map(int, measurement.groups())
            assert (measured_rounds, size, allocations) == (rounds, rounds * 257, 0)
            result[role] = {"rounds": rounds, "bytes_each_direction": size,
                            "nanoseconds": elapsed, "managed_allocations": allocations}
            if trace:
                result[role]["native"] = counts(native)["benchmark"]
                descriptors(native)
                observed = result[role]["native"]
                assert observed["in"] == size and observed["out"] == size
                assert observed["send"] == rounds and observed["recv"] >= rounds
                assert all(observed[key] == 0 for key in ("control", "options", "allocate", "available"))
                if timeout == 0:
                    assert observed["clock"] == observed["poll"] == 0
        REPORT[name] = result
    finally:
        if server.poll() is None:
            server.kill()
            server.communicate()


def interop():
    for family in ("4", "6"):
        for server, client in (("ironwood", "ironwood"), ("ironwood", "java"), ("java", "ironwood")):
            peers(server, client, family)
    for family in ("dual4", "dual6"):
        peers("ironwood", "ironwood", family)


def metrics():
    for kind in ("default", "custom"):
        for family in ("4", "6"):
            name = f"metrics-{kind}-{family}"
            result = run(name, [OUT / "Metrics", kind, family, "64"], diagnostic=True, limit=True)
            expected = [3, 4, 2, 0, 0, 2, 1, 1, 3] if kind == "default" else [4, 5, 2, 0, 0, 2, 1, 1, 3]
            ledgers = [[int(value) for value in row.split()] for row in re.findall(r"LEDGER (.*)", result.stdout)]
            assert ledgers == [expected] * 64, (name, ledgers)
            rendering = [[int(value) for value in row.split()] for row in re.findall(r"RENDER (.*)", result.stdout)]
            assert rendering == [[4, 4, 5, 5]] * 64, (name, rendering)
            REPORT[name] = {"cycles": 64, "ledger": expected, "rendering": rendering[0],
                            "descriptors": descriptors(result.stderr), "descriptor_limit": 32}
    fallback = run("ipv4-only", [OUT / "Metrics", "default", "4", "16"], diagnostic=True, limit=True,
                   extra={"IRONWOOD_TEST_IPV4_ONLY": "1"})
    REPORT["ipv4-only"] = {"cycles": 16, "descriptors": descriptors(fallback.stderr)}


def operations():
    for mode in ("ordinary", "faults"):
        result = run("operations-" + mode, [OUT / "Operations", mode], diagnostic=True, limit=True)
        observed = counts(result.stderr)
        for name in ("scalar_read", "bulk_read", "after_timed_read", "after_timeout_reset", "after_timed_connect", "after_real_timeout", "eof"):
            assert observed[name]["recv"] == 1, (name, observed[name])
            assert all(observed[name][key] == 0 for key in ("poll", "clock", "control", "options", "allocate", "available")), (name, observed[name])
        assert all(value == 0 for value in observed["zero_read"].values())
        for name in ("scalar_write", "bulk_write", "write_with_read_timeout"):
            assert observed[name]["send"] == 1
            assert all(observed[name][key] == 0 for key in ("poll", "clock", "control", "options", "allocate", "available"))
        if mode == "faults":
            assert observed["short_read"]["recv"] == 1 and observed["short_read"]["in"] == 2
            assert observed["read_eintr"]["recv"] == 2 and observed["read_eintr"]["poll"] == 0
            assert observed["read_would_block"]["recv"] == 2 and observed["read_would_block"]["poll"] == 1
            assert observed["write_partial"]["send"] == 2 and observed["write_partial"]["out"] == 4
        REPORT["operations-" + mode] = {"counts": observed, "descriptors": descriptors(result.stderr)}


def messages():
    run("copied-message", [OUT / "Messages"])
    for limit in range(3):
        run(f"message-failure-{limit}", [OUT / "MessageFailure"], extra={"IRONWOOD_ALLOCATION_LIMIT": str(limit)})
    REPORT["messages"] = {"copied_input_reclaimed": True, "allocation_limits_passed": [0, 1, 2]}


def contracts():
    version = run("java-contract-version", ["java", "-version"])
    assert re.search(r'version "21\.', version.stderr), "Put a Java 21 JDK bin directory first in PATH"
    source = (CASES / "tcp_foundation/Contracts.iron").read_text()
    source = source.replace("import ironwood.net.*;", "import java.net.*;").replace("import ironwood.io.*;", "import java.io.*;")
    source = source.replace("SocketOption<boolean>", "SocketOption<Boolean>")
    source = source.replace("public String valueKind()", "public Class<Boolean> type()").replace('return "boolean";', "return Boolean.class;")
    source = source.replace("public static int main(String[] args)", "public static void main(String[] args)").replace("        return 0;", "        return;")
    source = "\n".join(line for line in source.splitlines() if not line.strip().startswith("free ")) + "\n"
    java_source = OUT / "Contracts.java"
    java_source.write_text(source)
    run("javac-contracts", ["javac", "--release", "21", "-d", OUT / "java-contracts", java_source])
    java = run("contracts-java", ["java", "-cp", OUT / "java-contracts", "Contracts"])
    native = run("contracts-ironwood", [OUT / "Contracts"])
    assert java.stdout == native.stdout, "Java contract output differs; see contracts logs"
    for role in ("client", "server"):
        run("factory-" + role, [OUT / "FactoryHooks", role])
    result = run("borrowed-native-delegate", [OUT / "Borrowed"], diagnostic=True)
    REPORT["borrowed-native-delegate"] = {"descriptors": descriptors(result.stderr)}
    result = run("custom-cleanup-primary", [OUT / "Cleanup"], diagnostic=True)
    REPORT["custom-cleanup-primary"] = {"descriptors": descriptors(result.stderr)}
    result = run("custom-cleanup-accept", [OUT / "Cleanup", "accept"], diagnostic=True)
    REPORT["custom-cleanup-accept"] = {"descriptors": descriptors(result.stderr)}
    result = run("custom-inventory", [OUT / "Inventory"], diagnostic=True)
    REPORT["custom-inventory"] = {"descriptors": descriptors(result.stderr)}
    REPORT["contracts"] = {"java_differential_lines": len(java.stdout.splitlines()),
                           "java_version": version.stderr.splitlines()[0], "factory_roles": ["client", "server"]}


def failures():
    result = run("failure-after-accept", [OUT / "Failures", "native"], diagnostic=True, limit=True)
    REPORT["failure-after-accept"] = {"cycles": 32, "descriptors": descriptors(result.stderr),
                                      "retained_exception_and_message_per_cycle": 2,
                                      "heap": heap(result.stderr, (128, 9216))}
    for limit in range(4):
        result = run(f"failure-wrapper-{limit}", [OUT / "Failures", "wrapper"], diagnostic=True, limit=True,
                     extra={"IRONWOOD_TEST_CALLOC_FAILURE": str(limit)})
        REPORT[f"failure-wrapper-{limit}"] = {"cycles": 32, "descriptors": descriptors(result.stderr),
                                               "live_allocation_delta": 0,
                                               "heap": heap(result.stderr, (1, 64))}
    for fixture, maximum in (("Partial", 5), ("SnapshotFailure", 8)):
        for limit in range(maximum + 1):
            run(f"failure-{fixture}-{limit}", [OUT / fixture], extra={"IRONWOOD_ALLOCATION_LIMIT": str(limit)})
        REPORT[fixture] = {"allocation_limits_passed": list(range(maximum + 1)), "live_allocation_delta": 0}
    for limit in range(9):
        result = run(f"failure-inventory-{limit}", [OUT / "Inventory", "rollback"], diagnostic=True,
                     extra={"IRONWOOD_TEST_CALLOC_FAILURE": str(limit)})
        descriptors(result.stderr)
    REPORT["InventoryRollback"] = {"allocation_limits_passed": list(range(9)), "live_allocation_delta": 0}


def benchmark():
    for timeout in (0, 1000):
        peers("ironwood", "ironwood", "4", rounds=512, timeout=timeout, mode="bulk")
        peers("ironwood", "ironwood", "4", rounds=512, timeout=timeout, mode="trace", trace=True)


def inputs():
    # The CLI deliberately separates source compilation from native linking.
    # Reconstruct extension effects and primitive options from one archive too.
    archive = OUT / "delegation.ironjar"
    run("archive-create", [ROOT / "bin/ironjar", "--create", "--file", archive,
                            OUT / "Main-classes", OUT / "Inventory-classes"])
    for main in ("Main", "Inventory"):
        run("archive-link-" + main, [CLI, "--link", "--main-class", main, "-cp", archive,
                                      "-o", OUT / (main + "-archive"), "-O3", "--unfreed=error"])
        run("archive-run-" + main, [OUT / (main + "-archive")])
    snapshot = OUT / "snapshot.ironjar"
    run("snapshot-archive-create", [ROOT / "bin/ironjar", "--create", "--file", snapshot, OUT / "Snapshot-classes"])
    run("snapshot-archive-link", [CLI, "--link", "--main-class", "Main", "-cp", snapshot,
                                    "-o", OUT / "Snapshot-archive", "-O3", "--unfreed=error"])
    run("snapshot-archive-run", [OUT / "Snapshot-archive"])
    invalid = {
        "option-value": 'import ironwood.net.*; class Invalid { static void check(ObservingImpl value) throws ironwood.io.IOException { value.setOption(StandardSocketOptions.TCP_NODELAY, 1); } }',
        "inventory-free": 'class Invalid { static void check(InventoryImpl value) { free value.supportedOptions(); } }',
    }
    for name, source in invalid.items():
        path = OUT / ("invalid-" + name + ".iron")
        path.write_text("// SPDX-License-Identifier: MIT OR Apache-2.0\n" + source + "\n")
        rejected = subprocess.run([str(CLI), str(path), "-cp", str(archive), "-d", str(OUT / "invalid-classes"),
                                   "--unfreed=off"], cwd=ROOT, text=True, capture_output=True, timeout=60)
        (OUT / ("archive-reject-" + name + ".log")).write_text(rejected.stdout + rejected.stderr)
        assert rejected.returncode != 0 and "error:" in rejected.stderr, (name, rejected)
    REPORT["inputs"] = {"source_compilation": True, "class_directory_links": True,
                         "archive_links": ["Main", "Inventory", "Snapshot"], "archive_rejections": list(invalid)}


def disassembly():
    llvm = os.environ.get("IRONWOOD_LLVM_HOME", "/opt/homebrew/opt/llvm")
    objdump = Path(llvm) / "bin/llvm-objdump"
    if not objdump.exists():
        objdump = shutil.which("llvm-objdump-23") or shutil.which("llvm-objdump")
    assert objdump, "llvm-objdump is required for machine-code evidence"
    result = run("Operations-disassembly", [objdump, "--disassemble", "--no-show-raw-insn", OUT / "Operations"])
    (OUT / "Operations.asm").write_text(result.stdout)
    if platform.system() == "Darwin":
        run("Operations-native-symbols", [objdump, "--macho", "--indirect-symbols", OUT / "Operations"])
        run("Operations-macho-disassembly", [objdump, "--macho", "--disassemble", OUT / "Operations"])
    REPORT["disassembly"] = str(OUT / "Operations.asm")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("groups", nargs="*", choices=("prepare", "contracts", "interop", "metrics", "operations", "messages", "failures", "inputs", "benchmark", "disassembly"))
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    report_path = OUT / "report.json"
    if report_path.exists():
        REPORT.update(json.loads(report_path.read_text()))
    for group in args.groups or ("prepare", "contracts", "interop", "metrics", "operations", "messages", "failures", "inputs", "benchmark", "disassembly"):
        print(f"networking: {group}", flush=True)
        globals()[group]()
        (OUT / "report.json").write_text(json.dumps(REPORT, indent=2) + "\n")
    print(f"networking checks passed; evidence: {OUT}")
