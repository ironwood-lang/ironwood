#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Stage GC1 reproduction inputs and an explicitly selected, verified SDK."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('prepare_gc', ROOT / 'scripts/prepare-gc.py')
gc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gc)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('IRONWOOD_GC_HOME'))
    parser.add_argument('--llvm-home', type=Path, default=os.environ.get('IRONWOOD_LLVM_HOME'))
    args = parser.parse_args()
    stage = args.stage.resolve()
    inputs = [*gc.ADAPTER, 'packaging/bdwgc.json', 'scripts/prepare-gc.py',
              'scripts/build-gc-fixture.py', 'scripts/test-gc-engine.py', 'scripts/package-gc-inputs.py',
              'integration-tests/native/gc_engine_probe.c', 'docs/GC1_ENGINE_PREPARATION.md',
              'docs/BDWGC_INTEGRATION_PLAN.md', 'docs/GC0_BASELINE_AND_CONTRACTS.md']
    for name in inputs:
        destination = stage / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, destination)
    record = {'format': 1, 'production.enabled': False, 'sdk.bundled': False,
              'inputs.sha256': {name: gc.digest(stage / name) for name in inputs}}
    if args.sdk:
        if not args.llvm_home:
            raise ValueError('bundling a GC SDK requires --llvm-home or IRONWOOD_LLVM_HOME')
        sdk, llvm = args.sdk.resolve(), args.llvm_home.resolve()
        manifest, _ = gc.verify(sdk, llvm, gc.host_target())
        destination = stage / 'runtime/gc-sdk'
        shutil.copytree(sdk, destination)  # Preserve source, headers, licenses and build evidence together.
        gc.verify(destination, llvm, gc.host_target())
        record.update({'sdk.bundled': True, 'gc.config.sha256': manifest['gc.config.sha256'],
                       'sdk.manifest.sha256': gc.digest(destination / 'build.json'),
                       'sdk.files.sha256': manifest['files.sha256']})
    (stage / 'packaging/gc-inputs.json').write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
