# Important native optimizations

This document explains Ironwood's native compiler optimizations: the LLVM
lowering constraints they address, why they preserve semantics, how they
interact with stack traces, and how they were verified. The maintained OrderBook
project provides a reproducible paired Ironwood and Java benchmark on Linux.
The normative summaries live in
[COMPILER.md](COMPILER.md) and in decision D134 of
[DECISIONS.md](DECISIONS.md); read those first for the short version.

The official Linux throughput and batch-latency results and measurement
protocol are maintained in [BENCHMARK.md](BENCHMARK.md).

Nothing in this work changed the language, the typed IR, or program semantics.
All changes are in the LLVM emitter, the `opt` options selected by `-O3`, and
the runtime's on-demand stack-trace decoder.

## 1. The benchmark

`projects/OrderBook/src/main/ironwood/org/ironwood/orderbook/Bench.iron` is a
deterministic throughput benchmark over a fixed-capacity matching engine. Each
cycle performs eight operations against a book that starts and ends empty: four
limit orders (two bids, two asks), a `reduceTo`, a `cancel`, and two market
orders that sweep the book. The two arguments are the warmup and measured
operation counts in millions, and the only output is the measured time in
nanoseconds:

```console
$ cd projects/OrderBook && ./compile.sh && ./link.sh
$ ./throughput.sh 10 100
```

The paired Java source under `projects/OrderBook/java` ships the same `Bench`
class with the same arguments and output, so the two numbers are directly
comparable. Both programs are allocation-free in steady state: orders and price
levels come from fixed pools, and the benchmark validates full pool recovery.

## 2. Diagnosis

### 2.1 Sources of avoidable call overhead

Small accessors, pool operations, and listener callbacks can remain out of line
when dispatch or large failure paths hide their simple normal behavior from
LLVM. Each surviving call requires argument passing and control transfer.
Calls into the separately compiled C runtime also prevent LLVM from inlining
the runtime implementation into generated application code.

The maintained OrderBook benchmark isolates matching and fixed-pool reuse.
The collection, listener, and String examples below explain compiler mechanisms;
measuring their effect in other applications requires Linux comparisons with
the same workload in both languages.

### 2.2 Why LLVM kept them out of line

LLVM's inliner reports every rejected call site when `opt` runs with
`-pass-remarks-missed=inline`. Inspect these remarks on the Linux benchmark
build to identify which calls exceed its inline budget.

`ArrayList.get` has a small normal path, but its failure paths add to the
inliner's estimated cost. Every implicit safety check (null receiver, array
bounds, array length, division, checked cast) lowers to a block that allocates
the exception object, calls its constructor and calls `ironwood_throw`; an
explicit `throw new X(...)` additionally runs the type-initialization barrier
for `X`, invokes the constructor with a rollback landing pad for failed
construction, and null-checks the fresh object before throwing it. `get` had
three such paths (two null checks and the bounds check) plus the explicit
`IndexOutOfBoundsException` of the inlined `checkBounds`. LLVM's cost model has
no notion of an uncommon trap: it counts every call in those blocks at full
price, so the estimated method cost includes more than its hot path.

A second effect compounds the first. LLVM strongly prefers to inline an
internal function that has a single call site, because inlining removes the
function entirely. `ArrayList.grow()` is called only from `add`, so inlining
it into `add` brings `Math.round`, an array allocation, a bulk
copy and a `free` with it. That growth can keep `add` itself out of line.
The same concern applies to pool growth and `StringBuilder.ensureCapacity`.

Third, an interface callback can have multiple implementations in the closed
world. Class-hierarchy analysis alone devirtualizes calls with exactly one
target. A call that retains table dispatch hides its targets from LLVM.

Fourth, the emitter lowered `String.charAt` to `ironwood_string_char_at`, a
function in the C runtime object; the program and the runtime are separate
translation units, so the per-character call could never be inlined.

Fifth, the IR contained null checks LLVM could not fold: the receiver of every
instance method was checked again inside callees (LLVM does not know that
`this` is never null), and `throw new X()` null-checked the object that
`ironwood_allocate` had just returned (LLVM did not know the allocator never
returns null).

### 2.3 Evaluating pipeline choices on Linux

