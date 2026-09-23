#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Build, measure and archive the unchanged OrderBook throughput workload on Linux."""

import argparse
from collections import OrderedDict
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import statistics
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback


VARIANTS = OrderedDict([
    ("cpp-baseline", []),
    ("cpp-inline-1000", ["-mllvm", "-inline-threshold=1000"]),
    ("cpp-inline-1000-partial", ["-mllvm", "-inline-threshold=1000",
                                 "-mllvm", "-enable-partial-inlining"]),
    ("cpp-inline-2000", ["-mllvm", "-inline-threshold=2000"]),
    ("cpp-inline-2000-partial", ["-mllvm", "-inline-threshold=2000",
                                 "-mllvm", "-enable-partial-inlining"]),
])

IRONWOOD_VARIANTS = OrderedDict([
    ("ironwood", []),  # Default -O3 threshold 1000, selective and partial inlining on.
    ("ironwood-inline-2000", ["--inline-threshold", "2000"]),
    ("ironwood-inline-4000", ["--inline-threshold", "4000"]),
])
DEFAULT_ROUNDS = 2 * (len(IRONWOOD_VARIANTS) + len(VARIANTS))
# User-space hardware events work with perf_event_paranoid=2. Avoid requesting
# kernel events or system-wide access just to inspect this application loop.
PERF_EVENTS = "cycles:u,instructions:u,branches:u,branch-misses:u,cache-references:u,cache-misses:u"


def positive(value):
    number = int(value)
    if not 1 <= number <= 2147483647:
        raise argparse.ArgumentTypeError("expected an integer from 1 to 2147483647")
    return number


def read_optional(path):
    try:
        return Path(path).read_text().strip()
    except OSError as error:
        return "unavailable: " + str(error)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


