# Public Throwable stack-trace review

## Decision and behavioral target

D121 and D122 implement `Throwable.printStackTrace()`,
`printStackTrace(ironwood.io.PrintStream)` and `fillInStackTrace()`. The target is
the [Java 21 public Throwable contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Throwable.html),
verified with independently written Java/native differential probes. The
PrintStream destinations available today are `System.out` and `System.err`.

| Operation | Implemented behavior |
| --- | --- |
| Ordinary Throwable construction | Invoke virtual `fillInStackTrace` and snapshot source frames, even if the object is never thrown. |
| Throw or rethrow | Preserve the stored snapshot. |
| `fillInStackTrace()` | Replace the snapshot and return the same receiver; a no-op override can leave the object stackless. |
| `printStackTrace()` | Write to `System.err`, without terminating the process or adding the uncaught-report prefix. |
| PrintStream overload | Use the supplied supported stream; null throws NullPointerException. |
| `getStackTrace()` | Return a fresh caller-owned array of immutable source-frame values. |
| Descriptions and causes | Invoke virtual `toString` and `getCause`; compress shared suffixes and stop on repeated object identity. |
| Cleanup failures | Print Ironwood's occurrence-ordered secondary sequence with `Secondary:`, preserving the existing primary-exception policy. |
| Division/remainder by zero | The compiler-created ArithmeticException now carries `/ by zero`. |

Capture omits consecutive constructor and fill frames belonging to the captured
Throwable's class hierarchy at its top. An unrelated exception constructor that
creates or refreshes another exception remains visible. Other constructor frames, nested names, file basenames and call-expression lines
retain D054's source identity. Native inlining does not change those source
frames. The fatal uncaught reporter retains its prefix, raw stored description,
secondary reports and exit status 1; it is separate from virtual public printing.

Mutable stack-trace setters, Throwable PrintWriter destinations, serialization,
and Java suppression APIs remain absent. D122 adds mutable cause initialization
with Java's single-assignment and self-cause checks. These omissions are
compile-time API boundaries. `Secondary:` represents Ironwood's existing cleanup
semantics, not Java's suppressed-exception policy.

## Implementation and ownership

This is an independent facade with original compiler/runtime mechanisms under
`MIT OR Apache-2.0`. No OpenJDK implementation bodies, comments or tests were
copied or adapted. The public traversal is small, while the difficult capture
and reclamation work depends on Ironwood's existing source frames, typed IR and
native object lifetime. Importing Java's VM backtrace representation would not
solve those native requirements.

The source facade uses the existing `ironwood.ds.ArrayList` to track visited
objects. A private helper owns that list and its backing storage and is freed
in `finally`. Graph references are borrowed. Existing PrintStream rendering
reclaims descriptions only when the concrete callback returns a proven-fresh,
unescaped String; borrowed, published and uncertain results stay untouched.
User callback exceptions propagate, except for the allocation-failure fallback
described below. Conservative callback effects still govern subsequent `free`.

Throwable has one reserved private integer slot after message and cause. It
holds opaque native metadata, never a public pointer. The compiler verifies the
bundled field layout. Metadata and frame buffers belong to that Throwable;
refresh replaces the buffer, normal destruction releases all owned metadata,
and failed-constructor rollback releases it before raw deallocation. Secondary
association nodes own their storage but borrow the associated exceptions.
Message/cause ownership is unchanged. A source-thrown or published exception
still has the existing escape restrictions on `free`.

These operations have an explicit typed-IR representation. Capture is classified
as allocation for destructor-effect checking even though its private C storage
is outside the managed allocation counters. Borrowing refinements preserve
publication by fill, description, cause and destination-overload callbacks.
Older bundled Throwable artifacts must be rebuilt for the new layout.

D122's public snapshot allocates only its reference array. The compiler emits
immutable process-lifetime StackTraceElement objects and immutable field Strings
for every callable/line pair that on-demand pseudo-probe decoding may record. The
runtime maps captured sites to those objects. Callers reclaim the array, never
its elements, and no runtime object registry or element allocation is needed.

## Performance boundary and failure behavior

