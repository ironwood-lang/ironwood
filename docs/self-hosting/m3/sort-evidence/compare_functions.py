# SPDX-License-Identifier: MIT OR Apache-2.0
"""Compare LLVM function bodies of two links of the same program.

Closed-world type IDs, dispatch-slot indexes and string-constant names are
renumbered whenever the library gains a type or method. This normalizes
exactly those three numberings and reports any other difference.
"""
import re
import sys


def functions(path):
    text = open(path, encoding="utf-8").read()
    found = {}
    for match in re.finditer(r'^define [^\n]*?(@"[^"]+"|@[\w.$-]+)\(.*?^\}', text, re.S | re.M):
        body = match.group(0)
        body = re.sub(r'@"ironwood\.string\.\d+"', '@"ironwood.string.#"', body)
        body = re.sub(r'(@"ironwood\.is_instance"\(ptr %[\w.]+, i32 )\d+', r'\1#', body)
        body = re.sub(r'(getelementptr inbounds ptr, ptr %ironwood\.guard\.dispatch\.\d+, i32 )\d+', r'\1#', body)
        found[match.group(1)] = body
    return found


base, current = functions(sys.argv[1]), functions(sys.argv[2])
differing = sorted(name for name in base.keys() | current.keys() if base.get(name) != current.get(name))
print(f"base functions {len(base)}, current functions {len(current)}, differing {len(differing)}")
for name in differing:
    print("differs:", name)
sys.exit(1 if differing or not base else 0)
