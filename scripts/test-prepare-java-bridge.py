#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused fail-closed tests for bridge preparation; no downloads or containers."""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("bridge_setup", Path(__file__).with_name("prepare-java-bridge.py"))
SETUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETUP)
PINS = json.loads(SETUP.PINS.read_text())


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.prefix = Path(self.temporary.name) / "jdk"
        self.prefix.mkdir()
        self.target = "linux-arm64"
        self.selected = PINS["targets"][self.target]
        self.installation = {"target": self.target, "archive": self.selected,
                             "pins_sha256": SETUP.digest(SETUP.PINS)}
        (self.prefix / "installation.json").write_text(json.dumps(self.installation))
        self.properties = {"java.vendor": PINS["vendor"], "java.runtime.version": PINS["version"],
                           "java.vm.name": "OpenJDK 64-Bit Server VM", "os.name": "Linux",
                           "os.arch": "aarch64"}
        self.clean_environment = patch.dict(os.environ, {}, clear=True)
        self.clean_environment.start()
        self.addCleanup(self.clean_environment.stop)

    def check(self):
        output = "\n".join("    " + key + " = " + value for key, value in self.properties.items())
        with patch.object(SETUP, "execute", side_effect=[output, "javac 21.0.12.1"]), \
                patch.object(SETUP.urllib.request, "urlopen", side_effect=AssertionError("offline check downloaded")):
            return SETUP.check_jdk(self.prefix, self.target, PINS)

    def test_exact_identity_and_explicit_tools(self):
        result = self.check()
        self.assertEqual(result["java"], str((self.prefix / self.selected["home"] / "bin/java").resolve()))
        self.assertIn("no physical CPU", result["evidence_scope"])

    def test_selected_release_requires_its_own_installation_identity(self):
        for major in (22, 23, 24, 25):
            pins_path = SETUP.PINS.with_name(f"java-bridge-jdks-{major}.json")
            pins = json.loads(pins_path.read_text())
            target = "macos-arm64" if major >= 24 else self.target
            selected = pins["targets"][target]
            properties = dict(self.properties, **{"java.runtime.version": pins["version"],
                                                  "os.name": selected["os"], "os.arch": selected["arch"]})
            output = "\n".join("    " + key + " = " + value for key, value in properties.items())
            record = {"target": target, "archive": selected,
                      "pins_sha256": SETUP.digest(pins_path)}
            (self.prefix / "installation.json").write_text(json.dumps(record))
            with patch.object(SETUP, "execute", side_effect=[output, "javac " + pins["version"].split("+")[0]]):
                self.assertEqual(SETUP.check_jdk(self.prefix, target, pins, pins_path)["pins_sha256"], record["pins_sha256"])
            record["pins_sha256"] = SETUP.digest(SETUP.PINS)
            (self.prefix / "installation.json").write_text(json.dumps(record))
            with patch.object(SETUP, "execute", side_effect=[output, "javac " + pins["version"].split("+")[0]]), \
                    self.assertRaisesRegex(ValueError, "installation"):
                SETUP.check_jdk(self.prefix, target, pins, pins_path)

    def test_reject_each_identity_mismatch(self):
        for key in self.properties:
            with self.subTest(key=key):
                original = self.properties[key]
                self.properties[key] = "incorrect"
                with self.assertRaises(ValueError):
                    self.check()
                self.properties[key] = original

    def test_reject_injected_options(self):
        for name in SETUP.INJECTED_OPTIONS:
            with self.subTest(name=name), patch.dict(os.environ, {name: "-Xmx1g"}):
                with self.assertRaisesRegex(ValueError, name):
                    self.check()

    def test_reject_stale_installation(self):
        self.installation["pins_sha256"] = "wrong"
        (self.prefix / "installation.json").write_text(json.dumps(self.installation))
        with self.assertRaisesRegex(ValueError, "installation"):
            self.check()

    def test_never_overwrite_prefix(self):
        with self.assertRaisesRegex(ValueError, "overwrite"):
            SETUP.install(self.prefix, self.prefix, self.target, PINS)

    def test_reject_corrupt_cache_without_download(self):
        cache = self.prefix / "cache"
        cache.mkdir()
        (cache / (self.selected["sha256"] + ".tar.gz")).write_bytes(b"bad archive")
        with patch.object(SETUP.urllib.request, "urlopen", side_effect=AssertionError("download")), \
                self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            SETUP.install(self.prefix / "new", cache, self.target, PINS)

    def test_image_identity_includes_base_target_and_preparation(self):
        initial = SETUP.image_key("base-a", "linux-arm64")
        self.assertNotEqual(initial, SETUP.image_key("base-b", "linux-arm64"))
        self.assertNotEqual(initial, SETUP.image_key("base-a", "linux-x86_64"))
        with patch.object(Path, "read_bytes", return_value=b"changed preparation"):
            self.assertNotEqual(initial, SETUP.image_key("base-a", "linux-arm64"))


if __name__ == "__main__":
    unittest.main()
