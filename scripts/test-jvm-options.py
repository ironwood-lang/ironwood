#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0

"""Exercise the real launchers with an argument-recording Java stand-in."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent
TOOLS = {
    "ironwoodc": ["-jar"],
    "ironjar": ["-cp"],
    "irondoc": ["-cp"],
}
MAIN_CLASSES = {
    "ironjar": "ironwood.compiler.IronJarMain",
    "irondoc": "ironwood.compiler.doc.IronDoc",
}


class JvmOptionsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ironwood-jvm-options-")
        self.addCleanup(self.temporary.cleanup)
        self.scratch = Path(self.temporary.name)
        self.root = self.scratch / "relocated installation with spaces"
        for directory in ("bin", "lib", "scripts", "conf", "toolchain/lib/jvm/bin"):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        for tool in TOOLS:
            shutil.copy2(ROOT / "bin" / tool, self.root / "bin" / tool)
        shutil.copy2(ROOT / "scripts/jvm-options.sh", self.root / "scripts/jvm-options.sh")
        (self.root / "lib/ironwoodc.jar").touch()
        self.options = self.root / "conf/jvm.options"
        self.system_bin = self.scratch / "system-bin"
        self.system_bin.mkdir()
        self.java = self.system_bin / "java"
        self.java.write_text(
            f"#!{sys.executable}\nimport json, os, sys\n"
            "print(json.dumps(sys.argv[1:]))\n"
            "sys.exit(int(os.environ.get('IRONWOOD_TEST_JAVA_EXIT', '0')))\n",
            encoding="utf-8",
        )
        self.java.chmod(0o755)
        self.bundled_java = self.root / "toolchain/lib/jvm/bin/java"
        self.env = {key: value for key, value in os.environ.items()
                    if key not in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")}
        self.env["PATH"] = str(self.system_bin) + os.pathsep + self.env.get("PATH", "")
        # Configuration belongs to the invoked installation, not cwd or an env override.
        self.env["IRONWOOD_HOME"] = str(self.scratch)
        (self.scratch / "conf").mkdir()
        (self.scratch / "conf/jvm.options").write_text("-Dwrong.installation=true\n")

    def run_tools(self, options=(), error=None, returncode=0):
        for bundled in (False, True):
            if bundled:
                shutil.copy2(self.java, self.bundled_java)
            elif self.bundled_java.exists():
                self.bundled_java.unlink()
            for tool, prefix in TOOLS.items():
                with self.subTest(tool=tool, bundled=bundled):
                    result = subprocess.run(
                        ["/bin/bash", str(self.root / "bin" / tool), "--help", "file with spaces", ""],
                        cwd=self.scratch, env=self.env, text=True, capture_output=True,
                    )
                    self.assertEqual(result.returncode, returncode, result.stderr)
                    if error is not None:
                        self.assertIn(error, result.stderr)
                        self.assertEqual(result.stdout, "")
                    else:
                        expected = list(options) + prefix + [str(self.root / "lib/ironwoodc.jar")]
                        if tool in MAIN_CLASSES:
                            expected.append(MAIN_CLASSES[tool])
                        expected.extend(["--help", "file with spaces", ""])
                        self.assertEqual(json.loads(result.stdout), expected)
                        self.assertEqual(result.stderr, "")

    def test_missing_file_uses_defaults(self):
        self.run_tools()

    def test_empty_file_uses_defaults(self):
        self.options.touch()
        self.run_tools()

    def test_shipped_file_keeps_defaults(self):
        shutil.copy2(ROOT / "conf/jvm.options", self.options)
        self.run_tools()

    def test_comments_whitespace_crlf_and_final_unterminated_line(self):
        self.options.write_bytes(b"  # comment\r\n\t\r\n -Xmx2g \r\n\t-Dname=two words\t")
        self.run_tools(["-Xmx2g", "-Dname=two words"])

    def test_arguments_are_literal_and_keep_order(self):
        marker = self.scratch / "must-not-exist"
        options = ["-Dsame=first", "-Dsame=second", "-Dhash=value # literal",
                   '-Dquoted="literal quotes"', r"-Dpath=C:\some\path", "-Dunicode=Ironwood 🌲",
                   f"-Dliteral=$(touch {marker}); `touch {marker}` $HOME * ? [abc] ~"]
        self.options.write_text("\n".join(options), encoding="utf-8")
        self.run_tools(options)
        self.assertFalse(marker.exists())

    def test_non_option_has_file_and_line_diagnostic(self):
        self.options.write_text("# comment\n\nnot-a-jvm-option\n")
        self.run_tools(error=f"{self.options}:3: expected one JVM option", returncode=1)

    def test_java_argument_files_are_not_accepted(self):
        self.options.write_text("@other-options.txt\n")
        self.run_tools(error=f"{self.options}:1: expected one JVM option", returncode=1)

    def test_directory_is_not_treated_as_missing_configuration(self):
        self.options.mkdir()
        self.run_tools(error=f"JVM options must be a readable file: {self.options}", returncode=1)

    def test_dangling_symlink_is_not_treated_as_missing_configuration(self):
        self.options.symlink_to(self.scratch / "missing")
        self.run_tools(error=f"JVM options must be a readable file: {self.options}", returncode=1)

    @unittest.skipIf(os.geteuid() == 0, "root can read chmod 000 files")
    def test_unreadable_file_reports_error(self):
        self.options.write_text("-Xmx2g\n")
        self.options.chmod(0o000)
        self.addCleanup(self.options.chmod, 0o600)
        self.run_tools(error=f"JVM options must be a readable file: {self.options}", returncode=1)

    def test_java_exit_status_is_preserved(self):
        self.env["IRONWOOD_TEST_JAVA_EXIT"] = "42"
        self.options.write_text("-Xmx2g\n")
        self.run_tools(["-Xmx2g"], returncode=42)


if __name__ == "__main__":
    unittest.main(verbosity=2)
