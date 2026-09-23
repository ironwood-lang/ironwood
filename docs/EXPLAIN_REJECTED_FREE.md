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
ironwoodc --explain-rejected-free -cp lib/classes --source-path app/src \
  -d app/classes app/src/app/Keeper.iron
ironwoodc --link --explain-rejected-free -cp classes --main-class Main -o app
```

The second command is a developer-requested retry of the first with the same
other options. The link command is a separate example for existing class inputs;
a failed source compilation does not produce new classes to link. Do not add an
automatic diagnostic rerun after failure. Developers decide whether they need
the more expensive explanation.

The dependency-compilation example does not use `--link`: ordinary compilation
also loads and analyzes preserved library source with the application. A library
that compiled safely alone can reject a free in this larger context. Source-path
dependencies are analyzed from their source files; class directories and archives
provide reconstructed source. Linking accepts compiled inputs through `-cp` and
`--main-class`, not source files or `--source-path`. See section 5.12 for a valid
dependency failure and the distinct compilation/linking test recipes.

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
  reclamation, including registration-time deferred-free errors, both
  loop-back-edge diagnostics, and later owned-array element cleanup validation.
  Section 3.4 defines eligibility at each emitter, including compound checks.
  A primary location may be a loop or field declaration; preserve it and put
  the causal reclamation/store location in a related note.
- Unknown allocation identity, borrowed values, and uncertain ownership are in
  scope. They may have an honest boundary explanation rather than a full chain.
- If earlier errors prevented ownership refinement, attach exactly one
  limited-analysis note per rejected reclamation instead of an ownership chain.
  Follow the phase-readiness policy in section 6.4, including for library code.
- Parsing/type errors such as `free 42;`, missing-free warnings, and unrelated
  errors retain their existing diagnostics. Wrong-pool transfers, writes to a
  local with a pending deferred free, and standalone use-after-free reports are
  outside scope, although those operations may supply evidence for a separate
  eligible reclamation error. Notes must not hide existing companion errors.
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

The first causal note must explain the check or reason selected for the primary
error, using that selection's supported evidence. For a stored blocking reason,
follow the update rules in section 3.5, not source proximity or the first event
that made the allocation observable. Follow with necessary context and, when
useful, the allocation site. If evidence for the selected reason is unavailable,
state that boundary rather than substitute a better-documented different blocker.
Do not suggest deleting `free`, suppressing missing-free warnings, or adding
arbitrary scopes as a general fix. A remedy is appropriate only if it follows
from the demonstrated ownership relationship.

Use fixed internal limits initially: at most eight notes per primary error,
including exit, boundary, and truncation notes, and four call-summary hops per
chain. For an eligible cleanup-copy error after completed refinement, reserve
one note for its exit context and, when needed, one for truncation. Keep the
selected reason's supported cause before less useful allocation/history detail.
Deduplicate the same event within an explanation. Indicate omitted detail
explicitly. Bound collection as well as rendering: a small printed result must
not conceal an unbounded evidence graph. Exact storage limits are finalized during M0 after
the focused measurements described below.

The output cap is per diagnostic, not per source line: three errors at one
cleanup site may produce up to 24 notes. Repeat a shared cause in each error's
own explanation rather than saying "see the previous error". Each diagnostic
must stand alone for CLI and IDE consumers. An internal immutable cause may be
shared to save storage; the output must not suppress it across copies. The
collection budget still applies across the invocation, and exhaustion must not
drop existing primary errors or the exit/boundary distinction. Merging duplicate
primary errors is a separate diagnostic-policy decision, outside this feature.

## 3. What the compiler already knows

The following paths were inspected for this plan. Names are implementation
anchors, not a requirement to keep all new logic in the same large class.

| Current component | Existing information | Implication for implementation |
| --- | --- | --- |
| [Main.java](../compiler/src/main/java/ironwood/compiler/Main.java), `CommandLine`, `run`, `printDiagnostics` | CLI parsing, compile/link selection, error printing | Thread an invocation-local explanation selection into the pipeline. |
| [CompilerPipeline.java](../compiler/src/main/java/ironwood/compiler/CompilerPipeline.java) | `UnfreedMode`, parsing, semantic entry, typed program and LLVM production | Existing constructors keep explanations off; add an explicit opt-in API. |
| [Diagnostic.java](../compiler/src/main/java/ironwood/compiler/diagnostic/Diagnostic.java) and [DiagnosticFormatter.java](../compiler/src/main/java/ironwood/compiler/diagnostic/DiagnosticFormatter.java) | One primary message, source, span, and severity; source/caret formatting | Add immutable related notes without turning notes into independent diagnostics. |
| [FunctionAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java), `lowerFreeOperand` | Rejection checks for identity, borrows, pending cleanup, freed/escaped states, fields, array slots, and locals | Attach evidence to the check that actually rejected the free, preserving check order. |
| `prepareDeferredFree`, `lowerDestructorFieldFree` | Registration-time eligibility/duplicate checks; separate field ownership and pending-call checks | Classify the actual failing condition, not the shared primary text. |
| [OwnedArrayFieldAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/OwnedArrayFieldAnalyzer.java) | Field ownership membership; only the sibling-field publication rejection supplies text, without a source witness | Use a field-proof boundary until M4. Existing reason presence also affects field-load identity; do not populate that map for diagnostics. |
| [OwnedArrayElementAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/OwnedArrayElementAnalyzer.java), `validate`, `Checker.check`, `Checker.add`, `Checker.reject` | Later typed-IR validation of creation-array cleanup; diagnostics at the field declaration | Pass mode/readiness beyond function lowering; retain the offending operation's own source identity for related notes. |
| `AllocationInfo`, `AllocationStateSnapshot` | Allocation identity, ownership state, and a single `blockingReason` string | Preserve the current reason; optional evidence needs separate storage. |
| `lowerLocalVariable`, `readLocal`, local assignment through `resolveLValue` | Locals mapped to operands; reads can reuse an operand created elsewhere | The operand's span is not an alias-assignment location. Record the source expression separately when establishing the binding. |
| `markEscaped`, `addRetainedBorrow`, `recordReceiverBorrow`, `trackArrayElementStore` | Publication and retaining relationships; these APIs receive no operation span | Thread source context from producers. Operand creation spans cannot substitute for the store, call, or argument that established the fact. |
| `reclamations`, `Reclamation`, `lowerFreeOperand`, `validateLoopBackEdges` | Existing method-wide list of accepted frees with allocation identity and full statement span | Reuse available event spans, but add optional path associations. The list is not rolled back by ownership restore and cannot alone explain an earlier free. |
| `snapshotOwnership`, `restoreOwnership`, `mergeOwnership`, `validateLoopBackEdges` | Path-specific states and merged uncertainty | Evidence must follow snapshots and invalidation without affecting state comparisons. |
| `DeferredCallAction`, `PreparedInvocation`, `pendingDeferredOperands` | Evaluated receiver/arguments plus whole-call and null-check spans; no per-argument expression spans | Retain each source operand's role and expression span separately during preparation. Later reassignment of a source local does not redirect the call. |
| `DeferredFreeAction`, `prepareDeferredFree`, `pendingDeferredFrees`, cleanup lowering | Resolved `LocalSymbol`, registration spans, and live-after locals; no saved value | Explain the matched bound local and defer site, using the same environment/allocation lookup as the rejecting check. Keep its cleanup-exit context separate. |
| `lowerDeferredTail`, `lowerTry`/catch lowering, `completeReturnThrough`, `completeTransferThrough`, `completeYieldThrough`, `lowerFinallyForPendingException`, `emitCleanupAction` | Distinct cleanup entry routes; exceptional predecessors merge at `beginExceptionHandler` | Supply exit context at the copy's entry, including normal/catch completion, transfers, and grouped exceptional unwinding; do not infer it from a shared action span. |
| [EscapeSummaryAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java) | Receiver/parameter escape sets, retention, and return-origin summaries | Call-site notes are feasible early; source chains inside callees require additional evidence. |
| [SymbolicReturnOriginAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java), `withSymbolicReturnSummary`, `applyAuditedBorrowingContract` | Separate return/non-return escape fixed point, followed by transformations of the summary consumed by final lowering | M4 evidence must support the exact final effect, not merely an earlier raw escape bit. |
| [SemanticAnalyzer.java](../compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java) | Provisional lowering, dispatch binding, iterative ownership refinement, then final lowering | Do not report discarded provisional failures or let notes influence convergence. |
| [IronClass.java](../compiler/src/main/java/ironwood/compiler/IronClass.java) and [SourceSetLoader.java](../compiler/src/main/java/ironwood/compiler/SourceSetLoader.java) | Preserved dependency source reconstructed in both ordinary compilation and linking, with artifact display paths | Explanations can cross from a dependency into application code during either invocation. Keep each note's own source identity without persisting explanation records. |

The compiler is following ownership relationships, but it is not retaining a
complete proof transcript. Escape propagation can overwrite a reason, borrow
sets contain allocation identities without insertion locations, and joins can
replace branch-specific causes with a general conflict. A method summary can
say that an argument escapes without retaining the source operation that caused
the summary. This is why useful local notes are a smaller change than complete
explanations across methods and control flow.

### Source-context work that remains

Local notes require producer changes, not just formatting facts already stored.
`TypedValue` has no expression span, and `readLocal` returns the existing operand.
In section 5.1 both names can therefore denote the allocation operand from line
5; neither that operand nor the environment entry records the alias initializer
on line 6. `PreparedInvocation` likewise does not retain argument-expression
locations for section 5.4 or 5.9. Source AST/planning objects have those locations
while lowering the operation, before ownership helpers lose that context.

Audit and thread the following families, following section 3.5's exact reason
guards. Enumerate call sites again at implementation time rather than treating
a historical count as complete coverage:

| Producers / transfers | Context to retain only when enabled | Milestone |
| --- | --- | --- |
| `lowerLocalVariable`, local assignment through `resolveLValue`, other environment writes and binding merges | Resolved local, current allocation, initializer/right-hand expression span; clear stale binding evidence on replacement | M1 local cases; M3 alternative-path presentation |
| Every `markEscaped` caller: field/static/array stores, returns/throws, call effects, captures/enclosing instances, pool and container paths | Actual operation and receiver/argument/store role, plus propagated retaining relationship | M2, with explicitly unsupported paths receiving boundary notes |
| Direct `AllocationInfo.escape` calls in construction and escape propagation | Construction/call source and the selected reason's event | M2 |
| `blockReclamation`, `makeUncertain`, merged identities and direct join reason assignments | Actual uncertainty-producing expression or labeled predecessor context | M2 local producers; M3 joins |
| `addRetainedBorrow`, `recordReceiverBorrow`, `trackArrayElementStore`, array exposure and recursive escape propagation | Relationship-establishing site and later triggering operation, distinguished from child allocation origin | M2 |
| `lowerInvocationArguments`, invocation preparation and specialized preparation paths | Original argument expressions, receiver role, source argument numbers, and mapping through conversions/packing; never infer these from lowered operand spans | M2 ordinary calls; M3 deferred calls |
| Accepted reclamation in `lowerFreeOperand` | Existing statement span and allocation identity associated with the current path's freed state | M1 association and restore discipline; M3 labeled joins/cleanup copies |

Reusing a `SourceSpan` reference need not allocate in disabled mode. New event
objects, collections, and formatted text must remain behind the phase-specific
collection gates in section 6.4. Passing context and checking the mode can still
have a compilation cost; measure it under section 9 rather than promising zero
cost. Do not change operand
spans, `TypedValue` equality, or generated IR to carry explanation-only data.

### 3.1 A critical semantic isolation requirement

Today `AllocationStateSnapshot` includes `blockingReason`, and branch/loop logic
compares snapshots with record equality. Adding evidence fields to that record
would change equality and could change acceptance. Similarly, extra provenance
must not enter escape-summary equality, cache keys, or refinement convergence.

Preserve these concrete stopping comparisons and the values they compare:

| Analyzer | Existing comparison that must ignore evidence |
| --- | --- |
| `EscapeSummaryAnalyzer` escape rounds | `summaries.equals(before)` |
| `SymbolicReturnOriginAnalyzer` return/non-return rounds | `merged.equals(previous)` after `previous.merge(discovered)` |
| `SemanticAnalyzer` temporary-borrow refinement | `refinedBorrows.equals(temporaryBorrows)` and `refinedLists.equals(temporaryLists)` |
| `OwnedArrayFieldAnalyzer` proof stability | `refinedFields.sameProofsAs(ownedArrayFields)`, covering owned fields, borrowed-return fields, and ambiguous borrowed returns |
| `ClosedWorldEffectAnalyzer` effects | `next.equals(previous)` |

No witness, source path, discovery order, or diagnostic generation identifier
belongs inside those semantic records, sets, maps, or their keys. On/off tests
must preserve not just successful convergence but the same semantic pass counts
and results. Evidence exhaustion must not request another pass or change a limit.

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

### 3.4 Rejection-site inventory and scope

This inventory is the eligibility checklist for implementation. Unless qualified,
methods belong to `FunctionAnalyzer`. Message templates use placeholders for
names, indices, types, and existing `blockingReason` text; they do not authorize
rewriting primary diagnostics. Dynamic reason producers are evidence sources,
not extra diagnostic emitters. Re-audit callers and emitters in M0 if code moves.

M1 installs structured eligibility and the phase-readiness gate for every
in-scope row, including later passes. After completed refinement, unsupported
detail must have an honest boundary note until the listed milestone supplies
the required evidence. When refinement was skipped, section 6.4 overrides all
in-scope rows with the single limited-analysis note. Out-of-scope rows receive
neither kind of note. The milestones below schedule richer evidence, not changes
to safety decisions or primary locations.

| Rejection site / condition | Existing primary message or suffix | Scope and evidence milestone |
| --- | --- | --- |
| `lowerFree` / unresolved local target | `free target must be a local variable, not a field or type name` | Out: name/target error. |
| `lowerFreeOperand` / non-reference type | `free target must have a class, interface, or array reference type, not <type>` | Out: type error. |
| `lowerFreeOperand` / unknown allocation, local or expression | `cannot prove free of <target> safe: value is not a known allocation created by new in this method, returned by a proven fresh factory, or a proven detached private backing array`; expression variant: `free target must be a local variable created by new in this method or a proven fresh expression` | In, M2: explain the missing identity/freshness proof. The expression variant is not a name error. |
| `lowerFreeOperand` / dependent borrow | `cannot free <target>: value is a borrowed helper owned by another object` | In, M2: proven owner/acquisition relationship. |
| `lowerFreeOperand` / pending deferred free | `cannot free <target>: allocation has a pending deferred free` | In, M3: matched bound local and registration, through `allocationOf(environment.get(action.target()))`. No synthetic captured value. |
| `lowerFreeOperand` / retaining owner | `cannot free <target>: allocation is still borrowed by a live container` or `wrapper` | In, M2: the deterministically selected owner and retaining operation. |
| `lowerFreeOperand` / pending call or yield | `cannot free <target>: allocation is retained by a pending deferred call` or `pending yield result` | In, M3: pending capture/result and relevant exit. |
| `lowerFreeOperand` / `FREED` | `cannot free <target>: allocation was already freed` | In, M1: earlier reclamation of the same allocation. |
| `lowerFreeOperand` / `ESCAPED`, `UNCERTAIN`, `MAYBE_FREED` | `cannot free <target>: <blockingReason>` | In, M2 for immediate escape, M3 for paths, M4 for bounded callee/field witnesses. Preserve the selected state/reason and uncertainty. |
| `lowerFreeOperand` / attached owned field | `cannot free <target>: allocation is still reachable through private field '<field>'` | In, M2: owning field and attachment. |
| `lowerFreeOperand` / stored array alias | `cannot free <target>: allocation is still reachable through known array element [<index>]` | In, M2: selected array/index and store. |
| `lowerFreeOperand` / live local alias | `cannot free <target>: allocation may still be observed through local '<alias>'` | In, M1: current alias-producing binding. |
| `prepareDeferredFree` / unresolved local | `defer free target must be a local variable` | Out: name error. |
| `prepareDeferredFree` / non-reference local | `cannot defer free of '<name>': target must be a live, proven owned local reference` | Out: type error despite sharing text with ownership failures. |
| `prepareDeferredFree` / unknown allocation, dependent borrow, or state that may be freed | Same `cannot defer free of ...` message above | In, M3: distinguish the first failing ownership condition as described below. |
| `prepareDeferredFree` / duplicate action on same local or allocation | `allocation already has a pending deferred free` | In, M3: earlier registration, including a registration through another alias. |
| `lowerDestructorFieldFree` / primitive field | `destructor free target must have a reference type, not <type>` | Out: type error. |
| `lowerDestructorFieldFree` / unproved field ownership | `cannot prove destructor free of field '<field>' safe: field ownership is uncertain` | In: field-proof boundary initially; M4 adds bounded supported reasons/witnesses. Do not infer the cause from an arbitrary field write. |
| `lowerDestructorFieldFree` / captured attached field | `cannot free field '<field>': allocation is retained by a pending deferred call` | In, M3: the matching deferred capture. |
| `validateLoopBackEdges` / carried freed or maybe-freed local | `cannot carry freed allocation in local '<local>' across loop back edge` | In, M3: preserve the back-edge flow block's primary span (often the loop); note the causal free on that predecessor when known. |
| `validateLoopBackEdges` / invalid repeated reclamation | `cannot prove free safe across loop back edge: the next iteration may observe a freed, escaped, or different allocation` | In, M3: preserve the reclamation span; note the blocking back edge/state. |
| `OwnedArrayElementAnalyzer.Checker.reject`, reached through `validate` | `cannot prove owned elements of '<field>' safe: <reason>` | In, M4: preserve the field-declaration span; relate the actual failed element contract and recognized destructor cleanup. Reason families are listed below. |
| `finishPoolTransfer` / wrong owner | `cannot transfer an object owned by another pool or containing object; release must return a value checked out from this pool` | Out: rejects `release`, not reclamation by `free`. Keep in safety-parity tests. |
| `rejectPendingFreeWrite` | `cannot assign to or update local '<name>' while its deferred free is pending` | Out: rejects a write, not the registered cleanup. |
| `lowerName`, `checkNotFreed` | `cannot use '<name>' after its allocation was freed`; `cannot use evaluated reference after its allocation was freed` | Out: use-after-free reports, even if expression evaluation accompanies a rejected free. |
| [PrimitiveGenericSpecializer.java](../compiler/src/main/java/ironwood/compiler/semantic/PrimitiveGenericSpecializer.java), `instruction` / primitive `IrFreeInstruction` | `primitive specialization cannot free a value` | Out: specialization type guard. Source audit establishes the site; no ordinary-source reproducer is claimed. |
| [Parser.java](../compiler/src/main/java/ironwood/compiler/parser/Parser.java), `parseDefer`, `parseFree` | `defer free requires a local variable name`; `expected ';' after free statement`, plus ordinary expression/syntax diagnostics | Out: parsing, not ownership. |

`prepareDeferredFree` currently combines four predicates in one short-circuit
check. Record the first failing condition in the existing evaluation order:
non-reference type (excluded), absent allocation identity, dependent borrow,
then `mayBeFreed`. Preserve null safety and the primary message. For a parameter,
say that a local owned allocation has not been proved; do not invent an escape.
For a dependent borrow, identify the owner if known. For `FREED`, point at the
earlier free; for `MAYBE_FREED`, explain the incoming-path uncertainty rather than
claiming definite reclamation. Select duplicate registrations deterministically
and explain the exact matched local/allocation. Cleanup-time checks still flow
through `lowerFreeOperand`; do not confuse them with registration failures.

The owned-element validator has twelve current reason strings. All are in scope
under its single diagnostic family; generic substring matching is insufficient:

| `Checker` reason suffix | Required M4 evidence or honest boundary |
| --- | --- |
| `creation-array elements require a direct dependent-borrow getter or destructor loop` | Unsupported element load and its enclosing callable. |
| `each creation-array entry must receive a distinct fresh object exactly once` | Distinguish no fresh origin, repeated object, and store repetition without recreation; show the matching operation(s). |
| `creation-array copying must preserve each element once in fresh replacement storage` | Failed copy/replacement proof; do not claim a concrete duplicate unless known. |
| `creation-array storage cannot be passed to an arbitrary call` | Array argument at the call. |
| `a creation-array object cannot also escape through a field` | Instance/static field store of the recorded object. |
| `a recorded object is reclaimed only by creation-array cleanup` | Independent free of the recorded object. |
| `a fresh creation-array object cannot also be stored in another array` | Other-array store and original recorded identity. |
| `a fresh creation-array object cannot escape through a call` | Call argument and failed confinement condition. |
| `an owned element must keep its storage-owner backlink encapsulated` | Constructor argument/backlink whose confinement is unproved. |
| `returning a creation-array object requires a proved dependent-borrow contract` | Return and missing borrow contract. |
| `a creation-array object cannot escape through throw` | Throw of the recorded object. |
| `creation-array storage must remain private to its owner` | Field load whose receiver/owner context failed the check in `Checker.add`. |

Do not change `Checker.failed` behavior: it currently reports at most one reason
per field/function checker, not one error for the entire field. M0 must also
check deterministic selection among its candidate reasons, including unordered
collections. Any necessary stabilization is a separate prerequisite under
section 3.2; notes may not independently choose another failure.
Cause classification must preserve short-circuit evaluation and state changes,
including `recorded.putIfAbsent` in the distinct-entry check. Do not evaluate a
mutating predicate again just to decide which explanation to emit.

### 3.5 Keep reason selection and evidence selection aligned

`lowerFreeOperand` first selects a rejection category in its existing order.
Only its escaped/uncertain/maybe-freed state check reads `blockingReason`.
Evidence must follow both levels: the selected category (section 3.4), then the
particular reason retained for that allocation on that analysis path. A later
event elsewhere in the source is not necessarily the selected cause.

| Current reason producer or transfer | Existing semantic rule | Required diagnostic-only evidence behavior |
| --- | --- | --- |
| `AllocationInfo.escape(reason)` | Overwrites state/reason with `ESCAPED` whenever `!state.mayBeFreed()`; does nothing for `FREED` or `MAYBE_FREED`. | Replace selected evidence exactly when the update is accepted, even if the text equals the previous reason. The latest accepted escape wins on that path. |
| `AllocationInfo.blockReclamation(reason)` | Records `UNCERTAIN` and the reason only while `state == ACTIVE`. | Install evidence only for that first accepted uncertainty. Later ignored uncertainties cannot replace the selected evidence. |
| `AllocationInfo.makeUncertain(reason)` | Ignores owned-field origins; otherwise delegates to `blockReclamation`. | Respect both guards. An attempted update is not a selected reason. |
| `mergeOwnership`, equal incoming semantic snapshots | Copies the first snapshot's state, reason, and detached flag. | Restore supporting incoming evidence; equal reason text does not establish equal source histories. Retain bounded representative predecessors where needed, without changing semantic equality. |
| `mergeOwnership`, differing incoming semantic snapshots | Writes a general conflict reason, or the incoming-path maybe-freed reason. | Replace any stale direct-event explanation with join evidence for that selected general reason. Predecessors support the conflict, not a claim of one definite escape. |
| `mergeOwnership`, conflicting pool owners or inexact array slots | Calls `blockReclamation` for affected allocations. | Apply the same ACTIVE-only guard. For an incoming array-store reason, relate the actual store and its incoming path; do not point at a later ignored uncertainty. |
| `mergeAllocationIdentity`, existing alternative allocations | Calls `blockReclamation` with the merged-reference reason. | Preserve any existing escape/uncertainty when the update is ignored. This part does not unconditionally replace reasons at a join. |
| `mergeAllocationIdentity`, synthetic possibly-freed allocation | Creates a new `MAYBE_FREED` identity with `merged reference may designate an allocation that was freed`. | Give the new identity its own merge evidence, with bounded possible freed predecessors; do not inherit an unrelated alternative's last escape. |
| `snapshotAllocationStates` / `snapshotOwnership`, then `restoreOwnership` | Saves/copies state and reason; restore resets presence and reinstates snapshot contents. | Save/restore the corresponding evidence beside the proof, including absence, so sibling branches and cleanup copies cannot leave stale reasons or locations. |
| Initial `AllocationInfo.blockingReason` | Starts with `compiler could not prove allocation identity`. | No source cause is implied by that default; use an evidence boundary if needed. |

"Latest" and "first" refer to accepted updates along the analyzed path, not a
global chronological claim about runtime execution or the greatest source line.
Joins can replace those reasons. Detailed join explanations must preserve the
existing decisions; this table does not authorize changing the join algorithm.

Carry a bounded event/relationship identifier and its source into the actual
reason-setting path. Update optional evidence under exactly the same conditions
as the reason, including direct assignments in join and restore code. Do not
maintain an independent "most recent event" cursor or recover the cause by
matching English messages afterward. Same-field stores can have identical reason
strings and different source locations, so string equality cannot skip an
accepted evidence replacement.

`markEscaped` also propagates escape to retained children and known array
elements with different reason text. Match each child's selected reason to that
propagated relationship and the triggering event; do not attach the parent's
message as if it were the child's direct operation. Guard evidence updates per
allocation without changing recursion, visited-set behavior, or propagation.

Other blockers are optional. Include them only when existing final facts and
supported evidence establish that they still block reclamation; label them
separately, for example "this also blocks the free". They must not displace the
selected cause, consume its reserved space, or imply that repairing one event
makes the free safe. Do not add another solver just to enumerate blockers.

## 4. Use cases and required evidence

After completed refinement, each explanation must identify the selected blocker
without claiming that fixing it necessarily resolves every blocker. Preserve the
stabilized primary rejection selection established by section 3.2 and M0. The
first causal notes must explain that exact selected owner, array slot, event, or
predecessor, including the reason-update rules in section 3.5. Ordering notes by
source location must not independently select a different blocker. Additional
blockers may be described only as separately labeled facts, in deterministic
source order.
Do not derive any selection from identity-map iteration order.

| Case | What the developer needs to learn | Evidence to retain or identify |
| --- | --- | --- |
| Local alias, including widened references, casts, or identity-returning calls | Which local still denotes this allocation | The alias-producing binding or latest relevant assignment, not just its declaration; preserve the allocation identity across conversions. |
| Field, static field, or constructor publication | The publication named in the message, normally the latest accepted escape on that path | The selected reason's store or constructor/call evidence and qualified field/type, not automatically the first publication or nearest operation. A general join reason requires join evidence instead. |
| Container or wrapper retention | Which object still borrows the allocation | The retaining receiver and insertion/constructor call. Use a proven current local name, or a type plus creation location if no unambiguous name survives. |
| Known array slot or uncertain index | Which array retains the reference, or why an exact slot cannot be identified | The store, known index where available, and array identity. Do not invent an index after precision is lost. |
| Borrowed helper, iterator, view, or pool-owned item | Why this value cannot be independently freed | Its owner and borrow/acquisition operation; distinguish ownership from borrowing and pool return. |
| Attached owned field or uncertain destructor field ownership | Why a field cannot be freed here | The attached field or failed ownership condition. Do not infer a general recursive-free or transfer contract. |
| Owned-array element cleanup | Which contract prevents the recognized destructor cleanup | Preserve the field primary; identify the actual failed load/store/copy/call contract and the cleanup site, or state the evidence boundary. |
| Pending deferred call | Which saved receiver or argument still observes the allocation | Matching evaluated operand and its registration expression; names describe the value at registration, not a later binding. |
| Pending deferred free | Which bound local already schedules reclamation of this allocation | Resolved local and registration span from the matched action, using the rejecting check's current environment/allocation lookup. |
| Pending yield result | Which pending result still observes the allocation | Existing pending result identity and yield/cleanup boundary, not a deferred-call capture or deferred-free binding. |
| Repeated reclamation | Where the same allocation was already freed or scheduled | Earlier free/action location; replacement of the local with a new allocation must retire the old association. |
| Joined branches, including equal semantic snapshots with different witnesses | What each analyzed incoming alternative records, and why reclamation remains unproved | Labeled predecessor facts captured at the join, including differing escape destinations, escape on one path, and the same field stored at different sites. Preserve equality and primary wording. |
| Loop back edge | Why a later iteration can observe freed, escaped, or different storage | Original allocation, reclamation, and relevant back edge/rebinding; distinguish body-local allocations. |
| Call-mediated escape, including polymorphic dispatch | Which argument/receiver and which possible target blocks proof | Call site first; a bounded callee chain only when supported by final summary evidence. |
| Parameter, mixed identity, unknown factory result, or non-fresh return | Which required ownership fact is missing | Parameter/result binding and an honest analysis-boundary note; no invented allocation or escape site. |
| Throw, catch, return, or closure capture | Which outward use keeps the allocation observable | Throw/return/capture site and retained identity, including enclosing-instance capture where applicable. |
| Source recovered from a class or archive | Where the same blocking event is in the code actually analyzed | Artifact display path and preserved source excerpt, not an assumed local checkout of that library. |
| Refinement skipped after earlier errors | Fix earlier errors before investigating a potentially secondary rejection | Explicit phase readiness; one limited-analysis note and no ownership history, including for library code. |

If evidence is unavailable, stop at a verified boundary for the selected reason.
A note such as
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
The statement span already exists in `reclamations`; what is missing is its
association with the freed state on paths reaching this rejection. See section
5.10 before extending this straight-line example to branches or cleanup copies.

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

The general "conflicting ownership" family needs a different explanation from
maybe-freed state. Section 5.8 covers differing escapes, escape on just one
incoming branch, and equal escape reasons with distinct source locations.

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

### 5.7 A rejected cleanup on one return path

`OneExit.iron` is a complete negative fixture: the static publication blocks
deferred reclamation on the return path, while ordinary completion has no store.

```java
class OneExit {

