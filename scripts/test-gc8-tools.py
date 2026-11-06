#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Focused checks for GC8 measurement arithmetic and assembly preservation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('gc8', Path(__file__).with_name('test-gc8.py'))
gc8 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc8)


class MeasurementTests(unittest.TestCase):
    def test_checksum_independent_simulation(self):
        values = list(range(4096)); checksum = 0
        for i in range(16384):
            index = (i * 17) & 4095
            values[index] += i & 7
            checksum += values[index] + values[(index + 1) & 4095]
            if (i + 1) % 4096 == 0:
                self.assertEqual(gc8.oracle(i + 1), checksum)
        for count in (0, -4096, 4097):
            with self.assertRaises(ValueError): gc8.oracle(count)

    def test_process_uncertainty(self):
        self.assertEqual(gc8.summary([7] * 6)['bootstrap_median_95_percent'], [7, 7])
        result = gc8.summary([1, 2, 3, 4, 5, 6])
        self.assertEqual(result['processes'], 6)
        self.assertEqual(result['median'], 3.5)
        self.assertEqual(result, gc8.summary([1, 2, 3, 4, 5, 6]))

    def test_normalization_preserves_changed_instructions(self):
        original = '00001000 <_ironwood_run>:\n1000: mov x0, #0x20\n1004: bl 0x2000 <_helper>\n\n'
        relocated = '00003000 <_ironwood_run>:\n3000: mov x0, #0x20\n3004: bl 0x4000 <_helper>\n\n'
        self.assertEqual(gc8.disassembly(original), gc8.disassembly(relocated))
        for changed in (original.replace('#0x20', '#0x21'), original.replace('mov', 'add'),
                        original.replace('_helper', '_iw_gc_poll')):
            self.assertNotEqual(gc8.disassembly(original), gc8.disassembly(changed))

    def test_elf_callers_are_included(self):
        source = '00001000 <ironwood_run>:\n1000: movq $0x20, %rax\n1004: callq 0x2000 <helper>\n\n'
        result = gc8.disassembly(source)
        self.assertIn('ironwood_run', result)
        self.assertNotEqual(result, gc8.disassembly(source.replace('$0x20', '$0x21')))
        bridge = '00001000 <ironwood_bridge_entry_7>:\n1000: retq\n\n'
        self.assertIn('ironwood_bridge_entry_7', gc8.disassembly(bridge))

    def test_disk_floor_accepts_and_refuses(self):
        for available, refused in ((29 * 1024**3, True), (30 * 1024**3, False)):
            with patch.object(gc8.shutil, 'disk_usage') as usage:
                usage.return_value.free = available
                if refused:
                    with self.assertRaises(RuntimeError): gc8.disk_floor(Path('.'))
                else: gc8.disk_floor(Path('.'))

    def test_staged_source_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / '.qualification-source.json').write_text(json.dumps({'base_revision': 'snapshot-revision'}))
            with patch.object(gc8, 'ROOT', root):
                self.assertEqual(gc8.source_revision(), 'snapshot-revision')

    def test_fixed_argv_keeps_guarded_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(gc8.engine.subprocess, 'Popen') as launch:
                launch.return_value.poll.return_value = 0
                launch.return_value.returncode = 0
                gc8.engine.run(['fixed-argv', '8'], Path(directory), 'probe', executable='/chosen/program')
                self.assertEqual(launch.call_args.args[0], ['fixed-argv', '8'])
                self.assertEqual(launch.call_args.kwargs['executable'], '/chosen/program')
                self.assertTrue(launch.call_args.kwargs['start_new_session'])

    def test_stale_input_refusal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for name in ('compiler/build/ironwoodc.jar', 'compiler/build/ironwood-stdlib.ironjar', 'sdk/build.json', 'source.iron', 'program'):
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'input')
            binary, source, sdk = root / 'program', root / 'source.iron', root / 'sdk'
            with patch.object(gc8, 'ROOT', root):
                identity = gc8.build_identity(binary, [source], sdk, 'configuration')
                (root / 'identity.json').write_text(json.dumps(identity))
                gc8.verify_identity(binary, sdk, 'configuration')
                with self.assertRaises(ValueError): gc8.verify_identity(binary, sdk, 'other')
                for path in (binary, source, sdk / 'build.json', root / 'compiler/build/ironwoodc.jar',
                             root / 'compiler/build/ironwood-stdlib.ironjar'):
                    path.write_bytes(b'changed')
                    with self.assertRaises(ValueError): gc8.verify_identity(binary, sdk, 'configuration')
                    path.write_bytes(b'input')


if __name__ == '__main__': unittest.main()