The compiler's `--emit-llvm` option writes the module that goes into the
pipeline, so the pipeline can be re-run by hand with different `opt` and `llc`
options. Evaluate each change on Linux with a fixed workload, matching warmup
and measurement counts, and repeated trials. Inspect the optimized IR and native
code alongside the timings. The official results in [BENCHMARK.md](BENCHMARK.md)
measure the complete supported configuration; attributing a speedup to one
option requires a separate controlled comparison.

## 3. The optimizations

### 3.1 Cold failure-path outlining

The emitter now recognizes two block shapes in the typed IR of every function
and emits each as one call to a shared helper instead of inline code.

The bundled shape is a block containing exactly an `IrAllocateInstruction` and
an `IrCallInstruction` to a constructor of that class whose first argument is
the allocation, terminated by an `IrThrowTerminator` of the allocation with no
unwind target. This is how every implicit check fails.

The explicit shape is the lowering of `throw new X(...)` outside any local
handler: a block with an optional `IrEnsureTypeInitializedInstruction` for `X`
and the allocation, terminated by an invoke of the constructor; an unwind block
holding the landing pad, the `IrRollbackInstruction` of the allocation and a
rethrow with no unwind target; a continuation block holding only the null check
of the allocation and its branch; a throw block with no instructions; and a
failure block of the bundled shape. Each of the four absorbed blocks must be
referenced exactly once in the function, so nothing else can jump into the
middle of the sequence. Sequences inside a `try` (whose allocation, constructor
and throw carry unwind edges to the local landing pad) are left inline.

Both shapes lower to:

```llvm
null.failure.4:
  call void @llvm.pseudoprobe(...)      ; the sites of the outlined operations
  call void @llvm.pseudoprobe(...)
  call void @llvm.pseudoprobe(...)      ; the throw site is last
  call void @"ironwood.throw.ironwood.ironwood.lang.NullPointerException.<init>$void"() nomerge, !dbg !N
  unreachable
```

One helper exists per constructor and shape, named after the constructor's
linkage name with `$ensure` and `$rollback` suffixes, and takes the constructor
arguments other than the allocation as parameters:

```llvm
define internal void @"ironwood.throw....IllegalStateException.<init>$ref_...String$ensure$rollback"(ptr %a0)
    noinline cold noreturn personality ptr @__gxx_personality_v0 {
entry:
  call void @"ironwood.initialize.ironwood.lang.IllegalStateException"()
  %size.ptr = getelementptr %"ironwood.class.ironwood.lang.IllegalStateException", ptr null, i32 1
  %size = ptrtoint ptr %size.ptr to i64
  %exception = call ptr @ironwood_allocate(i64 %size, ptr @"ironwood.typeinfo....", ptr @"ironwood.immortal.implicit allocation failure")
  invoke void @"ironwood.ironwood.lang.IllegalStateException.<init>$ref_ironwood_dlang_dString"(ptr %exception, ptr %a0)
      to label %throw unwind label %rollback
rollback:
  %landing = landingpad { ptr, i32 } catch ptr null
  %handle = extractvalue { ptr, i32 } %landing, 0
  %pending = call ptr @ironwood_exception_take(ptr %handle)
  call void @"ironwood.rollback"(ptr %exception)
  call void @ironwood_throw(ptr %pending)
  unreachable
throw:
  call void @ironwood_throw(ptr %exception)
  unreachable
}
```

Why this preserves behavior:

- The helper performs exactly the sequence the block performed, including the
  initialization barrier and failed-construction rollback. Exceptions raised by
  the constructor or the allocator unwind through the helper into the caller
  exactly as they unwound from the inline code, because the outlined shapes
  have no local handler by construction.
- The absorbed blocks have no successors outside the sequence, so removing them
  changes no phi and no control-flow edge elsewhere.
- Stack traces keep their lines. The pseudo probes of the outlined operations
  are still emitted in the caller, immediately before the helper call, with the
  throw statement's probe last so that the decoder's tie-break on equal
  addresses selects the throw line. The helper itself has no probes, so the
  runtime resolves no site for its frame and skips it, and the frames above it
  (the exception constructors) are already hidden by the existing rules.
- `nomerge` on the call site is required. Without it, LLVM merges identical
  `invoke @ironwood.throw.NullPointerException()` blocks of one function into a
  single shared block with several predecessors, and the shared return address
  no longer sits next to the probes of the site that actually failed. This was
  observed as a missing inlined frame at `-O3` and fixed by the attribute.

