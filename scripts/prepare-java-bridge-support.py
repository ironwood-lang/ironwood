#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Prepare pinned private Linux bridge dependencies and corresponding source; never a consumer step."""

import argparse
import compression.zstd
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PINS = ROOT / "packaging/java-bridge-support.properties"


def progress(message):
    print(f"bridge support: {message}", file=sys.stderr, flush=True)


def download_progress(name, received, total, elapsed):
    size = f"{received / (1024 * 1024):.1f} MiB"
    if total:
        size += f" / {total / (1024 * 1024):.1f} MiB ({100 * received / total:.0f}%)"
    speed = received / (1024 * 1024) / elapsed if elapsed > 0 else 0
    progress(f"{name}: {size}, {speed:.1f} MiB/s, {elapsed:.0f}s elapsed")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#"))


def download(cache, url, expected):
    cache.mkdir(parents=True, exist_ok=True)
    destination = cache / url.rsplit("/", 1)[1]
    if not destination.exists():
        progress(f"downloading {url} (connection/read timeout: 120s)")
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
            pending = Path(stream.name)
        try:
            with urllib.request.urlopen(url, timeout=120) as source, pending.open("wb") as output:
                length = source.headers.get("Content-Length", "")
                total = int(length) if length.isdecimal() and int(length) > 0 else None
                started = last_report = time.monotonic()
                received = 0
                download_progress(destination.name, received, total, 0)
                # Do not wait for a full chunk before reporting a slow transfer.
                while chunk := source.read1(1024 * 1024):
                    output.write(chunk)
                    received += len(chunk)
                    now = time.monotonic()
                    if now - last_report >= 2:
                        download_progress(destination.name, received, total, now - started)
                        last_report = now
                download_progress(destination.name, received, total, time.monotonic() - started)
            progress(f"verifying download SHA-256: {destination.name}")
            if digest(pending) != expected:
                raise ValueError(f"download SHA-256 mismatch: {url}")
            pending.replace(destination)
        finally:
            pending.unlink(missing_ok=True)
    else:
        progress(f"using cached download: {destination}")
    progress(f"verifying cached SHA-256: {destination.name}")
    if digest(destination) != expected:
        raise ValueError(f"cached SHA-256 mismatch: {destination}")
    return destination


def check(prefix, target, pins):
    progress(f"checking SDK manifest and file checksums: {prefix} ({target})")
    manifest = properties(prefix / "build.properties")
    if (properties(prefix / "dependencies.properties") != pins or manifest.get("format") != "1"
            or manifest.get("platform") != target or manifest.get("pins.sha256") != digest(PINS)):
        raise ValueError("bridge support manifest, target or pin mismatch")
    required = ["lib/libgcc_s.so.1", "lib/libstdc++.so.6", "lib/libgcc_s.so", "lib/libstdc++.so", "sources/gcc-16.2.0.tar.gz",
                "sources/zlib-1.3.1.tar.gz", "licenses/GPL-3.0.txt", "licenses/GCC-exception-3.1.txt",
                "recipes/libgcc/info/recipe/meta.yaml", "recipes/libstdcxx/info/recipe/meta.yaml"]
    for name in required:
        if "sha256." + name not in manifest:
            raise ValueError(f"missing bridge support input: {name}")
    for key, value in manifest.items():
        if key.startswith("sha256."):
            relative = Path(key[7:])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("invalid manifest path")
            progress(f"checking SHA-256: {relative}")
            if digest(prefix / relative) != value:
                raise ValueError(f"bridge support checksum mismatch: {relative}")
    for name, filename in [("libgcc", "libgcc_s.so.1"), ("libstdcxx", "libstdc++.so.6")]:
        if digest(prefix / "lib" / filename) != pins[f"{target}.{name}.file.sha256"]:
            raise ValueError("runtime payload differs from pinned upstream binary")
    for name in ("gcc", "zlib"):
        source_name = pins[name + ".source.url"].rsplit("/", 1)[1]
        if digest(prefix / "sources" / source_name) != pins[name + ".source.sha256"]:
            raise ValueError("corresponding source differs from pinned upstream archive")
    return manifest


