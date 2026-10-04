<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M0 baseline qualification

## M0.1, increment 1, B0a, S0

Scope: freeze the original Java seed and all source inputs before any ordering
refactor. No production compiler, library, runtime, ownership proof, or launcher
default changes. Original implementation tooling uses MIT OR Apache-2.0;
no upstream implementation is copied. M0 has no prior milestone prerequisite.
The original plan audit revisions remain in the plan introduction.

The qualified development profile is this Apple M5, 32 GiB, macOS arm64 host.
Other development profiles require their own measurements before claiming
qualification. [identity.json](identity.json) records the exact host, OS limits,
source revision, per-input SHA-256, JDK executable/module hashes, LLVM tool
hashes, environment, commands, and seed hash. J0 is built from Git object
`6bde84df320e9dbc32977a2b665d214ac09bc2d4`, independently of mutable build output.
Its source-only standard library and runtime are from the same revision.

Reproduce from the repository root:

```sh
python3 scripts/self-hosting/freeze-j0.py \
  --jdk /Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home \
  --llvm /opt/homebrew/opt/llvm
```

An existing destination is rejected to preserve frozen evidence. Use `--output`
and `--report` for an independent reconstruction. The seed JAR is deterministic
with a fixed ZIP timestamp. The build uses Java 21, `--release 21`, UTF-8,
`-Xlint:all -Werror`, with no diagnostics. The recorded launcher reports
`0.6.1-beta`, LLVM and Clang 23.1.0. The installed `lib/ironwoodc.jar` prevents
automatic rebuilds in measured launcher runs. The qualification installation's
`conf/jvm.options` contains `-Xms256m -Xmx4096m -Xss8m -XX:+UseG1GC`, one option
per line. The shipped defaults remain untouched. Clear `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` for every qualification command.
[jvm-effective.txt.gz](jvm-effective.txt.gz) retains exact raw settings and final flags; effective
heap and thread stack match 4 GiB and 8192 KiB. OS main-thread stack is 8176 KiB.

Measurement contract, fixed before measurements and native evaluation:

- Each workload runs in a fresh JVM using the frozen installation and profile.
  Measure both frontend-only and complete semantic/IR/LLVM work. JVM startup is
  included in process wall time; adapter phase timing excludes startup.
- Sample used heap and the worker Java frame depth every 1 ms in the adapter.
  Sampled maxima are lower bounds. Frame depth is not stack bytes. Completion
  on deep workloads under `-Xss8m` is required. Stack byte high-water is not
  available from this JVM and is explicitly unmeasured.
- Use macOS `/usr/bin/time -l` for process peak RSS and fresh-process wall time.
  Adapter JVMs launch no child tools. Record native-link pipeline wall/RSS
  separately, with LLVM/Clang subprocess measurements, avoiding attributing
  child memory to the Java heap. Retain raw logs and sampling metadata.
- Use geometrically increasing source volume and independently increasing
  control-flow/type depth. Include real frontend sources, branch/loop ownership,
  generic inference, safe cleanup, and mandatory unsafe rejection.
- Choose numerical budgets after Java measurements and before any native pilot.
  No native pilot is built or evaluated during M0.

Pre-change consumer map: the adapter consumes lexer, parser, diagnostics,
compiler-owned typed IR and emitted LLVM through existing APIs; the inventory
reads production Java source using javac attribution. Neither modifies shared
analysis. Preserve list/map order and semantic IDs in comparisons; never sort
diagnostics or IR to conceal differences. Verification will include a changed
diagnostic, removed IR edge, and altered LLVM output, plus safe/unsafe ownership
fixtures. No hot lowering, ABI, packaging, public API or stdlib contract changes
are planned, so optimized-machine-code and native allocation checks apply to
later pilot phases rather than this measurement tooling.

M0.1 outcome: identity/profile freeze passed. M0.2 inventory and M0.3
comparison/resource qualification remain required before S0 exits.

## M0.2 discovery tooling increment, not the inventory exit gate

The attributed discovery scans all 460 frozen production Java files. It records
resolved overloads (including inherited declarations), source UTF-16 ranges,
field uses, excluded-syntax candidates, container declarations/references,
callback captures, and a conservative interprocedural flow graph. Record
accessors and constructor components have explicit edges. Local symbols carry
the declaration file and UTF-16 position so disjoint same-named locals remain
distinct. Views, factories/collectors, copies, helper parameters, returned values
and retained elements are discovery flows. They require source review; alias
edges do not establish ordering independence or reclamation safety.

Reproduction, with the same JVM profile and cleared Java option variables:

```sh
/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home/bin/javac \
  --release 21 -Xlint:all -Werror -d target/self-hosting-m0/tooling \
  scripts/self-hosting/Inventory.java
/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home/bin/java \
  -Xms256m -Xmx4096m -Xss8m -XX:+UseG1GC \
  -cp target/self-hosting-m0/tooling Inventory \
  target/self-hosting-m0/J0 docs/self-hosting/m0/inventory
python3 scripts/self-hosting/classify-inventory.py --archive
python3 scripts/self-hosting/test-inventory.py \
  --jdk /Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
```

Discovery TSVs use backslash escapes for tabs/newlines/backslashes, retain
original encounter order, and are versioned as deterministic gzip files.
The manifest records uncompressed SHA-256 and row counts. Decompress with
`gzip -dc`. Candidate dependency/ordering tables are explicitly unreviewed;
their counts cannot satisfy M0.2. Exact overload contracts, caller admission,
transitive traversal classifications, and first-consuming fixtures remain the
next work. The graph overapproximates copies and aggregate-return flows, merges
instances through shared fields, and does not prove virtual callback targets or
semantic ordering; review source rather than treating reachability as a proof.

Focused discovery qualification passed: distinct overloads, inherited members,
array length/construction distinctions, actual local/enclosing captures,
same-named sibling locals, and a hash source flowing through a helper, immutable
record, accessor, stream collector, view and list copy. Javac uses
`-Xlint:all -Werror`; no production analysis changes or native pilot are included.

M0.1 reconstruction correction: preserve Git executable modes for every copied
entrypoint, including `ironjar` and `irondoc`. An independent `J0-rebuild` made
with the corrected script reproduces seed SHA-256
`2a8b10ab5575f84d3660b3824a4b80cba7f6f9110023e7e30db3b41103893ad7`
and every source hash; all three launchers are executable. The original identity
report stays intact. [seed-build.txt](seed-build.txt) retains the successful
diagnostic-free build result.

The heap/frame sampler's own allocations and frame-snapshot work can affect
measurements. M0.3 must report sampling-on/off wall/RSS comparisons and sampled
versus unsampled limitations, rather than claiming instrumentation is free.

Raw JVM settings contain trailing spaces. Preserve the original bytes in
deterministic gzip rather than trimming the baseline; [jvm-effective.json](jvm-effective.json)
records their uncompressed SHA-256 and command. Read with
`gzip -dc docs/self-hosting/m0/jvm-effective.txt.gz`. Diff verification includes
the working tree, staged additions and the complete task diff from `6bde84df`.
