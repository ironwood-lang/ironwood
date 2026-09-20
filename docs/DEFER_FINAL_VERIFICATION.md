<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Defer final documentation and status audit

Date: 2026-09-20. Scope: the separately selected step 5 of Milestone 2 and
Section 9 of the [approved plan](DEFER_PLAN.md). Audited implementation:
`03079626346d6f822721bac85e3bcdbdea09d160` on `new-defer-keyword`.
That checkpoint changed documentation only and completed both milestones locally
for maintainer review. It performed no rebase, merge, branch switch, worktree
creation or push. The subsequent integration status below supersedes its pending
review/integration status; the recorded verification remains checkpoint evidence.

## Exit criteria and evidence

The following results come from the completed implementation checkpoints. This
audit checked their continued applicability and recorded evidence; it did not
rerun those passing behavior tests or performance workloads.

| Stage and local commit | Recorded result |
| --- | --- |
| Milestone 1 calls, `3ea065f32d52f1cc31c3613fda8e9f879841d4aa` | [33 focused selections passed](DEFER_CALLS_VERIFICATION.md): parsing, semantics, safety, typed IR, native exits/failures, artifacts and affected regressions. |
| Milestone 1 free, `a3a8b335774930dbdac9e549e3af947e19ec4e73` | [32 combined selections passed](DEFER_FREE_VERIFICATION.md), including mandatory rejection in every unfreed mode, both resource-header rejections and artifact reconstruction. |
| Milestone 2 performance, `564a56dd3b69e970f2ee811eceb9d33623df889d` | [Section 8 matrix passed](DEFER_PERFORMANCE_VERIFICATION.md): 18 cases, 48 binaries, 68 variant/mode configurations, 816 timed executions, two 16-round timing controls and 13 focused selections. |
| Milestone 2 example, `9f8f07660508401d9fbcb24b45e14946a4a0bab3` | [Strict compile, O3 class link and native run passed](../examples/deferredcleanup/README.md): five scenarios, exact output, exit 42, allocation-free pool reuse and expected live counts. |
| Milestone 2 project, `03079626346d6f822721bac85e3bcdbdea09d160` | [Ten project groups and three focused selections passed](DEFER_PROJECT_VERIFICATION.md), plus strict reconstruction of the failure fixture with exit 74. The six-request native reply probe observed zero allocations/frees, including the first request. |

Selection counts overlap across stages and must not be added as a count of
distinct tests. Each linked document retains its exact commands, environment,
results and historical scope. Their earlier pending-stage statements now read
as checkpoint history rather than current feature status.

The compiler implementation, runtime and standard library are unchanged since
Milestone 1's final commit, so the accepted performance evidence remains
applicable. Deferred versus directly nested equivalent cleanup had identical
inspected assembly, function sizes and total text in all 18 cases. Timings and
identical-binary controls found no reproducible slowdown attributable to defer.
This remains a macOS ARM64/LLVM 23 result, not a universal speed or platform claim.

Actual SimpleTcpEcho adoption preserved everything outside `Server.serve`
byte-for-byte, including `reply` and `main`; client/probe sources are unchanged.
The saved comparison records linked text at **63,752 bytes** in both versions.
Inlined `main` grew from 6,732 to 6,752 bytes in cold cleanup code, offset by
20 fewer alignment bytes, with the same 112-byte stack frame and successful-path
work. This project result is not an identical-machine-code claim. The separate
deterministic fixture proves close/free before each client catch, continued
accepts and listener cleanup before the outer status-74 handler.

## Documentation and compatibility audit

- [Language](LANGUAGE.md), [support/grammar](LANGUAGE_SPECS.md),
  [memory](MEMORY.md), [compiler](COMPILER.md) and
  [formatting](IRONWOOD_FORMATTING.md) agree on both implemented forms:
  explicit-block placement, early call captures, invocation-time checks/effects,
  LIFO exits, D051 failure order, unchanged free bindings and mandatory safety.
  The plan's stale planned-switch note now matches the implemented language note.
