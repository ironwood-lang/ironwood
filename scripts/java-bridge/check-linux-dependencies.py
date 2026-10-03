#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""P1 D202: inventory final ELF closure and exercise it in a JVM-only scratch image."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(directory, name, command, timeout=90):
    command = list(map(str, command))
    (directory / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    (directory / (name + ".log")).write_text(result.stdout)
    (directory / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
    if result.returncode:
        raise ValueError(f"{name} failed ({result.returncode}): {result.stdout}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, choices=("linux-arm64", "linux-x86_64"))
    parser.add_argument("--execution-scope", required=True, choices=("ARM64 virtualization", "x86-64 Rosetta translation", "x86-64 physical hardware"))
    parser.add_argument("--development-image", required=True)
    parser.add_argument("--docker-context")
    parser.add_argument("--library-evidence", type=Path, required=True)
    parser.add_argument("--traces-evidence", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    directory = args.evidence.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    docker = ["docker", *(["--context", args.docker_context] if args.docker_context else [])]
    arch = "arm64" if args.target == "linux-arm64" else "amd64"
    base = json.loads(run(directory, "development-image", [*docker, "image", "inspect", args.development_image]))[0]
    local_base = "ironwood-bridge-source:" + base["Id"].split(":", 1)[1]
    run(directory, "name-local-base", [*docker, "tag", base["Id"], local_base])
    minimal_sources = Path(__file__).with_name("minimal-jvm")
    identity = hashlib.sha256(base["Id"].encode() + b"\0" + args.target.encode())
    for path in sorted(minimal_sources.iterdir()):
        identity.update(path.name.encode() + b"\0" + path.read_bytes())
    image = "ironwood-bridge-minimal-" + args.target + ":" + identity.hexdigest()[:16]
    run(directory, "build", [*docker, "build", "--pull=false", "--platform", "linux/" + arch, "--build-arg", "DEVELOPMENT_IMAGE=" + local_base,
                            "--tag", image, minimal_sources], timeout=180)
    inspection = json.loads(run(directory, "minimal-image", [*docker, "image", "inspect", image]))[0]
    container = run(directory, "create-inventory-container", [*docker, "create", "--platform", "linux/" + arch, image, "-version"]).strip()
    try:
        run(directory, "copy-inventory", [*docker, "cp", container + ":/jvm-runtime-inventory.json", directory / "jvm-runtime-inventory.json"])
    finally:
        run(directory, "remove-inventory-container", [*docker, "rm", container])
    inventory = json.loads((directory / "jvm-runtime-inventory.json").read_text())
    if any("libstdc++" in name or "libgcc_s" in name for name in inventory["libraries"]):
        raise ValueError("minimal JVM unexpectedly carries the tested compiler runtimes")
    run(directory, "java", [*docker, "run", "--rm", "--platform", "linux/" + arch, image, "-XshowSettings:properties", "-version"])
    libraries = args.library_evidence.resolve()
    traces = args.traces_evidence.resolve()
    records = []
    for fixture, root in [("library", libraries), ("traces", traces)]:
        files = sorted(root.rglob("*.so")) + sorted(root.rglob("*.so.1")) + sorted(root.rglob("*.so.6"))
        for index, path in enumerate(files):
            name = f"{fixture}-elf-{index}"
            relative = path.relative_to(root)
            output = run(directory, name, [*docker, "run", "--rm", "--platform", "linux/" + arch,
                         "-v", str(root) + ":/payload:ro", base["Id"], "/opt/ironwood-toolchain/bin/llvm-readelf",
                         "--dynamic", "--version-info", "/payload/" + str(relative)])
            needs = output.split("Version needs section", 1)[-1]
            if any(tuple(map(int, version.split("."))) > (2, 17) for version in re.findall(r"Name: GLIBC_([0-9.]+)", needs)):
                raise ValueError(f"{path}: exceeds the glibc 2.17 native baseline")
            paths = re.findall(r"Library (?:rpath|runpath): \[([^\]]*)\]", output)
            if not paths or any(not part.startswith("$ORIGIN") for value in paths for part in value.split(":")):
                raise ValueError(f"{path}: missing or nonrelative dependency lookup path")
            if path.parent == root or path.parent.name in ("left", "right"):
                if "BIND_NOW" not in output and not re.search(r"FLAGS.*NOW", output):
                    raise ValueError(f"{path}: missing eager relocation binding")
            resolved = run(directory, name + "-closure", [*docker, "run", "--rm", "--platform", "linux/" + arch,
                           "-v", str(root) + ":/payload:ro", base["Id"], "ldd", "/payload/" + str(relative)])
            if "not found" in resolved:
                raise ValueError(f"{path}: unresolved closure")
            for line in resolved.splitlines():
                if ("libstdc++.so.6 =>" in line or "libgcc_s.so.1 =>" in line) and "/payload/" not in line:
                    raise ValueError(f"{path}: resolved compiler runtime outside the delivered bundle")
            records.append({"fixture": fixture, "path": str(relative), "sha256": digest(path), "audit": name})
    for level in ("O0", "O3"):
        for checked in (False, True):
            suffix = level + ("-checked" if checked else "-ordinary")
            options = ["-Xcheck:jni"] if checked else []
            for budget in ("normal", "0", "1"):
                output = run(directory, "library-" + suffix + "-" + budget, [*docker, "run", "--rm", "--platform", "linux/" + arch,
                             "-v", str(libraries) + ":/payload:ro", *([] if budget == "normal" else ["-e", "IRONWOOD_ALLOCATION_LIMIT=" + budget]),
                             image, *options, "-cp", "/payload", "BridgeLibraryConsumer", f"/payload/library-{level}.so", budget])
                if output != "library-ok:" + budget + "\n":
                    raise ValueError("minimal JVM library/continued-call checks failed")
            output = run(directory, "missing-" + suffix, [*docker, "run", "--rm", "--platform", "linux/" + arch,
                         "-v", str(libraries) + ":/payload:ro", image, *options, "-cp", "/payload", "BridgeMissingSymbolConsumer",
                         f"/payload/missing-{level}.so"])
            if output != "missing-symbol-contained\n":
                raise ValueError("missing relocation entered native code or escaped the Java loader")
            output = run(directory, "traces-" + suffix, [*docker, "run", "--rm", "--platform", "linux/" + arch,
                         "-v", str(traces) + ":/payload:ro", image, *options, "-cp", "/payload", "BridgeTracePair",
                         f"/payload/left/trace-{level}.so", f"/payload/right/trace-{level}.so"])
            if output != "disjoint-traces-ok\n":
                raise ValueError("minimal JVM disjoint-image checks failed")
    payload_files = {label: {str(path.relative_to(root)): digest(path) for path in sorted(root.rglob("*")) if path.is_file()}
                     for label, root in [("library", libraries), ("traces", traces)]}
    (directory / "result.json").write_text(json.dumps({"target": args.target, "execution_scope": args.execution_scope,
            "development_image": base["Id"], "minimal_image": inspection["Id"], "records": records,
            "payload_files": payload_files, "scope": "P1 D202 only; no P6 or hardware inference from container architecture"}, indent=2) + "\n")
    print(f"D202 dependency/load checks passed: {directory} ({args.execution_scope})")


if __name__ == "__main__":
    main()
