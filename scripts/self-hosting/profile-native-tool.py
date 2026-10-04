#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
"""Test-only LLVM wrapper; preserve exact tool output and archive time's footer."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

tool = Path(sys.argv[0]).name
if tool not in ('clang', 'llvm-as', 'opt', 'llc', 'llvm-config', 'llvm-objcopy'):
    raise ValueError('unknown profiled tool')
root = Path(os.environ['IRONWOOD_M0_NATIVE_PROFILE'])
real = Path(os.environ['IRONWOOD_M0_REAL_LLVM']) / 'bin' / tool
argv = [str(real), *sys.argv[1:]]
index = 0
while (root / str(index)).exists():
    index += 1
out = root / str(index); out.mkdir()


def files():
    return {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
            for arg in sys.argv[1:] if not arg.startswith('-')
            for path in (Path(arg),) if path.is_file()}


inputs = files()
began = time.monotonic()
time_output = out / 'time.txt'
process = subprocess.run(['/usr/bin/time', '-l', '-o', str(time_output), *argv], capture_output=True)
wall = time.monotonic() - began
(out / 'stdout.txt').write_bytes(process.stdout)
(out / 'stderr.txt').write_bytes(process.stderr)
raw_time = time_output.read_bytes()
timing = re.search(rb'(?m)^[ \t]*(\d+(?:\.\d+)?) real[ \t]+(\d+(?:\.\d+)?) user[ \t]+(\d+(?:\.\d+)?) sys[ \t]*$', raw_time)
rss = re.search(rb'(?m)^\s*(\d+)\s+maximum resident set size\s*$', raw_time)
if timing is None or rss is None:
    raise ValueError('time footer missing; raw output retained')
record = {'tool': tool, 'argv': argv, 'returncode': process.returncode,
          'fresh_child_wall_seconds': wall, 'rss_bytes': int(rss.group(1)),
          'real_user_sys_seconds': [float(value) for value in timing.groups()],
          'inputs_sha256': inputs, 'outputs_and_inputs_sha256': files()}
(out / 'measurement.json').write_text(json.dumps(record, indent=2) + '\n')
sys.stdout.buffer.write(process.stdout)
sys.stderr.buffer.write(process.stderr)
sys.exit(process.returncode)
