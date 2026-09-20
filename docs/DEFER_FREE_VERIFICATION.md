<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Deferred-free and combined Milestone 1 verification

Date: 2026-09-19. Scope: Milestone 1, Stage 2 only, selected after maintainer
acceptance of the [call checkpoint](DEFER_CALLS_VERIFICATION.md). The local
checkpoint containing this document is based on
`3ea065f32d52f1cc31c3613fda8e9f879841d4aa` on `new-defer-keyword`.
Both forms in the reviewed [defer plan](DEFER_PLAN.md) are implemented for
maintainer review. Milestone 2 performance acceptance, example/project adoption,
and integration remain pending. No branch switch, worktree, rebase, merge, or
push was performed.

## Implemented boundary

`defer free name;` binds an existing owned local without a reference capture or
runtime registration. Its immutable cleanup action retains local-symbol identity
and source spans. The active cleanup chain enforces pending-target write guards,
including assignment expressions and lvalue updates. Ordinary free and deferred
free share the allocation, escape, alias, dependent-owner, pending-observer,
return/yield, and loop safety proof on each cleanup predecessor.

Only aliases belonging to the exiting lexical scopes expire for this proof.
Outer locals and other pending actions remain observers. The analyzer keeps the
environment intact for SSA and exceptional joins, and restores ownership and
cleanup contexts independently for mutually exclusive copies. Reassignment is
allowed before registration and after completed inner-block cleanup under the
existing ownership rules. Source visitors, generic specialization, reachability,
and source-bearing class/archive reconstruction handle both forms.

The implementation adds no runtime API, cleanup stack, callback allocation,
registration flag, or automatic reclamation. Java resource headers remain
rejected. Deferred field, element, fresh-expression, and parenthesized frees
remain outside the local-name syntax. Parameters, `this` aliases, attached owned
fields, dependent streams, and pool checkouts gain no free exemption. Existing
conservative ownership and back-edge limits remain in force.

## Focused behavior checks

Oracle Java 21.0.1 and LLVM 23.1.0 were used on macOS 26.6.2 ARM64. Every Stage 2
compiler check used the Java 21 bootstrap. The 30-selection combined run passed;
both resource-header selections then passed. The final ownership additions
passed a rerun of their one affected selection: **32 distinct selections passed,
zero remaining failures**. No unfiltered compiler or platform suite ran.

Reproduce the exact selection from the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/test.sh \
  --test 'deferred calls preserve explicit-block syntax and diagnostics' \
  --test 'deferred calls enforce invocation and checked-exception contracts' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred calls replay typed captures across independent exits' \
  --test 'deferred calls survive source class and archive reconstruction' \
  --test 'deferred calls preserve exit and failure order at O3' \
  --test 'deferred calls flush and close loopback sockets at O3' \
  --test 'deferred free enforces local syntax and pending binding writes' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'deferred free emits independent typed cleanup copies' \
  --test 'deferred free survives source class and archive reconstruction' \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'combined defer cleanup closes and reclaims loopback sockets at O3' \
  --test 'safe free accepts local allocation and ended aliases' \
  --test 'safe free rejects live aliases and escaped allocations' \
  --test 'safe free rejects unknown identities and uncertain control flow' \
  --test 'safe free rejects double free and post-free use' \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'finally transfers preserve ownership at destinations and loop back edges' \
  --test 'finally transfer reclamation runs at every optimization level' \
  --test 'safe free accounts for reference-array element aliases' \
  --test 'unfreed diagnostics preserve retained and reclaimed allocations' \
  --test 'finally preserves primary exceptions and exposes secondary exceptions' \
  --test 'first-failure finally runs at O3' \
  --test 'destructor and constructor effects are checked closed-world' \
  --test 'deterministic destructors and rollback run at O3' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'first-failure finally semantics survive source-path class-path and archive round trips' \
  --test 'safe free lowers to inspectable typed IR and LLVM' \
  --test 'safe free runs natively' \
  --test 'try-with-resources syntax is deliberately rejected' \
  --test 'malformed resource syntax retains parser diagnostics'