def setup(prefix, target, cache, pins):
    if prefix.exists():
        raise ValueError(f"refusing to overwrite support SDK: {prefix}")
    progress(f"preparing {target} SDK at {prefix}; download cache: {cache}")
    prefix.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=prefix.parent, prefix=".bridge-support-") as temporary:
        staged = Path(temporary) / "sdk"
        for name in ("lib", "sources", "licenses", "recipes"):
            (staged / name).mkdir(parents=True)
        shutil.copyfile(PINS, staged / "dependencies.properties")
        for name, filename in [("libgcc", "libgcc_s.so.1"), ("libstdcxx", "libstdc++.so.6")]:
            key = f"{target}.{name}."
            archive = download(cache, pins[key + "url"], pins[key + "sha256"])
            progress(f"extracting runtime and build recipes: {archive.name}")
            with zipfile.ZipFile(archive) as package:
                for category in ("pkg-", "info-"):
                    members = [entry for entry in package.namelist() if entry.startswith(category) and entry.endswith(".tar.zst")]
                    if len(members) != 1:
                        raise ValueError("unexpected conda package structure")
                    with tarfile.open(fileobj=io.BytesIO(compression.zstd.decompress(package.read(members[0])))) as source:
                        if category == "pkg-":
                            data = source.extractfile(pins[key + "file"]).read()
                            if hashlib.sha256(data).hexdigest() != pins[key + "file.sha256"]:
                                raise ValueError("runtime binary SHA-256 mismatch")
                            (staged / "lib" / filename).write_bytes(data)
                        else:
                            about = json.load(source.extractfile("info/about.json"))
                            if about["license"] != pins["license"] or about["extra"]["sha"] != pins["recipe.commit"]:
                                raise ValueError("unreviewed dependency license or recipe revision")
                            for member in source:
                                if member.isfile() and (member.name.startswith("info/recipe/") or member.name == "info/about.json"):
                                    relative = Path(member.name)
                                    if relative.is_absolute() or ".." in relative.parts:
                                        raise ValueError("invalid recipe member path")
                                    output = staged / "recipes" / name / relative
                                    output.parent.mkdir(parents=True, exist_ok=True)
                                    output.write_bytes(source.extractfile(member).read())
        (staged / "lib/libstdc++.so").symlink_to("libstdc++.so.6")
        (staged / "lib/libgcc_s.so").symlink_to("libgcc_s.so.1")
        for name in ("gcc", "zlib"):
            archive = download(cache, pins[name + ".source.url"], pins[name + ".source.sha256"])
            progress(f"copying corresponding source: {archive.name}")
            shutil.copyfile(archive, staged / "sources" / archive.name)
        progress("extracting upstream license texts from GCC source")
        licenses = {"gcc-16.2.0/COPYING3": "GPL-3.0.txt", "gcc-16.2.0/COPYING.RUNTIME": "GCC-exception-3.1.txt"}
        with tarfile.open(staged / "sources/gcc-16.2.0.tar.gz", "r|gz") as source:
            for member in source:
                if member.name in licenses:
                    (staged / "licenses" / licenses.pop(member.name)).write_bytes(source.extractfile(member).read())
                    if not licenses:
                        break
        if licenses:
            raise ValueError("upstream license texts missing")
        progress("hashing SDK files and writing manifest")
        manifest = {"format": "1", "platform": target, "pins.sha256": digest(PINS)}
        manifest.update({"sha256." + str(path.relative_to(staged)): digest(path)
                         for path in sorted(staged.rglob("*")) if path.is_file()})
        (staged / "build.properties").write_text("".join(f"{key}={value}\n" for key, value in sorted(manifest.items())))
        check(staged, target, pins)
        progress(f"publishing verified SDK: {prefix}")
        staged.rename(prefix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--setup", action="store_true")
    action.add_argument("--check", action="store_true")
    parser.add_argument("--target", required=True, choices=("linux-arm64", "linux-x86_64"))
    parser.add_argument("--prefix", required=True, type=Path)
    parser.add_argument("--cache", type=Path, default=ROOT / "workspace/java-bridge/downloads")
    args = parser.parse_args()
    pins = properties(PINS)
    if args.setup:
        setup(args.prefix.resolve(), args.target, args.cache.resolve(), pins)
    check(args.prefix.resolve(), args.target, pins)
    print(f"bridge support SDK verified: {args.prefix.resolve()} ({args.target})")


if __name__ == "__main__":
    main()
