# Ironwood performance advantages

Ironwood and Java can express nearly identical application logic, but they
reach native machine code through different optimization models. Java normally
relies on HotSpot profiling and JIT compilation. Ironwood compiles the complete,
closed-world program ahead of time through its typed IR and LLVM pipeline.

Neither model wins every workload. HotSpot C2 is especially effective on small,
stable, monomorphic code. Ironwood's closed-world optimizer can remove costs
from abstractions common in larger Java-shaped systems:
interfaces, reusable collections, pooled objects, safety checks, exception
paths, callbacks, and many small helper methods.

Whether those opportunities produce a larger relative advantage requires paired
measurements of each workload. Simplification changes both the work performed
and the optimization opportunities available to each compiler.

Linux is the official platform for Ironwood performance comparisons.
The official throughput and batch-latency results, workload, and environment
are maintained in [BENCHMARK.md](BENCHMARK.md). They compare the small OrderBook
engine on Linux with Oracle JDK 25 and GraalVM 25 running in JVM mode. They do
not establish a general speedup or a trend across application sizes. The
mechanisms below explain opportunities to investigate in other workloads.

## Native code is not the explanation by itself

Java's hot methods are compiled to native machine code by the JIT. A useful
performance comparison therefore cannot be reduced to native code versus
interpreted bytecode. After sufficient warmup, both programs execute optimized
machine code.

The relevant questions are instead:

- what each compiler knows about the complete program;
- which calls it can turn into direct calls or inline;
- whether uncommon failure paths inflate otherwise small methods;
- which object, receiver, and allocation facts reach the optimizer; and
- how much profile-dependent work remains in the steady-state path.

Ironwood answers these questions using a closed-world AOT model. The compiler
sees every reachable class and method before the final native link. HotSpot
learns much of the same information from runtime profiles, but its decisions
must remain compatible with JIT compilation budgets, code-cache limits, and
the possibility that observed types change.

## Why a small Java program can get very close

A small matching engine built from final classes, primitive fields,
fixed-capacity arrays, and short private methods is close to an ideal C2
workload. Its important calls are monomorphic, its loops are predictable, and
there are few exceptional paths.

On the Linux benchmark build, inspect which helpers C2 and LLVM inline into
the limit-order operation. When both compilers inline most helpers, there may
be little call overhead left to remove. Both implementations must still perform
the same matching work:

- following the best bid or ask and the FIFO order chain;
- comparing prices and sides;
- calculating the executable quantity;
- updating sizes and aggregate volume;
- unlinking completed orders and empty price levels; and
- returning reusable objects to fixed pool slots.

If simplification removes work that Ironwood optimizes more effectively, the
common-cost portion can grow and the relative difference can narrow. The effect
must be measured for the particular workload.

## What a feature-rich application adds

Production-shaped low-latency code commonly surrounds its core algorithm with
additional mechanisms:

- interface-based internal and external listeners;
- callback ordering and listener exception reporting;
- reentrancy checks;
- timestamps for lifecycle transitions;
- client identifiers and character copying;
- generic maps, lists, and object pools;
- validation and policy branches;
- time-in-force and other lifecycle state; and
- `try`, `finally`, rollback, and uncommon failure paths.

These features can remain allocation-free in steady state and still cost CPU
time. They add calls, branches, dispatch, code size, and control-flow edges.
They also make inlining harder because a method's cold behavior can dominate
the optimizer's estimate of its cost even when the normal path is short.

This is an important property of a realistic benchmark. An abstraction does
not have to allocate or perform I/O to affect latency. A tiny accessor, pool
operation, flag check, or callback can matter when it executes hundreds of
millions of times and remains out of line.

## Where Ironwood gains room

Ironwood's native pipeline is designed to remove this overhead while preserving
Java-shaped source.

### Closed-world dispatch

The final link knows the complete set of instantiable receiver types. When an
interface slot has a small target set, Ironwood emits descriptor guards followed
by direct calls, with ordinary table dispatch as a correctness fallback. LLVM
can then inline the direct targets into the caller.

A JIT can perform similar guarded inlining from type profiles. The distinction
is that Ironwood derives its target set from the complete linked program rather
than from types observed during one execution. The result does not depend on
profile warmup or change when a call site later observes a different reachable
type.

### Cold failure-path outlining

Java-shaped safety checks have small normal paths and comparatively large
failure paths. A bounds check may normally be one comparison, while its failure
path allocates and constructs an exception, unwinds the stack, and preserves a
source trace.

LLVM's ordinary inline cost model counts that cold machinery when deciding
whether to inline the surrounding method. Ironwood recognizes common failure
shapes and moves them into shared `cold noinline noreturn` helpers. The small
normal path then becomes inexpensive enough to inline. Exception semantics,
rollback, and source-level traces remain unchanged.

