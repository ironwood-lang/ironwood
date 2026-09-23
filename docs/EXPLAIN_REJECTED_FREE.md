<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explain rejected free: implementation plan

Status: Planned, not implemented. The maintainer requested this design document
after selecting the opt-in name `--explain-rejected-free`. The command examples,
additional notes, and milestones below describe proposed behavior. They do not
claim that the current compiler accepts the option. Creating this plan does not
start implementation or change the memory model.

Original code review baseline: `dfd3c9be55bec9763bd3dcc71f640c764b56c276`.
Review found unstable primary diagnostic selection at this baseline; section 3.2
defines a separate stabilization prerequisite before explanation-mode parity.

## 1. Purpose and design constraints

Help a developer understand why the compiler rejected an explicit reclamation:
which reference still observes the allocation, which operation published it,
which cleanup still needs it, or where ownership information became uncertain.
The explanation should help distinguish a program ownership problem from a
conservative analysis boundary without claiming to decide which one it is.

Ironwood keeps `free`, `defer`, fixed ownership, and mandatory compile-time
reclamation proofs. This feature adds diagnostic evidence, not an ownership
transfer mechanism, automatic reclamation, a collector, or a safety override.
The word "rejected" is intentional: inability to prove safety is not proof that
every execution would be unsafe.

The governing constraints are:

1. Normal compilation retains its current concise diagnostics and does not
   collect detailed explanation history. Enabling the option must enable
   tracking, not merely reveal information always collected in the background.
2. The same input and existing options must produce the same safety decisions,
   primary diagnostics, and generated program with the option on or off.
3. Additional evidence is compiler-only. It introduces no runtime instructions,
   object fields, LLVM metadata, or class/archive format changes.
4. Every claimed source relationship must follow the actual analysis. Do not
   reconstruct a plausible story by searching for a similarly named variable.
5. Keep evidence bounded, deterministic, and useful. Explain the selected
   rejection, not every possible execution path or every compiler operation.

Relevant contracts are D005/D027, D083, D132/D133, D140/D145, D168, and D169 in
[DECISIONS.md](DECISIONS.md), together with [MEMORY.md](MEMORY.md) and
[DEFER_PLAN.md](DEFER_PLAN.md). None is superseded by this proposal. In particular,
container insertion remains borrowing unless an existing specialized contract
says otherwise; returning an item to a pool is not ordinary object deallocation.

## 2. User-facing contract

### 2.1 Invocation and defaults

Add a boolean `--explain-rejected-free` option to `ironwoodc`. It is disabled by
default and valid for both ordinary source compilation and native linking.
Repeated occurrences are harmless. Value forms such as
`--explain-rejected-free=true` remain unsupported usage errors; no aliases or
separate verbosity levels are proposed initially.

Proposed usage:

```sh
ironwoodc Main.iron -d classes
ironwoodc --explain-rejected-free Main.iron -d classes
ironwoodc --link --explain-rejected-free -cp classes --main-class Main -o app
```

The second command is a developer-requested retry of the first with the same
other options. The link command is a separate example for existing class inputs;
a failed source compilation does not produce new classes to link. Do not add an
automatic diagnostic rerun after failure. Developers decide whether they need
the more expensive explanation.

Proposed help text:

```text
--explain-rejected-free
    Show additional ownership and source-location details for rejected free operations.
    Available during compilation and linking; disabled by default.
```

The option is per invocation, like `--unfreed`. It is not stored in `.ironclass`
or `.ironjar`, and compiling with it does not enable it for a later link. It is
independent of `--unfreed=off|warn|error` and `@SuppressUnfreed`: those controls
cannot suppress or weaken a rejected reclamation or its requested explanation.

### 2.2 What changes in the output

- Keep the existing primary error message, severity, source span, and ordering.
  Add structured `note:` entries underneath that error on standard error.
- A note can carry its own source file, span, source excerpt, and caret. Use the
  existing location style so the primary error remains recognizable.
- Explain rejection of ordinary `free`, `defer free`, and destructor field
  reclamation, including errors reported later by loop-back-edge validation.
- Unknown allocation identity, borrowed values, and uncertain ownership are in
  scope. They may have an honest boundary explanation rather than a full chain.
- If earlier errors prevented ownership refinement, attach exactly one
  limited-analysis note per rejected reclamation instead of an ownership chain.
  Follow the phase-readiness policy in section 6.4, including for library code.
- Parsing/type errors such as `free 42;`, missing-free warnings, and unrelated
  errors retain their existing diagnostics. Standalone use-after-free reports
  are outside initial scope, although an earlier free can support a rejected
  second free. Notes must not hide existing companion errors.
- Successful compilation prints no explanation report. With the option enabled,
  that successful invocation may still pay for evidence collection.
- Additional notes do not count as separate errors or warnings and do not change
  exit status or artifact emission. Semantic failures still stop before output.

Preserve the current default text; do not add a retry hint to every ordinary
diagnostic. Document the option in help and the memory guide instead.

### 2.3 Truthful wording and bounded detail

Distinguish a tracked observation from a conservative possibility. For example,
"reference stored here" describes an analyzed assignment, whereas "this call
may retain argument 1; the analysis cannot establish a non-retaining contract"
describes uncertainty. A summary-derived fact is not necessarily a concrete
runtime path, and notes from alternative branches must not be joined into one
fictional execution.

Prefer the nearest causal operation, followed by necessary context and, when
useful, the allocation site. Do not always print the allocation site if it adds
no information. Do not suggest deleting `free`, suppressing missing-free
warnings, or adding arbitrary scopes as a general fix. A remedy is appropriate
only if it follows from the demonstrated ownership relationship.

