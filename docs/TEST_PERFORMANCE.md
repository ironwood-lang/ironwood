# Test performance audit

## Audit of the 449-test pass (2026-09-06, 20:01)

The supplied run passed all 449 tests from 20:01:02.780 to 20:06:49.301:
5 minutes 46.521 seconds after the first `RUN` line. Pairing each start/result
by test number accounts for 346.476 seconds of test execution; the remaining
45 milliseconds are gaps between tests. Compiler/bootstrap build preparation
before the first line is outside this measurement.

| Threshold | Tests | Combined time |
| --- | ---: | ---: |
| More than 1 second | 97 | 200.370 s |
| More than 2 seconds | 33 | 112.097 s |
| More than 3 seconds | 10 | 56.167 s |
| More than 5 seconds | 2 | 29.800 s |
| More than 10 seconds | 1 | 21.877 s |

The median was 0.651 seconds; 352 tests finished within one second. The two
largest entries accounted for 8.6 percent of the run. The rest is spread across
many compiler and native integration checks, so removing one large entry cannot
halve suite time.

### Avoidable build repetition

The testing-module entry is valuable coverage. It runs the real optional testing
archive, generated `@Test` entry points, assertions, lifecycle hooks, skips,
continued execution after failures, and native exit statuses. It also runs
65 migrated pool/data-structure cases and 34 destruction cases. The smaller
compiler synthesis tests use stub testing types and do not replace this coverage.
There is thematic overlap with other collection fixtures, but deleting this
entry would remove the native framework self-test and its migrated suites.

Its old 21.877-second duration included six compile/link pairs in twelve fresh
Java processes. The script now builds one repository-only selector with all six
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

A matched local comparison ran those same two tests, in the same order, in a
fresh Java process before and after the edits, without compiler build preparation:

| Test | Before | After |
| --- | ---: | ---: |
| Standard-library testing module | 21.605 s | 6.993 s |
| Everyday String allocation failures | 8.601 s | 2.217 s |
| Pair total | 30.206 s | 9.210 s |

This removes about 21 seconds of work in that comparison. The combined suite
also measured 7.105 and 7.229 seconds in focused checks. The String test measured
1.726 seconds after another native test had warmed the runtime-object cache.
These observations show why cache/startup context matters; they are not a
cross-platform guarantee. Applying the measured saving to the supplied run
suggests approximately 5 minutes 25 seconds, but no new full run was performed.

The static-initialization round-trip test also compiled an unused sibling into
an output directory that was never read again. That compilation was removed;
the sibling is still built in the actual library directory and included in the
archive used by the pruning assertion. Its four input-form checks remain.

### Every test above two seconds

These entries were inspected for build repetition and the behavior each build
covers. Times below are from the user's full pass, not predicted post-change
values. A single displayed test can contain many independent compiler programs
or native failure scenarios.

