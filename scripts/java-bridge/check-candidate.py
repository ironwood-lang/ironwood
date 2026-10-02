#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Qualify fixed assembled candidates on one explicitly identified host target."""

import argparse
import hashlib
import importlib.util
import json
import os
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
CASES = {
    "values-O3": ("ironwood-values.jar", "org.ironwood.javabridge.consumer.Main",
                  "42\ncopied: bridge\ncaught: example failure\ncontinued: 42\n", []),
    "roots-O3": ("mixed.jar", "Consumer", "object-producer-ok\n", []),
    "orderbook-O3": ("orderbook.jar", "OrderBookConsumer",
                     "orderbook-paired-ok 30000 2500000 10300000000 59998\norderbook-production-warm-bytes=0\n",
                     ["-XX:-DoEscapeAnalysis"]),
    "version-O0": ("version-probe.jar", "VersionProbeConsumer", "version-probe-ok\n", []),
    "version-O3": ("version-probe.jar", "VersionProbeConsumer", "version-probe-ok\n", []),
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def properties(data):
    decode = lambda value: re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), value)
    return {decode(key): decode(value) for line in data.decode("ascii").splitlines()
            if line and not line.startswith("#") for key, value in [line.split("=", 1)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--target", choices=("macos-arm64", "linux-arm64", "linux-x86_64"), required=True)
    parser.add_argument("--execution-scope", choices=("ARM64 hardware", "ARM64 virtualization",
                        "x86-64 Rosetta translation", "x86-64 physical hardware"), required=True)
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--java21-prefix", type=Path)
    parser.add_argument("--llvm-home", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--audit-only", action="store_true", help="inspect packaged payloads without qualifying a JVM cell")
    args = parser.parse_args()
    mac = args.target == "macos-arm64"
    expected_arches = ("arm64", "aarch64") if args.target.endswith("arm64") else ("x86_64", "amd64")
    if platform.machine() not in expected_arches or platform.system() != ("Darwin" if mac else "Linux"):
        parser.error("run on the selected architecture and OS; translated execution must be labeled separately")
    if args.target.endswith("x86_64") != args.execution_scope.startswith("x86-64"):
        parser.error("execution scope does not match target")
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT"):
        if os.environ.get(name): parser.error("unset " + name)
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=False)
    candidate = args.candidate.resolve()

    def run(folder, name, command, expected=None):
        command = list(map(str, command))
        (folder / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=180)
        (folder / (name + ".stdout")).write_text(result.stdout)
        (folder / (name + ".stderr")).write_text(result.stderr)
        (folder / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if result.returncode or (expected is not None and (result.stdout != expected or result.stderr)):
            raise ValueError(f"{name}: {result.returncode}: {result.stdout}{result.stderr}")
        return result

    run(evidence, "revision", ["git", "rev-parse", "HEAD"])
    run(evidence, "working-diff", ["git", "diff", "--binary"])
    run(evidence, "os", ["sw_vers"] if mac else ["uname", "-a"])
    run(evidence, "cpu", ["sysctl", "-n", "machdep.cpu.brand_string"] if mac else ["lscpu"])
    if not mac: run(evidence, "libc", ["ldd", "--version"])
    jdks = {}
    if not args.audit_only:
        for major in (21, 22, 23, *([24, 25] if mac else [])):
            pins = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
            prefix = args.java21_prefix if major == 21 and args.java21_prefix else args.jdk_root / f"temurin-{major}-{args.target}"
            jdks[major] = PREPARATION.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
            (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
    records = []
    identities = {}
    for case, (filename, main_class, expected, flags) in CASES.items():
        original = json.loads((candidate / case / "evidence.json").read_text())
        combined = candidate / case / "combined"; jar = combined / filename
        if digest(jar) != original["assembled"]["jar.sha256"]:
            raise ValueError("changed candidate jar: " + str(jar))
        folder = evidence / case; folder.mkdir()
        with zipfile.ZipFile(jar) as archive:
            manifest = properties(archive.read("META-INF/ironwood/bridge.properties"))
            if manifest != original["assembled"]["manifest"]: raise ValueError("candidate manifest changed")
            prefix = "target." + args.target + "."
            resource = manifest[prefix + "resource"]
            payload = folder / Path(resource).name; payload.write_bytes(archive.read(resource))
            if digest(payload) != manifest[prefix + "sha256"]: raise ValueError("payload hash mismatch")
            identities[case] = {"jar": digest(jar), "generation": manifest["generation"],
                                "native.build": manifest[prefix + "build"], "native.sha256": digest(payload),
                                "compiler": manifest["compiler.sha256"], "runtime": manifest["runtime.sha256"]}
            for name in archive.namelist():
                if name.startswith(str(Path(resource).parent) + "/") and "/lib/" in name:
                    relative = Path(name).relative_to(Path(resource).parent)
                    destination = folder / relative; destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(name))
                    if digest(destination) != manifest["content.sha256." + name]: raise ValueError("dependency hash mismatch")
        if mac:
            run(folder, "signature", ["codesign", "--verify", "--strict", payload])
            shown = run(folder, "signature-display", ["codesign", "--display", "--verbose=4", payload])
            if "Signature=adhoc" not in shown.stderr: raise ValueError("missing ad-hoc signature")
            names = run(folder, "install-name", ["otool", "-D", payload]).stdout
            if names.splitlines()[1:] != ["@rpath/libbridge.dylib"]: raise ValueError("unstable install name")
            run(folder, "dependencies", ["otool", "-L", payload])
        else:
            dynamic = run(folder, "dynamic", [args.llvm_home / "bin/llvm-readelf", "--dynamic", "--version-info", payload]).stdout
            if "BIND_NOW" not in dynamic and not re.search(r"FLAGS_1.*NOW", dynamic): raise ValueError("missing eager binding")
            support = manifest[prefix + "linux.support"]
            if "$ORIGIN/" + support + "/lib" not in dynamic: raise ValueError("missing private dependency path")
            run(folder, "dependency-closure", ["ldd", payload])
            if "not found" in (folder / "dependency-closure.stdout").read_text(): raise ValueError("missing dependency")
        run(folder, "disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", payload])
        if args.audit_only: continue
        for major in (21, 22, 23):
            for checked in (False, True):
                for form in ("class", "module", "executable"):
                    cell = folder / f"java{major}-{form}-{checked}"; cell.mkdir(); temporary = cell / "tmp"; temporary.mkdir()
                    command = [jdks[major]["java"], "-Djava.io.tmpdir=" + str(temporary),
                               *(["-Xcheck:jni"] if checked else []), *flags]
                    if form == "class": command += ["-cp", str(jar) + os.pathsep + str(combined / "consumer-classes"), main_class]
                    elif form == "module": command += ["--module-path", jar, "--add-modules", manifest["java.module"], "-cp", combined / "consumer-classes", main_class]
                    else: command += ["-jar", combined / "consumer.jar"]
                    run(cell, "launch", command, expected)
                    extracted = list(temporary.rglob(payload.name))
                    if len(extracted) != 1 or digest(extracted[0]) != digest(payload): raise ValueError("extracted image changed")
                    if mac: run(cell, "extracted-signature", ["codesign", "--verify", "--strict", extracted[0]])
                    records.append({"case": case, "jdk": major, "form": form, "checked": checked, "exit": 0})
        if mac and case.startswith("version-"):
            # D245: pinned Java 24/25 admit the artifact under each launch form's native-access grant
            # without the JEP 472 warning, and explicit denial fails cleanly before native use.
            for major in (24, 25):
                for form in ("class", "module"):
                    cell = folder / f"java{major}-admission-{form}"; cell.mkdir(); temporary = cell / "tmp"; temporary.mkdir()
                    grant = "--enable-native-access=" + ("ALL-UNNAMED" if form == "class" else manifest["java.module"])
                    launch = ["-cp", str(jar) + os.pathsep + str(combined / "consumer-classes")] if form == "class" else ["--module-path", jar, "--add-modules", manifest["java.module"], "-cp", combined / "consumer-classes"]
                    run(cell, "launch", [jdks[major]["java"], "-Djava.io.tmpdir=" + str(temporary), grant, *launch, main_class], expected)
                    extracted = list(temporary.rglob(payload.name))
                    if len(extracted) != 1 or digest(extracted[0]) != digest(payload): raise ValueError("extracted image changed")
                    records.append({"case": case, "jdk": major, "form": form, "scope": "D245 admission with native-access grant", "exit": 0})
                cell = folder / f"java{major}-deny"; cell.mkdir(); temporary = cell / "tmp"; temporary.mkdir()
                result = run(cell, "launch", [jdks[major]["java"], "-Djava.io.tmpdir=" + str(temporary), "--illegal-native-access=deny",
                                              "-cp", str(jar) + os.pathsep + str(combined / "consumer-classes"), main_class, "deny"])
                if result.stdout.count("denied:") != 1 or "denied-image-not-mapped\n" not in result.stdout:
                    raise ValueError(f"Java {major} denial did not fail cleanly before native use")
                records.append({"case": case, "jdk": major, "form": "class", "scope": "explicit native-access denial", "exit": 0})
    if len({(item["compiler"], item["runtime"]) for item in identities.values()}) != 1:
        raise ValueError("mixed production compiler/runtime candidates")
    result = {"target": args.target, "execution_scope": args.execution_scope, "audit_only": args.audit_only,
              "runner.sha256": digest(Path(__file__)), "identities": identities, "records": records,
              "scope": "fixed assembled payload checks only; fault, stack and performance qualification are separate; scope is operator-declared"}
    (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"PASS: candidate audit and {len(records)} launches on {args.target} ({args.execution_scope})")


if __name__ == "__main__": main()
