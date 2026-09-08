#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Focused orchestration tests. These never start a VM or a compiler suite."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch


sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("platform_tests", Path(__file__).with_name("test-platforms.py"))
workflow = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workflow)


class PlatformWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ironwood-platform-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.write("scripts/platform-tests/Dockerfile", "FROM fixture\n")
        self.write("packaging/idk-environment.yml", "name: fixture\n")
        self.output = io.StringIO()
        self.enterContext(contextlib.redirect_stdout(self.output))
        self.enterContext(contextlib.redirect_stderr(self.output))
        self.enterContext(patch.object(workflow.platform, "system", return_value="Darwin"))
        self.enterContext(patch.object(workflow.platform, "machine", return_value="arm64"))

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def execute(self, *arguments):
        return workflow.execute(self.root, workflow.parser().parse_args(arguments))

    def test_requires_explicit_mode(self):
        for arguments in ([], ["--full", "--test", "Example"], ["--full", "--failed"],
                          ["--list", "--full"]):
            with self.assertRaises(SystemExit) as error:
                workflow.parser().parse_args(arguments)
            self.assertEqual(2, error.exception.code)

    def test_list_platforms_needs_no_host_tools_or_reports(self):
        with patch.object(workflow.platform, "system", return_value="Linux"), \
                patch.object(workflow.platform, "machine", return_value="x86_64"), \
                patch.object(workflow.subprocess, "run") as run, \
                patch.object(workflow.subprocess, "Popen") as start:
            self.assertEqual(0, self.execute("--list"))
            run.assert_not_called()
            start.assert_not_called()
        self.assertEqual("macos-arm64\nlinux-arm64\nlinux-x86_64\n", self.output.getvalue())
        self.assertFalse((self.root / "workspace").exists())

    def test_dry_run_has_no_side_effects_and_enables_rosetta(self):
        with patch.object(workflow.subprocess, "run") as run:
            self.execute("--setup", "--dry-run")
            self.execute("--full", "--dry-run")
            run.assert_not_called()
        self.assertFalse((self.root / "workspace").exists())
        self.assertIn("--vz-rosetta=true", self.output.getvalue())
        self.assertNotIn("tonistiigi/binfmt", self.output.getvalue())
        self.assertIn("--activate=false", self.output.getvalue())
        self.assertIn("colima-ironwood-tests", self.output.getvalue())
        self.assertIn("--platform linux/arm64", self.output.getvalue())
        self.assertIn("--platform linux/amd64", self.output.getvalue())
        self.assertIn("LANG=C.UTF-8", self.output.getvalue())
        self.assertIn("LC_ALL=C.UTF-8", self.output.getvalue())
        self.assertNotIn("FINAL PLATFORM SUMMARY", self.output.getvalue())

    def test_full_run_ends_with_all_platform_results(self):
        processes = []
        for lines, code in ((["ok - First", "PASS: 1 compiler tests"], 0),
                            (["ok - First", "not ok - Second", "FAIL: 1 test(s), 1 passed"], 1),
                            (["ok - First", "ok - Second", "PASS: 2 compiler tests"], 0)):
            process = MagicMock()
            process.__enter__.return_value = process
            process.stdout = iter(line + "\n" for line in lines)
            process.wait.return_value = code
            processes.append(process)
        # Simulate the three runs without starting Docker or a compiler suite.
        with patch.object(workflow, "verify_rosetta"), \
                patch.object(workflow.subprocess, "run"), \
                patch.object(workflow.subprocess, "Popen", side_effect=processes) as start, \
                patch.object(workflow.time, "monotonic", side_effect=[0, 62, 62, 77, 127, 127, 3671]):
            self.assertEqual(1, self.execute("--full"))
            self.assertEqual(3, start.call_count)
        output = self.output.getvalue()
        summary = output.rsplit("FINAL PLATFORM SUMMARY (full suite)", 1)[1]
        rows = [line.split() for line in summary.splitlines() if line.startswith(workflow.PLATFORMS)]
        self.assertEqual([
            ["macos-arm64", "PASS", "1", "0", "00:01:02"],
            ["linux-arm64", "FAIL", "1", "1", "00:01:05"],
            ["linux-x86_64", "PASS", "2", "0", "00:59:04"],
        ], rows)
        self.assertTrue(output.rstrip().endswith("Total test time: 01:01:11"))
        self.assertLess(output.index("When finished, stop"), output.index("FINAL PLATFORM SUMMARY"))
        report = json.loads((self.root / "workspace/platform-tests/linux-x86_64.json").read_text())
        self.assertEqual(2, report["passed_count"])

    def test_missing_linux_image_stops_before_mac_tests(self):
        with patch.object(workflow.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "docker")), \
                patch.object(workflow.subprocess, "Popen") as tests:
            with self.assertRaisesRegex(ValueError, "--setup first"):
                self.execute("--full", "--platform", "macos-arm64", "--platform", "linux-arm64")
            tests.assert_not_called()

    def test_rosetta_check_rejects_missing_or_competing_handlers(self):
        handler = "enabled\ninterpreter /mnt/lima-rosetta/rosetta\nflags: OCF\n"
        for output in ("", handler.replace("enabled", "disabled"),
                       handler.replace("OCF", "OC"),
                       handler.replace("OCF", "OC") + "disabled\ninterpreter /usr/bin/qemu-x86_64\nflags: OCF\n",
                       handler + "enabled\ninterpreter /usr/bin/qemu-x86_64\nflags: OCF\n"):
            with patch.object(workflow.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output)), \
                    patch.object(workflow.subprocess, "Popen") as tests:
                with self.assertRaisesRegex(ValueError, "Rosetta is not the active"):
                    self.execute("--full")
                tests.assert_not_called()
        with patch.object(workflow.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, handler)):
            workflow.verify_rosetta(self.root)
        self.assertIn("Rosetta active", self.output.getvalue())

    def test_failure_retry_selects_only_failed_tests(self):
        script = self.write("scripts/test.sh", "#!/bin/sh\nprintf '12:23:23.003 - 1/1 - not ok - Example\\nFAIL: 1 test(s), 0 passed\\n'\nexit 1\n")
        script.chmod(0o755)
        self.assertEqual(1, self.execute("--platform", "macos-arm64", "--test", "Example"))
        report = self.root / "workspace/platform-tests/macos-arm64.json"
        self.assertEqual(["Example"], json.loads(report.read_text())["failed_tests"])
        script.write_text("#!/bin/sh\ntest \"$1\" = --test && test \"$2\" = Example || exit 7\nprintf '12:23:23.003 - 1/1 - ok - Example\\nPASS: 1 compiler tests\\n'\n")
        self.assertEqual(0, self.execute("--platform", "macos-arm64", "--failed"))
        result = json.loads(report.read_text())
        self.assertEqual("selected", result["mode"])
        self.assertEqual(["Example"], result["tests"])
        self.assertEqual([], result["failed_tests"])
        self.assertTrue(result["completed"])
        with patch.object(workflow.subprocess, "Popen") as tests:
            self.assertEqual(0, self.execute("--platform", "macos-arm64", "--failed"))
            tests.assert_not_called()
        summary = self.output.getvalue().rsplit("FINAL PLATFORM SUMMARY (retried tests)", 1)[1]
        self.assertRegex(summary, r"macos-arm64\s+SKIPPED\s+-\s+-\s+-")
        self.assertNotIn("linux-", summary)

    def test_setup_failure_does_not_fall_back_to_full_suite(self):
        script = self.write("scripts/test.sh", "#!/bin/sh\nexit 3\n")
        script.chmod(0o755)
        self.assertEqual(1, self.execute("--platform", "macos-arm64", "--test", "Example"))
        self.assertRegex(self.output.getvalue(), r"macos-arm64\s+INCOMPLETE\s+0\s+0")
        with self.assertRaisesRegex(ValueError, "before individual test failures"):
            self.execute("--platform", "macos-arm64", "--failed")

    def test_interrupt_saves_failures_and_removes_only_its_container(self):
        report = self.write("workspace/platform-tests/linux-arm64.json",
                            json.dumps({"exit_code": 0, "failed_tests": []}))

        def lines():
            initial = json.loads(report.read_text())
            self.assertIsNone(initial["exit_code"])
            self.assertFalse(initial["completed"])
            yield "12:23:23.003 - 1/2 - not ok - Example\n"
            self.assertEqual(["Example"], json.loads(report.read_text())["failed_tests"])
            raise KeyboardInterrupt

        process = MagicMock()
        process.__enter__.return_value = process
        process.stdout = lines()
        process.pid = 12345
        process.wait.return_value = 130
        with patch.object(workflow.subprocess, "run") as run, \
                patch.object(workflow.subprocess, "Popen", return_value=process) as start, \
                patch.object(workflow.os, "killpg") as terminate:
            self.assertEqual(130, self.execute("--platform", "linux-arm64", "--platform", "macos-arm64",
                                              "--test", "Example"))
            start.assert_called_once()
            command = start.call_args.args[0]
            container = command[command.index("--name") + 1]
            self.assertTrue(container.startswith("ironwood-test-"))
            self.assertEqual([*workflow.DOCKER, "rm", "--force", container], run.call_args.args[0])
            terminate.assert_called_once_with(12345, workflow.signal.SIGTERM)
            self.assertTrue(start.call_args.kwargs["start_new_session"])
        result = json.loads(report.read_text())
        self.assertEqual(130, result["exit_code"])
        self.assertFalse(result["completed"])
        self.assertEqual(["Example"], result["failed_tests"])
        args = workflow.parser().parse_args(["--failed"])
        self.assertEqual(["Example"], workflow.selected_names(report, args))
        self.assertIn("Unrun tests remain unverified", self.output.getvalue())
        summary = self.output.getvalue().rsplit("FINAL PLATFORM SUMMARY (selected tests)", 1)[1]
        self.assertRegex(summary, r"linux-arm64\s+INTERRUPTED\s+0\s+1")
        self.assertRegex(summary, r"macos-arm64\s+NOT RUN\s+-\s+-\s+-")

    def test_legacy_failure_lines_are_still_recognized(self):
        script = self.write("scripts/test.sh", "#!/bin/sh\nprintf 'not ok - Example\\nFAIL: 1 test(s), 0 passed\\n'\nexit 1\n")
        script.chmod(0o755)
        self.assertEqual(1, self.execute("--platform", "macos-arm64", "--test", "Example"))
        report = self.root / "workspace/platform-tests/macos-arm64.json"
        self.assertEqual(["Example"], json.loads(report.read_text())["failed_tests"])


if __name__ == "__main__":
    unittest.main()