| Seconds | Test | Assessment |
| --- | --- | --- |
| 21.877 | standard-library testing module reports deterministic native results | Improved: six independent compile/link pairs became one shared build; all six suite processes and output checks remain. |
| 7.923 | everyday String allocation failures reclaim temporary storage | Improved: ten compile/link pairs became one selectable program; all ten operations and every allocation-failure budget remain. |
| 3.923 | compile and link modes keep ironclass and native output separate | Keep: actual CLI subprocesses, current-directory discovery, output modes, entry selection, and rejection diagnostics. |
| 3.362 | static initialization survives source-path class-path and archive round trips | Removed one unused compilation. Keep four source/directory/individual-class/archive paths and initialization/pruning assertions. |
| 3.292 | nested types inner construction and ordered initializer blocks follow Java rules | Keep: 29 separate positive/negative compilations for access, owner typing, construction, initializer order, and reclamation. Combining invalid hierarchies can suppress later diagnostics. |
| 3.219 | enums survive source class individual-file archive and tree-shaking round trips | Keep: four input forms plus constant-specific dispatch, singleton identity, and removal of unused enum metadata. |
| 3.185 | stack traces survive source class file and archive round trips | Keep: four input forms verify exact source files, lines, callable identities, and inherited exception behavior. |
| 3.168 | multidimensional arrays survive source class archive and tree-shaking round trips | Keep: four input forms verify nested-array types, casts, and descriptor pruning. |
| 3.148 | classic switch survives source-path class-path and archive round trips | Keep: four input forms reconstruct switch control flow and compile-time constants. |
| 3.070 | Throwable printing preserves lifetime and callback effects | Keep: three native lifetime/printing fixtures and six callback, destructor, and alias rejection checks. |
| 2.723 | data structures destroy internal pools and preserve caller items | Keep: one broad native collection fixture verifies destruction, retained caller objects, and allocation-free reuse across container states. |
| 2.692 | U2 file and path allocation failures roll back at O3 | Keep: three allocation-boundary fixtures for paths and file reads, with separate native failure budgets. Small future batching candidate. |
| 2.608 | minitee bounds allocations and preserves cleanup failures | Keep: stream/lifecycle/CLI builds, nine stream modes, and injected input/output/allocation failures. |
| 2.563 | caller-owned library results survive source class archive and tree-shaking round trips | Keep: source, loose-class, and archive reconstruction of fresh/borrowed ownership results and removal of unused declarations. |
| 2.547 | Ironwood classes resolve through Java classpath aliases | Keep: all three classpath spellings, adjacent and package output, individual-file and directory inputs, compile and link modes. |
| 2.470 | multiple explicit source files compile and run together | Keep: real CLI subprocesses verify multiple explicit sources and default output/current-directory linking. |
| 2.465 | implicit runtime failures survive source class and archive round trips | Keep: three input forms reconstruct catchable native safety checks and exception metadata. |
| 2.459 | remaining statements survive source class and archive round trips | Keep: three input forms reconstruct loop transfers through finally cleanup. |
| 2.457 | U3 streaming and failure cleanup run at O3 | Keep: two fixtures verify streaming and cleanup under a bounded file-descriptor limit. |
| 2.443 | static imports survive source class archive and tree-shaking round trips | Keep: three input forms verify imported declarations, initialization, and unused member/class pruning. |
| 2.442 | first-failure finally semantics survive source-path class-path and archive round trips | Keep: three input forms preserve primary/secondary exception behavior and exceptional cleanup. |
| 2.434 | data structure releases allow caller item reclamation | Keep: one broad native fixture verifies detachment and subsequent caller reclamation across collection families. |
| 2.423 | array initializers survive source class and archive round trips | Keep: three input forms preserve initializer typing, evaluation, and ownership. |
| 2.419 | multi-catch and precise rethrow survive source class and archive round trips | Keep: three input forms preserve union handlers, precise rethrow, and runtime metadata pruning. |
| 2.414 | instanceof type patterns survive source class and archive round trips | Keep: three input forms preserve pattern bindings and flow-sensitive control flow. |
| 2.404 | allocation failure survives source class and archive round trips | Keep: three input forms retain catchable allocation-failure metadata and behavior. |
| 2.373 | local and anonymous types survive owner-payload distribution round trips | Keep: source, class, and archive forms preserve local/anonymous owner payloads and isolated dependency discovery. |
| 2.364 | enum identity ordering lookup switching and initialization run at O3 | Keep: three distinct native fixtures for ordinary enums, constant bodies, and initialization failure. |
| 2.318 | static initialization ordering cycles and failures run at O3 | Keep: three fixtures for ordering/cycles, caught failure, and uncaught diagnostics. |
| 2.239 | U1 allocating text services preserve allocation-failure rollback | Keep: three different text/environment allocation boundaries and budgets. Small future batching candidate. |
| 2.239 | everyday String operations match Java | Keep: two native/Java differential fixtures, including fresh Java oracle compilation and process startup. |
| 2.230 | borrow dispatch uses exact overloads defaults and receiver flow | Keep: multiple receiver-flow, overload, generic, and default-method programs whose closed-world ownership proofs differ. |
| 2.204 | everyday StringBuilder ownership and allocations are preserved | Keep: native ownership/publication fixtures plus callback and alias rejection cases. |

