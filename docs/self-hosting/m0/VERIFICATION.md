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
[jvm-effective.txt](jvm-effective.txt) retains settings and final flags; effective
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