Use fixed internal limits initially, proposed as at most eight source-backed
notes per primary error and four call-summary hops per chain. Deduplicate the
same event within an explanation. Indicate omitted detail explicitly. Bound
collection as well as rendering: a small printed result must not conceal an
unbounded evidence graph. Exact storage limits are finalized during M0 after
the focused measurements described below.

## 3. What the compiler already knows

The following paths were inspected for this plan. Names are implementation
anchors, not a requirement to keep all new logic in the same large class.

| Current component | Existing information | Implication for implementation |
| --- | --- | --- |
| [Main.java](../compiler/src/main/java/ironwood/compiler/Main.java), `CommandLine`, `run`, `printDiagnostics` | CLI parsing, compile/link selection, error printing | Thread an invocation-local explanation selection into the pipeline. |
| [CompilerPipeline.java](../compiler/src/main/java/ironwood/compiler/CompilerPipeline.java) | `UnfreedMode`, parsing, semantic entry, typed program and LLVM production | Existing constructors keep explanations off; add an explicit opt-in API. |
| [Diagnostic.java](../compiler/src/main/java/ironwood/compiler/diagnostic/Diagnostic.java) and [DiagnosticFormatter.java](../compiler/src/main/java/ironwood/compiler/diagnostic/DiagnosticFormatter.java) | One primary message, source, span, and severity; source/caret formatting | Add immutable related notes without turning notes into independent diagnostics. |
| [FunctionAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java), `lowerFreeOperand` | Rejection checks for identity, borrows, pending cleanup, freed/escaped states, fields, array slots, and locals | Attach evidence to the check that actually rejected the free, preserving check order. |
| `AllocationInfo`, `AllocationStateSnapshot` | Allocation identity, ownership state, and a single `blockingReason` string | Preserve the current reason; optional evidence needs separate storage. |
| `markEscaped`, `addRetainedBorrow`, `recordReceiverBorrow`, `trackArrayElementStore` | Publication and retaining relationships | Carry the operation's source location while it is known; current relationships often discard it. |
| `snapshotOwnership`, `restoreOwnership`, `mergeOwnership`, `validateLoopBackEdges` | Path-specific states and merged uncertainty | Evidence must follow snapshots and invalidation without affecting state comparisons. |
| `DeferredFreeAction`, `DeferredCallAction`, cleanup lowering | Original action spans, captured operands, and separate cleanup predecessors | Explain the captured allocation and relevant exit, not the variable's later value. |
| [EscapeSummaryAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java) | Receiver/parameter escape sets, retention, and return-origin summaries | Call-site notes are feasible early; source chains inside callees require additional evidence. |
| [SemanticAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java) | Provisional lowering, dispatch binding, iterative ownership refinement, then final lowering | Do not report discarded provisional failures or let notes influence convergence. |
| [IronClass.java](../compiler/src/main/java/ironwood/compiler/IronClass.java) and [SourceSetLoader.java](../compiler/src/main/java/ironwood/compiler/SourceSetLoader.java) | Preserved source reconstructed with artifact display paths | Link-time explanations can use reconstructed source without persisting explanation records. |

The compiler is following ownership relationships, but it is not retaining a
complete proof transcript. Escape propagation can overwrite a reason, borrow
sets contain allocation identities without insertion locations, and joins can
replace branch-specific causes with a general conflict. A method summary can
say that an argument escapes without retaining the source operation that caused
the summary. This is why useful local notes are a smaller change than complete
explanations across methods and control flow.

### 3.1 A critical semantic isolation requirement

Today `AllocationStateSnapshot` includes `blockingReason`, and branch/loop logic
compares snapshots with record equality. Adding evidence fields to that record
would change equality and could change acceptance. Similarly, extra provenance
must not enter escape-summary equality, cache keys, or refinement convergence.

Keep explanation state separate from existing proof comparisons. Do not use this
feature to remove the existing reason from equality or otherwise clean up the
proof algorithm incidentally. Even a seemingly desirable semantic refactor needs
its own justification and paired regressions.

Other consumers to preserve include [IronDoc.java](../compiler/src/main/java/ironwood/compiler/doc/IronDoc.java),
the [language-server analysis engine](../ide/langserver/src/main/java/ironwood/lsp/AnalysisEngine.java),
and the [Eclipse output parser](../ide/eclipse/plugin/src/ironwood/ide/eclipse/CompilerOutputParser.java).
The language server currently uses the default pipeline and translates the
primary diagnostic. Eclipse parses textual primary error locations. Keep their
default behavior unchanged and ensure note locations cannot replace primary
locations. A new IDE setting or explanation command is not part of this CLI
feature; the structured note representation should allow later integration.

### 3.2 Prerequisite: stabilize competing diagnostic blockers

The original baseline does not always choose the same primary reason across
identical compiler invocations. Review reproduced both reported cases in eight
fresh JVM invocations each, using `--unfreed=off`:

- A value retained by a `Holder` and an `ArrayList<Object>` produced "live
  wrapper" six times and "live container" twice. `lowerFreeOperand` selects
  the first matching entry from the `retainedBorrows` identity map.
- A value stored in slots 0 through 3 of one array, followed by an `if` that
  saves/restores ownership, named slot 0 twice, slot 2 once, and slot 3 five
  times. `OwnershipSnapshot` uses `Map.copyOf`, which does not preserve slot
  traversal order, and the diagnostic selects the first matching slot.

These variations do not demonstrate a safety error: every selected blocker is
real. They do invalidate an assumption that exact baseline messages are already
stable. Changes in allocation or identity hashing can expose different traversal
orders; no particular JVM identity-hash implementation needs to be assumed.

Make diagnostic selection deterministic in a small, independently reviewable
prerequisite change, before implementing explanation tracking:

- Within the existing retaining-owner check, select the earliest registered
  allocation in the analyzer's existing allocation list. This is compiler
  analysis order, not a claim about runtime allocation order.
