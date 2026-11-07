<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# GC migration failed-test repair, 2026-11-07

The reported macOS run contained 676 compiler tests: 315 passed and 361 failed.
This repair retried the 361 failures, then only remaining failures and directly
affected controls. Every original failing selection now has passing evidence
under the current contract. The 315 earlier successes were not rerun, so this
is a focused repair checkpoint, not a new complete-suite result.

## Causes and changes

- 320 failures reported the missing macOS GC SDK. Platform setup now prepares
  or verifies a macOS SDK as well as each Linux SDK. Before testing, it verifies
  exact qualified profiles and passes the selected GC, LLVM and Apple SDK paths
  to the macOS process. Explicit SDK choices remain authoritative; an
  unqualified choice fails before compiler tests. With no explicit Apple SDK
  choice, an installed Xcode SDK may supply the qualified settings hash.
- LLVM assertions now distinguish managed address-space references, native
  pointers and address-space casts for immortal values. Metadata checks account
  for writable type descriptors and mandatory emergency failure types.
- Broken fixtures contained empty statements with obsolete rejection premises,
  unconditional failure returns, a truncated floating conversion test and two
  boolean returns in integer methods. Valid managed sharing is now accepted;
  deliberate removed-syntax cases still require migration diagnostics. Callback
  cleanup uses general `defer` and checks both supported foreign context and
  refusal of unknown or cached-initialization effects.
- Collection fixtures retain allocation-free pool reuse checks, verify live
  graphs across explicit collection, and check reclamation after helper roots
  expire. Wget probes collect between completed cycles, outside measured
  transfers, while preserving explicit native resource closure and OOM checks.
- Test toolchains expose LLVM headers and libraries needed by the mandatory
  statepoint plugin. Optimizer command capture observes the optimization stage
  while still forwarding final GC verification. TLS checks distinguish plugin
  compilation, per-link generated root contracts and cached runtime objects.
- Bridge properties serialization now uses schema 2, matching the authoritative
  Java producer. Standard-library summaries match the actual networking suite
  counts: 174 passed, one skipped, 175 total.

## Historical pilot boundary

The pre-GC M4.3 Ironwood pilot linker lacks managed admission, statepoint rewriting
and final root verification. Its former successful-link test is replaced by a
source/class/archive refusal test named `M4.3 native LLVM pipeline rejects managed
modules without GC5 admission`. The production Java bootstrap linker remains
authoritative. This does not claim that the pilot linker has been migrated.
See [D330](../DECISIONS.md#d330---refuse-managed-modules-in-the-historical-compiler-pilot-linker).
No self-hosting attempt was made.

## Verification

| Selection | Result |
| --- | --- |
| Originally failing macOS selections | 361 resolved through focused retries |
| macOS platform runner with default SDK selection | 1 native collection/reuse test passed |
| Platform workflow unit tests | 23 passed |
| Linux arm64 backend checks | 4 passed |
| Linux x86-64 backend checks through Rosetta | 4 passed |

The Linux selection covers native layout agreement, baseline SSE2 store merging,
inlining controls and optimization reports. It is a local regression check;
Rosetta results do not replace GC9 matching-hardware qualification. Existing GC
SDK profile hashes and runtime/compiler safety verifiers were not relaxed.

License, tracked-file and content-bound retirement checks pass.

All test Java sources compile with JDK 25, `--release 21`, all lint warnings and
`-Werror`. The macOS retry trees remained below the 16 GiB guard; the largest
sampled combined retry-tree RSS was about 3.9 GiB. The production collector and
runtime sources were unchanged. No per-operation bookkeeping or hot lowering
change was introduced.

Raw logs, the original failure inventory and the per-test aggregate remain under
ignored `workspace/platform-tests/`. The companion manifest records relative
paths and hashes. Changes and commits stay on the local
`codex/bdwgc-integration-plan` branch.
