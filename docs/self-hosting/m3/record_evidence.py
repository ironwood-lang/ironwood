# SPDX-License-Identifier: MIT OR Apache-2.0
"""Retain an M3 evidence run: compressed logs plus a manifest.

Usage: record_evidence.py SPEC.json

The spec names the evidence directory, the scratch run directory, the
increment, the commit the run used, the source files to hash and the logs to
retain. Logs are gzip-compressed with a zero timestamp so the retained bytes
depend only on their content. The manifest records the raw and compressed
SHA-256 of every log, the source hashes at the recorded commit and the run's
status lines.
"""
import gzip
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    spec = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    evidence = ROOT / spec["evidence"]
    run = ROOT / spec["run"]
    logs = evidence / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    commit = (run / "commit.txt").read_text(encoding="utf-8").strip()
    sources = []
    for path in spec["sources"]:
        blob = subprocess.run(["git", "-C", str(ROOT), "show", f"{commit}:{path}"],
                              check=True, capture_output=True).stdout
        sources.append({"path": path, "sha256": sha256(blob)})
    retained = []
    for name in spec["logs"]:
        raw = (run / name).read_bytes()
        target = logs / (Path(name).name + ".gz")
        target.write_bytes(gzip.compress(raw, compresslevel=9, mtime=0))
        retained.append({"log": name, "retained": str(target.relative_to(ROOT)),
                         "sha256": sha256(raw), "gzip_sha256": sha256(target.read_bytes())})
    status = (run / "status.txt").read_text(encoding="utf-8").splitlines()
    manifest = {
        "schema_version": 1,
        "increment": spec["increment"],
        "commit": commit,
        "host": spec.get("host", "macOS arm64 Apple M5, 32 GiB"),
        "java": "/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home",
        "llvm": "/opt/homebrew/opt/llvm@23",
        "reproduce": spec["reproduce"],
        "status": status,
        "sources": sources,
        "logs": retained,
    }
    manifest.update(spec.get("extra", {}))
    (evidence / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
    failures = [line for line in status if not line.endswith(("exit=0", "exit=42"))]
    if failures:
        print("non-passing status lines:", failures)
        return 1
    print(f"recorded {len(retained)} logs and {len(sources)} sources for {commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
