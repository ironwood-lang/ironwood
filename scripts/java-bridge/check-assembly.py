#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build matched local host jars, assemble them, and check all three launch forms."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import re
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_preparation", ROOT / "scripts/prepare-java-bridge.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(PREPARATION)


def run(directory, name, command, expected=None, timeout=300):
    command = list(map(str, command))
    (directory / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
    (directory / (name + ".log")).write_text(result.stdout)
    (directory / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
    if result.returncode or (expected is not None and result.stdout != expected):
        raise ValueError(f"{name} failed ({result.returncode}): {result.stdout}")
    return result.stdout


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def metadata(jar):
    with zipfile.ZipFile(jar) as archive:
        text = archive.read("META-INF/ironwood/bridge.properties").decode("ascii")
    decode = lambda text: re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), text)
    return {decode(key): decode(value) for line in text.splitlines() if line and not line.startswith("#")
            for key, value in [line.split("=", 1)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--source", type=Path)
    inputs.add_argument("--class-path", type=Path, help="dedicated compiled engine directory or archive")
    parser.add_argument("--export", dest="exports", action="append", required=True)
    parser.add_argument("--consumer", type=Path, required=True)
    parser.add_argument("--main", required=True)
    parser.add_argument("--expected", required=True, help="exact consumer stdout without its final newline")
    parser.add_argument("--optimization", choices=("O0", "O3"), default="O3")
    parser.add_argument("--java-option", action="append", default=[], help="extra consumer option; use --java-option=-XX:...")
    parser.add_argument("--artifact", default="bridge.jar")
    parser.add_argument("--java-home", type=Path, default=ROOT / "workspace/java-bridge/jdks/temurin-21-macos-arm64/jdk-21.0.12.1+1/Contents/Home")
    parser.add_argument("--compiler-classes", type=Path, default=ROOT / "compiler/build/classes")
    parser.add_argument("--docker-context", required=True)
    parser.add_argument("--linux-arm-image", required=True)
    parser.add_argument("--linux-x86-image", required=True)
    parser.add_argument("--minimal-arm-image", required=True)
    parser.add_argument("--minimal-x86-image", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("this local orchestration runner requires the ARM64 Mac; x86-64 is explicitly translated")
    if Path(args.artifact).name != args.artifact or not args.artifact.endswith(".jar"):
        parser.error("artifact must be a simple jar filename")
    directory = args.evidence.resolve(); directory.mkdir(parents=True, exist_ok=False)
    relative = lambda path: "/work/" + str(path.resolve().relative_to(ROOT))
    java = args.java_home.resolve() / "bin/java"
    checked_jdk = PREPARATION.check_jdk(args.java_home.resolve().parents[2], "macos-arm64",
                                      json.loads(PREPARATION.PINS.read_text()), PREPARATION.PINS)
    if Path(checked_jdk["java"]).resolve() != java.resolve():
        raise ValueError("selected producer is not the checked pinned JDK")
    (directory / "pinned-jdk.json").write_text(json.dumps(checked_jdk, indent=2) + "\n")
    compiler = args.compiler_classes.resolve(); source = (args.source or args.class_path).resolve()
    docker = ["docker", "--context", args.docker_context]
    run(directory, "revision", ["git", "rev-parse", "HEAD"])
    run(directory, "working-diff", ["git", "diff", "--binary"])
    run(directory, "hardware", ["sysctl", "-n", "machdep.cpu.brand_string"])
    run(directory, "os", ["sw_vers"])
    run(directory, "jdk", [java, "-XshowSettings:properties", "-version"])
    input_files = [source] if source.is_file() else sorted(path for path in source.rglob("*") if path.is_file())
    if not input_files: raise ValueError("empty producer input inventory")
    records = {"scope": {"macos-arm64": "ARM64 hardware", "linux-arm64": "ARM64 virtualization", "linux-x86_64": "x86-64 Rosetta translation"},
               "runner.sha256": digest(Path(__file__)),
               "inputs": {str(path): digest(path) for path in input_files}, "optimization": args.optimization,
               "consumer": {"path": str(args.consumer.resolve()), "sha256": digest(args.consumer)}}
    exports = [part for package in args.exports for part in ("--export", package)]
    hosts = []
    for target, image, arch in [("macos-arm64", None, None), ("linux-arm64", args.linux_arm_image, "arm64"),
                                ("linux-x86_64", args.linux_x86_image, "amd64")]:
        folder = directory / target; folder.mkdir(); jar = folder / args.artifact; hosts.append(jar)
        if image is None:
            command = [java, "-cp", compiler, "ironwood.compiler.Main", "--java-bridge", *exports, "--unfreed=off", "-" + args.optimization, "-o", jar,
                       *([source] if args.source else ["-cp", source])]
        else:
            run(folder, "image", [*docker, "image", "inspect", image])
            run(folder, "pinned-jdk", [*docker, "run", "--rm", "--platform", "linux/" + arch, "-v", str(ROOT) + ":/work", image,
                "python", "/opt/ironwood-bridge-preparation/scripts/prepare-java-bridge.py", "--check", "--target", target,
                "--prefix", "/opt/ironwood-bridge-jdk", "--evidence", relative(folder / "pinned-jdk.json")])
            command = [*docker, "run", "--rm", "--platform", "linux/" + arch, "-v", str(ROOT) + ":/work", "-w", "/work",
                       "-e", "IRONWOOD_BRIDGE_SUPPORT_HOME=/work/workspace/java-bridge/support/" + target,
                       image, "java", "-cp", relative(compiler), "ironwood.compiler.Main", "--java-bridge", *exports,
                       "--unfreed=off", "-" + args.optimization, "-o", relative(jar),
                       *([relative(source)] if args.source else ["-cp", relative(source)])]
        run(folder, "produce", command)
        records[target] = {"jar.sha256": digest(jar), "manifest": metadata(jar)}
    combined = directory / "combined"; combined.mkdir(); jar = combined / args.artifact
    run(combined, "assemble", [java, "-cp", compiler, "ironwood.compiler.Main", "--java-bridge-assemble", "-o", jar, *hosts])
    first = digest(jar)
    run(combined, "assemble-reversed", [java, "-cp", compiler, "ironwood.compiler.Main", "--java-bridge-assemble", "-o", jar, *reversed(hosts)])
    if digest(jar) != first:
        raise ValueError("input-order change produced a different assembled jar")
    manifest = metadata(jar); records["assembled"] = {"jar.sha256": first, "manifest": manifest}
    classes = combined / "consumer-classes"
    run(combined, "javac", [args.java_home / "bin/javac", "--release", "21", "-Xlint:all", "-Werror", "-cp", jar, "-d", classes, args.consumer.resolve()])
    executable = combined / "consumer.jar"; main_manifest = combined / "consumer.mf"
    main_manifest.write_text("Manifest-Version: 1.0\nMain-Class: " + args.main + "\nClass-Path: " + args.artifact + "\n\n")
    run(combined, "consumer-jar", [args.java_home / "bin/jar", "--create", "--file", executable, "--manifest", main_manifest, "-C", classes, "."])
    for target, image, arch in [("macos-arm64", None, None), ("linux-arm64", args.minimal_arm_image, "arm64"),
                                ("linux-x86_64", args.minimal_x86_image, "amd64")]:
        if image:
            run(combined, target + "-image", [*docker, "image", "inspect", image])
        prefix = [java] if image is None else [*docker, "run", "--rm", "--platform", "linux/" + arch, "-v", str(combined) + ":/payload:ro", image]
        location = combined if image is None else Path("/payload")
        for checked in (False, True):
            for form in ("class", "module", "executable"):
                if form == "class":
                    launch = ["-cp", str(location / args.artifact) + ":" + str(location / "consumer-classes"), args.main]
                elif form == "module":
                    launch = ["--module-path", location / args.artifact, "--add-modules", manifest["java.module"], "-cp", location / "consumer-classes", args.main]
                else:
                    launch = ["-jar", location / "consumer.jar"]
                run(combined, target + "-" + form + "-" + str(checked), [*prefix, *(["-Xcheck:jni"] if checked else []), *args.java_option, *launch], args.expected + "\n")
    (directory / "evidence.json").write_text(json.dumps(records, indent=2, sort_keys=True) + "\n")
    print("Matched assembly passed 18 launches; x86-64 evidence is translated: " + str(directory))


if __name__ == "__main__":
    main()
