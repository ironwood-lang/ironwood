#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Deterministic M3 contracts, ownership rollback and native evidence. No live probes."""

import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "integration-tests/target/networking-m3"
CASES = ROOT / "integration-tests/cases/host_interfaces"
CLI = ROOT / "bin/ironwoodc"
DARWIN = platform.system() == "Darwin"
LIBRARY = OUT / ("host_interpose.dylib" if DARWIN else "host_interpose.so")
REPORT = {"platform": platform.platform(), "live_reachability": "not run; separate opt-in smoke"}


def llvm_tool(name):
    home = os.environ.get("IRONWOOD_LLVM_HOME", "").strip()
    if home:
        tool = Path(home).resolve() / "bin" / name
        assert tool.is_file() and os.access(tool, os.X_OK), f"IRONWOOD_LLVM_HOME is missing executable {tool}"
        return str(tool)
    tool = shutil.which(name + "-23") or shutil.which(name)
    assert tool, f"{name} is required; set IRONWOOD_LLVM_HOME to the LLVM 23 installation"
    return tool


def run(name, command, *, controlled=False, mode=None, limit=None, expected=0):
    env = os.environ.copy()
    for key in ("DYLD_INSERT_LIBRARIES", "LD_PRELOAD", "IRONWOOD_ALLOCATION_LIMIT", "IRONWOOD_TEST_HOST_MODE"):
        env.pop(key, None)
    if controlled:
        env["DYLD_INSERT_LIBRARIES" if DARWIN else "LD_PRELOAD"] = str(LIBRARY)
    if mode:
        env["IRONWOOD_TEST_HOST_MODE"] = mode
    if limit is not None:
        env["IRONWOOD_ALLOCATION_LIMIT"] = str(limit)
    result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env, capture_output=True, text=True, timeout=90)
    (OUT / (name + ".log")).write_text(result.stdout + result.stderr)
    assert result.returncode == expected, f"{name}: exit {result.returncode}; see {OUT / (name + '.log')}"
    if controlled:
        match = re.search(r"HOST_COUNTS queries=(\d+) captured=(\d+) released=(\d+) sockets=(\d+) closed=(\d+) probes=(\d+) binds=(\d+) ioctls=(\d+)", result.stderr)
        assert match, (name, result.stderr)
        values = list(map(int, match.groups()))
        assert values[1] == values[2] and values[3] == values[4], (name, values)
        if limit is None:
            REPORT[name] = dict(zip(("queries", "captured", "released", "sockets", "closed", "probes", "binds", "ioctls"), values))
    return result


def link(main, classes, suffix=""):
    run("link-" + main + suffix, [CLI, "--link", "-cp", classes, "--main-class", main, "--unfreed=error", "-O3",
                                "-o", OUT / (main + suffix), "--emit-llvm", OUT / (main + suffix + ".ll")])


