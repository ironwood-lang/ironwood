#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Collect paired OrderBook batch-latency reports after performance input preparation."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
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
    parser.add_argument("--performance", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--llvm-home", type=Path, required=True)
    args = parser.parse_args(); performance = args.performance.resolve(); evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    measured = json.loads((performance / "measurements.json").read_text())
    prepared = json.loads((performance / "prepared.json").read_text())
    for filename, expected in prepared["inputs"].items():
        if CANDIDATE.digest(Path(filename)) != expected: raise ValueError("changed performance input: " + filename)
    jdks = {}
    for major in (21, 22, 23):
        previous = json.loads((performance / f"jdk-{major}.json").read_text())
        java = Path(previous["java"]); prefix = java.parents[4] if measured["target"] == "macos-arm64" else java.parents[2]
        pins = CANDIDATE.PREPARATION.PINS if major == 21 else CANDIDATE.PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        jdks[major] = CANDIDATE.PREPARATION.check_jdk(prefix, measured["target"], json.loads(pins.read_text()), pins)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")

    def run(name, command):
        command = list(map(str, command)); (evidence / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=300)
        (evidence / (name + ".stdout")).write_text(result.stdout); (evidence / (name + ".stderr")).write_text(result.stderr)
        (evidence / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if result.returncode or result.stderr: raise ValueError(f"{name}: {result.returncode}: {result.stdout}{result.stderr}")
        return result.stdout

    java_project = ROOT / "projects/OrderBook/java/src/main/java/org/ironwood/orderbook"
    iron_project = ROOT / "projects/OrderBook/src/main/ironwood"
    candidate = args.candidate.resolve() / "orderbook-O3/combined/orderbook.jar"
    batch = performance / "batch.jar"
    compiler_jar = ROOT / "compiler/build/ironwoodc.jar"
    if CANDIDATE.digest(compiler_jar) != prepared["inputs"][str(compiler_jar)]: raise ValueError("compiler changed")
    compiler = [jdks[21]["java"], "-cp", compiler_jar, "ironwood.compiler.Main"]
    javac = [jdks[21]["javac"], "--release", "21", "-Xlint:all", "-Werror"]
    java_sources = [java_project / (name + ".java") for name in ("Bench", "LatencyBench", "LatencyReport")]
    for label, jar in (("bridge", candidate), ("batch", batch)):
        run(label + "-javac", [*javac, "-cp", jar, "-d", evidence / (label + "-classes"), *java_sources,
            *([HERE / "OrderBookBatchLatency.java"] if label == "batch" else [])])
    run("paired-javac", [*javac, "-d", evidence / "paired-classes", *java_sources,
        *[java_project / (name + ".java") for name in ("OrderBook", "Order", "PriceLevel")]])
    run("native-compile", [*compiler, "--unfreed=off", "--source-path", iron_project, "-d", evidence / "native-classes", iron_project / "org/ironwood/orderbook/LatencyBench.iron"])
    executable = evidence / "native-latency"
    run("native-link", [*compiler, "--link", "-cp", evidence / "native-classes", "--main-class", "org.ironwood.orderbook.LatencyBench", "-O3", "-o", executable])
    run("native-disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", executable])
    records = []; settings = ["20000", "100000", "8"]
    commands = [("native", None, [executable])]
    for major in (21, 22, 23):
        for label, classpath, main_class in (("java", str(evidence / "paired-classes"), "LatencyBench"),
            ("bridge", str(candidate) + os.pathsep + str(evidence / "bridge-classes"), "LatencyBench"),
            ("batch", str(batch) + os.pathsep + str(evidence / "batch-classes"), "OrderBookBatchLatency")):
            commands.append((label, major, [jdks[major]["java"], "-cp", classpath, "org.ironwood.orderbook." + main_class]))
    for label, major, command in commands:
        for fork in range(3):
            name = f"{label}-{major or 'standalone'}-{fork}"
            output = run(name, [*command, *settings])
            if "Measurements: 100,000" not in output or "Operations per batch: 64" not in output or "99.999%" not in output:
                raise ValueError("incomplete latency report: " + output)
            records.append({"kind": label, "jdk": major, "fork": fork, "output.sha256": CANDIDATE.digest(evidence / (name + ".stdout")), "name": name})
    inputs = [candidate, batch, executable, compiler_jar, HERE / "OrderBookBatchLatency.java", Path(__file__), *java_sources]
    inputs += list(iron_project.rglob("*.iron")) + [path for path in evidence.rglob("*.class")]
    (evidence / "result.json").write_text(json.dumps({"target": measured["target"], "execution_scope": measured["execution_scope"],
        "host_notes": measured["host_notes"], "performance.sha256": CANDIDATE.digest(performance / "measurements.json"),
        "inputs": {str(path): CANDIDATE.digest(path) for path in inputs}, "records": records,
        "method": "three forks, 20000 warmup and 100000 measured batches, eight cycles/64 operations per batch; clock overhead included; no checked JNI; project workload verification preserved",
        "acceptance": "pending maintainer numerical review"}, indent=2) + "\n")
    print("Collected 30 verified OrderBook latency reports; numerical acceptance remains pending.")


if __name__ == "__main__": main()
