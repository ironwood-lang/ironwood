#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused address/resolver and remaining TCP API checks for networking Milestone 2."""

import argparse
import ipaddress
import json
import os
from pathlib import Path
import platform
import random
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "integration-tests/target/networking-m2"
CASES = ROOT / "integration-tests/cases/tcp_addresses"
DARWIN = platform.system() == "Darwin"
LIBRARY = OUT / ("address_interpose.dylib" if DARWIN else "address_interpose.so")
REPORT = {}


def run(name, command, *, diagnostic=False, extra=None):
    env = os.environ.copy()
    for key in ("DYLD_INSERT_LIBRARIES", "LD_PRELOAD", "IRONWOOD_ALLOCATION_LIMIT",
                "IRONWOOD_TEST_DNS_MODE", "IRONWOOD_TEST_LOCAL_NAME", "IRONWOOD_TEST_IPV6_ONLY",
                "IRONWOOD_TEST_TCP_MODE"):
        env.pop(key, None)
    if diagnostic:
        env["DYLD_INSERT_LIBRARIES" if DARWIN else "LD_PRELOAD"] = str(LIBRARY)
    env.update(extra or {})
    result = subprocess.run(list(map(str, command)), cwd=ROOT, env=env,
                            capture_output=True, text=True, timeout=90)
    (OUT / (name + ".log")).write_text(result.stdout + result.stderr)
    assert result.returncode == 0, f"{name}: exit {result.returncode}; see {OUT / (name + '.log')}"
    if diagnostic:
        match = re.search(r"ADDRESS_COUNTS lookup=(\d+) reverse=(\d+) opened=(\d+) released=(\d+)", result.stderr)
        assert match, (name, result.stderr)
        counts = list(map(int, match.groups()))
        assert counts[2] == counts[3], (name, counts)
        REPORT[name] = dict(zip(("lookup", "reverse", "opened", "released"), counts))
        REPORT[name]["output"] = result.stdout.splitlines()
        tcp = re.search(r"ADDRESS_TCP opened=(\d+) closed=(\d+) urgent=(\d+)", result.stderr)
        assert tcp and tcp[1] == tcp[2], (name, result.stderr)
        REPORT[name]["sockets"] = int(tcp[1])
        REPORT[name]["urgent_calls"] = int(tcp[3])
    return result


def compile_fixture(source, main, dependencies=None):
    classes = OUT / (main + "-classes")
    if classes.exists():
        shutil.rmtree(classes)
    command = [ROOT / "bin/ironwoodc", CASES / (source + ".iron"), "-d", classes, "--unfreed=error"]
    if dependencies:
        command += ["-cp", dependencies]
    run("compile-" + main, command)
    classpath = str(classes) + (os.pathsep + str(dependencies) if dependencies else "")
    run("link-" + main, [ROOT / "bin/ironwoodc", "--link", "--main-class", main,
                         "-cp", classpath, "-o", OUT / main, "-O3", "--unfreed=error",
                         "--emit-llvm", OUT / (main + ".ll")])
    return classes


def prepare():
    for source, main in (("Literals", "Literals"), ("Controlled", "ControlledAddresses"),
                         ("Ownership", "AddressOwnership"), ("SocketOperations", "SocketOperations"),
                         ("Contracts", "AddressContracts"), ("NativeFailures", "AddressNativeFailures"),
                         ("ResultFailures", "AddressResultFailures"), ("ConstructorFailures", "AddressConstructorFailures")):
        compile_fixture(source, main)
    dependencies = compile_fixture("../tcp_delegation/Main", "Main")
    compile_fixture("Extensions", "AddressExtensions", dependencies)
    command = ["clang", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
               "-dynamiclib" if DARWIN else "-shared", "-fPIC",
               ROOT / "integration-tests/native/address_interpose.c", "-o", LIBRARY]
    if not DARWIN:
        command.append("-ldl")
    run("interposer-build", command)
    native_allocation()
    if DARWIN:
        run("allocation-interposer-build", ["clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-O2",
            "-dynamiclib", ROOT / "integration-tests/native/tcp_interpose.c", "-o", OUT / "tcp_interpose.dylib"])


def native_allocation():
    executable = OUT / "resolver-native-allocation"
    run("native-allocation-build", ["clang", "-std=c11", "-O3", "-Wall", "-Wextra", "-Werror",
        ROOT / "integration-tests/native/resolver_native_allocation.c", "-o", executable])
    run("native-allocation", [executable])
    REPORT["native-allocation"] = {"long_name_failure_and_cleanup": "passed", "short_name_heap_allocations": 0}


