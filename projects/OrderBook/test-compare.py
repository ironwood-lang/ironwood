#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Small subprocess fixtures for compare.py, including retained failure evidence."""

import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).resolve().with_name("compare.py")


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="orderbook compare ")
        self.root = Path(self.temporary.name)
        self.baseline = self.fixture("baseline binary", "print(100)")
        self.candidate = self.fixture("candidate binary", "print(90)")
        self.output = self.root / "new results"

    def tearDown(self):
        self.temporary.cleanup()

    def fixture(self, name, body):
        path = self.root / name
        path.write_text("#!" + sys.executable + "\n" + body + "\n")
        path.chmod(0o755)
        return path

    def run_comparison(self, *options):
        return subprocess.run([sys.executable, str(RUNNER), str(self.baseline),
                               str(self.candidate), "--output", str(self.output),
                               "--pairs", "2"] + list(options),
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True)

    def test_alternating_reversed_paths_arguments_and_summary(self):
        self.fixture("baseline binary", "import sys\nassert sys.argv[1:] == ['0', '1']\nprint(100)")
        result = self.run_comparison("--warmup", "0", "--measured", "1", "--first", "candidate")
        self.assertEqual(result.returncode, 0, result.stderr)
        rows = [json.loads(line) for line in (self.output / "samples.jsonl").read_text().splitlines()]
        self.assertEqual([row["variant"] for row in rows], ["candidate", "baseline", "baseline", "candidate"])
        summary = json.loads((self.output / "summary.json").read_text())
        self.assertAlmostEqual(summary["metrics"]["elapsed_ns"]["median_change_percent"], -10)
        self.assertEqual(summary["outliers_removed"], 0)
        self.assertEqual(len(list(self.output.glob("*.stdout"))), 4)
        metadata = json.loads((self.output / "metadata.json").read_text())
        for variant, binary in [("baseline", self.baseline), ("candidate", self.candidate)]:
            self.assertEqual(metadata["binaries"][variant]["sha256"],
                             hashlib.sha256(binary.read_bytes()).hexdigest())
        original = (self.output / "summary.json").read_bytes()
        self.assertNotEqual(self.run_comparison().returncode, 0)
        self.assertEqual((self.output / "summary.json").read_bytes(), original)

    def test_invalid_duration_and_subprocess_failure_preserve_output(self):
        cases = [("print('bad')", "bad\n"), ("print(0)", "0\n"),
                 ("print(-1)", "-1\n"), ("print('1.5')", "1.5\n"),
                 ("print('100\\nextra')", "100\nextra\n"), ("pass", ""),
                 ("import sys\nprint(100)\nprint('failure', file=sys.stderr)\nsys.exit(7)", "100\n")]
        for index, (body, expected) in enumerate(cases):
            with self.subTest(body=body):
                self.candidate = self.fixture("bad binary", body)
                self.output = self.root / str(index)
                result = self.run_comparison()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((self.output / "001-candidate.stdout").read_text(), expected)
                self.assertTrue((self.output / "failure.json").is_file())
                self.assertFalse((self.output / "summary.json").exists())
                rows = (self.output / "samples.jsonl").read_text().splitlines()
                self.assertEqual(len(rows), 2)
                self.assertIn("error", json.loads(rows[-1]))

    def test_timeout_and_missing_executable(self):
        self.candidate = self.fixture("slow binary", "import time\nprint(100, flush=True)\ntime.sleep(5)")
        self.assertNotEqual(self.run_comparison("--timeout", "1").returncode, 0)
        self.assertEqual((self.output / "001-candidate.stdout").read_text(), "100\n")
        self.output = self.root / "missing results"
        self.candidate = self.root / "missing binary"
        self.assertNotEqual(self.run_comparison().returncode, 0)
        self.assertFalse(self.output.exists())

    def test_argument_validation(self):
        for options in [("--pairs", "0"), ("--pairs", "-1"), ("--measured", "0"),
                        ("--warmup", "2.5"), ("--cycles", "1"), ("--timeout", "0"),
                        ("--measured", "2147483648"), ("--mode", "latency", "--cycles", "0"),
                        ("--mode", "latency", "--warmup", "2147483647")]:
            with self.subTest(options=options):
                self.assertNotEqual(self.run_comparison(*options).returncode, 0)
                self.assertFalse(self.output.exists())

    def test_latency_counts_units_and_zero_minimum(self):
        report = ("Cycles per batch: 10\nOperations per batch: 80\nMeasured operations: 400\n"
                  "Measurements: 5 | Warm-Up: 2 | Iterations: 7\n"
                  "Avg Time: 1.200 micros | Min Time: 0.000 nano | Max Time: 2.000 micros\n")
        self.baseline = self.fixture("latency baseline", "print(" + repr(report) + ")")
        self.candidate = self.baseline
        options = ("--mode", "latency", "--warmup", "2", "--measured", "5", "--cycles", "10")
        result = self.run_comparison(*options)
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads((self.output / "summary.json").read_text())
        self.assertEqual(summary["metrics"]["average_ns"]["baseline"]["median"], 1200)
        self.assertIsNone(summary["metrics"]["minimum_ns"]["median_change_percent"])
        self.assertEqual(summary["metrics"]["minimum_ns"]["paired_change_percent"], [None, None])
        self.fixture("latency baseline", "print(" + repr(report.replace("Measurements: 5", "Measurements: 6")) + ")")
        self.output = self.root / "bad counts"
        self.assertNotEqual(self.run_comparison(*options).returncode, 0)
        self.assertTrue((self.output / "failure.json").exists())


if __name__ == "__main__":
    unittest.main()