- Within the existing array-slot check, select the lowest matching slot index;
  break equal-index ties by the array's existing allocation-list order.
- Keep the order of rejection categories, candidate predicates, identity maps,
  snapshot representation/equality, and all ownership transitions unchanged.
  Restrict the change to choosing which already-established blocker to report.

Pre-change verification selection: mixed owner kinds with reversed creation and
retention orders; multiple array slots written in reverse order, before and
after a snapshot/restore; several fresh JVM invocations; and nearby accepted
controls that release the borrowers before freeing the value. Check all three
unfreed modes in-process. Run the focused alias, array-alias, receiver-retention,
container, and duplicated-finally tests when changing these selection sites.
This prerequisite does not change runtime lowering or require a native benchmark.

M0 must audit further repeated-run instability rather than treating these two
repairs as proof that every diagnostic is deterministic. Preserve evidence of
old variants, then establish a stabilized baseline. A remaining unstable case
blocks exact-message parity for that case until separately resolved; do not
silently exclude it from the feature's required coverage.

### 3.3 Known limitation: refinement skipped after earlier errors

`SemanticAnalyzer.analyze` builds initial conservative escape/owned-field
summaries, then enters provisional lowering and closed-world ownership
refinement only when no errors have been reported so far. A missing mandatory
`@Override`, for example, prevents this phase from running. Final lowering still
runs with the initial summaries and can reject otherwise-safe reclamations in
user code and bundled library code. This is existing error-recovery behavior,
not an effect of the proposed option.

Consequently, "final analysis" alone does not mean refined summaries are
available. The section 5.6 example currently produces the missing-annotation
error, a conservative rejection of `free data`, and two rejections of the
library's deferred `free printer` in `Throwable.printStackTrace`. Adding the
annotation makes the same program compile cleanly. Those three secondary errors
are not evidence of unsafe reclamation in this particular corrected program.

Keep this limitation distinct from ordinary uncertainty after completed
refinement. The feature must carry explicit phase readiness into explanation
emission and report skipped refinement honestly. Do not reconstruct an apparent
ownership history from fallback assumptions. Also do not claim every rejection
after an earlier error is false: a program can contain an independent unsafe
`free` as well.

Suppressing or reclassifying these existing secondary errors needs a separate
error-recovery decision and verification, since it changes default diagnostics.
It is not part of this explanation feature. For now, preserve primary messages,
ordering, counts, rejection outcomes, and the absence of output on failure.

## 4. Use cases and required evidence

After completed refinement, each explanation must identify the selected blocker
without claiming that fixing it necessarily resolves every blocker. Preserve the
stabilized primary rejection selection established by section 3.2 and M0. The
first causal notes must explain that exact selected owner, array slot, or
predecessor. Ordering notes by source
location must not independently select a different blocker. Additional blockers
may be described only as separately labeled facts, in deterministic source order.
Do not derive any selection from identity-map iteration order.

| Case | What the developer needs to learn | Evidence to retain or identify |
| --- | --- | --- |
| Local alias, including widened references, casts, or identity-returning calls | Which local still denotes this allocation | The alias-producing binding or latest relevant assignment, not just its declaration; preserve the allocation identity across conversions. |
| Field, static field, or constructor publication | Where the reference became externally observable | The store or constructor call, qualified field/type when known, and allocation origin when useful. |
| Container or wrapper retention | Which object still borrows the allocation | The retaining receiver and insertion/constructor call. Use a proven current local name, or a type plus creation location if no unambiguous name survives. |
| Known array slot or uncertain index | Which array retains the reference, or why an exact slot cannot be identified | The store, known index where available, and array identity. Do not invent an index after precision is lost. |
| Borrowed helper, iterator, view, or pool-owned item | Why this value cannot be independently freed | Its owner and borrow/acquisition operation; distinguish ownership from borrowing and pool return. |
| Attached owned field or uncertain destructor field ownership | Why a field cannot be freed here | The attached field or failed ownership condition. Do not infer a general recursive-free or transfer contract. |
| Pending deferred call, deferred free, or yield result | Which future action still observes or will reclaim the allocation | Registration/capture site and relevant return/yield/cleanup boundary, using captured operand identity. |
| Repeated reclamation | Where the same allocation was already freed or scheduled | Earlier free/action location; replacement of the local with a new allocation must retire the old association. |
| Divergent branches | Which alternative prevents an all-path proof | Branch boundary and representative predecessor facts, explicitly labeled as alternatives. |
| Loop back edge | Why a later iteration can observe freed, escaped, or different storage | Original allocation, reclamation, and relevant back edge/rebinding; distinguish body-local allocations. |
| Call-mediated escape, including polymorphic dispatch | Which argument/receiver and which possible target blocks proof | Call site first; a bounded callee chain only when supported by final summary evidence. |
| Parameter, mixed identity, unknown factory result, or non-fresh return | Which required ownership fact is missing | Parameter/result binding and an honest analysis-boundary note; no invented allocation or escape site. |
| Throw, catch, return, or closure capture | Which outward use keeps the allocation observable | Throw/return/capture site and retained identity, including enclosing-instance capture where applicable. |
| Source recovered from a class or archive | Where the same blocking event is in the code actually analyzed | Artifact display path and preserved source excerpt, not an assumed local checkout of that library. |
| Refinement skipped after earlier errors | Fix earlier errors before investigating a potentially secondary rejection | Explicit phase readiness; one limited-analysis note and no ownership history, including for library code. |

If evidence is unavailable, stop at the nearest verified fact. A note such as
"the final call summary may retain this argument; a more detailed source reason
is unavailable" is preferable to a guessed callee path.

## 5. Proposed diagnostic examples