    static byte[] saved;

    static void example(boolean flag) {

        byte[] data = new byte[16];
        defer free data;
        if (flag) {
            saved = data;
            return;
        }
    }
}
```

Proposed output after completed refinement (excerpts/carets omitted):

```text
error: cannot free 'data': allocation escapes through static field 'OneExit.saved'
  --> OneExit.iron:8:20
note: the reference is stored in static field 'OneExit.saved' here
  --> OneExit.iron:10:21
note: this error is for the cleanup that runs at this return
  --> OneExit.iron:11:13
```

The exit note describes only this rejected cleanup copy. It must not claim that
other exits are safe or have already been checked. For a shared store followed
by calls and a conditional return, the same cause may appear in three errors:
one labeled with that return, one with normal completion of the protected block,
and one with exceptional unwinding. An exceptional copy may represent several
call/throw predecessors; say so instead of selecting one as the definite cause.
The analogous source-written `finally` case follows the same rules.

### 5.8 Joined branches can reject for different reasons

`DifferentFields.iron`:

```java
class DifferentFields {

    static byte[] first;
    static byte[] second;

    static void example(boolean flag) {

        byte[] data = new byte[16];
        if (flag) {
            first = data;
        } else {
            second = data;
        }
        free data;
    }
}
```

Proposed output after completed refinement (excerpts/carets omitted):

```text
error: cannot free 'data': allocation has conflicting ownership across if branches
  --> DifferentFields.iron:14:14