The effect on the inliner is immediate: `ArrayList.get` became a bounds check,
a load and three cold calls, and every accessor listed in section 2.2 dropped
well under the budget or into it.

### 3.2 Guarded closed-world dispatch

Semantic lowering keeps its exact singleton-target devirtualization. On top of
that, the emitter computes, for every dispatch slot, the receivers that can
reach it at run time: the dispatch entries of every class that is instantiated
anywhere in the pruned program (allocation instructions, immortal objects, enum
constant storage, `String`, `StackTraceElement`) plus every array type. A
virtual or interface call whose slot has at most four such receivers and at
most three distinct targets lowers to the following representative shape. Type
names are shortened for clarity:

```llvm
  %ironwood.guard.typeinfo.2 = load ptr, ptr %v8
  %ironwood.guard.test.3 = icmp eq ptr %ironwood.guard.typeinfo.2, @"ironwood.typeinfo.example.BenchmarkTimestamper"
  br i1 %ironwood.guard.test.3, label %null.valid.9.guard.0.call.0, label %null.valid.9.guard.0.test.1
null.valid.9.guard.0.test.1:
  %ironwood.guard.test.4 = icmp eq ptr %ironwood.guard.typeinfo.2, @"ironwood.typeinfo.example.SystemTimestamper"
  br i1 %ironwood.guard.test.4, label %null.valid.9.guard.0.call.1, label %null.valid.9.guard.0.fallback
null.valid.9.guard.0.call.0:
  %ironwood.guard.result.5 = call i64 @"ironwood.example.BenchmarkTimestamper.nanoEpoch"(ptr %v8), !dbg !1701
  br label %null.valid.9.guard.0.join
null.valid.9.guard.0.call.1:
  %ironwood.guard.result.6 = call i64 @"ironwood.example.SystemTimestamper.nanoEpoch"(ptr %v8), !dbg !1701
  br label %null.valid.9.guard.0.join
null.valid.9.guard.0.fallback:
  ; ordinary table dispatch through the descriptor
  %ironwood.guard.result.11 = call i64 %ironwood.guard.callee.10(ptr %v8), !dbg !1701
  br label %null.valid.9.guard.0.join
null.valid.9.guard.0.join:
  %v11 = phi i64 [ %ironwood.guard.result.5, %null.valid.9.guard.0.call.0 ], [ %ironwood.guard.result.6, %null.valid.9.guard.0.call.1 ], [ %ironwood.guard.result.11, %null.valid.9.guard.0.fallback ]
```

Design points:

- Comparing the descriptor pointer costs one load and one compare per
  receiver, against three dependent loads for comparing the table entry.
  Receivers that share a target branch to the same call block.
- The fallback keeps correctness independent of the instantiability heuristic.
  A receiver the emitter did not list still dispatches correctly, only without
  the direct call.
- When the call is an invoke (it has a local handler), every direct call and
  the fallback invoke to a shared join block and unwind to the original landing
  pad. The emitter plans each function's block layout before emitting it and
  rewrites successor phis: the normal successor now sees the join block as its
  predecessor, and the landing pad sees every call block and the fallback.
- The typed IR is untouched, so IR inspection, borrow analysis and the
  `DEVIRTUALIZED_*` markers behave as before.

For small receiver sets, these guards expose direct call targets that LLVM can
inline into the caller. Inspect the Linux build's optimized output to determine
which listener or other interface calls are inlined in a particular workload.

### 3.3 Facts LLVM could not infer

Three declarations now state what the language guarantees:

- The receiver parameter of every instance callable is `nonnull noundef`. Every
  explicit receiver is null-checked before the call or dispatch, allocation
  never yields null, and the destroy and rollback helpers test for null before
  reaching a destructor or rollback body, so `this` never carries null. LLVM
  folds the redundant receiver checks that callees used to repeat.
- `ironwood_allocate` and `ironwood_allocate_array` are `noalias nonnull`:
  they return fresh storage or raise the allocation failure. The null check on
  a freshly allocated object in `throw new X()` folds away.
- `ironwood_throw` is `noreturn cold`. Blocks that end in a throw are laid out
  as cold code, which keeps hot paths dense even where a failure sequence is
  not outlined.

### 3.4 Inline `String.charAt`

