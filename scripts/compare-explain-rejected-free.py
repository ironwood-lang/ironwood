#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Compare two independently built Ironwood compilers with explanations off."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parent.parent
PROBE = ROOT / "scripts/ParityStdlibProbe.java"
CORE_TYPES = ("ironwood.lang.Object", "ironwood.lang.String")


class ComparisonError(RuntimeError):
    pass


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def invoke(argv, cwd, environment, timeout, label, log_dir):
    try:
        result = subprocess.run(argv, cwd=cwd, env=environment, timeout=timeout,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ComparisonError(f"{label}: process failure: {error}") from error
    log_dir.mkdir(parents=True, exist_ok=True)
    (log_dir / "stdout").write_bytes(result.stdout)
    (log_dir / "stderr").write_bytes(result.stderr)
    (log_dir / "status").write_text(f"{result.returncode}\n", encoding="utf-8")
    return result


def require_success(result, label):
    if result.returncode != 0:
        raise ComparisonError(f"{label}: tool failed with status {result.returncode}")


def export_base(revision, destination):
    archive = subprocess.run(["git", "archive", "--format=tar", revision],
                             cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             check=False)
    if archive.returncode != 0:
        raise ComparisonError("base export failed: " + archive.stderr.decode("utf-8", "replace"))
    destination.mkdir(parents=True)
    with tarfile.open(fileobj=io.BytesIO(archive.stdout), mode="r:") as contents:
        for member in contents:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ComparisonError(f"unsafe base archive entry: {member.name}")
        contents.extractall(destination, filter="data")


def build(root, environment, log_dir):
    result = invoke([str(root / "scripts/build.sh")], root, environment, 600,
                    "build", log_dir)
    require_success(result, f"build at {root}")
    jar = root / "compiler/build/ironwoodc.jar"
    stdlib = root / "compiler/build/ironwood-stdlib.ironjar"
    if not jar.is_file() or not stdlib.is_file():
        raise ComparisonError(f"build at {root}: missing compiler or standard-library archive")
    return jar, stdlib


def java_environment(root):
    environment = os.environ.copy()
    environment["IRONWOOD_STDLIB_HOME"] = str(root.resolve())
    return environment


def compile_probe(jar, destination, environment, log_dir):
    destination.mkdir(parents=True)
    result = invoke(["javac", "--release", "21", "-encoding", "UTF-8", "-cp",
                     str(jar), "-d", str(destination), str(PROBE)], ROOT,
                    environment, 120, "probe build", log_dir)
    require_success(result, "standard-library probe build")


def verify_discovery(jar, probe_classes, root, cwd, names, environment, log_dir):
    expected = (root / "compiler/build/ironwood-stdlib.ironjar").resolve()
    if not expected.is_file():
        raise ComparisonError(f"expected standard-library archive missing: {expected}")
    actual_environment = environment.copy()
    actual_environment["IRONWOOD_STDLIB_HOME"] = str(root.resolve())
    result = invoke(["java", "-cp", os.pathsep.join((str(jar), str(probe_classes))),
                     "ironwood.compiler.ParityStdlibProbe", *names], cwd,
                    actual_environment, 120, "standard-library discovery", log_dir)
    require_success(result, "standard-library discovery")
    rows = {}
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split("\t", 1)
        if len(parts) != 2 or parts[0] in rows:
            raise ComparisonError(f"malformed standard-library discovery: {line}")
        rows[parts[0]] = parts[1]
    if set(rows) != set(names):
        raise ComparisonError(f"standard-library discovery returned {sorted(rows)}, expected {sorted(names)}")
    prefix = str(expected) + "!/"
    for name, path in rows.items():
        if not path.startswith(prefix):
            raise ComparisonError(f"{name} resolved outside expected archive: {path}")
    (log_dir / "discovery.json").write_text(json.dumps({
        "configured_home": str(root.resolve()), "cwd": str(cwd.resolve()),
        "archive": str(expected), "archive_sha256": digest(expected),
        "types": rows,
    }, indent=2) + "\n", encoding="utf-8")
    return rows


def load_fixture(manifest):
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("expected") not in ("accepted", "rejected"):
        raise ComparisonError(f"{manifest}: expected must be accepted or rejected")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", data.get("id", "")):
        raise ComparisonError(f"{manifest}: invalid fixture id")
    sources = [(manifest.parent / name).resolve() for name in data["sources"]]
    if not sources or any(not source.is_file() for source in sources):
        raise ComparisonError(f"{manifest}: missing source")
    if data["expected"] == "accepted" and not data.get("main_class"):
        raise ComparisonError(f"{manifest}: accepted fixture needs main_class")
    if data["expected"] == "rejected" and not data.get("diagnostic_pattern"):
        raise ComparisonError(f"{manifest}: rejected fixture needs diagnostic_pattern")
    data["source_paths"] = sources
    data["source_hashes"] = {str(path): digest(path) for path in sources}
    data["stdlib_types"] = sorted(set(CORE_TYPES) | set(data.get("stdlib_types", [])))
    return data


def files(directory):
    return {str(path.relative_to(directory)): digest(path)
            for path in directory.rglob("*") if path.is_file()}


def validate_compile(result, expected, diagnostic_pattern, output_dir):
    if expected == "accepted":
        if result.returncode != 0:
            raise ComparisonError(f"accepted fixture failed with status {result.returncode}")
        if not any(output_dir.rglob("*.ironclass")):
            raise ComparisonError("accepted fixture produced no class artifact")
        return
    if result.returncode != 1:
        raise ComparisonError(f"rejected fixture returned status {result.returncode}, expected 1")
    stderr = result.stderr.decode("utf-8", "replace")
    if ("usage: ironwoodc" in stderr or "Exception in thread" in stderr
            or "error: native link failed" in stderr or "error: cannot load" in stderr
            or not re.search(diagnostic_pattern, stderr, re.MULTILINE)):
        raise ComparisonError("rejected fixture has tool failure or lacks its expected diagnostic")
    if files(output_dir):
        raise ComparisonError("rejected fixture emitted an artifact")


def normalize_output(data, replacements):
    text = data.decode("utf-8", "strict")
    for old, new in sorted(replacements.items(), key=lambda item: len(item[0]), reverse=True):
        text = text.replace(old, new)
    return text


def compare_processes(left, right, left_roots, right_roots, label):
    if left.returncode != right.returncode:
        raise ComparisonError(f"{label}: status changed: {left.returncode} versus {right.returncode}")
    for stream in ("stdout", "stderr"):
        a = normalize_output(getattr(left, stream), left_roots)
        b = normalize_output(getattr(right, stream), right_roots)
        if a != b:
            raise ComparisonError(f"{label}: {stream} changed; inspect preserved raw logs")


def compare_files(left, right, label):
    a = files(left)
    b = files(right)
    if a != b:
        raise ComparisonError(f"{label}: file inventory or exact bytes changed: {a} versus {b}")
    return a


def compare_bytes(left, right, label):
    if left.read_bytes() != right.read_bytes():
        raise ComparisonError(f"{label}: exact bytes changed")


def exercise_isolation(base_root, candidate_root, base_jar, base_probe, base_stdlib,
                       scratch, environment):
    conflicting = environment.copy()
    conflicting["IRONWOOD_STDLIB_HOME"] = str(candidate_root)
    verify_discovery(base_jar, base_probe, base_root, candidate_root,
                     CORE_TYPES, conflicting, scratch / "conflicting-home-and-cwd")
    held = base_stdlib.with_name(base_stdlib.name + ".held")
    base_stdlib.rename(held)
    try:
        fallback = invoke(["java", "-cp", os.pathsep.join((str(base_jar), str(base_probe))),
                           "ironwood.compiler.ParityStdlibProbe", "ironwood.lang.Object"],
                          candidate_root, java_environment(base_root), 120,
                          "missing archive fallback", scratch / "missing-archive-fallback")
        require_success(fallback, "missing archive fallback control")
        fallback_path = fallback.stdout.decode("utf-8", "replace")
        if (str(base_stdlib) in fallback_path
                or (".ironclass!/" not in fallback_path and ".iron" not in fallback_path)):
            raise ComparisonError("missing archive control did not expose class/source fallback")
        try:
            verify_discovery(base_jar, base_probe, base_root, candidate_root,
                             CORE_TYPES, conflicting, scratch / "missing-archive-rejection")
        except ComparisonError as error:
            if "expected standard-library archive missing" not in str(error):
                raise
        else:
            raise ComparisonError("missing archive was not rejected")
    finally:
        held.rename(base_stdlib)


def run_fixture(data, root, jar, probe_classes, scratch, inherited_environment,
                llvm_home, timeout):
    run_dir = scratch / "output"
    classes = run_dir / "classes"
    classes.mkdir(parents=True)
    environment = java_environment(root)
    names = data["stdlib_types"]
    verify_discovery(jar, probe_classes, root, root, names, inherited_environment,
                     scratch / "discovery")
    source_path = os.pathsep.join(sorted({str(path.parent) for path in data["source_paths"]}))
    command = ["java", "-jar", str(jar), *map(str, data["source_paths"]), "-d",
               str(classes), "--source-path", source_path, "--class-path",
               str(run_dir / "empty-classpath"), "--unfreed=off"]
    compiled = invoke(command, root, environment, timeout, "compile", scratch / "compile")
    validate_compile(compiled, data["expected"], data.get("diagnostic_pattern", "^error:"), classes)
    result = {"compile": compiled, "classes": classes}
    if data["expected"] == "rejected":
        return result
    archive = run_dir / "fixture.ironjar"
    packed = invoke(["java", "-cp", str(jar), "ironwood.compiler.IronJarMain",
                     "--create", "--file", str(archive), str(classes)], root,
                    environment, timeout, "archive", scratch / "archive")
    require_success(packed, "archive creation")
    llvm = run_dir / "fixture.ll"
    native = run_dir / "fixture"
    link_command = ["java", "-jar", str(jar), "--link", "--main-class",
                    data["main_class"], "--class-path", str(classes), "-o",
                    str(native), "--emit-llvm", str(llvm), "--unfreed=off"]
    if llvm_home:
        link_command.extend(("--llvm-home", str(llvm_home)))
    linked = invoke(link_command, root, environment, timeout, "link", scratch / "link")
    require_success(linked, "native link")
    if not archive.is_file() or not llvm.is_file() or not native.is_file():
        raise ComparisonError("accepted fixture missing archive, LLVM, or native output")
    result.update({"archive": archive, "llvm": llvm, "link": linked})
    if data.get("run_native"):
        executed = invoke([str(native)], root, environment, timeout, "native run",
                          scratch / "native")
        result["native"] = executed
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="full base commit SHA")
    parser.add_argument("--fixture", required=True, type=Path, action="append")
    parser.add_argument("--scratch", type=Path)
    parser.add_argument("--llvm-home", type=Path)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.base):
        parser.error("--base must be a full commit SHA")
    scratch = args.scratch.resolve() if args.scratch else Path(tempfile.mkdtemp(prefix="ironwood-parity-"))
    scratch.mkdir(parents=True, exist_ok=True)
    print(f"comparison evidence: {scratch}", flush=True)
    base_root = scratch / "base"
    export_base(args.base, base_root)
    candidate_root = ROOT
    inherited = os.environ.copy()
    base_jar, base_stdlib = build(base_root, java_environment(base_root), scratch / "base-build")
    candidate_jar, candidate_stdlib = build(candidate_root, java_environment(candidate_root), scratch / "candidate-build")
    source_files = sorted(path.relative_to(base_root) for path in (base_root / "stdlib/src/main/ironwood").rglob("*.iron"))
    candidate_source_files = sorted(path.relative_to(candidate_root) for path in
                                    (candidate_root / "stdlib/src/main/ironwood").rglob("*.iron"))
    if source_files != candidate_source_files:
        raise ComparisonError("standard-library source inventory changed")
    for relative in source_files:
        if digest(base_root / relative) != digest(candidate_root / relative):
            raise ComparisonError(f"standard-library source changed: {relative}")
    base_probe = scratch / "base-probe"
    candidate_probe = scratch / "candidate-probe"
    compile_probe(base_jar, base_probe, java_environment(base_root), scratch / "base-probe-build")
    compile_probe(candidate_jar, candidate_probe, java_environment(candidate_root), scratch / "candidate-probe-build")
    exercise_isolation(base_root, candidate_root, base_jar, base_probe, base_stdlib,
                       scratch / "isolation", inherited)
    fixtures = [load_fixture(path.resolve()) for path in args.fixture]
    ids = [fixture["id"] for fixture in fixtures]
    if len(set(ids)) != len(ids):
        raise ComparisonError("fixture IDs must be unique")
    report = {"base": args.base, "candidate_head": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "candidate_diff_sha256": hashlib.sha256(subprocess.check_output(
            ["git", "diff", "HEAD"], cwd=ROOT)).hexdigest(),
        "candidate_untracked_sha256": {name: digest(ROOT / name) for name in
            subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard"],
                                    cwd=ROOT, text=True).splitlines() if (ROOT / name).is_file()},
        "base_stdlib_sha256": digest(base_stdlib),
        "candidate_stdlib_sha256": digest(candidate_stdlib),
        "java_version": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT,
                                                text=True).strip(),
        "javac_version": subprocess.check_output(["javac", "-version"], stderr=subprocess.STDOUT,
                                                 text=True).strip(),
        "llvm_home": str(args.llvm_home or os.environ.get("IRONWOOD_LLVM_HOME", "auto-discovered")),
        "flags": ["--unfreed=off", "--emit-llvm for accepted fixtures"],
        "fixtures": []}
    for label, root, jar in (("base", base_root, base_jar),
                             ("candidate", candidate_root, candidate_jar)):
        version = invoke(["java", "-jar", str(jar), "--version"], root,
                         java_environment(root), 120, "compiler version",
                         scratch / (label + "-version"))
        require_success(version, label + " compiler version")
        report[label + "_compiler_version"] = version.stdout.decode("utf-8", "replace")
    for fixture in fixtures:
        identity = fixture["id"]
        base_run = run_fixture(fixture, base_root, base_jar, base_probe,
                               scratch / identity / "base", inherited, args.llvm_home, args.timeout)
        candidate_run = run_fixture(fixture, candidate_root, candidate_jar, candidate_probe,
                                    scratch / identity / "candidate", inherited, args.llvm_home, args.timeout)
        base_out = scratch / identity / "base/output"
        candidate_out = scratch / identity / "candidate/output"
        left_roots = {str(base_out): "<output>", str(base_stdlib): "<stdlib>"}
        right_roots = {str(candidate_out): "<output>", str(candidate_stdlib): "<stdlib>"}
        compare_processes(base_run["compile"], candidate_run["compile"],
                          left_roots, right_roots, identity + " compile")
        class_hashes = compare_files(base_run["classes"], candidate_run["classes"],
                                     identity + " classes")
        entry = {"id": identity, "expected": fixture["expected"],
                 "sources": fixture["source_hashes"], "class_sha256": class_hashes,
                 "normalizations": ["paired output roots -> <output>",
                                    "paired verified stdlib archives -> <stdlib>"]}
        if fixture["expected"] == "accepted":
            compare_processes(base_run["link"], candidate_run["link"],
                              left_roots, right_roots, identity + " link")
            compare_bytes(base_run["archive"], candidate_run["archive"], identity + " archive")
            compare_bytes(base_run["llvm"], candidate_run["llvm"], identity + " LLVM")
            entry["archive_sha256"] = digest(base_run["archive"])
            entry["llvm_sha256"] = digest(base_run["llvm"])
            if "native" in base_run:
                compare_processes(base_run["native"], candidate_run["native"],
                                  left_roots, right_roots, identity + " native")
                entry["native_status"] = base_run["native"].returncode
        report["fixtures"].append(entry)
        print(f"PASS {identity}", flush=True)
    (scratch / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PASS {len(fixtures)} fixture(s)", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ComparisonError, OSError, ValueError, KeyError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
