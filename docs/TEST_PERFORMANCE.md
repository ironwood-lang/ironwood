# Test performance audit

This document retains compiler and test-harness findings and correctness
verification. Published timing comparisons require Linux measurements with a
recorded workload, environment, and protocol. Official application throughput
and batch-latency results are maintained in [BENCHMARK.md](BENCHMARK.md).

## Audit of the 449-test pass (2026-09-06, 20:01)

The supplied run passed all 449 tests. The audit reviewed repeated compilation,
independent failure scenarios, and the coverage each native build preserves.

### Avoidable build repetition

The testing-module entry is valuable coverage. It runs the real optional testing
archive, generated `@Test` entry points, assertions, lifecycle hooks, skips,
continued execution after failures, and native exit statuses. It also runs
65 migrated pool/data-structure cases and 34 destruction cases. The smaller
compiler synthesis tests use stub testing types and do not replace this coverage.
There is thematic overlap with other collection fixtures, but deleting this
entry would remove the native framework self-test and its migrated suites.

The old script ran six compile/link pairs in twelve fresh Java processes. The
script now builds one repository-only selector with all six
suites, then invokes each generated suite main in a separate native process.
All expected output, 102 passes, one skip, and the separate intentional-failure
fixture remain checked. Compilation still uses the optional archive explicitly;
linking still consumes compiled class artifacts. There is no persisted result
cache or skipped suite.

The String allocation-failure test similarly built ten nearly identical
executables. One switch-selected program now contains the same ten operations.
Each operation and budget still runs in a fresh process, measures its own setup
baseline, and requires exactly one caught failure for each allocation in that
operation. Source state and live-allocation checks are unchanged.

The static-initialization round-trip test also compiled an unused sibling into
an output directory that was never read again. That compilation was removed;
the sibling is still built in the actual library directory and included in the
archive used by the pruning assertion. Its four input-form checks remain.

### Reviewed test coverage

These entries were inspected for build repetition and the behavior each build
covers. A single displayed test can contain many independent compiler programs
or native failure scenarios.

| Test | Assessment |
| --- | --- |
| standard-library testing module reports deterministic native results | Improved: six independent compile/link pairs became one shared build; all six suite processes and output checks remain. |
| everyday String allocation failures reclaim temporary storage | Improved: ten compile/link pairs became one selectable program; all ten operations and every allocation-failure budget remain. |
| compile and link modes keep ironclass and native output separate | Keep: actual CLI subprocesses, current-directory discovery, output modes, entry selection, and rejection diagnostics. |
| static initialization survives source-path class-path and archive round trips | Removed one unused compilation. Keep four source/directory/individual-class/archive paths and initialization/pruning assertions. |
| nested types inner construction and ordered initializer blocks follow Java rules | Keep: 29 separate positive/negative compilations for access, owner typing, construction, initializer order, and reclamation. Combining invalid hierarchies can suppress later diagnostics. |
| enums survive source class individual-file archive and tree-shaking round trips | Keep: four input forms plus constant-specific dispatch, singleton identity, and removal of unused enum metadata. |
| stack traces survive source class file and archive round trips | Keep: four input forms verify exact source files, lines, callable identities, and inherited exception behavior. |
| multidimensional arrays survive source class archive and tree-shaking round trips | Keep: four input forms verify nested-array types, casts, and descriptor pruning. |
| classic switch survives source-path class-path and archive round trips | Keep: four input forms reconstruct switch control flow and compile-time constants. |
| Throwable printing preserves lifetime and callback effects | Keep: three native lifetime/printing fixtures and six callback, destructor, and alias rejection checks. |
| data structures destroy internal pools and preserve caller items | Keep: one broad native collection fixture verifies destruction, retained caller objects, and allocation-free reuse across container states. |
| U2 file and path allocation failures roll back at O3 | Keep: three allocation-boundary fixtures for paths and file reads, with separate native failure budgets. Small future batching candidate. |
| minitee bounds allocations and preserves cleanup failures | Keep: stream/lifecycle/CLI builds, nine stream modes, and injected input/output/allocation failures. |
| caller-owned library results survive source class archive and tree-shaking round trips | Keep: source, loose-class, and archive reconstruction of fresh/borrowed ownership results and removal of unused declarations. |
| Ironwood classes resolve through Java classpath aliases | Keep: all three classpath spellings, adjacent and package output, individual-file and directory inputs, compile and link modes. |
| multiple explicit source files compile and run together | Keep: real CLI subprocesses verify multiple explicit sources and default output/current-directory linking. |
| implicit runtime failures survive source class and archive round trips | Keep: three input forms reconstruct catchable native safety checks and exception metadata. |
| remaining statements survive source class and archive round trips | Keep: three input forms reconstruct loop transfers through finally cleanup. |
| U3 streaming and failure cleanup run at O3 | Keep: two fixtures verify streaming and cleanup under a bounded file-descriptor limit. |
| static imports survive source class archive and tree-shaking round trips | Keep: three input forms verify imported declarations, initialization, and unused member/class pruning. |
| first-failure finally semantics survive source-path class-path and archive round trips | Keep: three input forms preserve primary/secondary exception behavior and exceptional cleanup. |
| data structure releases allow caller item reclamation | Keep: one broad native fixture verifies detachment and subsequent caller reclamation across collection families. |
| array initializers survive source class and archive round trips | Keep: three input forms preserve initializer typing, evaluation, and ownership. |
| multi-catch and precise rethrow survive source class and archive round trips | Keep: three input forms preserve union handlers, precise rethrow, and runtime metadata pruning. |
| instanceof type patterns survive source class and archive round trips | Keep: three input forms preserve pattern bindings and flow-sensitive control flow. |
| allocation failure survives source class and archive round trips | Keep: three input forms retain catchable allocation-failure metadata and behavior. |
| local and anonymous types survive owner-payload distribution round trips | Keep: source, class, and archive forms preserve local/anonymous owner payloads and isolated dependency discovery. |
| enum identity ordering lookup switching and initialization run at O3 | Keep: three distinct native fixtures for ordinary enums, constant bodies, and initialization failure. |
| static initialization ordering cycles and failures run at O3 | Keep: three fixtures for ordering/cycles, caught failure, and uncaught diagnostics. |
| U1 allocating text services preserve allocation-failure rollback | Keep: three different text/environment allocation boundaries and budgets. Small future batching candidate. |
| everyday String operations match Java | Keep: two native/Java differential fixtures, including fresh Java oracle compilation and process startup. |
| borrow dispatch uses exact overloads defaults and receiver flow | Keep: multiple receiver-flow, overload, generic, and default-method programs whose closed-world ownership proofs differ. |
| everyday StringBuilder ownership and allocations are preserved | Keep: native ownership/publication fixtures plus callback and alias rejection cases. |