The runtime string layout is `{ descriptor, utf16Length, utf8Length, units[] }`.
The emitter declares `%"ironwood.string" = type { ptr, i32, i32, [0 x i16] }`
and lowers `IrStringCharAtInstruction` to a `getelementptr` into the unit array
and a 16-bit load. The bounds check stays in `String.charAt`; the instruction
was already reserved for indices that passed it. Source loops that copy text
character by character no longer call the runtime for each character.

### 3.5 `-O3` pipeline options

`-O3` now runs `opt -passes='default<O3>' -inline-threshold=1000
-enable-partial-inlining`. `-O2` keeps LLVM's defaults.

The threshold quadruples LLVM's C-oriented default. The whole closed-world
program is one module and Java-shaped code is dominated by small methods, so
the higher budget allows methods such as `ArrayList.add` to inline even when
LLVM has already folded their single-caller slow path into them. This can
increase code size. Partial inlining handles the early-return guards common in
this code base: a method such as `reportOrderBookListenerExceptionsIfNecessary`
starts with two flag tests that almost always return, followed by a large loop
with exception handling. LLVM inlines the guard and moves the body into an
outlined function that is called only when a guard fails.

### 3.6 Stack traces through outlined code

D132 resolves stack traces on demand from native return addresses and LLVM
pseudo-probe metadata. Native frame ownership comes from the unwinder's
function-start address, matched exactly against the registered function table.
An address-order search bounded by a final code marker is insufficient: a
linker can place cold functions such as a throwing cleanup method after
the marker, interleaved with runtime functions. Exact matching preserves those
frames without adding work outside exception capture.

Partial inlining exposed two further gaps, both fixed in the
runtime decoder in `runtime/src/ironwood_runtime.c`.

When LLVM outlines part of `process` into `process.13.if.merge.2`, the new
function receives its own debug subprogram, and probes of callees inlined into
the outlined body are filed under the outlined symbol's GUID. The site table
only knows `process`'s GUID, so the outlined frame resolved to no site and
disappeared. The decoder now records, while scanning the probe section for a
frame, every top-level node whose code lives in the frame's function symbol
(these are siblings: the outlined body's own probes still carry `process`'s
GUID), and retries a missing site against those sibling GUIDs.

The caller of an outlined body keeps an inlined copy of the function's hot
part, so the calling frame's nearest probe is `process`'s entry and the trace
would show `process` twice for one activation. The decoder now treats an
outlined body and the innermost site of the next frame for the same function
as one activation and drops the duplicate. Real recursion is unaffected: a
recursive frame runs in the function's own symbol, which is never classified
as an outlined body, so its frames are all reported. The check programs in
section 5 exercise exactly these cases.

### 3.7 Guarded fully initialized specialization

The post-validation typed-IR pass versions selected loop-containing functions
under read-only state-2 entry guards. A failed guard enters the original CFG;
it does not initialize a type. Zero-trip loops, untaken branches, recursive
state-1 observations and cached state-3 failures retain their original behavior.
The fast body removes ensures only for the guarded types. Guardless clones of
existing direct callees carry those facts without repeating entry tests. Calls
whose target remains virtual or interface-dispatched retain their original form.

Published enum constants can replace static pointer loads by their immortal
object addresses only when the declaring type is proven state 2, the field is
final, and the typed program contains exactly one store publishing that exact
compiler-owned object from its declaring class initializer. Normal completion
of an ensure permits state 1 and cannot provide this proof. No state facts are
inferred from normal calls or exported to callers.

Initialized enum-field propagation additionally substitutes final `int`/`long`
payloads for exact enum receivers inside these existing fast paths. A proof
requires a straight-line initializer with exactly one constructor call and
publication per constant, literal arguments, and bounded constructor paths
containing only known reference copies, non-null checks, final stores and
proved superclass constructors. Each evaluated body is limited to 256 operations
and each superclass chain to nine bodies; loops and unknown instructions decline
the proof. Repeated/foreign construction, foreign field writes and native field
addresses also decline it. Constant-specific subclasses and constructor
delegation retain their loads. Runtime constructors and publication remain.
Direct or devirtualized accessor calls fold only if the same bounded evaluation
proves their result without effects or possible exceptions for that exact
receiver. Original caller checks, evaluated operands, fallback paths and layouts
remain. Source/class/archive reconstruction preserves validated final metadata.
This adds no guards or steady-state bookkeeping. The maintainer accepted it as
a small Linux OrderBook latency gain (D177): median paired average and p99
changes were -1.98% and -2.02%, both improving in 19/20 pairs against `9217418`.
Throughput and extreme tails remain inconclusive. These are workload-specific
results; the protocol, identities and limits are recorded in
[PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-retained-enum-field-propagation).

