#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Validate recorded performance identities/checksums and summarize observations without accepting thresholds."""

import argparse
import hashlib
import json
from pathlib import Path
import statistics


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def recurrence(seed, count):
    # Exponentiation of the specified affine map modulo 2^64, independently of
    # the measured native loop. This takes logarithmic time for the fixed count.
    mask = (1 << 64) - 1
    multiplier, increment = 2862933555777941757, 3037000493
    accumulated_multiplier, accumulated_increment = 1, 0
    while count:
        if count & 1:
            accumulated_increment = (accumulated_increment * multiplier + increment) & mask
            accumulated_multiplier = accumulated_multiplier * multiplier & mask
        increment = (multiplier + 1) * increment & mask
        multiplier = multiplier * multiplier & mask
        count >>= 1
    value = (accumulated_multiplier * seed + accumulated_increment) & mask
    return value if value < 1 << 63 else value - (1 << 64)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args(); evidence = args.evidence.resolve()
    measured = json.loads((evidence / "measurements.json").read_text())
    prepared = json.loads((evidence / "prepared.json").read_text())
    if measured["prepared.sha256"] != digest(evidence / "prepared.json"): raise ValueError("changed preparation inventory")
    for filename, expected in prepared["inputs"].items():
        if digest(Path(filename)) != expected: raise ValueError("changed measured input: " + filename)
    records = measured["records"]
    if len(records) != 132: raise ValueError("incomplete observation set")
    expected_checksums = [str(recurrence(seed, 1000000)) for seed in (19, 17)]
    if any(record["checksum"] != expected_checksums for record in records if record["kind"] == "native-micro"):
        raise ValueError("native scalar recurrence checksum mismatch")
    summaries = []
    groups = sorted({(row.get("jdk", 0), row["kind"]) for row in records})
    for major, kind in groups:
        rows = [row for row in records if row.get("jdk", 0) == major and row["kind"] == kind]
        if sorted(row["fork"] for row in rows) != [0, 1, 2]: raise ValueError("missing or repeated fork")
        values = []; bytes_per_op = []; latency = []
        for row in rows:
            value = row.get("ns_per_operation", row.get("ns_per_cycle", row.get("ns")))
            values.extend(value if isinstance(value, list) else [value])
            if "java_bytes" in row:
                if len(row["java_bytes"]) != 7 or len(row["ns_per_operation"]) != 7: raise ValueError("missing warm trial")
                bytes_per_op.extend(value / row["count"] for value in row["java_bytes"])
                latency.append(row["sampled_latency_ns"])
        if any(value <= 0 for value in values): raise ValueError("nonpositive timing observation")
        summary = {"jdk": major or None, "kind": kind, "forks": 3, "observations": len(values),
                   "median_ns": statistics.median(values), "minimum_ns": min(values), "maximum_ns": max(values),
                   "unit": "cold call" if kind == "cold" else "eight-operation cycle" if "orderbook" in kind else "operation"}
        if bytes_per_op:
            summary["java_bytes_per_call_range"] = [min(bytes_per_op), max(bytes_per_op)]
            summary["sampled_latency_p50_p95_p99_ns_by_fork"] = latency
        summaries.append(summary)
    ratios = []
    for major in (21, 22, 23):
        get = lambda kind: next(row for row in summaries if row["jdk"] == major and row["kind"] == kind)
        for generated, baseline in (("scalar", "bare"), ("instance", "bare-instance")):
            observed, reference = get(generated)["median_ns"], get(baseline)["median_ns"]
            ratios.append({"jdk": major, "generated": generated, "baseline": baseline, "delta_ns": observed - reference, "ratio": observed / reference})
        for kind in ("scalar", "instance", "object"):
            if get(kind)["java_bytes_per_call_range"] != [0, 0]: raise ValueError("unexpected warmed managed allocation: " + kind)
    result = {"target": measured["target"], "execution_scope": measured["execution_scope"], "summaries": summaries, "jni_comparisons": ratios,
              "measurements.sha256": digest(evidence / "measurements.json"), "native_checksums": expected_checksums,
              "acceptance": "pending maintainer review; no automatic numerical threshold"}
    (evidence / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    for row in summaries:
        print(f'{row["jdk"] or "native"} {row["kind"]}: {row["median_ns"]:.3f} ns/{row["unit"]} [{row["minimum_ns"]:.3f}, {row["maximum_ns"]:.3f}]')
    print("Identities, checksums and warm allocation assertions pass; numerical acceptance remains pending.")


if __name__ == "__main__": main()
