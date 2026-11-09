#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Check stdlib SDK selection with isolated commands, without native compilation."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


RUNNER = Path(__file__).with_name("test-stdlib.sh").read_text()


class StdlibWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ironwood-stdlib-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve() / "source tree"
        self.write("scripts/test-stdlib.sh", RUNNER)
        self.write("fake/bin/uname", '#!/bin/sh\nprintf "%s\\n" "$FIXTURE_HOST"\n', executable=True)
        stop = '#!/bin/sh\nprintf "%s" "$IRONWOOD_GC_HOME" > "$FIXTURE_SELECTION"\nexit 23\n'
        self.write("fake/bin/java", stop, executable=True)
        self.write("scripts/build.sh", stop, executable=True)
        self.selection = self.root / "selection"
        self.env = os.environ | {"PATH": str(self.root / "fake/bin") + os.pathsep + os.environ["PATH"],
                                 "FIXTURE_HOST": "Darwin arm64", "FIXTURE_SELECTION": str(self.selection)}
        self.env.pop("IRONWOOD_GC_HOME", None)

    def write(self, name, text, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        if executable:
            path.chmod(0o755)
        return path

    def sdk(self, relative):
        return self.write(relative + "/build.properties", "format=1\n").parent

    def run_script(self, *arguments):
        return subprocess.run(["bash", str(self.root / "scripts/test-stdlib.sh"), *arguments],
                              cwd=self.root.parent, env=self.env, capture_output=True, text=True, timeout=10)

    def assert_selected(self, expected, *arguments):
        result = self.run_script(*arguments)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(str(expected), self.selection.read_text())

    def test_explicit_sdk_wins_over_bundle_and_cache(self):
        explicit = self.sdk("explicit SDK")
        self.sdk("toolchain/ironwood-gc")
        self.sdk("workspace/platform-tests/gc/macos-arm64")
        self.env["IRONWOOD_GC_HOME"] = str(explicit)
        self.assert_selected(explicit, "--skip-build")

    def test_bundle_wins_over_cache(self):
        bundled = self.sdk("toolchain/ironwood-gc")
        self.sdk("workspace/platform-tests/gc/macos-arm64")
        self.assert_selected(bundled, "--skip-build")

    def test_host_cache_is_exported_with_and_without_build(self):
        for host, target in (("Darwin arm64", "macos-arm64"), ("Linux aarch64", "linux-arm64"),
                             ("Linux arm64", "linux-arm64"), ("Linux x86_64", "linux-x86_64")):
            for arguments in ((), ("--skip-build",)):
                with self.subTest(host=host, arguments=arguments):
                    self.env["FIXTURE_HOST"] = host
                    self.assert_selected(self.sdk("workspace/platform-tests/gc/" + target), *arguments)

    def test_missing_sdk_reports_explicit_setup_before_build(self):
        result = self.run_script()
        self.assertEqual(1, result.returncode)
        self.assertIn("no prepared GC SDK", result.stderr)
        self.assertIn("prepare-gc.py", result.stderr)
        self.assertIn('--llvm-home "$IRONWOOD_LLVM_HOME"', result.stderr)
        self.assertIn("workspace/platform-tests/gc/macos-arm64", result.stderr)
        self.assertFalse(self.selection.exists())

    def test_invalid_explicit_sdk_cannot_fall_back(self):
        self.sdk("workspace/platform-tests/gc/macos-arm64")
        for override in ("", str(self.root / "missing SDK")):
            with self.subTest(override=override):
                self.env["IRONWOOD_GC_HOME"] = override
                result = self.run_script("--skip-build")
                self.assertEqual(1, result.returncode)
                self.assertIn("error:", result.stderr)
                self.assertFalse(self.selection.exists())

    def test_incomplete_bundle_cannot_fall_back(self):
        (self.root / "toolchain/ironwood-gc").mkdir(parents=True)
        self.sdk("workspace/platform-tests/gc/macos-arm64")
        result = self.run_script("--skip-build")
        self.assertEqual(1, result.returncode)
        self.assertIn("toolchain/ironwood-gc", result.stderr)
        self.assertFalse(self.selection.exists())

    def test_unsupported_host_cannot_select_another_target(self):
        self.sdk("workspace/platform-tests/gc/macos-arm64")
        self.env["FIXTURE_HOST"] = "Darwin x86_64"
        result = self.run_script("--skip-build")
        self.assertEqual(1, result.returncode)
        self.assertIn("unsupported stdlib test host", result.stderr)
        self.assertFalse(self.selection.exists())


if __name__ == "__main__":
    unittest.main()
