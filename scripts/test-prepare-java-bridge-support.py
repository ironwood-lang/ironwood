#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused offline manifest-integrity regressions, with small synthetic SDK files."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("support_setup", Path(__file__).with_name("prepare-java-bridge-support.py"))
SUPPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUPPORT)


class SupportPreparationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk"
        files = ["lib/libgcc_s.so.1", "lib/libstdc++.so.6", "lib/libgcc_s.so", "lib/libstdc++.so",
                 "sources/gcc-16.2.0.tar.gz", "sources/zlib-1.3.1.tar.gz", "licenses/GPL-3.0.txt",
                 "licenses/GCC-exception-3.1.txt", "recipes/libgcc/info/recipe/meta.yaml", "recipes/libstdcxx/info/recipe/meta.yaml"]
        for name in files:
            path = self.sdk / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("fixture " + name)
        self.target = "linux-arm64"
        self.pins = {self.target + ".libgcc.file.sha256": SUPPORT.digest(self.sdk / files[0]),
                     self.target + ".libstdcxx.file.sha256": SUPPORT.digest(self.sdk / files[1]),
                     "gcc.source.url": "https://invalid.example/gcc-16.2.0.tar.gz",
                     "zlib.source.url": "https://invalid.example/zlib-1.3.1.tar.gz",
                     "gcc.source.sha256": SUPPORT.digest(self.sdk / files[4]),
                     "zlib.source.sha256": SUPPORT.digest(self.sdk / files[5])}
        self.pins_path = self.root / "pins.properties"
        self.write(self.pins_path, self.pins)
        self.write(self.sdk / "dependencies.properties", self.pins)
        self.manifest = {"format": "1", "platform": self.target, "pins.sha256": SUPPORT.digest(self.pins_path)}
        self.manifest.update({"sha256." + name: SUPPORT.digest(self.sdk / name) for name in files})
        self.write(self.sdk / "build.properties", self.manifest)
        self.pin_patch = patch.object(SUPPORT, "PINS", self.pins_path)
        self.pin_patch.start()
        self.addCleanup(self.pin_patch.stop)

    @staticmethod
    def write(path, data):
        path.write_text("".join(key + "=" + value + "\n" for key, value in sorted(data.items())))

    def test_valid_offline_identity(self):
        with patch.object(SUPPORT.urllib.request, "urlopen", side_effect=AssertionError("offline check downloaded")):
            self.assertEqual(SUPPORT.check(self.sdk, self.target, self.pins), self.manifest)

    def test_wrong_platform(self):
        with self.assertRaisesRegex(ValueError, "target"):
            SUPPORT.check(self.sdk, "linux-x86_64", self.pins)

    def test_missing_corresponding_source_is_not_a_runtime_only_sdk(self):
        del self.manifest["sha256.sources/gcc-16.2.0.tar.gz"]
        self.write(self.sdk / "build.properties", self.manifest)
        with self.assertRaisesRegex(ValueError, "missing bridge support input"):
            SUPPORT.check(self.sdk, self.target, self.pins)

    def test_modified_binary_or_source_cannot_be_repaired_by_editing_manifest(self):
        for name in ("lib/libgcc_s.so.1", "sources/gcc-16.2.0.tar.gz"):
            with self.subTest(name=name):
                path = self.sdk / name
                original = path.read_bytes()
                original_hash = self.manifest["sha256." + name]
                path.write_bytes(b"changed")
                self.manifest["sha256." + name] = SUPPORT.digest(path)
                self.write(self.sdk / "build.properties", self.manifest)
                with self.assertRaisesRegex(ValueError, "differs from pinned"):
                    SUPPORT.check(self.sdk, self.target, self.pins)
                path.write_bytes(original)
                self.manifest["sha256." + name] = original_hash

    def test_untrusted_manifest_path(self):
        self.manifest["sha256." + "../outside"] = "unused"
        self.write(self.sdk / "build.properties", self.manifest)
        with self.assertRaisesRegex(ValueError, "invalid manifest path"):
            SUPPORT.check(self.sdk, self.target, self.pins)

    def test_setup_preserves_existing_prefix(self):
        with self.assertRaisesRegex(ValueError, "overwrite"):
            SUPPORT.setup(self.sdk, self.target, self.root / "cache", self.pins)


if __name__ == "__main__":
    unittest.main()