note: when the condition is true, the reference is stored in static field 'DifferentFields.first' here
  --> DifferentFields.iron:10:21
note: when the condition is false, the reference is stored in static field 'DifferentFields.second' here
  --> DifferentFields.iron:12:22
note: both incoming branches store the reference; they differ in the destination field
```

Both incoming snapshots are `ESCAPED`, but their reason strings differ, so the
existing equality check produces `UNCERTAIN` with the general message. Do not
claim that one branch retains exclusive ownership. Storing the reference is not
a transfer-of-ownership contract, and no note should suggest changing the stores
to the same field as a repair.

`OneBranch.iron`:

```java
class OneBranch {

    static byte[] first;

    static void example(boolean flag) {

        byte[] data = new byte[16];
        if (flag) {
            first = data;
        }
        free data;
    }
}
```

Proposed output:

```text
error: cannot free 'data': allocation has conflicting ownership across if branches
  --> OneBranch.iron:11:14
note: when the condition is true, the reference is stored in static field 'OneBranch.first' here
  --> OneBranch.iron:9:21
note: when the condition is false, the analysis records no escape on the incoming path to this join
  --> OneBranch.iron:8:13
```

The false path has no explicit `else`; the note refers to the condition, not an
invented statement. Its incoming state is `ACTIVE`, while the true path is
`ESCAPED`. The wording describes the recorded facts, not a promise that the
false path satisfies every other reclamation requirement.

Two further fixtures in
[FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
complete the comparison:

| Fixture | Primary retained unchanged | Required explanation |
| --- | --- | --- |
| `FieldOrCall`: one branch stores directly, the other calls retaining `keep` | `allocation has conflicting ownership across if branches` | True-path store at 14:21 and false-path call argument at 16:18, with the call labeled according to its final escape summary. M4 may add the supported callee store; M3 must not invent it from the message. |
| `SameField`: both branches store into `first` | `allocation escapes through static field 'SameField.first'` | Two labeled alternatives at 9:21 and 11:21. Equal semantic snapshots retain the field reason, but do not erase either source witness or turn the stores into one sequential history. |

All four remain rejected. Matching branch summaries in `SameField` does not
make reclamation safe. For `FieldOrCall`, prefer "the analysis records escape
on both incoming paths" over a definite runtime-escape claim based only on a
possibly retaining call summary. The primary error's wording alone cannot
justify either an all-path or a one-path explanation.

### 5.9 Deferred-call values and deferred-free bindings

Deferred calls capture evaluated values; deferred frees bind a local whose
writes are forbidden while the action is pending. This distinction follows
[D168](DECISIONS.md#d168---plan-explicit-block-scoped-defer) and the
[operand-capture contract](DEFER_PLAN.md#operand-capture), not a new explanation
policy for the language.

`CallCaptureRejected.iron`:

```java
class CallCaptureRejected {

    static void inspect(byte[] value) {
    }

    static void example() {

        byte[] data = new byte[16];
        byte[] first = data;
        defer inspect(data);
        data = new byte[32];
        free first;
    }
}
```

Proposed output after completed refinement (excerpts/carets omitted):

```text
error: cannot free 'first': allocation is retained by a pending deferred call
  --> CallCaptureRejected.iron:12:14
note: argument 1 of this deferred call captured the allocation here, when 'data' still referred to it
  --> CallCaptureRejected.iron:10:23
```

The outer `inspect` invocation has not run yet. Its argument was evaluated and
captured at registration; the capture is a reference identity, not a copy of
the array. A note saying only "the deferred call uses data" would confuse the
old allocation with the new allocation now denoted by that local.

`PendingFree.iron`:

```java
class PendingFree {

    static void example() {

        byte[] data = new byte[16];
        byte[] alias = data;
        defer free data;
        free alias;
    }
}
```

Proposed output:

```text
error: cannot free 'alias': allocation has a pending deferred free
  --> PendingFree.iron:8:14
note: this deferred free is bound to 'data' and schedules reclamation of the same allocation at block exit
  --> PendingFree.iron:7:20
```

Here the pending action is found by looking up its bound local `data` in the
current ownership environment and comparing allocation identity with `alias`.
It does not contain a saved operand. The diagnostic must use the action/local
that matched that check, not a separate allocation captured for explanations.
Both errors above reject an ordinary free while cleanup is pending, so they
do not receive an executed-cleanup exit label from section 6.3.

The complementary fixtures are preserved in
[CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java):

- `CallCapture` defers inspection of the original 16-byte array, assigns a new
  32-byte array to `data`, and successfully frees that replacement. Use
  `--unfreed=off` for this acceptance control: the original array is intentionally
  unreclaimed. A deferred call does not implicitly free its arguments.
- `FreeBinding` registers `defer free data` and then assigns another array to
  that local. It keeps `cannot assign to or update local 'data' while its
  deferred free is pending` at 7:9, with no rejected-free notes. This write error
  remains outside the option's scope. The restriction protects the resolved
  binding, not the contents of the array or object it denotes.

### 5.10 Earlier-free alternatives and field-proof boundaries

The four fixtures in
[FreeEvidenceBaselineTests](../compiler/src/test/java/ironwood/compiler/FreeEvidenceBaselineTests.java)
preserve the current errors and distinguish facts that still need collection.

| Fixture | Existing rejection | Required explanation |
| --- | --- | --- |
| `TwoPathFree`: both if/else arms free `data`, followed by another free | Already freed, at 11:14 | True-path free at 7:13 and false-path free at 9:13, as alternatives reaching this join. Both exist in `reclamations`; neither is the one unconditional predecessor. |
| `ReturnedFree`: the true arm frees and returns; two frees follow the if | Already freed, at 11:14 | Only the preceding free at 10:9. The recorded free at 7:13 belongs to a path that returned and cannot reach this rejection. |
| `HolderLocal`: constructor stores `input` into `buffer`, then a method loads and frees it through `local` | Unknown allocation identity, at 13:14 | Explain the failed identity proof first. If a field-load association is available, identify `buffer`; do not point to the constructor assignment at 7:9 as a known cause until M4 retains that actual failed freshness predicate. |
| `PairLocal`: `share` stores `first` into `second`; `drop` reads and frees `first` through `local` | Still reachable through private field `first`, at 14:14 | Explain the selected attached-field blocker. Do not replace it with the unused sibling-field reason or imply that the current message names `second`. |

Proposed M3 notes for `TwoPathFree` (primary unchanged, excerpts omitted):

```text
error: cannot free 'data': allocation was already freed
  --> TwoPathFree.iron:11:14
note: when the condition is true, the same allocation was freed here
  --> TwoPathFree.iron:7:13
note: when the condition is false, the same allocation was freed here
  --> TwoPathFree.iron:9:13