The initial general policy considers reachable methods containing a CFG cycle,
at most four required types and 1,200 typed instructions/terminators per body.
It copies at most 32 functions and 4,096 operations per group. Total copied-body
budget is the smaller of 8,192 operations and half the input program's operation
count, plus bounded guard prefixes. The group must expose at least twice as
many removable ensures/immutable enum loads as guard types. Roots are ordered by
local removable operations and deterministic linkage order; a root already
included in another group is skipped. These are static profitability estimates,
not profile feedback or a guarantee for every workload. Recursive graphs use a
finite type-demand fixed point and one guardless version per function per group;
a recursive root's additional copy is charged to the same budget.

Both root CFGs stay in one source function. Callee clones retain original source
names, kinds, files and spans with distinct native linkage and trace probes.
SSA and labels are renamed explicitly; deleted unwind edges remove unreachable
cleanup and obsolete phi inputs. Reachability retains guard types, clones and
direct enum storage. There is no per-operation state maintenance, allocation,
TLS, runtime registry, new PGO or global inline-policy change.

Local macOS ARM64 code, correctness, size and paired official-argument timing
evidence is in [PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md).
Linux execution remains a separate validation step; these observations do not
establish a Linux speedup.

### 3.8. Exact field value forwarding

After initialized and enum-argument specialization, `FieldValueForwarder` reuses
an integer/reference field's previously loaded or stored value for the exact
receiver. Storage identity is the declaring owner and layout slot. A write to
that slot invalidates all receivers' cached values before recording the new
exact value, because different receiver values can alias. Different slots and
array/static stores do not overlap instance-field storage in validated typed IR.
Reference copies preserve identity; repeated array loads and phi values do not
establish it. Floating-point forwarding and array/static value tracking are
outside this pass.

Facts flow only along single-predecessor paths. Joins, loop entry merges and
unwind edges start empty; backedges never revisit a block with cached facts.
Unknown calls, native operations, initialization and reclamation clear facts.
Leaf getter/setter summaries admit at most 64 typed operations, one acyclic
nonnull-receiver path, direct receiver field accesses, parameter/constant stores
and reference copies. Null-check alternatives must have no normal return.
Calls remain unless a pure getter has a previously established exact field
value, which also proves nonnull access. Removed getter invokes become normal
jumps; unreachable cleanup blocks and phi inputs are repaired. Retained paths
preserve stores, evaluated operands, caller checks and source spans.

