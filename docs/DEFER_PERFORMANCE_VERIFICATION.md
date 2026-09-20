<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Defer performance verification

Date: 2026-09-19. Scope: only Milestone 2 step 1 and Section 8 of the approved
[plan](DEFER_PLAN.md), after acceptance of [Milestone 1](DEFER_FREE_VERIFICATION.md).
Compiler source is revision `a3a8b335774930dbdac9e549e3af947e19ec4e73`;
the checkpoint containing this document adds the fixtures, driver, and evidence.
Work stays local on `new-defer-keyword`. Adoption and integration are separate,
unselected work. No compiler, runtime, or standard-library change was needed.

## Reproduction and scope

Environment: Oracle Java 21.0.1, LLVM 23.1.0, macOS 26.6.2 ARM64. The driver
currently enforces this machine-code inspection platform; no other-platform
performance claim is made. From the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/build.sh
python3 scripts/test-defer-performance.py --llvm-home /opt/homebrew/opt/llvm --rounds 12
python3 scripts/test-defer-performance.py --llvm-home /opt/homebrew/opt/llvm \
  --control --case free --case failing-2 --rounds 16
```

Use repeated exact `--case` selections for an investigation. `--skip-build`
reuses fixtures only when their source, compiler-jar hash, and executable hash
still match. A subsequent compiler rebuild can invalidate that hash even with
unchanged source. The final measurement batch used `--skip-build --rounds 12`
after compiling and inspecting every variant. Earlier batches with 8 and 12
rounds prompted the timing and inspection checks described below.

The dedicated driver extends the existing single-action driver's approach:
primitive batch timing with `System.nanoTime()` and host-side paired samples.
It introduces no benchmark API or framework. `ironwood.bench` was reviewed;
per-operation sampling would perturb these short operations, and its reporting
objects are unnecessary for this linked-code comparison. Timing, output, and
allocation-counter reads surround the batch, with no socket or stdout work
inside it.

All variants compile and link with `--unfreed=error` and `-O3`. Inspection
reproduces `NativeBackend`'s actual optimization pipeline:

```sh
llvm-as program.ll -o program.bc
opt '-passes=default<O3>' -inline-threshold=1000 -enable-partial-inlining \
  -S program.bc -o optimized.ll