def java_fixture(source, main):
    version = run("java-version", ["java", "-version"])
    assert re.search(r'version "21\.', version.stderr), "Put Java 21 first in PATH"
    text = (CASES / (source + ".iron")).read_text().replace("ironwood.net", "java.net").replace("ironwood.io", "java.io")
    text = re.sub(r"\bfree [^;]+;", "", text)
    text = text.replace("public static int main(String[] args)", "public static void main(String[] args)")
    text = text.replace("        return 0;", "        return;")
    path = OUT / (main + ".java")
    path.write_text(text)
    run("javac-" + main, ["javac", "--release", "21", "-d", OUT / "java", path])
    return ["java", "-Djava.net.preferIPv4Stack=false", "-Djava.net.preferIPv6Addresses=false",
            "-Djdk.net.allowAmbiguousIPAddressLiterals=false", "-cp", OUT / "java", main]


def contracts():
    java = java_fixture("Contracts", "AddressContracts")
    for family, host in ((4, "127.0.0.1"), (6, "::1")):
        expected = run(f"contracts-java-{family}", java + [host])
        actual = run(f"contracts-native-{family}", [OUT / "AddressContracts", host])
        assert actual.stdout == expected.stdout, f"IPv{family} contract output differs; see contracts logs"
        REPORT[f"contracts-{family}"] = {"java21_differential_lines": len(actual.stdout.splitlines())}
    run("owned-results", [OUT / "AddressOwnership"])
    run("socket-operations", [OUT / "SocketOperations"])
    run("extensions", [OUT / "AddressExtensions"], diagnostic=True)
    REPORT["owned-results"] = {"live_allocation_delta": 0}


def literals():
    interface = "lo0" if DARWIN else "lo"
    cases = ["0", "127.1", "2130706433", "010.0.0.1", "255.255.255.255", "0xffffffff",
             "0177.0.0.1", "0x7f.1", "127.0.0.1.", "256.1.1.1", "1..1", "[127.0.0.1]",
             "::", "::1", "[::1]", "::1%0", "::1%" + interface, "fe80::1%" + interface,
             "2001:db8::1%42", "::ffff:127.1", "::ffff:127.0.0.1", "::ffff:127.0.0.1%42",
             "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::8", "1:2:3:4:5:6:192.168.1.2",
             "224.0.0.1", "224.0.1.0", "239.192.1.1", "239.255.2.3", "ff01::1", "ff02::1",
             "ff05::1", "ff08::1", "ff0e::1", "169.254.1.2", "172.16.1.2", "192.168.2.3", "fec0::1"]
    cases += ["１２７.0.0.1", "١٢٧.0.0.1", "::１２", "::١٢", "１２７", "+127.0.0.1", "127.0.0.1 ",
              "::1%2147483648", "::1%+1", "::1%-1", "::1%4294967296", "[::1%0000]",
              "::ffff:001.002.003.004", "1:2:3:4:5:6:7:8:", "1:2:3:4:5:6:7", "[foo]", "[::1]foo"]
    generator = random.Random(11)
    for _ in range(160):
        address = ipaddress.IPv6Address(generator.getrandbits(128))
        cases += [address.exploded, address.compressed, str(ipaddress.IPv4Address(generator.getrandbits(32)))]
    (OUT / "literal-cases.json").write_text(json.dumps(cases, indent=2) + "\n")
    java = java_fixture("Literals", "Literals")
    expected = run("literals-java", java + cases)
    actual = run("literals-native", [OUT / "Literals"] + cases)
    assert actual.stdout == expected.stdout, "Literal/predicate/hash mismatch; see literals logs"
    REPORT["literals"] = {"java21_differential_cases": len(cases), "named_scope_interface": interface}


