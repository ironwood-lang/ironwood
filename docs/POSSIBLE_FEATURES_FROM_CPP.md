<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Possible features inspired by C++

Status: exploration, September 22, 2026. This document records recommendations
for future investigation, not accepted language changes or implementation work.
Existing plans are identified separately from new suggestions. Names other than
the already proposed `@Inline` are descriptions, not proposed syntax.

## Strategic recommendation

Use C++ as an optimization reference and a source of representation ideas.
Keep Java and GraalVM Native Image as primary comparisons for Java-shaped
applications. Neither choice requires abandoning Ironwood's systems-language
ambition or promising to outperform every expert C++ implementation.

The equivalent-source OrderBook experiment remains useful: it asks how the
toolchains optimize closely matched algorithms, object graphs, field order,
integer semantics, and workloads. It does not establish the fastest possible
C++ order book. Matching declarations also does not guarantee identical native
layout or machine code; those are compiler outcomes to inspect.

An expert C++ implementation may use a different representation and run faster.
That is a legitimate comparison of implementation capabilities, but a different
experiment. We should welcome such implementations, inspect what makes them
fast, and decide whether the underlying idea fits Ironwood. Their existence
does not invalidate a clearly described equivalent-source experiment.

Publish configurations and limitations, including cases where C++ wins. In the
September 22 threshold sweep, the best tested C++ configuration achieved about
126.44 million operations/second versus 117.45 million for the best tested
Ironwood configuration: roughly 7.6% higher throughput. These are exploratory
throughput medians from 16 runs per configuration, not latency results or a
claim about either language's maximum performance. The evidence archive is
`orderbook-20260922-192438-xowlyzeh.tar.gz`; the configurations were C++ threshold
2000 without the explicit partial-inlining option and Ironwood threshold 4000.

The separately recorded [Java and Native Image results](BENCHMARK.md) establish
advantages for the documented workload and configurations. They do not establish
a general advantage across applications, and their ratios should not be combined
with this later experiment as though all measurements came from one run.

Source hints are legitimate tools in general C++ development. One-sided manual
hints violate this experiment's agreed common-source constraint. Compiler and
linker tuning remains allowed, disclosed, and available to both implementations.
Future layout or directive experiments should retain the current baseline and
identify their changed source contracts explicitly.

## What the discrepancies teach us

The earlier C++ translation exposed several independent choices. The changes
that restored equivalence should remain in the shared benchmark; the original
differences are useful design prompts, not defects to reintroduce.

| Earlier difference | Underlying opportunity | Recommendation |
|---|---|---|
| Individually allocated objects versus contiguous pooled objects | Control over object placement and ownership | Investigate flat storage and owned regions; substantial design work. |
| Referenced arrays versus embedded arrays | Remove indirection and keep related data together | Investigate automatic embedding first, then explicit fixed-size storage if needed. |
| Reference enums versus compact values | Remove enum loads and reduce representation cost | Extend proven enum optimization; consider a distinct value form later. |
| Heap object versus local C++ object | Avoid allocation and expose object state to optimization | Prioritize planned escape analysis and scalar replacement. |
| Explicit inlining attributes | Express intent where compiler heuristics miss an opportunity | Keep planned `@Inline` narrow; evaluate other controls separately. |
| Wrapping versus undefined signed overflow | Different optimization assumptions | Preserve defined arithmetic; improve range proofs. |
| Signed `int` versus `size_t` index | Different width and conversion rules | No new feature justified by this benchmark. |

Allocation outside the timed interval does not neutralize the resulting memory
layout: every measured access still uses it. However, storage location, object
layout, and allocation frequency are separate issues. A contiguous heap pool can
have good locality; putting the book itself on the stack does not move all of
its referenced orders and arrays there. None of these changes guarantees a win.

## Contiguous object storage and owned regions

An ordinary reference array keeps its references together, not necessarily the
objects they refer to. Flat object storage could remove an extra load and improve
cache locality, particularly for larger working sets. This deserves more design
attention than accumulating many optimization annotations.

There are two different candidate designs:

- **Value objects and flat arrays:** store values directly in an array or owner.
  A first design could restrict values to non-null, identity-free data. Define
  assignment and copying, mutation, construction, equality, generic use, and
  conversion to reference-oriented APIs before choosing syntax.
- **Owned regions or contiguous pools:** retain stable object identity while one
  owner controls a group of objects. This may better suit mutable order books
  with links between orders and price levels. Define construction failure,
  element access, address stability, and whole-region reclamation. A region must
  not be freed while an accessible reference into it survives.

These are not interchangeable features. Copying an identity-free value is very
different from copying a reference to a mutable pooled order. A compact storage
API that forces copies or introduces handles and lookups could lose the intended
benefit. Individually freeing an element of region storage cannot retain ordinary
independent-allocation semantics without a separate, explicit contract.

Automatic allocation coalescing is another possibility when the compiler can
prove it preserves behavior. It must account for object identity, observable
construction and initialization order, allocation failure, and independent
`free` operations. Merely noticing adjacent `new` expressions is insufficient.