- [D051 and D168](DECISIONS.md#d168---plan-explicit-block-scoped-defer),
  the [roadmap](ROADMAP.md), plan and status summaries record both milestones as
  complete locally at that checkpoint, with review and integration then pending.
  D132/D133 remain unchanged; no new decision or unrelated feature was selected.
- Every occurrence of feature 72 and try-with-resources in
  [the Java comparison](IRONWOOD_VS_JAVA.md#feature-72) was reviewed, including
  its matrix, snippets, prose and summary. It remains an implemented Ironwood
  alternative; Java resource headers are still rejected. No feature count or
  pending rank changes follow from this audit. [Compatibility guidance](DIFFERENCES_FROM_JAVA.md)
  keeps that distinction and the reserved-word artifact migration requirement.
- `Parser.parseTry` and its full-message test both recommend ordinary `finally`
  or an explicit defer call. Tests retain declaration/existing-variable header
  rejection and malformed-header recovery. Both selections passed in Milestone 1;
  their implementation is unchanged.
- The [example index](../examples/README.md) has **73** workflows, **73** table
  rows and matching `TOTAL`/`PASS` sample counts. The
  [project index](../projects/README.md), [project README](../projects/SimpleTcpEcho/README.md),
  [project guide](SIMPLE_TCP_ECHO.md) and [testing guide](LOCAL_TESTING.md) match
  the adopted scope, cleanup ordering, allocation probe and failure fixture.
  No runnable snippet or production source changed in this audit.

## Focused audit checks

The read-only source/evidence checks passed:

- All four evidence documents' exact test selections still resolve to registered
  tests: 33 call-stage, 32 combined-stage, 13 performance-stage and 3 project-stage
  names, respectively. All **48 distinct names** also appear in the built Java 21
  test runner's `--list` output; listing does not execute tests.
- All 18 saved performance reports retain the Milestone 1 compiler revision;
  all 48 generated fixture sources match the current driver and all 48 executable
  hashes match their reports. Deferred/nested normalized assembly and recorded
  function/text sizes match in every case. All 68 configurations contain 12
  samples, totaling 816; both controls retain 16 samples per variant and the
  identical-binary hash match.
- The server source boundary, unchanged compiler/runtime/library implementation,
  example catalog and project test log agree with the evidence above.

Useful read-only reproduction commands from the repository root:

```sh
git diff --exit-code a3a8b335774930dbdac9e549e3af947e19ec4e73 \
  03079626346d6f822721bac85e3bcdbdea09d160 -- compiler/src/main runtime stdlib
git diff 9f8f07660508401d9fbcb24b45e14946a4a0bab3 \
  03079626346d6f822721bac85e3bcdbdea09d160 -- projects/SimpleTcpEcho/src/main
rg -n 'feature-72|try-with-resources|defer' docs/IRONWOOD_VS_JAVA.md
git diff --check
```

The saved matrix is under ignored `integration-tests/target/defer-performance/`:
each case has `report.json`, each variant has `Main.iron`, `program`,
`build.json` and `normalized-assembly.json`, and `controls/` holds the timing
investigations. The [performance guide](DEFER_PERFORMANCE_VERIFICATION.md)
reproduces these artifacts when they are absent; it also explains compiler-jar
hash invalidation after a rebuild. The [project guide](DEFER_PROJECT_VERIFICATION.md)
reproduces the server comparison and native checks. No new timing sample is
inferred from checking these saved artifacts.

All **405** relative link targets in the **14** changed Markdown files and all
**4** added fragment links resolve. Status consistency, added-text character/name
restrictions, and `git diff --check` passed.
The earlier source checkpoints passed the required license audit (five existing
OpenJDK-derived files). Per [AGENTS.md](../AGENTS.md), this documentation-only
checkpoint has no licensing impact and needs neither another license audit nor
compiler, native, benchmark, packaging or full-suite execution.

## Integration and follow-up status

On 2026-09-20, Git ancestry checks against fetched `origin/main` confirm every
implementation, performance, example and project commit in the table above,
plus this audit's commit `c9b2523`. The later example/project adoption `b6ce3a8`
and stdlib/testing adoption `bacf9f5` are also on `main`. The old
`new-defer-keyword` restriction is historical; the current checkout follows
[AGENTS.md](../AGENTS.md).

The separately selected [helper-proof follow-up](OWNERSHIP_HELPER_INVESTIGATION.md)
is complete on `main`: `1576883` implements the confined temporary-borrow
proofs and `3d3c660` adopts all three target helper refactors. Their verification
records preserve the remaining conservative limits and measured cost evidence.

No selected implementation or adoption step or unresolved verification failure
remains in this workstream. This status closeout checked commit ancestry, local
documentation links, cross-document status and `git diff --check`; it changed
no executable source and did not rerun passing behavior or performance suites.
The accepted syntax and safety contracts are unchanged. NIO1 remains a separate,
unselected [roadmap milestone](NIO_NETWORKING_PLAN.md), and this closeout makes
no new release or platform-validation claim.
