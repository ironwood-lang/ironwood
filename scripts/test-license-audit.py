#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Check license input selection without building a compiler or changing the checkout."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


AUDIT = Path(__file__).with_name("check-licenses.sh").read_text()


class LicenseAuditTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ironwood-license-fixture-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve() / "source tree"
        self.root.mkdir()
        required = re.search(r"IRONWOOD_REQUIRED_LICENSE_FILES=\((.*?)\n\)", AUDIT, re.S).group(1).split()
        for name in required:
            self.write(name, "license fixture\n")
        for name in ("compiler/src", "runtime", "stdlib/src", "examples", "projects",
                     "integration-tests", "scripts", "packaging", "ide", ".github"):
            (self.root / name).mkdir(parents=True, exist_ok=True)
        self.write("Makefile", "# SPDX-License-Identifier: MIT OR Apache-2.0\n")
        self.write("scripts/check-licenses.sh", AUDIT)
        self.write(".gitignore", "target/\n")
        self.write("runtime/valid.c", "// SPDX-License-Identifier: MIT OR Apache-2.0\nint fixture;\n")
        self.git("init", "-q")
        self.git("add", ".")

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def git(self, *arguments):
        subprocess.run(["git", "-C", str(self.root), *arguments], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def audit(self, env=None):
        return subprocess.run(["bash", "scripts/check-licenses.sh"], cwd=self.root,
                              env=env, capture_output=True, text=True, timeout=30)

    def test_ignored_sdk_recipes_are_excluded(self):
        self.write("projects/wget/target/.ironwood-bridge-support-fixture/recipes/libgcc/info/recipe/build.sh",
                   "#!/bin/sh\nexit 0\n")
        self.write("integration-tests/target/scratch/target.c", "int generated;\n")
        result = self.audit()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("license audit passed", result.stdout)

    def test_untracked_maintained_source_without_header_is_rejected(self):
        self.write("projects/wget/src/source with spaces.c", "int unlicensed;\n")
        result = self.audit()
        self.assertEqual(1, result.returncode)
        self.assertIn("source lacks an SPDX license header: projects/wget/src/source with spaces.c", result.stderr)

    def test_force_tracked_ignored_source_checks_current_contents(self):
        name = "projects/wget/target/maintained.c"
        self.write(name, "// SPDX-License-Identifier: MIT OR Apache-2.0\nint maintained;\n")
        self.git("add", "-f", name)
        self.write(name, "int changed_without_header;\n")
        result = self.audit()
        self.assertEqual(1, result.returncode)
        self.assertIn("source lacks an SPDX license header: " + name, result.stderr)

    def test_source_export_excludes_generated_output_and_checks_source(self):
        shutil.rmtree(self.root / ".git")
        self.write("projects/wget/target/recipes/build.sh", "#!/bin/sh\nexit 0\n")
        result = self.audit()
        self.assertEqual(0, result.returncode, result.stderr)
        self.write("projects/wget/src/Main.iron", "class Main {}\n")
        result = self.audit()
        self.assertEqual(1, result.returncode)
        self.assertIn("source lacks an SPDX license header: projects/wget/src/Main.iron", result.stderr)

    def test_inventory_failure_cannot_report_success(self):
        fake = self.write("fake/bin/git", "#!/bin/sh\n"
                          "if [ \"$5\" = rev-parse ]; then printf '%s\\n' \"$LICENSE_FIXTURE_ROOT\"; exit 0; fi\n"
                          "exit 1\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"],
                                         "LICENSE_FIXTURE_ROOT": str(self.root)})
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot enumerate source files for license audit", result.stderr)
        self.assertNotIn("license audit passed", result.stdout)

    def test_repository_inspection_failure_cannot_use_export_fallback(self):
        fake = self.write("fake/bin/git", "#!/bin/sh\necho 'fatal: fixture metadata unreadable' >&2\nexit 1\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"]})
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot inspect repository for license audit", result.stderr)
        self.assertIn("fatal: fixture metadata unreadable", result.stderr)
        self.assertNotIn("license audit passed", result.stdout)

    def test_shared_ownership_trust_is_scoped_and_headers_are_still_checked(self):
        env = os.environ | {"GIT_TEST_ASSUME_DIFFERENT_OWNER": "true"}
        untrusted = subprocess.run(["git", "-C", str(self.root), "-c", "safe.directory=",
                                    "rev-parse", "--show-toplevel"], env=env, capture_output=True, text=True)
        self.assertNotEqual(0, untrusted.returncode)
        self.assertIn("dubious ownership", untrusted.stderr)
        result = self.audit(env)
        self.assertEqual(0, result.returncode, result.stderr)
        self.write("runtime/unlicensed.c", "int unlicensed;\n")
        result = self.audit(env)
        self.assertEqual(1, result.returncode)
        self.assertIn("source lacks an SPDX license header: runtime/unlicensed.c", result.stderr)
        other = self.root.parent / "unrelated checkout"
        subprocess.run(["git", "init", "-q", str(other)], check=True, capture_output=True)
        unrelated = subprocess.run(["git", "-C", str(other), "-c", "safe.directory=",
                                    "-c", "safe.directory=" + str(self.root), "rev-parse", "--show-toplevel"],
                                   env=env, capture_output=True, text=True)
        self.assertNotEqual(0, unrelated.returncode)
        self.assertIn("dubious ownership", unrelated.stderr)

    def test_equivalent_checkout_alias_is_accepted(self):
        alias = self.root.parent / "checkout alias"
        alias.symlink_to(self.root, target_is_directory=True)
        real_git = shutil.which("git")
        fake = self.write("fake/bin/git", "#!/bin/sh\n"
                          "if [ \"$5\" = rev-parse ]; then printf '%s\\n' \"$LICENSE_FIXTURE_ALIAS\"; exit 0; fi\n"
                          "exec \"$LICENSE_FIXTURE_GIT\" \"$@\"\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"],
                                         "LICENSE_FIXTURE_ALIAS": str(alias), "LICENSE_FIXTURE_GIT": real_git})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_different_repository_root_is_rejected(self):
        fake = self.write("fake/bin/git", "#!/bin/sh\nprintf '%s\\n' \"$LICENSE_FIXTURE_ROOT\"\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"],
                                         "LICENSE_FIXTURE_ROOT": str(self.root.parent)})
        self.assertEqual(1, result.returncode)
        self.assertIn("repository root mismatch for license audit", result.stderr)
        self.assertNotIn("license audit passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