These are deliberately rejected source examples, not successful runnable
programs. Number each displayed source from line 1. Output paths are shortened
for readability; real output uses the source paths held by the compiler. The
primary messages below already exist, while every added `note:` is proposed.
Except for the multi-error example in section 5.6, output excerpts show one
relevant error; unrelated or companion diagnostics must continue to be reported
normally.

### 5.1 Local alias

`AliasDemo.iron`:

```java
class AliasDemo {

    static void example() {

        byte[] data = new byte[16];
        byte[] alias = data;
        free data;
    }
}
```

Current concise error:

```text
error: cannot free 'data': allocation may still be observed through local 'alias'
  --> AliasDemo.iron:7:14
  |
7 |         free data;
  |              ^^^^
```

With `--explain-rejected-free`, append:

```text
note: local 'alias' receives a reference to the same allocation here
  --> AliasDemo.iron:6:24
  |
6 |         byte[] alias = data;
  |                        ^^^^
note: the ownership analysis still tracks 'alias' as an observer at this free
```

This does not assert that the example contains a later read of `alias`. It
explains the current conservative condition that prevented reclamation.

### 5.2 Escape through a helper

`EscapeDemo.iron`:

```java
class EscapeDemo {

    static byte[] saved;

    static void retain(byte[] value) {

        saved = value;
    }

    static void example() {

        byte[] data = new byte[16];
        retain(data);
        free data;
    }
}
```

Proposed explanation after the bounded call-summary milestone:

```text
error: cannot free 'data': allocation escapes through argument 1 of method 'retain'
  --> EscapeDemo.iron:14:14
   |
14 |         free data;
   |              ^^^^
note: this call passes the allocation as argument 1 to 'EscapeDemo.retain'
  --> EscapeDemo.iron:13:16
   |
13 |         retain(data);
   |                ^^^^
note: the callee stores that parameter in static field 'EscapeDemo.saved'
  --> EscapeDemo.iron:7:17
  |
7 |         saved = value;
  |                 ^^^^^
```

The earlier call-site milestone may show only the call and its retaining
summary. It must not claim that it already traces into the callee.

### 5.3 Earlier free

`DoubleFreeDemo.iron`:

```java
class DoubleFreeDemo {

    static void example() {

        byte[] data = new byte[16];
        free data;
        free data;
    }
}
```

Proposed output:

```text
error: cannot free 'data': allocation was already freed
  --> DoubleFreeDemo.iron:7:14
  |
7 |         free data;
  |              ^^^^
note: the same allocation was freed here
  --> DoubleFreeDemo.iron:6:9
  |
6 |         free data;
  |         ^^^^^^^^^^
```

The note must follow allocation identity rather than the spelling `data`.

### 5.4 Pending deferred observer

`DeferredDemo.iron`:

```java
class DeferredDemo {

    static void inspect(byte[] value) {

        int length = value.length;
    }

    static void example() {

        byte[] data = new byte[16];
        defer inspect(data);
        free data;
    }
}
```

Proposed output:

```text
error: cannot free 'data': allocation is retained by a pending deferred call
  --> DeferredDemo.iron:12:14
   |
12 |         free data;
   |              ^^^^
note: this deferred call captures the allocation for use at block exit
  --> DeferredDemo.iron:11:23
   |
11 |         defer inspect(data);
   |                       ^^^^
```

Do not generalize this example into "move every free to the end." The correct
cleanup sequence depends on the actual captures, owners, and exit paths.

### 5.5 Container and uncertain-path wording

For the existing "still borrowed by a live container" error, proposed notes
would identify the receiver, for example "container 'pending' retains this
allocation through the insertion below," then show that actual insertion site.
Keep the primary message unchanged. If `pending` was reassigned, identify the
original container by another proven alias or its creation site, rather than
mislabeling the new value of `pending`.

For an existing "allocation may have been freed on an incoming control-flow
path" error, show a representative earlier free and the branch boundary. Say
that this predecessor prevents a proof for all incoming paths. Do not report
that the allocation was unconditionally freed, or present mutually exclusive
branch events as a single sequence.

### 5.6 Earlier errors prevented refinement

This complete negative fixture intentionally omits `@Override` on `Quiet.accept`.
Use `--unfreed=off` to isolate mandatory safety diagnostics; the unreclaimed
`Quiet` allocation is unrelated to the example.

```java
class Sink {

    void accept(byte[] value) {
    }
}

class Quiet extends Sink {

    void accept(byte[] value) {
    }
}

class Main {

    static void use(Sink sink) {

        byte[] data = new byte[16];
        sink.accept(data);
        free data;
    }

    public static void main(String[] args) {

        use(new Quiet());
    }
}
```

Proposed output with `--explain-rejected-free`, omitting excerpts/carets here
for brevity (the formatter still prints the existing primary locations):

```text
error: method 'accept(byte[])' overrides or implements an inherited method and must be declared @Override
  --> Main.iron:9:10
error: cannot free 'data': cannot prove argument 1 of polymorphic method 'accept' does not escape
  --> Main.iron:19:14
note: ownership analysis was limited because of earlier errors; fix those first and recompile; this rejection may be secondary
error: cannot free 'printer': allocation escapes through receiver of method 'print'
  --> ironwood-stdlib.ironjar!/ironwood/lang/Throwable.ironclass!/source/Throwable.iron:93:24
note: ownership analysis was limited because of earlier errors; fix those first and recompile; this rejection may be secondary
error: cannot free 'printer': allocation has conflicting ownership across exceptional paths
  --> ironwood-stdlib.ironjar!/ironwood/lang/Throwable.ironclass!/source/Throwable.iron:93:24
note: ownership analysis was limited because of earlier errors; fix those first and recompile; this rejection may be secondary
```

