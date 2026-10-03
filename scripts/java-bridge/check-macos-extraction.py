#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""D210 private P1 jar: final ad-hoc signing, byte-preserving extraction and pinned launches."""

import argparse
import importlib.util
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_preparation", ROOT / "scripts/prepare-java-bridge.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(PREPARATION)


def run(evidence, name, command, require=True):
    command = list(map(str, command))
    (evidence / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (evidence / (name + ".log")).write_text(result.stdout)
    (evidence / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
    if require and result.returncode != 0:
        raise ValueError(f"{name} failed ({result.returncode}): {result.stdout}")
    return result


def signature(evidence, name, path):
    shown = run(evidence, name + "-display", ["codesign", "--display", "--verbose=4", path]).stdout
    run(evidence, name + "-verify", ["codesign", "--verify", "--strict", "--verbose=2", path])
    if "Signature=adhoc" not in shown or "arm64" not in shown:
        raise ValueError(f"{name}: expected an ARM64 ad-hoc signature")
    return {line.split("=", 1)[0]: line.split("=", 1)[1] for line in shown.splitlines()
            if line.startswith(("Identifier=", "CDHash=", "Signature=", "CodeDirectory ")) and "=" in line}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scalar-evidence", type=Path, required=True)
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("D210 requires macOS ARM64 hardware")
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    jdks = {}
    for major in (21, 22, 23):
        pins_path = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        pins = json.loads(pins_path.read_text())
        jdks[major] = PREPARATION.check_jdk(args.jdk_root / f"temurin-{major}-macos-arm64", "macos-arm64", pins, pins_path)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
        java = jdks[major]["java"]
        run(evidence, f"launcher-{major}-signature", ["codesign", "--display", "--verbose=4", "--entitlements", ":-", java])
        run(evidence, f"launcher-{major}-verify", ["codesign", "--verify", "--strict", "--verbose=2", java])
    run(evidence, "macos", ["sw_vers"])
    run(evidence, "hardware", ["sysctl", "-n", "machdep.cpu.brand_string"])
    revision = run(evidence, "revision", ["git", "-C", ROOT, "rev-parse", "HEAD"]).stdout.strip()
    run(evidence, "working-diff", ["git", "-C", ROOT, "diff", "--binary"])
    classes = evidence / "classes"
    classes.mkdir()
    run(evidence, "javac", [jdks[21]["javac"], "--release", "21", "-d", classes,
                            args.scalar_evidence / "BridgeScalarConsumer.java", Path(__file__).with_name("ExtractedPayloadProbe.java")])
    records = []
    for level in ("O0", "O3"):
        directory = evidence / level
        directory.mkdir()
        source = args.scalar_evidence / f"scalar-{level}.dylib"
        image = directory / "payload.dylib"
        shutil.copyfile(source, image)
        initial = run(directory, "linker-signature-verify", ["codesign", "--verify", "--strict", "--verbose=2", image], require=False)
        signing = "linker supplied final signature"
        if initial.returncode != 0:
            run(directory, "producer-sign", ["codesign", "--force", "--sign", "-", image])
            signing = "explicit producer ad-hoc signing required"
        original_signature = signature(directory, "final", image)
        run(directory, "dependencies", ["otool", "-L", image])
        run(directory, "architecture", ["file", image])
        digest = PREPARATION.digest(image)
        jar = directory / "probe.jar"
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as output:
            for file in sorted(classes.glob("*.class")):
                output.write(file, file.name)
            output.write(image, "native/payload.dylib")
        for major, jdk in jdks.items():
            cell = directory / str(major)
            cell.mkdir()
            extracted = Path(run(cell, "extract", [jdk["java"], "-cp", jar, "ExtractedPayloadProbe", "extract", digest, cell]).stdout.strip())
            if not extracted.is_relative_to(cell) or PREPARATION.digest(extracted) != digest:
                raise ValueError("extracted path or byte identity mismatch")
            if signature(cell, "extracted", extracted) != original_signature:
                raise ValueError("extraction changed the recorded signature")
            run(cell, "attributes", ["ls", "-ldeO@", extracted.parent, extracted])
            run(cell, "extended-attributes", ["xattr", "-l", extracted])
            for checked in (False, True):
                result = run(cell, "checked" if checked else "ordinary", [jdk["java"], *(["-Xcheck:jni"] if checked else []),
                             "-cp", jar, "ExtractedPayloadProbe", "load", digest, extracted])
                if not result.stdout.endswith("caught\nscalar-ok\n"):
                    raise ValueError("scalar/exception/continued-call marker missing")
            records.append({"jdk": major, "optimization": level, "source_sha256": PREPARATION.digest(source),
                            "payload_sha256": digest, "jar_sha256": PREPARATION.digest(jar),
                            "extracted": str(extracted), "signature": original_signature, "signing": signing})
    (evidence / "result.json").write_text(json.dumps({"revision": revision, "records": records,
            "scope": "P1 D210 macOS ARM64 private fixture; not P2/P6 generated-artifact qualification"}, indent=2) + "\n")
    print(f"D210 passed: {evidence} ({len(records)} cells, ordinary and checked launches)")


if __name__ == "__main__":
    main()
