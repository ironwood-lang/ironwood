# Important native optimizations

This document is the detailed account of the September 2026 performance work
that improved Java-shaped native workloads. The maintained OrderBook project
provides a reproducible paired Ironwood and Java benchmark. This document
explains how the slowdown was diagnosed, what each optimization does and why it
is safe, how the pieces interact with stack traces, how everything was
verified, and which experiments were rejected. The normative summaries live in
[COMPILER.md](COMPILER.md) and in decision D134 of
[DECISIONS.md](DECISIONS.md); read those first for the short version.

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

### 2.1 Where the time went

The original investigation used a broader internal order-book workload with
listeners and formatted reporting. The maintained OrderBook project isolates
the matching and pooling hot path, while the original profile remains useful
for explaining why the compiler changes were needed.

The macOS `sample` profiler (1 ms interval, six seconds of the measured loop)
gave a flat top-of-stack profile spread over many small functions:

| Function | Samples |
|---|---|
| `OrderBook.createOrder` | 497 |
| `OrderBook.fillOrRest` | 444 |
| `Order.execute` | 428 |
| `OrderBook.checkExternalListenerReentrancy` | 424 |
| `ironwood.ds.ArrayList.get` | 408 |
| `ironwood.ds.ArrayList.add` | 340 |
| `OrderBook.removeOrder` | 282 |
| `OrderBook.reportOrderBookListenerExceptionsIfNecessary` | 274 |
| `OrderBook.match` | 250 |
| `ironwood.pool.ArrayObjectPool.release` | 226 |
| `ironwood.pool.ArrayObjectPool.get` | 190 |
| `ironwood_string_char_at` (C runtime) | 177 |
| `Order.accept`, `Order.reportListenerExceptionsIfNecessary` | 161 each |
| `StringBuilder.ensureCapacity` | 133 |
| `OrderBook$InternalOrderListener.onOrder*` callbacks | about 300 total |

Three facts stand out. Trivial accessors such as `ArrayList.get`, a one-line
flag test such as `checkExternalListenerReentrancy`, and pool `get`/`release`
were real call targets, each paying prologue, epilogue, argument marshalling
and a branch. `String.charAt` reached a C function compiled separately from the
program, so LLVM could not inline it. The listener callbacks were interface
calls with two implementations, dispatched through the table for every order
event. A Java JIT inlines all of these: the accessors because they are tiny,
the callbacks through its type profile, and `charAt` as an intrinsic.

### 2.2 Why LLVM kept them out of line

LLVM's inliner reports every rejected call site when `opt` runs with
`-pass-remarks-missed=inline`. With the original emitter the remarks looked
like this (`-O3` uses an inline threshold of 250):

| Callee | Estimated cost |
|---|---|
| `ArrayList.get` | 660 |
| `ArrayList.add` | 1410 |
| `OrderBook.checkExternalListenerReentrancy` | 380 to 505 |
| `OrderBook.reportOrderBookListenerExceptionsIfNecessary` | 645 |
| `ArrayObjectPool.get` | 930 |
| `ArrayObjectPool.release` | 310 to 745 |
| `StringBuilder.append(char)` | 305 to 425 |
| `StringBuilder.ensureCapacity` | 530 |
| `Order.reportListenerExceptionsIfNecessary` | 670 to 995 |

`ArrayList.get` is one bounds check and one array load. Its cost of 660 came
from its failure paths. Every implicit safety check (null receiver, array
bounds, array length, division, checked cast) lowers to a block that allocates
the exception object, calls its constructor and calls `ironwood_throw`; an
explicit `throw new X(...)` additionally runs the type-initialization barrier
for `X`, invokes the constructor with a rollback landing pad for failed
construction, and null-checks the fresh object before throwing it. `get` had
three such paths (two null checks and the bounds check) plus the explicit
`IndexOutOfBoundsException` of the inlined `checkBounds`. LLVM's cost model has
no notion of an uncommon trap: it counts every call in those blocks at full
price, so the method looked ten times larger than its hot path.

A second effect compounds the first. LLVM strongly prefers to inline an
internal function that has a single call site, because inlining removes the
function entirely. `ArrayList.grow()` is called only from `add`, so it was
always inlined into `add`, bringing `Math.round`, an array allocation, a bulk
copy and a `free` with it. `add` then cost 1410 and was itself never inlined.
The same happened to the pool's `grow` and to `StringBuilder.ensureCapacity`.

