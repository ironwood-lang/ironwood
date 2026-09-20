<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Deferred-call checkpoint verification

Date: 2026-09-19. Scope: only Milestone 1's internal checkpoint in
[DEFER_PLAN.md](DEFER_PLAN.md). Local commit `3ea065f32d52f1cc31c3613fda8e9f879841d4aa` on
`new-defer-keyword` implements deferred void calls and generalized exit
integration, based on `1e249eea7aa89f7d90d545668a09c4500a3a955e`.
Deferred free, full Milestone 1, Milestone 2, and example/project adoption were
pending at that checkpoint. After maintainer acceptance, Stage 2 completed the
[deferred-free and combined checks](DEFER_FREE_VERIFICATION.md); this document
preserves the original call-stage evidence. No merge, rebase, branch switch,
worktree, or push was performed.

## Environment and results

Final behavior checks used Oracle Java 21.0.1 (`javac --release 21`) and LLVM
23.1.0 on macOS 26.6.2 ARM64. Initial development checks used Java 25 with release
21 bytecode; all 29 then-selected tests were subsequently verified with Java 21.
The final native/IR additions passed their two affected selections, and the last
rendering addition passed its native selection and two existing rendering
regressions. Final additions cover destructor field captures and local loopback
output, with affected ownership/destructor/TCP regressions. Overall: **33 distinct
focused selections passed, zero unresolved failures** (7 new checkpoint
selections and 26 existing regressions). Native
fixtures use `-O3`; no unfiltered suite or other platform was run.

The seven new selections cover:

- Reserved keyword and dedicated AST, explicit-block placement and recovery,
  rejected deferred free/non-void/invalid actions, and ordinary access/overloads.
- Lexical checked-exception contexts, cleanup-only checked failures, constructor
  delegation and destructor restrictions, source-finally replay without duplicate
  checked diagnostics, and unreachable source statements.
- Captures as observers in every unfreed mode, including suppression, reassigned
  source locals, dependent socket streams, branch/loop/source-finally frees,
  delayed publication and constructor resurrection. Destructor field frees also
  reject direct and getter-based captures, including later operand calls that
  invalidate the attached-loan lookup. Safe observation followed by
  ordinary free remains accepted; fresh unnamed captures retain missing-free
  obligations. Final-field and local/anonymous capture visitors see operand uses.
- One operand evaluation feeding multiple typed cleanup copies with the same SSA
  value and source span, including reassignment of the original source local.
- Source-path, class-path, and archive native reconstruction (all exit 42),
  unsafe artifact dependency loading and final-link rejection, and format-1
  keyword-collision rejection during source reload.
- Native event sequences for LIFO and nested scopes, return and reference return,
  exceptions, labeled/unlabeled break/continue, primitive/reference yield, switch
  fallthrough and skipped arms, source-finally captures versus late reads,
  primary identity and secondary order, failures superseding pending transfers,
  catch-body failures bypassing siblings, capture failure, delayed null checks,
  initialization timing/failure, superclass/default-interface/generic dispatch,
  constructor rollback, allocation-free destructor cleanup, reusable pool state,
  and fresh String/factory/toString capture lifetime. Intermediate rendering text
  is released both on success and when a later rendering aborts capture. Direct
  unnamed fresh captures intentionally remain live rather than being reclaimed.
- Single-process loopback sockets receive exactly bytes 42, 43, and 44 followed
  by EOF after deferred write/flush/close in LIFO order. Explicit buffer and
  socket reclamation follows completed deferred calls; no external peer is used.

Source visitors were enumerated. Dependency, local-class, effectively-final,
blank-final, anonymous-diamond, escape, owned-array, and symbolic-return walkers
include the call form. PatternFlow treats the declaration as a normally
completing statement; nested expression flow keeps its own scope. Restricted
fresh-factory/data-structure/owned-element recognizers retain their existing
shape boundaries, while typed-IR consumers see ordinary invocation/cleanup CFG.
No generated capture receives an ownership exemption.

Exact selected names, reproducible after selecting Java 21 in `JAVA_HOME` and
placing its `bin` first on `PATH`:

```sh
./scripts/test.sh \
  --test 'try-with-resources syntax is deliberately rejected' \
  --test 'malformed resource syntax retains parser diagnostics' \
  --test 'deferred calls preserve explicit-block syntax and diagnostics' \
  --test 'deferred calls enforce invocation and checked-exception contracts' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred calls replay typed captures across independent exits' \
  --test 'deferred calls survive source class and archive reconstruction' \
  --test 'deferred calls preserve exit and failure order at O3' \
  --test 'deferred calls flush and close loopback sockets at O3' \
  --test 'methods and constructors overload by parameter signature' \
  --test 'overload resolution diagnoses no match and ambiguity' \
  --test 'generic callables capture wildcard receivers without planning side effects' \
  --test 'generic calls preserve conservative safe-free summaries' \
  --test 'finally preserves primary exceptions and exposes secondary exceptions' \
  --test 'receiver-retained borrows run natively' \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'finally transfers preserve ownership at destinations and loop back edges' \
  --test 'finally transfer reclamation runs at every optimization level' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'TCP facade preserves typed options and result ownership' \
  --test 'destructor and constructor effects are checked closed-world' \
  --test 'super field and method access lower as direct superclass operations' \
  --test 'first-failure finally semantics survive source-path class-path and archive round trips' \
  --test 'overloads and constructor delegation run natively' \
  --test 'interface constants defaults helpers and qualified super run at O3' \
  --test 'static initialization ordering cycles and failures run at O3' \
  --test 'first-failure finally runs at O3' \
  --test 'deterministic destructors and rollback run at O3' \
  --test 'borrow dispatch uses exact overloads defaults and receiver flow' \
  --test 'borrow dispatch rejects retaining and unknown receiver flows' \
  --test 'nested finally failures retain ordered independent traces' \
  --test 'Object String concatenation releases implicit rendering results' \
  --test 'Object and collection rendering reclaim temporary text at O3'
```