class Experiment:
    def __init__(self, args, output):
        self.args = args
        self.output = output
        self.project = output / "source/projects/OrderBook"
        self.cpu = args.cpu
        self.sequence = 0
        self.samples = []
        self.executables = OrderedDict()

    def run(self, name, command, cwd=None, required=True, return_stderr=False):
        """Retain commands and separate output streams, including failed diagnostics."""
        command = [str(item) for item in command]
        self.sequence += 1
        stem = self.output / "logs" / ("%03d-%s" % (self.sequence, name))
        record = {"command": command, "cwd": str(cwd or self.project),
                  "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                  "stdout": str(stem.relative_to(self.output)) + ".stdout",
                  "stderr": str(stem.relative_to(self.output)) + ".stderr"}
        print("+ " + " ".join(shlex.quote(argument) for argument in command), flush=True)
        # Write before execution so an interrupted command remains identifiable.
        write_json(stem.with_suffix(".json"), record)
        started = time.monotonic()
        with Path(str(stem) + ".stdout").open("w") as stdout, \
                Path(str(stem) + ".stderr").open("w") as stderr:
            try:
                result = subprocess.run(command, cwd=cwd or self.project,
                                        stdout=stdout, stderr=stderr, timeout=1800)
                code = result.returncode
            except (OSError, subprocess.TimeoutExpired) as error:
                stderr.write(str(error) + "\n")
                code = -1
        record.update(returncode=code, wall_seconds=time.monotonic() - started)
        write_json(stem.with_suffix(".json"), record)
        if required and code != 0:
            raise RuntimeError("%s failed (%s); see %s.stderr" % (name, code, stem))
        return Path(str(stem) + (".stderr" if return_stderr else ".stdout")).read_text()

    def snapshot(self):
        repo = self.args.repo
        shutil.copytree(repo / "projects/OrderBook", self.project,
                        ignore=shutil.ignore_patterns("target", "__pycache__", ".DS_Store"))
        shutil.copy2(Path(__file__), self.output / "investigate-linux.py")
        for relative in ("LICENSE", "LICENSE-MIT", "LICENSE-APACHE", "LICENSES",
                         "docs/LICENSE_MECHANICS", "docs/SOURCE_PROVENANCE.md",
                         "docs/THIRD_PARTY_NOTICES.md"):
            source = repo / relative
            target = self.output / "source" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            elif source.is_file():
                shutil.copy2(source, target)
        self.run("git-head", ["git", "rev-parse", "HEAD"], cwd=repo, required=False)
        self.run("git-status", ["git", "status", "--short", "--branch"], cwd=repo, required=False)
        self.run("benchmark-diff", ["git", "diff", "HEAD", "--", "projects/OrderBook"],
                 cwd=repo, required=False)

    def machine(self):
        allowed = sorted(os.sched_getaffinity(0))
        if self.cpu is None:
            self.cpu = allowed[0]
        if self.cpu not in allowed:
            raise RuntimeError("CPU %s is outside allowed CPUs %s" % (self.cpu, allowed))
        cpu_root = Path("/sys/devices/system/cpu/cpu%d" % self.cpu)
        files = [Path(p) for p in ("/proc/cpuinfo", "/proc/meminfo", "/etc/os-release",
                 "/proc/version", "/proc/sys/kernel/perf_event_paranoid",
                 "/sys/devices/system/cpu/intel_pstate/no_turbo",
                 "/sys/devices/system/cpu/cpufreq/boost")]
        files += list((cpu_root / "cpufreq").glob("*"))
        files += [cpu_root / "topology/thread_siblings_list"]
        write_json(self.output / "machine.json", {
            "python_version": sys.version, "python_executable": sys.executable,
            "platform": platform.platform(), "allowed_cpus": allowed, "selected_cpu": self.cpu,
            "files": {str(p): read_optional(p) for p in files},
            "environment": {key: os.environ[key] for key in (
                "PATH", "JAVA_HOME", "IRONWOOD_LLVM_HOME", "IRONWOOD_RUNTIME_HOME",
                "IRONWOOD_STDLIB_HOME", "LD_LIBRARY_PATH", "LD_PRELOAD") if key in os.environ},
        })
        self.run("lscpu", ["lscpu"], required=False)
        self.run("affinity-check", ["taskset", "-c", str(self.cpu), "true"])

    def toolchain(self):
        # Reuse the same authoritative discovery as the ordinary C++ scripts.
        compiler = self.run("toolchain", ["bash", "-c",
            'source "$1"; find_llvm_cxx', "bash", self.project / "cpp/toolchain.sh"]).strip()
        self.clang = Path(compiler)
        self.llvm_bin = self.clang.parent
        self.cxx = [compiler, "--driver-mode=g++"]
        triple = self.run("target-triple", [compiler, "-print-target-triple"]).strip()
        if "-conda-" in triple:
            self.cxx += ["--target=" + triple.replace("-conda-", "-unknown-", 1),
                         "--gcc-toolchain=/usr"]
        self.run("clang-version", [compiler, "--version"])
        self.run("clang-driver", self.cxx + ["-###", "-x", "c++", "/dev/null"])
        # Preserve available installed support source and compiler identity, not
        # the entire LLVM/JDK installation. Explicit overrides are recorded above.
        launcher = Path(shutil.which("ironwoodc")).resolve()
        home = launcher.parent.parent
        installed = self.output / "installed-ironwood"
        installed.mkdir()
        write_json(installed / "location.json", {"launcher": str(launcher), "home": str(home),
                   "clang": str(self.clang), "clang_sha256": digest(self.clang)})
        for relative in ("lib/ironwoodc.jar", "compiler/build/ironwoodc.jar",
                         "lib/ironwood-stdlib.ironjar", "compiler/build/ironwood-stdlib.ironjar",
                         "stdlib/src/main/ironwood", "runtime/src", "LICENSES",
                         "LICENSE", "LICENSE-MIT", "LICENSE-APACHE",
                         "LICENSE_MECHANICS", "THIRD_PARTY_NOTICES.md",
                         "docs/SOURCE_PROVENANCE.md", "docs/THIRD_PARTY_NOTICES.md"):
            source = home / relative
            target = installed / relative
            if source.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                if source.is_dir():
                    shutil.copytree(source, target)
                else:
                    shutil.copy2(source, target)

    def perf_preflight(self):
        if self.args.skip_perf:
            write_json(self.output / "perf-status.json", {"enabled": False})
            return
        self.perf = shutil.which("perf")
        if not self.perf:
            raise RuntimeError("perf is missing; install perf for this kernel, or explicitly use --skip-perf")
        write_json(self.output / "perf-status.json", {"enabled": True, "executable": self.perf,
                   "events": PERF_EVENTS, "preflight_passed": False})
        self.run("perf-version", [self.perf, "--version"])
        probe = ["taskset", "-c", str(self.cpu), sys.executable, "-c",
                 "import time\nend = time.monotonic() + 0.25\nwhile time.monotonic() < end: pass"]
        try:
            self.stat_counters("preflight", probe)
            if self.args.perf_record:
                self.record_profile("preflight", probe)
        except RuntimeError as error:
            raise RuntimeError(
                "perf preflight failed before building or timing. Current perf_event_paranoid="
                + read_optional("/proc/sys/kernel/perf_event_paranoid")
                + ". For permission errors, note the original value, temporarily run "
                "'sudo sysctl -w kernel.perf_event_paranoid=2', rerun this script as your normal "
                "user, then restore the original value. Unsupported events or other restrictions "
                "need investigation in the perf logs; --skip-perf explicitly permits timing only. "
                + str(error)) from error
        write_json(self.output / "perf-status.json", {"enabled": True, "executable": self.perf,
                   "events": PERF_EVENTS, "preflight_passed": True})

    def stat_counters(self, name, command):
        output = self.run(name + "-perf-stat", [self.perf, "stat", "-x", ";", "-e", PERF_EVENTS,
                          "--"] + command, return_stderr=True)
        counted = set()
        for line in output.splitlines():
            fields = [field.strip() for field in line.split(";")]
            if len(fields) >= 3 and re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", fields[0]):
                counted.add(fields[2])
        missing = set(PERF_EVENTS.split(",")) - counted
        if missing:
            raise RuntimeError("perf did not count these events for %s: %s; see the perf-stat log"
                               % (name, ", ".join(sorted(missing))))

    def record_profile(self, name, command):
        profile = self.output / (name + ".perf.data")
        self.run(name + "-perf-record", [self.perf, "record", "-e", "cycles:u", "-F", "199",
                 "-o", profile, "--"] + command)
        if not profile.is_file() or not profile.stat().st_size:
            raise RuntimeError("perf record did not produce a nonempty profile: " + str(profile))
        self.run(name + "-perf-report", [self.perf, "report", "--stdio", "--no-children",
                 "--sort", "dso,symbol", "-i", profile])

    def build(self):
        build = self.output / "build"
        build.mkdir()
        iw = build / "ironwood"
        iw.mkdir()
        source = self.project / "src/main/ironwood"
        self.run("ironwood-compile", ["ironwoodc", "--source-path", source, "-d", iw / "classes",
                 source / "org/ironwood/orderbook/Bench.iron"])
        for name, flags in IRONWOOD_VARIANTS.items():
            directory = build / name
            directory.mkdir(exist_ok=True)
            binary = directory / "orderbook-bench"
            self.run(name + "-link", ["ironwoodc", "--link", "-cp", iw / "classes",
                     "--main-class", "org.ironwood.orderbook.Bench", "-O3", "-march=native"]
                     + flags + ["--emit-llvm", directory / "program.ll", "-o", binary])
            self.executables[name] = binary
        include = self.project / "cpp/src/main/cpp"
        src = include / "org/ironwood/orderbook"
        cpu_flag = "-mcpu=native" if platform.machine() in ("arm64", "aarch64") else "-march=native"
        common = ["-std=c++17", "-O3", cpu_flag, "-ffp-contract=off", "-fwrapv",
                  "-Wall", "-Wextra", "-Wpedantic", "-Werror", "-I", include]
        # One identical clock object for every variant, without LTO. Tuning the
        # engine cannot accidentally expose the separate runtime clock body.
        clock = build / "JavaCompat.o"
        self.run("clock-compile", self.cxx + common + ["-c", src / "JavaCompat.cpp", "-o", clock])
        write_json(self.output / "variants.json", {"common": [str(x) for x in common],
                   "variants": VARIANTS, "ironwood_variants": IRONWOOD_VARIANTS,
                   "ironwood_defaults": {"inline_threshold": 1000, "selective_inlining": True,
                                         "partial_inlining": True}, "lto": False})
        for name, flags in VARIANTS.items():
            directory = build / name
            directory.mkdir()
            obj = directory / "Bench.o"
            self.run(name + "-compile", self.cxx + common + flags + [
                "-fsave-optimization-record", "-foptimization-record-file=" + str(directory / "remarks.yaml"),
                "-c", src / "Bench.cpp", "-o", obj])
            binary = directory / "orderbook-bench"
            self.run(name + "-link", self.cxx + [obj, clock, "-o", binary])
            self.executables[name] = binary

    def benchmark_command(self, binary, smoke=False):
        return ["taskset", "-c", str(self.cpu), binary,
                "0" if smoke else str(self.args.warmup),
                "1" if smoke else str(self.args.measured)]

    @staticmethod
    def elapsed(output):
        if not re.fullmatch(r"[0-9]+\s*", output) or int(output) <= 0:
            raise RuntimeError("benchmark did not print one positive nanosecond count: " + repr(output))
        return int(output)

    def measure(self):
        for name, binary in self.executables.items():
            self.elapsed(self.run(name + "-smoke", self.benchmark_command(binary, smoke=True)))
        names = list(self.executables)
        with (self.output / "samples.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=["round", "position", "variant", "elapsed_ns",
                "operations", "million_ops_per_second", "loadavg_before", "loadavg_after"])
            writer.writeheader()
            for index in range(self.args.rounds):
                # Rotate positions; reverse after one round per executable.
                # The default two blocks give each variant every position twice.
                order = names[index % len(names):] + names[:index % len(names)]
                if (index // len(names)) % 2:
                    order.reverse()
                for position, name in enumerate(order, 1):
                    before = read_optional("/proc/loadavg")
                    elapsed = self.elapsed(self.run("round-%02d-%s" % (index + 1, name),
                                           self.benchmark_command(self.executables[name])))
                    sample = {"round": index + 1, "position": position, "variant": name,
                              "elapsed_ns": elapsed, "operations": self.args.measured * 1000000,
                              "million_ops_per_second": self.args.measured * 1e9 / elapsed,
                              "loadavg_before": before, "loadavg_after": read_optional("/proc/loadavg")}
                    writer.writerow(sample)
                    stream.flush()
                    self.samples.append(sample)
                    print("Round %d/%d: %s %.3f ms" % (
                        index + 1, self.args.rounds, name, elapsed / 1e6), flush=True)

    def diagnose(self):
        for name, binary in self.executables.items():
            for label, command in (
                ("disassembly", [self.llvm_bin / "llvm-objdump", "-d", "--demangle", binary]),
                ("symbols", [self.llvm_bin / "llvm-nm", "--demangle", "--size-sort", "--print-size", binary]),
                ("elf", [self.llvm_bin / "llvm-readelf", "-h", "-d", "-n", binary]),
                ("libraries", ["ldd", binary]),
            ):
                self.run(name + "-" + label, command, required=False)
            # Counters cover the whole process, including warmup and verification.
            # They are separate from the primary timings, never mixed into CSV.
            if not self.args.skip_perf:
                self.stat_counters(name, self.benchmark_command(binary))
                if self.args.perf_record:
                    self.record_profile(name, self.benchmark_command(binary))

    def summarize(self):
        summary = {}
        for name in self.executables:
            values = [s["elapsed_ns"] for s in self.samples if s["variant"] == name]
            if values:
                median = statistics.median(values)
                summary[name] = {"runs": len(values), "median_ns": median,
                                 "min_ns": min(values), "max_ns": max(values),
                                 "median_absolute_deviation_ns": statistics.median(abs(v - median) for v in values),
                                 "million_ops_per_second": self.args.measured * 1e9 / median}
        write_json(self.output / "summary.json", summary)
        for name, result in summary.items():
            print("%s: median %.3f ms, %.3f million ops/s (%d runs)" % (
                name, result["median_ns"] / 1e6, result["million_ops_per_second"], result["runs"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path.cwd(), help="Ironwood checkout root (default: current directory)")
    parser.add_argument("--output", type=Path, help="parent for a unique result directory and .tar.gz")
    parser.add_argument("--cpu", type=int, help="logical CPU (default: first CPU allowed by affinity)")
    parser.add_argument("--rounds", type=positive, default=DEFAULT_ROUNDS,
                        help="rounds across all eight variants (default: %d)" % DEFAULT_ROUNDS)
    parser.add_argument("--warmup", type=positive, default=40, help="warmup operations in millions (default: 40)")
    parser.add_argument("--measured", type=positive, default=400, help="measured operations in millions (default: 400)")
    perf_options = parser.add_mutually_exclusive_group()
    perf_options.add_argument("--perf-record", action="store_true", help="also require separate sampled CPU profiles")
    perf_options.add_argument("--skip-perf", action="store_true", help="explicitly run without perf counters or profiles")
    args = parser.parse_args()
    if sys.version_info < (3, 6):
        parser.error("Python 3.6 or newer is required")
    if platform.system() != "Linux":
        parser.error("run this experiment on Linux; --help is available on every platform")
    args.repo = args.repo.expanduser().resolve()
    if not (args.repo / "projects/OrderBook/cpp/toolchain.sh").is_file():
        parser.error("--repo must identify an Ironwood checkout with the current OrderBook C++ sources")
    # Keep bundled third-party source and generated YAML outside source audits.
    parent = (args.output or args.repo / "target/orderbook-investigations").expanduser().resolve()
    project = args.repo / "projects/OrderBook"
    if (parent == project or project in parent.parents) and "target" not in parent.relative_to(project).parts:
        parser.error("--output inside OrderBook must be under a target directory, outside the source snapshot")
    parent.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix="orderbook-" + time.strftime("%Y%m%d-%H%M%S-"), dir=parent))
    (output / "logs").mkdir()
    write_json(output / "options.json", {key: str(value) if isinstance(value, Path) else value
                                        for key, value in vars(args).items()})
    experiment = Experiment(args, output)
    status = {"success": False}
    try:
        experiment.snapshot()
        experiment.machine()
        experiment.perf_preflight()
        experiment.toolchain()
        experiment.build()
        experiment.measure()
        experiment.diagnose()
        status["success"] = True
    except (Exception, KeyboardInterrupt) as error:
        status["error"] = str(error) or type(error).__name__
        (output / "failure.txt").write_text(traceback.format_exc())
        print("Experiment stopped: " + status["error"], file=sys.stderr)
    finally:
        experiment.summarize()
        write_json(output / "status.json", status)
        with (output / "SHA256SUMS").open("w") as manifest:
            for path in sorted(output.rglob("*")):
                if path.is_file() and path.name != "SHA256SUMS":
                    manifest.write(digest(path) + "  " + str(path.relative_to(output)) + "\n")
        archive = Path(str(output) + ".tar.gz")
        with tarfile.open(str(archive), "w:gz") as bundle:
            bundle.add(str(output), arcname=output.name)
        print("\nUpload this archive: " + str(archive), flush=True)
    return 0 if status["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
