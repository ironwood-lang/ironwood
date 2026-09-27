#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""D203/D209 macOS experiment: production refusal and separately paired Java 25 admission."""

import argparse
import importlib.util
import json
import os
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


def run(directory, name, command):
    command = list(map(str, command))
    (directory / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=180)
    (directory / (name + ".stdout")).write_text(result.stdout)
    (directory / (name + ".stderr")).write_text(result.stderr)
    (directory / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
    if result.returncode != 0:
        raise ValueError(f"{name} exited {result.returncode}: {result.stdout}{result.stderr}")
    return result


def properties(jar):
    with zipfile.ZipFile(jar) as archive:
        text = archive.read("META-INF/ironwood/bridge.properties").decode("ascii")
    # Read only generated ASCII identity fields, not arbitrary Properties syntax.
    return dict(line.split("=", 1) for line in text.splitlines() if line and not line.startswith("#"))


def experimental_compiler(directory, compiler, javac):
    copied = directory / "experimental-compiler"
    shutil.copytree(compiler, copied)
    replacements = {
        "BridgeLoaderSources.java": [("feature >= 21 && feature <= 23", "feature == 25"),
                                     ("requires Java 21-23; detected", "requires experimental Java 25; detected")],
        "BridgeGeneration.java": [('manifest.put("java.supported", "21,22,23");',
                                    'manifest.put("java.supported", "25-experimental-D209");')]
    }
    sources = []
    changes = {}
    for name, edits in replacements.items():
        original = ROOT / "compiler/src/main/java/ironwood/compiler/bridge" / name
        text = original.read_text()
        for before, after in edits:
            if text.count(before) != 1:
                raise ValueError(f"experiment policy patch no longer matches {name}: {before}")
            text = text.replace(before, after)
        source = directory / name
        source.write_text(text)
        sources.append(source)
        changes[name] = {"original_sha256": PREPARATION.digest(original),
                         "experimental_sha256": PREPARATION.digest(source), "edits": edits}
    run(directory, "experimental-producer-javac", [javac, "--release", "21", "-encoding", "UTF-8",
        "-Xlint:all", "-Werror", "-cp", copied, "-d", copied, *sources])
    (directory / "experimental-policy-changes.json").write_text(json.dumps(changes, indent=2) + "\n")
    return copied


def check_signature(directory, name, image):
    run(directory, name + "-verify", ["/usr/bin/codesign", "--verify", "--strict", image])
    run(directory, name + "-signature", ["/usr/bin/codesign", "--display", "--verbose=4", image])


def launches(directory, jar, manifest, jdks, experimental):
    classes = directory / "consumer-classes"
    run(directory, "consumer-javac", [jdks[21]["javac"], "--release", "21", "-cp", jar, "-d", classes,
                                      Path(__file__).with_name("VersionProbeConsumer.java")])
    mf = directory / "consumer.mf"
    mf.write_text(f"Manifest-Version: 1.0\nMain-Class: VersionProbeConsumer\nClass-Path: {jar.name}\n\n")
    executable = directory / "consumer.jar"
    run(directory, "consumer-jar", [Path(jdks[21]["java"]).with_name("jar"), "--create", "--file", executable,
                                    "--manifest", mf, "-C", classes, "."])
    image = directory / "packaged.dylib"
    with zipfile.ZipFile(jar) as archive:
        image.write_bytes(archive.read(manifest["native.resource"]))
    if PREPARATION.digest(image) != manifest["native.sha256"]:
        raise ValueError("jar/payload digest mismatch")
    check_signature(directory, "packaged", image)
    run(directory, "dependencies", ["/usr/bin/otool", "-L", image])
    run(directory, "deployment", ["/usr/bin/otool", "-l", image])
    records = []
    scenarios = [(25, "normal", []), (25, "normal", ["-Xcheck:jni"]),
                 (25, "deny", ["--illegal-native-access=deny"])] if experimental else [(21, "normal", []), (24, "refuse", []), (25, "refuse", [])]
    for major, mode, flags in scenarios:
        for form in ("class", "module", "executable"):
            name = f"java{major}-{form}-{mode}-" + ("diagnostic" if "-Xcheck:jni" in flags else "default")
            cell = directory / name
            cell.mkdir()
            temporary = cell / "tmp"
            temporary.mkdir()
            command = [jdks[major]["java"], "-Djava.io.tmpdir=" + str(temporary), *flags]
            if form == "class":
                command += ["-cp", str(jar) + os.pathsep + str(classes), "VersionProbeConsumer"]
            elif form == "module":
                command += ["--module-path", jar, "--add-modules", manifest["java.module"], "-cp", classes, "VersionProbeConsumer"]
            else:
                command += ["-jar", executable]
            result = run(cell, "launch", [*command, mode])
            extracted = list(temporary.rglob("*.dylib"))
            if mode == "normal":
                if result.stdout != "version-probe-ok\n":
                    raise ValueError(f"{name}: functional output mismatch: {result.stdout}")
                if major == 21 and result.stderr:
                    raise ValueError(f"{name}: unexpected supported-JVM warning")
                if major == 25 and "WARNING: A restricted method in java.lang.System has been called" not in result.stderr:
                    raise ValueError(f"{name}: expected default native-access warning absent")
                if "WARNING in native method" in result.stdout + result.stderr or "FATAL ERROR" in result.stdout + result.stderr:
                    raise ValueError(f"{name}: JNI diagnostic failure")
            elif (result.stdout.count("refused:" if mode == "refuse" else "denied:") != 1
                  or result.stdout.count("repeated-facade-init-failure") != 1):
                raise ValueError(f"{name}: target ran or repeated rejection failed")
            if mode == "deny" and "denied-image-not-mapped\n" not in result.stdout:
                raise ValueError(f"{name}: denied native image mapping was not checked")
            if mode == "refuse":
                if list(temporary.iterdir()) or result.stderr:
                    raise ValueError(f"{name}: refusal extracted files or emitted native-access warnings")
            else:
                if len(extracted) != 1 or PREPARATION.digest(extracted[0]) != manifest["native.sha256"]:
                    raise ValueError(f"{name}: extraction identity mismatch")
                check_signature(cell, "extracted", extracted[0])
                run(cell, "attributes", ["/bin/ls", "-ldeO@", extracted[0].parent, extracted[0]])
                run(cell, "extended-attributes", ["/usr/bin/xattr", "-l", extracted[0]])
            records.append({"jdk": major, "form": form, "mode": mode, "flags": flags,
                            "stdout": result.stdout, "stderr": result.stderr, "exit": result.returncode,
                            "jar_sha256": PREPARATION.digest(jar), "payload_sha256": manifest["native.sha256"]})
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classes", type=Path, default=ROOT / "compiler/build/classes")
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("D209 requires macOS ARM64 hardware")
    if os.environ.get("IRONWOOD_ALLOCATION_LIMIT"):
        parser.error("unset IRONWOOD_ALLOCATION_LIMIT for the version experiment")
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    jdks = {}
    for major in (21, 24, 25):
        pins_path = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        jdks[major] = PREPARATION.check_jdk(args.jdk_root / f"temurin-{major}-macos-arm64", "macos-arm64", json.loads(pins_path.read_text()), pins_path)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
        run(evidence, f"launcher-{major}", ["/usr/bin/codesign", "--display", "--verbose=4", "--entitlements", ":-", jdks[major]["java"]])
        run(evidence, f"launcher-{major}-verify", ["/usr/bin/codesign", "--verify", "--strict", jdks[major]["java"]])
    run(evidence, "macos", ["sw_vers"])
    run(evidence, "hardware", ["sysctl", "-n", "machdep.cpu.brand_string"])
    run(evidence, "revision", ["git", "rev-parse", "HEAD"])
    run(evidence, "working-diff", ["git", "diff", "--binary"])
    compiler = args.compiler_classes.resolve()
    experimental = experimental_compiler(evidence, compiler, jdks[21]["javac"])
    records = []
    for level in ("O0", "O3"):
        identities = []
        for probe, selected in ((False, compiler), (True, experimental)):
            directory = evidence / (level + ("-experimental" if probe else "-ordinary"))
            directory.mkdir()
            jar = directory / "version-probe.jar"
            run(directory, "producer", [jdks[21]["java"], "-cp", selected, "ironwood.compiler.Main",
                "--java-bridge", "--export", "versionprobe", "--unfreed=off", "-" + level,
                "-o", jar, Path(__file__).with_name("VersionProbe.iron")])
            manifest = properties(jar)
            if manifest["java.supported"] != ("25-experimental-D209" if probe else "21,22,23"):
                raise ValueError("incorrect producer policy identity")
            identities.append(manifest)
            records.extend(launches(directory, jar, manifest, jdks, probe))
        for key in ("program", "api", "runtime.sha256"):
            if identities[0][key] != identities[1][key]:
                raise ValueError("experimental policy unexpectedly changed " + key)
        for key in ("generation", "compiler.sha256", "native.build"):
            if identities[0][key] == identities[1][key]:
                raise ValueError("experiment reused ordinary " + key)
    files = [p for p in evidence.rglob("*") if p.is_file() and p.suffix in (".jar", ".class", ".java", ".dylib")]
    files.extend([Path(__file__), Path(__file__).with_name("VersionProbe.iron"), Path(__file__).with_name("VersionProbeConsumer.java")])
    (evidence / "identities.json").write_text(json.dumps({str(p): PREPARATION.digest(p) for p in sorted(files)}, indent=2) + "\n")
    (evidence / "result.json").write_text(json.dumps({"records": records,
        "scope": "D203/D209 value experiment only; Java 21-23 support unchanged; not P6 qualification"}, indent=2) + "\n")
    print(f"D203/D209 completed: {len(records)} child launches; {evidence}")


if __name__ == "__main__":
    main()
