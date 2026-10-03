#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Offline checks for every official throughput wrapper using subprocess fixtures."""

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
WRAPPERS = (
    ("official-throughput.sh", "throughput.sh"),
    ("java/official-throughput.sh", "throughput.sh"),
    ("java/official-throughput-native-image.sh", "throughput-native-image.sh"),
    ("cpp/official-throughput.sh", "throughput.sh"),
    ("java-bridge/official-throughput.sh", "throughput.sh"),
)


class OfficialThroughputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="orderbook official throughput ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def fixture(self, wrapper, driver, body="print([30, 10, 20][count % 3])"):
        folder = self.root / wrapper.replace("/", " ")
        folder.mkdir(exist_ok=True)
        script = folder / Path(wrapper).name
        shutil.copy2(ROOT / wrapper, script)
        stub = folder / driver
        stub.write_text("#!" + sys.executable + "\n"
                        "from pathlib import Path\nimport sys\n"
                        "log = Path('runs.txt')\n"
                        "count = len(log.read_text().splitlines()) if log.exists() else 0\n"
                        "with log.open('a') as output:\n"
                        "    output.write(' '.join(sys.argv[1:]) + '\\n')\n" + body + "\n")
        stub.chmod(0o755)
        return script, folder / "runs.txt"

    def run_wrapper(self, script, *arguments):
        return subprocess.run([str(script)] + list(arguments), cwd=self.root,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True)

    def test_custom_counts_median_and_external_working_directory(self):
        for wrapper, driver in WRAPPERS:
            with self.subTest(wrapper=wrapper):
                script, log = self.fixture(wrapper, driver)
                result = self.run_wrapper(script, "3", "0", "1")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "20\n")
                self.assertEqual(log.read_text().splitlines(), ["0 1"] * 3)
                self.assertEqual(result.stderr.splitlines(),
                                 ["run 1/3: 30 ns", "run 2/3: 10 ns", "run 3/3: 20 ns"])

    def test_default_protocol(self):
        for wrapper, driver in WRAPPERS:
            with self.subTest(wrapper=wrapper):
                script, log = self.fixture(wrapper, driver)
                result = self.run_wrapper(script)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "20\n")
                self.assertEqual(log.read_text().splitlines(), ["8 80"] * 31)
                self.assertEqual(len(result.stderr.splitlines()), 31)

    def test_invalid_run_count_never_starts_benchmark(self):
        for wrapper, driver in WRAPPERS:
            script, log = self.fixture(wrapper, driver)
            for runs in ("0", "2", "-1", "invalid"):
                with self.subTest(wrapper=wrapper, runs=runs):
                    result = self.run_wrapper(script, runs)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("positive odd number", result.stderr)
                    self.assertEqual(result.stdout, "")
                    self.assertFalse(log.exists())

    def test_invalid_timing_or_failed_benchmark_never_reports_median(self):
        for wrapper, driver in WRAPPERS:
            for body in ("print(0)", "print(-1)", "print('bad')", "print('1\\n2')", "sys.exit(7)"):
                with self.subTest(wrapper=wrapper, body=body):
                    script, log = self.fixture(wrapper, driver, body)
                    result = self.run_wrapper(script, "3", "0", "1")
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(result.stdout, "")
                    self.assertEqual(log.read_text().splitlines(), ["0 1"])
                    log.unlink()


if __name__ == "__main__":
    unittest.main()
