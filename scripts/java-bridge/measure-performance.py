#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Prepare paired measurement inputs, then collect isolated, unchecked-JNI observations."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("bridge_candidate", HERE / "check-candidate.py")
CANDIDATE = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(CANDIDATE)
PREPARATION = CANDIDATE.PREPARATION
MODES = ("clock", "java", "bare", "scalar", "bare-instance", "instance", "batch", "string", "object", "exception")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--target", choices=("macos-arm64", "linux-arm64", "linux-x86_64"), required=True)
    parser.add_argument("--execution-scope", choices=("ARM64 hardware", "ARM64 virtualization", "x86-64 physical hardware"), required=True)
    parser.add_argument("--compiler", type=Path, default=ROOT / "compiler/build/ironwoodc.jar")
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--java21-prefix", type=Path)
    parser.add_argument("--llvm-home", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--measure-prepared", action="store_true", help="verify recorded input hashes and use an existing prepared directory")
    parser.add_argument("--host-notes", required=True, help="VM resources and known host contention; supplied by the operator")
    args = parser.parse_args()
    mac = args.target == "macos-arm64"
    if args.prepare_only and args.measure_prepared: parser.error("choose preparation or measurement")
    if platform.system() != ("Darwin" if mac else "Linux") or platform.machine() not in (("arm64", "aarch64") if args.target.endswith("arm64") else ("amd64", "x86_64")):
        parser.error("matching hardware environment required; no translated timing qualification")
    if args.target.endswith("x86_64") != args.execution_scope.startswith("x86-64"):
        parser.error("execution scope does not match target")
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT"):
        if os.environ.get(name): parser.error("unset " + name)
    evidence = args.evidence.resolve()
    if not args.measure_prepared: evidence.mkdir(parents=True, exist_ok=False)

    def run(name, command):
        command = list(map(str, command))
        (evidence / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=300)
        (evidence / (name + ".stdout")).write_text(result.stdout); (evidence / (name + ".stderr")).write_text(result.stderr)
        (evidence / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if result.returncode: raise ValueError(f"{name}: {result.returncode}: {result.stdout}{result.stderr}")
        return result.stdout

    jdks = {}
    for major in (21, 22, 23):
        pins = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        prefix = args.java21_prefix if major == 21 and args.java21_prefix else args.jdk_root / f"temurin-{major}-{args.target}"
        jdks[major] = PREPARATION.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
    candidate = args.candidate.resolve()
    paired = candidate / "orderbook-O3/combined/orderbook.jar"
    original = json.loads((candidate / "orderbook-O3/evidence.json").read_text())["assembled"]
    if CANDIDATE.digest(paired) != original["jar.sha256"]: raise ValueError("changed final OrderBook candidate")
    compiler = [jdks[21]["java"], "-cp", args.compiler.resolve(), "ironwood.compiler.Main"]
    javac = [jdks[21]["javac"], "--release", "21", "-Xlint:all", "-Werror"]
    micro = evidence / "micro.jar"; batch = evidence / "batch.jar"
    baseline = evidence / ("baseline.dylib" if mac else "baseline.so")
    native_micro = evidence / "native-micro"; native_book = evidence / "native-orderbook"
    java_project = ROOT / "projects/OrderBook/java/src/main/java/org/ironwood/orderbook"
    iron_project = ROOT / "projects/OrderBook/src/main/ironwood"
    if not args.measure_prepared:
        run("revision", ["git", "rev-parse", "HEAD"]); run("working-diff", ["git", "diff", "--binary"])
        run("os", ["sw_vers"] if mac else ["uname", "-a"])
        run("cpu", ["sysctl", "-n", "machdep.cpu.brand_string"] if mac else ["lscpu"])
        run("clang-version", [args.llvm_home / "bin/clang", "--version"])
        run("micro-producer", [*compiler, "--java-bridge", "--export", "bridgeperf", "--unfreed=off", "-O3", "-o", micro, HERE / "PerformanceProbe.iron"])
        run("batch-producer", [*compiler, "--java-bridge", "--export", "bridgeperf", "--export", "org.ironwood.orderbook", "--unfreed=off", "-O3", "-o", batch,
            "-cp", candidate / "inputs/engine-classes", HERE / "OrderBookBatch.iron"])
        payloads = {}
        for label, jar in (("micro", micro), ("batch", batch)):
            with zipfile.ZipFile(jar) as archive:
                manifest = CANDIDATE.properties(archive.read("META-INF/ironwood/bridge.properties"))
                for key in ("compiler.sha256", "runtime.sha256"):
                    if manifest[key] != original["manifest"][key]: raise ValueError("different production input: " + key)
                if manifest["native.target"] != args.target: raise ValueError("different target")
                image = evidence / (label + (".dylib" if mac else ".so")); image.write_bytes(archive.read(manifest["native.resource"]))
            payloads[label] = {"jar.sha256": CANDIDATE.digest(jar), "manifest": manifest}
            run(label + "-disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", image])
            run(label + "-symbols", [args.llvm_home / "bin/llvm-nm", "--defined-only", image])
        (evidence / "payloads.json").write_text(json.dumps(payloads, indent=2) + "\n")
        run("micro-javac", [*javac, "-cp", micro, "-d", evidence / "micro-classes", HERE / "PerformanceConsumer.java"])
        run("paired-javac", [*javac, "-d", evidence / "paired-classes", *[java_project / (name + ".java") for name in ("OrderBook", "Order", "PriceLevel", "Bench")]])
        run("bridge-javac", [*javac, "-cp", paired, "-d", evidence / "bridge-classes", java_project / "Bench.java"])
        run("batch-javac", [*javac, "-cp", batch, "-d", evidence / "batch-classes", java_project / "Bench.java", HERE / "OrderBookBatchConsumer.java"])
        java_home = Path(jdks[21]["java"]).parent.parent
        flags = []
        if not mac:
            pins = CANDIDATE.properties((ROOT / "packaging/java-bridge-support.properties").read_bytes())
            flags = ["--no-default-config", "--sysroot=" + str(args.llvm_home / pins[args.target + ".sysroot"]), "--gcc-toolchain=" + str(args.llvm_home)]
        run("baseline-clang", [args.llvm_home / "bin/clang", *flags, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O3", "-fPIC", "-fvisibility=hidden",
            "-dynamiclib" if mac else "-shared", "-I" + str(java_home / "include"), "-I" + str(java_home / ("include/darwin" if mac else "include/linux")),
            HERE / "PerformanceBaseline.c", "-o", baseline])
        run("baseline-disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", baseline])
        run("native-micro-compile", [*compiler, "--unfreed=off", "-d", evidence / "native-micro-classes", HERE / "PerformanceProbe.iron", HERE / "PerformanceNative.iron"])
        run("native-micro-link", [*compiler, "--link", "-cp", evidence / "native-micro-classes", "--main-class", "bridgeperfnative.PerformanceNative", "-O3", "-o", native_micro])
        run("native-book-compile", [*compiler, "--unfreed=off", "--source-path", iron_project, "-d", evidence / "native-book-classes", iron_project / "org/ironwood/orderbook/Bench.iron"])
        run("native-book-link", [*compiler, "--link", "-cp", evidence / "native-book-classes", "--main-class", "org.ironwood.orderbook.Bench", "-O3", "-o", native_book])
        for label, path in (("native-micro", native_micro), ("native-orderbook", native_book)):
            run(label + "-disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", path])
        sources = [HERE / name for name in ("PerformanceProbe.iron", "PerformanceNative.iron", "PerformanceConsumer.java", "PerformanceBaseline.c", "OrderBookBatch.iron", "OrderBookBatchConsumer.java", "measure-performance.py")]
        sources += [args.compiler.resolve(), paired, micro, batch, baseline, native_micro, native_book]
        sources += list(java_project.glob("*.java")) + list(iron_project.rglob("*.iron"))
        sources += [path for path in evidence.rglob("*.class")]
        (evidence / "prepared.json").write_text(json.dumps({"target": args.target, "inputs": {str(path): CANDIDATE.digest(path) for path in sources}}, indent=2) + "\n")
    prepared = json.loads((evidence / "prepared.json").read_text())
    if prepared["target"] != args.target: raise ValueError("prepared target mismatch")
    for path, expected in prepared["inputs"].items():
        if CANDIDATE.digest(Path(path)) != expected: raise ValueError("prepared input changed: " + path)
    if args.prepare_only:
        print("Prepared fixed performance inputs; no timing qualification: " + str(evidence)); return
    if (evidence / "measurements.json").exists(): raise ValueError("measurements already exist; preserve them")
    run("measurement-processes", ["ps", "-Ao", "pid,etime,pcpu,command"])
    records = []
    for fork in range(3):
        native = run(f"native-micro-{fork}", [native_micro, "1000000"]).splitlines()
        if len(native) != 3 or not all(re.fullmatch(r"-?\d+", value) for value in native): raise ValueError("native micro output")
        records.append({"kind": "native-micro", "fork": fork, "ns_per_operation": int(native[0]) / 1000000, "checksum": native[1:]})
        elapsed = int(run(f"native-book-{fork}", [native_book, "1", "2"]).strip())
        records.append({"kind": "native-orderbook", "fork": fork, "ns_per_cycle": elapsed / 250000})
    for major in (21, 22, 23):
        java = jdks[major]["java"]
        for fork in range(3):
            cold = run(f"java{major}-cold-{fork}", [java, "-cp", str(micro) + os.pathsep + str(evidence / "micro-classes"), "PerformanceConsumer", "cold"])
            records.append({"kind": "cold", "jdk": major, "fork": fork, "ns": int(cold.strip().removeprefix("cold-ns="))})
            for mode in MODES:
                output = run(f"java{major}-{mode}-{fork}", [java, "-cp", str(micro) + os.pathsep + str(evidence / "micro-classes"), "PerformanceConsumer", mode, baseline])
                first = re.search(r"mode=(\S+) count=(\d+) units=(\d+)", output)
                if first is None or first[1] != mode: raise ValueError("micro observation missing")
                durations = json.loads(re.search(r"duration-ns=(\[[^\n]+\])", output)[1]); allocated = json.loads(re.search(r"java-bytes=(\[[^\n]+\])", output)[1])
                records.append({"kind": mode, "jdk": major, "fork": fork, "count": int(first[2]), "units": int(first[3]),
                    "ns_per_operation": [value / (int(first[2]) * int(first[3])) for value in durations], "java_bytes": allocated,
                    "sampled_latency_ns": list(map(int, re.search(r"sampled-latency-ns=(\d+,\d+,\d+)", output)[1].split(",")))})
            for label, classpath, main_class in (("java-orderbook", str(evidence / "paired-classes"), "org.ironwood.orderbook.Bench"),
                ("bridge-orderbook", str(paired) + os.pathsep + str(evidence / "bridge-classes"), "org.ironwood.orderbook.Bench"),
                ("batch-orderbook", str(batch) + os.pathsep + str(evidence / "batch-classes"), "org.ironwood.orderbook.OrderBookBatchConsumer")):
                elapsed = int(run(f"java{major}-{label}-{fork}", [java, "-cp", classpath, main_class, "1", "2"]).strip())
                records.append({"kind": label, "jdk": major, "fork": fork, "ns_per_cycle": elapsed / 250000})
        for mode in ("scalar", "instance"):
            run(f"java{major}-jit-{mode}", [java, "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintCompilation", "-XX:+PrintInlining", "-cp",
                str(micro) + os.pathsep + str(evidence / "micro-classes"), "PerformanceConsumer", mode, baseline])
    result = {"target": args.target, "execution_scope": args.execution_scope, "host_notes": args.host_notes, "records": records,
        "prepared.sha256": CANDIDATE.digest(evidence / "prepared.json"), "acceptance": "pending maintainer numerical review",
        "method": "three fresh JVM forks per JDK/mode; seven warmed throughput observations; consumed deterministic results; sampled latency includes clock/dispatch cost; cold first native call excludes JVM startup; no checked JNI; native executables use matching baseline O3"}
    (evidence / "measurements.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"Collected {len(records)} performance records; numerical acceptance remains pending: {evidence}")


if __name__ == "__main__": main()
