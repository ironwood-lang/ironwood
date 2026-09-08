# Ironwood performance advantages

Ironwood and Java can express nearly identical application logic, but they
reach native machine code through different optimization models. Java normally
relies on HotSpot profiling and JIT compilation. Ironwood compiles the complete,
closed-world program ahead of time through its typed IR and LLVM pipeline.

Neither model wins every workload. HotSpot C2 is especially effective on small,
stable, monomorphic code. Ironwood's relative advantage tends to grow when an
application contains the abstractions common in larger Java-shaped systems:
interfaces, reusable collections, pooled objects, safety checks, exception
paths, callbacks, and many small helper methods.

This distinction explains why a deliberately small matching engine can show a
narrower Ironwood advantage than a feature-rich matching engine even when both
execute the same basic order flow. Simplification removes work, but it can also
remove exactly the work where closed-world compilation has the greatest
advantage.

In one local paired comparison, the deliberately small engine ran about 10
percent faster in Ironwood, while the feature-rich engine ran about 15 percent
faster. Those figures describe the tested workloads and test system; they are
not a general speedup guarantee. The useful result is the direction of the
change and the compiler behavior that explains it.

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

In the paired minimal benchmark used during Ironwood development, HotSpot C2
was able to inline nearly the complete limit-order operation. Its compiled path
included order acquisition, initialization, matching, execution, price-level
updates, removal, reset, pool release, price-level lookup, and resting the
unfilled order. The result was effectively one large optimized operation made
mostly of primitive loads, stores, comparisons, and branches.

LLVM also optimizes that code well, but there is little abstraction overhead
left for Ironwood to remove. Both compilers spend most of their time performing
the same unavoidable matching work:

- following the best bid or ask and the FIFO order chain;
- comparing prices and sides;
- calculating the executable quantity;
- updating sizes and aggregate volume;
- unlinking completed orders and empty price levels; and
- returning reusable objects to fixed pool slots.

The simpler the program becomes, the larger this common-cost portion becomes.
That naturally narrows the relative difference between the generated programs.

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

## Why removing features narrows the percentage

The effect follows directly from Amdahl's law. Divide a workload into two
parts:

1. Core algorithmic work that both LLVM and C2 already optimize well.
2. Application structure where closed-world dispatch, cold outlining, and
   whole-program inlining give Ironwood more room to improve the result.

Removing listeners, maps, generic pools, strings, timestamps, and failure
machinery reduces the second part. The remaining runtime is dominated by the
first part, so the observed percentage advantage becomes smaller even if
Ironwood's machine code for the core algorithm is unchanged.

The Java compiler has effectively been given an easier program. Final classes,
fixed arrays, and short direct methods let C2 flatten most of the operation. The
Ironwood compiler still has its closed-world information, but much less work
remains that can benefit from it.

This also explains why the absolute speed of both programs may improve while
their relative separation shrinks. Relative performance depends on the mix of
work, not only on the quality of either compiler in isolation.

## Evidence from compiler diagnostics

The difference can be observed directly in compiler output. In the minimal
matching benchmark, C2's inlining diagnostics showed that nearly all helpers
inside the limit-order path were folded into its compiled operation.

In a feature-rich version of the same workload, important lifecycle methods
were hundreds of Java bytecodes long. C2 reported representative execution and
cancellation methods as `hot method too big`, leaving boundaries around code
that also contained callbacks, timestamps, exception handling, and lifecycle
state. It still inlined many small helpers, but it could not flatten the call
graph as completely.

Ironwood profiling showed the complementary result after its native
optimizations: samples were concentrated in the application's substantive
large methods, while small collection operations, pool helpers, accessors, and
listener callbacks had disappeared into their callers.

These diagnostics are more useful than source size alone. A larger source tree
does not automatically favor Ironwood. The relevant difference is how much hot
call and control-flow structure each optimizer can remove.

## Benchmark implications

A minimal paired benchmark is still valuable. It tests the irreducible
algorithm, makes equivalent source easy to review, and shows whether Ironwood
can compete when HotSpot receives an ideal optimization case. It should not be
treated as the maximum expected difference for a larger application.

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
counts. The resulting progression shows whether the gap grows gradually or is
dominated by one mechanism.

The feature ladder tests a narrow expectation: complexity expressed through
Java-shaped abstractions creates more opportunities for Ironwood's closed-world
optimizer to remove costs that a profile-driven JIT may retain because of
dispatch uncertainty, compilation budgets, or large uncommon paths.

The implementation details behind these mechanisms are recorded in
[COMPILER.md](COMPILER.md), decision D134 in [DECISIONS.md](DECISIONS.md), and
[IMPORTANT_OPTIMIZATIONS.md](IMPORTANT_OPTIMIZATIONS.md).