The library path and line above reflect the current bundled artifact. Use the
loaded source identity in actual diagnostics. Do not add an ownership chain or
suggest changes to the library. Adding `@Override` above `Quiet.accept` accepts
this fixture with no diagnostics. Changing the annotated implementation to
publish the argument must still reject its reclamation. The limited-analysis
note is advice to retry after fixing earlier errors, not a safety verdict.

## 6. Implementation approach

### 6.1 Small option and diagnostic API changes

Pass the explicit mode through `Main` to `CompilerPipeline`, `SemanticAnalyzer`,
and the relevant final analysis. Preserve existing constructor entry points
with the mode disabled. A small boolean or two-value selection is sufficient;
do not introduce a general configuration framework for one feature.

Extend diagnostics with an immutable list of related notes containing a message
and optional source/span. Preserve existing message/source/span/severity accessors
and convenience constructors. Empty notes preserve existing rendering exactly.
`Diagnostic.hasErrors`, `CompilationArtifact.valid()`, and `successful()` continue
to depend on primary severity and output availability, not on note count.

Keep explanation eligibility structured at the rejection site. Do not decide
whether an error is eligible by searching its English text for "free".

### 6.2 Optional evidence beside the proof

Use a focused explanation collector beside the existing proof. It should retain
only facts needed for the supported notes, such as:

- An allocation's source origin and prior reclamation event.
- A local binding's current allocation and alias-producing location.
- A retaining relationship's receiver, child allocation, and retaining site.
- A publication event, with its source and qualified member/call identity.
- A cleanup capture/action and a relevant predecessor or back-edge boundary.

Key relationships by compiler identities, with source names as presentation data.
Preserve `SourceFile` plus `SourceSpan`, since a span alone does not identify a
file. Reuse immutable source objects; do not copy source text for every event.

When disabled, do not allocate the collector, per-allocation history nodes,
provenance maps, note lists, or snapshot copies solely for explanations. Guard
construction at the producer so disabled calls do not allocate argument objects
or build messages before reaching a no-op sink. Reuse the existing reason strings
without extending them with hidden detail. A small mode check may remain; measure
its cost instead of promising literally zero compiler overhead.

In enabled mode, save and restore evidence alongside ownership control flow,
but outside proof equality. Clear or update evidence when a local is reassigned,
a borrow ends, storage detaches, or a relationship changes. Otherwise a correct
rejection could be accompanied by an incorrect stale explanation.

### 6.3 Branches, loops, and duplicated cleanup

Use compact immutable event references across snapshots, rather than copying a
growing history at every branch. Associate evidence with its predecessor and
allocation identity. At a join, select bounded representative causes consistent
with the final blocking state. Evidence exhaustion may shorten notes; it must
never shorten the safety analysis or grant acceptance.

Cover rejections in `validateLoopBackEdges` as well as `lowerFreeOperand`. A
free can initially pass locally and later fail because of another iteration.
Keep the note attached to the source free and show the relevant loop evidence.

For deferred actions and duplicated `finally` paths, preserve source action
identity and captured operands. Do not mix evidence from normal, exceptional,
returning, or yielding predecessors. Deduplicate notes within each diagnostic;
do not change existing primary error counts/order as a side effect of this work.

### 6.4 Final analysis and bounded call evidence

Pass explicit diagnostic-only phase readiness from `SemanticAnalyzer` through
final lowering to every rejected-reclamation explanation site. A small flag or
two-value state is sufficient: refinement completed, or refinement skipped due
to earlier errors. Set completion only after successful convergence of the
existing provisional binding and ownership-refinement phase. Do not infer it
from whether the final diagnostic list contains errors, whether a summary map
is empty, or whether a helper happens to be non-null.

- With the option disabled, preserve current diagnostics and collect no
  explanation evidence, regardless of readiness.
- With the option enabled and refinement completed, collect evidence during
  final lowering and emit the supported explanations. A later unrelated body
  error does not retroactively turn this into skipped refinement. Completed
  refinement can still produce conservative results; label those honestly.
- With the option enabled and refinement skipped, keep the evidence collector
  absent and attach exactly the limited-analysis note in section 5.6 to each
  existing rejected reclamation. Apply this to ordinary, deferred, destructor,
  and later loop-validation rejections, including those in library sources.
  Do not mix that note with allocation/alias/escape chains or substitute it for
  the primary error. Unrelated errors receive no rejected-free notes.

This readiness state controls explanation only. It must not permit a `free`,
alter conservative summaries, suppress existing errors, or enter proof equality.
Provisional failures remain non-final; do not collect or publish their history.
The current nonconvergence path reports its own error and returns before final
lowering, so it must not manufacture rejected-free diagnostics or notes. Adding
notes must not trigger another semantic run automatically.

Call-site notes can use the final selected summaries without explaining their
internals. A later milestone may add a separate, opt-in map from final summary
effects to bounded source witnesses. Do not add provenance to the semantic
`EscapeSummary` record or its comparison/convergence inputs.

The witness must describe the actual effect: receiver retention, outward
publication, return aliasing, and fresh-return ownership are distinct. Preserve
existing pool-release, temporary-borrow, owned-field, and dispatch contracts.
Do not create a second ownership solver for diagnostics or scan a method body
for a possible store and claim it caused the final summary.

At recursion or a cycle, terminate the explanation with a summary boundary. For
polymorphic calls, identify a supported possible retaining target where known;
otherwise report unresolved retention conservatively. Respect hop and storage
limits, with a deterministic terminal note when evidence is missing or truncated.

### 6.5 Artifacts and integrations

Linking re-analyzes preserved source. Build evidence from that source and retain
paths such as `library.ironjar!/Type.ironclass!/source/Type.iron` as supplied by
the loader; do not assume that exact entry name for every artifact. No feature
state or explanation history is serialized into class/archive outputs.