Recommendation: explore one narrow ownership model before exposing a general
arena facility. Preserve Java-shaped access and mandatory static lifetime proofs;
do not introduce raw pointers, unchecked interior references, or per-access
runtime tracking. Measure both the current small pool and larger pools before
claiming a locality benefit.

## Embedded arrays and owned fields

Keeping small fixed-size arrays inside an owner could eliminate array-pointer
loads and separate allocations. The two-sided `head`, `tail`, and `levelCount`
storage is a concrete example, although embedding reference arrays still leaves
their referenced price levels elsewhere.

First investigate compiler-proven embedding when an array has a known size and
cannot be observed or reclaimed independently. A `final` array field alone is
not that proof: its elements can still change and aliases can still escape.
Existing array identity, nullability, aliasing, bounds failures, and shallow
array reclamation must remain correct.

If automatic transformation is too restricted, consider an explicit fixed-size
owned-storage form. Specify whether it can be passed as an ordinary array, what
such a view owns, and how its lifetime relates to the enclosing object. Adding a
hidden copy or runtime ownership registry would defeat important goals.

Recommendation: promising, but design it alongside flat storage and ownership,
not as an isolated annotation promising that arbitrary reference fields can be
embedded. Preserve bounds safety; known lengths can help prove checks redundant.

## Automatic stack allocation and scalar replacement

