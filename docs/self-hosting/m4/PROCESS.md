<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M4.3 synchronous launch and discovery adapters

Status: passes
([D272](../../DECISIONS.md#d272---run-external-programs-synchronously-by-absolute-path),
[D273](../../DECISIONS.md#d273---drive-native-tools-through-invocation-scoped-port-adapters)).
`ironwood.process.ProcessRunner.runToFile` runs a program by absolute path
with inherited environment, an optional child directory and merged file
output, and returns a primitive-only `ProcessResult`; the Java seed's two
bare launches are gone; and the compiler port's adapters (Command, Probes,
ExecutableSearch, LlvmPipeline) drive discovery and the actual LLVM pipeline
through it. All sources are original and use the default license.

## Launch design

The pre-change review compared `posix_spawn` with `fork`/`execv` against the
baselines: macOS 11.0 has spawn-time `chdir` (10.15+) and
close-on-exec-by-default, but glibc 2.17 has no spawn-time `chdir`, so one
`fork`/`execv` path serves both hosts. The runtime encodes the command,
directory and output spellings into one block before forking (an allocation
failure is raised before any descriptor exists); the parent opens the output
(so a relative output is the caller's) and `/dev/null`, keeps both and the
report pipe close-on-exec and above descriptor 2; the child only calls `dup2`,
`chdir`, `execv`, `write` and `_exit`; a pre-exec failure reaches the parent
as a stage and errno through the pipe, so a child's own exit status 127 is a
result; the parent retries EINTR on the pipe and in `waitpid` and always
reaps. Stage and reason map to NoSuchFileException, AccessDeniedException or
a FileSystemException naming the executable, directory or output.

## Behavioral contract review

1. **Overloads and arguments.** One distinctly named static method; Java's
   ProcessBuilder and Process stay absent, so no Java call binds it. A null
   directory selects the caller's directory; null command, element or output
   throws NullPointerException.
2. **Native-model constraints.** Closed-world native launch with no JVM
   machinery; executable discovery belongs to callers (B4), so no PATH search.
3. **Reduced surface.** No environment map, pipes, asynchronous wait,
   timeout or kill; the reduced ProcessBuilder/Process remains roadmap item 4.
4. **Comparison with Java.** The exit value follows Java's POSIX convention
   (128 plus the signal); arguments use U+FFFD for an unpaired surrogate where
   Java uses `?`; inherited descriptors without close-on-exec reach the
   program where Java's launcher closes them. These are recorded in
   DIFFERENCES_FROM_JAVA.md.
5. **Provenance.** Independent implementation.

## Caller adaptations

The launch audit's two bare launches are gone from the Java seed: TlsDependency
runs `/usr/bin/xcrun` (MacNativeTools' fixed choice, now shared as
`MacNativeTools.XCRUN`) and LlvmToolchain launches the `brew` that the new
compiler-owned `ExecutableSearch` resolves. The search tries PATH entries in
order, skips empty entries rather than searching the working directory,
resolves a relative entry against the working directory without normalizing
it (so `link/../bin` follows the link, as exec would) and takes the first
executable regular file. PATH-selected `xcrun` substitutes are no longer
selected; Homebrew remains optional. LLVM/JDK selection and the fixed Apple
tool paths stay caller policy.

## Compiler integration

`IrProcessInstruction` is bound only to the exact `runProcessValue` intrinsic
and joins every IR consumer: closed-world effects (an unused result still
launches), allocation-failure reachability, borrow dispatch (a primitive
result), primitive specialization, CFG renaming, invoke eligibility and LLVM
emission (`invoke i64 @ironwood_process_run`). `runToFile` has an audited
borrowing contract for its parameters and, by exact signature, for the
command's elements: an analyzed method receiving a `String[]` otherwise
exposes the elements, which would make every String placed in a command
unreclaimable. String is final and the launch retains nothing, so the
contract is sound; an ordinary method taking `String[]` keeps the
conservative rule.

## Results

| Check | Result |
| --- | --- |
| Controlled programs | Twenty-eight cases from class and archive links against `process_helper.c` with a PATH of empty entries and decoys: exit statuses 0, 3 and a child's 127; signals 15 and 9 (exit values 143 and 137); spaces, quotes, `$`, `*`, `;|&`, a backslash, an empty argument, an emoji and an unpaired surrogate passed literally; NUL rejected; an inherited variable; child and inherited directories; output relative to the caller; empty stdin; 2 MiB of interleaved output; only descriptors 0-2 in the child while the caller holds a file stream and a directory stream; missing, non-executable, directory and unknown-format executables; a shell script; missing and non-directory directories; missing and directory outputs; empty and relative commands; 300 repeated launches with a stable descriptor count. No decoy ran. |
| Native injection | `process_services.c`: failures opening the output and stdin, creating the pipe and forking close what was opened and start nothing; an encoding allocation failure is raised before any descriptor; exec and directory failures arrive through the pipe and the child is reaped; a real SIGALRM every 20 ms interrupts the wait, which resumes; 200 launches leave descriptors and children unchanged; a descriptor inherited without close-on-exec reaches the program (the recorded boundary). |
| Signals | With the program leading a job's process group, SIGINT to the group ends the program (exit value 130) and its tool; SIGTERM to the program alone ends it (143) and leaves the tool running, the recorded boundary. |
| Ownership | In every unfreed mode the command array, a Path spelling and a fresh String in the command and both Paths are freed after the call and the result once; double free, use after free, freeing an element while the array is live, freeing an element of a published array and an ordinary `String[]` method stay rejected. |
| Allocation failure | Every limit from 0 to 6 around a launch and its read-back unwinds to the baseline and leaves no output file; limit 7 succeeds. |
| Typed IR | One process instruction remains for an unused result; a destructor may not launch (allocation effect). |
| Caller adaptations | ExecutableSearch takes the first executable regular file over a non-executable file and a directory, skips empty entries even when the working directory holds the tool, resolves relative entries and keeps `link/../bin` unnormalized. A child JVM with PATH `::decoy:fake:/usr/bin:/bin` launches `fake/brew` by absolute path and returns its prefix, and finds none without brew; TLS and Apple discovery under a PATH offering a decoy `xcrun` return `/usr/bin/xcrun`'s SDK and never run the decoy. |

## Port adapters (D273)

| Java demand | Native treatment | First consumers |
| --- | --- | --- |
| `new ProcessBuilder(List<String>)` with a command built from Paths and literals | `Command`: a creation array of exact length, each argument a fresh copy, lent to `runToFile` | NativeBackend.run, LlvmToolchain.run, MacNativeTools.run, TlsDependency.command (S4), BridgeBuildTools.run (S7) |
| `readAllBytes` of a probe's output, `new String(..., UTF_8)`, `strip()` | `Probes.output`: a fresh log per uncached probe under one lazily created scratch directory, read back within 1 MiB, decoded with replacement, stripped and deleted on every path | LlvmToolchain version and Clang probes, MacNativeTools SDK, linker and version probes, TlsDependency's SDK query |
| Repeated discovery within one compiler run | `Probes` reuses a successful answer for the same command, PATH, SDKROOT and DEVELOPER_DIR; failures are never reused; a new invocation probes again | the same |
| `brew` and `llvm-config` found on PATH | `ExecutableSearch.find`, equal to the Java seed's | LlvmToolchain.discoverHomebrewPrefix and locateOnPath (S4) |
| NativeBackend.linkImage's stage commands, temporary directory and `deleteTree` | `LlvmPipeline.link` with stage logs and allocation-free staged cleanup | the native driver (S4 optional route) |

The creation-array proof (D163) admits exactly one consumer of an owner's
storage, `ProcessRunner.runToFile`, because D272's audited contract borrows
the array and its Strings for the call only. A probe is one `ProbeRecord`
whose constructor runs it and owns its key and output, so an allocation
failure after the launch rolls back with the record; a failed record is kept
but never reused. Trace finalization between `opt` and the second `llvm-as`
is S4's native mode; the pipeline takes it as an executable, which the test
supplies as the Java baseline's own `OptimizedTraceMetadata.inject`.

## Adapter results

| Check | Result |
| --- | --- |
| Ownership | In every unfreed mode a caller frees its own Strings after adding them to a Command; freeing a lent argument, using a Command after free, freeing a probe output and using it after its context is freed are rejected; an owner passing its creation array to `String.join` stays rejected. |
| Port search | On one tree the port's `ExecutableSearch` prints the Java seed's eight answers (candidate order, empty entries with a tool in the working directory, relative and `link/../bin` entries, an empty PATH, missing and separator names) from class and archive links. |
| Discovery probes | The six macOS probes (two on Linux) answer as LlvmToolchain and MacNativeTools: LLVM 23.1.0, the Clang version line, the SDK, the linker and their versions. A repeated pass launches nothing; a new invocation probes again; a missing tool, a failing probe run twice (two launches) and 1.2 MB of output (an explicit failure) leave every log deleted and the scratch directory removed. |
| Actual pipeline | The Java compiler's emitted module of a program that prints a computed line and a caught exception's trace is linked by the port's pipeline, from class and archive links, into an executable whose exit status, output and resolved stack trace equal the Java link's; no output staging or probe scratch remains. |
| Pipeline failures | A malformed module (`LLVM IR assembly failed with exit code 1:` and llvm-as's diagnostic), a missing LLVM home (`cannot run native target discovery: /no/llvm/bin/clang`) and a failing finalizer (`stack-trace metadata finalization failed with exit code 1:` and its output) produce no executable and leave no staging. |
| Allocation failure | Every limit from 0 to 63 across a Command, two probes (one reused) and a search unwinds to the baseline with no log or scratch directory; limit 64 succeeds. |

## Measurements

Discovery on this macOS arm64 host (seven serial runs, load below 3, LLVM
from Homebrew, Xcode SDK), from `compiler_discovery_probes.iron`:

| Pass | Launches | Logs created/deleted | Bytes written and read | Elapsed |
| --- | --- | --- | --- | --- |
| Cold, six probes | 6 | 6/6 | 771 | median 43.6 ms (42.6 to 49.8) |
| Repeated in the same invocation | 0 | 0/0 | 0 | median 37 µs |
| New invocation | 6 | 6/6 | 771 | as cold |

The context retains 27 live allocations after the cold pass (six records with
their keys and outputs, the arrays and Paths) and 5,097 UTF-16 units of keys
and outputs, mostly the PATH value held in each key. File output adds one
exclusive log creation, one read-back and one deletion per uncached probe;
pipe capture would have needed neither but risks pipe-capacity stalls and
unbounded buffering. Distinct probes still need their own capture.

## Hosts

| Host | Result |
| --- | --- |
| macOS 27.0.1 arm64 | all fifteen M4.3 tests |
| Linux x86-64 (`x86host`, kernel 4.15, glibc 2.27) | all fifteen M4.3 tests, including the process-group interrupt, the actual pipeline with `llvm-objcopy` and the conda LLVM 23.1.0 toolchain; the Apple discovery test checks nothing there |
| Linux arm64 (`armvm`, Ubuntu 22.04 guest, kernel 5.15, glibc 2.35) | all fifteen M4.3 tests with the IDK's conda LLVM 23.1.0, including the process-group interrupt and the actual pipeline ([run](arm64-evidence/manifest.json)) |

## Boundaries

- Descriptors the compiler inherited without close-on-exec reach its tools.
- A signal sent to the compiler alone, or an uncatchable termination, leaves
  a running tool; a terminal interrupt reaches both through the process group.
  A job started with interrupts ignored passes that disposition to its tools,
  as Unix does.
- An allocation failure inside the pipeline's own cleanup ends the process
  through the runtime's emergency path and leaves its staging directory.
- Trace finalization is an external step until S4's native mode.
- The shell-driver route of D269 remains available; these adapters are
  prerequisites for S4's NativeBackend, LlvmToolchain and MacNativeTools
  ports and S7's BridgeBuildTools, not those ports.
