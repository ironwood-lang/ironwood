<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M4 pre-change review

Entry: G1 passed at adfa0515194209c1cd4fb2766f3097ea79f51573 and M3 is complete
at bdf34a55a1981aa36297a2b6fcff720bc9cf159c. M4 delivers B3's filesystem
operations and publication guarantees (M4.1, M4.2) and B4's synchronous
process facility with the compiler's discovery adapters (M4.3). It prepares
the native driver route and M5 publication; it does not port S4's
NativeBackend, LlvmToolchain or MacNativeTools, and the shell-driver route of
D269 stays available. M5, M6 and every S2+ stage are out of scope. All work
and commits are local on `before-self-hosting`, without remote operations.

## Scope and first consumers

The M0 source-backed inventory (`m0/deferred/calls.json.gz`) assigns 361 calls
in 95 patterns to `M4.1-M4.2 before M4.3` and 35 calls in 11 patterns to M4.3
(including `M4.3 before M3.3` and `M4.3 before M6.1 before M6.2`). Their
consumers are the native driver's NativeBackend, LlvmToolchain,
MacNativeTools, ToolchainDiscovery and TlsDependency, plus the Bridge build
tools whose S7 calls name the same process contract. M3.3 recorded these
further B rows for M4: a link's temporary LLVM file and its deletion
(`Main.createTemporaryLlvm`, `Files.deleteIfExists`), the optimization-report
alias check (`Main.resolvedOutputPath`, `Path.toRealPath`, `Files.isSameFile`),
and Linux Bridge support delivery (`BridgeNativeSupport.deliver`: temporary
directory, copy, no-replace move, post-order deletion). Each M4 phase
classifies every one of its patterns, as M3 did, into an existing Ironwood
API (A), a recorded port convention (B) or a delivered helper (D).

| Phase | Delivery | First consumers |
| --- | --- | --- |
| M4.1 | `Files.deleteIfExists`, `createTempFile` and `createTempDirectory` (explicit and default directory), `Path.toRealPath`, `Files.isReadable`, `isExecutable`, `readAttributesNoFollow`; the post-order tree deletion helper replacing reverse-sorted `Files.walk` cleanup | Main's temporary LLVM file and alias check, NativeBackend's staging directory and cleanup, LlvmToolchain and MacNativeTools access checks and real paths, Bridge support staging |
| M4.2 | Three distinctly named publication moves: atomic replacement, replacement with a permitted cross-file-system fallback, and atomic no-replace | IronJar.write (M5), BridgeJarArchive.publish and Bridge distribution and support staging (M6/S7) |
| M4.3 | `ironwood.process.ProcessRunner.runToFile` and `ProcessResult`; `/usr/bin/xcrun` and absolute Homebrew resolution in the Java seed; the port's probe adapter, invocation scratch, discovery reuse, executable search and LLVM pipeline adapter | NativeBackend.run, LlvmToolchain.run and discovery, MacNativeTools.run, TlsDependency.command |

## Contracts to preserve

- B3: reuse the existing `Files` surface; no stream-returning `walk`/`list`
  (D122 keeps `Files.list` absent); callback Paths and attributes stay
  borrowed; directory-stream entries are fresh and caller-owned; close the
  stream before freeing its wrapper; exclusive creation, never
  `exists`-then-create; `free path` never deletes the entry.
- STDLIB_ROADMAP: temporary files need secure randomness and documented
  permissions; no invented booleans for Java option enums; publish only
  overloads whose Java default behavior is unambiguous.
- B4: absolute executable in `command[0]`, no PATH search and no shell,
  inherited environment, optional child directory, noninteractive stdin,
  merged output to a file, synchronous wait that always reaps, launch failure
  distinct from a child's exit status 127, borrowed inputs and no retained
  handle.
- Existing Files contracts: audited borrowing of every facade parameter,
  fresh results classified separately from returned input aliases, typed
  `IrFileInstruction` lowering with throwing and nonthrowing forms, error
  categories for absence, permission, existence and non-empty directories,
  and the separate no-replace `Files.move` (unchanged here).
- D132/D133 and mandatory memory safety in every unfreed mode; D261/D262 port
  forms (creation-array owners, no service getters, invocation-lived shared
  graphs, exhaustive variant switches, fail-closed admission).

