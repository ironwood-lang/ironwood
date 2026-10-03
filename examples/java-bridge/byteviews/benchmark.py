#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build matched payloads and retain raw timings, allocation counts and disassembly."""
import argparse
import csv
import hashlib
import io
import json
import os
import pathlib
import shutil
import statistics
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New evidence directory; existing directories are refused")
    parser.add_argument("--cpu", type=int, help="Linux CPU for functional and timing child processes")
    parser.add_argument("--quick", action="store_true", help="Short functional smoke, not performance evidence")
    parser.add_argument("--bridge-jar", type=pathlib.Path, help="Reuse a qualified single-target host JAR without rebuilding it")
    args = parser.parse_args()
    source = pathlib.Path(__file__).resolve().parent
    root = source.parents[2]
    output = pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    jdk = pathlib.Path(os.environ["JAVA_HOME"])
    compiler = root / "bin/ironwoodc"
    commands = []

    def run(name, command, pin=False):
        command = list(map(str, command))
        if pin and args.cpu is not None:
            command = ["taskset", "-c", str(args.cpu)] + command
        commands.append({"name": name, "command": command})
        (output / "commands.json").write_text(json.dumps(commands, indent=2))
        result = subprocess.run(command, cwd=str(root), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                universal_newlines=True, timeout=600)
        (output / (name + ".log")).write_text(result.stdout)
        if result.returncode:
            raise RuntimeError(name + " failed; see " + str(output / (name + ".log")))
        return result.stdout

    run("java-version", [jdk / "bin/java", "-version"])
    jar = output / "byteviews.jar"
    values = output / "ironwood-bridge-values.jar"
    if args.bridge_jar:
        shutil.copyfile(args.bridge_jar, jar)
        shutil.copyfile(args.bridge_jar.parent / "ironwood-bridge-values.jar", values)
    else:
        run("bridge-build", [compiler, "--java-bridge", "--export", "bytebench", "--unfreed=off", "-O3",
                             "--license", root / "LICENSE-MIT", "--license", root / "LICENSE-APACHE",
                             "-o", jar, source / "ByteOps.iron"])
    run("native-compile", [compiler, "--unfreed=off", "-d", output / "iron-classes",
                           source / "ByteOps.iron", source / "NativeBench.iron"])
    executable = output / "native-bench"
    run("native-link", [compiler, "--link", "--unfreed=off", "-O3", "-cp", output / "iron-classes",
                        "--main-class", "bytedriver.NativeBench", "-o", executable])
    run("java-compile", [jdk / "bin/javac", "--release", "21", "-Xlint:all", "-Werror", "-d", output / "java",
                         "-cp", str(jar) + os.pathsep + str(values), source / "JavaBench.java"])
    run("baseline-compile", [jdk / "bin/javac", "--release", "21", "-Xlint:all", "-Werror", "-d", output / "baseline",
                             "-cp", values, source / "ByteOps.java"])
    with zipfile.ZipFile(str(jar)) as archive:
        (output / "bridge.properties").write_bytes(archive.read("META-INF/ironwood/bridge.properties"))
        images = [name for name in archive.namelist() if name.startswith("META-INF/ironwood/native/")
                  and pathlib.Path(name).name in ("libbridge.so", "libbridge.dylib")]
        if len(images) != 1:
            raise ValueError("benchmark requires one host payload")
        image = images[0]
        library = output / pathlib.Path(image).name
        library.write_bytes(archive.read(image))
    objdump = shutil.which("llvm-objdump")
    if objdump is None:
        raise RuntimeError("pinned LLVM objdump is required")
    run("llvm-version", [objdump, "--version"])
    run("bridge-assembly", [objdump, "-d", "--demangle", library])
    run("native-assembly", [objdump, "-d", "--demangle", executable])
    identities = {str(path.relative_to(output)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in (jar, values, library, executable)}
    (output / "payloads.json").write_text(json.dumps(identities, indent=2))
    rows = []
    for operation in range(3):
        for size in (1, 64, 4096):
            count = 1000 if args.quick else 1000000 if size < 4096 else 50000
            results = {}
            for scenario in ("native", "java-array", "java-view", "bridge-array", "bridge-view"):
                if scenario == "native":
                    command = [executable, operation, size, count]
                else:
                    implementation = jar if scenario.startswith("bridge") else output / "baseline"
                    command = [jdk / "bin/java", "-Xms128m", "-Xmx128m", "-cp", str(output / "java") + os.pathsep + str(implementation) + os.pathsep + str(values),
                               "JavaBench", operation, size, count, scenario]
                raw = run("timing-{}-{}-{}".format(scenario, operation, size), command, pin=True)
                parsed = list(csv.DictReader(io.StringIO("\n".join(line for line in raw.splitlines() if not line.startswith("cold storage")))))
                if len(parsed) != 7:
                    raise AssertionError("missing samples")
                for row in parsed:
                    expected = count if scenario == "bridge-array" else 0
                    if int(row["native_allocations"]) != expected:
                        raise AssertionError("unexpected native allocations: " + repr(row))
                    if not args.quick and scenario != "native" and int(row["java_bytes"]) != 0:
                        raise AssertionError("warmed copied inputs allocated Java objects: " + repr(row))
                results[scenario] = parsed
                rows.extend(parsed)
            if any([row["checksum"] for row in results[s]] != [row["checksum"] for row in results["native"]]
                   for s in ("java-array", "java-view", "bridge-array", "bridge-view")):
                raise AssertionError("scenario checksum mismatch")
    with (output / "samples.csv").open("w") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    summary = []
    for operation in range(3):
        for size in (1, 64, 4096):
            for scenario in ("native", "java-array", "java-view", "bridge-array", "bridge-view"):
                selected = [row for row in rows if row["scenario"] == scenario and int(row["size"]) == size and int(row["operation"]) == operation]
                times = [int(row["elapsed_ns"]) / int(row["calls"]) for row in selected]
                median = statistics.median(times)
                summary.append({"operation": ["read", "update", "overlap"][operation], "size": size, "scenario": scenario,
                                "median_ns_per_call": median, "million_calls_per_second": 1000 / median,
                                "min_ns_per_call": min(times), "max_ns_per_call": max(times),
                                "java_bytes_per_call": None if scenario == "native" else
                                statistics.median(int(r["java_bytes"]) / int(r["calls"]) for r in selected)})
    (output / "summary.json").write_text(json.dumps({"quick": args.quick, "results": summary}, indent=2))
    print("byte-view benchmark evidence: " + str(output))


if __name__ == "__main__":
    main()
