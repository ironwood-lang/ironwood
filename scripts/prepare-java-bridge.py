#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Explicit pinned bridge JDK setup and offline preflight, separate from IDK pins."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PINS = ROOT / "packaging/java-bridge-jdks.json"
INPUTS = ("packaging/java-bridge-jdks.json", "scripts/prepare-java-bridge.py",
          "scripts/java-bridge/Dockerfile")
INJECTED_OPTIONS = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def execute(command):
    result = subprocess.run(list(map(str, command)), check=True, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return result.stdout.strip()


def settings(text):
    return dict(re.findall(r"^\s*([\w.]+) = (.*)$", text, re.MULTILINE))


def check_jdk(prefix, target, pins):
    for name in INJECTED_OPTIONS:
        if os.environ.get(name):
            raise ValueError(f"unset {name} for reproducible bridge subprocesses")
    selected = pins["targets"][target]
    home = (prefix / selected["home"]).resolve()
    java, javac = home / "bin/java", home / "bin/javac"
    version = execute([java, "-XshowSettings:properties", "-version"])
    actual = settings(version)
    expected = {"java.vendor": pins["vendor"], "java.runtime.version": pins["version"],
                "os.arch": selected["arch"], "os.name": selected["os"]}
    for key, value in expected.items():
        if actual.get(key) != value:
            raise ValueError(f"{java}: expected {key}={value}, observed {actual.get(key)!r}")
    if "OpenJDK 64-Bit Server VM" not in actual.get("java.vm.name", ""):
        raise ValueError("bridge requires the pinned HotSpot Server VM")
    javac_version = execute([javac, "-version"])
    if javac_version != "javac " + pins["version"].split("+")[0]:
        raise ValueError(f"unexpected compiler: {javac_version}")
    installation = json.loads((prefix / "installation.json").read_text())
    if installation != {"target": target, "archive": selected, "pins_sha256": digest(PINS)}:
        raise ValueError("JDK installation does not match checked-in bridge pins")
    return {"target": target, "java": str(java), "javac": str(javac),
            "java_settings": version, "javac_version": javac_version,
            "archive": selected, "pins_sha256": digest(PINS),
            "host": platform.uname()._asdict(), "libc": platform.libc_ver(),
            "evidence_scope": "JDK preflight only; no physical CPU or bridge case qualification"}


def install(prefix, cache, target, pins):
    if prefix.exists():
        raise ValueError(f"refusing to overwrite existing JDK prefix: {prefix}")
    selected = pins["targets"][target]
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / (selected["sha256"] + ".tar.gz")
    if not archive.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temporary:
            pending = Path(temporary.name)
        try:
            with urllib.request.urlopen(selected["url"], timeout=120) as response, pending.open("wb") as output:
                shutil.copyfileobj(response, output)
            if digest(pending) != selected["sha256"]:
                raise ValueError("downloaded JDK archive SHA-256 mismatch")
            pending.replace(archive)
        finally:
            pending.unlink(missing_ok=True)
    if digest(archive) != selected["sha256"]:
        raise ValueError(f"cached JDK archive SHA-256 mismatch: {archive}")
    prefix.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=prefix.parent, prefix=".bridge-jdk-") as temporary:
        staged = Path(temporary) / "installation"
        staged.mkdir()
        with tarfile.open(archive) as source:
            source.extractall(staged, filter="data")
        (staged / "installation.json").write_text(json.dumps(
            {"target": target, "archive": selected, "pins_sha256": digest(PINS)}, indent=2) + "\n")
        check_jdk(staged, target, pins)
        staged.rename(prefix)


def image_key(base_identity, target):
    identity = hashlib.sha256((base_identity + "\0" + target).encode())
    for name in INPUTS:
        identity.update(name.encode() + b"\0" + (ROOT / name).read_bytes())
    return "ironwood-bridge-" + target + ":" + identity.hexdigest()[:16]


def setup_image(args):
    if not args.target.startswith("linux-") or not args.base_image:
        raise ValueError("--setup-image requires a Linux target and --base-image")
    docker = ["docker"] + (["--context", args.docker_context] if args.docker_context else [])
    base_identity = execute([*docker, "image", "inspect", "--format", "{{.Id}}", args.base_image])
    image = image_key(base_identity, args.target)
    architecture = "arm64" if args.target == "linux-arm64" else "amd64"
    with tempfile.TemporaryDirectory(prefix="ironwood-bridge-image-") as temporary:
        context = Path(temporary)
        for name in INPUTS:
            destination = context / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, destination)
        subprocess.run([*docker, "build", "--platform", "linux/" + architecture,
                        "--build-arg", "BASE_IMAGE=" + args.base_image,
                        "--build-arg", "BRIDGE_TARGET=" + args.target,
                        "--file", str(context / "scripts/java-bridge/Dockerfile"),
                        "--tag", image, str(context)], check=True)
    return {"image": image, "base_identity": base_identity,
            "image_inspect": json.loads(execute([*docker, "image", "inspect", image])),
            "target": args.target, "evidence_scope": "setup only, not a hardware qualification"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--setup", action="store_true")
    action.add_argument("--check", action="store_true", help="offline check; never downloads")
    action.add_argument("--setup-image", action="store_true")
    pins = json.loads(PINS.read_text())
    parser.add_argument("--target", required=True, choices=tuple(pins["targets"]))
    parser.add_argument("--prefix", type=Path)
    parser.add_argument("--cache", type=Path, default=ROOT / "workspace/java-bridge/downloads")
    parser.add_argument("--base-image")
    parser.add_argument("--docker-context")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if args.setup_image:
        evidence = setup_image(args)
    else:
        if args.prefix is None:
            parser.error("--prefix is required for JDK setup/check")
        prefix = args.prefix.resolve()
        if args.setup:
            install(prefix, args.cache.resolve(), args.target, pins)
        evidence = check_jdk(prefix, args.target, pins)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps({key: evidence[key] for key in ("target", "java", "image") if key in evidence}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"bridge preparation failed: {error}") from error
