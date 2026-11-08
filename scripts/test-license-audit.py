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
                          "if [ \"$3\" = rev-parse ]; then printf '%s\\n' \"$LICENSE_FIXTURE_ROOT\"; exit 0; fi\n"
                          "exit 1\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"],
                                         "LICENSE_FIXTURE_ROOT": str(self.root)})
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot enumerate source files for license audit", result.stderr)
        self.assertNotIn("license audit passed", result.stdout)

    def test_repository_inspection_failure_cannot_use_export_fallback(self):
        fake = self.write("fake/bin/git", "#!/bin/sh\nexit 1\n")
        fake.chmod(0o755)
        result = self.audit(os.environ | {"PATH": str(fake.parent) + os.pathsep + os.environ["PATH"]})
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot inspect repository for license audit", result.stderr)
        self.assertNotIn("license audit passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
