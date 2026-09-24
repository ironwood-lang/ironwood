#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Controlled failure tests for the M1a off/off comparison harness."""

import importlib.util
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("compare-explain-rejected-free.py")
SPEC = importlib.util.spec_from_file_location("explain_parity", SCRIPT)
PARITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PARITY)


def result(status, stdout=b"", stderr=b""):
    return types.SimpleNamespace(returncode=status, stdout=stdout, stderr=stderr)


class ComparisonHarnessTests(unittest.TestCase):
    def test_expected_rejection_is_not_a_tool_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            PARITY.validate_compile(result(1, stderr=b"error: cannot free 'value'\n"),
                                    "rejected", "^error: cannot free", output)
            for failure in (result(0), result(2, stderr=b"usage: ironwoodc\n"),
                            result(1, stderr=b"Exception in thread main\n"),
                            result(1, stderr=b"error: native link failed\n"),
                            result(1, stderr=b"error: unrelated parse failure\n")):
                with self.assertRaises(PARITY.ComparisonError):
                    PARITY.validate_compile(failure, "rejected", "^error: cannot free", output)
            (output / "stale.ironclass").write_bytes(b"bad")
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.validate_compile(result(1, stderr=b"error: cannot free 'value'\n"),
                                        "rejected", "^error: cannot free", output)

    def test_timeout_and_missing_executable_are_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.invoke(["/definitely/missing/ironwood-tool"], directory, {}, 1,
                              "missing", directory / "missing")
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.invoke(["python3", "-c", "import time; time.sleep(2)"], directory,
                              None, 0.01, "timeout", directory / "timeout")

    def test_primary_and_path_mapping_differences_fail(self):
        left = result(1, stderr=b"error: cannot free\n  --> /input/A.iron:4:5\n")
        right = result(1, stderr=b"error: cannot free\n  --> /input/A.iron:4:6\n")
        with self.assertRaises(PARITY.ComparisonError):
            PARITY.compare_processes(left, right, {}, {}, "primary")
        self.assertEqual("<output>/Main.ironclass\n",
                         PARITY.normalize_output(b"/base/output/Main.ironclass\n",
                                                 {"/base/output": "<output>"}))
        self.assertEqual("/input/A.iron:4:5\n",
                         PARITY.normalize_output(b"/input/A.iron:4:5\n",
                                                 {"/base/output": "<output>"}))
        with self.assertRaises(PARITY.ComparisonError):
            PARITY.compare_processes(result(0, stdout=b"line\n"),
                                     result(0, stdout=b"line"), {}, {}, "newline")

    def test_artifact_inventory_and_exact_bytes_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            left = directory / "left"
            right = directory / "right"
            left.mkdir()
            right.mkdir()
            (left / "Main.ironclass").write_bytes(b"same")
            (right / "Main.ironclass").write_bytes(b"same")
            PARITY.compare_files(left, right, "classes")
            (right / "Extra.ironclass").write_bytes(b"extra")
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.compare_files(left, right, "classes")
            (right / "Extra.ironclass").unlink()
            (right / "Main.ironclass").write_bytes(b"changed")
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.compare_files(left, right, "classes")
            (left / "fixture.ll").write_bytes(b"define i32 @main() { ret i32 0 }\n")
            (right / "fixture.ll").write_bytes(b"define i32 @main() { ret i32 1 }\n")
            with self.assertRaises(PARITY.ComparisonError):
                PARITY.compare_bytes(left / "fixture.ll", right / "fixture.ll", "LLVM")

    def test_wrong_standard_library_archive_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "compiler/build/ironwood-stdlib.ironjar"
            archive.parent.mkdir(parents=True)
            archive.write_bytes(b"control")
            wrong = root / "other/ironwood-stdlib.ironjar"
            with mock.patch.object(PARITY, "invoke", return_value=result(
                    0, stdout=f"ironwood.lang.Object\t{wrong}!/source/Object.iron\n".encode())):
                with self.assertRaisesRegex(PARITY.ComparisonError,
                                            "outside expected archive"):
                    PARITY.verify_discovery(root / "ironwoodc.jar", root, root,
                                            root, ("ironwood.lang.Object",), {},
                                            root / "probe-log")


if __name__ == "__main__":
    unittest.main()