Audit shared formatting/API consumers. Existing IDE and IronDocs invocations
stay off by default. Text notes must not become extra error markers or overwrite
the primary marker location. Related notes should be representable for future
LSP `relatedInformation`, but enabling an IDE workflow is separate work.

## 7. Milestones and exit criteria

All milestones below are pending. They are ordered to establish useful local
explanations before the more expensive cross-method work. Do not describe an
intermediate milestone as complete support for every use case.

### M0. Baseline and evidence boundaries

- Compile representative rejected inputs repeatedly in separate JVMs before
  changes, starting with the mixed-owner and array-slot cases in section 3.2.
  Record complete primary messages/spans and any variations. Repetition is a
  discovery check, not proof of determinism; inspect the selection policy too.
- Finish and verify the separate diagnostic-stabilization prerequisite. Record
  its reviewed revision and intentional wording selections before establishing
  the feature baseline. Keep this distinct from explanation implementation.
- Record representative accepted/rejected pairs from section 8 using that
  stabilized compiler, including concise diagnostic text/spans and successful IR.
- Record the skipped-refinement case in section 5.6, including library secondary
  errors, its corrected control, a retaining target, and a later body error
  after completed refinement. Map the readiness handoff to final diagnostic
  sites before adding a collector. Track secondary-error suppression separately.
- Map producers, snapshot consumers, proof comparisons, and final diagnostic
  emission for each initial evidence category. Record any unsupported category.
- Select the fixed storage budget and truncation policy from representative
  workloads; avoid a new public tuning option initially.
- Record unmodified compilation timing and peak memory for the workloads in
  section 9 before implementing tracking.

Exit: reviewed evidence schema and stable baseline expectations, with old
variations and prerequisite changes recorded. Agreement between future modes
alone is insufficient, as is one unchanged-compiler run per input.

### M1. CLI, structured notes, and immediate local explanations

- Add the option, disabled-default pipeline API, help text, and note formatting.
- Carry phase readiness and implement the section 6.4 gate before collecting
  evidence. Test one limited-analysis note per rejected reclamation, no chains
  when refinement was skipped, and no false limited-analysis label after a
  later body error. Cover library and deferred-free diagnostics from the start.
- Implement allocation-origin, local-alias, and earlier-free notes with the
  optional collector. Guard against stale bindings and equivalent conversions.
- Preserve default constructors and shared diagnostic consumers.
- Add focused CLI/formatter tests, on/off parity checks, and golden output for
  the alias and double-free examples.

Exit: useful local notes; no evidence records allocated when disabled; default
messages, safety outcomes, and selected generated IR match the baseline.

### M2. Retaining relationships and immediate escape sites

- Cover field/static stores, constructor escape call sites, known/unknown array
  stores, containers, wrappers, dependent borrows, and attached owned fields.
- Identify retaining objects reliably, with type/creation-site fallback.
- Add call-site notes for receiver/argument escape and notes explaining unknown
  identity. Do not yet promise internal callee paths.
- Cover pool adoption/return and borrow termination using existing contracts.

Exit: retention and escape notes point at the actual operation and object; safe
cleanup after supported borrow termination remains accepted in both modes.

### M3. Deferred actions and control-flow explanations

- Cover pending deferred calls/frees, pending yield observers, and rejected
  destructor field reclamation where these paths provide a source witness.
- Propagate optional evidence through branches, loops, exception paths, and
  duplicated cleanup without participating in proof comparisons.
- Label uncertainty and alternative paths accurately; handle loop validation
  that rejects a previously lowered free.
- Test replaced bindings, same-state branches with different source evidence,
  nested cleanup, recursion limits, and deterministic truncation.

Exit: notes survive snapshot/restore/merge correctly; predecessor facts are not
mixed; comparisons and accepted/rejected outcomes remain unchanged.

### M4. Bounded explanations through final call summaries

- Add separate summary evidence only for supported final effects, without
  changing dispatch, summary meaning, refinement order, or convergence.
- Explain the helper-escape example into its callee, plus a bounded forwarding
  helper chain. Cover a possible retaining dispatch target and an unresolved
  summary boundary.
- Test safe helper extraction versus inline code, fresh returns that also
  publish inputs, and pool-release helpers. Stop cleanly at cycles and limits.

Exit: every displayed call hop has evidence from the actual final analysis;
missing evidence is stated honestly. Record coverage limits rather than
claiming arbitrary whole-program proof reconstruction.

### M5. Cost, artifact compatibility, and documentation completion

- Complete source, loose-class, and archive reconstruction checks, including
  notes whose source is inside dependencies and valid artifact parity.
- Run the focused performance comparison and disabled-allocation inspection.
  Resolve repeatable normal-mode regressions before claiming the feature ready.
- Finish applicable CLI/API integration checks. Update `COMPILER.md`,
  `MEMORY_MANAGEMENT.md`, relevant `MEMORY.md` diagnostic guidance, `LOCAL_TESTING.md`,
  CLI help, and the relevant README/IDK option references found during the audit.
- Record the implemented diagnostic architecture and boundaries in a new
  `DECISIONS.md` entry, using the next number at implementation time. No change
  to the accepted ownership/reclamation rules is intended.
- Update this plan with measured results, completed milestones, and exact
  remaining limitations. Do not mark unsupported categories complete.

Exit: all preceding milestone criteria hold, default and explained compilation
remain semantically equivalent, and measured costs are documented. This does
not authorize another memory-management feature or an unfiltered test run.

## 8. Focused verification and affected contracts

This selection follows the [regression lessons and pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes).
Revisit it if implementation touches additional proof producers or consumers.

### 8.1 Required comparisons

