#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Exercise release input admission and orchestration without building an archive."""

import json
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPTS = Path(__file__).resolve().parent


class ReleasePackagingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ironwood-release-inputs-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve() / "source tree"
        self.toolchain = self.root / "toolchain with spaces"
        self.log = self.root / "commands.jsonl"
        self.write("scripts/build-release-idk.sh", (SCRIPTS / "build-release-idk.sh").read_text())
        command = f"#!{sys.executable}\n" + '''import json, os, pathlib, sys
args = sys.argv[1:]
script = pathlib.Path(sys.argv[0]).name
if script in ('python', 'python3'):
    if args[0] == '-B': args = args[1:]
    script, args = pathlib.Path(args[0]).name, args[1:]
with open(os.environ['FIXTURE_LOG'], 'a') as output:
    output.write(json.dumps({'script': script, 'args': args}) + '\\n')
if script.startswith('prepare-'):
    if script == 'prepare-gc.py' and os.environ.get('FIXTURE_REJECT_GC'):
        sys.exit(17)
    if '--prefix' in args:
        prefix = pathlib.Path(args[args.index('--prefix') + 1])
        if '--verify' not in args and '--check' not in args:
            prefix.mkdir(parents=True, exist_ok=True)
            (prefix / 'build.properties').write_text('format=1\\n')
'''
        for name in ("python", "python3", "conda-pack", "clang", "llvm-as", "opt", "llc", "llvm-objcopy",
                     "llvm-readelf", "ld.lld"):
            self.write("toolchain with spaces/bin/" + name, command, executable=True)
        self.write("toolchain with spaces/bin/llvm-config", "#!/bin/sh\necho 23.1.0\n", executable=True)
        for name in ("check-licenses.sh", "check-tracked-files.sh", "build.sh", "update-irondocs.sh",
                     "package-idk.sh", "test-idk.sh"):
            self.write("scripts/" + name, command, executable=True)
        self.write("toolchain with spaces/lib/jvm/bin/java", "fixture\n", executable=True)
        for name in ("bin/javadoc", "include/jni.h", "include/darwin/jni_md.h"):
            self.write("toolchain with spaces/lib/jvm/" + name, "fixture\n")
        self.write("fake/bin/uname", f"#!{sys.executable}\n" + '''import os, sys
host = os.environ['FIXTURE_HOST'].split()
print(host[0] if sys.argv[1] == '-s' else host[1] if sys.argv[1] == '-m' else ' '.join(host))
''', executable=True)
        self.write("scripts/jdk.sh", '''ironwood_select_java() {
    export JAVA_HOME="$IRONWOOD_IDK_TOOLCHAIN_HOME/lib/jvm"
    IRONWOOD_JAVA_FEATURE=21
}
ironwood_require_jdk() { :; }
''')
        self.env = {key: value for key, value in os.environ.items() if not key.startswith('IRONWOOD_')}
        self.env.update(IRONWOOD_IDK_TOOLCHAIN_HOME=str(self.toolchain), FIXTURE_LOG=str(self.log),
                        FIXTURE_HOST="Darwin arm64", PATH=str(self.root / "fake/bin") + os.pathsep + os.environ["PATH"])

    def write(self, name, text, executable=False):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        if executable:
            path.chmod(0o755)
        return path

    def execute(self, script="build-release-idk.sh", platform="macos-arm64"):
        arguments = ["0.6.2-beta"] + ([platform] if script == "build-release-idk.sh" else [])
        return subprocess.run(["bash", str(self.root / "scripts" / script), *arguments],
                              cwd=self.root.parent, env=self.env, capture_output=True, text=True, timeout=20)

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_missing_inputs_are_prepared_then_both_archive_smokes_run(self):
        for host, target in (("Darwin arm64", "macos-arm64"), ("Linux aarch64", "linux-arm64"),
                             ("Linux x86_64", "linux-x86_64")):
            with self.subTest(target=target):
                self.env["FIXTURE_HOST"] = host
                self.env["IRONWOOD_GC_HOME"] = str(self.root / target / "gc")
                self.env["IRONWOOD_TLS_HOME"] = str(self.root / target / "tls")
                self.env["IRONWOOD_BRIDGE_SUPPORT_HOME"] = str(self.root / target / "support")
                if self.log.exists(): self.log.unlink()
                result = self.execute(platform=target)
                self.assertEqual(0, result.returncode, result.stderr)
                calls = self.commands()
                gc = next(call for call in calls if call['script'] == 'prepare-gc.py')
                self.assertNotIn('--verify', gc['args'])
                self.assertNotIn('', gc['args'])
                self.assertEqual(target, gc['args'][gc['args'].index('--target') + 1])
                smokes = [call for call in calls if call['script'] == 'test-idk.sh']
                self.assertEqual(2, len(smokes))
                self.assertEqual(smokes[0]['args'] + ['--gc9'], smokes[1]['args'])
                self.assertTrue(smokes[0]['args'][0].endswith('-' + target + '.tar.gz'))
                self.assertEqual(target.startswith('linux-'), any(call['script'] == 'prepare-java-bridge-support.py' for call in calls))

    def test_existing_sdk_is_verified_and_failure_stops_before_packaging(self):
        self.write("toolchain with spaces/ironwood-gc/build.properties", "fixture\n")
        self.env['FIXTURE_REJECT_GC'] = '1'
        result = self.execute()
        self.assertEqual(17, result.returncode, result.stderr)
        calls = self.commands()
        self.assertEqual('prepare-gc.py', calls[-1]['script'])
        self.assertIn('--verify', calls[-1]['args'])
        self.assertFalse(any(call['script'] == 'package-idk.sh' for call in calls))

    def test_mismatched_target_is_rejected_before_any_build(self):
        result = self.execute(platform='linux-x86_64')
        self.assertEqual(1, result.returncode)
        self.assertIn('matching target', result.stderr)
        self.assertEqual([], self.commands())

    def use_packager(self):
        self.write("scripts/package-idk.sh", (SCRIPTS / "package-idk.sh").read_text())

    def test_empty_gc_override_is_rejected_before_any_build(self):
        self.env['IRONWOOD_GC_HOME'] = ''
        result = self.execute()
        self.assertEqual(1, result.returncode)
        self.assertIn('IRONWOOD_GC_HOME is empty', result.stderr)
        self.assertEqual([], self.commands())

    def test_packager_refuses_missing_gc_before_building(self):
        self.use_packager()
        result = self.execute('package-idk.sh')
        self.assertEqual(1, result.returncode)
        self.assertIn('requires a prepared GC SDK', result.stderr)
        self.assertEqual([], self.commands())

    def test_packager_refuses_empty_override_despite_default_sdk(self):
        self.use_packager()
        self.write("toolchain with spaces/ironwood-gc/build.properties", "fixture\n")
        self.env['IRONWOOD_GC_HOME'] = ''
        result = self.execute('package-idk.sh')
        self.assertEqual(1, result.returncode)
        self.assertIn('requires a prepared GC SDK', result.stderr)
        self.assertEqual([], self.commands())

    def test_packager_verifies_default_and_explicit_sdk_before_building(self):
        self.use_packager()
        for prefix in (self.toolchain / 'ironwood-gc', self.root / 'explicit SDK'):
            with self.subTest(prefix=prefix):
                prefix.mkdir(parents=True, exist_ok=True)
                (prefix / 'build.properties').write_text('fixture\n')
                if prefix.name != 'ironwood-gc': self.env['IRONWOOD_GC_HOME'] = str(prefix)
                self.env['FIXTURE_REJECT_GC'] = '1'
                if self.log.exists(): self.log.unlink()
                result = self.execute('package-idk.sh')
                self.assertEqual(17, result.returncode, result.stderr)
                calls = self.commands()
                self.assertEqual(1, len(calls))
                self.assertEqual('prepare-gc.py', calls[0]['script'])
                self.assertIn('--verify', calls[0]['args'])
                self.assertEqual(str(prefix), calls[0]['args'][calls[0]['args'].index('--prefix') + 1])


    def test_linux_output_cleanup_preserves_glibc_rejection(self):
        source = (SCRIPTS / 'test-idk.sh').read_text()
        definitions = '\n'.join(re.search(re.escape(name) + r'\(\) \{\n.*?^\}', source, re.M | re.S).group()
                                for name in ('verify_linux_glibc_baseline', 'verify_and_clear_linux_target'))
        driver = self.write('check-target.sh', 'set -euo pipefail\n' + definitions + '\n'
                            'IRONWOOD_IDK_ROOT=$1\nIRONWOOD_GLIBC_BASELINE=2.17\n'
                            'verify_and_clear_linux_target "$2"\n')
        self.env['FIXTURE_HOST'] = 'Linux x86_64'
        self.write('toolchain/bin/llvm-readelf', '#!/bin/sh\n'
                   'if [ "$1" = --version-info ]; then printf "Name: GLIBC_%s\\n" "$FIXTURE_GLIBC"; fi\n',
                   executable=True)
        for version, status in (('2.17', 0), ('2.28', 1)):
            with self.subTest(version=version):
                target = self.root / ('target ' + version)
                self.write('target ' + version + '/native', 'fixture\n', executable=True)
                self.env['FIXTURE_GLIBC'] = version
                result = subprocess.run(['bash', str(driver), str(self.root), str(target)],
                                        env=self.env, capture_output=True, text=True, timeout=10)
                self.assertEqual(status, result.returncode, result.stderr)
                self.assertEqual(status != 0, target.exists())
                if status: self.assertIn('requires GLIBC_2.28', result.stderr)


    def test_metadata_space_uses_expanded_payload_and_rejects_insufficient_disk(self):
        spec = importlib.util.spec_from_file_location('gc9_packaging', SCRIPTS / 'test-gc9-distribution.py')
        driver = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(driver)
        jar = self.root / 'compressible.jar'
        with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('payload', b'0' * (2 * 1024**2))
        disk = driver.shutil._ntuple_diskusage(2 * 1024**3, 1024**3, 1024**3)
        with patch.object(driver.shutil, 'disk_usage', return_value=disk):
            required = driver.require_metadata_space(jar, self.root)
        self.assertGreater(required, 256 * 1024**2 + 2 * 2 * 1024**2)
        self.assertLess(required, 1024**3)
        for available, accepted in ((required, True), (required - 1, False)):
            disk = driver.shutil._ntuple_diskusage(2 * 1024**3, 1024**3, available)
            with self.subTest(available=available), patch.object(driver.shutil, 'disk_usage', return_value=disk):
                if accepted:
                    self.assertEqual(required, driver.require_metadata_space(jar, self.root))
                else:
                    with self.assertRaisesRegex(RuntimeError, 'metadata variants require'):
                        driver.require_metadata_space(jar, self.root)


if __name__ == '__main__':
    unittest.main()
