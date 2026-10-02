#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise local Maven/Gradle producer and cross-consumer dependency workflows."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "examples/java-bridge/build-tools"
EXPECTED = "42\ncopied: bridge\ncaught: example failure\ncontinued: 42\n"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--maven", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--paired-jar", type=Path, help="optional assembled value-example jar, copied without native rebuilding")
    args = parser.parse_args()
    directory = args.evidence.resolve(); directory.mkdir(parents=True, exist_ok=False)
    java_home = args.java_home.resolve()
    environment = os.environ.copy()
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        if environment.get(name): raise ValueError(f"unset {name} for bridge verification")
    environment.update(JAVA_HOME=str(java_home), IRONWOODC=str(ROOT / "bin/ironwoodc"),
                       PATH=str(java_home / "bin") + os.pathsep + environment["PATH"])
    if args.paired_jar: environment["BRIDGE_PAIRED_JAR"] = str(args.paired_jar.resolve())
    else: environment.pop("BRIDGE_PAIRED_JAR", None)

    def run(name, command, consumer=False):
        command = list(map(str, command))
        (directory / (name + ".command.json")).write_text(json.dumps(command, indent=2) + "\n")
        result = subprocess.run(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, timeout=300)
        (directory / (name + ".log")).write_text(result.stdout)
        (directory / (name + ".exit.txt")).write_text(str(result.returncode) + "\n")
        if result.returncode or (consumer and EXPECTED not in result.stdout):
            raise ValueError(f"{name} failed: {result.returncode}\n{result.stdout}")
        return result.stdout

    run("revision", ["git", "rev-parse", "HEAD"])
    run("working-diff", ["git", "diff", "--binary"])
    settings = run("jdk", [java_home / "bin/java", "-XshowSettings:properties", "-version"])
    pins = ('21.0.12.1+1', '22.0.2+9', '23.0.2+7', '24.0.2+12', '25.0.4.1+1')
    if not any('java.runtime.version = ' + pin in settings for pin in pins) or "java.vendor = Eclipse Adoptium" not in settings:
        raise ValueError("select a pinned Temurin 21 to 25 producer JDK")
    run("maven-version", [args.maven.resolve(), "--version"])
    run("gradle-version", [args.gradle.resolve(), "--version"])
    maven_repository = directory / "maven-repository"; gradle_repository = directory / "gradle-repository"
    maven = lambda project, repository: [args.maven.resolve(), "--batch-mode", "-Dmaven.repo.local=" + str(repository),
                                        "-f", EXAMPLES / project / "pom.xml"]
    gradle = lambda project, repository: [args.gradle.resolve(), "--no-daemon", "--project-cache-dir", directory / (project + "-cache"),
                                          "-Dmaven.repo.local=" + str(repository), "-p", EXAMPLES / project]
    run("maven-producer", [*maven("maven-producer", maven_repository), "clean", "install"])
    run("gradle-producer", [*gradle("gradle-producer", gradle_repository), "clean", "publishToMavenLocal"])
    run("maven-consumer", [*maven("maven-consumer", maven_repository), "clean", "compile", "exec:exec"], True)
    run("gradle-consumer", [*gradle("gradle-consumer", gradle_repository), "clean", "run"], True)
    run("gradle-consumes-maven", [*gradle("gradle-consumer", maven_repository), "clean", "run"], True)
    run("maven-consumes-gradle", [*maven("maven-consumer", gradle_repository), "clean", "compile", "exec:exec"], True)
    records = {"runner.sha256": digest(Path(__file__)), "scope": "local build-tool integration, no remote publication", "artifacts": {}}
    if args.paired_jar: records["input.sha256"] = digest(args.paired_jar)
    for tool, repository in (("maven", maven_repository), ("gradle", gradle_repository)):
        produced = EXAMPLES / (tool + "-producer") / "target/distribution"
        installed = repository / "org/ironwood/example/ironwood-values/0.1.0-local"
        records["artifacts"][tool] = {}
        for suffix in (".jar", "-sources.jar", "-javadoc.jar"):
            name = "ironwood-values-0.1.0-local" + suffix
            if digest(produced / name) != digest(installed / name): raise ValueError(f"installed bytes changed: {tool}/{name}")
            records["artifacts"][tool][name] = digest(installed / name)
        with zipfile.ZipFile(installed / "ironwood-values-0.1.0-local.jar") as archive:
            (directory / (tool + "-bridge.properties")).write_bytes(archive.read("META-INF/ironwood/bridge.properties"))
        for suffix in (".pom",):
            name = "ironwood-values-0.1.0-local" + suffix
            records["artifacts"][tool][name] = digest(installed / name)
    (directory / "evidence.json").write_text(json.dumps(records, indent=2) + "\n")
    print("PASS: two local producers, four ordinary dependency consumers and unchanged artifact/companion bytes")


if __name__ == "__main__": main()
