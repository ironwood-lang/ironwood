#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Replay explicitly selected, already-passing generated consumer fixtures on a pinned JDK."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_preparation", ROOT / "scripts/prepare-java-bridge.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(PREPARATION)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def comparable(output):
    # Existing consumers assert allocation deltas themselves. Absolute JVM
    # allocation totals and elapsed nanoseconds are observations, not constants.
    output = re.sub(r"(?m)^(root-(?:retention|scalar|alias):100000:\d+:\d+:)\d+$", r"\1<TIME>", output)
    output = re.sub(r"(?m)^(permanent-(?:scalar|object):100000:\d+:\d+:)\d+", r"\1<TIME>", output)
    output = re.sub(r"(?m)^(enum-hot:100000:\d+:\d+:)\d+$", r"\1<TIME>", output)
    output = re.sub(r" bytes=(\d+):(\d+)", lambda match: " bytes.delta=" + str(int(match[2]) - int(match[1])), output)
    # The asserting loader consumer includes its process-private extraction path
    # in HotSpot's already-loaded diagnostic. Preserve the refusal and basename.
    output = re.sub(r"(?m)^(refusal:Native Library )\S+/(libbridge\.(?:dylib|so))( already loaded in another classloader)$",
                    r"\1<EXTRACTED>/\2\3", output)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, action="append", required=True,
                        help="exact evidence directory of a passed test, repeat as needed")
    parser.add_argument("--jdk-prefix", type=Path, required=True)
    parser.add_argument("--java-major", type=int, choices=(21, 22, 23, 24, 25), required=True)
    parser.add_argument("--target", choices=("macos-arm64", "linux-arm64", "linux-x86_64"), required=True)
    parser.add_argument("--execution-scope", choices=("ARM64 hardware", "ARM64 virtualization",
                        "x86-64 Rosetta translation", "x86-64 physical hardware"), required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    mac = args.target == "macos-arm64"
    arches = ("aarch64", "arm64") if args.target.endswith("arm64") else ("amd64", "x86_64")
    if platform.system() != ("Darwin" if mac else "Linux") or platform.machine() not in arches:
        parser.error("fixture replay must use the matching target environment")
    if args.target.endswith("x86_64") != args.execution_scope.startswith("x86-64"):
        parser.error("execution scope does not match target")
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT", "IRONWOOD_FIXTURE_FAILURE"):
        if os.environ.get(name): parser.error("unset " + name)
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=False)
    pins = PREPARATION.PINS if args.java_major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{args.java_major}.json")
    jdk = PREPARATION.check_jdk(args.jdk_prefix, args.target, json.loads(pins.read_text()), pins)
    (evidence / "jdk.json").write_text(json.dumps(jdk, indent=2) + "\n")
    records = []; inputs = {}; excluded = []; native_images = set()
    for fixture in args.fixture:
        fixture = fixture.resolve(); selected = 0
        if not fixture.is_dir(): raise ValueError("missing fixture: " + str(fixture))
        for path in sorted(fixture.rglob("*")):
            if path.is_file() and (path.suffix in (".jar", ".class", ".java", ".iron", ".dylib", ".so", ".ll", ".c") or ".so." in path.name):
                inputs[str(path)] = digest(path)
                if path.suffix == ".jar":
                    with zipfile.ZipFile(path) as archive:
                        for name in archive.namelist():
                            if Path(name).name in ("libbridge.dylib", "libbridge.so"):
                                native_images.add(hashlib.sha256(archive.read(name)).hexdigest())
        for command_file in sorted(fixture.rglob("*.command.txt")):
            command = command_file.read_text().splitlines()
            launchers = [i for i, argument in enumerate(command) if Path(argument).name == "java"]
            if len(launchers) != 1 or "-Xcheck:jni" not in command: continue
            index = launchers[0]
            if any(value in command for value in ("ironwood.compiler.CompilerTests", "ironwood.compiler.Main")): continue
            if command_file.name.startswith("limit-"):
                excluded.append({"command": str(command_file), "reason": "destructive stack diagnostics require the separate adaptive probe"}); continue
            if "temurin-24-" in command[index]:
                excluded.append({"command": str(command_file), "reason": "recorded Java 24 refusal negative from a D203-era artifact is not a supported-JDK replay"}); continue
            if index and (command[0] != "/usr/bin/env" or any(not re.fullmatch(r"IRONWOOD_[A-Z_]+=.*", value) for value in command[1:index])):
                raise ValueError("unrecognized consumer command prefix: " + str(command_file))
            environment = os.environ.copy()
            while command and re.fullmatch(r"IRONWOOD_[A-Z_]+=.*", command[-1]):
                key, value = command.pop().split("=", 1)
                if value == "unset": environment.pop(key, None)
                else: environment[key] = value
            original = command_file.with_name(command_file.name.removesuffix(".command.txt") + ".log")
            expected = original.read_text()
            exit_file = command_file.with_name(command_file.name.removesuffix(".command.txt") + ".exit.txt")
            expected_exit = int(exit_file.read_text()) if exit_file.exists() else 0
            if expected_exit not in (0, 1) or (expected_exit == 1 and expected != "ironwood: allocation failed while implicit OutOfMemoryError is active\n"):
                raise ValueError("unrecognized baseline failure: " + str(command_file))
            if any(word in expected for word in ("WARNING", "FATAL ERROR", "AssertionError")):
                raise ValueError("baseline is not passing: " + str(command_file))
            cell = evidence / f"consumer-{len(records):04d}"; cell.mkdir(); temporary = cell / "tmp"; temporary.mkdir()
            command[index] = jdk["java"]
            command = [value for value in command if not value.startswith("-Djava.io.tmpdir=")]
            command.insert(index + 1, "-Djava.io.tmpdir=" + str(temporary))
            (cell / "command.json").write_text(json.dumps(command, indent=2) + "\n")
            overrides = {key: value for key, value in environment.items() if key.startswith("IRONWOOD_")}
            (cell / "environment.json").write_text(json.dumps(overrides, indent=2) + "\n")
            result = subprocess.run(command, cwd=ROOT, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            (cell / "output.log").write_text(result.stdout); (cell / "exit.txt").write_text(str(result.returncode) + "\n")
            if result.returncode != expected_exit or comparable(result.stdout) != comparable(expected):
                raise ValueError(f"replay mismatch: {command_file}\nexit={result.returncode}, expected={expected_exit}\n{result.stdout}")
            if command[-1] == "passive" and list(temporary.iterdir()): raise ValueError("passive enum access extracted payload")
            for image in temporary.rglob("libbridge.dylib" if mac else "libbridge.so"):
                if digest(image) not in native_images: raise ValueError("extracted image not present in selected fixture jars")
                if mac:
                    signature = subprocess.run(["codesign", "--verify", "--strict", str(image)], capture_output=True, text=True)
                    if signature.returncode: raise ValueError("extracted fixture signature failed: " + signature.stderr)
            records.append({"original_command": str(command_file), "command.sha256": digest(command_file),
                            "baseline.log.sha256": digest(original), "expected_exit": expected_exit, "exit": result.returncode})
            selected += 1
        if not selected: raise ValueError("no supported consumer commands in selected fixture: " + str(fixture))
    (evidence / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
    (evidence / "result.json").write_text(json.dumps({"target": args.target, "execution_scope": args.execution_scope,
        "java": args.java_major, "runner.sha256": digest(Path(__file__)), "records": records, "excluded": excluded,
        "packaged_native_hashes": sorted(native_images),
        "scope": "existing asserting generated consumers, including identified test-only fault images; no producer rebuild or stack-limit qualification"}, indent=2) + "\n")
    print(f"PASS: {len(records)} existing consumer cases on Java {args.java_major}; {args.execution_scope}")


if __name__ == "__main__": main()
