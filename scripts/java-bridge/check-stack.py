#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Final public-producer bounded stack checks and separate disposable limit diagnostics."""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bridge_candidate", Path(__file__).with_name("check-candidate.py"))
CANDIDATE = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(CANDIDATE)
PREPARATION = CANDIDATE.PREPARATION


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", type=Path, default=ROOT / "compiler/build/ironwoodc.jar")
    parser.add_argument("--candidate", type=Path)
    parser.add_argument("--callbacks", action="store_true", help="qualify P5 alternating Java/native callback frames")
    parser.add_argument("--revision-file", type=Path, help="archived checkout identity when Git metadata is absent")
    parser.add_argument("--target", choices=("macos-arm64", "linux-arm64", "linux-x86_64"), required=True)
    parser.add_argument("--execution-scope", choices=("ARM64 hardware", "ARM64 virtualization", "x86-64 physical hardware"), required=True)
    parser.add_argument("--jdk-root", type=Path, default=ROOT / "workspace/java-bridge/jdks")
    parser.add_argument("--java21-prefix", type=Path)
    parser.add_argument("--llvm-home", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if not args.callbacks and args.candidate is None:
        parser.error("ordinary final stack qualification requires --candidate")
    mac = args.target == "macos-arm64"
    arches = ("arm64", "aarch64") if args.target.endswith("arm64") else ("amd64", "x86_64")
    if platform.system() != ("Darwin" if mac else "Linux") or platform.machine() not in arches:
        parser.error("matching host required; this runner does not qualify translated execution")
    if args.target.endswith("x86_64") != args.execution_scope.startswith("x86-64"):
        parser.error("execution scope does not match target")
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT"):
        if os.environ.get(name): parser.error("unset " + name)
    evidence = args.evidence.resolve(); evidence.mkdir(parents=True, exist_ok=False)

    def run(folder, name, command, require=True):
        command = list(map(str, command))
        (folder / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (folder / (name + ".log")).write_text(result.stdout); (folder / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if require and result.returncode: raise ValueError(f"{name}: {result.returncode}: {result.stdout}")
        return result

    def run_child(folder, name, java, arguments, require=True):
        # Each JVM extracts a complete paired payload. Dispose only this child's
        # new scratch directory after exit, including abnormal limit probes.
        # Keep commands, crash logs and all qualification evidence outside it.
        with tempfile.TemporaryDirectory(prefix="ironwood-stack-child-") as scratch:
            return run(folder, name, ["/bin/sh", "-c", 'ulimit -c 0; exec "$@"', "bridge-stack-child", java,
                "-Djava.io.tmpdir=" + scratch, *arguments], require)

    if args.revision_file:
        (evidence / "archived-revision.txt").write_bytes(args.revision_file.read_bytes())
    else:
        run(evidence, "revision", ["git", "rev-parse", "HEAD"])
        run(evidence, "working-diff", ["git", "diff", "--binary"])
    run(evidence, "os", ["sw_vers"] if mac else ["uname", "-a"])
    run(evidence, "cpu", ["sysctl", "-n", "machdep.cpu.brand_string"] if mac else ["lscpu"])
    jdks = {}
    for major in (21, 22, 23):
        pins = PREPARATION.PINS if major == 21 else PREPARATION.PINS.with_name(f"java-bridge-jdks-{major}.json")
        prefix = args.java21_prefix if major == 21 and args.java21_prefix else args.jdk_root / f"temurin-{major}-{args.target}"
        jdks[major] = PREPARATION.check_jdk(prefix, args.target, json.loads(pins.read_text()), pins)
        (evidence / f"jdk-{major}.json").write_text(json.dumps(jdks[major], indent=2) + "\n")
        run(evidence, f"jvm-default-flags-{major}", [jdks[major]["java"], "-XX:+PrintFlagsFinal", "-version"])
    candidate = json.loads((args.candidate / "version-O3/evidence.json").read_text())["assembled"]["manifest"] if args.candidate else None
    stem = "QualificationCallbackStack" if args.callbacks else "QualificationStack"
    source = Path(__file__).with_name(stem + ".iron").resolve()
    sources = [source]
    if args.callbacks: sources.append(Path(__file__).with_name("StackListener.iron").resolve())
    consumer = Path(__file__).with_name(stem + "Consumer.java").resolve()
    package = "callbackstackprobe" if args.callbacks else "stackprobe"
    completion = "generated-callback-stack-envelope-ok\n" if args.callbacks else "generated-stack-envelope-ok\n"
    records = []; payloads = {}
    for level in ("O0", "O3"):
        folder = evidence / level; folder.mkdir(); jar = folder / "stack.jar"
        run(folder, "produce", [jdks[21]["java"], "-cp", args.compiler.resolve(), "ironwood.compiler.Main", "--java-bridge",
            "--export", package, "--llvm-home", args.llvm_home, "--unfreed=off", "-" + level, "-o", jar, *sources])
        with zipfile.ZipFile(jar) as archive:
            manifest = CANDIDATE.properties(archive.read("META-INF/ironwood/bridge.properties"))
            for key in ("compiler.sha256", "runtime.sha256"):
                if candidate and manifest[key] != candidate[key]: raise ValueError("stack producer differs from final candidate: " + key)
            image = folder / Path(manifest["native.resource"]).name; image.write_bytes(archive.read(manifest["native.resource"]))
        if manifest["native.target"] != args.target: raise ValueError("unexpected produced target")
        payloads[level] = {"jar.sha256": CANDIDATE.digest(jar), "manifest": manifest}
        disassembly = run(folder, "disassembly", [args.llvm_home / "bin/llvm-objdump", "--disassemble", image])
        if args.callbacks and disassembly.stdout.count("ironwood_bridge_callback_") < 2:
            raise ValueError("callback call and target missing from target machine code")
        classes = folder / "classes"
        run(folder, "javac", [jdks[21]["javac"], "--release", "21", "-Xlint:all", "-Werror", "-cp", jar, "-d", classes, consumer])
        for major in (21, 22, 23):
            cell = folder / str(major); cell.mkdir()
            base = [jdks[major]["java"], "-Xcheck:jni", "-cp", str(jar) + os.pathsep + str(classes), stem + "Consumer"]
            result = run_child(cell, "bounded", base[0], [
                "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + str(cell / "bounded-hs_err.log"), *base[1:], "bounded"])
            if not result.stdout.endswith(completion) or result.stdout.count("bounded:") != 8 or "WARNING" in result.stdout:
                raise ValueError("bounded generated entry failed: " + result.stdout)
            summaries = []
            for size in ("512k", "1m"):
                last_success = 0; depth = 64 if args.callbacks else 512
                while depth <= 1048576:
                    name = f"limit-{size}-{depth}"
                    command = [
                        "-Xss" + size, "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + str(cell / (name + "-hs_err.log")), *base[1:], str(depth)]
                    probe = run_child(cell, name, base[0], command, False)
                    if probe.returncode:
                        summaries.append({"stack": size, "last_success": last_success, "first_unsuccessful": depth,
                            "exit": probe.returncode, "classification": "JVM-startup-refusal" if f"probe-start:{depth}\n" not in probe.stdout else
                            "native-crash" if (cell / (name + "-hs_err.log")).exists() else
                            "Java-StackOverflowError" if "java.lang.StackOverflowError" in probe.stdout else "child-failure"})
                        break
                    if f"probe-ok:{depth}:" not in probe.stdout: raise ValueError("probe lost completion marker")
                    last_success = depth; depth *= 2
                else: summaries.append({"stack": size, "last_success": last_success, "classification": "no-failure-within-probe-cap"})
            records.append({"level": level, "jdk": major, "bounded": "pass", "limit_diagnostics": summaries})
    result = {"callbacks": args.callbacks, "target": args.target, "execution_scope": args.execution_scope, "records": records, "payloads": payloads,
        "inputs": {str(path): CANDIDATE.digest(path) for path in (*sources, consumer, Path(__file__), args.compiler.resolve())},
        "scope": "final compiler public producer; bounded cases qualify only these depths; limit child failures are diagnostics, never successful recovery; physical scope is operator-declared"}
    (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"PASS: six generated bounded stack cells; separate limit diagnostics recorded ({args.execution_scope})")


if __name__ == "__main__": main()
