#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Qualify the compiler-directed BDWGC profile and its allocation controls."""
import json
from pathlib import Path
import random
import subprocess
import sys
import run_experiments as runner

ROOT = Path(__file__).resolve().parent


def main():
    if not (ROOT / "roots.o").exists():
        runner.build_roots()
    cc = [str(runner.BIN / "clang"), "-O3", "-std=c11", "-D_POSIX_C_SOURCE=200809L",
          "-Wall", "-Wextra", "-Werror", "-I" + str(ROOT / "sources/gc-8.2.12/include")]
    lib = ROOT / "build-compiler-roots-large/.libs/libgc.a"
    for source, name, extra, wrapper in [
        ("mmtk_probe.c", "bdwgc-roots-large-probe", [], True),
        ("precise_roots.c", "bdwgc-precise-large-roots", [str(ROOT / "roots.o"), "-fno-omit-frame-pointer"], True),
        ("typeinfo_probe.c", "typeinfo-large-probe", [], False),
        ("large_span_probe.c", "large-span-probe", [], False),
        ("policy_probe.c", "policy-probe", [], True),
        ("allocation_probe.c", "allocation-compiler-roots-probe", [], True),
    ]:
        command = [*cc, str(ROOT / source)]
        if wrapper:
            command += [str(ROOT / "bdwgc_compiler_roots.c")]
        subprocess.run([*command, str(lib), *extra, "-o", str(ROOT / name)],
                       check=True, capture_output=True)
    results = []
    for name, command in [
        ("bdwgc-large-qualify", ["bdwgc-roots-large-probe", "0", "qualify"]),
        ("bdwgc-large-native", ["bdwgc-precise-large-roots"]),
        ("bdwgc-large-omitted", ["bdwgc-precise-large-roots", "marksweep", "omit"]),
        ("bdwgc-large-types", ["typeinfo-large-probe"]),
        ("large-span", ["large-span-probe"]),
        ("compiler-roots-policy", ["policy-probe"]),
    ]:
        results.append(runner.run(name, [str(ROOT / command[0]), *command[1:]]))
    if "--qualify-only" not in sys.argv:
        randomizer = random.Random(20261104)
        for repetition in range(5):
            jobs = [("compiler-roots", rare, [str(ROOT / "bdwgc-roots-large-probe"), "0", "bench", str(rare), "5000000"])
                    for rare in [0, 1]]
            jobs += [("calloc", rare, [str(ROOT / "probe-eager"), "bench", "0", str(rare), "5000000"])
                     for rare in [0, 1]]
            randomizer.shuffle(jobs)
            for label, rare, command in jobs:
                row = runner.run(f"final-{label}-{rare}-{repetition}", command)
                assert row["data"][0]["gc_events"] == 0
                row.update(label=label, rare=rare, repetition=repetition)
                results.append(row)
    (ROOT / "evidence/compiler-roots-large.json").write_text(json.dumps(results, indent=2) + "\n")
    allocation = []
    for backend in range(4):
        if "--qualify-only" not in sys.argv:
            for repetition in range(5):
                allocation.append(runner.run(f"latency-{backend}-{repetition}",
                                  [str(ROOT / "allocation-probe"), str(backend), "latency"]))
        if backend:
            allocation.append(runner.run(f"holes-{backend}",
                              [str(ROOT / "allocation-probe"), str(backend), "holes"]))
    (ROOT / "evidence/allocation.json").write_text(json.dumps(allocation, indent=2) + "\n")
    candidate = [runner.run("holes-compiler-roots",
                 [str(ROOT / "allocation-compiler-roots-probe"), "2", "holes"])]
    if "--qualify-only" not in sys.argv:
        for repetition in range(5):
            candidate.append(runner.run(f"latency-compiler-roots-{repetition}",
                             [str(ROOT / "allocation-compiler-roots-probe"), "2", "latency"]))
    (ROOT / "evidence/compiler-roots-allocation.json").write_text(json.dumps(candidate, indent=2) + "\n")
    print("compiler-directed qualification passed: six checks and four reuse controls", flush=True)


if __name__ == "__main__":
    main()
