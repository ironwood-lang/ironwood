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

## Original reference preservation and neutral process comparison

[qualified/identity.json](qualified/identity.json) adds a reconstructed frozen
standard-library archive, JDK native-library and LLVM shared-library hashes, SDK
identity and the explicit library environment. It preserves the earlier identity
report. The new installation reproduces the same seed and strict-built library
archive as the preceding independent reconstruction. All copied library sources
are compiled with `--unfreed=error`; the qualification does not change the
production policy. Relative output/report arguments now resolve before changing
the build working directory.

The original library discovery searches parent directories and prefers archives
to sources. A source-only copy inside the checkout could therefore consume a
mutable parent build. The qualification installation has its own first-priority
archive. The test adapter additionally resolves every discovered library type,
requires its bytes to match a copied source hash and its origin to be that
archive. The fourteen `package-info.iron` documentation files have no class
payload and must resolve from the frozen source directory instead. Each capture
retains the resolved type/content-hash metadata separately from semantic output.
No production discovery behavior is changed.

The original captures are in [original-captures.tar.gz](original-captures.tar.gz)
with an exact-byte [manifest](original-captures-manifest.json) and
[classification](original-captures.json). The two initial fixtures used an
invalid entrypoint. Their source bytes and failed outputs are retained under
`*-debug` and excluded from accepted references. The maintained fixtures now use
`public static int main(String[] args)`. Their hashes are reconciled with every
diagnostic source identity and process record.

`BranchJoin` analyzes/compiles successfully without diagnostics, compiles and
links with explicit `--unfreed=warn --explain-rejected-free`, and its `-O3`
native executable exits zero. Compile/link/run argv and raw output are retained.
`SlotOrder` has exactly the intended mandatory array-alias safety error and
its selected ownership note, with zero missing-free warnings. Its invalid status
is a required negative outcome. Four fresh corrected processes per fixture,
including the independent final installation, match tokens, AST, diagnostics,
typed IR and LLVM. These original outputs precede any production ordering edit;
this fixture pair does not prove the complete ordering inventory.

The test-only `ReferenceCapture` adapter serializes typed records as explicit
node/field structures, numeric kinds with exact payloads (raw floating bits),
enum type/name, UTF-16 strings, optional presence, source basename/content hash,
and ordered arrays/maps. Records are reflected only inside this Java test
producer; a native producer can emit the same wire structures without sharing
Java objects. Maps retain encounter order as entry arrays. Semantic IDs,
diagnostic order and IR edges are never normalized or sorted. Unknown object
types fail capture. The selected single-source fixtures use basename plus
content hash; multi-source fixtures must establish distinct logical source
identities before admission to this protocol.

`compare.py run` invokes any producer using a JSON argv/environment specification
with `{input}`, `{output}` and `{explain}` placeholders, and retains its status,
source hash and raw process streams. It requires all nine structural outputs and
LLVM on a successful compile. `compare.py compare` compares those structures in
order and LLVM bytes. [qualified/producer.json](qualified/producer.json) is the
current frozen Java producer specification. It requires the test adapter compiled
against the frozen JAR; it is separate from the production launcher.

[comparison-qualification.json](comparison-qualification.json) records the
successful deliberate mismatch checks. `compare.py selftest` changes a real
negative diagnostic message, removes `falseTarget` from a real `IrBranch` in the
positive typed IR, and changes generated LLVM bytes. Each is detected in the
expected artifact, with its structural path. Reproduce after extracting the
reference archive into an ignored scratch directory:

```sh
python3 scripts/self-hosting/archive-evidence.py \
  docs/self-hosting/m0/original-captures.tar.gz \
  docs/self-hosting/m0/original-captures-manifest.json --verify
python3 scripts/self-hosting/compare.py selftest \
  target/self-hosting-m0/extracted/BranchJoin-final \
  target/self-hosting-m0/extracted/SlotOrder-final \
  target/self-hosting-m0/new-comparison-qualification
```

At the original-reference increment, the attributed inventory included compact record-constructor reassignment
flows and Java `var`/supported text-block sites separately from the synchronized
method. That hash-origin graph discovered 866 sources and 1,093 transitive
traversals in 127 files. These are review obligations, not completed proofs.
The archive manifest now includes previously compressed TSVs as well as newly
generated tables, preventing partial regeneration from dropping hash records.

