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
MAC_ENVIRONMENT = workflow.mac_environment
TLS_SPEC = importlib.util.spec_from_file_location("tls_build_tests", Path(__file__).with_name("test-networking-m5-build.py"))
tls_build = importlib.util.module_from_spec(TLS_SPEC)
TLS_SPEC.loader.exec_module(tls_build)


class PlatformWorkflowTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ironwood-platform-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.write("scripts/platform-tests/Dockerfile", "FROM fixture\n")
        self.write("packaging/idk-environment.yml", "name: fixture\n")
        self.write("scripts/prepare-tls.py", "# fixture recipe\n")
        self.write("packaging/tls-dependencies.properties", "openssl.version=fixture\n")
        self.write("packaging/bdwgc-qualified.properties",
                   f"macos-arm64.configuration.sha256={'d' * 64}\n"
                   f"linux-arm64.configuration.sha256={'a' * 64}\n"
                   f"linux-x86_64.configuration.sha256={'b' * 64}\n"
                   f"linux-x86_64.native-host.configuration.sha256={'c' * 64}\n")
        self.write("LICENSES/MPL-2.0.txt", "fixture license\n")
        self.output = io.StringIO()
        self.enterContext(contextlib.redirect_stdout(self.output))
        self.enterContext(contextlib.redirect_stderr(self.output))
        self.enterContext(patch.object(workflow.platform, "system", return_value="Darwin"))
        self.enterContext(patch.object(workflow.platform, "machine", return_value="arm64"))
        self.enterContext(patch.dict(workflow.os.environ))
        workflow.os.environ.pop("IRONWOOD_GC_HOME", None)
        self.enterContext(patch.object(workflow, "mac_environment", side_effect=lambda root, dry_run=False: {
            "IRONWOOD_GC_HOME": str(workflow.gc_sdk(root, "macos-arm64")),
            "IRONWOOD_LLVM_HOME": "/fixture/llvm", "SDKROOT": "/fixture/apple-sdk"}))

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def execute(self, *arguments):
        return workflow.execute(self.root, workflow.parser().parse_args(arguments))

    def prepare_bridge_support(self, *targets):
        for target in targets:
            self.write(f"workspace/java-bridge/support/{target}/build.properties", f"platform={target}\n")

    def prepare_gc_sdk(self, *targets, identity=None):
        for target in targets:
            value = identity or {"macos-arm64": "d", "linux-arm64": "a", "linux-x86_64": "b"}[target] * 64
            self.write(f"workspace/platform-tests/gc/{target}/build.json",
                       json.dumps({"gc.config.sha256": value}))

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
        for target in ("linux-arm64", "linux-x86_64"):
            self.assertIn(f"scripts/prepare-java-bridge-support.py --setup --target {target} "
                          f"--prefix {self.root / 'workspace/java-bridge/support' / target}", self.output.getvalue())
            self.assertIn(f"scripts/prepare-gc.py --prefix {self.root / 'workspace/platform-tests/gc' / target} "
                          f"--llvm-home /opt/ironwood-toolchain --target {target}", self.output.getvalue())
            self.assertIn(f"IRONWOOD_GC_HOME={self.root / 'workspace/platform-tests/gc' / target}",
                          self.output.getvalue())
        self.assertNotIn("FINAL PLATFORM SUMMARY", self.output.getvalue())

    def test_full_run_ends_with_all_platform_results(self):
        self.prepare_gc_sdk("macos-arm64")
        processes = []
        for lines, code in ((["ok - First", "PASS: 1 compiler tests"], 0),
                            (["ok - First", "not ok - Second", "FAIL: 1 test(s), 1 passed"], 1),
                            (["ok - First", "ok - Second", "PASS: 2 compiler tests"], 0)):
            process = MagicMock()
            process.__enter__.return_value = process
            process.stdout = iter(line + "\n" for line in lines)
            process.wait.return_value = code
            processes.append(process)
        self.prepare_bridge_support("linux-arm64", "linux-x86_64")
        self.prepare_gc_sdk("linux-arm64", "linux-x86_64")
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

    def test_linux_commands_select_image_sdk_and_isolate_native_outputs(self):
        with patch.dict(workflow.os.environ, {"IRONWOOD_TLS_HOME": "/host/macos-sdk",
                                             "IRONWOOD_GC_HOME": "/host/macos-gc-sdk"}):
            for target in ("linux-arm64", "linux-x86_64"):
                command = workflow.test_command(self.root, target, ["TLS example"])
                self.assertIn("IRONWOOD_TLS_HOME=/opt/ironwood-tls", command)
                self.assertIn(f"IRONWOOD_BRIDGE_SUPPORT_HOME={self.root / 'workspace/java-bridge/support' / target}", command)
                self.assertIn(f"IRONWOOD_GC_HOME={self.root / 'workspace/platform-tests/gc' / target}", command)
                self.assertNotIn("/host/macos-sdk", " ".join(command))
                self.assertNotIn("/host/macos-gc-sdk", " ".join(command))
                for directory, destination in (("build", "compiler/build"),
                                                ("integration-target", "integration-tests/target"),
                                                ("stdlib-target", "stdlib/test/target")):
                    self.assertIn(f"type=bind,source={self.root / 'workspace/platform-tests' / directory / target},"
                                  f"target={self.root / destination}", command)

    def test_tls_inputs_invalidate_cached_images_and_enter_setup_context(self):
        previous = workflow.image(self.root, "linux-arm64")
        for name in ("scripts/prepare-tls.py", "packaging/tls-dependencies.properties", "LICENSES/MPL-2.0.txt"):
            self.write(name, (self.root / name).read_text() + "changed\n")
            current = workflow.image(self.root, "linux-arm64")
            self.assertNotEqual(previous, current)
            previous = current
        self.write("unrelated-source.iron", "not an image input")
        self.prepare_gc_sdk("linux-arm64")
        def inspect(command, root, *args):
            if "build" not in command: return
            context = Path(command[-1])
            expected = {"Dockerfile", *workflow.IMAGE_INPUTS[1:]}
            self.assertEqual(expected, {p.relative_to(context).as_posix() for p in context.rglob('*') if p.is_file()})
            for name in workflow.IMAGE_INPUTS[1:]:
                self.assertEqual((self.root / name).read_bytes(), (context / name).read_bytes())
        with patch.object(workflow, "run", side_effect=inspect):
            self.assertEqual(0, self.execute("--setup", "--platform", "linux-arm64"))

    def test_tls_wrapper_replaces_stale_links_without_touching_original_tools(self):
        first = self.write("old/bin/clang", "#!/bin/sh\nprintf old\n")
        second = self.write("new/bin/clang", "#!/bin/sh\nprintf new\n")
        first.chmod(0o755)
        second.chmod(0o755)
        self.write("old/share/identity", "old")
        self.write("new/share/identity", "new")
        wrapper, trace = self.root / "wrapper", self.root / "trace.jsonl"
        tls_build.prepare_toolchain_wrapper(self.root / "old", wrapper, trace)
        (wrapper / "share").unlink()
        (wrapper / "share").symlink_to(self.root / "absent")
        (wrapper / "bin/clang").unlink()
        (wrapper / "bin/clang").symlink_to(first)
        tls_build.prepare_toolchain_wrapper(self.root / "new", wrapper, trace)
        result = subprocess.run([wrapper / "bin/clang", "--version"], capture_output=True, text=True, check=True)
        self.assertEqual("new", result.stdout)
        self.assertEqual(["--version"], json.loads(trace.read_text()))
        self.assertEqual("new", (wrapper / "share/identity").read_text())
        self.assertEqual("#!/bin/sh\nprintf old\n", first.read_text())

    def test_missing_linux_image_stops_before_mac_tests(self):
        with patch.object(workflow.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "docker")), \
                patch.object(workflow.subprocess, "Popen") as tests:
            with self.assertRaisesRegex(ValueError, "--setup first"):
                self.execute("--full", "--platform", "macos-arm64", "--platform", "linux-arm64")
            tests.assert_not_called()

    def test_missing_bridge_support_stops_before_tests_and_setup_verifies_existing_sdk(self):
        with patch.object(workflow.subprocess, "run"), patch.object(workflow.subprocess, "Popen") as tests:
            with self.assertRaisesRegex(ValueError, "Java Bridge support SDK is missing.*--setup first"):
                self.execute("--full", "--platform", "linux-arm64", "--platform", "macos-arm64")
            tests.assert_not_called()
        self.prepare_bridge_support("linux-arm64")
        self.prepare_gc_sdk("linux-arm64")
        with patch.object(workflow, "run") as run:
            self.assertEqual(0, self.execute("--setup", "--platform", "linux-arm64"))
        commands = [call.args[0] for call in run.call_args_list]
        prepared = [command for command in commands if "scripts/prepare-java-bridge-support.py" in command]
        self.assertEqual(1, len(prepared))
        self.assertIn("--check", prepared[0])
        self.assertNotIn("--setup", prepared[0])
        self.assertEqual(str(self.root / "workspace/java-bridge/support/linux-arm64"), prepared[0][-1])
        self.assertIn(workflow.image(self.root, "linux-arm64"), prepared[0])
        def fail_preparation(command, root, *args, **kwargs):
            if "scripts/prepare-java-bridge-support.py" in command:
                raise subprocess.CalledProcessError(1, command)
        with patch.object(workflow, "run", side_effect=fail_preparation):
            with self.assertRaisesRegex(ValueError, "support SDK preparation failed.*rerun"):
                self.execute("--setup", "--platform", "linux-arm64")

    def test_setup_prepares_target_gc_sdk_and_verifies_existing_inputs(self):
        for target, arch in (("linux-arm64", "arm64"), ("linux-x86_64", "amd64")):
            with self.subTest(target=target), patch.object(workflow, "verify_rosetta"):
                def prepare(command, root, *args):
                    if "scripts/prepare-gc.py" in command:
                        self.prepare_gc_sdk(target)
                with patch.object(workflow, "run", side_effect=prepare) as run:
                    self.assertEqual(0, self.execute("--setup", "--platform", target))
                command = next(call.args[0] for call in run.call_args_list
                               if "scripts/prepare-gc.py" in call.args[0])
                self.assertIn("linux/" + arch, command)
                self.assertEqual(target, command[command.index("--target") + 1])
                self.assertEqual(str(workflow.gc_sdk(self.root, target)), command[command.index("--prefix") + 1])
                self.assertNotIn("--verify", command)
                with patch.object(workflow, "run") as run:
                    self.assertEqual(0, self.execute("--setup", "--platform", target))
                command = next(call.args[0] for call in run.call_args_list
                               if "scripts/prepare-gc.py" in call.args[0])
                self.assertIn("--verify", command)

    def test_missing_gc_sdk_stops_before_any_platform_tests(self):
        self.prepare_bridge_support("linux-arm64")
        with patch.object(workflow.subprocess, "run"), patch.object(workflow.subprocess, "Popen") as start:
            with self.assertRaisesRegex(ValueError, "GC SDK is missing.*--setup first"):
                self.execute("--test", "Example", "--platform", "macos-arm64", "--platform", "linux-arm64")
            start.assert_not_called()
        self.assertFalse((self.root / "workspace/platform-tests/macos-arm64.json").exists())

    def test_stale_gc_sdk_refuses_before_any_platform_and_preserves_inputs(self):
        self.prepare_bridge_support("linux-arm64")
        self.prepare_gc_sdk("linux-arm64")
        manifest = workflow.gc_sdk(self.root, "linux-arm64") / "build.json"
        original = manifest.read_bytes()
        def refuse(command, root, *args):
            if "scripts/prepare-gc.py" in command:
                self.assertIn("--verify", command)
                raise subprocess.CalledProcessError(1, command)
        with patch.object(workflow.subprocess, "run"), \
                patch.object(workflow, "run", side_effect=refuse), \
                patch.object(workflow.subprocess, "Popen") as start:
            with self.assertRaisesRegex(ValueError, "GC SDK preparation/verification failed.*--setup"):
                self.execute("--test", "Example", "--platform", "macos-arm64", "--platform", "linux-arm64")
            start.assert_not_called()
        self.assertEqual(original, manifest.read_bytes())

    def test_gc_sdk_profile_must_match_admitted_target(self):
        self.prepare_bridge_support("linux-arm64")
        for identity in ("b" * 64, "d" * 64, "invalid"):
            self.prepare_gc_sdk("linux-arm64", identity=identity)
            with self.subTest(identity=identity), patch.object(workflow.subprocess, "run"), \
                    patch.object(workflow.subprocess, "Popen") as start:
                with self.assertRaisesRegex(ValueError, "GC SDK profile is not qualified"):
                    self.execute("--test", "Example", "--platform", "linux-arm64")
                start.assert_not_called()
        for target, identity in (("linux-arm64", "a" * 64), ("linux-x86_64", "b" * 64),
                                 ("linux-x86_64", "c" * 64)):
            self.prepare_gc_sdk(target, identity=identity)
            with patch.object(workflow, "run") as run:
                workflow.prepare_gc_sdk(self.root, target, False, verify=True)
            self.assertIn("--verify", run.call_args.args[0])

    def test_mac_setup_prepares_sdk_and_selects_it_for_tests(self):
        def prepare(command, root, *args):
            if "scripts/prepare-gc.py" in command:
                self.prepare_gc_sdk("macos-arm64")
        with patch.object(workflow, "run", side_effect=prepare) as run:
            self.execute("--setup", "--platform", "macos-arm64")
        command = run.call_args.args[0]
        self.assertEqual("env", command[0])
        self.assertNotIn("docker", command)
        self.assertIn("--target", command)
        self.assertNotIn("--verify", command)
        with patch.object(workflow, "run") as run:
            self.execute("--setup", "--platform", "macos-arm64")
        self.assertIn("--verify", run.call_args.args[0])
        command = workflow.test_command(self.root, "macos-arm64", ["Example"])
        self.assertIn("IRONWOOD_GC_HOME=" + str(workflow.gc_sdk(self.root, "macos-arm64")), command)
        self.assertIn("SDKROOT=/fixture/apple-sdk", command)
        self.assertIn("IRONWOOD_LLVM_HOME=/fixture/llvm", command)
        self.assertEqual(["--test", "Example"], command[-2:])

    def test_missing_mac_sdk_stops_before_tests(self):
        with patch.object(workflow.subprocess, "Popen") as start:
            with self.assertRaisesRegex(ValueError, "macos-arm64: GC SDK is missing.*--setup first"):
                self.execute("--platform", "macos-arm64", "--test", "Example")
            start.assert_not_called()

    def test_mac_sdk_override_and_unqualified_profile_fail_closed(self):
        prefix = self.root / "custom-sdk"
        with patch.dict(workflow.os.environ, {"IRONWOOD_GC_HOME": str(prefix)}):
            self.assertEqual(prefix, workflow.gc_sdk(self.root, "macos-arm64"))
            self.assertNotEqual(prefix, workflow.gc_sdk(self.root, "linux-arm64"))
            self.write("custom-sdk/build.json", json.dumps({"gc.config.sha256": "a" * 64}))
            with patch.object(workflow, "run"), patch.object(workflow.subprocess, "Popen") as start:
                with self.assertRaisesRegex(ValueError, "macos-arm64: GC SDK profile is not qualified"):
                    self.execute("--platform", "macos-arm64", "--test", "Example")
                start.assert_not_called()

    def test_mac_apple_sdk_selection_honors_explicit_inputs(self):
        import hashlib
        sdk = self.write("apple-sdk/SDKSettings.json", '{"Version":"fixture"}').parent
        settings = hashlib.sha256((sdk / "SDKSettings.json").read_bytes()).hexdigest()
        self.write("packaging/bdwgc-qualified.properties", "macos-arm64.sdk.settings.sha256=" + settings + "\n")
        with patch.dict(workflow.os.environ, {"SDKROOT": str(sdk), "IRONWOOD_LLVM_HOME": "/explicit/llvm"}), \
                patch.object(workflow.subprocess, "check_output") as discover:
            environment = MAC_ENVIRONMENT(self.root)
            self.assertEqual(str(sdk), environment["SDKROOT"])
            self.assertEqual("/explicit/llvm", environment["IRONWOOD_LLVM_HOME"])
            discover.assert_not_called()
            (sdk / "SDKSettings.json").write_text("changed")
            with self.assertRaisesRegex(ValueError, "no qualified Apple SDK"):
                MAC_ENVIRONMENT(self.root)
        with patch.dict(workflow.os.environ, {"DEVELOPER_DIR": "/explicit/developer"}):
            workflow.os.environ.pop("SDKROOT", None)
            with patch.object(workflow.subprocess, "check_output", return_value=str(sdk)):
                with self.assertRaisesRegex(ValueError, "no qualified Apple SDK"):
                    MAC_ENVIRONMENT(self.root)

    def test_mac_discovers_installed_qualified_sdk_without_changing_host_settings(self):
        import hashlib
        clt = self.write("clt/SDKSettings.json", "unqualified").parent
        xcode = self.write("xcode/SDKSettings.json", "qualified").parent
        settings = hashlib.sha256((xcode / "SDKSettings.json").read_bytes()).hexdigest()
        self.write("packaging/bdwgc-qualified.properties", "macos-arm64.sdk.settings.sha256=" + settings + "\n")
        with patch.dict(workflow.os.environ), \
                patch.object(workflow.subprocess, "check_output", return_value=str(clt)), \
                patch.object(workflow.Path, "glob", return_value=[xcode]):
            workflow.os.environ.pop("SDKROOT", None)
            workflow.os.environ.pop("DEVELOPER_DIR", None)
            self.assertEqual(str(xcode), MAC_ENVIRONMENT(self.root)["SDKROOT"])
            self.assertNotIn("SDKROOT", workflow.os.environ)
            self.assertNotIn("DEVELOPER_DIR", workflow.os.environ)

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
        self.prepare_gc_sdk("macos-arm64")
        self.enterContext(patch.object(workflow, "run"))
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
        self.prepare_gc_sdk("macos-arm64")
        self.enterContext(patch.object(workflow, "run"))
        script = self.write("scripts/test.sh", "#!/bin/sh\nexit 3\n")
        script.chmod(0o755)
        self.assertEqual(1, self.execute("--platform", "macos-arm64", "--test", "Example"))
        self.assertRegex(self.output.getvalue(), r"macos-arm64\s+INCOMPLETE\s+0\s+0")
        with self.assertRaisesRegex(ValueError, "before individual test failures"):
            self.execute("--platform", "macos-arm64", "--failed")

    def test_interrupt_saves_failures_and_removes_only_its_container(self):
        self.prepare_gc_sdk("macos-arm64")
        self.prepare_bridge_support("linux-arm64")
        self.prepare_gc_sdk("linux-arm64")
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
        self.prepare_gc_sdk("macos-arm64")
        self.enterContext(patch.object(workflow, "run"))
        script = self.write("scripts/test.sh", "#!/bin/sh\nprintf 'not ok - Example\\nFAIL: 1 test(s), 0 passed\\n'\nexit 1\n")
        script.chmod(0o755)
        self.assertEqual(1, self.execute("--platform", "macos-arm64", "--test", "Example"))
        report = self.root / "workspace/platform-tests/macos-arm64.json"
        self.assertEqual(["Example"], json.loads(report.read_text())["failed_tests"])


if __name__ == "__main__":
    unittest.main()