def main():
    # Compiler tests supply their discovered LLVM home. Standalone runs may
    # select the same installation explicitly or expose its tools on PATH.
    clang = llvm_tool("clang")
    objdump = llvm_tool("llvm-objdump")
    REPORT["tools"] = {"clang": clang, "llvm-objdump": objdump}
    OUT.mkdir(parents=True, exist_ok=True)
    classes = OUT / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    sources = [CASES / (name + ".iron") for name in ("Main", "Controlled", "Arguments", "Probe", "Failures", "Benchmark")]
    run("compile", [CLI, *sources, "-d", classes, "--unfreed=error"])
    for entry in ("Main", "HostControlled", "HostArguments", "HostProbe", "HostFailures", "HostBenchmark"):
        link(entry, classes)
    command = [clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC",
               "-dynamiclib" if DARWIN else "-shared", ROOT / "integration-tests/native/host_interpose.c", "-o", LIBRARY]
    if not DARWIN:
        command.append("-ldl")
    run("interposer-build", command)
    run("native-build", [clang, "-std=c11", "-O3", "-Wall", "-Wextra", "-Werror",
                         ROOT / "integration-tests/native/reachability_contracts.c", "-o", OUT / "reachability-contracts"])
    native = run("native-contracts", [OUT / "reachability-contracts"])
    REPORT["native_contracts"] = native.stdout.strip()
    run("interface-native-build", [clang, "-std=c11", "-O3", "-Wall", "-Wextra", "-Werror",
                                   ROOT / "integration-tests/native/interface_contracts.c", "-o", OUT / "interface-contracts"])
    interface = run("interface-native-contracts", [OUT / "interface-contracts"])
    REPORT["interface_native_contracts"] = interface.stdout.splitlines()
    benchmark = run("status-benchmark", [OUT / "HostBenchmark"], controlled=True)
    # InetAddress's existing preferred-family initialization opens one socket.
    assert REPORT["status-benchmark"]["sockets"] == 2001 and REPORT["status-benchmark"]["ioctls"] == 2000
    REPORT["status-benchmark"].update(iterations=1000, managed_allocations=0,
                                      nanoseconds=int(benchmark.stdout.strip().splitlines()[-1]))
    run("snapshot-traversal", [OUT / "Main"], controlled=True)
    complete = run("snapshot-contracts", [OUT / "HostControlled"], controlled=True)
    assert "HOST_COMPLETE" in complete.stdout
    allocations = int(complete.stdout.strip().splitlines()[-1])
    for mode in (None, "pending"):
        name = "probe-" + (mode or "immediate")
        run(name, [OUT / "HostProbe"], controlled=True, mode=mode)
        assert REPORT[name]["probes"] == 4 and REPORT[name]["binds"] == 2
    for mode, argument in (("query_error", "query"), ("query_oom", "query_oom"), ("empty", "query"),
                           ("live_error", "live"), ("probe_error", "probe"), ("changing", "changing"),
                           (None, "cursor"), (None, "scope"), ("no_addresses", "no_addresses")):
        run("failure-" + (mode or argument), [OUT / "HostFailures", argument], controlled=True, mode=mode)
    for limit in range(allocations + 1):
        run("oom-" + str(limit), [OUT / "HostControlled"], controlled=True, limit=limit)
    REPORT["managed_rollback"] = {"allocation_limits": [0, allocations], "cases": allocations + 1, "live_delta": 0}

    # This original argument fixture has no networking calls after validation.
    # Only imports, entry-point returns and Ironwood cleanup differ for Java.
    java = (CASES / "Arguments.iron").read_text().replace("ironwood.net.", "java.net.")
    java = re.sub(r"\bfree\s+[^;]+;", "", java).replace("public static int main", "public static void main")
    java = java.replace("return 0;", "return;")
    java = re.sub(r"return ([1-9][0-9]*);", r"throw new AssertionError(\1);", java)
    java_path = OUT / "HostArguments.java"
    java_path.write_text(java)
    run("java-compile", ["javac", "--release", "21", "-d", OUT, java_path])
    expected = run("java-arguments", ["java", "-cp", OUT, "HostArguments"])
    actual = run("ironwood-arguments", [OUT / "HostArguments"], controlled=True)
    assert expected.stdout == actual.stdout, "public argument contract differs from Java 21"
    assert REPORT["ironwood-arguments"]["queries"] == 0 and REPORT["ironwood-arguments"]["probes"] == 0
    assert REPORT["ironwood-arguments"]["sockets"] == 1
    REPORT["java_argument_contracts"] = len(expected.stdout.splitlines())

    archive = OUT / "host-cases.ironjar"
    run("archive-create", [ROOT / "bin/ironjar", "--create", "--file", archive, classes])
    link("HostControlled", archive, "-archive")
    run("archive-snapshot", [OUT / "HostControlled-archive"], controlled=True)
    for dependency in (classes, archive):
        label = "archive" if dependency == archive else "classes"
        invalid = OUT / "Invalid.iron"
        invalid.write_text("// SPDX-License-Identifier: MIT OR Apache-2.0\nimport ironwood.util.Enumeration;\n"
                           "class Invalid { Enumeration<int> values; }\n")
        result = run("bounds-" + label, [CLI, invalid, "-cp", dependency, "-d", OUT / "invalid", "--unfreed=off"], expected=1)
        assert "error:" in result.stderr and "int" in result.stderr
        invalid.write_text("// SPDX-License-Identifier: MIT OR Apache-2.0\npackage ironwood.util;\n"
                           "class Invalid { EnumerationIterator<int> values; }\n")
        result = run("helper-bounds-" + label, [CLI, invalid, "-cp", dependency, "-d", OUT / "invalid", "--unfreed=off"], expected=1)
        assert "error:" in result.stderr and "int" in result.stderr
    REPORT["artifacts"] = "source, class directory and archive; primitive enumeration rejected"
    run("HostProbe-disassembly", [objdump, "--disassemble", "--no-show-raw-insn", OUT / "HostProbe"])
    run("HostControlled-disassembly", [objdump, "--disassemble", "--no-show-raw-insn", OUT / "HostControlled"])
    run("native-assembly", [clang, "-std=c11", "-O3", "-S", ROOT / "runtime/src/ironwood_host.c", "-o", OUT / "host.s"])
    REPORT["machine_code"] = ["HostProbe-disassembly.log", "HostControlled-disassembly.log", "host.s"]
    (OUT / "report.json").write_text(json.dumps(REPORT, indent=2) + "\n")
    print(f"PASS: M3 native/public contracts; {allocations + 1} managed failure boundaries; no live probes")


if __name__ == "__main__":
    main()