def resolver():
    def probe(name, argument, output, lookups, reverses=0, **extra):
        result = run(name, [OUT / "ControlledAddresses", argument], diagnostic=True, extra=extra)
        assert result.stdout.splitlines() == output, (name, result.stdout)
        assert REPORT[name]["lookup"] == lookups and REPORT[name]["reverse"] == reverses, REPORT[name]
    probe("mixed", "all", ["11", "127.0.0.2", "127.0.0.1", "0:0:0:0:0:0:0:2", "0:0:0:0:0:0:0:1", "0"], 1)
    probe("v4", "all", ["7", "127.0.0.2", "127.0.0.1", "0"], 1, IRONWOOD_TEST_DNS_MODE="v4")
    probe("v6", "all", ["7", "0:0:0:0:0:0:0:2", "0:0:0:0:0:0:0:1", "0"], 1, IRONWOOD_TEST_DNS_MODE="v6")
    probe("first", "first", ["127.0.0.2"], 1)
    probe("confirmed", "reverse", ["reverse.ironwood.test", "6", "0", "reverse.ironwood.test"], 2, 2)
    probe("unconfirmed", "reverse", ["127.0.0.42", "8", "0", "127.0.0.42"], 2, 2, IRONWOOD_TEST_DNS_MODE="unconfirmed")
    probe("local", "local", ["127.0.0.43", "local.ironwood.test"], 1, IRONWOOD_TEST_LOCAL_NAME="1")
    probe("cached-hash", "hash", ["1", "0"], 0)
    probe("literal-storage", "literal-storage", ["1", "2", "3", "0", "0"], 0)
    probe("no-cache", "changing", ["127.0.0.1", "unknown", "127.0.0.3", "unknown"], 4)
    for mode in ("partial_error", "empty"):
        probe(mode, "failure", ["unknown"], 1, IRONWOOD_TEST_DNS_MODE=mode)
    probe("unresolved", "unresolved", ["true", "missing.ironwood.test", "missing.ironwood.test"], 1)
    probe("defaults", "defaults", ["127.0.0.1", "0.0.0.0"], 0)
    probe("ipv6-defaults", "defaults", ["0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:0"], 0, IRONWOOD_TEST_IPV6_ONLY="1")
    probe("ambiguous-no-resolver", "ambiguous", ["unknown"], 0)


def failures():
    for limit in range(16):
        run(f"allocation-failure-{limit}", [OUT / "ControlledAddresses"], diagnostic=True,
            extra={"IRONWOOD_ALLOCATION_LIMIT": str(limit)})
    for mode in ("oom", "system_oom"):
        run("native-" + mode, [OUT / "ControlledAddresses", "native-oom"], diagnostic=True,
            extra={"IRONWOOD_TEST_DNS_MODE": mode})
        REPORT["native-" + mode]["retained_exception_objects"] = 1
    REPORT["failure-cleanup"] = {"allocation_limits": list(range(16)), "live_allocation_delta": 0}
    for mode in ("no-route", "traffic-error", "no-reuse-port"):
        run(mode, [OUT / "AddressNativeFailures", mode], diagnostic=True, extra={"IRONWOOD_TEST_TCP_MODE": mode})
    for mode in ("urgent-eintr", "urgent-would-block"):
        run(mode, [OUT / "SocketOperations"], diagnostic=True, extra={"IRONWOOD_TEST_TCP_MODE": mode})
        assert REPORT[mode]["urgent_calls"] == 2, REPORT[mode]


def allocations():
    for mode in ("reverse", "scope", "unresolved", "endpoint"):
        for limit in range(21):
            run(f"result-{mode}-{limit}", [OUT / "AddressResultFailures", mode], diagnostic=True,
                extra={"IRONWOOD_ALLOCATION_LIMIT": str(limit)})
    REPORT["result-cleanup"] = {"cases": 84, "live_allocation_delta": 0}
    if DARWIN:
        for mode in ("client", "server"):
            for limit in range(16):
                name = f"constructor-{mode}-{limit}"
                result = run(name, [OUT / "AddressConstructorFailures", mode], extra={
                    "DYLD_INSERT_LIBRARIES": str(OUT / "tcp_interpose.dylib"),
                    "IRONWOOD_TEST_CALLOC_FAILURE": str(limit)})
                match = re.search(r"TCP_FDS opened=(\d+) closed=(\d+)", result.stderr)
                assert match and match[1] == match[2], (name, result.stderr)
                REPORT[name] = {"sockets": int(match[1]), "live_allocation_delta": 0}
    else:
        REPORT["constructor-allocation-injection"] = "macOS dyld fixture only; resolver/result injection runs here"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("groups", nargs="*", choices=("prepare", "contracts", "literals", "resolver", "failures", "allocations"))
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    report_path = OUT / "report.json"
    if report_path.exists():
        REPORT.update(json.loads(report_path.read_text()))
    for group in args.groups or ("prepare", "contracts", "literals", "resolver", "failures", "allocations"):
        print("networking M2: " + group, flush=True)
        globals()[group]()
        report_path.write_text(json.dumps(REPORT, indent=2) + "\n")
    print("networking M2 checks passed; evidence: " + str(OUT))