```

The existing selection named `finally transfer reclamation runs at every
optimization level` retains its established runner behavior; the new native
fixtures run at `-O3`, without adding an optimization-level matrix.

Coverage and exact outcomes:

- Parser and semantic checks accept safe local object/array/factory cleanup,
  alias expiration, mutation, assignment before registration, separate same-named
  locals, and supported reuse after cleanup. They reject malformed targets,
  forbidden placement, writes while pending, manual/double free, publication,
  outer live aliases, unsafe joins/back edges, pending expression operands,
  returned/yielded references, reversed call/free order, and dependent-owner
  reclamation. Safety cases run under `off`, `warn`, and `error`; suppression
  cannot disable the pending-write error. Nonterminating paths do not invent a
  missing-free diagnostic for unreachable cleanup.
- Typed IR checks find multiple mutually exclusive `IrFreeInstruction` copies
  with one unchanged allocation operand and the original deferred-free span.
  Call-stage checks retain capture dominance, invocation timing, checked
  exceptions, source-finally behavior, and independent exit replay.
- `defer_free.iron` exits **42** at `-O3`. It checks normal completion, both return
  branches, nested/labeled loop transfers, yield, switch fallthrough/skipped
  blocks, inner handled exceptions, failed captures, delayed null failure,
  cleanup failure during return/break/continue/yield, and D051 primary identity
  plus secondary occurrence order. Close failures still attempt all frees.
  Constructor body failure and cleanup-only failure both reclaim the local
  before exactly-once failed-constructor rollback. Pool checkout/release reuses
  state with zero per-iteration allocation; pool destruction precedes builder
  reclamation. String/array cleanup is also exercised. The final live delta is
  exactly **1**, the deliberately retained implicit null-failure exception;
  all named owners are reclaimed.
- `defer_free_socket.iron` exits **42** at `-O3`, using only local loopback. The
  peer receives exactly **42, 43, 44, EOF** after deferred write/flush/close.
  Socket, wrapper, endpoint, address, and byte-array owners are reclaimed.
  After a first cycle initializes process-owned socket factories and option
  inventories, the next cycle has **zero live-allocation growth**. Borrowed
  streams are closed through their owners and are not freed independently.
- Valid source-path, class-directory, and archive reconstruction all link with
  `--unfreed=error` and exit **42**, with stable live counts and cleanup-only
  generic callees retained. Invalid pending writes and early frees fail source
  loading, class/archive dependency compilation, and final-link reconstruction,
  including `--unfreed=off`. The call-stage artifact selection also reruns the
  pre-reservation keyword-collision rejection.

Development failures were corrected in fixtures: an unsupported pool-builder
method name and `var` spelling, a destructor counter whose owning class had an
allocating initializer, an unsafe freed binding on a loop back edge, and live
counts that initially omitted the retained implicit exception or first-use
socket initialization. Existing compiler restrictions were preserved; none was
relaxed to make these fixtures pass.

## Narrow optimized-lowering evidence

After the build above:

```sh
python3 scripts/test-defer-calls.py --kind free --llvm-home /opt/homebrew/opt/llvm
/opt/homebrew/opt/llvm/bin/llvm-size -A \
  integration-tests/target/defer-free-cost/deferred/program \
  integration-tests/target/defer-free-cost/finally/program
```

The paired fixture allocates a 16-byte array and reclaims it once per iteration.
The handwritten baseline uses an equivalent `try`/`finally` with the same local,
body, ownership lifetime, and exceptional cleanup. Both link at `-O3`. Each
process verifies the first call, warms 1,000 iterations, and times 8,000,000
iterations. Eight runs per variant alternate order; output and counter sampling
are outside the loop. The driver records textual LLVM, standalone optimized
assembly, and final linked disassembly separately, since final-link optimization
can inline a function retained by the standalone `opt` output.

| Measurement | Deferred | Ordinary finally |
| --- | ---: | ---: |
| Checksum | 31999996000000 | 31999996000000 |
| First-call allocation/live deltas | 1 / 0 | 1 / 0 |
| Timed allocation/live deltas, every run | 8000000 / 0 | 8000000 / 0 |
| Median elapsed, ns | 153459500 | 155902000 |
| Min to max elapsed, ns | 149510000 to 172852000 | 142096000 to 170330000 |
| Standalone normalized entry/work assembly lines | 158 / 90 | 158 / 90 |
| Linked application-entry instructions | 232 | 232 |
| Linked Mach-O `__TEXT,__text` bytes | 13640 | 13640 |
| `__gcc_except_tab` / `__unwind_info` / `__eh_frame` bytes | 236 / 360 / 528 | 236 / 360 / 528 |

Median deferred/finally ratio: **0.984333**. Both standalone functions and the
linked application entry have identical instructions after normalization. The
linked timed loop is the same 23-instruction region in both variants. It retains
the existing array allocation, length/data loads, destructor dispatch guard,
deallocation, checksum, and loop branch. The entry uses the same 96-byte save
area; the standalone work function uses the same 80-byte save area. No extra
defer registration, TLS access, allocation, synchronization, spill, or helper
call appears on the successful loop path. Existing runtime allocation counters
and process-entry setup are common to both variants.

These measurements are the narrow free-lowering check, not Milestone 2's
full performance acceptance. Timing spread does not establish a speedup.
The full nullable/failure timing matrix, pool/multiple-action scaling and
adoption workloads remain pending.

The updated driver also passed its existing call mode:

```sh
python3 scripts/test-defer-calls.py --kind call --llvm-home /opt/homebrew/opt/llvm
```

That rerun produced checksum **8870876174608992256**, zero allocations and zero
live growth for every timed run, and identical standalone entry assembly
(277 normalized lines) and linked entry instructions (186). Deferred/finally
medians were **12302500 / 12265000 ns**, ratio **1.003057**; ranges were
12206000 to 26322000 / 12205000 to 27152000 ns. The original Stage 1 measurements
remain in their historical verification document.

## Artifacts and final hygiene

Ignored local logs and exact selections are under `workspace/defer-free/`.
Class/archive outputs are under `integration-tests/target/defer-free-artifacts/`.
The paired cost artifacts and raw samples are under
`integration-tests/target/defer-free-cost/`; the call-stage rerun uses
`integration-tests/target/defer-call-cost/`.

The final `git diff --check` and `./scripts/check-licenses.sh` passed (5 existing
OpenJDK-derived source files). Added text passed the prohibited-character and
name checks; 222 relative links in changed documents resolved. Python driver
syntax and the exact 32 successful test selections were checked. New source is
original and uses the default license. Runtime, standard library, examples,
and projects have no changes. Stage 2 stops here for maintainer review;
its completion authorizes neither Milestone 2 nor integration.
