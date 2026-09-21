#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Compare two preserved OrderBook executables; Python 3.6+, no dependencies."""

import argparse
import datetime
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import statistics
import subprocess
import sys


def integer(text):
    if not re.fullmatch(r"[0-9]+", text):
        raise argparse.ArgumentTypeError("expected a non-negative integer")
    return int(text)


def digest(path):
    with path.open("rb") as source:
        result = hashlib.sha256()
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def parse_output(output, mode, arguments):
    if mode == "throughput":
        if not re.fullmatch(r"[0-9]+\s*", output) or int(output) <= 0:
            raise ValueError("expected exactly one positive integer duration in ns")
        return {"elapsed_ns": int(output)}
    warmup, measured, cycles = arguments
    expected = {
        "Cycles per batch": cycles,
        "Operations per batch": cycles * 8,
        "Measured operations": measured * cycles * 8,
        "Measurements": measured,
        "Warm-Up": warmup,
        "Iterations": warmup + measured,
    }
    for label, value in expected.items():
        found = re.findall(r"(?:^| \| )" + re.escape(label)
                           + r": ([0-9,]+)(?=\n| \| |$)", output, re.M)
        if len(found) != 1 or found[0] not in (str(value), format(value, ",")):
            raise ValueError("latency report count mismatch: " + label)
    units = {"nano": 1, "micro": 1000, "milli": 1000000, "second": 1000000000}
    metrics = {}
    for label, key in [("Avg Time", "average_ns"), ("Min Time", "minimum_ns"),
                       ("Max Time", "maximum_ns")]:
        found = re.findall(re.escape(label) + r": ([0-9]+(?:\.[0-9]+)?) "
                           + r"(nano|micro|milli|second)s?(?=\n| \| |$)", output)
        if len(found) != 1:
            raise ValueError("missing or invalid latency metric: " + label)
        value = Decimal(found[0][0]) * units[found[0][1]]
        if value < 0 or (value == 0 and key != "minimum_ns"):
            raise ValueError("latency duration must be positive: " + label)
        metrics[key] = float(value)
    return metrics