The artifact round trips check more than CLI spelling: source discovery, class
reconstruction, exact generic/ownership/source metadata, and archive pruning can
fail independently. Keep those input paths. Combining related failure fixtures
is a reasonable smaller follow-up, provided initialization and injected budgets
remain independent. Splitting long tests into more names would not save work.
No additional slow entry above two seconds showed an evident unbounded loop in
its implementation or in this completed pass; runtime profiling would be needed
to attribute small remaining costs more precisely.

### Verification and prevention

Only the three changed test entries were run for functional verification; all
passed, and the two largest were additionally measured in matched invocations.
The combined selector rejects missing, unknown, and extra arguments. Shell syntax,
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
| `4ded1c0`, immediately before T3 | Diagnostic and exit 1 in 0.43 s |
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
absence of emitted class artifacts. The repaired test completed in 0.28 s.

### Valid-program compilation cost

The public printer uses `ironwood.ds.ArrayList` for visited objects. The current
compiler loads source dependencies at class granularity before pruning unused
members. Consequently, even a program that only returns 42 grew from 36 analyzed
classes and 339 functions at `f4f966d` to 43 classes and 413 functions at
`7615e93`. Its native code need not use those additional members.

Java Flight Recorder profiles exposed repeated dispatch selection and effect
target enumeration. Fixed-point analyses were rebuilding unchanged relationships
while their summaries evolved. Two bounded-lifetime caches now retain:

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

Measurements below used macOS ARM64 and Oracle JDK 23.0.1, with compiler sources
built for Java 21. The small probe repeatedly called `CompilerPipeline.compile`
on the same source in one JVM under JFR profiling. Each value is the median of
the final 20 of 40 compilations:

| Compiler | Small-program compilation |
| --- | ---: |
| Before public stack traces, `f4f966d` | 166.5 ms |
| Public stack traces, `7615e93` | 218.7 ms |
| With effect-target caching alone | 193.1 ms |
| With both caches | 175.3 ms |

A second comparison used six existing compiler tests: arithmetic rejection,
data-structure entry loans, default resolution, destructor/constructor effects,
primitive generics, and local/anonymous classes. Each compiler ran this same
group in a fresh JVM three times, serially, with the compiler order changed
between rounds. The old compiler used its matching standard library. The
regressed compiler restored the three changed production classes ahead of the
current classes on the Java classpath. All assertions passed in every run.

| Compiler | Median group elapsed time | Observed range |
| --- | ---: | ---: |
| `f4f966d` | 8.014 s | 7.869 to 8.298 s |
| `7615e93` | 10.021 s | 9.934 to 10.453 s |
| With both caches | 7.551 s | 7.486 to 7.676 s |

This group recovered the regression and took about 25 percent less time than
`7615e93`. The tiny probe retained a small difference from its earlier baseline.
These are local measurements, not a universal speedup or a full-suite estimate.

### Standard-library testing-module case

`standard-library testing module reports deterministic native results` invokes
`scripts/test-stdlib.sh --skip-build`. It builds six native executables through
12 separate Java compiler processes, then verifies 103 normal cases across five
suites plus an intentional-failure fixture. Its parent buffers script output,
so the intermediate builds are invisible while that test is running.

The test took 24.24 s before the caches and 22.09 s after them in this
investigation. Earlier local reports from before public stack traces recorded
approximately 18.9 to 20.1 s. These individual measurements are sensitive to
host load and are not a matched three-run comparison. All six generated
executables individually completed in 2.5 to 3.2 ms including process launch.
The long displayed duration is predominantly compilation and linking; the
existing build aggregation accounts for much of it, with an additional compiler
regression confirmed separately above.

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

This audit records the investigation of tests that exceeded one second in the
macOS ARM64 report captured on 2026-09-05. The supplied full run passed 413
tests in 4 minutes 39 seconds. It contained 77 tests above one second, totaling
162.327 seconds.

The measured suite was broader than its `CompilerTests` class name suggests. It
covered the compiler frontend, typed IR, LLVM backend, C runtime, standard
library, distribution formats, and application projects.

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