This adds no runtime checks, alias metadata, freshness assumptions or ownership
exemptions. Source/class/archive links reconstruct the same validated typed IR.
The maintainer accepted this pass after the independent Linux comparison against
`9217418`, with D177 excluded from both snapshots (D178). Median paired average
batch latency and p99 changed -4.93% and -5.08%, both lower in 19/20 pairs;
throughput elapsed time changed -3.32%, lower in 7/8 pairs. Extreme tails remain
mixed, and combined performance with D177 is unmeasured. The workload-specific
results, source/binary identities and protocol are recorded in
[PERFORMANCE_IMPROVEMENTS.md](PERFORMANCE_IMPROVEMENTS.md#round-2-stage-4-retained-field-value-forwarding).

## 4. Official Linux results

[BENCHMARK.md](BENCHMARK.md) is the source for official OrderBook throughput
and batch-latency results on Linux, including the workload, hardware, runtimes,
and measurement protocol. It compares the maintained allocation-free engine
compiled with Ironwood `-O3` against Oracle JDK 25 and GraalVM 25 in JVM mode.

Those measurements establish the result for the complete compiler configuration
and tested workload. They do not isolate each optimization's contribution or
establish results for a different application.

## 5. Verification

- `projects/OrderBook`: the Ironwood and Java demonstrations produce identical
  output, and the benchmark validates completed work and full pool recovery.
- `scripts/test-stdlib.sh`: 107 standard-library tests pass at `-O3`.
- Focused compiler tests: the 15 stack-trace tests, and 32 further tests
  covering dispatch and type tests, multi-catch, finally transfers, safe free,
  destructors and rollback, catchable implicit failures, allocation-failure
  boundaries, generics, strings, builders, concatenation and text blocks.
- Two structural assertions were updated to the new lowering: the interface
  fixture now expects the guard fallback's table dispatch, and the string
  intrinsics test expects the inline unit load instead of the runtime call.
- Hand-written programs compared at `-O0` and `-O3`: an uncaught bounds
  failure in a helper, an explicit `throw new` with a message, a null receiver
  in an inlined helper, a four-level recursion failing at the bottom, and a
  throw from inside a partially inlined body. All report identical frames and
  lines at both levels.
- `scripts/check-licenses.sh` and `git diff --check` pass.

## 6. Alternative pipeline choices

- Pseudo probes lower to read-only metadata and emit no executable instructions.
  Retain them to preserve on-demand source traces.
- Late hot/cold splitting (`-hot-cold-split=true`) runs after inlining decisions,
  so it cannot change decisions already made by the inliner.
- Early hot/cold splitting (`hotcoldsplit` before `default<O3>`) with a `cold`
  `ironwood_throw` can separate failure paths before inlining. This can prevent
  LLVM from deleting failure paths that become provably dead after inlining.
- Marking declarations (`cold` throw, `nonnull` allocators) supplies facts for
  code layout and null-check folding. Outlining separately reduces the failure
  machinery included in the surrounding method's inline cost.
- Outlining in the frontend by synthesizing typed-IR helper functions: rejected
  in favor of the emitter because it would change typed-IR tests, borrow and
  escape summaries, and trace-site planning for a purely backend concern.

## 7. Limitations and future work

- Failure sequences inside a `try` region remain inline because their
  operations carry unwind edges to the local landing pad. Outlining them needs
  an invoke of the helper plus removal of the absorbed blocks' phi entries in
  the landing pad; the plumbing exists, the pattern match does not.
- LLVM still inlines single-caller slow paths such as `grow()` into their
  callers before considering the caller; the larger budget absorbs this, but a
  static "resize path is cold" heuristic would let the budget shrink again.
- The instantiability heuristic is a performance filter only. A class it misses
  costs the guard's fallback, never correctness. Arrays are always included.
- The guard limits (four receivers, three targets) were chosen for focused
  two-implementation interface cases; wider sets could use a switch on type id.
- The receiver classes for a guard are compared in declaration order. Ordering
  by expected frequency is not available without profiles.
- The trace decoder's sibling search is bounded at eight top-level nodes per
  symbol.

## 8. Running the current benchmark and inspecting the output

Build and time both programs on the Linux benchmark host:

```console
$ cd projects/OrderBook
$ ./compile.sh && ./link.sh && ./throughput.sh 10 100
$ ./java/compile.sh && ./java/throughput.sh 10 100
```

Use matching arguments for both implementations. Run them as separate
processes, alternate their order across multiple independent trials, and
compare medians, as described in the [OrderBook guide](../projects/OrderBook/README.md#throughput).
Keep the processes sequential so they do not compete for processor resources.
The commands above use the scripts' default counts; the official throughput
results use 8 million warmup and 80 million measured operations. Follow
[BENCHMARK.md](BENCHMARK.md#throughput-warmup-and-timing) for those settings and
record trial counts, process order, and aggregation when collecting new results.

Collect native profiles on Linux with the same workload and compiler settings.
Keep profiling runs separate from the published timing runs.

Inspect the emitted module, the optimized module, and the inliner's decisions:

```console
$ ironwoodc --link -cp target/classes --main-class org.ironwood.orderbook.Bench \
      -o /tmp/bench -O3 --emit-llvm /tmp/bench.ll
$ llvm-as /tmp/bench.ll -o /tmp/bench.bc
$ opt -passes='default<O3>' -inline-threshold=1000 -enable-partial-inlining \
      -pass-remarks-missed=inline -S /tmp/bench.bc -o /tmp/bench.opt.ll 2> /tmp/inline.txt
$ grep -c '^define internal void @"ironwood.throw\.' /tmp/bench.ll     # outlined helpers
$ grep -c '\.guard\.[0-9]*\.join:' /tmp/bench.ll                       # guarded call sites
$ grep "cost=" /tmp/inline.txt | sort | uniq -c | sort -rn | head        # remaining rejections
```

The optimized module shows which functions survive as call targets; `nm` on
the executable shows the `ironwood.throw.*` helpers and any LLVM-outlined
bodies such as `process.13.if.merge.2`.