llc -O=3 --relocation-model=pic -filetype=asm optimized.ll -o program.s
llvm-objdump --disassemble --no-show-raw-insn program
llvm-size -A program
```

The compiler's subsequent immutable trace-table injection is preserved in the
linked executable. Both the optimized LLVM/assembly and final linked machine
code were inspected. The driver requires every selected fixture/helper function
to be present in its assembly inspection; exception-table labels cannot silently
consume later functions. It preserves normalized assembly, linked disassembly,
function byte inventories, sections, source, build logs, hashes and raw samples
under ignored `integration-tests/target/defer-performance/`. Investigation logs
and earlier samples are under ignored `workspace/defer-performance/`.

## Equivalence and behavioral checks

There are **18 cases, 48 separately linked variants, and 68 variant/mode
configurations**. Each configuration first passes an independent eight-iteration
event/checksum oracle. The final batch contains **816 timed process executions**,
12 per configuration, alternating variant order. Each process also checks the
first successful call and warms 1,000 successful or eight failing iterations.
Successful batches use 32,000,000 iterations; failure batches use 512.

The handwritten nested form captures operands in the same order and protects
the same source tail as each defer. Free targets remain unchanged. Null checks,
target initialization, invocation effects and failures occur at cleanup time.
No baseline prevalidates a receiver or drops exceptional cleanup.

For the multiple-action cases, a third, flatter form moves only total primitive
capture computations together, in their original order. These particular
computations access an already initialized `Main`, increment a counter and do
integer arithmetic; they cannot fail. This makes the changed protection boundary
equivalent for these fixtures. It is not an exemption for unknown call effects.
The nonthrowing flat cleanup can use a reverse-order statement list. The failing
flat cleanup still nests `finally` inside its one body-protecting finally, so a
failure cannot skip later cleanup. A plain throwing statement list would be an
invalid baseline. Nested source-block boundaries are retained for transfers.

| Cases | Action-owning block exits and observations |
| --- | --- |
| `call`, `straight-1/2/4/8` | Normal block completion; ordered, exactly-once captures and LIFO calls. |
| `free` | One 16-byte array allocation followed by proven-safe free per iteration. |
| `pool` | Preallocated checkout, mutation, release, then final pool/builder reclamation. |
| `nullable` | Owned receiver with optimizer-proven non-null success; null failure primary; body failure with null failure secondary. |
| `indirect` | The same three modes through an array lookup that retains the receiver null check. |
| `initialization` | First body runs before target initialization; cleanup initializes the target once; subsequent calls preserve the initialized path. |
| `transfers-1/2/4/8` | Nested blocks, early return, break, continue and normal fallthrough. A four-input cycle executes 1, 3, 1 and 3 loop bodies respectively. |
| `failing-1/2/4/8` | Success measured separately; failure mode makes every cleanup throw, with an additional body failure on even inputs. Checks primary action ID, exception types, ordered secondary IDs and attempted remaining actions. |

Pool acquisition/release stays in the owning method, matching existing ownership
proofs. The owned nullable fixture branches at the borrow call instead of freeing
an uncertain merged reference. The indirect fixture intentionally retains two
fixed process-owned lookup objects, allocated before the first-call probe.
No safety check or ownership rule was changed to admit a fixture.

All first-success probes report zero allocations and zero live growth, except
`free`, which reports exactly **1 allocation / 0 live growth**. Every successful
timed batch reports **0 / 0**, except `free`, which reports exactly
**32000000 / 0**. Nullable primary/secondary failure batches respectively report
**512 / 512** and **1024 / 1024**. Multiple-failure batches report
**768 / 768**, **1280 / 1280**, **2304 / 2304**, and **4352 / 4352** for 1, 2,
4, and 8 actions. Those live objects are the explicitly thrown/caught exceptions,
which retain their existing lifetime rules. All variants agree. First-call
results, full-batch counts, capture/action/body counts and paired checksums are
asserted by the driver, not inferred from timings.

## Code size and generated work

`D` means deferred, `N` directly nested handwritten cleanup, and `F` the flatter
handwritten form. D and N have identical inspected application/helper assembly
in **all 18 cases**, with identical linked function sizes and total text sizes.
The byte inventory includes main (and cleanup inlined into it), fixture methods,
partial-inlining helpers, fixture initialization/rollback/destructor/throw
helpers and shared destroy/rollback helpers, each counted once. Referenced
standard-library and runtime code is included in total linked `__TEXT,__text`.
This is code-section size, not executable file size.

| Case | Text D = N | Text F | D minus F / ratio | Fixture + helpers D = N | F |
| --- | ---: | ---: | ---: | ---: | ---: |
| call | 14600 | - | - | 1452 | - |
| free | 15624 | - | - | 2244 | - |
| pool | 19016 | - | - | 3512 | - |
| nullable | 16968 | - | - | 2980 | - |
| indirect | 18440 | - | - | 4184 | - |
| initialization | 15432 | - | - | 2312 | - |
| straight-1 | 14600 | 14600 | 0 / 1.000000 | 1452 | 1452 |
| straight-2 | 14600 | 14600 | 0 / 1.000000 | 1444 | 1444 |
| straight-4 | 14600 | 14600 | 0 / 1.000000 | 1484 | 1484 |
| straight-8 | 14728 | 14728 | 0 / 1.000000 | 1556 | 1556 |
| transfers-1 | 14984 | 14984 | 0 / 1.000000 | 1840 | 1840 |
| transfers-2 | 15112 | 15112 | 0 / 1.000000 | 1964 | 1964 |
| transfers-4 | 15368 | 15368 | 0 / 1.000000 | 2212 | 2212 |
| transfers-8 | 15048 | 15048 | 0 / 1.000000 | 1880 | 1880 |
| failing-1 | 16520 | 16520 | 0 / 1.000000 | 2292 | 2292 |
| failing-2 | 16648 | 16712 | -64 / 0.996170 | 2452 | 2520 |
| failing-4 | 16776 | 17096 | -320 / 0.981282 | 2552 | 2868 |
| failing-8 | 17160 | 17672 | -512 / 0.971028 | 2940 | 3464 |

Every D/N byte delta is zero and ratio is 1.000000. Fixture/helper D/F deltas
are also zero except failing-2, failing-4 and failing-8: respectively
-68 / 0.973016, -316 / 0.889819 and -524 / 0.848730.

The nonthrowing flat cases have the same instructions as D apart from a
one-time trace-table length immediate where source structure changes the number
of immutable sites. It does not add instructions or execute in the work loop.
The failing flat forms at 2, 4 and 8 actions contain more failure-only control
flow and secondary-list merging. D051 preserves an inner cleanup's completed
secondary sequence when attaching it to an outer primary; the runtime copies
that sequence. Grouping failing actions in one finally therefore creates
intermediate lists that the directly nested action form avoids. Successful
loop instruction work and stack requirements remain equivalent, including
register renaming in the two-action flat case. Small residual differences
between the total-text and selected-function deltas are layout/alignment bytes.
Disassembly places the differing gap between `ironwood_trace_register_current`
and `ironwood_throwable_trace_release`: D/F gaps are 20/16, 48/52, and 44/32
bytes for 2, 4, and 8 failing actions. These exactly explain the remaining
4, -4, and 12 bytes. No other function sizes or inter-function gaps differ.
The smaller eight-action transfer executable versus four actions reflects
LLVM partial inlining into a shared helper in all three variants.

Entry stack areas match each baseline: call/straight/transfer/failing entries
use 96 bytes, free 128, pool 176, indirect 112, and initialization 144. The
owned-nullable entry uses 96 bytes and its outlined loop 128. The indirect loop
uses 112. The eight-action transfer helper uses 16; the four-action failure helper
uses 112 and the other failure helpers use 128. Saved registers and spills match
D/N; the flat successful paths add no work that defer could avoid. Captures are
ordinary values and fixed register or stack operands, with no runtime action
representation.

The direct scalar loops have no cleanup runtime call. Free retains the ordinary
allocation, destruction guard and deallocation operations. Pool retains its
existing checkout/release operations, with zero allocation in the measured
cycle. Indirect receiver code retains one normal-path receiver test after body
effects; the proven non-null path eliminates it in both forms. Exceptional
cleanup copies retain the same necessary checks. Cold target initialization
remains at invocation and executes once in both forms.

Managed counters alone cannot prove absence of native allocations. The call
graphs and linked instructions were also checked: there is no defer-specific
allocation, native action storage, registration, registry access, TLS operation,
synchronization, callback, extra helper call or capture spill. Runtime allocator
calls for explicit allocations, and exception wrapper/trace/secondary metadata
on failing paths, remain the existing operations. The one-time process trace
table registration is outside the work loop. The free pair's entire linked
disassembly, including the runtime, is identical.

Unwind and other metadata remain separate from code bytes. Unwind columns list
`__gcc_except_tab / __unwind_info / __eh_frame`; trace columns sum `__probes` and
`__probe_descs`; constants sum both Mach-O `__const` sections. All values are
bytes. Other section sizes are unchanged between each case's variants. The flat
nonthrowing form sometimes has fewer immutable source-site entries, even with
identical code. The eight-action flat failure form has substantially more
source-site metadata as well as additional failure-only code and merging.

| Case | Unwind D = N | Unwind F | Trace sections D / N / F | Constants D / N / F |
| --- | --- | --- | --- | --- |
| call | 248 / 472 / 896 | - | 10735 / 10735 | 34024 / 34024 |
| free | 448 / 528 / 1040 | - | 11359 / 11359 | 36488 / 36488 |
| pool | 672 / 680 / 1608 | - | 14989 / 14989 | 58376 / 58376 |
| nullable | 372 / 536 / 1088 | - | 12630 / 12630 | 41464 / 41464 |
| indirect | 472 / 616 / 1360 | - | 12627 / 12627 | 44744 / 44744 |
| initialization | 332 / 488 / 944 | - | 11166 / 11166 | 34584 / 34584 |
| straight-1 | 248 / 472 / 896 | 248 / 472 / 896 | 10735 / 10735 / 10735 | 34024 / 34024 / 34024 |
| straight-2 | 248 / 472 / 896 | 248 / 472 / 896 | 10836 / 10836 / 10836 | 34264 / 34264 / 34168 |
| straight-4 | 248 / 472 / 896 | 248 / 472 / 896 | 11042 / 11042 / 11042 | 34744 / 34744 / 34456 |
| straight-8 | 248 / 472 / 896 | 248 / 472 / 896 | 11450 / 11450 / 11450 | 35704 / 35704 / 35032 |
| transfers-1 | 248 / 472 / 896 | 248 / 472 / 896 | 10876 / 10876 / 10876 | 34168 / 34168 / 34168 |
| transfers-2 | 248 / 472 / 896 | 248 / 472 / 896 | 11194 / 11194 / 11194 | 34552 / 34552 / 34552 |
| transfers-4 | 248 / 472 / 896 | 248 / 472 / 896 | 11662 / 11662 / 11662 | 35320 / 35320 / 35128 |
| transfers-8 | 248 / 480 / 896 | 248 / 480 / 896 | 11456 / 11456 / 11456 | 36888 / 36888 / 36312 |
| failing-1 | 408 / 600 / 1312 | 408 / 600 / 1312 | 11842 / 11842 / 11842 | 38600 / 38600 / 38600 |
| failing-2 | 436 / 600 / 1312 | 448 / 600 / 1312 | 11959 / 11959 / 11986 | 38840 / 38840 / 39032 |
| failing-4 | 484 / 600 / 1312 | 548 / 600 / 1312 | 12166 / 12166 / 12268 | 39320 / 39320 / 41432 |
| failing-8 | 584 / 600 / 1312 | 740 / 600 / 1312 | 12595 / 12595 / 12897 | 40280 / 40280 / 87704 |

## Repeated timing and attribution

Cells below give median milliseconds with the observed minimum to maximum in
parentheses. Ratios compare D to each baseline; no percentage allowance was
used as an acceptance rule.

| Case / mode | D median (range), ms | N median (range), ms | F median (range), ms | D/N | D/F |
| --- | ---: | ---: | ---: | ---: | ---: |
| call / success | 27.974 (27.920 to 28.394) | 27.973 (27.941 to 28.269) | - | 1.000071 | - |
| free / success | 593.970 (553.709 to 622.504) | 577.490 (557.264 to 600.715) | - | 1.028536 | - |
| pool / success | 81.852 (81.433 to 82.281) | 81.999 (81.552 to 82.489) | - | 0.998207 | - |
| nullable / success | 48.791 (48.742 to 48.953) | 48.800 (48.687 to 48.906) | - | 0.999816 | - |
| nullable / null primary | 14.184 (13.827 to 17.155) | 14.512 (14.004 to 15.116) | - | 0.977330 | - |
| nullable / body + null | 29.008 (28.328 to 29.564) | 29.116 (28.247 to 29.690) | - | 0.996274 | - |
| indirect / success | 95.972 (95.893 to 96.297) | 95.990 (95.771 to 98.213) | - | 0.999807 | - |
| indirect / null primary | 15.233 (14.983 to 15.984) | 15.432 (15.007 to 16.025) | - | 0.987137 | - |
| indirect / body + null | 30.997 (30.621 to 32.173) | 31.143 (30.494 to 31.957) | - | 0.995328 | - |
| initialization / success | 55.990 (50.900 to 77.639) | 58.425 (52.942 to 84.688) | - | 0.958331 | - |
| straight-1 / success | 27.959 (27.920 to 28.087) | 27.959 (27.929 to 28.110) | 27.965 (27.923 to 28.070) | 1.000000 | 0.999803 |
| straight-2 / success | 48.941 (48.884 to 49.142) | 48.928 (48.869 to 49.083) | 48.913 (48.879 to 50.091) | 1.000266 | 1.000562 |
| straight-4 / success | 90.987 (90.936 to 91.181) | 91.020 (90.945 to 91.380) | 90.998 (90.943 to 91.219) | 0.999632 | 0.999874 |
| straight-8 / success | 174.927 (174.813 to 177.991) | 174.989 (174.831 to 175.414) | 175.025 (174.868 to 175.408) | 0.999640 | 0.999437 |
| transfers-1 / success | 98.238 (98.116 to 98.938) | 98.245 (98.104 to 98.348) | 98.194 (98.111 to 98.520) | 0.999934 | 1.000453 |
| transfers-2 / success | 140.399 (140.308 to 142.693) | 140.367 (140.260 to 140.752) | 140.353 (140.248 to 140.903) | 1.000228 | 1.000324 |
| transfers-4 / success | 224.277 (224.065 to 224.470) | 224.276 (224.082 to 224.929) | 224.376 (224.140 to 226.461) | 1.000002 | 0.999557 |
| transfers-8 / success | 392.301 (391.894 to 392.550) | 392.057 (391.833 to 392.586) | 392.310 (391.746 to 399.054) | 1.000624 | 0.999977 |
| failing-1 / success | 75.502 (75.288 to 75.704) | 75.535 (75.405 to 75.834) | 75.466 (75.356 to 75.591) | 0.999570 | 1.000490 |
| failing-1 / failures | 20.134 (19.532 to 20.728) | 20.167 (19.902 to 20.849) | 20.203 (19.786 to 20.586) | 0.998388 | 0.996609 |
| failing-2 / success | 112.077 (102.749 to 118.635) | 113.543 (107.449 to 117.548) | 110.399 (100.484 to 117.827) | 0.987089 | 1.015199 |
| failing-2 / failures | 37.373 (36.249 to 38.143) | 37.190 (36.142 to 38.445) | 37.762 (37.137 to 38.764) | 1.004921 | 0.989699 |
| failing-4 / success | 146.895 (146.795 to 148.330) | 146.987 (146.809 to 147.204) | 146.909 (146.788 to 147.227) | 0.999381 | 0.999912 |
| failing-4 / failures | 74.206 (72.156 to 75.329) | 74.113 (72.367 to 75.430) | 79.352 (78.223 to 80.199) | 1.001248 | 0.935149 |
| failing-8 / success | 231.073 (230.855 to 232.500) | 231.137 (230.800 to 231.384) | 230.986 (230.802 to 233.822) | 0.999725 | 1.000379 |
| failing-8 / failures | 158.486 (154.675 to 168.656) | 158.607 (156.986 to 163.601) | 301.183 (297.916 to 304.379) | 0.999240 | 0.526213 |

Short initial batches showed host variation, especially allocation/free,
initialization and two-action success. The harness was corrected to use the
same `argv[0]` for every executable, avoiding startup-heap differences from
variant directory names, and successful batches were lengthened to 32 million
iterations. The final samples above retain the observed variation rather than
selecting only favorable timings.

The free pair still had a raw median ratio of 1.028536 despite identical whole
linked disassembly. A further 16-round control copied each payload to the same
executable path, alternating order, and included a third label running the
**exact same deferred executable bytes**. In that control D/N was **0.996222**;
D/identical-binary was **0.977998**. Median milliseconds were
583.478 / 585.691 / 596.604, with ranges 554.766 to 614.722 /
556.548 to 620.725 / 557.527 to 627.257.

The same control for two-action success gave D/N **1.016534** and
D/identical-binary **1.023875**. Medians were 113.003 / 111.165 / 110.368 ms;
ranges were 103.639 to 144.044 / 103.732 to 119.465 / 102.562 to 117.108.
Its D/N direction also changed across the earlier batches. These controls and
the instruction/size evidence identify measurement variation, with no
reproducible slowdown attributable to defer lowering. No speedup is claimed
from small ratios. The larger failure-only difference for grouped flat cleanup
is accompanied by extra secondary-list merging and larger source-site metadata;
it is not a universal speedup claim.

## Checks and stage outcome

All **13 focused call/free selections passed**, including mandatory safety
rejections, checked exceptions, typed cleanup replay, native exit/failure order,
loopback cleanup, and source/class/archive reconstruction:

```sh
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
  --test 'combined defer cleanup closes and reclaims loopback sockets at O3'
```

`./scripts/check-licenses.sh` passed (five existing OpenJDK-derived source files),
and `git diff --check` passed. Driver syntax, saved fixture sources, all 18 final
reports and both timing-control reports passed consistency checks.

No compiler regression requiring a fix was found. Benchmark-driver corrections
covered fixture ownership boundaries, complete function inspection, actual
backend optimization flags, consistent process arguments, and timing controls.
No runtime safety or exception semantics were relaxed. This completes the
performance stage for maintainer review on the measured host. Example/project
adoption and the rest of Milestone 2 remain pending. No rebase, merge, branch
switch, worktree, or push was performed.