| Test or group | Before | Focused result | Change |
| --- | ---: | ---: | --- |
| `System.arraycopy` failures | 8.275 s | 0.681 s | Compile once and run 12 failure modes |
| Local and anonymous classes | 5.023 s | 0.583 to 0.647 s each | Seven independently timed fixtures |
| Copied-key map pool allocation failures | 4.400 s | 1.368 s | Retain representative failure boundaries |
| Data structure entry-loan diagnostics | 4.250 s | 1.646 s cold | Batch all 40 invalid programs |
| Array failure traces | 2.466 s | 0.602 s | Compile once and run four failure modes |
| Data structure release diagnostics | 2.448 s | 0.617 s | Batch 26 invalid programs |
| Copied-key loan diagnostics | 2.318 s | 0.820 s | Batch cases that do not require exact package access |
| Uncaught exception failures | 1.837 s | 0.619 s | Compile once and run three failure modes |
| UTF-16 strings and `StringBuilder` | 1.682 s | 0.724 s | Remove duplicate execution |
| Null-message and caught exceptions | 1.541 s | 0.618 s | Compile once and run both modes |
| String concatenation | 1.503 s | 0.665 s | Remove duplicate execution |
| Pool ownership diagnostics | 1.171 s | 0.250 s | Batch invalid programs |
| Finally-transfer ownership diagnostics | 1.784 s | 0.913 s | Batch invalid programs |

Seven exact duplicate native test registrations were removed. Splitting the
local and anonymous aggregate kept the independent fixtures visible.

### Supplied full run

The subsequent macOS ARM64 run supplied on 2026-09-05 passed all 412 tests in
3 minutes 35 seconds. In that report, 58 tests exceeded one second and totaled
98.708 seconds.

### Focused verification

The 83 current tests corresponding to the original 77 slow tests all passed.
Their measured total was 124.354 seconds, 37.973 seconds or 23.4 percent less
than the same logical group in the supplied report. A subsequent focused change
reduced the finally-transfer ownership test from 1.784 to 0.913 seconds. This
left 62 tests above one second in that focused measurement when the cold
1.646-second entry-loan result was included.

Timings are not stable enough for a hard one-second pass or fail rule. Native
tests launch LLVM tools, Clang, and executables. The first native test also pays
the one-time runtime-object compilation cost. Host load and filesystem cache
state can move a test across the threshold without a code change.

### Tests retained above one second

The following tests were retained. `Multiple artifacts` means that removing a
source-path, loose-class, individual-class, archive, command-line, or project
execution would weaken the behavior named by the test. `Large native fixture`
means splitting the fixture would reduce the displayed per-test duration but
would not reduce suite time. `Failure matrix` means that distinct allocation or
cleanup boundaries must run in separate processes.

