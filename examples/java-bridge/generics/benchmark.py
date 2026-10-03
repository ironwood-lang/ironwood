#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Qualify the generated getter path against an equivalent nongeneric facade."""
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
    parser.add_argument("--output", required=True, help="New evidence directory")
    parser.add_argument("--cpu", type=int, help="Linux CPU for timing children")
    parser.add_argument("--quick", action="store_true", help="Functional smoke only")
    parser.add_argument("--bridge-jar", type=pathlib.Path, help="Reuse a qualified single-target host JAR without rebuilding it")
    args = parser.parse_args()
    source = pathlib.Path(__file__).resolve().parent
    root = source.parents[2]
    output = pathlib.Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    jdk = pathlib.Path(os.environ["JAVA_HOME"])
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
    jar = output / "generics.jar"
    if args.bridge_jar:
        shutil.copyfile(args.bridge_jar, jar)
    else:
        run("bridge-build", [root / "bin/ironwoodc", "--java-bridge", "--export", "genericbench", "--unfreed=off", "-O3",
                             "--license", root / "LICENSE-MIT", "--license", root / "LICENSE-APACHE", "-o", jar]
                            + sorted(source.glob("*.iron")))
    run("java-compile", [jdk / "bin/javac", "--release", "21", "-Xlint:all", "-Werror", "-d", output / "java",
                         "-cp", jar, source / "GenericBench.java", source / "Consumer.java"])
    run("consumer", [jdk / "bin/java", "-Xcheck:jni", "-cp", str(output / "java") + os.pathsep + str(jar), "Consumer"])
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
    for name in ("Box", "Plain"):
        run("bytecode-" + name, [jdk / "bin/javap", "-cp", jar, "-p", "-c", "genericbench." + name])
    (output / "payloads.json").write_text(json.dumps({path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                                                    for path in (jar, library)}, indent=2))
    rows = []
    # Alternate order across independent JVMs to expose fork and clock variation.
    for fork in range(3 if not args.quick else 1):
        for scenario in (("generic", "plain") if fork % 2 == 0 else ("plain", "generic")):
            raw = run("timing-{}-{}".format(fork, scenario), [jdk / "bin/java", "-Xms128m", "-Xmx128m", "-cp",
                      str(output / "java") + os.pathsep + str(jar), "GenericBench", scenario,
                      2000000 if not args.quick else 10000], pin=True)
            parsed = list(csv.DictReader(io.StringIO("\n".join(line for line in raw.splitlines() if not line.startswith("first generic")))))
            if len(parsed) != 7:
                raise AssertionError("missing samples")
            for row in parsed:
                if int(row["checksum"]) != int(row["calls"]) or int(row["native_allocations"]) != 0:
                    raise AssertionError("bad checksum or native allocation")
                if not args.quick and int(row["java_bytes"]) != 0:
                    raise AssertionError("warmed getter allocated Java storage")
                row["fork"] = fork
            rows.extend(parsed)
    with (output / "samples.csv").open("w") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    summary = []
    for scenario in ("generic", "plain"):
        selected = [row for row in rows if row["scenario"] == scenario]
        times = [int(row["elapsed_ns"]) / int(row["calls"]) for row in selected]
        median = statistics.median(times)
        summary.append({"scenario": scenario, "median_ns_per_call": median,
                        "million_calls_per_second": 1000 / median,
                        "min_ns_per_call": min(times), "max_ns_per_call": max(times)})
    (output / "summary.json").write_text(json.dumps({"quick": args.quick, "results": summary}, indent=2))
    print("generic getter evidence: " + str(output))


if __name__ == "__main__":
    main()