```

`ReturnedFree` instead gets only "the same allocation was freed here" at 10:9.
Do not choose the first or latest method-wide reclamation, or print all entries.
Equal `FREED` snapshots can have different witnesses, just as equal escape
reasons can; preserve both as alternatives without changing proof equality.

The field fixtures also have destructor variants in the test. Both currently
report uncertain field ownership. Until M4 has a supported field-proof witness,
use a limitation note such as:

```text
note: the compiler could not prove this class owns 'buffer'; no detailed reason is available
```

This is an analysis limitation, not proof that the field is borrowed or that a
specific constructor write caused the rejection. Use `first` for the corresponding
`PairLocal` destructor note. The skipped-refinement single-note rule still takes
precedence. Removing sharing alone does not justify freeing a still-attached
field through a local: the accepted controls prove freshness and detach it first,
or free the proven field in its destructor.

### 5.11 Summary chains, recursion, and discarded early facts

[FreeSummaryEvidenceTests](../compiler/src/test/java/ironwood/compiler/FreeSummaryEvidenceTests.java)
preserves the supplied `Chain`, `Cycle`, and temporary-borrow `Case` fixtures.
These are proposed M4 notes after completed refinement; primary diagnostics
remain unchanged and source excerpts are omitted.

```text
error: cannot free 'data': allocation escapes through argument 1 of method 'first'
  --> Chain.iron:24:14
note: this call passes the allocation as argument 1 to 'Chain.first'
  --> Chain.iron:23:15
note: 'Chain.first' passes that parameter to 'Chain.second' as argument 1
  --> Chain.iron:7:16
note: 'Chain.second' passes that parameter to 'Chain.third' as argument 1
  --> Chain.iron:12:15
note: 'Chain.third' stores that parameter in static field 'Chain.saved'
  --> Chain.iron:17:17
```

For the retaining recursive cycle, preserve the route to the actual store:

```text
error: cannot free 'data': allocation escapes through argument 1 of method 'ping'
  --> Cycle.iron:39:14
note: this call passes the allocation as argument 1 to 'Cycle.ping'
  --> Cycle.iron:38:14
note: 'Cycle.ping' can pass that parameter to 'Cycle.pong' as argument 1
  --> Cycle.iron:8:18
note: 'Cycle.pong' can store that parameter in static field 'Cycle.saved' when count is zero
  --> Cycle.iron:15:21
```

Do not follow the later `pong`-to-`ping` call indefinitely or imply that every
recursive call publishes. The separate `safePing`/`safePong` cycle has no retaining
operation. `safeExample` must remain accepted without escape witnesses; since the
combined file also contains the rejected caller, test an accepted control that
routes both callers through the safe cycle while retaining the publishing helpers.

The `Case` fixture registers cleanup for a temporary wrapper in `use`. Under
[D170](DECISIONS.md#d170---preserve-confined-temporary-borrowers-across-helper-calls),
refinement proves the caller's `item` need not remain retained by that helper.
It compiles without notes. Its constructor store must not appear as a stale
escape witness merely because an earlier analyzer treated it as retaining.

Add the test's separate `OverrideError.iron` source to skip refinement. Today's
two cleanup rejections for `item` both point at `Case.iron:33:20` and name argument
1 of `use`. With the option enabled, each gets only the section 5.6 limited-analysis
note, no call/store or exit chain. Apply that rule to any secondary library frees
too. The missing-annotation error gets no rejected-free notes. Adding `@Override`
restores acceptance; a variant that actually publishes `item` remains rejected
after completed refinement and receives an ordinary supported explanation.

### 5.12 A library free rejected by an application override

`lib/src/lib/Sink.iron` compiles independently:

```java
package lib;

public class Sink {

    public void accept(byte[] value) {
    }

    public static void use(Sink sink) {

        byte[] data = new byte[16];
        sink.accept(data);
        free data;
    }
}
```

`app/src/app/Keeper.iron` adds a retaining override:

```java
package app;

import lib.Sink;

public class Keeper extends Sink {

    static byte[] kept;

    @Override
    public void accept(byte[] value) {

        kept = value;
    }

    public static void main(String[] args) {

        Sink.use(new Keeper());
    }
}
```

The following current commands compile the library successfully and then reject
the application. Neither command links. `--unfreed=off` isolates the mandatory
reclamation check; it does not disable safety.

```sh
ironwoodc --unfreed=off --source-path lib/src -d lib/classes lib/src/lib/Sink.iron
ironwoodc --unfreed=off --source-path app/src -cp lib/classes \
  -d app/classes app/src/app/Keeper.iron
```

With `--explain-rejected-free`, proposed M4/M5 output for the second command
(paths shortened, excerpts omitted) is:

```text
error: cannot free 'data': allocation escapes through argument 1 of method 'accept'
  --> lib/classes/lib/Sink.ironclass!/source/Sink.iron:12:14
note: this call passes the allocation as argument 1 to 'accept'; one possible target is 'app.Keeper.accept'
  --> lib/classes/lib/Sink.ironclass!/source/Sink.iron:11:21
note: 'app.Keeper.accept' stores that parameter in static field 'app.Keeper.kept'
  --> app/src/app/Keeper.iron:12:16
