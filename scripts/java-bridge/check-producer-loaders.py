#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""P2 generated producer jars: package identity, binding cleanup and permanent image guards."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_preparation", ROOT / "scripts/prepare-java-bridge.py")
PREPARATION = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(PREPARATION)
SCENARIOS = ["ab-a", "ab-b", "ba-a", "ba-b", "ac-a", "ac-c", "ca-a", "ca-c",
             "disjoint", "mixed", "signature", "anchor", "late", "host", "floor",
             "missing", "corrupt", "build", "unsafe", "existing"]


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


def source(folder, package, name, value):
    path = folder / package / (name + ".iron")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"""// SPDX-License-Identifier: MIT OR Apache-2.0
package {package};

// Isolated loader fixture. The artifact-specific value exposes rebinding.
public final class {name} {{

    private {name}() {{

    }}

    public static int stamp() {{

        return {value};
    }}

    public static int value(int a, int b) {{

        return a + b + {value};
    }}
}}
""")
    return path


def metadata(jar):
    with zipfile.ZipFile(jar) as archive:
        return dict(line.split("=", 1) for line in archive.read("META-INF/ironwood/bridge.properties").decode().splitlines()
                    if line and not line.startswith("#"))


def rewrite_jar(original, destination, replacements):
    # Deliberately inconsistent test artifacts. Their original identity annotation
    # and native image stay fixed so loader preflight must reject the changed class.
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as target:
        for name in source.namelist():
            if name in replacements and replacements[name] is None:
                continue
            target.writestr(name, replacements.get(name, source.read(name)))


FAULT = """
            // Separate test producer only: fail after a partial second binding.
            static int iw_fixture_registrations, iw_fixture_cleanups, iw_fixture_partial;
            JNIEXPORT int iw_fixture_stat(int slot) {
                return slot == 0 ? iw_fixture_registrations : slot == 1 ? iw_fixture_cleanups : iw_fixture_partial;
            }
            static jint iw_fixture_register(JNIEnv *env, jclass type, const JNINativeMethod *methods, jint count) {
                if (++iw_fixture_registrations != 2) return (*env)->RegisterNatives(env, type, methods, count);
                if (count < 2 || (*env)->RegisterNatives(env, type, methods, 1) != 0) return JNI_ERR;
                iw_fixture_partial++;
                jclass error = (*env)->FindClass(env, "java/lang/LinkageError");
                if (error != NULL) { (*env)->ThrowNew(env, error, "injected partial registration"); (*env)->DeleteLocalRef(env, error); }
                return JNI_ERR;
            }
            static jint iw_fixture_unregister(JNIEnv *env, jclass type) {
                jint result = (*env)->UnregisterNatives(env, type);
                if (result == JNI_OK) iw_fixture_cleanups++;
                return result;
            }
"""


def fault_compiler(evidence, compiler, javac):
    copied = evidence / "fault-compiler"
    shutil.copytree(compiler, copied)
    original = ROOT / "compiler/src/main/java/ironwood/compiler/bridge/BridgeBootstrapSources.java"
    text = original.read_text()
    edits = [("(*env)->RegisterNatives(env, validated[index], binding->methods, binding->method_count)",
              "iw_fixture_register(env, validated[index], binding->methods, binding->method_count)"),
             ("(*env)->UnregisterNatives(env, validated[previous]);", "iw_fixture_unregister(env, validated[previous]);"),
             ("            static int iw_bound, iw_ready;", "            static int iw_bound, iw_ready;\n" + FAULT)]
    for before, after in edits:
        if text.count(before) != 1:
            raise ValueError("fault injection no longer matches producer source: " + before)
        text = text.replace(before, after)
    source = evidence / "BridgeBootstrapSources.java"
    source.write_text(text)
    run(evidence, "fault-producer-javac", [javac, "--release", "21", "-Xlint:all", "-Werror", "-cp", copied, "-d", copied, source])
    (evidence / "fault-producer-inputs.json").write_text(json.dumps({"original": PREPARATION.digest(original),
        "instrumented": PREPARATION.digest(source), "edits": edits}, indent=2) + "\n")
    return copied