## Material choices

Each choice records the options and the plan's recommended option, which M4
adopts.

**Temporary names, randomness and permissions.** Options: (a) libc
`mkstemps`/`mkdtemp`; (b) names from a clock-derived generator; (c) native
names from a secure random source with exclusive creation. BSD `mkstemps`
replaces every trailing `X`, so it would alter a prefix ending in `X`, and the
roadmap requires secure randomness, which (b) lacks. Choose (c): the name is
the prefix, an unsigned decimal 64-bit value and the suffix, as Java's
TempFileHelper spells it; the bits come from `arc4random_buf` on macOS and
from the raw `getrandom` syscall on Linux (guarded syscall numbers; glibc 2.17
has no wrapper), falling back to `/dev/urandom` only when the kernel lacks the
syscall. Without a secure source the call fails; it never uses weaker bits.
Creation is `open(O_CREAT | O_EXCL | O_CLOEXEC)` with mode 0600 or
`mkdir` with mode 0700 (Java's defaults, before the umask), retried on an
existing name a bounded number of times. The managed UnixPath is allocated
before the entry exists and adopts the created String inside its constructor,
so no managed allocation follows creation; a failed String allocation after
native creation removes the entry first. Prefix null means empty, a file
suffix null means `.tmp`; NUL fails with InvalidPathException and a name that
would have a parent fails with IllegalArgumentException, as Java decides both
from the generated name. The default-directory overloads read
`java.io.tmpdir` (nonempty `TMPDIR`, else `/tmp`) and delegate, so an unusable
nonempty `TMPDIR` fails with no second fallback. Attribute-varargs overloads
are omitted.

**Real paths.** `Path.toRealPath()` resolves the path's own spelling with
`realpath(3)` into a stack buffer, `.` standing for the empty path as Java's
`toAbsolutePath` does; it never normalizes lexically first. The result must
be valid UTF-8; otherwise the call fails like a directory entry would. The
UnixPath adopts the native String inside its constructor. The LinkOption
overload is omitted (no option enum).

**Access, deletion and no-follow attributes.** `isReadable` and
`isExecutable` use `access(2)` and return false on every failure, as Java's
advisory checks do. `deleteIfExists` reuses the existing typed delete and
returns false only for absence. `readAttributesNoFollow` is an Ironwood helper
over the existing no-follow attribute read.

**Publication policies.** Options for no-replace: the existing `Files.move`
(an `lstat` check followed by `rename`, racy under a competing creator, as
Java's own default move is), `link` plus `unlink` (files only, so it cannot
publish Bridge's staged directories), or an exclusive rename. Choose the
exclusive rename behind a distinctly named helper and leave `Files.move`
unchanged: `renamex_np(RENAME_EXCL)` on macOS (10.12+, within the 11.0
baseline) and the raw `renameat2` syscall with `RENAME_NOREPLACE` on Linux
(kernel 3.15+ and filesystem support; the glibc 2.28 wrapper is excluded by
the 2.17 baseline). An existing target fails with FileAlreadyExistsException;
an unsupported kernel or filesystem, or a cross-device move, fails with
AtomicMoveNotSupportedException; there is never a check-then-rename fallback.
Linux reports an unsupported flag and a directory moved into itself both as
EINVAL, so a failed call classifies EINVAL by comparing real paths, which has
no effect on success. Atomic replacement is `rename(2)`; a cross-device move
fails with AtomicMoveNotSupportedException and changes nothing. The permitted
fallback first tries the same rename; only for a regular file across file
systems does it copy into an exclusively created temporary beside the target
(mode and times preserved), rename that over the target and then unlink the
source, so any failure before that rename leaves the earlier target and the
source intact. If the source cannot be removed after the target was replaced,
the call reports that state distinctly and does not roll the target back.
Atomic visibility is not a durability claim; no fsync policy is added.

**Process facility.** Options: `posix_spawn` (macOS has spawn-time `chdir`
from 10.15 and close-on-exec-by-default; glibc 2.17 has no spawn-time
`chdir`) or `fork`/`execv` on both hosts. Choose one `fork`/`execv`
implementation so both hosts share a code path: argument storage is encoded
before forking, the child only redirects descriptors, changes directory and
executes, and pre-exec failures reach the parent through a close-on-exec pipe.
Internal descriptors are close-on-exec; descriptors the compiler itself
inherited without close-on-exec still pass to children, as with
`posix_spawn`'s default (recorded boundary). The child stays in the
compiler's process group, so terminal interruption reaches both; waits retry
EINTR; a signal sent to the compiler alone does not stop a running tool
(recorded boundary; no global signal machinery). `ProcessResult` holds only
primitives: Java's `exitValue` encoding (exit status, or 128 plus the signal),
whether a signal ended the child, and which. Arguments use the runtime's
existing host encoding (UTF-8, U+FFFD for an unpaired surrogate). The
facility is a new typed `IrProcessInstruction`, not a general native
declaration.

**Discovery adapters.** TlsDependency launches `/usr/bin/xcrun` when
`SDKROOT` is unset or blank, like MacNativeTools. LlvmToolchain resolves
`brew` through a compiler-owned PATH search (empty entries skipped, relative
entries resolved against the working directory, first executable regular
file wins) before launching it; a missing `brew` keeps discovery optional.
The port's probe adapter writes each uncached probe to a fresh log inside one
lazily created invocation scratch directory, reads back at most a fixed limit
(exceeding it is an explicit probe failure, never a truncated success),
deletes the log on every path and removes the directory at invocation end.
Successful probe results are reused within one invocation, keyed by the full
command, working directory and the `PATH`, `SDKROOT` and `DEVELOPER_DIR`
values; failures are never reused.

**Traversal rewrites.** Reverse-sorted `Files.walk` cleanup becomes a
no-follow `walkFileTree` that deletes files on visit and directories in
`postVisitDirectory`, with a best-effort policy (NativeBackend) or a
propagating one (Bridge staging); only child-before-parent order is
observable there, and the root goes last. The remaining `walk`/`list`
consumers belong to M5 and M6 and follow B3's table.

## Shared machinery and safety

| Changed machinery | Consumers to protect | Paired evidence |
| --- | --- | --- |
| New `IrFileInstruction` operations (temporary creation, real path, access, three moves) | FunctionAnalyzer binding, TypeDependencyScanner intrinsic names, ClosedWorldPruner/EffectAnalyzer, BorrowDispatchAnalysis, specializer and CFG renamer, invoke eligibility and LLVM emission | Typed-IR and runtime-boundary checks; allocation failure reachable after pruning; existing U2/U5 tests unchanged |
| Files borrowing classification and fresh results | `isBorrowingFilesFacade`, `applyAuditedBorrowingContract`, `isFreshFileResult`/`isFreshPathResult` | A temporary input Path/String freed after each call; fresh Paths and attributes freed by the caller; retaining or double-freeing them rejected; moves return the target alias, not a fresh Path |
| New `IrProcessInstruction` | Every IR consumer above, closed-world effects (a launch is never pure), invoke and emission | Borrowed command arrays and Paths freed after the call; the result freed; launch failures and OOM unwind |
| Runtime ABI (`ironwood_runtime.c`, header) | glibc 2.17 and macOS 11.0 packaging targets | Native harness with injected failures; symbol inspection showing no `renameat2@GLIBC_2.28` or newer dependency |

No existing lowering, analysis rule or hot path changes; a program that does
not call the new members keeps identical function bodies once closed-world
numbering is normalized. Every new public member gets IronDocs and a
behavioral contract review. All sources are original under the default
license; no OpenJDK source is consulted or translated.

## Focused verification selection

New registrations, each pairing normal results with allocation, IO, aliasing
and resource-failure cases: typed IR and runtime boundaries for each new
operation; native behavior compared with Java 21 where Java-compatible
(deletion, temporary-name grammar and failures, real paths, access checks);
`TMPDIR` unset, empty, nonempty and unusable; collisions, Unicode and spaces,
symbolic and dangling links, inaccessible parents; publication races with
competing file and directory creators, same-file, directory and symlink
targets, cross-device fallback and injected native failures; ownership in
every unfreed mode; allocation-failure sweeps; the traversal helper against
Java's reverse-sorted walk; the process runner's controlled helpers (exit
codes, signals, cwd, environment, literal argv, large output, descriptors,
missing and non-executable tools, ENOEXEC, status 127, interrupted waits,
repeated calls without leaked descriptors or children, conflicting PATH);
Homebrew/TLS discovery; probe log cleanup, reuse and measurements; and the
real `llvm-as` -> `opt` -> `llc` plus Clang pipeline.

Existing consumers to rerun: `U2 path and whole-file operations use typed IR
and audited ownership`, `U2 paths and whole-file I/O run at O3`, `U2 file and
path allocation failures roll back at O3`, `U5 directory foundation uses
typed native operations`, `U5 file tree traversal enforces borrowed visitor
callbacks`, `U5 directory foundation enumerates entries and reads attributes`,
`U5 file tree traversal controls depth links and cleanup`, `filesystem
mutation and random access helpers run at O3`, `native filesystem scratch and
resource cleanup survive injected failures`, `path operations allocate only
their owned result and match Java 21`, `caller-owned library results survive
source class archive and tree-shaking round trips`, `IronDocs comments, CLI,
links, and reproducible library documentation`, and `Clang version reporting
preserves vendor identity and diagnoses query failures` with the TLS and
toolchain discovery tests for the Java seed change.

Hosts: this macOS arm64 machine qualifies every phase. Linux x86-64 runtime
and process evidence runs on the `estonia` host (kernel 4.15, glibc 2.27
host, glibc 2.17 sysroot toolchain); Linux arm64 is recorded as unresolved.
Run `git diff --check` for every change and `./scripts/check-licenses.sh` for
source changes. Never run an unfiltered suite. Unsafe programs are
compile-only.

Status: initial review recorded before implementation. Each increment below
adds the review made as its phase advances.

## M4.1 increment (D270)

Existing limits found while writing the fixtures: `new StringBuilder(String)`
cannot be freed (its constructor calls an unaudited append), a caught
exception cannot be freed, and Ironwood's SimpleFileVisitor lacks Java's
`throws IOException` on `visitFile` and `preVisitDirectory`; the fixtures and
TreeDeletion work within those limits, which are recorded rather than
changed. The temporary-file design changed once: `mkstemps` would have
altered a prefix ending in `X` on macOS, so the runtime generates names from a
secure source itself. No analysis rule changed; the new operations needed
only fresh-result and borrowing entries. Two programs that call none of the
new members keep identical function bodies once closed-world and debug
numbering is normalized.

## M4.2 increment (D271)

The macOS `renamex_np(RENAME_EXCL)` accepts a name renamed to itself while
Linux reports EEXIST, so a successful exclusive rename whose source still
names the target's file reports an existing target on both hosts. The first
Linux build showed glibc hides `realpath` at the runtime's strict feature
level, like `syscall`; both are declared explicitly. No real file system
without exclusive rename was available, so that path is qualified by
injection. No analysis rule changed.

## M4.3 increment (D272, D273)

Two shared-analysis changes were needed, each with paired regressions. (1)
Any analyzed call that receives a `String[]` exposes its elements, so every
String placed in a command would have been unreclaimable; `runToFile` gains
an exact-signature contract that borrows the elements (String is final, and
the launch encodes and retains nothing), and an ordinary `String[]` method
keeps the rule. (2) The creation-array proof rejected passing an owner's
storage to any call; it now admits exactly `runToFile`'s command argument, and
`String.join` stays rejected. Probe recording first kept separate key and
output strings, which an allocation failure between them could strand; each
probe is now one ProbeRecord whose constructor owns both. A probe directory
removed by a tree walk allocated during OOM unwinding, which the runtime
treats as fatal; every log is deleted on its own path, so the empty scratch
and staging directories are removed without allocating. On Linux the test
JVM ran under `nohup ... &` and so ignored SIGINT, which its children
inherit; the process-group helper resets the interrupt dispositions as a
shell's foreground job does.

## Linux arm64 qualification

The boundary recorded above as unresolved closed on the `miami` guest
(Ubuntu 22.04 aarch64): the twelve M4.1/M4.2 and fifteen M4.3 tests and the
glibc 2.17 symbol check pass ([run](arm64-evidence/manifest.json)). The
first attempt stopped in `scripts/jdk.sh`: that guest's kernel reports SVE2
without SVE, HotSpot warns on stderr at every start, and the javac version
check compared the merged output exactly. The check now compares javac's own
line, and the discovery test's Java reference keeps only stdout. No runtime,
library or analysis code changed.