Separate the historical compiler from the stabilized feature baseline. For the
section 3.2 prerequisite, preserve acceptance, primary severity/span, and valid
generated code; record the intentional choice among previously varying messages.
Do not require one arbitrary historical message to match the stable choice.

Then compare three configurations: the stabilized compiler before explanation
tracking, the new compiler with the option off, and the new compiler with it on.
For matching existing options, assert the same accepted/rejected result and the
same ordered primary messages,
severities, and spans. Notes are the only intended diagnostic difference.
Repeat the comparisons across fresh JVMs, including the competing-blocker inputs.
Assert that notes refer to the owner/slot selected by the primary diagnostic;
independently sorted but mismatched evidence is a failure.
Compare successful typed IR/LLVM directly. Do not normalize away explanation
metadata to make the comparison pass; it must not be present there at all.

Use `UnfreedMode.OFF`, `WARN`, and `ERROR`, and a suppressed-allocation negative
case. Verify valid inputs emit no explanation text, unrelated errors retain
their formatting, failed commands emit no new class/native outputs, and malformed
input still produces diagnostics rather than crashing.

| Contract | Accepted control | Rejected neighbor |
| --- | --- | --- |
| Local identity and aliases | Alias reassigned away before free | Live alias, including widened/cast/returned alias |
| Reclamation identity | Free, replace local with fresh allocation, free replacement | Free the same allocation twice |
| Publication and call effects | Proven non-retaining call | Field/static publication or retaining/unknown call |
| Fresh return | Independent fresh result without outward publication | A result or input also published elsewhere |
| Containers, arrays, and views | Supported borrow termination before owner free | Live container/slot/view or uncertain retaining store |
| Pools and helpers | Proven same-pool release, direct and helper forms | Wrong/unknown pool or independent free of a pool-owned item |
| Owned fields | Existing proven destructor cleanup/detachment | Borrowed, attached, or uncertain field ownership |
| Deferred cleanup | Existing correct capture and free ordering | Pending observer, duplicate free, or escaping return/yield |
| Branches and loops | Equivalent live states; body-local allocation each iteration | Maybe-freed join or loop back edge observing old storage |
| Exceptions and cleanup | Existing safe cleanup across independent exits | Publication or a still-observed allocation on an exit |
| Dispatch and artifacts | Known non-retaining targets from source/classes/archive | Retaining target or unresolved flow through the same paths |
| Phase readiness | Section 5.6 with the required annotation | Missing annotation skips refinement; retaining target remains unsafe after annotation fix |

For section 5.6, preserve every existing primary diagnostic across option modes.
When enabled, each rejected reclamation gets exactly one limited-analysis note
and no ownership chain, including both deferred library rejections. The missing
`@Override` diagnostic receives no such note. The corrected fixture compiles
without notes. A retaining implementation still fails after annotation repair.
Also test an unrelated final-body error with refinement completed, both with a
safe free and with an independent unsafe free: the latter must receive its
ordinary supported explanation, not the skipped-refinement note. No failure may
produce a program/artifact. Keep current secondary-error counts as a recorded
baseline, not an intended permanent error-recovery contract.

Explanation-specific tests must assert exact related files/spans and causal
wording, not just the presence of a `note:` string. Include reassigned container
names, same-line/multiple events, source-name collisions across methods, source
paths with spaces, missing locations, repeated runs, multiple independent errors,
and truncation. Golden output should cover the examples in section 5.

For artifact failures, use fixtures that can legitimately be produced, or the
existing test-level artifact construction facilities. Do not assume rejected
source can first be compiled into a class with safety disabled; no such mode
exists. Test reconstructed source identity and archive paths explicitly.

### 8.2 Existing regression selections

The following registered tests are relevant starting points, verified by reading
their registrations. Run the group for the machinery actually changed; do not
run every listed group after every small edit.

Core diagnostic and identity changes:

```sh
./scripts/test.sh \
  --test 'diagnostic formatting includes location and source' \
  --test 'safe free accepts local allocation and ended aliases' \
  --test 'safe free rejects live aliases and escaped allocations' \
  --test 'safe free selects stable blockers across fresh compiler processes' \
  --test 'safe free distinguishes earlier errors from refined dispatch' \
  --test 'safe free rejects unknown identities and uncertain control flow' \
  --test 'safe free rejects double free and post-free use' \
  --test 'safe free accounts for reference-array element aliases'
```

Retention, summaries, and existing library consumers:

```sh
./scripts/test.sh \
  --test 'unfreed diagnostics track receiver-retained allocations' \
  --test 'data structures retain inserted references for safe-free analysis' \
  --test 'private backing arrays are freed only after proven detachment' \
  --test 'borrow dispatch uses exact overloads defaults and receiver flow' \
  --test 'borrow dispatch rejects retaining and unknown receiver flows' \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'pool release helper proofs survive artifact reconstruction'
```

Control-flow and suppression boundaries:

```sh
./scripts/test.sh \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'deferred free emits independent typed cleanup copies' \
  --test 'deferred free survives source class and archive reconstruction' \
  --test '@SuppressUnfreed preserves mandatory safe-free errors'
```

Register new focused explanation tests during implementation for CLI validation,
note locations, mode parity, artifact source recovery, disabled tracking, and
bounded summary/control-flow evidence. Their names must be added to
`LOCAL_TESTING.md` once they exist; the commands above do not claim to test the
unimplemented option. Select loop and owned-field tests additionally when those
paths are edited. Exercise existing IDE parser/API checks if their shared
interfaces are touched.

Use one small accepted native fixture at the integration milestone to compare
behavior and reclamation counts with the flag off/on. Source/IR parity is the
primary evidence for a diagnostics-only change. If implementation unexpectedly
changes hot lowering, stop and revisit scope; D132/D133 require the relevant
optimized-code inspection and deterministic benchmark before accepting it.

