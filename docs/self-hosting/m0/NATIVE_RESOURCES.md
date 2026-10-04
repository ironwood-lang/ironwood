<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Seed compile/link and native child costs

The [archive](native-resources.tar.gz) and
[manifest](native-resources-manifest.json) preserve original-J0 and J0-D247
measurements separately, with exact tooling, identities, sources, argv, raw
output, executables, LLVM, child records and process samples. Final qualification
covers sixteen pipelines per seed, thirty-two total. All sixteen paired LLVM
hashes also match across seeds. Each pipeline compiles source into classes and
then links those classes using two fresh Java processes. The resulting executable
must exit zero. No native compiler pilot or translated compiler was measured.

## Procedure and boundaries

Run `python3 scripts/self-hosting/measure-native.py --output NEW_PATH` for the
qualified ordered seed. Add `--identity docs/self-hosting/m0/qualified/identity.json
--baseline-label original-J0` for original J0. Sources are the already frozen
BranchJoin, Volume256, Control128 and Ownership128 resource families. Each has
two fresh plain pipelines and two fresh profiled pipelines, serially. Java uses
the qualified 4 GiB heap/8 MiB stack profile with option variables cleared.
The runner verifies seed, standard-library archive, every copied runtime source
and each actual LLVM executable hash before launch. Explicit library/runtime
homes prevent checkout fallback. The selected SDK path/version is retained;
a supplementary post-run manifest records SDK settings/library stubs and Apple
linker content hashes. Original J0's identity pins SDK path/version, not those
supplementary content hashes. Runtime compilation occurs in every fresh link
process, so the process-local runtime cache does not remove these four costs.

The compiler's actual NativeBackend supplies all commands. Profiling inserts
wrappers at its explicit --llvm-home boundary; arguments and binary paths are
otherwise passed unchanged to the pinned LLVM 23 tools. Each final profiled
pipeline records exactly eleven LLVM/Clang calls:

1. llvm-config version discovery.
2. Clang target/data-layout discovery.
3. LLVM assembly of targeted program IR.
4. opt with default<O3>, inline threshold 1000 and partial inlining enabled.
5. Assembly after the seed's optimized trace-metadata injection.
6. llc PIC object generation at -O=3.
7. Clang -O3/PIC compilation of ironwood_runtime.c.
8. Clang -O3/PIC compilation of ironwood_case.c.
9. Clang -O3/PIC compilation of ironwood_tcp.c.
10. Clang -O3/PIC compilation of ironwood_host.c.
11. Clang C++-driver native linking with the Apple linker and dead stripping.

The seed's target application and trace injection remain inside JVM link work.
Apple discovery helpers are included in total wall time and process-tree samples,
but have no individual wrapper cost records. Clang linking includes its linker
descendants, so its cost is not an isolated Apple-linker measurement.

macOS /usr/bin/time -l -o writes timing separately from tool stderr. The wrapper
archives and forwards stdout/stderr bytes and exit status exactly, including
nonzero status. Child wall timing surrounds time launch/tool execution/output
capture; wrapper startup, input/output hashing and record serialization instead
contribute to total pipeline cost. Raw real/user/sys and maximum RSS are retained
for each child. Two controlled invocations use a timing-shaped stderr line,
no terminal newline and exit codes zero/seven; byte preservation and exact exit
propagation pass. No footer is inferred from or removed from tool diagnostics.

Compile and link wall times are retained separately. Total timing starts before
source compilation and ends after link completion, including intervening runner
bookkeeping and profiling. Executable validation, result hashing and archival
occur afterward. Plain pipelines omit wrappers and process sampling. The sampled
procedure requests a 10 ms pause and uses ps to record the root process and all
currently visible descendants, with raw PIDs/parents/RSS and sample times. The
sampler itself remains outside the measured process tree. Its scheduling/process
cost and wrappers' combined overhead appear in the plain/profiled comparison;
these contributions are not individually separated.

Process rusage RSS can include descendants and must not be described as isolated
JVM RSS for linking. Separate no-child JVM measurements remain in
[RESOURCE_MEASUREMENTS.md](RESOURCE_MEASUREMENTS.md). A sample sums concurrently
observed per-process RSS. Its maximum lower-bounds peak summed per-process RSS;
shared pages can be counted repeatedly, so it is not a bound on unique physical
memory. It is neither a heap measurement nor native live-object accounting.
Sampling-off zeros mean unmeasured.

## Qualification and observations

Both sixteen-pipeline qualifiers pass fresh/configuration LLVM byte comparisons,
eleven-child ordering/flags, four runtime compilations, and executable outcomes.
Controls reject a changed LLVM byte, missing child record and missing repeat.
All tool/source/result bytes have manifest hashes. The archive also preserves
two early setup failures and sixteen preliminary successful pipelines using an
earlier timing-footer adapter; those are not the final qualified cost baseline.
One setup failure used the copied corpus module's wrong base path; the other
was the CLI's explicit rejection of source arguments to --link. Current tooling
uses the designated repository root and the required compile-then-link flow.

| Baseline | Maximum plain total wall | Maximum profiled total wall | Maximum process rusage RSS | Maximum sampled concurrent RSS sum |
| --- | --- | --- | --- | --- |
| Original J0 | 4.473 s | 5.062 s | 433.891 MiB | 548.360 MiB |
| J0-D247 | 4.433 s | 5.167 s | 471.219 MiB | 585.985 MiB |

Maximum compile/link walls are 1.894/3.168 s for original J0 and 1.990/3.176 s
for J0-D247, across both configurations. Combined wrapper/sampler median wall
ratios span 1.123..1.162 and 1.081..1.157 respectively. Maximum observed sample
gaps are 45.467/49.595 ms despite a requested 10 ms pause. These are selected
observations, not performance or memory upper bounds.

Original/ordered maximum child wall observations, in milliseconds:

| Stage | Original J0 | J0-D247 |
| --- | --- | --- |
| llvm-config | 6.654 | 5.440 |
| Target Clang | 35.005 | 35.035 |
| First llvm-as | 30.490 | 28.788 |
| opt | 32.558 | 31.172 |
| Traced llvm-as | 18.552 | 18.030 |
| llc | 22.827 | 21.806 |
| Runtime Clang | 302.783 | 267.398 |
| Case Clang | 90.244 | 85.951 |
| TCP Clang | 75.973 | 86.854 |
| Host Clang | 79.883 | 79.017 |
| Link Clang and descendants | 63.546 | 67.060 |

Do not sum independent stage maxima as one observed pipeline. Largest child RSS
is approximately 97 MiB during runtime compilation. Exact values and all runs
are retained in qualification.json/measurement.json within the archive. Expanded
canonical references, explicit pilot closure, complete dependency classifications
and numerical budgets remain before M0/S0 exit.