Ordinary non-static local C++ objects have automatic storage duration, commonly
implemented using the stack, although optimization can remove their storage
altogether. [C++ storage-duration rules](https://eel.is/c++draft/basic.stc.auto).

Ironwood already identifies escape-driven stack allocation and scalar replacement
as future work in the [memory model](MEMORY.md) and
[language specification](LANGUAGE_SPECS.md#memory-analysis-and-optimization).
Scalar replacement means replacing an object's storage with its individual
values, potentially keeping them in registers and removing the allocation.

Prioritize this automatic route. Developers should be able to write ordinary
objects and receive better code when the compiler proves their lifetimes and
uses. Preserve identity, initialization, exception behavior, and observable
`free` semantics. Analyze aliases and calls, including foreign calls, rather
than assuming a local variable implies a local object lifetime.

An explicit scoped-storage feature could eventually express intent and reject
escaping references at compilation. It needs a demonstrated gap that automatic
analysis cannot address. Do not silently change ordinary `new` into a different
source lifetime contract or add runtime escape tracking.

Recommendation: high priority for automatic optimization; defer explicit stack
syntax. For this benchmark, moving only the book's outer object is not evidence
of a large steady-state speedup. Large pools may belong on the heap even when
their owners have short lifetimes.

## Compact enums without changing existing enum behavior

C++ enums have an integral underlying type; scoped enums default to `int` unless
another type is specified. Their representation follows that underlying type,
so a one-byte scoped enum needs a suitable underlying type; it is not a
universal C++ property. [C++ enumeration rules](https://eel.is/c++draft/dcl.enum).

Ironwood's existing enums are objects with identity, initialization, fields,
methods, and nullable references. Replacing every enum reference with a byte
would need to preserve all those behaviors, including null reset and access to
`Side.index`. Shared mutable enum state cannot become per-value state.

The compiler already has accepted enum-argument specialization and initialized
enum payload propagation in
[D174](DECISIONS.md#d174---retain-bounded-constant-enum-argument-specialization) and
[D177](DECISIONS.md#d177---retain-initialized-enum-payload-propagation).
Extend that direction before adding syntax: represent a proven enum use as a
constant or tag internally when its observable object behavior can be preserved.
General tag lowering would also need a null representation and correct bridges
back to object-oriented uses, without moving initialization or exception timing.

A separate restricted value-enum form could be useful where identity and
mutable payloads are deliberately absent. It should be a distinct opt-in
contract, not a silent change to existing enums. Smaller fields can improve
density but do not automatically produce faster operations.

Recommendation: prioritize proof-based optimization of current enums. Consider
value enums with the broader value-storage design.

## Inlining controls and profile information

Inlining can expose constants and remove abstraction boundaries, but it can
also increase register pressure, spills, or instruction-fetch costs. The Linux
sweep demonstrates that a higher threshold or another inlining pass is not
automatically faster. Executable size alone is not a reason to reject an
optimization; measured runtime effects are relevant.

Ironwood already makes compiler-owned inlining decisions, including selected
LLVM `alwaysinline` attributes. It exposes `--inline-threshold` and
`--selective-inlining=on|off` under
[D175](DECISIONS.md#d175---retain-selective-inlining-with-link-controls).
The missing capability is source-level intent, not inlining itself.

The [roadmap](ROADMAP.md) proposes `@Inline` as a narrow compiler directive,
not a general annotation system or a guarantee. That remains a sensible scope.

| C++ mechanism | Meaning | Opinion for Ironwood |
|---|---|---|
| Standard `inline` | Does not force substitution; also governs permitted definitions across translation units. | Do not copy its multiple-definition rules into Ironwood. |
| `always_inline` / `__forceinline` | Stronger request to attempt substitution, still subject to feasibility. | Main inspiration for planned `@Inline`. |
| `noinline` | Suppresses inlining of the affected calls. | Consider a narrow control for measured regressions and diagnostics. |
| `flatten` | Requests inlining of calls inside a function where possible. | Defer: broad scope makes effects harder to predict. |
| Call-site attributes | Control specific calls instead of all uses of a method. | Potentially useful later; do not begin with both method and call-site syntax. |
| `hot` / `cold` | Supply execution-frequency information. | Prefer measured profiles and compiler knowledge first. |

These mechanisms are not all standard C++ keywords. See the
[standard `inline` rules](https://eel.is/c++draft/dcl.inline) and Clang's
[strong inline](https://clang.llvm.org/docs/AttributeReference.html#always-inline-force-inline),
[noinline](https://clang.llvm.org/docs/AttributeReference.html#noinline),
[flatten](https://clang.llvm.org/docs/AttributeReference.html#flatten), and
[hot](https://clang.llvm.org/docs/AttributeReference.html#hot) and
[cold](https://clang.llvm.org/docs/AttributeReference.html#cold) documentation.

Branch-likelihood hints are related but do not request inlining. They should
also wait for evidence that automatic or profile-guided decisions are
insufficient. An optimization hint must never make a valid path incorrect.

For `@Inline`, carry intent through `.ironclass`, `.ironjar`, and final linking;
report why a request was not applied. Provide a way to compare generated code
with source hints disabled. Define conflicts with other controls explicitly.
No directive may bypass ownership analysis, alter argument evaluation, or discard
required exception behavior. A future no-inline directive should not promise
that all other optimization of a call is disabled.

The roadmap currently mentions code growth as a possible reason to decline
`@Inline`. Before implementation, reconcile that wording with D175 and the
project's performance-first policy: binary size by itself must not be a veto.
Feasibility and detrimental runtime effects are different concerns.

Profile-guided optimization is already a roadmap review item. Representative
profiles can guide inlining and hot/cold layout without decorating application
methods. Instrumentation belongs in explicit profile-collection builds, not
ordinary execution; stale profiles must never become correctness assumptions.
The link-only `--optimization-report <file.yaml>` now exposes LLVM's existing
`opt` remarks without changing optimization policy. It helps explain inlining
and other reported decisions; it does not explain every remaining call, check,
or allocation. Richer reporting remains possible future work.

Recommendation: pursue reporting and PGO alongside automatic inlining work;
retain `@Inline` as the first candidate source control. Evaluate no-inline
control when a concrete use warrants it. Defer broader directive families.

## Arithmetic and index types

Do not adopt undefined signed overflow as a performance feature. Ironwood's
wrapping arithmetic is useful and predictable. Preserve the C++ benchmark's
`-fwrapv` contract rather than silently granting one compiler stronger
assumptions. The useful compiler work is proving where overflow cannot happen
and optimizing those operations accordingly.

Likewise, replacing the latency index with `size_t` is not inherently faster.
It changes width and signedness on relevant targets, and can introduce different
conversions. Retain the shared signed 32-bit source contract. The compiler can
choose efficient induction and address calculations when it proves equivalence.
Unsigned or native-width source types would need a separate API or interop
justification, not this translation discrepancy.

Recommendation: no language change for either issue on this evidence. Improve
range analysis and bounds-check elimination without making programmers assert
unchecked facts. Similar caution applies to aliasing and non-null promises:
infer and validate them rather than exposing a shortcut around safety proofs.

## Recommended investigation order

1. **Complete the two agreed compiler investigations:** measure the now available
   link-only `--partial-inlining=on|off` control, and remove more null/bounds checks
   where compiler facts prove them unnecessary. The new control preserves existing
   defaults; its Linux performance evaluation remains pending. Partial inlining
   is distinct from the existing selective-inlining policy. The C++ slowdown motivates an
   experiment, not an assumption that disabling it will help Ironwood.
2. **Improve visibility and automatic decisions:** optimization reports, escape
   analysis, scalar replacement, range proofs, and the planned PGO workflow.
   These can improve ordinary applications without requiring specialized source.
3. **Design representation capabilities:** contiguous storage, owned regions,
   and embedded fixed-size data. Separate identity-bearing objects from values
   and specify reclamation before choosing syntax.
4. **Add narrowly justified intent controls:** begin with the planned `@Inline`
   review; consider other directives only for demonstrated gaps. Revisit compact
   value enums as part of representation design.

For each candidate, define the observable contract and paired accepted/rejected
lifetime cases before implementation. Preserve the compiler-owned typed-IR
pipeline and the no-bookkeeping guarantees of
[D132](DECISIONS.md#d132---stack-traces-use-on-demand-native-decoding-without-runtime-bookkeeping)
and [D133](DECISIONS.md#d133---type-initialization-uses-an-inline-fast-barrier-and-outlined-slow-path).
Unproven reclamation remains a compilation error.

Use Linux machine code and repeatable throughput and latency measurements to
judge performance. Include the current workload, larger working sets where
layout matters, and a relevant independent consumer. Keep source-preserving
compiler experiments separate from redesigned representations. Give comparator
toolchains comparable tuning attention and publish correctness checks, flags,
source identities, warmup, CPU conditions, and run distributions. Mac builds
remain portability checks; Linux supplies the performance evidence for this work.