| Seconds | Test | Reason |
| ---: | --- | --- |
| 4.248 | data structures destroy internal pools and preserve caller items | Large native fixture |
| 3.902 | data structure releases allow caller item reclamation | Large native fixture |
| 3.464 | compile and link modes keep ironclass and native output separate | Multiple command-line modes |
| 2.518 | U3 streaming and failure cleanup run at O3 | Failure matrix |
| 2.517 | enums survive source class individual-file archive and tree-shaking round trips | Multiple artifacts |
| 2.516 | U2 file and path allocation failures roll back at O3 | Failure matrix |
| 2.508 | multidimensional arrays survive source class archive and tree-shaking round trips | Multiple artifacts |
| 2.412 | classic switch survives source-path class-path and archive round trips | Multiple artifacts |
| 2.395 | stack traces survive source class file and archive round trips | Multiple artifacts |
| 2.369 | static initialization survives source-path class-path and archive round trips | Multiple artifacts |
| 2.354 | minitee bounds allocations and preserves cleanup failures | Failure matrix |
| 2.168 | caller-owned library results survive source class archive and tree-shaking round trips | Multiple artifacts |
| 2.137 | multiple explicit source files compile and run together | External compiler and linker processes |
| 2.137 | allocation failure survives source class and archive round trips | Multiple artifacts |
| 1.957 | first-failure finally semantics survive source-path class-path and archive round trips | Multiple artifacts |
| 1.940 | enum identity ordering lookup switching and initialization run at O3 | Large native fixture |
| 1.905 | implicit runtime failures survive source class and archive round trips | Multiple artifacts |
| 1.904 | remaining statements survive source class and archive round trips | Multiple artifacts |
| 1.889 | static initialization ordering cycles and failures run at O3 | Large native fixture |
| 1.875 | IronDocs pool example runs natively | First native test and cold runtime compilation |
| 1.873 | array initializers survive source class and archive round trips | Multiple artifacts |
| 1.859 | Ironwood classes resolve through Java classpath aliases | Multiple classpath forms |
| 1.853 | static imports survive source class archive and tree-shaking round trips | Multiple artifacts |
| 1.841 | instanceof type patterns survive source class and archive round trips | Multiple artifacts |
| 1.838 | multi-catch and precise rethrow survive source class and archive round trips | Multiple artifacts |
| 1.837 | U1 allocating text services preserve allocation-failure rollback | Failure matrix |
| 1.834 | nested types inner construction and ordered initializer blocks follow Java rules | Large native fixture |
| 1.712 | pool and data structures allocate nothing after warmup | Large native fixture |
| 1.658 | the minigrep project runs from loose classes and an ironjar | End-to-end project and multiple artifacts |
| 1.646 | data structure entry loans cannot outlive their containers | All 40 container and alias diagnostics retained |
| 1.594 | copied map key storage is reclaimed without freeing caller keys | Large native fixture |
| 1.580 | owning pools roll back allocation failures without leaks | Failure matrix |
| 1.543 | local and anonymous types survive owner-payload distribution round trips | Multiple artifacts |
| 1.499 | generic set families run at O3 | Large native fixture |
| 1.496 | direct text results preserve allocation failure and bounds precedence | Failure matrix |
| 1.469 | Object and collection rendering reclaim temporary text at O3 | Multiple native fixtures |
| 1.434 | standard library resolves implicitly and remains class-granular | Multiple artifacts |
| 1.392 | U3 streaming behavior matches Java 21 | Native and Java differential processes |
| 1.368 | copied-key map pool cleanup survives allocation failures | Failure matrix |
| 1.364 | owned collection helpers run at O3 | Large native fixture |
| 1.328 | U3 CLI runs through source class archive and separate link | Multiple artifacts |
| 1.311 | object-model features survive source-path class-path and archive round trips | Multiple artifacts |
| 1.304 | standard-library caller-owned results teardown and rollback run at O3 | Success and failure fixtures |
| 1.300 | U3 construction and result OOM reclaim owned graphs | Failure matrix |
| 1.291 | nested types survive source-path class-path and archive round trips | Multiple artifacts |
| 1.273 | interface members survive source-path class-path and archive round trips | Multiple artifacts |
| 1.234 | modern switch survives source class and archive round trips | Multiple artifacts |
| 1.226 | primitive generic specializations survive class archive and tree-shaking round trips | Multiple artifacts |
| 1.195 | linked and character-sequence maps run at O3 | Large native fixture |
| 1.192 | generic inference and specificity edges run at O3 | Large native fixture |
| 1.184 | data structure caller loans survive allocation failures | Failure matrix |
| 1.172 | primitive set families run at O3 | Large native fixture |
| 1.159 | identity map and set iterators unlink entries across buckets | Large native fixture |
| 1.159 | heap buffer-key map runs at O3 | Large native fixture |
| 1.156 | value map and set iterators unlink entries across buckets | Large native fixture |
| 1.152 | allocation failure primary and secondary ordering runs at O3 | Two distinct failure fixtures |
| 1.141 | primitive list families run at O3 | Large native fixture |
| 1.118 | primitive-key maps run at O3 | Large native fixture |
| 1.114 | object and identity maps run at O3 | Large native fixture |
| 1.112 | generic list families run at O3 | Large native fixture |
| 1.013 | filesystem operations minimize managed scratch and preserve snapshots | Native filesystem fixture |

The sub-1.5-second entries should be treated as near-threshold tests, not fixed
performance regressions. Further wall-clock reduction should come from safe
compiler and linker performance improvements or test sharding. Splitting large
fixtures only to make each displayed duration smaller would leave total suite
time unchanged.