def build(evidence, compiler, jdks, level):
    directory = evidence / level
    directory.mkdir()
    fixtures = {"a": [("loadshared", "Common", 111), ("loadshared", "OnlyA", 111)],
                "b": [("loadshared", "Common", 222), ("loadother", "OnlyB", 222)],
                "c": [("loadshared", "OnlyC", 333)], "d": [("loaddisjoint", "OnlyD", 444)],
                "fault": [("loadshared", "Common", 111), ("loadshared", "OnlyA", 111)]}
    for name, types in fixtures.items():
        sources = [source(directory / (name + "-source"), *item) for item in types]
        exports = [option for package in sorted({item[0] for item in types}) for option in ("--export", package)]
        selected = evidence / "fault-compiler" if name == "fault" else compiler
        run(directory, name + "-producer", [jdks[21]["java"], "-cp", selected, "ironwood.compiler.Main", "--java-bridge",
            *exports, "--unfreed=off", "-" + level, "-o", directory / (name + ".jar"), *sources])
    with zipfile.ZipFile(directory / "b.jar") as other:
        rewrite_jar(directory / "a.jar", directory / "mixed.jar", {"loadshared/Common.class": other.read("loadshared/Common.class")})
    with zipfile.ZipFile(directory / "a.jar") as original:
        text = original.read("META-INF/ironwood/java-sources/loadshared/OnlyA.java").decode()
    text, changed = re.subn(r"(private static native int \$ironwood\$native\$\d+\()int a, int b", r"\1long a, int b", text)
    if changed != 1:
        raise ValueError("signature fault did not alter exactly one native declaration")
    path = directory / "signature-source/loadshared/OnlyA.java"
    path.parent.mkdir(parents=True)
    path.write_text(text)
    classes = directory / "signature-classes"
    run(directory, "signature-fault-javac", [jdks[21]["javac"], "--release", "21", "-cp", directory / "a.jar", "-d", classes, path])
    rewrite_jar(directory / "a.jar", directory / "signature.jar", {"loadshared/OnlyA.class": (classes / "loadshared/OnlyA.class").read_bytes()})
    manifest = metadata(directory / "a.jar")
    for name in ("host", "floor", "unsafe", "existing"):
        shutil.copyfile(directory / "a.jar", directory / (name + ".jar"))
    rewrite_jar(directory / "a.jar", directory / "missing.jar", {manifest["native.resource"]: None})
    rewrite_jar(directory / "a.jar", directory / "corrupt.jar", {manifest["native.resource"]: b"deliberately corrupt native payload"})
    support = "ironwood/bridge/generated/g" + manifest["generation"] + "/Support"
    with zipfile.ZipFile(directory / "a.jar") as original:
        text = original.read("META-INF/ironwood/java-sources/" + support + ".java").decode()
    before = '{"' + manifest["native.target"] + '", "' + manifest["native.build"] + '",'
    if text.count(before) != 1:
        raise ValueError("build-pairing fault does not match Support")
    text = text.replace(before, '{"' + manifest["native.target"] + '", "' + "0" * 64 + '",')
    path = directory / "build-source" / (support + ".java")
    path.parent.mkdir(parents=True)
    path.write_text(text)
    classes = directory / "build-classes"
    run(directory, "build-fault-javac", [jdks[21]["javac"], "--release", "21", "-cp", directory / "a.jar", "-d", classes, path])
    rewrite_jar(directory / "a.jar", directory / "build.jar", {support + ".class": (classes / (support + ".class")).read_bytes()})
    return directory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classes", type=Path, default=ROOT / "compiler/build/classes")
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--llvm-home", type=Path, default=Path("/opt/homebrew/opt/llvm"))
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--scenario", action="append", choices=SCENARIOS, help="run only selected focused cases")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("P2 producer loader qualification requires macOS ARM64")
    if os.environ.get("IRONWOOD_ALLOCATION_LIMIT"):
        parser.error("unset IRONWOOD_ALLOCATION_LIMIT")
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=False)
    jdks = {}
    for major in (21, 22, 23):
        pins = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        jdks[major] = PREPARATION.check_jdk(args.jdk_root / f"temurin-{major}-macos-arm64", "macos-arm64", json.loads(pins.read_text()), pins)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
    run(evidence, "revision", ["git", "rev-parse", "HEAD"])
    run(evidence, "working-diff", ["git", "diff", "--binary"])
    run(evidence, "macos", ["sw_vers"])
    run(evidence, "hardware", ["sysctl", "-n", "machdep.cpu.brand_string"])
    inspector = evidence / "inspector.dylib"
    java_home = Path(jdks[21]["java"]).parent.parent
    run(evidence, "inspector-clang", [args.llvm_home / "bin/clang", "-std=c11", "-Wall", "-Wextra", "-Werror", "-dynamiclib",
        "-I" + str(java_home / "include"), "-I" + str(java_home / "include/darwin"), Path(__file__).with_name("LoaderImageInspector.c"), "-o", inspector])
    classes = evidence / "consumer-classes"
    run(evidence, "consumer-javac", [jdks[21]["javac"], "--release", "21", "-Xlint:all", "-Werror", "-d", classes,
                                   Path(__file__).with_name("LoaderQualificationConsumer.java")])
    compiler = args.compiler_classes.resolve()
    fault_compiler(evidence, compiler, jdks[21]["javac"])
    scenarios = args.scenario or SCENARIOS
    records = []
    for level in ("O0", "O3"):
        directory = build(evidence, compiler, jdks, level)
        for major, jdk in jdks.items():
            for scenario in scenarios:
                cell = directory / (str(major) + "-" + scenario)
                cell.mkdir()
                temporary = cell / "tmp"
                temporary.mkdir()
                result = run(cell, "launch", [jdk["java"], "-Xcheck:jni", "-Djava.io.tmpdir=" + str(temporary), "-cp", classes,
                    "LoaderQualificationConsumer", directory, scenario, inspector])
                if not result.stdout.endswith("loader-qualified:" + scenario + "\n") or result.stderr or "WARNING" in result.stdout:
                    raise ValueError(f"{level}/{major}/{scenario}: unexpected output: {result.stdout}{result.stderr}")
                for image in temporary.rglob("*.dylib"):
                    generation = next(part for part in image.parts if re.fullmatch("[0-9a-f]{64}", part))
                    if scenario == "existing" and generation == metadata(directory / "existing.jar")["generation"]:
                        # The consumer asserts this intentionally damaged, refused
                        # file remains untouched and unmapped. It is not a payload pass.
                        if PREPARATION.digest(image) == metadata(directory / "existing.jar")["native.sha256"]:
                            raise ValueError("existing-file fault did not damage the bytes")
                        continue
                    jars = [p for p in directory.glob("*.jar") if metadata(p)["generation"] == generation]
                    if not jars or any(metadata(p)["native.sha256"] != PREPARATION.digest(image) for p in jars):
                        raise ValueError("extracted image not paired with a producer jar")
                    run(cell, "signature-" + generation, ["/usr/bin/codesign", "--verify", "--strict", image])
                records.append({"optimization": level, "jdk": major, "scenario": scenario, "exit": result.returncode})
    files = [p for p in evidence.rglob("*") if p.is_file() and p.suffix in (".jar", ".class", ".java", ".iron", ".dylib")]
    files.extend([Path(__file__), Path(__file__).with_name("LoaderQualificationConsumer.java"), Path(__file__).with_name("LoaderImageInspector.c")])
    (evidence / "identities.json").write_text(json.dumps({str(p): PREPARATION.digest(p) for p in sorted(files)}, indent=2) + "\n")
    (evidence / "result.json").write_text(json.dumps({"records": records,
        "scope": "P2 public producer loader qualification; fault compiler and mixed/signature jars are separate negative controls"}, indent=2) + "\n")
    print(f"Producer loader qualification passed: {len(records)} child cases; {evidence}")


if __name__ == "__main__":
    main()
