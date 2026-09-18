#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Check downloader-driver progress, subprocess cleanup and tool discovery."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SOURCE = Path(__file__).with_name('test-networking-m6.py')
SPEC = importlib.util.spec_from_file_location('wget_driver', SOURCE)
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)


class DriverTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='wget driver ')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / 'logs'
        self.output.mkdir()
        self.enterContext(patch.object(DRIVER, 'ROOT', self.root))
        self.enterContext(patch.object(DRIVER, 'OUT', self.output))
        self.enterContext(patch.object(DRIVER, 'PROGRESS_INTERVAL', .05))
        self.progress = self.enterContext(contextlib.redirect_stderr(io.StringIO()))

    def executable(self, path, source):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        path.chmod(0o755)
        return path

    def toolchain(self, path, version='23.1.0'):
        for name in ('clang', 'llvm-objdump', 'llvm-config'):
            self.executable(path / 'bin' / name, f'#!/bin/sh\nprintf "%s\\n" "{version}"\n')
        return path

    def assert_stopped(self, pid):
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True)
            if result.returncode != 0 or result.stdout.strip().startswith('Z'): return
            time.sleep(.02)
        self.fail(f'child {pid} is still running')

    def test_progress_keeps_binary_output_and_expected_status(self):
        command = [sys.executable, '-c',
                   'import sys,time; sys.stdout.buffer.write(b"a\\x00b"); sys.stdout.flush(); '
                   'time.sleep(.15); print("diagnostic", file=sys.stderr); sys.exit(8)']
        result = DRIVER.run('slow', command, status=8, announce=True)
        self.assertEqual(result.stdout, b'a\x00b')
        self.assertEqual(result.stderr, b'diagnostic\n')
        self.assertEqual((self.output / 'slow.log').read_bytes(), b'a\x00bdiagnostic\n')
        for message in ('RUN - slow', 'WAIT - slow', 'ok - slow'):
            self.assertIn(message, self.progress.getvalue())

    def test_timeout_preserves_partial_log_and_stops_descendants(self):
        command = [sys.executable, '-c',
                   'import subprocess,sys,time; child=subprocess.Popen([sys.executable,"-c","import time;time.sleep(30)"]); '
                   'print(child.pid, flush=True); time.sleep(30)']
        with self.assertRaisesRegex(TimeoutError, 'exceeded.*see'):
            DRIVER.run('timeout', command, timeout=1)
        pid = int((self.output / 'timeout.log').read_text().strip())
        self.assert_stopped(pid)
        self.assertIn('STOP - timeout', self.progress.getvalue())

    def test_ctrl_c_is_concise_and_stops_compiler_children(self):
        script = self.root / 'scripts' / SOURCE.name
        script.parent.mkdir()
        shutil.copyfile(SOURCE, script)
        pid_file = self.root / 'child.pid'
        self.executable(self.root / 'bin/ironwoodc', f'''#!{sys.executable}
import subprocess, sys, time
from pathlib import Path
child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(30)'])
print('compiler started', flush=True)
Path({str(pid_file)!r}).write_text(str(child.pid))
time.sleep(30)
''')
        process = subprocess.Popen([sys.executable, script, '--group', 'protocol'], cwd=self.root,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while not pid_file.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue(pid_file.exists(), 'fake compiler did not start')
            process.send_signal(signal.SIGINT)
            stdout, stderr = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 130, stderr)
            self.assertEqual(stdout, b'')
            self.assertIn(b'RUN - compile', stderr)
            self.assertIn(b'Interrupted; active command stopped.', stderr)
            self.assertNotIn(b'Traceback', stderr)
            self.assert_stopped(int(pid_file.read_text()))
            log = self.root / 'integration-tests/target/networking-m6/compile.log'
            self.assertIn('compiler started', log.read_text())
        finally:
            if process.poll() is None:
                process.send_signal(signal.SIGINT)
                process.communicate(timeout=5)

    def test_homebrew_discovery_without_environment_override(self):
        home = self.toolchain(self.root / 'LLVM with spaces')
        brew = self.executable(self.root / 'tools/brew', f'#!/bin/sh\nprintf "%s\\n" "{home}"\n')
        with patch.dict(os.environ, {'PATH': str(brew.parent)}, clear=True):
            self.assertEqual(DRIVER.llvm_home(), home.resolve())

    def test_explicit_invalid_toolchain_does_not_fall_back(self):
        home = self.toolchain(self.root / 'wrong-version', '22.1.0')
        with patch.dict(os.environ, {'IRONWOOD_LLVM_HOME': str(home)}, clear=True):
            with self.assertRaisesRegex(AssertionError, 'selected prefix.*set IRONWOOD_LLVM_HOME'):
                DRIVER.llvm_home()

    def test_bundled_toolchain_matches_launcher_priority(self):
        home = self.toolchain(self.root / 'toolchain')
        self.executable(home / 'lib/jvm/bin/java', '#!/bin/sh\nexit 0\n')
        with patch.dict(os.environ, {'IRONWOOD_LLVM_HOME': str(self.root / 'absent')}, clear=True):
            self.assertEqual(DRIVER.llvm_home(), home.resolve())


if __name__ == '__main__':
    unittest.main(verbosity=2)