```

The call/primary are in the dependency, but the retaining store is in the
application. Select `Keeper.accept` from the contributing final dispatch effect
as required by section 6.4, not from a filename guess or the first target in a
combined summary. Both notes retain their own source file and excerpt.
No application classes are emitted on failure. A `Quiet` variant with an empty
override compiles against the same library. These examples intentionally isolate
reclamation diagnostics rather than demonstrate cleanup of the entry-point object.

Use the following legal input matrix in M5, with the option off and on:

| Invocation | Dependency selection | Primary and call-note source | Application-store note source |
| --- | --- | --- | --- |
| Compile Keeper source | `--source-path app/src:lib/src` | `lib/src/lib/Sink.iron` | `app/src/app/Keeper.iron` |
| Compile Keeper source | `-cp lib/classes` | `lib/classes/lib/Sink.ironclass!/source/Sink.iron` | `app/src/app/Keeper.iron` |
| Compile Keeper source | `-cp lib/lib.ironjar` | `lib/lib.ironjar!/lib/Sink.ironclass!/source/Sink.iron` | `app/src/app/Keeper.iron` |
| Link existing Keeper classes | Application classes plus `lib/classes` on `-cp` | `lib/classes/lib/Sink.ironclass!/source/Sink.iron` | `keeper-built/classes/app/Keeper.ironclass!/source/Keeper.iron` |
| Link existing Keeper classes | Application classes plus `lib/lib.ironjar` on `-cp` | `lib/lib.ironjar!/lib/Sink.ironclass!/source/Sink.iron` | `keeper-built/classes/app/Keeper.ironclass!/source/Keeper.iron` |

The colon-separated source-path example is POSIX notation; tests use the platform
path separator. Produce the archive with `ironjar --create --file lib/lib.ironjar
lib/classes` from the successful standalone build. Do not pass `--source-path`
or source files to `--link`; those are existing usage errors. For a source-path
round trip, compile `Quiet` with its source dependency, then link the produced
classes and run it successfully.

For the rejected links, the failed application compilation above cannot supply
Keeper classes. Build them legitimately against an earlier compatible `Sink`
implementation whose `use` allocates/frees its own array without calling
`sink.accept(data)`. Then link those application classes against the independently
compiled original Sink, using a class directory or archive. Both prior builds
pass mandatory checks; the changed composition fails reanalysis before emitting
an executable or requested LLVM output. Keep the earlier Sink off the final
classpath so it cannot shadow the intended dependency. The baseline test uses
this recipe, without forged artifacts or any disabled safety check.

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
Use section 3.4 as the exhaustive emitter checklist for this code baseline,
including explicitly excluded branches. Compound predicates sharing a primary
message need diagnostic-only cause classification in their existing order.
Do not route use-after-free or pool errors into this option merely because they
occur while processing a free or share its allocation identity.

### 6.2 Optional evidence beside the proof

Use a focused explanation collector beside the existing proof. It should retain
only facts needed for the supported notes, such as:

- An allocation's source origin and prior reclamation event.
- A local binding's current allocation and alias-producing location.
- A retaining relationship's receiver, child allocation, and retaining site.
- A publication event, with its source and qualified member/call identity.
- The event or join currently supporting each allocation's selected blocking
  reason, updated under the same guards and transfers as that reason.
- A deferred call's captured receiver/arguments, or a deferred free's resolved
  local and registration site, with relevant predecessor/back-edge context.

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

For `blockingReason`, follow every producer/transfer in section 3.5 rather than
updating evidence at the final diagnostic alone. Reason and evidence must be
logically synchronized, with the evidence update guarded by the opt-in mode and
phase readiness. If the selected event cannot be retained within budget, replace
its evidence with an explicit missing/truncated boundary, not the previous
event's location. An ignored semantic reason update must not change selected
evidence even if its candidate event is closer to the free.

Keep snapshot evidence outside `AllocationStateSnapshot` and the equality of
all semantic snapshots, summaries, and cleanup contexts. When equivalent proof
states came from different locations, keep bounded supporting predecessors
separately and label them as alternatives. Do not make a new ownership conflict
because diagnostic histories differ, and do not pretend equal reason strings
prove a single source event. Restoring a path must restore its reason/evidence
association together; clearing an absent path must prevent stale evidence reuse.

For an accepted free, associate its allocation and existing `Reclamation` span
with the current freed state in the optional collector. Reuse immutable span or
event references where practical; a duplicate always-on free-history list is
unnecessary. The new information is path membership, not discovery of a free's
source location. Keep the existing method-wide `reclamations` list and loop
consumer unchanged, including its ordering and repeated cleanup entries.

Save/restore the association beside ownership snapshots. At a join, retain only
incoming paths that reach it, with distinct witnesses even for equal `FREED`
states; scope cleanup witnesses to their checked copy. Never retrieve a cause
from that list by first/latest matching allocation alone. M1 must already avoid
stale cross-branch evidence; until M3 can render the alternatives, emit an honest
boundary note for unsupported joins rather than an arbitrary earlier-free site.

Deferred-call evidence follows `PreparedInvocation` operand identities, as
consumed by `pendingDeferredOperands`. Preserve the association from a matching
operand to its action, receiver/argument role, and original source expression.
Use source argument numbering, not an index into a flattened operand list that
may also contain the receiver. If a variable name is useful, describe it at
registration; do not resolve that name again after reassignment to identify the
captured allocation. Do not evaluate operand expressions again for diagnostics.

Deferred-free evidence follows `LocalSymbol` identity. **Do not add a saved
value or allocation capture to `DeferredFreeAction` for explanations**, in IR,
or in runtime lowering. Do not build a parallel action-to-captured-allocation
model in the evidence collector either. The pending-free check uses
`allocationOf(environment.get(action.target()))`; cleanup passes
`environment.get(free.target())` into `lowerFreeOperand`. The duplicate
registration check matches the same bound local or its currently resolved
allocation. Notes must follow the actual matched predicate and action.

At the rejection site, freeze the matched action's bound-local description,
registration span, and relevant path context for the immutable diagnostic.
Formatting must not look up the local again after a different branch or cleanup
environment has been restored. This records the successful check's evidence;
it does not introduce another deferred value or ownership decision. Resolve
source names only for display, preserve the current guard/check order, and
allocate no explanation records when disabled or refinement was skipped.

### 6.3 Branches, loops, and duplicated cleanup

Use compact immutable event references across snapshots, rather than copying a
growing history at every branch. Associate evidence with its predecessor and
allocation identity. At a join, select bounded representative causes consistent
with the final blocking state. Evidence exhaustion may shorten notes; it must
never shorten the safety analysis or grant acceptance.

Capture the incoming labels, allocation presence, state, selected reason, and
bounded supporting witnesses while the join still has its inputs. Do not infer
predecessor facts later from the merged `UNCERTAIN` state or its general message.
Preserve the current `AllocationStateSnapshot` comparison, including reason text
and detached state. In the equal-snapshot case, keep distinct source witnesses
as labeled alternatives beside the unchanged proof; `SameField` requires both
stores when the budget permits. This is evidence retention, not a new join rule.

Supply source labels at join callers rather than inventing them from generated
block names. `mergeOwnership` and `mergeFlowOwnership` do not by themselves
know every source construct or the origin of every predecessor.

| Join / caller | Incoming-alternative labels and source anchors |
| --- | --- |
| `lowerIf` | Condition true/false, anchored at that condition and the actual event; preserve the false path when no explicit else exists. |
| Conditional reference expression | Condition true/false for the expression's alternatives; keep it distinct from a statement-level join. |
| Switch dispatch, group entry, or result join | Case/default arm, grouped labels, direct dispatch, fallthrough from a prior group, or yielded result as appropriate. Distinguish an unmatched path when no default exists; do not equate each predecessor with one independent case. |
| `lowerTry` normal continuation | Normal completion of the try body or the identified catch clause/body, after any applicable finally effects. Include only the flows that reach this continuation. |
| `beginExceptionHandler` | Exceptional predecessor or bounded predecessor group in this protected region; identify a possible call/throw site only when retained. Do not label an exception edge as normal catch completion or a guaranteed throw. |
| `mergeFlowOwnership` / `mergeLoopOwnership` | Preserve caller context: loop entry/condition exit, back edge, break/continue, yield, or other continuation. If the label cannot be supported, use an explicit source-region boundary rather than guessing. |

Only discuss predecessors actually admitted by the existing analysis to this
join for this allocation. A branch that exits without reaching this continuation
(for example, by returning) is not an incoming alternative at its later free. An
allocation absent from a predecessor must remain absent in the explanation;
absence is not `ACTIVE`, proof of no escape, or a safe-path verdict. These are
analyzed incoming alternatives, not an enumeration of feasible runtime paths.

Use a small diagnostic-only classification over all contributing input facts
to distinguish escape recorded on every input, on some inputs with no escape
recorded on others, or mixed/unknown facts. Compute it as part of evidence
capture at the existing join, without running another solver or changing
semantic states. `UNCERTAIN`, `MAYBE_FREED`, missing facts, and absent identities
must not be counted as proof of no escape. `ESCAPED` may encode conservative
call effects; distinguish that from a witnessed direct store. Saying that paths
"differ only in how they escape" requires evidence for that narrower claim,
including the other compared facts, not just two `ESCAPED` enum values.

Show all incoming alternatives for the small examples in section 5.8. For a
larger or nested join, retain a deterministic bounded set of representatives,
prioritizing distinct relevant states/reasons and preserving their path labels.
Keep enough capacity for required cleanup-exit and truncation notes within the
eight-note cap. Say that other alternatives were omitted. A summary covering
all inputs is allowed only when the aggregate was computed over all inputs
without missing relevant facts; never derive it from the displayed sample.
If capture was incomplete, say so and omit the all/some claim. Identical text
on different paths must not silently lose its path labels through deduplication.
Do not reconstruct nested paths as an exponential list or combine incompatible
predecessors into one history.

Cover rejections in `validateLoopBackEdges` as well as `lowerFreeOperand`. A
free can initially pass locally and later fail because of another iteration.
For the reclamation error, keep the primary at the source free and show the
blocking back edge in notes. For the companion carried-local error, keep the
primary at its existing flow-block span and point a note back to the causal
free on that predecessor. Do not move either primary or merge the two errors.
If the cause is only a merged may-be-freed state, report that boundary rather
than choosing a free from an incompatible path.

For deferred actions and duplicated `finally` paths, preserve source action
identity, bound locals for deferred frees, and captured receiver/argument values
for deferred calls. Do not mix evidence from normal, exceptional,
returning, or yielding predecessors. Deduplicate notes within each diagnostic;
do not change existing primary error counts/order as a side effect of this work.

After completed refinement, every eligible rejection emitted while checking a
cleanup copy must carry an exit-context note, even when it is the only error at
that source location. This applies to `defer free` and frees inside source-written
`finally`, including an inner deferred-free registration failure encountered
during an outer cleanup. A registration failure in ordinary code is not itself
an executed cleanup copy and must not be labeled as one. Excluded diagnostics
in section 3.4 remain excluded. If refinement was skipped, the single
limited-analysis note in section 6.4 takes precedence; no exit chain is added.

Capture diagnostic-only exit context at the callers of `emitCleanupAction`,
where the entry reason is known. Keep it separate from ownership snapshots,
`FinallyContext` equality, and typed IR. D091 compares cleanup-context lists
to resolve transfer targets, so adding copy-specific fields to that record
would risk changing semantics. Use a scoped side context, restored with the
existing environment/ownership restoration in `finally`, and retain its identity
with deferred evidence such as later loop-reclamation validation. A source span
alone cannot identify a copy; never key exit evidence only by the free's span.

| Cleanup entry | Required exit description and location |
| --- | --- |
| `completeReturnThrough` | This return; use the original return statement span passed through the cleanup chain. |
| Normal completion via `lowerDeferredTail` or `lowerTry` | Normal completion of the protected source block/tail; retain that block's identity/span explicitly. The current helper's span may be the defer declaration, not the block end. |
| Catch completion via catch lowering | Normal completion of this catch body; identify that body rather than the try body's end. |
| `completeTransferThrough` | This `break` or `continue`, including its source label/target where useful; carry the kind from the source transfer, not an invented LLVM block name. |
| `completeYieldThrough` | This `yield`; identify the source transfer and enclosing switch result when needed. |
| `lowerFinallyForPendingException` | Exceptional unwinding of this protected region. If merged, state that the copy combines exceptional predecessors; optional bounded source witnesses must be labeled possible predecessors. |

Normal-completion locations should use the correct protected block boundary
when available, otherwise its source span with block-completion wording. Do not
present the cleanup declaration as the exit, guess a closing-brace location,
or claim every copy corresponds to one concrete runtime path. Calls that are
empty in source may still produce exceptional edges in the current lowering;
explain the analyzed copy, not a guaranteed runtime exception.

For nested cleanup, associate the note with the cleanup action currently being
checked and the transfer/unwind that entered it. If an inner cleanup changes the
transfer (for example, it returns), subsequent outer cleanup uses that new
context. Preserve bounded enclosing context when needed to disambiguate; never
reuse a previous sibling's exit label. A merged exceptional state may prevent
identifying a unique throw site, so region-level wording is the honest result.
This extends the explanation of D090/D091 behavior without changing their proof.

### 6.4 Final analysis and bounded call evidence

Pass explicit diagnostic-only phase readiness from `SemanticAnalyzer` through
final lowering and later owned-element validation to every eligible explanation
site in section 3.4. A small flag or two-value state is sufficient: refinement
completed, or refinement skipped due to earlier errors. Set completion only
after successful convergence of the
existing provisional binding and ownership-refinement phase. Do not infer it
from whether the final diagnostic list contains errors, whether a summary map
is empty, or whether a helper happens to be non-null.

- With the option disabled, preserve current diagnostics and collect no
  explanation evidence, regardless of readiness.
- With the option enabled and refinement completed, collect function-local
  evidence during final lowering and applicable later checks, and emit supported
  explanations using only the final selected analyzers' summary witnesses.
  A later unrelated body error does not retroactively turn this into skipped
  refinement. Completed refinement can still produce conservative results;
  label those honestly.
- With the option enabled and refinement skipped, keep the function-local
  collector absent, expose no summary witnesses, and attach exactly the
  limited-analysis note in section 5.6 to each
  existing rejected reclamation. Apply this to ordinary, deferred, destructor,
  both loop-validation errors, and owned-element validation rejections, including
  those in library sources. Excluded type/name branches remain excluded even
  when their primary wording is shared with an eligible ownership branch.
  Do not mix that note with allocation/alias/escape chains or substitute it for
  the primary error. Unrelated errors receive no rejected-free notes.

This readiness state controls explanation only. It must not permit a `free`,
alter conservative summaries, suppress existing errors, or enter proof equality.
Provisional lowering failures remain non-final; do not collect or publish their
diagnostic history. M4 summary construction uses the separate policy below.
The current nonconvergence path reports its own error and returns before final
lowering, so it must not manufacture rejected-free diagnostics or notes. Adding
notes must not trigger another semantic run automatically.

Call-site notes can use final selected summaries without explaining their
internals. M4 adds a separate optional evidence map owned by each
`EscapeSummaryAnalyzer` instance, including bounded evidence from its existing
`SymbolicReturnOriginAnalyzer` run. Use this approach instead of reconstructing
the final analyzer for diagnostics. Do not add provenance to `EscapeSummary`,
`ReturnSummary`, or their comparison/convergence inputs.

**Collection lifetime.** When the option is enabled and earlier errors have not
already ruled out refinement, eligible summary constructions record bounded
witnesses as part of their existing work. This includes constructions that later
turn out to be provisional. No caller needs to predict which instance will be
last. `SemanticAnalyzer` stops at the start of an outer pass when the proofs are
stable; final lowering consumes the analyzer already stored in `escapeSummaries`,
not a newly built analyzer for that stopping pass. Its optional map is the only
summary-evidence root exposed to final diagnostics.

Discard superseded analyzers' evidence when no longer needed. Do not union maps
across instances by method name or carry a witness forward merely because its
message matches. If refinement is skipped, discard any staged evidence and show
only the limited-analysis notes; if it does not converge, expose no chains.
When earlier errors already establish that refinement will be skipped, do not
enable summary collection in the first place. With the option off, allocate no
summary witness maps/nodes. Budget live evidence across simultaneously retained
analyzers, not independently without a limit; measure total enabled allocations
and time across all rounds, including discarded instances. This explicitly
permits optional summary collection before readiness is known, while preserving
the readiness gate for function-local history and published explanations.

**Discovery and dependencies.** Identify a fact by callable linkage identity,
effect kind, and receiver/parameter/return-origin role, within its analyzer and
analysis phase. Source names are display data. Do not collapse outward escape,
receiver-only retention, and non-return escape into one "parameter escapes" key.
At the operation that first contributes a supported fact, retain its source and
bounded reason: a direct store/other operation, a conservative boundary, or a
call dependency on the precise callee fact used by that transfer. Commit the
witness only if the method's resulting summary contains that fact after its
local adjustments. A later occurrence of the same fact must not overwrite the
first retained derivation with a recursive forwarding call.

Call dependencies refer to immutable witness versions already available when
the fact was derived, not a mutable lookup of "whatever explains this callee
now". A diagnostic-only discovery ordinal can enforce strictly earlier links
within a phase. The analyzer updates summaries during each traversal, so several
links may be learned in one round; round number alone is insufficient. Retain
the dependency's source operand and parameter mapping, including receiver roles
and the actual selected dispatch target. Do not change semantic visitation order
or rerun analysis to find a more attractive witness. If a fact is removed, retire
its association; if it reappears, establish a supported new version.

For `Cycle` in section 5.11, the first retaining derivation leads from `ping`
through `pong` to the store. Do not replace `pong`'s store witness with a back
edge to `ping`. A safe recursive cycle has no escape fact and therefore no escape
witness. First discovery is a cycle-avoidance discipline, not a promise that every
chain ends at a concrete publication: unknown calls, conservative analysis, and
storage exhaustion end at an explicit boundary. A bounded visited-node guard
remains required even with immutable dependencies.

**Final effect fidelity.** Escape rounds are not the last step inside an
analyzer. `withSymbolicReturnSummary` replaces the non-return escape components
and adds return/borrow facts; `applyAuditedBorrowingContract` can remove escape
facts afterward. Instrument the existing symbolic producers/merges separately
for effects whose final source is that analysis, using the same first-discovery
rule. Preserve supported evidence through each transformation, retire evidence
for removed facts, and use a boundary for an unsupported transformed fact. Do
not relabel a raw escape witness as a non-return publication without evidence.

Before rendering a chain, require its root to match the exact final fact consumed
by the rejecting call check. Every dependency must still support the effect
being described after final transformations; a withdrawn or unsupported dependency
terminates with a boundary, not a stale store. Immutable versions prevent loops
but do not by themselves establish this final validity. The Chain/Cycle examples
require supported final non-return escape witnesses from symbolic analysis as
well as source operand locations. Merely instrumenting `markEscaped` in the raw
escape scanner does not complete M4.

The witness must describe the actual effect: receiver retention, outward
publication, return aliasing, and fresh-return ownership are distinct. Preserve
existing pool-release, temporary-borrow, owned-field, and dispatch contracts.
Do not create a second ownership solver for diagnostics or scan a method body
for a possible store and claim it caused the final summary.

Recursive source does not itself require stopping if an acyclic recorded
derivation reaches a supported operation. Stop at repeated witness nodes or hop
and storage limits with an explicit boundary. For `combinedSummary` over several
dispatch targets, record which contributing target supports the exact selected
effect; do not attribute a merged effect to `bound.getFirst()` without evidence.
Label it as a possible target, with a deterministic selection among supported
contributors. Unknown dispatch stays a conservative boundary. No additional
solver or semantic replay is authorized for this selection.

Field ownership needs the same separation: `isOwned` alone does not identify
which write or use defeated the proof. Existing `rejectionReason` strings are
limited and are not source witnesses. M4 may retain bounded evidence at the
actual rejecting check, outside ownership membership and `sameProofsAs`; until
available, state that the field proof failed and no specific source cause was
retained. Do not promise a history of every write or rerun a second field solver.

The field analyzer is recreated during refinement. Only witnesses associated
with the final selected proof may reach diagnostics; provisional records must
not leak into that result. Evidence stays outside `sameProofsAs`, whose existing
comparison covers ownership membership and borrowed-return facts. M4 must cover
reasonless predicate failures as well as the one textual sibling-field reason.

**Do not add diagnostic reasons to the existing `rejectionReasons` map.** It is
not a harmless explanation channel: `trackOwnedFieldLoad` tests for a non-null
reason before registering an allocation identity for an unproved field. Adding
entries could therefore change the proof and primary rejection. Use a separate
opt-in evidence map; preserve existing reason presence and consumers. A local
loaded from a reasonless unproved field may have no `AllocationInfo` at all, so
an explanation needs a separate field-load association, not a fabricated identity.

Track the `PairLocal` dropped reason as a separate existing issue. Its sibling
publication text reaches `trackOwnedFieldLoad`, but `makeUncertain` ignores an
`OWNED_FIELD` origin, leaving the attached-field diagnostic selected. Removing
that guard could affect ownership state and downstream behavior as well as
wording. This plan does not authorize that change. Explain the current selected
blocker first; any later field-proof context must be supported and labeled as
additional context, not a substituted primary reason.

Owned-element cleanup takes a separate route: a recognized destructor loop can
lower directly to `IrDestroyArrayElementsInstruction`, then
`OwnedArrayElementAnalyzer.validate` checks the supporting contract after final
function lowering. Route mode/readiness to this validator explicitly. In M4,
capture the failed predicate, relevant instruction(s), and the recognized
cleanup location while their identities are available. Resolve each instruction
span against its function's source, including reconstructed library sources,
not automatically the field owner's file. If that mapping is unavailable,
report the boundary. Keep this evidence outside typed IR and all proof data.

### 6.5 Artifacts and integrations

Both ordinary compilation and linking load dependencies through `SourceSetLoader`
and analyze their preserved source together with the current inputs. In `Main`,
ordinary compilation calls `pipeline.analyze(loaded.sources())`; linking calls
`pipeline.compile` on those sources with the selected main class. A dependency's
previous successful compilation does not prove its frees safe in every later
application context. Keep reanalysis and mandatory safety checks unchanged.

Build evidence from the loaded sources and retain the paths supplied by the
loader. During compilation, these include source-path files, loose classes such
as `lib/classes/lib/Sink.ironclass!/source/Sink.iron`, and archive paths such as
`lib/lib.ironjar!/lib/Sink.ironclass!/source/Sink.iron`. Do not assume those exact
entry names for every artifact or substitute an available library checkout.
Linking reconstructs class/archive source too, but it rejects `--source-path`
and positional source inputs. Test source-path compilation followed by linking
its produced classes, not an unsupported source-path link command.

Each related note retains its own `SourceFile` and `SourceSpan`. A primary and
call note can be inside a dependency while the next witness is an application
override. Render each excerpt/caret from that note's file, not the primary's
file or the preceding note's file. At link time the application witness may
itself have an artifact display path. Preserve these distinctions even for
identical basenames or overlapping line numbers. M4 dispatch/summary witnesses
must support dependency-to-application edges; M5 verifies them through loading
and CLI rendering. No feature state or explanation history is serialized into
class/archive outputs.

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
  emission for every row in section 3.4, including compound predicate branches,
  late validators, and explicit exclusions. Record missing source witnesses and
  repeated-run instability before implementing richer notes.
- Record the duplicated-cleanup baselines in section 11.5, preserving their
  counts/order and common primary spans. Map each cleanup entry in section 6.3
  to available transfer, block, or exceptional-region source identity.
- Audit every `blockingReason` assignment and its callers against section 3.5.
  Record selected-reason baselines from section 11.6 before adding evidence;
  include ignored updates, identical text from different events, and restores.
- Record all four join fixtures in section 5.8 and the existing if, try/catch,
  exceptional, and general-control-flow messages. Map caller-supplied path
  labels, absent allocations, and non-reaching branches before evidence capture.
- Audit the source-context producers in section 3, distinguishing spans already
  available from missing path or operand-role associations. Record section 5.10's
  earlier-free and field-proof baselines before extending those producers.
- Map summary instances and internal phases through final selection, including
  symbolic-return enrichment and audited borrowing contracts. Record section
  5.11's chain/cycle/refinement baselines and the section 3.1 stopping comparisons.
- Record section 5.12's library/application composition failures during ordinary
  compilation and artifact linking. Map the distinct source identities before
  adding cross-file witnesses; do not treat classpath analysis as link-only.
- Select the fixed storage budget and truncation policy from representative
  workloads; avoid a new public tuning option initially.
- Record unmodified compilation timing and peak memory for the workloads in
  section 9 before implementing tracking.

Exit: reviewed evidence schema and stable baseline expectations, with old
variations and prerequisite changes recorded. Agreement between future modes
alone is insufficient, as is one unchanged-compiler run per input.

### M1. CLI, structured notes, and immediate local explanations

- Add the option, disabled-default pipeline API, help text, and note formatting.
- Wire structured eligibility at all in-scope emitters in section 3.4; use
  explicit boundary notes where richer evidence awaits M2 to M4. Test excluded
  branches of shared messages, not just messages containing the word `free`.
  Until M3 exit context is available, cleanup notes must explicitly acknowledge
  that missing context; do not present otherwise identical cause chains as a
  complete cleanup explanation.
- Carry phase readiness and implement the section 6.4 gate before collecting
  function-local evidence. Test one limited-analysis note per rejected reclamation,
  no chains when refinement was skipped, and no false limited-analysis label after a
  later body error. Cover library and deferred-free diagnostics from the start.
- Implement allocation-origin, local-alias, and earlier-free notes with the
  optional collector. Guard against stale bindings and equivalent conversions.
  Budget for threading initializer/assignment source spans and attaching existing
  free spans to path state, not merely adding a renderer. Preserve associations
  on restore from the start; joins without supported witnesses get a boundary
  note until M3, never a first/latest-list-entry guess.
- Establish the section 3.5 reason/evidence association before exposing stored
  reason explanations; unsupported selected causes get a boundary note.
- Preserve default constructors and shared diagnostic consumers.
- Add focused CLI/formatter tests, on/off parity checks, and golden output for
  the alias and double-free examples.

Exit: useful local notes; no evidence records allocated when disabled; default
messages, safety outcomes, and selected generated IR match the baseline.

### M2. Retaining relationships and immediate escape sites

- Cover field/static stores, constructor escape call sites, known/unknown array
  stores, containers, wrappers, dependent borrows, and attached owned fields.
- Thread operation spans through the section 3 producer inventory, including
  direct escape/uncertainty updates and recursive relationship propagation.
  This is substantial producer plumbing across M1/M2, not a single helper edit.
  Preserve exact argument/receiver locations before invocation lowering loses
  them; per-argument deferred-call associations are completed in M3.
- Identify retaining objects reliably, with type/creation-site fallback.
- Add call-site notes for receiver/argument escape and notes explaining unknown
  identity. Do not yet promise internal callee paths.
- Verify repeated escapes, escape versus uncertainty, and first-uncertainty
  selection in both orders. Notes follow accepted reason updates, not proximity.
  Repeated identical reason text still replaces the selected escape witness.
- Cover pool adoption/return and borrow termination as evidence for rejected
  frees using existing contracts. The wrong-pool transfer error gets no notes.

Exit: retention and escape notes point at the actual operation and object; safe
cleanup after supported borrow termination remains accepted in both modes.

### M3. Deferred actions and control-flow explanations

- Cover pending deferred calls/frees and pending yield observers, including the
  destructor's local pending-call rejection. Detailed whole-class field-proof
  failures remain M4 work; M3 does not claim to explain why field ownership
  could not be proved.
- Distinguish deferred-free registration failures from cleanup execution.
  Cover missing identity, dependent borrow, definitely/maybe-freed state, and
  duplicate registration through aliases. Non-reference/name failures stay out.
- Preserve the section 5.9 distinction: deferred-call notes follow captured
  values, while deferred-free notes follow the matched bound local and existing
  lookup. Test reassignment versus pending-binding protection; add no synthetic
  free capture or second action-to-allocation model.
- Explain the destructor's deferred-call blocker locally. Uncertain field
  ownership keeps an honest proof-boundary note pending M4 evidence.
- Propagate optional evidence through branches, loops, exception paths, and
  duplicated cleanup without participating in proof comparisons.
- Explain the section 5.8 joins with labeled incoming alternatives, including
  both witnesses when semantic snapshots are equal. Test all-input summaries
  separately from bounded displayed witnesses; incomplete evidence gets a
  boundary/truncation note, never an unsupported all-path claim.
- Explain section 5.10's alternative earlier frees and exclude returned paths.
  Preserve distinct witnesses across equal freed states and repeated cleanup
  copies; keep the method-wide reclamation list's existing loop role unchanged.
- Require the exit note for every eligible cleanup-copy rejection after
  completed refinement, using the entry routes in section 6.3. Preserve grouped
  exceptional predecessors and scoped context restoration, including nested
  transfers and later loop validation. Repeat shared causes within the per-error
  note cap; do not merge or reorder primary errors.
- Label uncertainty and alternative paths accurately; handle loop validation
  that rejects a previously lowered free and its carried-local companion.
  Preserve their different primary locations and all existing error counts.
- Test replaced bindings, same-state branches with different source evidence,
  nested cleanup, recursion limits, and deterministic truncation.

Exit: notes survive snapshot/restore/merge correctly; predecessor facts are not
mixed; comparisons and accepted/rejected outcomes remain unchanged.

### M4. Bounded call, field, and owned-element explanations

- Add bounded optional witness maps on each summary analyzer, using the lifetime
  and first-discovery rules in section 6.4. Only the final selected instance may
  supply diagnostics; do not rebuild it or aggregate provisional maps.
- Instrument both raw escape and symbolic-return producers for supported effect
  kinds. Use immutable dependency versions and preserve/remove evidence through
  final summary transformations. Keep all section 3.1 comparisons unchanged;
  verify semantic pass counts as well as results with the option on and off.
- Explain the helper-escape example into its callee, plus a bounded forwarding
  helper chain. Cover a possible retaining dispatch target and an unresolved
  summary boundary.
  Include the dependency-to-application override in section 5.12, retaining a
  separate source file for every hop. M5 tests the loader and CLI combinations.
- Test safe helper extraction versus inline code, fresh returns that also
  publish inputs, and pool-release helpers. Stop cleanly at cycles and limits.
- Require the exact Chain/Cycle links in section 5.11, including the recursive
  route to a store and the accepted safe cycle. Case must discard early retaining
  evidence after temporary-borrow refinement and expose no chain when skipped.
- Add bounded field-proof witnesses only at supported rejecting checks, without
  changing field membership, summary equality, or convergence. Retain the
  boundary note when a specific cause cannot be supported.
  Cover reasonless freshness/use failures and sibling-field sharing in a separate
  optional map, not by extending existing `rejectionReasons`. Preserve final-pass
  association and support field-load provenance without granting new identities.
- Cover all owned-element reason families in section 3.4. Preserve the field
  primary, relate the recognized destructor cleanup and actual failing
  operation, and distinguish the compound distinct-fresh-entry predicates.
  Test source identity across owner/function files and reconstructed artifacts.

Exit: every displayed call hop and field/element witness comes from the actual
final analysis; missing evidence is stated honestly. Record coverage limits
rather than claiming arbitrary whole-program proof reconstruction.

### M5. Cost, artifact compatibility, and documentation completion

- Complete source, loose-class, and archive reconstruction checks, including
  notes whose source is inside dependencies and valid artifact parity.
  Exercise ordinary compilation explicitly, including a library free rejected
  only after adding the application's override. Use section 5.12's legal matrix:
  three compilation forms, class/archive links, and a source-compilation output
  round trip. Assert each note's own dependency/application source identity.
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
| Owned array elements | Distinct fresh entries and supported destructor cleanup | Repeated object, missing freshness, repeated store, publication, or unsupported access/copy from section 3.4 |
| Deferred cleanup | Correct call-value capture, free-local binding, and cleanup ordering | Pending observer, duplicate free, pending-binding write, or escaping return/yield; the write error remains note-free |
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

Make eligibility coverage explicit: each row of section 3.4 needs a test for its
promised milestone. A safety-parity case is not automatically entitled to notes.
In particular, wrong-pool release remains rejected with no related notes, while
an independent rejected free of a pool-owned item receives its own explanation.
Keep parser/name/type errors, primitive specialization guards, use-after-free,
and pending-free reassignment errors unchanged and note-free. Exercise the
specialization guard at the appropriate lower-level boundary if ordinary source
cannot reach it; do not weaken earlier checks to build a CLI fixture.

For selected stored reasons, extend
[FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
with these first-causal-note assertions as M2/M3 evidence becomes available.
The source fixtures are preserved in that test; the note locations below are
requirements for the unimplemented option, not currently emitted output.

| Fixture | Selected primary reason | Required first causal location |
| --- | --- | --- |
| `TwoStores` | Escape through `TwoStores.second` | Line 10, `second = data;`, not the first publication on line 9. |
| `EscapeThenMerge` | Escape through `EscapeThenMerge.saved` | Line 8, `saved = data;`, not the nearer ternary on line 10. |
| `MergeThenArray` | Observation through a merged reference | Line 7, the conditional reference, not the subsequent array store. |
| `ArrayThenMerge` | Observation through an array element on an incoming path | Line 8, the store on that predecessor, with its conditional context; not the subsequent ternary. |

Check both event orders, removing the named blocker to expose the remaining
reason, and accepted controls with all blockers removed. Add repeated stores
to the same field: identical primary text must still select the later accepted
store as the first causal location. Additional independent blockers, if shown,
must follow the selected cause and be labeled separately within the note cap.
Do not promise that repairing the named event makes the free safe.

Exercise ignored uncertainty updates after both `UNCERTAIN` and `ESCAPED`, and
ignored escape updates after `FREED`/`MAYBE_FREED`. Add path restore, repeated
loop/cleanup analysis, equal semantic snapshots with different witness locations,
and a synthetic possibly-freed merged identity. Check that a join-selected
general reason gets join evidence rather than a stale direct-event location.
Snapshot equality and accepted/rejected outcomes must match the baseline even
when the optional evidence differs. Existing missing-evidence, truncation, and
skipped-refinement rules still apply; an unavailable selected witness does not
license substitution of another blocker.

For join explanations in M3, extend the four section 5.8 fixtures with exact
source/path-label assertions while preserving their existing primary messages:

- `DifferentFields`: true at 10:21 and false at 12:22, with both direct-store
  alternatives retained and no claim that either path has exclusive ownership.
- `OneBranch`: true store at 9:21; false-path absence of a recorded escape
  anchored to the condition at 8:13. No invented else statement or general
  safety verdict for the false path.
- `FieldOrCall`: true store at 14:21 and false call argument at 16:18. Preserve
  the distinction between a direct event and a conservative final call effect;
  any M4 callee witness must follow the actual selected summary.
- `SameField`: true at 9:21 and false at 11:21, despite equal semantic snapshots
  and unchanged field-specific primary text. Do not collapse the two stores
  into one unconditional location or turn them into sequential operations.

Pair the failures with controls removing publication. Changing the second store
in `DifferentFields` to the first field still rejects, with the same field-specific
reason as the corresponding `SameField` pattern. A publishing branch that returns
before the continuation must not be labeled as an incoming path to the later
free. Test absence of an allocation on one predecessor separately from `ACTIVE`.

Cover the try/catch, exceptional, and general-control-flow conflict messages,
including normal catch completion versus an exception edge, switch fallthrough,
no-default switch continuation, nested joins, and loop/transfer predecessors.
Assert exact source labels only where the retained evidence supports them.
Add a join exceeding the eight-note limit and one with incomplete captured
evidence: both must disclose missing alternatives, preserve primary parity, and
avoid deriving all-path claims from sampled witnesses. Equal snapshots with
different witnesses must not change proof equality, convergence, or acceptance.

For the compound deferred-free check, test non-reference targets (excluded),
parameters/unknown identity, dependent borrows, definitely and maybe-freed values,
and duplicate registrations on both the same local and distinct aliases. Pair
these with accepted live owned locals and correct cleanup ordering. Assert each
ownership note identifies the condition actually selected by the check.

For the section 5.9 deferred-target fixtures, extend `deferredTargets` in
[CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
with option-off/on comparisons in M3:

- `CallCapture` remains accepted with `--unfreed=off`, without notes and with
  identical generated IR. Capturing the old value must not retain its replacement.
- `CallCaptureRejected` keeps its primary at 12:14 and explains argument 1's
  captured value at 10:23. Do not describe the replacement allocation or imply
  that `inspect` has already run.
- `FreeBinding` keeps its existing assignment error at 7:9 with no related
  notes. Removing the assignment remains an accepted control.
- `PendingFree` keeps its primary at 8:14 and identifies the matched bound local
  `data` and registration at 7:20. Removing the early free remains accepted.

Also compare the same sources under `WARN` and `ERROR` without requiring the
intentionally unreclaimed `CallCapture` fixture to succeed under `ERROR`.
Missing-free diagnostics remain unchanged and separate from these notes.
Test receiver versus argument roles, shadowed local names, alias registration,
and snapshot/cleanup restoration so evidence cannot follow a different binding.

For loops, assert that both errors retain their distinct primary spans/counts:
the carried-local error points back to a supported predecessor free, while the
free-site error points to the blocking back edge. Include a maybe-freed branch
and a body-local allocation control. For destructor fields, pair proved ownership
with unproved parameter storage and pending deferred capture. For owned elements,
test every reason family and compound predicate from section 3.4, with accepted
fresh-entry, borrow, and resize controls as applicable. The repeated-object case
must identify the second store and the earlier store of the same object, while
the primary remains at the field. Do not infer definite duplication from a
failure to prove freshness or non-repetition.

For section 5.10, extend
[FreeEvidenceBaselineTests](../compiler/src/test/java/ironwood/compiler/FreeEvidenceBaselineTests.java)
with option-off/on checks as evidence becomes available:

- M1 must not leak freed-state witnesses from a restored or non-reaching path;
  unsupported joins get a boundary note while retaining exact primary parity.
- M3 requires the two labeled free spans at 7:13 and 9:13 for `TwoPathFree`, and
  only 10:9 for `ReturnedFree`. Neither case may be explained by scanning the
  method-wide reclamation list alone. Repeat with duplicated cleanup and bounded
  truncation without changing loop validation or primary multiplicity.
- Until M4, field destructor variants get the limitation note from section 5.10,
  not invented assignment causes. Ordinary-local variants preserve their distinct
  unknown-identity and attached-field blockers, with honest evidence boundaries.
- M4 must identify the actual non-fresh assignment in `HolderLocal` and the
  sibling-field publication in `PairLocal` from the final field analysis. Keep
  the primary blocker first and label any additional field-proof context. Assert
  exact source spans for the predicate being described; text without a witness
  is insufficient. Do not change reason-map presence or create new proof identities.
- Keep accepted controls for removal of the last free, fresh field storage with
  supported detachment, and proven destructor ownership. All failed analyses
  continue to expose no program or class output.

For section 5.11, extend
[FreeSummaryEvidenceTests](../compiler/src/test/java/ironwood/compiler/FreeSummaryEvidenceTests.java)
in M4 with the exact call/argument/store notes shown there. The existing test
checks current primaries and safe controls only; it does not inspect witness
graphs or implement the option. Required implementation checks include:

- `Chain`: call at 23:15, forwarding arguments at 7:16 and 12:15, terminal
  store value at 17:17, in that order and within the note cap.
- `Cycle`: call at 38:14, forwarding at 8:18, conditional store at 15:21;
  no repeated ping/pong witness loop. Safe recursion has no escape witnesses.
- `Case`: accepted with no notes and unchanged typed IR/LLVM. With the extra
  missing-annotation source, each existing rejected free gets only the limited
  note, including the two Case cleanup copies at 33:20. Annotation repair
  restores acceptance; the actually publishing helper variant remains unsafe.
- Use focused test-only observation of analyzer construction, semantic pass
  counts, and final summary/proof results to check every section 3.1 comparison
  under on/off modes. Do not add production telemetry or treat equal final
  diagnostics alone as proof of unchanged convergence.
- Test a removed/transformed summary fact, unavailable dependency, audited
  borrowing override, and a contributing dispatch target that is not the first
  target in the merged list. No stale or mismatched witness may be printed.
- Repeat with reordered helper declarations and multiple causal operations.
  Exact rounds need not match a different source order, but fixed input must
  produce deterministic evidence and equal semantic rounds across option modes.
- Exercise bounded witness exhaustion across several refinement instances and
  a chain longer than the hop limit. Stop with a boundary without another
  semantic pass, changed acceptance, or a false nonconvergence diagnostic.

For duplicated cleanup, extend the baseline fixtures in
[CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
with explanation assertions in M3:

- `DupCleanup` and `FinallyDup` retain three identical primary errors, in their
  original order and at their original shared spans. Each explanation repeats
  its supported publication cause and identifies its own return, normal, or
  exceptional cleanup entry. The two calls do not imply two exceptional copies.
- Removing both calls and the return leaves one error; keeping just the return
  or just the calls leaves two. Assert labels follow the actual lowered copies,
  not positions guessed from an error count.
- `OneExit` retains one error at the deferred-free target, with store and return
  notes as in section 5.7. Its mirrored normal-exit-only failure gets a normal
  completion label. Removing publication accepts the fixtures without notes.
- Add catch completion, multiple distinct returns, `break`, `continue`, `yield`,
  nested cleanup, and cleanup that replaces a pending transfer. Confirm labels
  are restored between siblings and carried to later validation when needed.
- For merged exceptional predecessors, assert region-level wording and any
  optional possible-predecessor locations without inventing a unique throw site.
  If selected state is a merge conflict, show uncertainty rather than a single
  fictional execution through incompatible predecessors.
- Exercise evidence truncation across several copies of the same source free:
  each has at most eight notes, including its exit and any truncation notice;
  shared causes remain self-contained and no primary error is removed. Repeated
  runs preserve note/primary ordering. Do not claim sibling exits are safe.
- Combine a cleanup failure with skipped refinement: exactly the one
  limited-analysis note remains, with no cause or exit chain. Type/name errors
  inside cleanup retain the exclusions from section 3.4.

Explanation-specific tests must assert exact related files/spans and causal
wording, not just the presence of a `note:` string. Include reassigned container
names, same-line/multiple events, source-name collisions across methods, source
paths with spaces, missing locations, repeated runs, multiple independent errors,
and truncation. Golden output should cover the examples in section 5.

For artifact failures, use fixtures that can legitimately be produced, or the
existing test-level artifact construction facilities. Do not assume rejected
source can first be compiled into a class with safety disabled; no such mode
exists. Test reconstructed source identity and archive paths explicitly.

Use section 5.12 and
[FreeDependencyDiagnosticTests](../compiler/src/test/java/ironwood/compiler/FreeDependencyDiagnosticTests.java)
as the concrete composition fixture. Its current regression checks primary
messages, paths/excerpts, retained source identities, output suppression, and
successful native controls. M4/M5 must add these explanation assertions:

- Run ordinary compilation with source-path, class-directory, and archive
  dependencies under both option modes. Keep the single primary at Sink 12:14;
  enabled notes point to the loaded library argument at 11:21 and the application's
  store value at Keeper 12:16. Render each note's own content and path.
- Repeat rejected links with the valid earlier-built Keeper classes and the
  replacement Sink class directory/archive. The application witness now points
  inside Keeper's artifact, not its original on-disk source. Preserve primary
  counts/order, source excerpts, and absent executable/LLVM output on failure.
- `Quiet` compiles without notes in all three compilation forms under both
  modes; link and run the produced artifacts, including the source-path build.
  Compare successful IR/artifacts using the existing timestamp qualifications.
- Extend a formatter/loader case with identical library/application basenames
  in different paths and spans, so accidentally using the primary source for
  a related note cannot pass. Keep artifact entry spelling supplied by the loader.
- Preserve the usage rejection for `--link` with `--source-path` or source
  inputs. Do not expand CLI input semantics to make an invalid test matrix work.

The skipped-refinement standard-library failures in section 5.6 are another
ordinary-compilation dependency case. They keep only the limited-analysis note;
the real retaining override here receives supported cross-file notes after
completed refinement. Artifact origin alone must not select the limited mode.

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
  --test 'rejected free preserves escape and uncertainty reason selection' \
  --test 'rejected free preserves branch reclamation and field proof boundaries' \
  --test 'rejected free preserves call chains cycles and final borrow refinement' \
  --test 'rejected free distinguishes incoming branch facts without changing join reasons' \
  --test 'safe free distinguishes earlier errors from refined dispatch' \
  --test 'safe free rejects unknown identities and uncertain control flow' \
  --test 'safe free rejects double free and post-free use' \
  --test 'safe free accounts for reference-array element aliases'
```