Focused adapter, mismatch, native fixture, inventory and license checks pass.
M0.2 remains open for complete reviewed contracts and transitive ordering proofs;
M0.3 remains open for the full corpus, measured resource baselines/budgets and
the exact M1/M2 selection. No native pilot or M1 implementation has begun.

## Reviewed contracts and mutation-order discovery correction

[FRONTEND_CONTRACTS.md](FRONTEND_CONTRACTS.md) reviews every resolved external
member pattern in the lexer/parser/source packages, including exact overloads,
actual caller admission, equality/null/failure rules, owned versus borrowed
storage, selected replacements, B owner, phase, first consumer and required
fixtures. `review-frontend.py` joins all 60 patterns to their 689 actual use
sites in [frontend-reviewed.json.gz](frontend-reviewed.json.gz), checks frozen
source hashes and rejects missing/stale pattern reviews. The reached AST closure
and other packages remain separate obligations; a shared API id does not confer
this review on unrelated consumers.

[SNAPSHOT_CONTRACTS.md](SNAPSHOT_CONTRACTS.md) records the real ownership/effect
representation and retirement/equality distinctions selected for the M2 work.
Its E1-E7 proofs cover all 24 hash origins and 62 traversal occurrences in the
private RejectedFreeEvidence store. `review-evidence-order.py` requires the exact
original source hash and maps every selected traversal to its reviewed proof in
[evidence-order-reviewed.json.gz](evidence-order-reviewed.json.gz). It fails when
an unmatched site or changed selection requires re-review. No source proof here
claims that FunctionAnalyzer's upstream witness selection is order-independent.

`EvidenceOrderProbe` exercises the original frozen Java implementation, rather
than reproducing its algorithms in a toy map. It checks independent copies,
equal independent Site/span values, distinct equal-looking source/event/join
identities, value slot keys, common intersections, complete membership deletion,
shared payload cleanup, atomic snapshot-budget failure and idempotent close.
Adversarial keys force bucket collisions and resize thresholds at 8/32/128
entries; insertion and incoming path order are reversed. Four fresh processes
match. [evidence-probe-value-copies/qualification.json](evidence-probe-value-copies/qualification.json)
pins the original seed and qualification profile, probe hash, commands and raw
logs. This is focused ordering/contract evidence, not resource qualification;
GC/queue retirement timing is not forced or claimed. The earlier probe logs
remain under `evidence-probe`.

Discovery formerly treated bulk destination writes only as retained elements.
This missed encounter-order propagation into linked destinations and mutations
through aliases. The scanner now has explicit bulk order edges and conservative
bidirectional aggregate aliases for reads, initializers, assignments, choices,
casts, helper parameters/returns and record accessors. A focused fixture requires
hash order to reach linked putAll destinations, a returned parameter alias,
assigned fields and subsequent copied/view traversals. Its javac/graph checks
pass. This deliberately overapproximates independent copies and merged helper
instances; source proofs must identify actual consumers, rather than assuming
every graph edge is a runtime alias.

The corrected discovery graph has 866 origins and 2,957 traversal occurrences
in 233 files, replacing the earlier incomplete discovery selection. Full TSV
hashes/counts match the archive manifest. The earlier graph/tool versions remain
in the preceding local commit. Reviewed classifications remain separate from
the global unreviewed candidates. M0.2 stays open until the remaining exact
dependency and ordering consumers are reviewed; M0.3 resource/corpus/budget work
follows that gate. There are still no production compiler/library edits or M1
implementations in these increments.

## Reached AST dependency and helper qualification

[AST_CONTRACTS.md](AST_CONTRACTS.md) reviews all 60 exact external dependency
patterns and 386 attributed uses across the 91 frozen AST files. The joined
[ast-reviewed.json.gz](ast-reviewed.json.gz) includes each caller, exact
declaration, proof row and every source hash. `review-ast.py` rejects missing or
stale reviews, source hash changes and a newly discovered AST hash dependency.
The private PatternFlow map is lookup-only; first input binding and second input
conflict order are properties of list traversal, not map traversal.

The original helper probe passes 84 checks in four fresh qualified JVMs, with
`-Xlint:all -Werror` compilation and retained exact probe source/commands/logs in
[ast-probe-expanded](ast-probe-expanded/qualification.json). The 74-check initial
probe remains separately preserved. It exercises child snapshot independence,
null and constructor failures, value/identity distinctions, UTF-16 bounds,
primitive sum wrapping, qualified/dot-edge name rendering, locale differences,
first-binding conflicts, label/completion rules, field projections and declared
pre-order identities. It calls the actual frozen AST helpers, not a replacement
implementation. The English original profile and deliberate Turkish difference
remain explicit; no Java/native output normalization is introduced.