def summarize(rows):
    result = {"pairs": len(rows) // 2, "metrics": {}, "outliers_removed": 0}
    for key in rows[0]["metrics"]:
        values = {variant: [row["metrics"][key] for row in rows
                            if row["variant"] == variant]
                  for variant in ("baseline", "candidate")}
        metric = {variant: {"min": min(items), "median": statistics.median(items),
                            "max": max(items)} for variant, items in values.items()}
        changes = [(candidate / baseline - 1) * 100 if baseline else None for baseline, candidate
                   in zip(values["baseline"], values["candidate"])]
        metric["median_change_percent"] = (
            (metric["candidate"]["median"] / metric["baseline"]["median"] - 1) * 100
            if metric["baseline"]["median"] else None)
        metric["paired_change_percent"] = changes
        metric["median_paired_change_percent"] = (
            statistics.median(changes) if None not in changes else None)
        metric["candidate_lower_pairs"] = sum(candidate < baseline for baseline, candidate
                                              in zip(values["baseline"], values["candidate"]))
        result["metrics"][key] = metric
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, required=True,
                        help="new directory; existing paths are never overwritten")
    parser.add_argument("--mode", choices=("throughput", "latency"), default="throughput")
    parser.add_argument("--pairs", type=integer, default=8)
    parser.add_argument("--first", choices=("baseline", "candidate"), default="baseline")
    parser.add_argument("--warmup", type=integer)
    parser.add_argument("--measured", type=integer)
    parser.add_argument("--cycles", type=integer, help="latency mode only")
    parser.add_argument("--cpu", type=integer, help="optional single Linux CPU via taskset")
    parser.add_argument("--timeout", type=integer, default=300, help="seconds per process")
    args = parser.parse_args()
    latency = args.mode == "latency"
    warmup = args.warmup if args.warmup is not None else (10000 if latency else 8)
    measured = args.measured if args.measured is not None else (50000 if latency else 80)
    cycles = args.cycles if args.cycles is not None else 1000
    if not args.pairs or not measured or not args.timeout or (latency and not cycles):
        parser.error("pairs, measured work, cycles and timeout must be positive")
    if not latency and args.cycles is not None:
        parser.error("--cycles requires --mode latency")
    arguments = [warmup, measured] + ([cycles] if latency else [])
    if any(value > 2147483647 for value in arguments):
        parser.error("benchmark arguments must fit a signed 32-bit integer")
    if latency and (warmup + measured > 2147483647
                    or (warmup + measured) * cycles > (2 ** 63 - 1) // 250):
        parser.error("latency sample count or workload counters would overflow")
    binaries = {variant: getattr(args, variant).resolve()
                for variant in ("baseline", "candidate")}
    for path in binaries.values():
        if not path.is_file() or not os.access(str(path), os.X_OK):
            parser.error("not an executable file: " + str(path))
    prefix = []
    if args.cpu is not None:
        taskset = shutil.which("taskset")
        if platform.system() != "Linux" or not taskset:
            parser.error("--cpu requires Linux and an existing taskset command")
        prefix = [taskset, "-c", str(args.cpu)]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    hashes = {variant: digest(path) for variant, path in binaries.items()}
    metadata = {"started_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "platform": platform.platform(), "python": sys.version,
                "cwd": os.getcwd(), "mode": args.mode, "arguments": arguments,
                "pairs": args.pairs, "first": args.first, "cpu": args.cpu,
                "timeout_seconds": args.timeout,
                "binaries": {variant: {"path": str(path), "sha256": hashes[variant]}
                             for variant, path in binaries.items()},
                "warmup_operations_per_process": warmup * (cycles * 8 if latency else 1000000),
                "measured_operations_per_process": measured * (cycles * 8 if latency else 1000000)}
    write_json(output / "metadata.json", metadata)
    rows = []
    try:
        for pair in range(args.pairs):
            order = [args.first, "candidate" if args.first == "baseline" else "baseline"]
            if pair % 2:
                order.reverse()
            for variant in order:
                stem = "{:03d}-{}".format(pair + 1, variant)
                command = prefix + [str(binaries[variant])] + [str(v) for v in arguments]
                row = {"pair": pair + 1, "variant": variant, "command": command,
                       "stdout": stem + ".stdout", "stderr": stem + ".stderr"}
                # File-backed output survives a nonzero exit, timeout or malformed report.
                try:
                    with (output / row["stdout"]).open("wb") as stdout, \
                            (output / row["stderr"]).open("wb") as stderr:
                        process = subprocess.run(command, stdout=stdout, stderr=stderr,
                                                 timeout=args.timeout)
                    row["returncode"] = process.returncode
                    if process.returncode != 0:
                        raise ValueError("benchmark exited {}".format(process.returncode))
                    row["metrics"] = parse_output(
                        (output / row["stdout"]).read_text(), args.mode, arguments)
                except (OSError, ValueError, subprocess.TimeoutExpired) as error:
                    row["error"] = str(error)
                    raise
                finally:
                    with (output / "samples.jsonl").open("a") as log:
                        log.write(json.dumps(row, sort_keys=True) + "\n")
                rows.append(row)
            print("pair {} / {} complete".format(pair + 1, args.pairs), flush=True)
        for variant, path in binaries.items():
            if digest(path) != hashes[variant]:
                raise ValueError("executable changed while measuring: " + str(path))
        summary = summarize(rows)
        write_json(output / "summary.json", summary)
        print(json.dumps(summary, indent=2, sort_keys=True))
    except (OSError, ValueError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        write_json(output / "failure.json", {"error": str(error), "completed_samples": len(rows)})
        raise


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.TimeoutExpired, KeyboardInterrupt) as error:
        print("error: {}".format(error), file=sys.stderr)
        sys.exit(1)