During development, fixture assertions exposed incorrect test API accessors,
missing `@Override`, a nonexistent exception type, and a missing-free diagnostic
wording mismatch; these were corrected. Destructor testing also exposed the
real conservative analysis issue addressed in this checkpoint: generated
cleanup rethrows must not count as escaping exceptions when their unwind
predecessors are proved unreachable. Closed-world effect reachability now
converges with callee effects; unknown calls and potentially allocating or
throwing targets retain their unwind edges. The existing destructor and
constructor safety selections pass, including rejected unsafe cases.
The final destructor capture checks also cover the specialized field-free path
and preserve getter-result field provenance. Existing null-check/destructor
restrictions are unchanged; accepted field-observation fixtures use implicit
field access. Restricted creation-array loads/calls retain their existing
ownership boundaries rather than gaining a deferred-call exemption.

## Focused optimized-call check

```sh
python3 scripts/test-defer-calls.py --llvm-home /opt/homebrew/opt/llvm
/opt/homebrew/opt/llvm/bin/llvm-size -A \
  integration-tests/target/defer-call-cost/deferred/program \
  integration-tests/target/defer-call-cost/finally/program
```

The paired fixture saves two primitive operands per loop iteration and performs
one void cleanup call after the same body operation. The baseline uses those
same saved operands in ordinary `try`/`finally`. Both are compiled and linked
with LLVM 23 at `-O3`, with identical input and checksum work. Eight executions
per variant alternate order; each process checks the first successful call,
warms with 1,000 iterations, then times 8,000,000 iterations. Output and
allocation-counter reads occur outside the timed loop.

| Measurement | Deferred | Ordinary finally |
| --- | ---: | ---: |
| Checksum | 8870876174608992256 | 8870876174608992256 |
| First-call allocation/live deltas | 0 / 0 | 0 / 0 |
| Timed allocation/live deltas, every run | 0 / 0 | 0 / 0 |
| Median elapsed, ns | 12450500 | 12545500 |
| Min to max elapsed, ns | 12336000 to 27444000 | 12374000 to 27272000 |
| Normalized application entry assembly lines | 277 | 277 |
| Linked Mach-O `__TEXT,__text` bytes | 14024 | 14024 |
| `__gcc_except_tab` / `__unwind_info` / `__eh_frame` bytes | 244 / 472 / 888 | 244 / 472 / 888 |

Median deferred/finally ratio: 0.992428. Timing spread does not establish a
speedup. Normalized optimized application-entry assembly, including the inlined
work loop, is identical. Linked disassembly confirms the same ten-instruction
loop: arithmetic, comparison and branch only, with no calls, allocation,
registration, TLS access, synchronization, spills or loads/stores in that loop.
Existing entry setup and the measurement/output helpers remain outside it.
Both textual LLVM and machine code were inspected; no defer runtime ABI or
action-storage mechanism was introduced.

This is narrow call-lowering evidence, **not full Milestone 2 acceptance**.
Multiple-action/code-size scaling, the full nullable/failure timing matrix,
combined deferred free, and adoption workloads were pending at this checkpoint.
Stage 2's combined checks are recorded separately. No other platform performance
claim is made.

## Artifacts and hygiene

The automated artifact test constructs old format-1 source containers directly.
An additional check built a real keyword-collision class/archive using the exact
pre-reservation `Lexer.java` from the base commit on an isolated compiler
classpath, then loaded both with the new lexer. Both final links rejected the
old `defer` field identifier with `expected member name`. No repository checkout
or branch was changed for this check.

Local logs and the exact final selections are under ignored
`workspace/defer-checkpoint/`; class/archive outputs are under
`integration-tests/target/defer-artifacts/`. The cost driver preserves source,
LLVM, optimized assembly, linked disassembly, run samples and `report.json` under
`integration-tests/target/defer-call-cost/`.

`git diff --check` passed. The required `./scripts/check-licenses.sh` passed
with 5 existing OpenJDK-derived source files; this checkpoint introduces only
original default-licensed source. Added text passed the prohibited-character and
name checks, and changed-document relative links were checked. Runtime, stdlib,
examples and projects had no changes. Stage 1 stopped for maintainer review
before deferred free was explicitly selected.