Run `git diff --check` throughout. Run `./scripts/check-licenses.sh` when adding
or changing compiler/test source. Do not run the full suite or hosted platform
builds for this feature without an explicit human request.

## 9. Compilation cost and acceptance evidence

The feature's purpose requires testing the ordinary successful build path, not
only demonstrating attractive output on a small failing example.

Measure the same prebuilt bootstrap compiler configurations from section 8:

1. A small successful source compilation.
2. A representative larger successful program, such as OrderBook's compiler
   invocation, with identical source/classpath inputs.
3. A focused failing input with several ownership rejections.
4. An enabled-mode stress input with nested joins, loops, and forwarding calls,
   to verify bounded evidence growth and recursion termination.

Keep JDK, JVM options, machine, stdlib inputs, and existing compiler flags fixed.
Record warm-up, repeat count, alternating execution order, median wall time and
variation, peak process memory, and compiler allocation profiles where useful.
Time direct compiler invocations separately from `scripts/test.sh` rebuild time.
Separate source analysis cost from LLVM/native linking, which could mask a
frontend regression. Measure link-time source reconstruction separately where
it materially differs.

Required evidence:

- No detailed-history allocation sites execute with the option disabled. Verify
  by inspecting guarded producers and with a focused allocation/profile check;
  output silence alone proves nothing about collection cost.
- No repeatable normal-mode slowdown or memory growth outside baseline variation
  is accepted silently. Investigate and record it; revise the implementation or
  discuss a material remaining tradeoff before acceptance. Do not invent a
  universal percentage claim before collecting measurements.
- Enabled-mode time and memory increases are reported honestly, including for
  successful compilations. Optional does not mean unbounded or unmeasured.
- Storage/output limits stop explanation growth without changing the semantic
  analysis, and the output explicitly identifies truncated detail.
- Generated code contains no explanation machinery. Successful artifacts retain
  the same semantic contents; account for container timestamps when comparing
  archive bytes rather than treating timestamp variation as a code change.

## 10. Completion boundaries

The deliverable is an opt-in explanation of existing rejected reclamations with
useful, truthful supporting locations. It is not a proof visualizer, exhaustive
path explorer, automatic repair tool, or new reclamation policy. A compiler
limitation may remain a limitation after it is explained.

Before closing implementation, document which use-case rows have full evidence,
which stop at a call/ownership boundary, and which are deliberately deferred.
Keep the normal-mode cost and semantic-equivalence evidence alongside those
coverage claims. Richer diagnostics are worthwhile only if developers can trust
both the explanation and the unchanged safety decision beneath it.

## 11. Verification of this planning document

### 11.1 Initial plan

The plan was checked against the code review baseline above:

- All four negative source examples were compiled individually with the current
  compiler and `--unfreed=off`. Each failed with its documented primary reason
  and primary source location, without producing class output.
- Local document/source links and every existing exact test name in the command
  selections were checked against the repository.
- The added notes and new command-line option remain proposed. Their output,
  performance, and semantic parity have not been implemented or tested.

This was focused documentation validation. No full compiler or native suite was
run, and these results do not mark any implementation milestone complete.

### 11.2 Diagnostic determinism review, 2026-09-23

The section 3.2 prerequisite was implemented for review as a change to the two
diagnostic selections in `lowerFreeOperand`. It leaves ownership maps, snapshot
equality, state transitions, and rejection-category precedence unchanged. The
explanation option and evidence collector are still unimplemented.

The new [FreeDiagnosticTests](../compiler/src/test/java/ironwood/compiler/FreeDiagnosticTests.java)
regression failed against the unchanged compiler: reverse-order slot stores
without a join selected slot 3 instead of the intended lowest slot, 0. After the
selection fix, seven focused tests passed:

- `safe free selects stable blockers across fresh compiler processes`
- `safe free accepts local allocation and ended aliases`
- `safe free rejects live aliases and escaped allocations`
- `safe free accounts for reference-array element aliases`
- `safe free tracks ownership independently across duplicated finally paths`
- `unfreed diagnostics track receiver-retained allocations`
- `data structures retain inserted references for safe-free analysis`

The new regression covers creation/retention-order variants and accepted cleanup
under every unfreed mode, plus identical complete diagnostic output across eight
fresh JVMs. The license audit passed. These checks establish the two repaired
selections, not determinism of every compiler diagnostic or a compilation-time
performance claim. M0 must still audit other candidates and record the accepted
stabilized revision before explanation-mode implementation.

### 11.3 Skipped-refinement review, 2026-09-23

The section 5.6 example was reproduced through `bin/ironwoodc --unfreed=off`
using Java 21 on `PATH`. It produced the missing-override error and all three
secondary reclamation errors shown above, with no class output. Adding the
annotation compiled cleanly. Java 25 is not required; the shell's Java 8 cannot
run this bootstrap compiler.

The new [FreeAnalysisReadinessTests](../compiler/src/test/java/ironwood/compiler/FreeAnalysisReadinessTests.java)
records the current user-code fallback rejection, accepts the annotated
non-retaining case, rejects a retaining variant, and confirms a later body error
does not lose the refined proof for the safe free. Failed analyses expose no
typed program or LLVM output. It intentionally does not freeze the number or
wording of secondary library errors as a permanent recovery contract.

Four focused tests passed:

- `safe free distinguishes earlier errors from refined dispatch`
- `mandatory @Override enforces override intent in both directions`
- `borrow dispatch uses exact overloads defaults and receiver flow`
- `borrow dispatch rejects retaining and unknown receiver flows`

The license audit and diff whitespace checks passed. This review changes the
plan and baseline tests only. Phase readiness, limited-analysis notes, and the
option itself remain unimplemented; their on/off comparisons belong to M1.
Production analysis and existing diagnostic output are unchanged.
