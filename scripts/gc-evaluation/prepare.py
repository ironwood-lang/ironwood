#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Prepare isolated macOS arm64 collector experiments with pinned inputs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request

SOURCE = Path(__file__).resolve().parent
MMTK = "d154f4f0dcca27439b920165ce5c948ca18aef24"


def download(url, target, expected):
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise RuntimeError(f"checksum mismatch for {target.name}: {actual}")
    target.write_bytes(data)
    return {"url": url, "sha256": actual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--llvm-bin", type=Path, default=Path("/opt/homebrew/opt/llvm@23/bin"))
    parser.add_argument("--rust-tools", type=Path,
                        help="Reuse a task-local directory containing cargo/ and rustup/")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise SystemExit("This qualification fixture is validated only on macOS arm64.")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    (out / "sources").mkdir()
    (out / "evidence").mkdir()
    for source in SOURCE.rglob("*"):
        if source.is_file() and (source.suffix in {".c", ".ll", ".java", ".py", ".rs", ".toml", ".lock"}
                                 or source.name == "COPYRIGHT"):
            target = out / source.relative_to(SOURCE)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    inputs = []
    for name, url, expected in [
        ("gc-8.2.12.tar.gz", "https://github.com/bdwgc/bdwgc/releases/download/v8.2.12/gc-8.2.12.tar.gz",
         "42e5194ad06ab6ffb806c83eb99c03462b495d979cda782f3c72c08af833cd4e"),
        ("mmtk-core.tar.gz", "https://codeload.github.com/mmtk/mmtk-core/tar.gz/" + MMTK,
         "2a296a7bce60f737de35f8703d9705c4383b09d6f2783cdf3ac420ed26806b2e"),
    ]:
        archive = out / name
        inputs.append(download(url, archive, expected))
        with tarfile.open(archive) as package:
            package.extractall(out / "sources", filter="data")
    (out / "evidence/inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
    common = ["--disable-shared", "--enable-static", "--disable-threads",
              "--disable-parallel-mark", "--disable-thread-local-alloc", "--disable-docs",
              "--disable-gcj-support", "--disable-java-finalization", "--disable-disclaim",
              "--disable-throw-bad-alloc-library", "--with-libatomic-ops=none"]
    for name in ["eager", "lazy", "compiler-roots-large"]:
        build = out / ("build-" + name)
        build.mkdir()
        flags = "-O3 -DGC_DISABLE_INCREMENTAL"
        options = list(common)
        if name != "lazy":
            flags += " -DEAGER_SWEEP"
        if name == "compiler-roots-large":
            flags += " -DSTACK_NOT_SCANNED -DDONT_ADD_BYTE_AT_END"
            options += ["--disable-register-main-static-data", "--disable-dynamic-loading",
                        "--enable-large-config"]
        command = [str(out / "sources/gc-8.2.12/configure"), *options,
                   "CC=" + str(args.llvm_bin / "clang"), "CFLAGS=" + flags]
        with (out / ("evidence/build-" + name + ".log")).open("w") as log:
            subprocess.run(command, cwd=build, stdout=log, stderr=subprocess.STDOUT, check=True)
            subprocess.run(["make", "-j4"], cwd=build, stdout=log, stderr=subprocess.STDOUT, check=True)
        print("built BDWGC", name, flush=True)
    tools = args.rust_tools.resolve() if args.rust_tools else out / "tools"
    env = os.environ.copy()
    env.update(CARGO_HOME=str(tools / "cargo"), RUSTUP_HOME=str(tools / "rustup"))
    if not (tools / "cargo/bin/cargo").exists():
        with urllib.request.urlopen("https://sh.rustup.rs", timeout=60) as response:
            installer = response.read()
        (out / "rustup-init.sh").write_bytes(installer)
        subprocess.run(["sh", str(out / "rustup-init.sh"), "-y", "--profile", "minimal",
                        "--no-modify-path", "--default-toolchain", "1.99.1"], env=env, check=True)
    env["PATH"] = str(tools / "cargo/bin") + os.pathsep + env["PATH"]
    with (out / "evidence/build-mmtk.log").open("w") as log:
        subprocess.run([str(tools / "cargo/bin/cargo"), "build", "--release", "--locked", "-j4"],
                       cwd=out / "mmtk-binding", env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    cc = [str(args.llvm_bin / "clang"), "-O3", "-std=c11", "-D_POSIX_C_SOURCE=200809L",
          "-Wall", "-Wextra", "-Werror", "-I" + str(out / "sources/gc-8.2.12/include")]
    for name in ["eager", "lazy"]:
        subprocess.run([*cc, str(out / "probe.c"), str(out / ("build-" + name + "/.libs/libgc.a")),
                        "-o", str(out / ("probe-" + name))], check=True)
    lib = out / "mmtk-binding/target/release"
    linking = ["-L" + str(lib), "-lironwood_gc_probe", "-Wl,-rpath," + str(lib)]
    subprocess.run([*cc, str(out / "mmtk_probe.c"), *linking, "-o", str(out / "mmtk-probe")], check=True)
    subprocess.run([*cc, str(out / "allocation_probe.c"), str(out / "build-eager/.libs/libgc.a"),
                    *linking, "-o", str(out / "allocation-probe")], check=True)
    subprocess.run(["javac", str(out / "PoolBench.java")], check=True)
    print("prepared", out, flush=True)


if __name__ == "__main__":
    main()