Third, `OrderListener` has two implementations in this closed world (the book's
and the price level's internal listeners). Class-hierarchy analysis only
devirtualizes calls with exactly one target, so every callback went through the
dispatch table, which LLVM cannot see through.

Fourth, the emitter lowered `String.charAt` to `ironwood_string_char_at`, a
function in the C runtime object; the program and the runtime are separate
translation units, so the per-character call could never be inlined.

Fifth, the IR contained null checks LLVM could not fold: the receiver of every
instance method was checked again inside callees (LLVM does not know that
`this` is never null), and `throw new X()` null-checked the object that
`ironwood_allocate` had just returned (LLVM did not know the allocator never
returns null).

### 2.3 Experiments that confirmed the diagnosis

The compiler's `--emit-llvm` option writes the module that goes into the
pipeline, so the pipeline can be re-run by hand with different `opt` and `llc`
options. Measured on the original emitter's IR with 3 million warmup and 50
million measured operations:

| Variant | Time |
|---|---|
| `opt -passes='default<O3>'` (the shipped pipeline) | 1.55 s |
| plus `-inline-threshold=1000` | 1.32 s |
| pseudo probes stripped from the IR | 1.56 s |
| `-inline-threshold=1000`, probes stripped | 1.34 s |
| `-inline-threshold=1000`, `llc -mcpu=apple-m4` | no change (noise) |
| `-hot-cold-split=true` | 1.57 s |
| `-enable-partial-inlining` | 1.42 s |
| `-enable-partial-inlining -inline-threshold=1000 -hot-cold-split=true` | 1.23 s |

Inlining was the lever: a larger inline budget alone tied Java. The trace
probes and the target CPU setting cost nothing. The structural changes below
attack the cause instead of only raising the budget, and the budget increase
was kept because Java-shaped code still benefits from it once the cause is
removed.

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

In the benchmark this turned the `Timestamper.nanoEpoch()` call and every
`OrderListener` callback into direct calls that LLVM then inlined into the
listener loops of `Order`, the same shape HotSpot produces with bimorphic
inlining.

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
the trade is a few percent of code size for the ability to inline methods such
as `ArrayList.add` whose single-caller slow path LLVM insists on inlining
first. Partial inlining handles the early-return guards that are common in this
code base: a method such as `reportOrderBookListenerExceptionsIfNecessary`
starts with two flag tests that almost always return, followed by a large loop
with exception handling. LLVM inlines the guard and moves the body into an
outlined function that is called only when a guard fails.

### 3.6 Stack traces through outlined code

D132 resolves stack traces on demand from native return addresses and LLVM
pseudo-probe metadata. Native frame ownership comes from the unwinder's
function-start address, matched exactly against the registered function table.
An address-order search bounded by a final code marker is insufficient: the
macOS linker can place cold functions such as a throwing cleanup method after
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

## 4. Results

The following measurements were captured on the development workload while the
optimizations were implemented. They document the progression of the compiler
changes rather than current OrderBook performance.

Progression with 3 million warmup and 50 million measured operations:

| Configuration | Time |
|---|---|
| original emitter, shipped pipeline | 1.55 s |
| structural changes (3.1 to 3.4), shipped pipeline | 1.33 s |
| structural changes, `-inline-threshold=1000` | 1.14 s |
| structural changes, `-enable-partial-inlining` | 1.23 s |
| structural changes, both options (the new `-O3`) | 1.10 s |

Final development comparison with 10 million warmup and 100 million measured
operations on macOS using an Apple M5:

| Build | Time |
|---|---|
| Java | 2.62 to 2.66 s |
| Ironwood before | 3.09 to 3.18 s |
| Ironwood after | 2.21 to 2.28 s |

Ironwood went from 18 percent slower to about 16 percent faster. The benchmark
executable grew from 735,888 to 768,040 bytes (4 percent). The profile after
the change is concentrated in application methods that create, match, execute,
rest, and remove orders. Small accessors and standard-library methods inline
into those paths.
Linux was not measured here; the changes are IR-level and option-level, so the
same mechanisms apply.

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

## 6. Rejected or neutral experiments

- Stripping the pseudo probes: no measurable effect, so on-demand traces are
  free at run time.
- `llc -mcpu=apple-m4`: no measurable effect.
- Late hot/cold splitting (`-hot-cold-split=true`): no effect alone, because
  it runs after inlining decisions; a marginal loss combined with the options
  that were adopted.
- Early hot/cold splitting (`hotcoldsplit` before `default<O3>`) with a `cold`
  `ironwood_throw`: 238 cold functions were split out, but the result was
  slower than the adopted options. Splitting before inlining prevents LLVM from
  deleting failure paths that become provably dead after inlining, and the
  explicit-throw sequences with landing pads were not extracted at all.
- Marking only the declarations (`cold` throw, `nonnull` allocators) without
  outlining: no effect on inlining; kept for code layout and null-check folding.
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

Build and time both programs:

```console
$ cd projects/OrderBook
$ ./compile.sh && ./link.sh && ./throughput.sh 10 100
$ ./java/compile.sh && ./java/throughput.sh 10 100
```

Run the Java project's `Bench` with the same two arguments for the comparison.
Warm the machine with a first run; the second run of each is the stable one.

Profile on macOS while the benchmark runs (Linux: `perf record -g`):

```console
$ ./target/orderbook-bench 2 400 & sleep 1; sample orderbook-bench 6 1 -file profile.txt
```

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
