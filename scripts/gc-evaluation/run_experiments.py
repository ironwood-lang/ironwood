#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Bounded, host-local GC qualification and synthetic performance measurements."""
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "evidence"
RAW = OUT / "raw"
RAW.mkdir(exist_ok=True)
GUARD_KIB = 16 * 1024 * 1024
BIN = Path(os.environ.get("GC_PROBE_LLVM_BIN", "/opt/homebrew/opt/llvm@23/bin"))


def run(name, command, expected=0, timeout=60):
    stdout = RAW / (name + ".stdout")
    stderr = RAW / (name + ".stderr")
    peak = 0
    with stdout.open("w") as output, stderr.open("w") as error:
        child = subprocess.Popen(command, cwd=ROOT, stdout=output, stderr=error,
                                 start_new_session=True)
        started = time.monotonic()
        while child.poll() is None:
            rss = subprocess.run(["ps", "-o", "rss=", "-p", str(child.pid)],
                                 text=True, capture_output=True).stdout.strip()
            if rss:
                peak = max(peak, int(rss))
            if peak >= GUARD_KIB or time.monotonic() - started > timeout:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()
                raise RuntimeError(f"guard or timeout: {name}, {peak} KiB")
            time.sleep(0.1)
        if child.returncode != expected:
            raise RuntimeError(f"{name}: exit {child.returncode}, expected {expected}: "
                               + stderr.read_text()[-2000:])
    return {"name": name, "exit": child.returncode, "sampled_peak_rss_kib": peak,
            "stdout_sha256": hashlib.sha256(stdout.read_bytes()).hexdigest(),
            "stderr_sha256": hashlib.sha256(stderr.read_bytes()).hexdigest(),
            "data": [json.loads(line) for line in stdout.read_text().splitlines()
                     if line.startswith("{")]}


def build_roots():
    commands = [
        [BIN / "llvm-as", ROOT / "precise_roots.ll", "-o", ROOT / "roots.bc"],
        [BIN / "opt", "-passes=default<O3>,rewrite-statepoints-for-gc",
         ROOT / "roots.bc", "-o", ROOT / "roots-opt.bc"],
        [BIN / "llc", "-filetype=obj", ROOT / "roots-opt.bc", "-o", ROOT / "roots.o"],
        [BIN / "llc", ROOT / "roots-opt.bc", "-o", ROOT / "roots.s"],
        [BIN / "clang", "-std=c11", "-O3", "-fno-omit-frame-pointer", "-Wall",
         "-Wextra", "-Werror", ROOT / "precise_roots.c", ROOT / "roots.o",
         "-L" + str(ROOT / "mmtk-binding/target/release"), "-lironwood_gc_probe",
         "-Wl,-rpath," + str(ROOT / "mmtk-binding/target/release"),
         "-o", ROOT / "precise-roots"],
    ]
    for command in commands:
        subprocess.run(list(map(str, command)), check=True, capture_output=True)


def main():
    build_roots()
    qualification = []
    for name, args, expected in [
        ("cycles", ["cycles"], 0), ("integer-roots", ["integer"], 0),
        ("numeric-untyped", ["heap", "0"], 0),
        ("numeric-typed", ["heap", "1"], 0),
        ("oom-disabled", ["oom", "0"], 0),
        ("oom-heuristic-only", ["oom", "1"], 3),
        ("sweep-eager", ["sweep"], 0),
    ]:
        qualification.append(run(name, [str(ROOT / "probe-eager"), *args], expected))
    qualification.append(run("sweep-lazy", [str(ROOT / "probe-lazy"), "sweep"]))
    for plan in ["0", "1"]:
        qualification.append(run("mmtk-qualify-" + plan,
                                 [str(ROOT / "mmtk-probe"), plan, "qualify"]))
    for plan in ["marksweep", "immix"]:
        qualification.append(run("roots-" + plan, [str(ROOT / "precise-roots"), plan]))
        qualification.append(run("omitted-roots-" + plan,
                                 [str(ROOT / "precise-roots"), plan, "omit"]))
    (OUT / "qualification.json").write_text(json.dumps(qualification, indent=2) + "\n")
    print("qualification passed", len(qualification), "cases", flush=True)
    if "--qualify-only" in sys.argv:
        return

    native = []
    for allocator, label in [(0, "calloc"), (1, "bdwgc-untyped"), (2, "bdwgc-typed")]:
        for rare in [0, 1]:
            native.append((label, rare, [str(ROOT / "probe-eager"), "bench",
                                         str(allocator), str(rare), "5000000"]))
    for plan, label in [(0, "mmtk-marksweep"), (1, "mmtk-immix")]:
        for rare in [0, 1]:
            native.append((label, rare, [str(ROOT / "mmtk-probe"), str(plan),
                                         "bench", str(rare), "5000000"]))
    java = []
    for label, flag in [("epsilon", "-XX:+UseEpsilonGC"),
                        ("serial", "-XX:+UseSerialGC"), ("g1", "-XX:+UseG1GC")]:
        for rare in [0, 1]:
            java.append(("java-" + label, rare,
                         ["java", "-Xms512m", "-Xmx512m", "-XX:+AlwaysPreTouch",
                          "-XX:+UnlockExperimentalVMOptions", flag, "-cp", str(ROOT),
                          "PoolBench", str(bool(rare)).lower(), "5000000"]))
    results = []
    randomizer = random.Random(20261104)
    for repetition in range(5):
        jobs = native + java
        randomizer.shuffle(jobs)
        for label, rare, command in jobs:
            record = run(f"bench-{label}-{rare}-{repetition}", command)
            record.update(label=label, rare=rare, repetition=repetition)
            data = record["data"][0]
            assert data["gc_events"] == 0, record["name"]
            record["median_ns_per_op"] = statistics.median(data["ns"]) / data["iterations"]
            results.append(record)
        print("completed randomized repetition", repetition + 1, flush=True)
    checksums = {r["data"][0]["checksum"] for r in results if not r["label"].startswith("java-")}
    assert len(checksums) == 1, checksums
    (OUT / "benchmarks.json").write_text(json.dumps(results, indent=2) + "\n")
    summary = []
    for label, rare, _ in native + java:
        rows = [r for r in results if r["label"] == label and r["rare"] == rare]
        samples = [r["median_ns_per_op"] for r in rows]
        summary.append({"label": label, "rare": rare,
                        "median_ns_per_op": statistics.median(samples),
                        "min_process_median": min(samples),
                        "max_process_median": max(samples),
                        "processes": len(rows)})
    (OUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
