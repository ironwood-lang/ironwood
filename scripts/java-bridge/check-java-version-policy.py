#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""D245 macOS check: the ordinary producer's artifact on pinned Java 24/25 under each JEP 472 launch policy, with Java 21 as the control."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_preparation", ROOT / "scripts/prepare-java-bridge.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(PREPARATION)
WARNING = "WARNING: A restricted method in java.lang.System has been called"
# (JDK, consumer mode, launch policy): the policy selects the native-access option per launch form.
SCENARIOS = [(21, "normal", "default"),
             (24, "normal", "default"), (24, "normal", "checked"), (24, "normal", "granted"), (24, "deny", "deny"),
             (25, "normal", "default"), (25, "normal", "checked"), (25, "normal", "granted"), (25, "deny", "deny")]


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


def check_signature(directory, name, image):
    run(directory, name + "-verify", ["/usr/bin/codesign", "--verify", "--strict", image])
    run(directory, name + "-signature", ["/usr/bin/codesign", "--display", "--verbose=4", image])


def executable_jar(directory, name, jar, classes, jdk, granted):
    manifest = directory / (name + ".mf")
    attributes = "Manifest-Version: 1.0\n" + ("Enable-Native-Access: ALL-UNNAMED\n" if granted else "")
    manifest.write_text(attributes + f"Main-Class: VersionProbeConsumer\nClass-Path: {jar.name}\n\n")
    executable = directory / (name + ".jar")
    run(directory, name + "-jar", [Path(jdk["java"]).with_name("jar"), "--create", "--file", executable,
                                   "--manifest", manifest, "-C", classes, "."])
    return executable


def launches(directory, jar, manifest, jdks):
    classes = directory / "consumer-classes"
    run(directory, "consumer-javac", [jdks[21]["javac"], "--release", "21", "-cp", jar, "-d", classes,
                                      Path(__file__).with_name("VersionProbeConsumer.java")])
    executables = {False: executable_jar(directory, "consumer", jar, classes, jdks[21], False),
                   True: executable_jar(directory, "consumer-native-access", jar, classes, jdks[21], True)}
    image = directory / "packaged.dylib"
    with zipfile.ZipFile(jar) as archive:
        image.write_bytes(archive.read(manifest["native.resource"]))
    if PREPARATION.digest(image) != manifest["native.sha256"]:
        raise ValueError("jar/payload digest mismatch")
    check_signature(directory, "packaged", image)
    run(directory, "dependencies", ["/usr/bin/otool", "-L", image])
    run(directory, "deployment", ["/usr/bin/otool", "-l", image])
    records = []
    for major, mode, policy in SCENARIOS:
        for form in ("class", "module", "executable"):
            name = f"java{major}-{form}-{policy}"
            cell = directory / name
            cell.mkdir()
            temporary = cell / "tmp"
            temporary.mkdir()
            flags = {"default": [], "checked": ["-Xcheck:jni"], "deny": ["--illegal-native-access=deny"],
                     "granted": {"class": ["--enable-native-access=ALL-UNNAMED"],
                                 "module": ["--enable-native-access=" + manifest["java.module"]],
                                 "executable": []}[form]}[policy]
            command = [jdks[major]["java"], "-Djava.io.tmpdir=" + str(temporary), *flags]
            if form == "class":
                command += ["-cp", str(jar) + os.pathsep + str(classes), "VersionProbeConsumer"]
            elif form == "module":
                command += ["--module-path", jar, "--add-modules", manifest["java.module"], "-cp", classes, "VersionProbeConsumer"]
            else:
                # The manifest attribute is the executable jar's only grant form.
                command += ["-jar", executables[policy == "granted"]]
            result = run(cell, "launch", [*command, mode])
            output = result.stdout + result.stderr
            extracted = list(temporary.rglob("*.dylib"))
            if "WARNING in native method" in output or "FATAL ERROR" in output:
                raise ValueError(f"{name}: JNI diagnostic failure")
            if mode == "normal":
                if result.stdout != "version-probe-ok\n":
                    raise ValueError(f"{name}: functional output mismatch: {result.stdout}")
                if policy in ("default", "checked") and major >= 24:
                    if result.stderr.count(WARNING) != 1 or "--enable-native-access=" not in result.stderr:
                        raise ValueError(f"{name}: expected exactly one JEP 472 warning: {result.stderr}")
                elif result.stderr:
                    raise ValueError(f"{name}: unexpected output on a silent launch: {result.stderr}")
            elif result.stdout.count("denied:") != 1 or result.stdout.count("repeated-facade-init-failure") != 1 \
                    or "denied-image-not-mapped\n" not in result.stdout:
                raise ValueError(f"{name}: denial ran the target or repeated rejection failed")
            if len(extracted) != 1 or PREPARATION.digest(extracted[0]) != manifest["native.sha256"]:
                raise ValueError(f"{name}: extraction identity mismatch")
            check_signature(cell, "extracted", extracted[0])
            run(cell, "attributes", ["/bin/ls", "-ldeO@", extracted[0].parent, extracted[0]])
            run(cell, "extended-attributes", ["/usr/bin/xattr", "-l", extracted[0]])
            records.append({"jdk": major, "form": form, "mode": mode, "policy": policy, "flags": flags,
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
        parser.error("this check requires macOS ARM64 hardware with the pinned launchers")
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT"):
        if os.environ.get(name):
            parser.error("unset " + name + " for the version policy check")
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
    records = []
    identities = {}
    for level in ("O0", "O3"):
        directory = evidence / level
        directory.mkdir()
        jar = directory / "version-probe.jar"
        run(directory, "producer", [jdks[21]["java"], "-cp", compiler, "ironwood.compiler.Main",
            "--java-bridge", "--export", "versionprobe", "--unfreed=off", "-" + level,
            "-o", jar, Path(__file__).with_name("VersionProbe.iron")])
        manifest = properties(jar)
        if manifest["java.supported"] != "21,22,23,24,25":
            raise ValueError("incorrect producer policy identity")
        identities[level] = manifest
        records.extend(launches(directory, jar, manifest, jdks))
    files = [p for p in evidence.rglob("*") if p.is_file() and p.suffix in (".jar", ".class", ".java", ".dylib")]
    files.extend([Path(__file__), Path(__file__).with_name("VersionProbe.iron"), Path(__file__).with_name("VersionProbeConsumer.java")])
    (evidence / "identities.json").write_text(json.dumps({str(p): PREPARATION.digest(p) for p in sorted(files)}, indent=2) + "\n")
    (evidence / "result.json").write_text(json.dumps({"records": records, "generations": identities,
        "scope": "D245 Java 24/25 launch-policy check on macOS ARM64 pinned Temurin; not a complete release qualification"}, indent=2) + "\n")
    print(f"D245 version policy check completed: {len(records)} child launches; {evidence}")


if __name__ == "__main__":
    main()