The artifact round trips check more than CLI spelling: source discovery, class
reconstruction, exact generic/ownership/source metadata, and archive pruning can
fail independently. Keep those input paths. Combining related failure fixtures
is a reasonable smaller follow-up, provided initialization and injected budgets
remain independent. Splitting long tests into more names would not save work.
Further cost attribution requires focused Linux profiling and timing under a
recorded build, cache, and process-startup context.

### Verification and prevention

Only the three changed test entries were run for functional verification; all
passed. The combined selector rejects missing, unknown, and extra arguments.
Shell syntax,
license, and diff checks passed. No compiler, runtime, library API, or test
registration was changed. The suite still has 449 registered tests, with every
suite/case assertion retained.

The mistake was repeating compilation around related execution scenarios.
Test cost needs the same attention as test assertions: share builds when the
closed-world context can be shared, preserve fresh failure processes, and retain
separate builds when the input/distribution path is itself under test. See
[local testing guidance](LOCAL_TESTING.md) and the
[repository suite description](TESTING.md#run-the-repository-suites).

## Throwable compiler regression (2026-09-06)

Two separate regressions were confirmed: an infinite loop when rejecting cyclic
inheritance, and additional compiler work on valid programs. Neither observation
is a measurement of exception-free native application throughput.

### Inheritance-cycle hang

`class and interface inheritance cycles are rejected` did not merely run slowly.
A live Java thread dump showed `ThrowableSemantics.isThrowable` following the
same superclass chain indefinitely. At `7615e93`, the caller was
`EscapeSummaryAnalyzer.throwableCaptureOverridesBorrowReceiver`.

The compiler had already diagnosed the invalid hierarchy, but continued into
ownership analysis. The helper assumed an acyclic graph. Comparing historical
compiler and matching bundled-library sources with
`class Main extends Main { public static int main(String[] args) { return 0; } }`
located the first bad change:

| Revision | Result |
| --- | --- |
| `4ded1c0`, immediately before T3 | Diagnostic and exit 1 |
| `37caa2b`, T3 Throwable rendering | Did not finish within 5 s; terminated |
| `f4f966d`, immediately before public stack traces | Did not finish within 5 s; terminated |
| `7615e93`, public stack traces | Live stack confirmed the unbounded walk |

T3 introduced the unsafe assumption; the later stack-trace work added another
entry into it. Cycle validation now returns the accumulated diagnostics before
ownership and dispatch analysis. It reuses the existing validation pass rather
than adding checks to every valid-program superclass traversal.

The regression test now runs a child compiler with a 15-second timeout, so a
future failure cannot hang the remaining tests. It checks self, indirect,
interface, and generic cycles, a descendant of a cycle, source locations, and
absence of emitted class artifacts. The repaired test passed.

### Valid-program compilation cost

The public printer uses `ironwood.ds.ArrayList` for visited objects. The current
compiler loads source dependencies at class granularity before pruning unused
members. Consequently, even a program that only returns 42 grew from 36 analyzed
classes and 339 functions at `f4f966d` to 43 classes and 413 functions at
`7615e93`. Its native code need not use those additional members.

Fixed-point analyses repeatedly rebuilt unchanged dispatch selections and
effect-target relationships while their summaries evolved. Two bounded-lifetime
caches now retain:

- Declared-receiver dispatch selections after parent/member binding and slot
  installation, for one hierarchy analysis. Inferred/captured exact views still
  resolve independently.
- Call, initialization, and destructor target sets for one closed-world effect
  analysis. Allocation, throw, publication, and returned-origin summaries are
  still recomputed to convergence and read afresh.

No safety analysis or library validation was removed. There is no cross-build
cache and no change to native runtime code. The effect regression also checks
an indirect allocating interface implementation declared after its caller,
where a later class initializer's allocation must propagate back to the
destructor across iterations.

Focused compiler checks covered arithmetic rejection, data-structure entry
loans, default resolution, destructor/constructor effects, primitive generics,
and local/anonymous classes. All assertions passed. Quantifying the compilation
cost of these changes requires a separate Linux measurement.

### Standard-library testing-module case

`standard-library testing module reports deterministic native results` invokes
`scripts/test-stdlib.sh --skip-build`. It builds six native executables through
12 separate Java compiler processes, then verifies 103 normal cases across five
suites plus an intentional-failure fixture. Its parent buffers script output,
so the intermediate builds are invisible while that test is running.

The displayed test duration includes compilation, linking, and native process
execution. Measure those phases separately on Linux before attributing a change
to compiler analysis or generated application code.

### Verification and lesson

Fourteen focused hierarchy, generic dispatch, borrowing, effect, destructor,
static-initialization, and Throwable checks passed with the fixes, as did the
testing-module case. The expanded late-effect check also passed separately.
No full suite was run. Relinking the existing arithmetic hot-loop fixture
produced a complete LLVM module byte-for-byte identical to the pre-fix output.
That verifies this fix leaves that program's emitted code unchanged; it does
not establish runtime performance for every application.

The verification mistake was treating feature-output tests and unchanged native
hot-loop code as sufficient coverage for changes to shared compiler analysis.
Malformed hierarchy rejection and compiler throughput are separate obligations.
The remedy is an enforced phase invariant, a bounded hang regression, and
focused shared-phase checks and measurements. Existing repository rules already
require useful malformed-source diagnostics and prohibit unsupported performance
claims; no additional AGENTS.md rule is necessary.

After a normal build, the focused effect check can also be profiled directly:

```sh
java -XX:StartFlightRecording=filename=workspace/effects.jfr,settings=profile \
  -ea -cp compiler/build/classes:compiler/build/test-classes \
  ironwood.compiler.CompilerTests \
  --test 'destructor and constructor effects are checked closed-world'
```

## Structural test follow-up (2026-09-06)

The user subsequently supplied a report with eight failures and 441 passes.
All eight failures reproduced in a focused run on `c7193b6`, and the same assertions
failed with the three performance-fix compiler classes restored from `7615e93`.
They therefore predated the performance fix. Investigation found outdated or
overly broad structural assertions:

| Affected checks | Cause and repair |
| --- | --- |
| Overloads, two hierarchy checks, implicit Object roots, instanceof, catch markers | The tests counted global IR or treated every package outside `ironwood.lang` as application code. New library dependencies contributed unrelated slots, classes, type tests, and catches. Select fixture types, receiver dispatch entries, and application functions instead. Preserve the expected two overloads, six named hierarchy types, five instanceof operations, and three source catch markers. |
| Allocation-failure storage, within the catch-marker test | D121 deliberately made the immortal OutOfMemoryError writable for its trace metadata. Assert writable global storage while retaining singleton type and source allocation unwind-edge checks. |
| Builder subsequence formatting | D118 delegates to `StringBuilder.substring`, which calls the direct char-range String factory. The old test rejected every call named `substring`. Inspect both builder slice helpers and their factory callees, retain rejection of full snapshots and scratch arrays, and verify exact allocation behavior natively. |
| Standard-library pruning | D117 removes unused virtual methods, including allocating Object rendering in a program that only returns 42. Requiring its unused exception hierarchy contradicted that behavior. Check removal in the plain program and retention of allocation-failure metadata in the existing stream-using program. |

The repair changes tests and documentation only. Native checks confirm that
numeric, character, and subsequence results allocate only retained text, that
failed slice allocation and invalid bounds preserve their specified behavior,
and that emergency allocation-failure traces remain bounded. The dispatch
pruning check preserves called overrides and removes unused String casing.
All eight reported tests and those four related checks passed in focused runs;
the full suite was not rerun.

The missed maintenance was in the existing assertions accompanying D117, D118,
and D121. Library growth exposed tests that accidentally depended on the entire
dependency closure, while intentional implementation changes left old structural
expectations behind. Updating those expectations requires checking the accepted
contract and retaining behavioral controls, rather than increasing global counts
or deleting failing assertions. The focused guidance is recorded in
[local testing](LOCAL_TESTING.md); no new AGENTS.md policy is needed.

## Earlier audit (2026-09-05)

The supplied full run on 2026-09-05 passed 413 tests. Its scope was broader
than the `CompilerTests` class name suggests: compiler frontend, typed IR, LLVM
backend, C runtime, standard library, distribution formats, and application
projects.

### Changes made

All optimization-level fixture loops now exercise O3 only. Duplicate O3
executions were removed where another test already compiled, linked, ran, and
inspected the same fixture at O3.

The native backend now caches the compiled C runtime object within one compiler
process. The cache key includes the Clang executable metadata, optimization
level, runtime source content, and runtime header content. The cache is bounded
to eight objects. A changed compiler, source, header, or optimization level
therefore produces a fresh runtime object.

Repeated diagnostic and failure programs were consolidated when one compiled
program could preserve every assertion. Large unrelated fixtures were not
combined merely to conceal their individual cost.

| Test or group | Change |
| --- | --- |
| `System.arraycopy` failures | Compile once and run 12 failure modes |
| Local and anonymous classes | Seven independent fixtures |
| Copied-key map pool allocation failures | Retain representative failure boundaries |
| Data structure entry-loan diagnostics | Batch all 40 invalid programs |
| Array failure traces | Compile once and run four failure modes |
| Data structure release diagnostics | Batch 26 invalid programs |
| Copied-key loan diagnostics | Batch cases that do not require exact package access |
| Uncaught exception failures | Compile once and run three failure modes |
| UTF-16 strings and `StringBuilder` | Remove duplicate execution |
| Null-message and caught exceptions | Compile once and run both modes |
| String concatenation | Remove duplicate execution |
| Pool ownership diagnostics | Batch invalid programs |
| Finally-transfer ownership diagnostics | Batch invalid programs |

Seven exact duplicate native test registrations were removed. Splitting the
local and anonymous aggregate kept the independent fixtures visible.

### Supplied full run

The subsequent full run supplied on 2026-09-05 passed all 412 tests.

### Focused verification

The 83 tests corresponding to the original 77 reviewed entries all passed.
The finally-transfer ownership fixture retained its diagnostics after batching.

Native tests launch LLVM tools, Clang, and executables. The first native test
also pays the runtime-object compilation cost. Linux timing comparisons must
record host load, cache state, and whether build preparation is included.

### Retained coverage

The following tests were retained. `Multiple artifacts` means that removing a
source-path, loose-class, individual-class, archive, command-line, or project
execution would weaken the behavior named by the test. `Large native fixture`
means splitting the fixture would reduce the displayed per-test duration but
would not reduce suite time. `Failure matrix` means that distinct allocation or
cleanup boundaries must run in separate processes.

| Test | Reason |
| --- | --- |
| data structures destroy internal pools and preserve caller items | Large native fixture |
| data structure releases allow caller item reclamation | Large native fixture |
| compile and link modes keep ironclass and native output separate | Multiple command-line modes |
| U3 streaming and failure cleanup run at O3 | Failure matrix |
| enums survive source class individual-file archive and tree-shaking round trips | Multiple artifacts |
| U2 file and path allocation failures roll back at O3 | Failure matrix |
| multidimensional arrays survive source class archive and tree-shaking round trips | Multiple artifacts |
| classic switch survives source-path class-path and archive round trips | Multiple artifacts |
| stack traces survive source class file and archive round trips | Multiple artifacts |
| static initialization survives source-path class-path and archive round trips | Multiple artifacts |
| minitee bounds allocations and preserves cleanup failures | Failure matrix |
| caller-owned library results survive source class archive and tree-shaking round trips | Multiple artifacts |
| multiple explicit source files compile and run together | External compiler and linker processes |
| allocation failure survives source class and archive round trips | Multiple artifacts |
| first-failure finally semantics survive source-path class-path and archive round trips | Multiple artifacts |
| enum identity ordering lookup switching and initialization run at O3 | Large native fixture |
| implicit runtime failures survive source class and archive round trips | Multiple artifacts |
| remaining statements survive source class and archive round trips | Multiple artifacts |
| static initialization ordering cycles and failures run at O3 | Large native fixture |
| IronDocs pool example runs natively | First native test and cold runtime compilation |
| array initializers survive source class and archive round trips | Multiple artifacts |
| Ironwood classes resolve through Java classpath aliases | Multiple classpath forms |
| static imports survive source class archive and tree-shaking round trips | Multiple artifacts |
| instanceof type patterns survive source class and archive round trips | Multiple artifacts |
| multi-catch and precise rethrow survive source class and archive round trips | Multiple artifacts |
| U1 allocating text services preserve allocation-failure rollback | Failure matrix |
| nested types inner construction and ordered initializer blocks follow Java rules | Large native fixture |
| pool and data structures allocate nothing after warmup | Large native fixture |
| the minigrep project runs from loose classes and an ironjar | End-to-end project and multiple artifacts |
| data structure entry loans cannot outlive their containers | All 40 container and alias diagnostics retained |
| copied map key storage is reclaimed without freeing caller keys | Large native fixture |
| owning pools roll back allocation failures without leaks | Failure matrix |
| local and anonymous types survive owner-payload distribution round trips | Multiple artifacts |
| generic set families run at O3 | Large native fixture |
| direct text results preserve allocation failure and bounds precedence | Failure matrix |
| Object and collection rendering reclaim temporary text at O3 | Multiple native fixtures |
| standard library resolves implicitly and remains class-granular | Multiple artifacts |
| U3 streaming behavior matches Java 21 | Native and Java differential processes |
| copied-key map pool cleanup survives allocation failures | Failure matrix |
| owned collection helpers run at O3 | Large native fixture |
| U3 CLI runs through source class archive and separate link | Multiple artifacts |
| object-model features survive source-path class-path and archive round trips | Multiple artifacts |
| standard-library caller-owned results teardown and rollback run at O3 | Success and failure fixtures |
| U3 construction and result OOM reclaim owned graphs | Failure matrix |
| nested types survive source-path class-path and archive round trips | Multiple artifacts |
| interface members survive source-path class-path and archive round trips | Multiple artifacts |
| modern switch survives source class and archive round trips | Multiple artifacts |
| primitive generic specializations survive class archive and tree-shaking round trips | Multiple artifacts |
| linked and character-sequence maps run at O3 | Large native fixture |
| generic inference and specificity edges run at O3 | Large native fixture |
| data structure caller loans survive allocation failures | Failure matrix |
| primitive set families run at O3 | Large native fixture |
| identity map and set iterators unlink entries across buckets | Large native fixture |
| heap buffer-key map runs at O3 | Large native fixture |
| value map and set iterators unlink entries across buckets | Large native fixture |
| allocation failure primary and secondary ordering runs at O3 | Two distinct failure fixtures |
| primitive list families run at O3 | Large native fixture |
| primitive-key maps run at O3 | Large native fixture |
| object and identity maps run at O3 | Large native fixture |
| generic list families run at O3 | Large native fixture |
| filesystem operations minimize managed scratch and preserve snapshots | Native filesystem fixture |

Use focused Linux measurements to evaluate compiler and linker performance
changes or test sharding. Splitting large fixtures only to make each displayed
duration smaller would leave total suite work unchanged.
