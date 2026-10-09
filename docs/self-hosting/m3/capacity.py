# SPDX-License-Identifier: MIT OR Apache-2.0
"""Summarize S3 capacity measurements of a compiler build of the port.

Usage: capacity.py DIRECTORY OUTPUT.json

DIRECTORY holds, per measured command NAME, NAME.status (exit code),
NAME.wall (monotonic seconds from the runner), NAME.rusage (macOS
/usr/bin/time -l), NAME.gc (unified JVM GC log) and NAME.load (uptime at
start). The summary records wall time, maximum RSS in bytes (rusage of the
JVM and its waited children, so a link includes LLVM and Clang), the largest
sampled used heap before a collection (a lower bound on peak heap, not a
live-object measurement), the collection count and the start load. A missing
GC log or a nonzero status is recorded as such, never as zero usage.
"""
import json
import re
import sys
from pathlib import Path


def heap(gc_path):
    if not gc_path.exists():
        return None, 0
    peak, count = None, 0
    for line in gc_path.read_text(errors="replace").splitlines():
        match = re.search(r"Pause .*?(\d+)M->(\d+)M\((\d+)M\)", line)
        if match:
            count += 1
            before = int(match.group(1)) * 1024 * 1024
            peak = before if peak is None else max(peak, before)
    return peak, count


def main():
    directory, output = Path(sys.argv[1]), Path(sys.argv[2])
    records = []
    for status_file in sorted(directory.glob("*.status")):
        name = status_file.stem
        rusage = (directory / f"{name}.rusage").read_text() if (directory / f"{name}.rusage").exists() else ""
        rss = re.search(r"(\d+)\s+maximum resident set size", rusage)
        peak, collections = heap(directory / f"{name}.gc")
        records.append({
            "name": name,
            "exit": int(status_file.read_text().strip()),
            "wall_seconds": float((directory / f"{name}.wall").read_text().strip()),
            "max_rss_bytes": int(rss.group(1)) if rss else None,
            "sampled_heap_before_gc_bytes": peak,
            "gc_pauses": collections,
            "start_load": (directory / f"{name}.load").read_text().strip(),
        })
    output.write_text(json.dumps({"records": records}, indent=1) + "\n")
    for record in records:
        print(record["name"], "exit", record["exit"], "wall", record["wall_seconds"],
              "rss", record["max_rss_bytes"], "heap", record["sampled_heap_before_gc_bytes"],
              "pauses", record["gc_pauses"])


if __name__ == "__main__":
    main()