This matters for operations such as list access, pool acquisition and release,
builder capacity checks, receiver checks, and array access. Each operation is
small on the successful path but can otherwise appear too large to LLVM.

### Java-shaped inlining policy

Ironwood sends the entire reachable program to LLVM as one module. At `-O3`, it
uses a larger inline threshold and enables partial inlining because Java-shaped
programs contain many small accessors and early-return guards.

Partial inlining can place the common guard in the caller while leaving a large
exception-reporting or recovery body out of line. Raising the budget also
allows a useful small method to inline even when LLVM has already folded its
single-caller growth or recovery helper into it.

The result is a denser hot path without removing the source-level abstraction.

### Strong optimizer facts

The compiler communicates language guarantees that LLVM cannot infer from
ordinary calls alone:

- instance method receivers are `nonnull noundef`;
- object and array allocators return `noalias nonnull` storage or fail;
- the throw helper is `noreturn cold`; and
- selected primitive string operations lower directly against the known native
  layout.

These facts eliminate redundant null checks, improve alias analysis, keep throw
blocks cold, and prevent simple operations from crossing an opaque runtime
boundary.

## How workload structure can change the percentage

Amdahl's law provides a way to reason about a possible effect. Consider a
workload with two parts:

1. Core algorithmic work that both LLVM and C2 already optimize well.
2. Application structure where closed-world dispatch, cold outlining, and
   whole-program inlining may give Ironwood more room to improve the result.

If measurements show a larger Ironwood advantage in the second part, reducing
its share can narrow the overall advantage while leaving the core algorithm's
machine code unchanged. Listeners, maps, generic pools, strings, timestamps,
and failure machinery are candidates for this experiment; their presence alone
does not establish which compiler optimizes them better.

Final classes, fixed arrays, and short direct methods can let C2 flatten much
of an operation. Ironwood retains its closed-world information, but the amount
of work that benefits from it depends on the remaining application structure.

Both programs may become faster while their relative separation grows or
shrinks. Relative performance depends on the mix of work and the code each
compiler generates for it.

## Collecting compiler evidence on Linux

Collect C2 inlining diagnostics, LLVM optimization remarks, and native profiles
on the Linux benchmark host. Check which collection operations, pool helpers,
accessors, and listener callbacks remain as calls in each compiled program.
Inspect large lifecycle methods for inlining limits and cold exception paths.

Use those diagnostics to explain measured results from the same workload and
configuration. A larger source tree does not automatically favor Ironwood; the
relevant question is how much hot call and control-flow structure each optimizer
can remove. Keep profiling runs separate from the timing runs used for the
published comparison.

## Benchmark implications

A minimal paired benchmark is still valuable. It tests the irreducible
algorithm, makes equivalent source easy to review, and shows whether Ironwood
can compete when HotSpot receives an ideal optimization case. Its results apply
to the measured workload and environment. Larger applications require their
own comparisons.

A convincing comparison should keep the two implementations structurally
equivalent and should report enough context to reproduce the result:

- identical operations, constants, data structures, and validation;
- no steady-state allocation unless allocation is the subject of the test;
- sufficient Java warmup before measurement;
- alternating process order across trials;
- medians or distributions from multiple independent trials;
- compiler, JDK, operating-system, and hardware versions; and
- separate throughput and latency-distribution results when making low-latency
  claims.

Throughput alone does not establish tail-latency behavior. A financial or other
latency-sensitive evaluation should also measure per-operation percentiles,
warmup behavior, and run-to-run variance.

## Isolating each source of advantage

The cleanest follow-up experiment is a feature ladder. Start with the minimal
paired application and add the same mechanism to both languages at every step:

1. Replace specialized pool arrays with the generic pool API.
2. Add indexed lookup through the standard data structures.
3. Add reusable client-identifier copying.
4. Add timestamp dispatch.
5. Add internal lifecycle listeners.
6. Add external listeners and callback ordering.
7. Add reentrancy checks and deferred exception reporting.

Benchmark every stage with the same harness and inspect C2 inlining output,
Ironwood LLVM optimization remarks, native profiles, code size, and allocation
counts. The resulting progression shows whether the gap grows, shrinks, or
remains unchanged, and which mechanisms affect it.

The feature ladder tests whether adding Java-shaped abstractions gives
Ironwood's closed-world optimizer more opportunities to remove costs that a
profile-driven JIT may retain because of dispatch uncertainty, compilation
budgets, or large uncommon paths.

The implementation details behind these mechanisms are recorded in
[COMPILER.md](COMPILER.md), decision D134 in [DECISIONS.md](DECISIONS.md), and
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md).
