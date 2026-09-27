#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused physical-host handoff; each component retains its own qualification scope."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("bridge_candidate", HERE / "check-candidate.py")
CANDIDATE = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(CANDIDATE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--revision", required=True, help="exact checkout revision from the handoff manifest")
    parser.add_argument("--target", choices=("macos-arm64", "linux-arm64", "linux-x86_64"), required=True)
    parser.add_argument("--execution-scope", choices=("ARM64 hardware", "ARM64 virtualization", "x86-64 physical hardware"), required=True)
    parser.add_argument("--java21-prefix", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--jdk-root", type=Path, required=True)
    parser.add_argument("--llvm-home", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--host-notes", required=True)
    parser.add_argument("--plan-only", action="store_true", help="write exact stage commands; execute no qualification")
    args = parser.parse_args()
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=False)
    selection = json.loads((HERE / "qualification-tests.json").read_text())
    if len(selection["fixtures"]) != 17 or len(selection["proofs"]) != 15:
        raise ValueError("review changed focused qualification selection before execution")
    java = args.java_home.resolve() / "bin/java"
    base = ["--target", args.target, "--execution-scope", args.execution_scope]
    jdks = ["--java21-prefix", args.java21_prefix.resolve(), "--jdk-root", args.jdk_root.resolve()]
    native = ["--llvm-home", args.llvm_home.resolve()]
    candidate = ["--candidate", args.candidate.resolve()]
    fixtures = [ROOT / "scripts/test.sh", *[item for name in selection["fixtures"] for item in ("--test", name)]]
    proofs = [java, "-ea", "-cp", "compiler/build/classes:compiler/build/test-classes", "ironwood.compiler.CompilerTests",
              *[item for name in selection["proofs"] for item in ("--test", name)]]
    stages = [
        ("fixtures", fixtures),
        ("proofs", proofs),
        ("candidate", [sys.executable, HERE / "check-candidate.py", *base, *jdks, *native, *candidate, "--evidence", evidence / "candidate"]),
        ("loaders", [sys.executable, HERE / "check-producer-loaders.py", *base, *jdks, *native, "--evidence", evidence / "loaders"]),
        ("generated-stack", [sys.executable, HERE / "check-stack.py", *base, *jdks, *native, *candidate, "--evidence", evidence / "generated-stack"]),
        ("performance", [sys.executable, HERE / "measure-performance.py", *base, *jdks, *native, *candidate,
                         "--host-notes", args.host_notes, "--evidence", evidence / "performance"]),
        ("performance-summary", [sys.executable, HERE / "summarize-performance.py", "--evidence", evidence / "performance"]),
        ("orderbook-latency", [sys.executable, HERE / "measure-orderbook-latency.py", "--performance", evidence / "performance",
                               *candidate, *native, "--evidence", evidence / "orderbook-latency"]),
    ]
    plan = {"revision": args.revision, "target": args.target, "execution_scope": args.execution_scope,
            "host_notes": args.host_notes, "status": "planned; no execution", "selection": selection,
            "stages": [{"name": name, "command": list(map(str, command))} for name, command in stages],
            "dynamic_stage": "Replay all successfully generated fixture directories on pinned Java 22 and 23 before performance; preserve original child assertions and fault settings."}
    (evidence / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    if args.plan_only:
        print("Plan recorded; no hardware qualification executed: " + str(evidence)); return
    expected_os = "Darwin" if args.target == "macos-arm64" else "Linux"
    arches = ("aarch64", "arm64") if args.target.endswith("arm64") else ("x86_64", "amd64")
    if platform.system() != expected_os or platform.machine() not in arches:
        raise ValueError("matching hardware architecture and OS required")
    if args.target.endswith("x86_64") != args.execution_scope.startswith("x86-64"):
        raise ValueError("execution scope does not match target")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if revision != args.revision: raise ValueError("checkout revision differs from handoff")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip():
        raise ValueError("qualification requires a clean checkout; preserve changes and use an independent authorized checkout")
    preparation = CANDIDATE.PREPARATION
    jdk = preparation.check_jdk(args.java21_prefix, args.target, json.loads(preparation.PINS.read_text()), preparation.PINS)
    if Path(jdk["java"]).resolve() != java: raise ValueError("JAVA_HOME does not identify the pinned producer")
    environment = os.environ.copy()
    environment.update(JAVA_HOME=str(args.java_home.resolve()), IRONWOOD_LLVM_HOME=str(args.llvm_home.resolve()),
                       PATH=str(java.parent) + os.pathsep + str(args.llvm_home.resolve() / "bin") + os.pathsep + environment["PATH"])
    records = []

    def run(name, command):
        command = list(map(str, command))
        (evidence / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        with (evidence / (name + ".log")).open("w") as stream:
            result = subprocess.run(command, cwd=ROOT, env=environment, stdout=stream, stderr=subprocess.STDOUT)
        (evidence / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if result.returncode: raise ValueError("stage failed; preserve evidence and rerun only failing cases: " + name)
        records.append({"stage": name, "exit": result.returncode})
        return (evidence / (name + ".log")).read_text()

    for name, command in stages:
        if name == "performance":
            for major in (22, 23):
                replay = [sys.executable, HERE / "replay-consumers.py", *base, "--java-major", str(major),
                    "--jdk-prefix", args.jdk_root / f"temurin-{major}-{args.target}", "--evidence", evidence / f"replay-{major}",
                    *[value for fixture in fixture_paths for value in ("--fixture", fixture)]]
                run(f"replay-{major}", replay)
        output = run(name, command)
        if name in ("fixtures", "proofs") and not output.endswith(f'PASS: {len(selection[name])} compiler tests\n'):
            raise ValueError("focused selection did not report exact completion: " + name)
        if name == "fixtures":
            fixture_paths = []
            for value in re.findall(r"evidence: (/.+)\n", output):
                folder = Path(value)
                if (folder / "child.log").exists():
                    child = (folder / "child.log").read_text()
                    if not child.endswith("PASS: 1 compiler tests\n"): raise ValueError("nested forced-reuse fixture failed")
                    fixture_paths.extend(re.findall(r"evidence: (/.+)\n", child))
                else: fixture_paths.append(value)
            if len(fixture_paths) != 17: raise ValueError("fixture evidence inventory incomplete")
            (evidence / "fixtures.json").write_text(json.dumps(fixture_paths, indent=2) + "\n")
    (evidence / "result.json").write_text(json.dumps({"revision": revision, "records": records, "execution_scope": args.execution_scope,
        "runner.sha256": CANDIDATE.digest(Path(__file__)), "numerical_acceptance": "pending maintainer review",
        "scope": "selected stages passed; physical execution is operator-attested; minimal-JVM consumer launches and final review must also be recorded as described in the handoff"}, indent=2) + "\n")
    print("Selected host stages passed; final numerical acceptance and evidence review remain required.")


if __name__ == "__main__": main()
