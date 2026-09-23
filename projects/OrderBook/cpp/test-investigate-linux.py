#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Exercise the Linux experiment's orchestration without timing fake results as evidence."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("investigate-linux.py")
SPEC = importlib.util.spec_from_file_location("investigate", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class InvestigationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="orderbook investigation ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "checkout"
        project = self.repo / "projects/OrderBook"
        (project / "cpp").mkdir(parents=True)
        (project / "src").mkdir()
        shutil.copy2(SCRIPT.with_name("toolchain.sh"), project / "cpp/toolchain.sh")
        self.bin = self.root / "installed/bin"
        self.bin.mkdir(parents=True)
        driver = self.bin / "fixture"
        driver.write_text("#!" + sys.executable + "\n" + r'''
import os
from pathlib import Path
import sys
args = sys.argv[1:]
name = Path(sys.argv[0]).name
if name == 'taskset':
    os.execvp(args[2], args[2:])
if name == 'perf':
    if args == ['--version']:
        print('perf fixture')
        sys.exit(0)
    if os.environ.get('FIXTURE_PERF_DENIED'):
        print('fixture: perf permission denied', file=sys.stderr)
        sys.exit(1)
    if args[0] == 'report':
        print('fixture profile report')
        sys.exit(0)
    if args[0] == 'stat':
        for event in args[args.index('-e') + 1].split(','):
            count = '<not supported>' if os.environ.get('FIXTURE_PERF_UNSUPPORTED') else '1000'
            print(count + ';;' + event + ';1000000;100.00;', file=sys.stderr)
    if args[0] == 'record':
        Path(args[args.index('-o') + 1]).write_text('fixture profile')
    command = args[args.index('--') + 1:]
    os.execvp(command[0], command)
if name == 'ironwoodc' and args == ['-v']:
    home = Path(sys.argv[0]).parent.parent
    print('ironwoodc fixture\nLLVM version: 23.1.0\nLLVM home: ' + str(home))
    print('LLVM clang: ' + str(home / 'bin/clang') + '\nClang version: fixture')
elif name == 'clang' and args == ['-print-target-triple']:
    print('x86_64-conda-linux-gnu')
elif '-o' in args:
    output = Path(args[args.index('-o') + 1])
    output.parent.mkdir(parents=True, exist_ok=True)
    if os.environ.get('FIXTURE_BUILD_FAILURE') and 'cpp-inline-1000-partial' in str(output):
        print('fixture build failure', file=sys.stderr)
        sys.exit(2)
    if output.suffix == '.o':
        output.write_text('fixture object')
    else:
        output.write_text('#!/bin/sh\nprintf "1000000\\n"\n')
        output.chmod(0o755)
''')
        driver.chmod(0o755)
        for name in ("ironwoodc", "clang", "taskset", "perf", "lscpu", "ldd",
                     "llvm-objdump", "llvm-nm", "llvm-readelf"):
            shutil.copy2(driver, self.bin / name)

    def run_experiment(self, failure=False, perf_denied=False, perf_unsupported=False, skip_perf=False):
        argv = [str(SCRIPT), "--repo", str(self.repo), "--output", str(self.root / "results"),
                "--warmup", "1", "--measured", "1", "--skip-perf" if skip_perf else "--perf-record"]
        environment = {"PATH": str(self.bin) + os.pathsep + os.environ["PATH"]}
        if failure:
            environment["FIXTURE_BUILD_FAILURE"] = "1"
        if perf_denied:
            environment["FIXTURE_PERF_DENIED"] = "1"
        if perf_unsupported:
            environment["FIXTURE_PERF_UNSUPPORTED"] = "1"
        with patch.object(sys, "argv", argv), patch.dict(os.environ, environment), \
                patch.object(MODULE, "shlex", SimpleNamespace(quote=MODULE.shlex.quote)), \
                patch.object(MODULE.platform, "system", return_value="Linux"), \
                patch.object(MODULE.os, "sched_getaffinity", return_value={2, 4}, create=True), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            status = MODULE.main()
        archive = next((self.root / "results").glob("*.tar.gz"))
        output = Path(str(archive)[:-7]).resolve()
        with tarfile.open(archive) as bundle:
            names = bundle.getnames()
            self.assertIn(output.name + "/SHA256SUMS", names)
            self.assertIn(output.name + "/status.json", names)
        return status, output

    def test_balanced_sweep_exact_flags_and_successful_perf(self):
        status, output = self.run_experiment()
        self.assertEqual(status, 0)
        import csv
        with (output / "samples.csv").open() as stream:
            samples = list(csv.DictReader(stream))
        names = list(MODULE.IRONWOOD_VARIANTS) + list(MODULE.VARIANTS)
        self.assertEqual(len(samples), MODULE.DEFAULT_ROUNDS * len(names))
        for name in names:
            self.assertEqual(sorted(int(s["position"]) for s in samples if s["variant"] == name),
                             sorted(list(range(1, len(names) + 1)) * 2))
            self.assertTrue((output / "build" / name / "orderbook-bench").is_file())
        commands = [json.loads(p.read_text()) for p in (output / "logs").glob("*.json")]
        for name, flags in MODULE.VARIANTS.items():
            compilation = next(c["command"] for c in commands if name + "-compile.stdout" in c["stdout"])
            self.assertIn("--target=x86_64-unknown-linux-gnu", compilation)
            self.assertIn("-fwrapv", compilation)
            for flag in flags:
                self.assertIn(flag, compilation)
            link = next(c["command"] for c in commands if name + "-link.stdout" in c["stdout"])
            self.assertIn(str(output / "build/JavaCompat.o"), link)
            self.assertNotIn("-flto", link)
        for name, flags in MODULE.IRONWOOD_VARIANTS.items():
            link = next(c["command"] for c in commands if name + "-link.stdout" in c["stdout"])
            for flag in flags:
                self.assertIn(flag, link)
            self.assertIn(str(output / "build/ironwood/classes"), link)
        perf_commands = [c for c in commands if Path(c["command"][0]).name == "perf"]
        self.assertTrue(perf_commands)
        self.assertTrue(all(c["returncode"] == 0 for c in perf_commands))
        self.assertTrue(all(event.endswith(':u') for event in MODULE.PERF_EVENTS.split(',')))
        self.assertFalse(any(Path(c["command"][0]).name in ("sudo", "sysctl") for c in commands))
        for name in names:
            self.assertGreater((output / (name + ".perf.data")).stat().st_size, 0)
        self.assertTrue(json.loads((output / "perf-status.json").read_text())["preflight_passed"])

    def test_perf_permission_failure_stops_before_building(self):
        status, output = self.run_experiment(perf_denied=True)
        self.assertEqual(status, 1)
        self.assertFalse((output / "build").exists())
        self.assertIn("normal user", (output / "failure.txt").read_text())

    def test_unsupported_counters_fail_even_when_perf_exits_zero(self):
        status, output = self.run_experiment(perf_unsupported=True)
        self.assertEqual(status, 1)
        self.assertFalse((output / "build").exists())
        self.assertIn("perf did not count", (output / "failure.txt").read_text())

    def test_explicit_skip_perf_preserves_timing_results(self):
        status, output = self.run_experiment(perf_denied=True, skip_perf=True)
        self.assertEqual(status, 0)
        self.assertFalse(json.loads((output / "perf-status.json").read_text())["enabled"])
        self.assertFalse(list((output / "logs").glob("*-perf-*.json")))

    def test_failed_build_still_archives_logs_and_prior_executables(self):
        status, output = self.run_experiment(failure=True)
        self.assertEqual(status, 1)
        self.assertFalse(json.loads((output / "status.json").read_text())["success"])
        self.assertTrue((output / "build/cpp-baseline/orderbook-bench").is_file())
        self.assertIn("cpp-inline-1000-partial", (output / "failure.txt").read_text())


if __name__ == "__main__":
    unittest.main()