Dependency diagnostics during compilation and native linking:

```sh
./scripts/test.sh --test 'rejected free in dependencies preserves compile and link source locations'
```

This selection requires the native toolchain and runs the three accepted Quiet
artifact workflows in addition to rejected compile/link checks.

Retention, summaries, and existing library consumers:

```sh
./scripts/test.sh \
  --test 'unfreed diagnostics track receiver-retained allocations' \
  --test 'data structures retain inserted references for safe-free analysis' \
  --test 'private backing arrays are freed only after proven detachment' \
  --test 'temporary constructor helpers preserve mandatory ownership proofs' \
  --test 'borrow dispatch uses exact overloads defaults and receiver flow' \
  --test 'borrow dispatch rejects retaining and unknown receiver flows' \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'pool release helper proofs survive artifact reconstruction'
```

For later owned-element validation, additionally select:

```sh
./scripts/test.sh --test 'creation-array cleanup proves distinct fresh elements'
```

This existing regression checks the supporting cleanup contract; new tests must
also check the proposed field-primary and operation-note locations.

Control-flow and suppression boundaries:

```sh
./scripts/test.sh \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'rejected cleanup frees preserve per-exit diagnostic multiplicity' \
  --test 'deferred calls capture values while deferred free binds locals' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred free enforces local syntax and pending binding writes' \
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
frontend regression. Measure dependency reconstruction during both ordinary
compilation and linking separately where it materially differs.

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
  Include witness construction in all summary/refinement rounds and discarded
  analyzers, not just final lowering or note formatting. Report peak live storage
  separately from cumulative allocation; no extra semantic replay is permitted.
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

### 11.4 Rejection-site inventory review, 2026-09-23

Reviewed the emitters and supporting checks listed in section 3.4 against
`0e7990e`. Six supplied source fixtures were compiled independently through
`bin/ironwoodc --unfreed=off` using Java 21. Every fixture failed without producing
class output:

| Fixture | Observed existing diagnostics and primary locations |
| --- | --- |
| `LoopDemo`: allocation before a loop, free in its body | Carried-local error at 6:9 and repeated-free proof error at 7:13. |
| `DeferDemo`: parameter, already-freed local, two aliases registered | Shared eligibility error at 5:20 and 12:20; duplicate deferred free at 20:20. |
| `Holder`: constructor stores a parameter into the field | Uncertain destructor field ownership at 12:14. |
| `Owner`: destructor defers an observer before freeing its field | Pending deferred-call field rejection at 11:14. |
| Owned-elements fixture: constructor stores one object in two array slots | Distinct-fresh-entry rejection at the field declaration, 6:20. |
| Two-pool fixture: checkout from the first, release to the second | Wrong-pool transfer rejection at the argument, 20:23. |

An additional primitive-local `defer free` fixture reproduced the same primary
eligibility wording as the parameter and already-freed cases. It confirms why
the non-reference predicate must be excluded structurally. The primitive generic
specialization guard was verified in source only, not through a CLI reproducer.

This review changes only this plan: the inventory, scope, evidence routing,
milestones, and future verification requirements. No compiler or test behavior
was changed, and no compiler suite was needed. Document/source references,
registered test names, reason strings, and diff whitespace were checked. Notes
and the option remain unimplemented.

### 11.5 Cleanup-exit review, 2026-09-23

Reviewed cleanup entry routes, exceptional-state merging, and the D090/D091
contracts against `e3c7b90`. The three supplied fixtures were reproduced through
`bin/ironwoodc --unfreed=off` with Java 21: `DupCleanup` produced three identical
rejections at 11:20, `FinallyDup` three at 19:18, and `OneExit` one at 8:20.
All failed without class output. The empty `work` bodies still contribute
exceptional edges in this lowering; the explanation must not claim they are
guaranteed to throw at runtime or assign the merged exception copy to one call.

The new [CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
preserves these messages, counts, and primary locations. For both deferred and
source-written finally cleanup it checks the 1/2/2/3 count progression when
calls/returns are removed or retained. It also covers the one-return failure,
a mirrored normal-exit-only failure, and accepted controls without publication.
Failed analyses expose no typed program or LLVM output.

Verification passed:

- `rejected cleanup frees preserve per-exit diagnostic multiplicity`
- `safe free tracks ownership independently across duplicated finally paths`
- `deferred free preserves ownership across cleanup predecessors`
- License audit, document/source consistency, and diff whitespace checks.

The first run of the new test exposed a test-only comparison between a `Path`
and a string. After correcting that assertion, only the failed test was rerun;
the two existing tests had passed. This review changes the plan and baseline
tests, not production analysis. Exit-note rendering, note-budget enforcement,
and the option itself remain future implementation work.

### 11.6 Selected-reason review, 2026-09-23

Reviewed the reason producers and snapshot transfers in section 3.5 against
`945358d`. All four supplied source fixtures were reproduced independently with
`bin/ironwoodc --unfreed=off` on Java 21. `TwoStores` selected the second field;
`EscapeThenMerge` selected the earlier static publication; `MergeThenArray` and
`ArrayThenMerge` selected their respective first uncertainty. Their primary
locations were 11:14, 11:14, 12:14, and 12:14. Each failed with one error and
without class output.

The new [FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
checks those primary messages and target spans, reversed escape order, escape
after uncertainty, repeated same-field publication, remaining blockers after
the selected one is removed, and accepted controls after all blockers are
removed. Failed analyses expose no typed program or LLVM output.

Three focused tests passed:

- `rejected free preserves escape and uncertainty reason selection`
- `safe free rejects unknown identities and uncertain control flow`
- `safe free accounts for reference-array element aliases`

The license audit, document/source consistency, and diff whitespace checks
passed. This review changes the plan and baseline tests only. Production reason
selection is unchanged. Evidence collection and the proposed first-note
locations are implementation requirements, not verified explanation output.

### 11.7 Incoming-join-facts review, 2026-09-23

Reviewed `lowerIf`, normal try/catch continuation, exceptional-state merging,
switch flow, and `mergeOwnership`/`mergeFlowOwnership` against `d10023d`. The four
section 5.8 fixtures were reproduced through `bin/ironwoodc --unfreed=off` on
Java 21. Each produced one rejection without class output: `DifferentFields`,
`OneBranch`, and `FieldOrCall` used the if-branch conflict reason at 14:14,
11:14, and 18:14 respectively; `SameField` retained its static-field escape
reason at 13:14.

The new `joinedReasons` group in
[FreeReasonSelectionTests](../compiler/src/test/java/ironwood/compiler/FreeReasonSelectionTests.java)
preserves these primary messages and target spans. It also checks that changing
both branches to store into the same field still rejects, accepts controls with
publication removed, and accepts the later free when the publishing branch
returns before reaching the join. Failed analyses expose no typed program or
LLVM output.

Three focused tests passed:

- `rejected free distinguishes incoming branch facts without changing join reasons`
- `rejected free preserves escape and uncertainty reason selection`
- `safe free rejects unknown identities and uncertain control flow`

The license audit, document/source and proposed-location checks, and diff
whitespace checks passed. This review changes the plan and baseline tests only.
Production joins, snapshot equality, and primary diagnostics are unchanged.
The path labels, aggregate descriptions, and note locations remain proposed
M3 behavior; the option is still unimplemented.

### 11.8 Deferred-target review, 2026-09-23

Reviewed deferred-call preparation, pending-operand retention, deferred-free
registration, binding-write protection, and cleanup execution against `d9d2658`,
with D168 and `DEFER_PLAN.md` as the language contract. `DeferredFreeAction`
stores a resolved local and spans, not a captured value; its checks and execution
resolve that local in the current ownership environment.

The four supplied fixtures were reproduced through `bin/ironwoodc --unfreed=off`
on Java 21. `CallCapture` compiled and produced class output. The other three
each produced one error without class output: `CallCaptureRejected` at 12:14,
`FreeBinding` at 7:9, and `PendingFree` at 8:14.

The new `deferredTargets` group in
[CleanupDiagnosticTests](../compiler/src/test/java/ironwood/compiler/CleanupDiagnosticTests.java)
preserves those outcomes, primary messages, and locations, plus accepted controls
removing the binding write or early alias free. Failed analyses expose no typed
program or LLVM output. Three focused tests passed:

- `deferred calls capture values while deferred free binds locals`
- `deferred calls retain captures and mandatory ownership proofs`
- `deferred free enforces local syntax and pending binding writes`

The license audit, document/source and proposed-location checks, and diff
whitespace checks passed. This review changes the plan and baseline tests only;
production compiler behavior and deferred-action representations are unchanged.
The option and exact note assertions remain future implementation work.

### 11.9 Evidence-availability review, 2026-09-23

Reviewed alias binding, invocation arguments, escape/uncertainty producers,
reclamation recording and restore, and field-proof producers/consumers against
`895abad`. Operation spans must be threaded where ownership helpers receive
only operands and reasons. Existing reclamation spans lack path membership.
Field rejection text is mostly absent; its existing map is also consumed by
identity tracking, so new explanation records must remain separate.

All four supplied fixtures were reproduced through `bin/ironwoodc --unfreed=off`
on Java 21. Each produced one error without class output: `TwoPathFree` and
`ReturnedFree` at 11:14, `HolderLocal` at 13:14, and `PairLocal` at 14:14.
Their messages match section 5.10's recorded baselines.

The new [FreeEvidenceBaselineTests](../compiler/src/test/java/ironwood/compiler/FreeEvidenceBaselineTests.java)
preserves those messages and target spans, both field destructor variants, and
accepted controls removing the repeated free or proving field ownership with
appropriate detachment/destructor cleanup. Failed analyses expose no typed
program or LLVM output. Three focused tests passed:

- `rejected free preserves branch reclamation and field proof boundaries`
- `safe free rejects double free and post-free use`
- `private backing arrays are freed only after proven detachment`

License audit, document/source links, registered test names, proposed locations,
and diff whitespace checks passed. Only the plan and baseline tests changed;
production compiler behavior, reclamation recording, reason maps, and ownership
guards remain unchanged. Related-note output and its assertions remain planned.

### 11.10 Summary-witness lifecycle review, 2026-09-23

Reviewed escape rounds, symbolic-return rounds, final summary transformations,
outer refinement, and effect convergence against `bef49fd`. Section 6.4 now
chooses optional per-analyzer maps, first-discovery immutable dependencies, and
final-instance-only consumption, with no extra semantic run. Symbolic-return
effects and audited borrowing overrides need explicit evidence handling; raw
escape witnesses alone cannot explain every final call effect.

Five CLI reproductions used `bin/ironwoodc --unfreed=off` on Java 21. `Chain`
and `Cycle` each produced one error at 24:14 and 39:14 respectively, without
class output. The safe-cycle control and `Case` compiled with class output.
`Case` plus `OverrideError.iron` produced the missing-annotation error, two
identical cleanup rejections at `Case.iron:33:20`, and two existing library
cleanup errors, without class output.

The new [FreeSummaryEvidenceTests](../compiler/src/test/java/ironwood/compiler/FreeSummaryEvidenceTests.java)
preserves these user-code primaries and target spans, accepts removal of the
retaining store and annotation repair, and rejects actual publication by `use`
after completed refinement. Failed analyses expose no typed program or LLVM
output. Three focused tests passed:

- `rejected free preserves call chains cycles and final borrow refinement`
- `safe free distinguishes earlier errors from refined dispatch`
- `temporary constructor helpers preserve mandatory ownership proofs`

License audit, document/source links, registered test names, proposed note
locations, and diff whitespace checks passed. This review changes the plan and
baseline tests only. No production summary, refinement, or diagnostic behavior
changed. Witness graphs, exact notes, and on/off semantic-pass comparisons are
implementation requirements, not verified features of the current compiler.

### 11.11 Dependency-compilation review, 2026-09-23

Reviewed `Main`, `SourceSetLoader`, `IronClass`, and `IronJar` against `f242649`.
Ordinary compilation and linking both analyze reconstructed dependency source;
source-path inputs are available only during compilation. Final linking explicitly
rejects source paths and positional source inputs. Section 5.12 records the legal
matrix and the distinct application/library note identities.

The new [FreeDependencyDiagnosticTests](../compiler/src/test/java/ironwood/compiler/FreeDependencyDiagnosticTests.java)
uses the actual compiler and archive CLI entry points on Java 21. It passed the
focused selection `rejected free in dependencies preserves compile and link source locations`:

- Sink compiles independently and packages with `ironjar --create`.
- Compiling Keeper with source-path, class-directory, and archive dependencies
  produces the same single rejection at the correctly loaded Sink 12:14, with
  its source excerpt and no application class output. Loader checks preserve
  the library and application source identities/content separately.
- Keeper also compiles against the earlier compatible Sink implementation.
  Linking those valid application classes against the original Sink classes or
  archive rejects at the dependency's reconstructed source, with no executable
  or requested LLVM output.
- Quiet compiles, links, and runs with exit status zero and no output in all
  three compilation/artifact workflows, including source-path-produced classes.

License audit, document/source links, test registration, exact example and
proposed-note locations, and diff whitespace checks passed. Production loading,
CLI rules, ownership analysis, and diagnostics are unchanged. The related notes
and their option-off/on comparisons remain planned M4/M5 behavior.