Reproduce the exact dependency join and a fresh helper qualification:

```sh
python3 scripts/self-hosting/review-ast.py
python3 scripts/self-hosting/qualify-evidence.py --probe ast \
  --output target/self-hosting-m0/new-ast-qualification
```

This increment completes that package's external-use review, not the full M0.2
gate. AST variant selection/coverage, global excluded syntax/captures/worklists,
remaining overload and ordering proofs, full corpus/resources/budgets remain
open. M1 and native pilot work have not begun.

## Reviewed worklist and lexical-stack storage

[WORKLIST_CONTRACTS.md](WORKLIST_CONTRACTS.md) covers all 44 ArrayDeque origins,
FunctionAnalyzer's borrowed restoreDeque parameter, 325 attributed references,
and 19 exact deque declarations used at 256 sites. The joined
[worklists-reviewed.json.gz](worklists-reviewed.json.gz) preserves source hashes,
origin declarations, every reference, exact member uses and reviewed contract
rows. `review-worklists.py` rejects uncovered/stale origins, changed source hashes
and new exact deque dependencies.

The review distinguishes FIFO append/take from scope-stack push/pop and
innermost-first traversal. It records coupled queue priorities, stateful first
visits, bounded candidate prefixes, first proof failures and stack copy/restore
ordering. A tail-backed stack must reverse its traversal and saved-list restore
relative to storage; a FIFO must not shift an ArrayList on each dequeue. The
private native representation and its allocation/cleanup qualification remain
M1/M3 work, with one selected ClosedWorldEffectAnalyzer FIFO needed by the M2
workload. No general Deque API or worklist implementation is added here.

Inherited collection operations and passed/snapshotted stacks are included as
source references; the exact declaration count above is restricted to members
declared by ArrayDeque/Deque. Global hash-origin order entering a queue or scope
map is a separate still-open proof obligation. Removing queued membership never
permits freeing a potentially shared record, state or context without its own
reclamation proof. Required per-consumer fixtures are specified, not claimed
passing by this documentation increment.

Reproduce with `python3 scripts/self-hosting/review-worklists.py`. Focused
consistency checks pass, including rejection after omitting Q20, omitting the
Deque.removeFirst member review or changing the reviewed origin line. Diff and
license checks pass; no compiler behavior
changes require a compiler suite. M0.2 remains open for global syntax/captures,
other overloads and transitive ordering, followed by the full M0.3 resource gate.

## Syntax discovery label correction

Java `var` is excluded by Feature 88; the discovery's earlier context label
incorrectly called it supported. Enhanced-for iteration declarations also have
no initializer in javac's tree, but are supported loop bindings, rather than
Java's excluded optional local initializers. Inventory now distinguishes those
cases. All 12,413 syntax sites remain, including 1,753 excluded var declarations,
118 excluded uninitialized locals and 1,141 enhanced-for bindings. The 1,941
type patterns split into 1,405 supported INSTANCE_OF contexts and 536 excluded
PATTERN_CASE_LABEL contexts. Text blocks remain supported syntax; their called
normalization APIs have separate contracts.

The focused fixture now requires an assigned-after-branch local, an enhanced-for
binding, Java var, a text block and the synchronized method to remain distinct.
Its attribution/flow checks pass. A fresh qualified 460-source attribution run
exits zero without javac diagnostics. Only syntax.tsv.gz is replaced; all other
versioned TSV payloads retain their previous bytes/hashes. Fresh non-syntax data
agrees, except javac's ephemeral wildcard capture numbers in calls.tsv's
instantiated_type and captures.tsv's retention_context fields; syntax.context
has the same incidental naming difference in addition to the corrected labels.
Those discovery-only names are not semantic compiler
identities; original attributed tables stay intact. No diagnostics, AST, typed
IR or LLVM are normalized. [syntax-discovery-correction.json](syntax-discovery-correction.json)
records exact old/new hashes, row counts, changed-field limits and raw process
streams. All eleven archive manifest entries verify. This fixes discovery
labels; full excluded-syntax and callback contract review remains required.