The performance boundary is zero executable stack-trace bookkeeping
instructions on ordinary paths. LLVM pseudo probes survive optimization and
inlining as read-only binary metadata. A Throwable capture alone starts native
unwinding, source-site decoding, constructor/fill filtering and private trace
allocation. The compiler prevents tail-call elimination in retained Ironwood
source functions because removed dynamic frames cannot be recovered from
immutable metadata. Other call optimization, object layouts and destruction
paths are unchanged; only Throwable receives the private slot and trace cleanup.

Construction pays for a metadata node and a frame buffer, even if the exception
is never thrown. Refresh and printing also cost work. Captured metadata remains
live with its Throwable until permitted reclamation or process exit. This is
consistent with Ironwood's explicit lifetime model, not automatic collection.

Capture failure preserves exception delivery and marks the trace unavailable.
The preallocated OutOfMemoryError retains bounded static trace storage (64
frames) and reusable occurrence identity. Its immortal native object is writable
so its private trace slot can change. Printing allocation failure releases
completed traversal helpers and falls back to the root's concrete type, stored
message and captured frames without allocating language objects. It may follow
partial output and omits virtual descriptions, causes and secondary traversal
in that emergency fallback. This preserves a useful original diagnostic without
recursively attempting the failed allocation. Reusing the emergency singleton
can still replace metadata visible through old aliases, as specified by D081.

## Verification and lesson

Focused tests cover Java 21 output comparison for never-thrown and delayed-thrown
objects, rethrows, refresh, stackless/delegating overrides, descriptions, causes,
cycles, unrelated exception-constructor frames, null destinations, empty/null
messages and arithmetic failures. Native
checks cover public secondary labels, visited-list growth, callback failure,
repeated printing, proven-fresh renderings, safe reclamation and constructor
rollback. Negative tests retain alias/publication restrictions, reject capture
from destructors and reject the omitted mutable frame setter. Typed IR and
existing source/class/archive tests cover capture, rollback release and source
identity. Focused D122 tests also cover caller-owned arrays, immutable frame
accessors, rendering, allocation failure and exact source identity.

A C harness counts private native allocations and injects metadata failures.
Repeated replacement and release return to the expected live count;
secondary cleanup does not free its borrowed exception. Emergency snapshots
remain bounded and allocation-free. Managed printing failure injection covers
budgets from one through twelve allocations and preserves the root diagnostic
without leaking completed helpers.

A representative million-iteration arithmetic loop and helper were compiled
before the change at `f4f966d` and after it. Their emitted function bodies were
identical. After LLVM 23 `default<O3>` optimization, the complete native `main`
IR, including the inlined helper, was identical. This is direct evidence for
that program plus an audit of unchanged frame instrumentation, not a general
throughput benchmark or cross-platform performance claim. Verification uses
focused local macOS ARM64 tests, not the full suite or release platform checks.

D054 deliberately shipped a crash-report facility. The mistake would have been
to equate that facility with Java's public Throwable contract: exposing its old
first-throw snapshot would give incorrect results for preconstructed and
never-thrown exceptions. This implementation reviews capture timing, virtual
callbacks, graph semantics, failure and ownership together. Neither wholesale
OpenJDK copying nor independent implementation alone guarantees compatibility.
The existing AGENTS.md and mandatory porting review already require these checks;
no additional agent-policy rule is needed.

## Compiler regression follow-up

The [subsequent investigation](TEST_PERFORMANCE.md#throwable-compiler-regression-2026-09-06)
found an invalid-hierarchy compiler hang first introduced by T3, with another
entry path added by D121, and a separate valid-program compilation slowdown.
The printer's collection dependency enlarged the class-granular source closure
analyzed even for a tiny program. Profiling exposed repeated resolution of
unchanged dispatch and effect targets; per-analysis caches now avoid that work
without caching evolving safety summaries or changing generated runtime code.

The native hot-loop comparison above did not measure compiler throughput or
malformed-source behavior. Focused shared-phase rejection checks and compiler
timings were missing from that verification. Preserving exception-free native
execution does not establish unchanged build performance.
