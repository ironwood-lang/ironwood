#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused offline download-progress and manifest-integrity regressions."""

import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("support_setup", Path(__file__).with_name("prepare-java-bridge-support.py"))
SUPPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUPPORT)


class SupportDownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name) / "cache"
        self.url = "https://invalid.example/runtime.conda"
        self.data = b"a" * (2 * 1024 * 1024)
        self.expected = hashlib.sha256(self.data).hexdigest()
        self.output = io.StringIO()
        self.stderr = Mock(wraps=self.output)
        self.stderr_patch = patch.object(SUPPORT.sys, "stderr", self.stderr)
        self.stderr_patch.start()
        self.addCleanup(self.stderr_patch.stop)

    def test_download_reports_intermediate_progress_and_flushes(self):
        source = io.BytesIO(self.data)
        source.headers = {"Content-Length": str(len(self.data))}
        with patch.object(SUPPORT.urllib.request, "urlopen", return_value=source) as open_url, \
                patch.object(SUPPORT.time, "monotonic", side_effect=[0, 2, 4, 6]):
            path = SUPPORT.download(self.cache, self.url, self.expected)
        open_url.assert_called_once_with(self.url, timeout=120)
        self.assertEqual(path.read_bytes(), self.data)
        messages = self.output.getvalue()
        self.assertIn("downloading " + self.url, messages)
        self.assertIn("1.0 MiB / 2.0 MiB (50%), 0.5 MiB/s, 2s elapsed", messages)
        self.assertIn("2.0 MiB / 2.0 MiB (100%)", messages)
        self.assertIn("verifying download SHA-256", messages)
        self.assertEqual(self.stderr.flush.call_count, len(messages.splitlines()))

    def test_unknown_or_invalid_length_still_reports_received_bytes(self):
        for length in (None, "0", "invalid"):
            with self.subTest(length=length):
                self.output.seek(0)
                self.output.truncate()
                source = io.BytesIO(self.data)
                source.headers = {} if length is None else {"Content-Length": length}
                cache = self.cache / str(length)
                with patch.object(SUPPORT.urllib.request, "urlopen", return_value=source), \
                        patch.object(SUPPORT.time, "monotonic", side_effect=[0, 2, 4, 6]):
                    path = SUPPORT.download(cache, self.url, self.expected)
                self.assertEqual(path.read_bytes(), self.data)
                self.assertIn("1.0 MiB, 0.5 MiB/s, 2s elapsed", self.output.getvalue())
                self.assertNotIn("%", self.output.getvalue())

    def test_cached_download_is_reported_and_still_verified_offline(self):
        self.cache.mkdir()
        path = self.cache / "runtime.conda"
        path.write_bytes(self.data)
        with patch.object(SUPPORT.urllib.request, "urlopen", side_effect=AssertionError("cache downloaded")):
            self.assertEqual(SUPPORT.download(self.cache, self.url, self.expected), path)
            path.write_bytes(b"corrupt")
            with self.assertRaisesRegex(ValueError, "cached SHA-256 mismatch"):
                SUPPORT.download(self.cache, self.url, self.expected)
        self.assertIn("using cached download", self.output.getvalue())
        self.assertIn("verifying cached SHA-256", self.output.getvalue())

    def test_interrupted_or_corrupt_download_does_not_publish_partial_cache(self):
        for interrupted in (True, False):
            with self.subTest(interrupted=interrupted):
                source = io.BytesIO(b"corrupt")
                source.headers = {}
                if interrupted:
                    source.read1 = Mock(side_effect=[b"partial", TimeoutError("read timed out")])
                error = TimeoutError if interrupted else ValueError
                message = "read timed out" if interrupted else "download SHA-256 mismatch"
                with patch.object(SUPPORT.urllib.request, "urlopen", return_value=source):
                    with self.assertRaisesRegex(error, message):
                        SUPPORT.download(self.cache, self.url, self.expected)
                self.assertEqual(list(self.cache.iterdir()), [])


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
