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
Recorded outputs are findings at named revisions, not frozen expectations for
later milestones. Section 8 uses the base revision of each implementation change.

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

The foundational contracts are D005/D027 (mandatory explicit reclamation proofs),
D083 (destruction), D132/D133 (no added steady-state runtime bookkeeping),
D140/D145 (separate missing-free diagnostics), D168 (defer), and D169
(originating-pool provenance) in [DECISIONS.md](DECISIONS.md), together with
[MEMORY.md](MEMORY.md) and [DEFER_PLAN.md](DEFER_PLAN.md). The following contracts
also govern what an explanation may say:

| Decision | Required interpretation for notes |
| --- | --- |
| [D084](DECISIONS.md#d084---treat-compiler-owned-reusable-helpers-as-dependent-borrows) | Identify the root owner of a reusable helper such as an iterator. Its dependent borrow is not a caller-owned allocation; do not suggest another independent free. Read its later D105/D107 amendments for private pool teardown and releasable item loans. |
| [D090](DECISIONS.md#d090---preserve-ownership-independently-across-duplicated-finally-cleanup) | Explain each mutually exclusive cleanup copy in its own ownership state and exit context, with D091/D168's later transfer/defer support. Do not merge copies into one execution history. |
| [D091](DECISIONS.md#d091---carry-ownership-through-cleanup-transfers-and-loop-back-edges) | Preserve pending-yield observers, possibly-freed state, transfer destinations, and loop-back-edge proofs. Relate each loop rejection to the correct source free or predecessor without moving the primary. |
| [D094](DECISIONS.md#d094---prove-caller-owned-wrapper-borrows-across-cleanup) | A proven constructor-retained private-field borrow ends with accepted destruction of its retaining wrapper, not merely closing it. The child remains caller-owned. D096 supersedes the original blanket dispatch join. |
| [D096](DECISIONS.md#d096---resolve-borrowing-calls-with-typed-receiver-flow) | Use this compilation's final typed receiver-flow targets. Name a supported possible retaining implementation, never an unrelated declared subtype. Unknown inputs or empty flow conservatively admit type-compatible targets; absence of an entry point does not prove a specific call occurs. |
| [D102](DECISIONS.md#d102---make-object-pools-own-their-values-across-checkout) | Checkout lends a pool-owned value; identify the originating pool and distinguish return from deallocation. Successful pool destruction also destroys checked-out values. Apply D103/D104's later scope and return rules, not D102's superseded external-transfer or runtime-policing clauses. |
| [D147](DECISIONS.md#d147---track-proven-receiver-retained-method-borrows) | An exact encapsulated setter/method borrow ends when its retaining receiver is freed; freeing that receiver does not free the caller's child. Other live borrows/aliases can still prevent reclamation. Do not infer this relationship for unknown or exposed receivers. |
| [D170](DECISIONS.md#d170---preserve-confined-temporary-borrowers-across-helper-calls) | Use the final proof of confined temporary borrowing and cleanup/rollback on every required exit. Do not show discarded early retention as a final escape; syntax resembling a temporary wrapper is not sufficient proof. |

This proposal supersedes none of these decisions. Their existing supersessions
still apply, including [D104](DECISIONS.md#d104---record-pool-creations-without-runtime-ownership-policing),
[D105](DECISIONS.md#d105---destroy-private-data-structure-pools-with-their-containers),
and [D107](DECISIONS.md#d107---track-releasable-caller-item-loans-from-local-data-structures).
Container payloads remain borrowed: supported successful `clear()` can end those
loans under D107, whereas closing an ordinary wrapper does not end D094/D147
retention. Returning a checked-out item to its own pool is not object deallocation.

## 2. User-facing contract

### 2.1 Invocation and defaults

Add a boolean `--explain-rejected-free` option to `ironwoodc`. It is disabled by
default and valid for both ordinary source compilation and native linking.
Repeated occurrences are harmless. Accept only the exact bare flag. Recognize
`--explain-rejected-free=<anything>` before the generic unknown-option branch
and reject it with this targeted usage error, including an empty value:

```text
error: --explain-rejected-free does not take a value
```

Follow it with the full usage text on standard error and return status 2, using
the existing usage-error path. Values such as `true`, `false`, and `on` are not
supported; no aliases or separate verbosity levels are proposed initially.
Do not consume the next argument as a flag value: the bare option can precede a
source filename. Other unknown option spellings keep the generic diagnostic.

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

Extend the existing shared compilation/linking sentence in the compact usage
summary, keeping the current usage lines and avoiding a separate option block:

```text
       Both compilation and linking accept --unfreed=off|warn|error (default: warn)
       and --explain-rejected-free (notes on rejected frees; default: off).
```

Preserve the existing `-h`/`--help` convention: print usage on standard error,
leave standard output empty, and return status 2 without an `error:` prefix.
Changing the help stream or exit status is outside this feature.

The option is per invocation, like `--unfreed`. It is not stored in `.ironclass`
or `.ironjar`, and compiling with it does not enable it for a later link. It is
independent of `--unfreed=off|warn|error` and `@SuppressUnfreed`: those controls
cannot suppress or weaken a rejected reclamation or its requested explanation.

### 2.2 What changes in the output

- Keep the existing primary error message, severity, source span, and ordering.
  Add structured `note:` entries underneath that error on standard error.
- A note can carry its own source file, span, source excerpt, and caret. Use the
  existing location style so the primary error remains recognizable.
  Attach notes only to an eligible error with both a primary source and span.
  Print its complete primary block before any note, as specified in section 6.5.
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
diagnostic. Document the option in help and the practical guide
[MEMORY_MANAGEMENT.md](MEMORY_MANAGEMENT.md) instead. Put detailed explanation
rules, current coverage, and limits in [MEMORY.md](MEMORY.md), adjacent to
"Missing-free diagnostics". Section 6.6 assigns these updates to M1 onward.

#### Output format

These conventions are settled before M1 writes golden expectations:

- **Gutters are per location.** Size each primary or note excerpt independently
  from the decimal width of its starting line number, as `DiagnosticFormatter`
  does today. Do not pad line numbers to the widest location in the diagnostic.
  Section 5.2 deliberately uses a three-space gutter at line 14 and a two-space
  gutter at line 7. Adding a note must not reformat the primary block.
- **Note spans identify the event precisely.** Prefer the smallest supported
  single-line span that identifies the event: the relevant argument/receiver or
  assignment operand, the `if` condition for a branch boundary, the `return`
  statement for that exit, or the protected block's closing brace for normal
  completion. A call/defer spread over several lines should use the relevant
  operand's span when that operand fits on one line. Keep primary spans unchanged.
  Choose spans from source-aware producers, not by searching for similar text.
  Preserve a verified original block boundary through cleanup lowering; never
  guess a closing brace from a synthetic tail or defer declaration. If the
  condition, operand, or statement itself spans lines and no smaller truthful
  span is available, retain its full span. If a block-end location is unavailable,
  use the known region with wording that identifies it as a region, as in 6.3.
- **Multiline spans keep the current rendering.** Print the span's first source
  line and one caret at its starting column; do not add continuation excerpts,
  ellipses, or a fabricated single-line span. The structured note retains its
  actual full span. This fallback applies to primary and note locations alike.
- **Golden text uses LF after CRLF normalization.** Keep the formatter's
  `System.lineSeparator()` behavior. Store expected golden text with LF and
  replace CRLF with LF in actual text before comparison. Do not trim, collapse
  whitespace, change gutters, or remove the final newline during normalization.
  `format()` returns no trailing separator; CLI `println` supplies one. Test
  these two layers separately. Retain a direct assertion that formatter-inserted
  separators use `System.lineSeparator()` so normalization cannot hide a change
  to the public renderer's platform behavior. This is a test convention, not a
  claim of Windows support or a relaxation of artifact-byte parity.

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

Apply the section 1 contracts to that relationship, not to the English error
text alone. A dependent helper and a pool checkout share a primary message but
need different owner/return explanations. For a proven D094/D147 edge, a note
may say the borrow ends when the retaining object is freed; do not suggest
`close()` or claim that freeing one receiver resolves every blocker. This is
conditional on accepted owner reclamation and does not transfer or free the child.
Audited D107 container `clear()` has its own successful-continuation rule and
must not be generalized to arbitrary wrappers or methods with the same name.

Fix the output limits as a design decision: at most eight notes per primary error,
including exit, boundary, and truncation notes, and four call-summary hops per
chain. For an eligible cleanup-copy error after completed refinement, reserve
one note for its exit context and, when needed, one for truncation. Keep the
selected reason's supported cause before less useful allocation/history detail.
Deduplicate the same event within an explanation. Indicate omitted detail
explicitly. Changing these output limits requires an explicit decision update
and corresponding golden-output changes, not incidental storage tuning.

Storage limits are separate and provisional in M0. Per-method and per-fact caps
are the primary limits, with local allocation/join and field/checker bounds as
applicable. Unrelated methods do not compete for a shared routine quota. Total
evidence storage may grow with program size. An invocation-wide safety stop
guards exceptional aggregate growth and must be reported when it limits an
explanation; it is not the ordinary witness-selection policy. Workload shape can
justify initial values, but only the enabled collector can establish their real
cost. M1 enforces provisional limits from its first implementation; M3 measures
snapshots/joins/cleanup and M4 measures summary
witnesses, adjusting those limits with recorded evidence. M5 records the resulting
values, units, and measurements. No user tuning option is proposed initially.
Bound collection as well as rendering: a small printed result must not conceal
an unbounded evidence graph. Exhaustion yields an explicit omission/boundary
note without changing the proof, primary diagnostic, or output limits.

The output cap is per diagnostic, not per source line: three errors at one
cleanup site may produce up to 24 notes. Repeat a shared cause in each error's
own explanation rather than saying "see the previous error". Each diagnostic
must stand alone for CLI and IDE consumers. An internal immutable cause may be
shared to save storage; the output must not suppress it across copies. The
method/fact caps and aggregate safety stop still apply; exhaustion must not
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
Section 6.7 defines the nullable test observer and exact counting points that
make this requirement observable without changing the comparisons.

Keep explanation state separate from existing proof comparisons. Do not use this
feature to remove the existing reason from equality or otherwise clean up the
proof algorithm incidentally. Even a seemingly desirable semantic refactor needs
its own justification and paired regressions.

Other consumers to preserve include [IronDoc.java](../compiler/src/main/java/ironwood/compiler/doc/IronDoc.java),
the [language-server analysis engine](../ide/langserver/src/main/java/ironwood/lsp/AnalysisEngine.java),
and the [Eclipse output parser](../ide/eclipse/plugin/src/ironwood/ide/eclipse/CompilerOutputParser.java).
The language server currently uses the default pipeline and translates the
primary diagnostic. Eclipse runs a fixed compiler command and parses textual
primary error locations; neither currently enables explanation collection.
IronDoc does not run ownership analysis: it uses the shared diagnostic formatter
for its own errors. Keep their default behavior unchanged, including exact output
for diagnostics without notes. Section 6.5 defines ordering/location rules and
future IDE questions; no new IDE setting or explanation command is part of this
CLI feature.

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
old variants and record the stabilized selection at a named revision. A remaining
unstable case blocks exact-message parity for that case until separately resolved;
do not silently exclude it from the feature's required coverage.

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

**Stable evidence ordering.** Do not derive selection, note order, join samples,
truncation survivors, or discovery tie-breaks from any collection whose iteration
order is unspecified: identity maps/sets, `HashMap`, `HashSet`, `Set.of`,
`Set.copyOf`, `Map.of`, or `Map.copyOf`. Read-only or immutable does not mean
ordered. A `LinkedHashMap`/`LinkedHashSet` copied from an unordered collection
only preserves that arbitrary order; snapshot restore does not make it stable.
The collection types remain usable for identity lookup and membership, not as
an implicit selection policy.

Use explicit keys with a defined scope and tie-breaks:

- Existing allocation-list index within its callable, and the selected slot
  index where applicable; never object hashes or identity-hash codes.
- Source identity including the complete path/artifact entry, then source start
  and end offsets, event kind, callable/field identity, and operand role when
  needed. An offset or basename alone is not unique across files or events.
- Predecessor labels/ordinals assigned by the source construct's traversal
  (true/false, case/catch order, or the recorded transfer), not the position in
  a restored map/set. Retain separate path identity for duplicated cleanup.
- For M4 facts at the same discovery event, effect kind and receiver/parameter/
  return-origin role, with stable callable/source keys. Section 6.4 preserves
  causal discovery order; a source offset alone is not a dependency ordinal.

Preserve the already-selected primary blocker and existing priority between
distinct reason/state classes before using these tie-breaks. Apply ordering
only among eligible alternatives; a call-to-cause chain keeps its causal order,
not a global source-location sort. Select retained evidence by stable keys before
truncation, not just at rendering: taking the first N items of an unordered set
and sorting them afterward leaves random omissions.
Use bounded selection by stable keys; do not materialize an unbounded sorted
history to meet a determinism test.

Audit each traversal supplying evidence, including ownership snapshots and
summary-origin sets. Keep semantic container contents, equality, visitation,
and pass counts unchanged. Sort/enumerate diagnostic candidates separately and
only when collection is enabled. If an existing unordered semantic traversal
changes the selected reason or first supported derivation and cannot be isolated
this way, record a separate stabilization prerequisite under section 3.2; do not
silently reorder proof work or substitute a different causal event for its note.

| Case | What the developer needs to learn | Evidence to retain or identify | Contracts |
| --- | --- | --- | --- |
| Local alias, including widened references, casts, or identity-returning calls | Which local still denotes this allocation | The alias-producing binding or latest relevant assignment, not just its declaration; preserve the allocation identity across conversions. | D005/D027 |
| Field, static field, or constructor publication | The publication named in the message, normally the latest accepted escape on that path | The selected reason's store or constructor/call evidence and qualified field/type, not automatically the first publication or nearest operation. A general join reason requires join evidence instead. | D005, D094, D147 |
| Container or wrapper retention | Which object still borrows the allocation | The retaining receiver and insertion/constructor call. Use a proven current local name, or a type plus creation location if no unambiguous name survives. | D094, D107, D147, D170 |
| Known array slot or uncertain index | Which array retains the reference, or why an exact slot cannot be identified | The store, known index where available, and array identity. Do not invent an index after precision is lost. | D005, D091 |
| Borrowed helper, iterator, view, or pool-owned item | Why this value cannot be independently freed | Its owner and borrow/acquisition operation; distinguish ownership from borrowing and pool return. | D084, D102/D104, D105, D107, D169 |
| Attached owned field or uncertain destructor field ownership | Why a field cannot be freed here | The attached field or failed ownership condition. Do not infer a general recursive-free or transfer contract. | D083/D084, D094 |
| Owned-array element cleanup | Which contract prevents the recognized destructor cleanup | Preserve the field primary; identify the actual failed load/store/copy/call contract and the cleanup site, or state the evidence boundary. | D104 |
| Pending deferred call | Which saved receiver or argument still observes the allocation | Matching evaluated operand and its registration expression; names describe the value at registration, not a later binding. | D168, D090/D091 |
| Pending deferred free | Which bound local already schedules reclamation of this allocation | Resolved local and registration span from the matched action, using the rejecting check's current environment/allocation lookup. | D168, D090/D091 |
| Pending yield result | Which pending result still observes the allocation | Existing pending result identity and yield/cleanup boundary, not a deferred-call capture or deferred-free binding. | D091 |
| Repeated reclamation | Where the same allocation was already freed or scheduled | Earlier free/action location; replacement of the local with a new allocation must retire the old association. | D005, D090/D091, D168 |
| Joined branches, including equal semantic snapshots with different witnesses | What each analyzed incoming alternative records, and why reclamation remains unproved | Labeled predecessor facts captured at the join, including differing escape destinations, escape on one path, and the same field stored at different sites. Preserve equality and primary wording. | D005, D090/D091 |
| Loop back edge | Why a later iteration can observe freed, escaped, or different storage | Original allocation, reclamation, and relevant back edge/rebinding; distinguish body-local allocations. | D091 |
| Call-mediated escape, including polymorphic dispatch | Which argument/receiver and which possible target blocks proof | Call site first; a bounded callee chain only when supported by final summary evidence. | D096, D169, D170 |
| Parameter, mixed identity, unknown factory result, or non-fresh return | Which required ownership fact is missing | Parameter/result binding and an honest analysis-boundary note; no invented allocation or escape site. | D005/D027 |
| Throw, catch, return, or closure capture | Which outward use keeps the allocation observable | Throw/return/capture site and retained identity, including enclosing-instance capture where applicable. | D005, D090/D091 |
| Source recovered from a class or archive | Where the same blocking event is in the code actually analyzed | Artifact display path and preserved source excerpt, not an assumed local checkout of that library. | D096, D170 |
| Refinement skipped after earlier errors | Fix earlier errors before investigating a potentially secondary rejection | Explicit phase readiness; one limited-analysis note and no ownership history, including for library code. | D096, D170; section 3.3 limitation |

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

Complete excerpts follow section 2.2's per-location gutters and caret rules.
Examples explicitly omitting excerpts/carets are abbreviated illustrations,
not complete golden strings.

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

### 5.13 Owner contracts and compilation-specific dispatch

[FreeOwnershipContractTests](../compiler/src/test/java/ironwood/compiler/FreeOwnershipContractTests.java)
preserves the iterator, pool, setter, and three dispatch variants from this
review. The following are proposed notes; current primaries remain unchanged.

The dependent-borrow message alone cannot distinguish an iterator from a pool
checkout. Use the actual helper/root-owner or originating-pool relationship:

```text
error: cannot free 'it': value is a borrowed helper owned by another object
  --> IteratorFree.iron:10:14
note: 'list' owns the reusable iterator returned here; the caller may not free it independently
  --> IteratorFree.iron:9:31
note: the iterator is reclaimed when 'list' is successfully freed
```

```text
error: cannot free 'item': value is a borrowed helper owned by another object
  --> PooledFree.iron:20:14
note: this checkout lends an object owned by 'pool'
  --> PooledFree.iron:19:23
note: return this checkout with 'pool.release(item)'; return does not destroy the object
note: successful destruction of 'pool' also destroys its checked-out objects
```

Name a local only if it still unambiguously denotes the proved owner; otherwise
use the owner type/creation site. These notes do not tell callers to release
iterators to pools, free checked-out values themselves, or transfer external
objects into a pool. D104 requires return to the same originating pool and does
not imply that duplicate returns are dynamically checked. Missing provenance
gets a boundary note, not a guessed owner based on type or method spelling.

The setter fixture has a proved private-field borrow under D147:

```text
error: cannot free 'value': allocation is still borrowed by a live wrapper
  --> SetterFree.iron:18:14
note: 'holder' retains this caller-owned allocation through its private field 'value'
  --> SetterFree.iron:17:20
note: this borrow ends when 'holder' is successfully freed; freeing 'holder' does not free 'value'
```

Swapping the two frees is accepted in this fixture. Calling an otherwise empty
`holder.close()` before `free value` remains rejected. If another holder retains
the same value, freeing the first does not discharge that other borrow. Report
the selected retaining receiver, not a blanket instruction to reverse cleanup.

For the dispatch fixture, retain the context of this particular compilation:

| Variant | Current outcome | Required M4 wording |
| --- | --- | --- |
| Main calls `use(new Quiet())` and `use(new Keeper())` | One argument-escape rejection at `Main.iron:42:14` | At the call argument 41:21, identify `Keeper.accept` as a possible retaining target. Its store is at 21:16. Do not attribute this failure to `Stash.accept`, which is not in this call's refined target set. |
| Remove the Keeper call, retain both retaining class declarations | Accepted | No notes. Declaring a retaining subtype alone does not make it a target at this call. |
| Remove main | Same primary rejection at 42:14 | Say that no entry point narrows the receiver inputs and that compatible retaining implementations remain possible. `Keeper.accept` and `Stash.accept` are alternatives, not observed runtime calls; any store notes must be labeled with their possible target. |

In the no-main case, removing Keeper's store still rejects because Stash remains
a possible retaining implementation; removing both stores accepts. With main
present, removing only Keeper's store accepts even though Stash still retains.
These controls prevent confusing all declared subtypes with the final call-site
target set. A receiver-flow path back to `use(new Keeper())` on line 48 may be
shown only if actually retained; do not reconstruct it from a name search.

D096's analysis is context-insensitive and conservative: method inputs join
callers, all lowered bodies contribute, and unknown/empty receiver flow can
admit compatible targets even with an entry point. A possible target is not a
guarantee that its call occurs at runtime. Record the actual selection/fallback
context alongside explanation evidence without altering dispatch. D170's final
temporary-borrow proof still takes precedence over discarded early retaining
summaries, as demonstrated in section 5.11.

### 5.14 A user override blocks a bundled-library free

[FreeBundledSourceTests](../compiler/src/test/java/ironwood/compiler/FreeBundledSourceTests.java)
uses `KeepingWriter extends ironwood.io.Writer`. Its `write(char[], int, int)`
stores `buffer` into a static field; its `flush` and `close` overrides are empty.
The pipeline adds the bundled `Writer` source itself. `Writer.write(int)` passes
its private `scalar` buffer through `writeScalar` to the overridable write method,
and its destructor later requests reclamation. The retaining override remains a
possible target through D096's conservative type-compatible receiver fallback,
including when an entry point exists. `BorrowDispatchAnalysis` examines every
lowered body; after receiver propagation, a call with no observed targets is
checked against all type-compatible receivers. Without an entry point, reference
parameters are instead seeded with compatible unknown inputs. Neither case is
evidence that the application actually calls `KeepingWriter.write`.

The entry-point variants are part of the baseline and M4c checks:

| Sources in the compilation | Expected outcome |
| --- | --- |
| Retaining `KeepingWriter`, no `main` | Bundled `Writer.scalar` destructor rejection. |
| Retaining `KeepingWriter`, `main` returning 0 without creating a writer | Same rejection. |
| Retaining `KeepingWriter`, `main` creates a `StringWriter`, calls `write(65)`, and frees it | Same rejection. |
| The same `StringWriter` main without `KeepingWriter` | Accepts. |
| Non-retaining `KeepingWriter` with each of the three entry-point variants | Accepts. |

`StringWriter` overrides `write(int)` itself; using it in `main` does not establish
a receiver for the bundled `Writer.writeScalar` call. Uncalled bodies still
contribute conservative targets. The explanation must follow the final call-site
selection, not infer a missing entry point from the primary error or attach the
application's `StringWriter` call as though it invoked the retaining override.

Current primary and proposed pre-M4 note (artifact prefix is installation-specific):

```text
error: cannot prove destructor free of field 'scalar' safe: field ownership is uncertain
  --> compiler/build/ironwood-stdlib.ironjar!/ironwood/io/Writer.ironclass!/source/Writer.iron:46:14
note: the compiler could not prove this class owns 'scalar'; no detailed reason is available
```

This is a field-proof boundary after completed refinement, not the limited-analysis
note for earlier errors. M4's field/summary witnesses should connect the rejected
field proof through the actual helper/dispatch calls in bundled `Writer` to the
possible `KeepingWriter.write` implementation and its store at
`KeepingWriter.iron:10:16`. Preserve each source identity and final possible-target
qualification. Record whether the relevant call used observed flow, unknown
receiver inputs, or the empty-flow fallback. For a verified empty-flow site, a
note may say "no receiver targets were established for this call; the analysis
includes type-compatible implementations such as 'KeepingWriter.write'".
Do not blame a missing `main`, promise that adding one fixes this rejection, or
claim that a compatible implementation ran. If the fallback provenance or a
supported witness is unavailable, retain the boundary rather than inventing a
path by searching for the user's store or guessing an uncalled method.

Removing `kept = buffer;` accepts the same class and bundled destructor. These
results hold with `--unfreed=off`, `warn`, and `error`; the future explanation
option must add notes to the bundled rejection independently of those settings.
The existing skipped-refinement library errors in section 5.6 instead require
their single limited-analysis note. Both cases test scope without conflating it
with phase readiness.

## 6. Implementation approach

### 6.1 Small option and diagnostic API changes

Pass the explicit mode through `Main` to `CompilerPipeline`, `SemanticAnalyzer`,
and the relevant final analysis. Preserve existing constructor entry points
with the mode disabled. A small boolean or two-value selection is sufficient;
do not introduce a general configuration framework for one feature.

Pass explanation selection independently of `UnfreedMode` and `unfreedSources`.
It applies to every eligible rejection in final analysis, including application,
source-path, class/archive dependency, and pipeline-added bundled-library code.
`CompilerPipeline` supplies the original input-source set for missing-free checks
after adding bundled units; `SemanticAnalyzer.lowerFunctions` uses that set to
filter only those checks. Do not derive explanation enablement from its local
`mode`, `checkUnfreed`, or `withUnfreedChecks` decision. Wire the separate selection
through methods, constructors, destructors, static initializers, and later
validators. Provisional/final phase readiness remains a separate gate.

Extend diagnostics with an immutable list of related notes containing a message
and optional source/span. Preserve existing message/source/span/severity accessors
and convenience constructors. Empty notes preserve existing rendering exactly.
For this feature, notes require an error with both primary source and span;
keep unlocated primaries note-free. Enforce this attachment rule at the common
note-producing boundary and test it explicitly. Never invent a primary location
from a note or turn an unlocated input error into a compiler crash.
`Diagnostic.hasErrors`, `CompilationArtifact.valid()`, and `successful()` continue
to depend on primary severity and output availability, not on note count.

Record this shared API change in the M1 decision and compiler documentation.
Adding a record component changes the canonical constructor and generated
`equals`, `hashCode`, and `toString`; preserving existing overloads/accessors
does not preserve the old record shape. State that full diagnostic equality
includes notes; primary-parity tests compare message/source/span/severity
explicitly. Audit construction and comparison consumers, including
[AnalysisEngine](../ide/langserver/src/main/java/ironwood/lsp/AnalysisEngine.java)
and [IronDoc](../compiler/src/main/java/ironwood/compiler/doc/IronDoc.java).
Keep their default behavior and primary rendering intact; do not use record
`toString` as the CLI format or enable IDE note transport implicitly.

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
- A dependent borrow's root owner and acquisition site, distinguishing a reusable
  helper from a checkout of an object still owned by its originating pool.
- A publication event, with its source and qualified member/call identity.
- The event or join currently supporting each allocation's selected blocking
  reason, updated under the same guards and transfers as that reason.
- A deferred call's captured receiver/arguments, or a deferred free's resolved
  local and registration site, with relevant predecessor/back-edge context.

Key relationships by compiler identities, with source names as presentation data.
Preserve `SourceFile` plus `SourceSpan`, since a span alone does not identify a
file. Reuse immutable source objects; do not copy source text for every event.

When disabled, do not allocate the collector, per-allocation history nodes,
provenance maps, note lists, or snapshot copies solely for explanations. Follow
the nullable `UnfreedAllocationTracker` construction pattern: the function-local
explanation collector remains null unless the option is enabled for final lowering
after completed refinement. Guard every producer/use with a null check, including
reason changes, snapshot/restore/merge, cleanup, and argument construction. Put
allocating expressions and message construction inside the guard, not in arguments
passed to a no-op sink. Saved evidence state uses a shared immutable empty value
when disabled, with no fresh evidence overlay or copied history. Reuse existing
reason strings without extending them with hidden detail. Copy this lifecycle
pattern, not missing-free source eligibility.

Audit all explanation allocation sites and callers, including field validators
and M4 summary witness maps outside `FunctionAnalyzer`, for equivalent guarded
construction. A null collector alone does not prove that a helper did not already
allocate evidence. Test the lifecycle using section 6.7's package-private observer:
off means null, while on plus final/completed analysis creates the collector;
on during provisional lowering or skipped refinement leaves it null. Exercise
snapshot/restore/merge with shared empty evidence while disabled. Do not add a
public debug API or always-on production instrumentation for these tests. The
explicitly allowed nullable observer reports the actual collector field while
the function analyzer is alive; an accessor on an unreachable instance is not
sufficient coverage.

This supports a no-explanation-allocation guarantee by construction. It is not
a literal zero-cost guarantee: guards, added reference fields, and object-layout
effects still need the section 9 comparisons. Profiling can quantify costs, but
absence of sampled allocation events is not proof of absence on every path.

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

**Enforce storage budgets at the producer.** Before constructing or retaining
an event, alternative, snapshot association, path-label chain, or witness edge,
check its applicable method/fact and local provisional limits. At local
exhaustion, stop retaining extra detail for that scope and mark the affected
association as truncated; other methods retain their own budgets. Also check
the invocation-wide emergency limit, as specified below. In either case,
do not first materialize an unbounded list and trim it at rendering. Use a bounded
omission marker and reserve room for required exit/boundary information. Missing
evidence must not preserve a stale source location for a newly selected reason.

Share immutable evidence where practical, but count the whole reachable graph,
including map/list entries, snapshot references, labels, and retained analyzer
roots. Local bounds must include retained versions and associations, not just
the nodes directly stored in one snapshot or analyzer map.
Specify each cap's unit and lifetime; a count of references is not a heap-byte
measurement. Avoid copying entire histories or expanding combinations of paths.
Sharing also must not keep superseded graphs reachable indefinitely. All auxiliary
deduplication, budget accounting, and omission state must have a bounded cost.
The evidence limits do not cap the compiler's AST/proof state or mandatory
primary diagnostics, which may grow with the source; report those separately.
Also account separately for rendered note objects, bounded per primary rather
than by a constant total error count. They must not keep hidden evidence graphs
alive after rendering; producing omission notes must not bypass collection caps.

**Isolate ordinary evidence budgets.** Give each method its own storage allowance
and each summary fact a bounded witness/version allowance, keyed by the existing
callable, effect, and operand-role identities from section 6.4. Field/element
proof evidence uses the corresponding field/checker scope. Standard-library and
dependency methods obey the same local rules; their discovery order must not
spend another method's allowance. Do not divide a fixed total quota among all
methods or prefer user files: the needed cause can be in a dependency.

Charge each shared immutable node to its producing scope and each retained edge
or snapshot association to its holder. Count live retained versions across
analysis phases/instances and retire obsolete roots; resetting a per-method
counter on each pass must not hide still-live graphs. M0b specifies these units
and lifetimes. Below the emergency stop, adding methods outside a rejection's
proof dependencies must not remove its witnesses merely through storage
competition. A method can still exhaust its own allowance; that is a local
limitation, with its own explicit omission reason.

**Invocation-wide safety stop.** Keep aggregate live-storage accounting as a
last-resort bound, sized and measured separately from normal method/fact caps.
On reaching it, latch the stop for this invocation and stop further detailed
collection without changing semantic work or rerunning it. Never evict another
method's witnesses to favor a later-discovered rejection. Retained evidence is
usable only while it still supports the current fact; missing updates become
boundaries, not stale chains. Required exit/boundary bookkeeping remains bounded.

Distinguish this stop from a local cap in affected diagnostics, for example:
"explanation detail omitted because the invocation-wide evidence safety limit
was reached". Use the existing per-error note allowance, not a new warning or
changed status. Test counters and section 9 measurements must record whether
the stop tripped, even when no eligible rejection uses the missing detail;
successful compilation still prints no explanation report. A stop on ordinary
representative workloads requires sizing/design review, not acceptance of silent
loss of detail. Below it, require section 9's unrelated-import stability check;
after it, explicit truncation and unchanged safety outcomes are the contract.

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
Use section 4's stable predecessor/event/allocation keys to choose and order
those representatives, including after `Map.copyOf`/`Set.copyOf` snapshot
restoration. Never use snapshot iteration position as an incoming-path ordinal.
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

Normal-completion locations should use the verified closing-brace span of the
correct protected block when available, otherwise its source span with wording
that identifies normal completion of this region. Follow section 2.2's precise
note-span preference and multiline rendering fallback. Do not
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

Apply the readiness policy below to all analyzed sources, including bundled
standard-library units outside `unfreedSources`. A missing-free tracker being
absent says nothing about eligibility for an explanation. In reduced mode the
function-local collector is absent, yet eligible library rejections still get
the direct limited-analysis note; this emission must not depend on collector
presence. After refinement, section 5.14's bundled field rejection gets the
ordinary field-proof boundary or supported M4 witnesses instead.

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
summary witness maps/nodes. Apply the method/fact limits across live retained
versions and count all simultaneously retained analyzers for the aggregate
safety stop under section 6.2. Retired rounds must not permanently consume a
cumulative invocation allowance. Measure total enabled allocations and time
separately across all rounds, including discarded instances. This explicitly
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
within a phase. Derive its sequence from the existing supported discovery
events and stable evidence enumeration at each event, following section 4.
When one operation contributes several origin/fact candidates from a set,
enumerate the diagnostic candidates by stable role/source keys before assigning
their ordinals; do not inherit a `Set.copyOf` iteration order. Keep this separate
from updates to the semantic origin sets and summaries. Audit the event traversal
itself for stability rather than assuming that an incrementing counter is stable.

An ordinal is a causal sequence, not a source-offset ranking across methods:
a caller can precede its callee in source while depending on a fact learned
earlier from that callee. Stable keys resolve evidence ties; they must not make
a dependency appear discovered before it existed, replace the first supported
derivation with a later preferred one, or change semantic traversal. If that
requires a proof-order change, use the separate prerequisite rule in section 4.
Absolute ordinal values are internal; adding unrelated methods may renumber
them but must not alter the chain or consume another method's evidence allowance.

The analyzer updates summaries during each traversal, so several
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

**Text compatibility rules.** Apply all three rules in M1, including when the
shared formatter is used outside ownership analysis:

1. Attach notes only to eligible errors whose primary has both source and span.
   An unlocated error receives no notes, even if a potential note has a location.
   Do not borrow that location for the primary. Current rejected-free sites are
   located; extending notes to unlocated diagnostics needs a separate consumer
   compatibility decision.
2. Render the complete primary message, its own `-->` line, source excerpt, and
   caret before any `note:` block. Never insert a note between the primary message
   and its location. Every located note then renders its own source block; an
   unlocated note is a plain `note:` line beneath the completed primary block.
3. A diagnostic with no notes renders exactly as today, including located and
   unlocated errors/warnings and platform line separators. IronDoc's only change
   exposure is this shared formatter/API; it has no ownership-analysis mode to
   keep disabled. Do not add one for this feature.

Apply section 2.2's output conventions to every source block: per-location
gutters, unchanged multiline rendering, and platform separators. Select precise
note spans at the evidence producer; the shared formatter must not infer events
or cleanup boundaries from source text.

The Eclipse builder's current command does not enable the option. Nevertheless,
the formatter contract must remain consumable by
[CompilerOutputParser](../ide/eclipse/plugin/src/ironwood/ide/eclipse/CompilerOutputParser.java):
it finalizes a pending error at the first location line, after which note blocks
are ignored. A note before that location, or under an unlocated pending primary,
could instead be merged into the message and supply the primary's location.
The rules above prevent this without changing the parser or enabling notes in
Eclipse. Preserve diagnostic count, order, message, and primary location.

Extend [VerifyCompilerOutput](../ide/eclipse/tools/VerifyCompilerOutput.java)
in M1 to parse actual note-aware `DiagnosticFormatter` output, including a
cross-file note, an unlocated note, LF/CRLF, and adjacent unlocated errors with
no notes. Compare the parsed primaries exactly. The existing proposed-text
fixtures are useful now but cannot detect future renderer drift by themselves.
Run the standalone command documented in `LOCAL_TESTING.md`; no Eclipse
workbench, full plugin build, or language-server packaging is required.

**Future IDE questions, outside this feature.** The language server currently
uses the default pipeline and converts each primary source path with
`DocumentStore.toUri`; enabling explanations is separate work. That work must
resolve both of these before claiming navigable related notes:

- LSP `relatedInformation` requires a location. An unlocated note cannot be
  inserted there as-is, and clients may not support related information. The
  proposed fallback is to append note text to the displayed diagnostic message,
  preserving its severity and primary range; do not invent a range or emit an
  extra error marker. Decide capability handling and avoid duplicate text.
- An artifact display path such as `lib.ironjar!/lib/Sink.ironclass!/source/Sink.iron`
  is not a filesystem source file. Converting it to a `file:` URI does not make
  it editor-openable. This is already an issue for dependency primary diagnostics,
  not one introduced by notes. Choose a supported virtual-document or extracted
  read-only-source mapping, with identity/lifetime handling, before enabling
  cross-file navigation. Until then, retain honest artifact path text rather than
  promising a working link or moving the error to an arbitrary application file.

Keep the structured note data suitable for those later decisions, but do not add
LSP transport, archive extraction, or editor capabilities as part of this feature.

### 6.6 Documentation and decision record travel with behavior

Create the feature's decision in M1b, in the same commit that changes the shared
diagnostic API. M1e later exposes the CLI option after the M1 safety and evidence
gates pass. Use the next available number at M1b; this planning review reserves
no number and does not advertise an implemented
option. Record the chosen name, disabled invocation-local default for compile
and link, structured immutable notes, section 1 invariants, and the shared API
contract in section 6.1, plus section 2.2's output conventions. Notes are
compiler-only evidence: no runtime machinery or class/archive serialization.
The entry supersedes no ownership or reclamation decision. Its status must
distinguish implemented coverage from pending M2 to M5 work, and be updated
in the same commits that deliver each checkpoint. M1b documents the shared API
and formatter in `COMPILER.md` and the decision, explicitly marking CLI support
pending. M1c/M1d document their delivered pipeline capability and coverage; do not
publish the completed-M1 usage paragraph below until M1e. M1e ships the help and
all remaining practical-guide/README rows with the public option.

M1 includes these documentation changes as part of its implementation:

| Location | Content to deliver with M1 |
| --- | --- |
| CLI help | Option spelling and concise purpose, consistent with the invocation-local disabled default. |
| [COMPILER.md](COMPILER.md) near compile/link `--unfreed` guidance | Option contract, `note:` rendering, unchanged primaries/outcomes, current coverage, and the shared `Diagnostic` API/consumer contract. Keep rejection explanations separate from missing-free policy. |
| [MEMORY_MANAGEMENT.md](MEMORY_MANAGEMENT.md) near the `--unfreed` table | Short practical mention of how to request explanations; explicitly say it never makes an unsafe `free` legal. Link to the detailed limits. Do not make it another row of `--unfreed` modes. |
| [MEMORY.md](MEMORY.md) adjacent to "Missing-free diagnostics" | A separate rejected-free explanation subsection covering current supported cases, boundary/limited-analysis notes, exclusions, cleanup limitations, note caps/truncation, and optional compilation cost. Distinguish these diagnostics from reclamation rules. |
| [README.md](../README.md) near its `--unfreed` paragraph | One sentence introducing the option and linking to the practical guide; no claim of complete explanation coverage. |
| [LOCAL_TESTING.md](LOCAL_TESTING.md) | Focused CLI/API/formatter and parity selections for delivered behavior. |
| [DECISIONS.md](DECISIONS.md) | The decision and M1 status described above, with a link to this plan. |

Suggested M1 coverage wording for `COMPILER.md`, to reconcile with the actual
implementation before publishing it:

> Both source compilation and native linking accept `--explain-rejected-free`.
> It adds structured `note:` entries below eligible rejected-free errors. The
> option is off by default and applies only to that invocation. It changes no
> acceptance decision, primary error text, exit status, or generated code.
> This version provides supported local alias, earlier-free, and allocation-origin
> evidence. Eligible rejections whose detailed evidence is not yet supported get
> an explicit boundary note; cleanup exit detail remains limited. When earlier
> errors prevent refinement, each eligible rejection gets only a limited-analysis
> note. Parsing/type errors and other excluded diagnostics keep their existing
> output. See [MEMORY.md](MEMORY.md) for current coverage and limits.

Do not say that all other rejected frees have no notes: M1 already requires
boundary notes and the skipped-refinement gate. Conversely, do not promise
branch alternatives, cleanup exit labels, or callee witnesses before the
corresponding milestone delivers them. M2 to M4 must update coverage and limits
in `COMPILER.md` and `MEMORY.md`, plus the decision's status and this plan, in
their implementation commits. Review the practical guide, README, help, and
test selections each time; edit them if their claims or usage have changed.
M5 checks final consistency and records measurements rather than introducing
the first documentation or creating a second decision for the same feature.

### 6.7 Allowed test observation seam

Add a small package-private `SemanticAnalysisObserver` in
`ironwood.compiler.semantic`, supplied through a package-private
`SemanticAnalyzer` constructor overload. The name is proposed; the visibility,
lifetime, and guards below are requirements. Existing public constructors and
normal CLI/IDE pipeline construction pass null, even when explanations are on.
There is no CLI switch, environment setting, public debug API, global observer,
thread-local registry, or always-present no-op implementation.

Keep observer presence independent of explanation mode: tests need observations
from both enabled and disabled runs. Pass the nullable reference down the normal
analyzer construction paths. Guard each callback and all observation-only
argument construction with `if (observer != null)`. Do not accumulate
observation-only counters, allocate events/instance tokens, copy results, or
build labels before that guard. Accounting required to enforce enabled evidence
budgets remains part of the collector, independent of test observation.
Count events in the test observer rather than adding always-updated production
counters. Existing pass indices may be reported directly. This permits dormant
hooks in production source, not active production telemetry; their null checks
and field/layout cost still fall under section 9's normal-mode measurements.

**Test access through the real pipeline.** Semantic-package tests can call the
constructor directly with parsed units. For pipeline tests, allow a narrow
package-private `SemanticAnalyzerFactory` and constructor overload in
`ironwood.compiler`: a nullable factory receives the pipeline's actual unfreed
mode, source selection, and explanation setting and returns a `SemanticAnalyzer`.
Normal construction leaves that factory null and creates the analyzer directly
as today. A test-only bridge in the semantic package constructs the observed
analyzer; a compiler-package test supplies it through the factory. Any public
bridge needed by the registered test runner lives only under `src/test/java`.
This preserves the real parser, bundled dependency loading, input filtering,
and compile/link preparation; do not duplicate those phases or expose the
package-private observer through a production public type to cross packages.

**Observation points and counting rules.** Deliver these lifecycle/round hooks
in M1c, then add collector/storage events with their implementations. All event
names below are conceptual, not a public protocol:

| Site | Observation and required interpretation |
| --- | --- |
| `SemanticAnalyzer` refinement loop | Emit an entered-iteration event at the top of every iteration, before the stability test can break. Report the outcome and final readiness from existing control flow. Count the terminating stable iteration separately from construction of another summary analyzer; skipped refinement has zero entered iterations. |
| Analyzer construction and selection | Identify each actual escape, symbolic-return, field, and effect analyzer instance and phase. Report which instances final lowering consumes. Use observer-only opaque tokens, not references that keep an analyzer alive; never infer rebuild count from the outer pass index. |
| `EscapeSummaryAnalyzer.analyzeAll` | Count the initial sweep and every repeated sweep, including the final sweep whose comparison terminates the loop. Associate each with its analyzer token. |
| `SymbolicReturnOriginAnalyzer.analyze` and `ClosedWorldEffectAnalyzer.analyze` | Count each entered fixed-point round, including the stable last round, per instance and analysis invocation. Do not count functions visited as rounds. |
| Existing proof comparisons and final selection | Report already-computed decisions and detached immutable semantic projections needed by the tests, excluding witness metadata. Preserve short-circuit evaluation: do not re-evaluate skipped comparisons or call a mutating proof helper just to report it. Field stability is a comparison in outer refinement, not an invented extra field-analysis loop. |
| `lowerFunctions` / configured `FunctionAnalyzer` | After options/readiness are applied and immediately before `analyze()`, report callable identity, lowering phase, readiness, and whether the actual collector field is non-null. Cover static initializers, constructors, destructors, and methods in user, dependency, and bundled sources. Observe completion before discarding the instance; do not report only the configured enable flag. |
| Evidence snapshot/restore/merge and producers | M1d/M3 report actual shared-empty use, evidence copies/construction, and applicable budget counters under the observer guard, including disabled paths. M4 adds actual witness-map presence, instance retirement, and local-cap versus invocation-stop events. These events supplement the producer guard audit; they do not replace it. |

Do not retain mutable analyzer objects, collectors, live summary maps, or graph
roots in callbacks. Tests receive scalars, existing immutable source identities,
and guarded detached projections; observers aggregate bounded fixture data.
Use void callbacks with no control return value. Observation cannot adjust
semantic state, limits, work order, diagnostics, or witness selection. Separate
package-private immutable test budget inputs may force evidence exhaustion as
already planned; the observer must not mutate budgets during callbacks.

**Seam verification.** The tests must demonstrate that hooks ran for the intended
callables/phases, so an empty event list cannot satisfy lifecycle or pass parity.
Check enabled/disabled runs with completed refinement and with skipped refinement,
and a later unrelated body error that preserves completed readiness. A skipped
run has no provisional lowering; assert its absence and the observed final
collector absence. Do not invent impossible phase combinations to fill a matrix.
Failed convergence may stop before final lowering and must be reported as such.
M1c establishes phase observations; M1d requires the actual positive/negative
collector cases; M4 compares instance/round counts and final semantic projections
with witnesses on, off, and forcibly truncated.

Compare an observed run with an otherwise identical null-observer run for primary
diagnostics, typed IR/LLVM where available, and explanation output. Public
constructors and normal CLI/IDE construction must leave both the observer and
factory absent regardless of explanation mode. Verify these defaults and the
guarded producers by package-local tests and
review; do not turn on logging to prove logging is absent. Cost/profile runs use
null observers so test event allocation does not masquerade as feature overhead.
The concrete lifecycle and convergence assertions use the real pipeline through
the test factory when bundled-source loading matters; CLI/artifact parity still
uses the normal observer-free entry points.

## 7. Milestones and exit criteria

M0 is partially prepared; M1 through M5 are unimplemented. Keep these milestone
names stable because the emitter inventory, examples, and tests refer to them.
The lettered checkpoints below define implementation order and review size;
the milestone bullets remain the complete acceptance checklist. No checkpoint
implicitly implements another or authorizes starting implementation from this
planning review.

#### Status and existing preparation

| Work | Status at `3d41fbf` | Remaining gate |
| --- | --- | --- |
| Competing owner/array-slot diagnostic selection | Implemented in `0bb8933`; section 11.2 records focused verification. | M0a audits other candidate selections, including the later element validator; do not repeat or broaden the fix without evidence. |
| Primary-only regression fixtures and consumer baselines | Committed during this review series, including loop primaries in `fec4047`, bundled Writer in `6acd1ae`, and parser fixtures in `4853eab`. Sections 8.2 and 11 identify exact tests and results. | Credit this work in M0a; fill only identified gaps. These tests do not validate the unimplemented option or note collector. |
| Emitter/producer maps, contracts, output rules, and expected notes | Specified in sections 1 through 6 and 8. | Reconcile against current code at M0a; no new broad audit of unchanged paths is needed merely because implementation starts later. |
| Workload measurements, provisional numeric budgets, and evidence schema | Not completed by the planning reviews. | M0b must record measured workload shape, cost baseline, budget units/values, and the proposed bounded structures. |
| Option, structured notes, collectors, and witnesses | Not implemented. | M1 through M5; future tests must inspect actual enabled behavior. |

#### Execution and checkpoint gates

Proceed M0a, M0b, M1a through M1e, M2a through M2d, M3a through M3e,
M4a through M4e, then M5a and M5b. Each checkpoint depends on its predecessor;
the tables also identify dependencies that are easy to overlook. They are
ordered work packages, not a requirement to put all their changes in one commit.
Split a package further by producer or consumer when necessary, keeping each
commit buildable, verified, and explicit about unavailable evidence. Do not
combine a formatter/API change with join or summary instrumentation simply to
finish a milestone in one commit.

Before a checkpoint changes code, record its affected contracts/consumers,
accepted and rejected fixtures, exact section 8.2 test names, and new assertions
from section 8.1. Reuse the named selections already assigned below; do not
defer choosing them until after editing. Newly introduced focused tests must
be registered and documented with their actual names. This follows the linked
pre-change review without running every listed group for every edit.

Every implementation checkpoint must pass these common gates:

- Preserve ordered primary diagnostics, safety outcomes, and selected artifacts
  against the change's explicit base, using M1a's section 8.3 harness. From M1c
  onward also compare enabled/disabled pipeline runs; M1e adds real CLI checks.
  API/formatter-only M1b verifies no-note compatibility and synthetic structured
  notes, without claiming an enabled ownership-analysis test exists yet.
- Test the enabled evidence being delivered, its absence when disabled, exact
  source associations, and applicable cap-exhaustion/fallback behavior. New
  snapshot or summary state must stay outside all section 3.1 comparisons.
  Add cost/accounting checks with each new storage lifetime; M5 is not permission
  to postpone discovering a normal-mode regression.
- Update current coverage, limitations, decision status, and verification records
  in the same change, following section 6.6. Mark a checkpoint complete only
  with its commit and evidence; mark a milestone complete only after every child
  checkpoint and all of its acceptance bullets pass.

Unsupported categories keep their explicit boundary notes. After M1e, each
checkpoint may deliver additional truthful notes without waiting for an entire
later milestone. Do not advertise pending coverage or make disabled collection
run to support it. A new proof change, error suppression, duplicate-error merge,
runtime cost, or expansion of scope requires a separate discussion; it cannot be
hidden inside evidence plumbing. Failed parity, stale witnesses, unbounded
storage, or changed convergence stop progression to the next checkpoint.

#### Review coverage cross-check

This assigns the twenty review items to checkpoints without replacing their
detailed contracts or exact fixtures elsewhere in the plan.

| Review items | Checkpoints responsible |
| --- | --- |
| 1: stable primary selection | M0a credits the existing fix and audits remaining candidates; all later parity gates preserve it. |
| 2, 3, 17: readiness, emitter eligibility, disabled construction, and source scope | M1c covers every emitter and reduced-mode boundary; M1d verifies absent disabled collection; M3b/M3d/M4c/M4d add specialized evidence; M4a/M4e extend the lifecycle audit. |
| 4, 6, 7, 8, 19: cleanup copies, joins, defer semantics, missing source/path facts, and loop/destructor regressions | M0a credits and completes baselines; M1d supplies local path state; M2b preserves operand spans; M3a through M3d supply alternatives, captures, exits, and loops; M4c supplies missing field reasons. |
| 5, 11: selected reasons and ownership contracts | M1d establishes reason association; M2a through M2d add actual events and owners; M3a handles join replacement; M4b uses final dispatch/temporary-borrow facts. |
| 9: interprocedural witness collection and convergence | M4a establishes bounded instance/version lifetimes; M4b verifies actual final chains; M4e measures all rounds and pass-count parity. |
| 10, 16: cross-file dependency causes and artifact parity | M1b/M1c cover per-note sources and dependency eligibility; M4b/M4c test real cross-file chains; M1a establishes exact artifact comparison and M5a completes the legal compile/link matrix. |
| 12, 13, 14: documentation timing, CLI contract, and moving baselines | M1a provides per-change comparisons; M1b records the API decision; M1e ships documented CLI behavior; every later checkpoint updates coverage with its changes. |
| 15: collection bounds versus output limits | M0b selects provisional values; M1d enforces and measures the first collector; M3a/M3e and M4a/M4e test new lifetimes and exhaustion; M5b publishes final values. |
| 18, 20: consumers and output formatting | M1b fixes API/renderer/parser compatibility and full-output tests before producers; M1e verifies CLI output; M2b/M3a/M3c verify precise event/exit spans. Future IDE navigation stays outside this feature. |

### M0. Baseline and evidence boundaries

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M0a. Reconcile existing preparation | Credit the status table and section 11 results. Close remaining emitter/producer, cleanup-entry, reason-selection, summary-instance, and exclusion gaps against current code. Record exact remaining safe/unsafe tests and any separate stabilization prerequisite; do not recreate already committed baselines. |
| M0b. Set the initial storage design | Measure section 9 workload shape and uninstrumented cost; record provisional method/fact and local caps separately from the invocation safety stop, with numeric values, units, evidence ownership, snapshots, truncation, and retirement. Review how later joins and summary instances fit the bounds without implementing them now. No collector work begins with unspecified storage limits. |

- Compile representative rejected inputs repeatedly in separate JVMs before
  changes, starting with the mixed-owner and array-slot cases in section 3.2.
  Record complete primary messages/spans and any variations. Repetition is a
  discovery check, not proof of determinism; inspect the selection policy too.
- Finish and verify the separate diagnostic-stabilization prerequisite. Record
  its reviewed revision and intentional wording selections as dated findings.
  Keep this distinct from explanation implementation.
- Record representative accepted/rejected pairs from section 8 using that
  stabilized compiler, including concise diagnostic text/spans and successful IR.
  These document what M0 observed; later changes compare against their own base
  revision under section 8.3, not against M0's captured output or timing values.
- Pin both `LoopDemo` primaries before adding M3 evidence, using the registered
  `loop back-edge rejections preserve both primary diagnostics` baseline. It
  requires the carried-local error at 6:9 followed by the reclamation error at
  7:13, with exact text, severity, source, count, and order. Breaking immediately
  after the free and allocating within each iteration are accepted controls.
- Record the skipped-refinement case in section 5.6, including library secondary
  errors, its corrected control, a retaining target, and a later body error
  after completed refinement. Map the readiness handoff to final diagnostic
  sites before adding a collector. Track secondary-error suppression separately.
- Map producers, snapshot consumers, proof comparisons, and final diagnostic
  emission for every row in section 3.4, including compound predicate branches,
  late validators, and explicit exclusions. Record missing source witnesses and
  repeated-run instability before implementing richer notes.
- Audit section 4's unspecified-order collections along those producer/consumer
  paths, including immutable snapshot and summary copies. Record stable evidence
  keys and any semantic-order prerequisite before assigning discovery ordinals.
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
- Record section 5.13's helper, pool, wrapper, and dispatch controls against the
  section 1 decisions and their amendments. Map actual relationship kinds and
  final dispatch targets before designing owner names or remedy wording.
- Measure workload shape using the existing compiler: present allocations per
  method, snapshot entries and simultaneously retained snapshots, joins and
  immediate predecessor counts, cleanup copies, and summary/refinement rounds.
  Use temporary diagnostic counters if necessary, kept out of production code;
  record their patch and measurement method. Choose provisional storage limits,
  units, and collection-time truncation policy from these findings. Do not claim
  to have measured collector memory before it exists. Keep section 2.3's output
  limits fixed; section 9 defines the later measurement checkpoints.
- Record unmodified compilation timing and peak memory for the workloads in
  section 9 before implementing tracking.

Exit: reviewed evidence schema and dated findings at explicit revisions, with
old variations and prerequisite changes recorded. Same-build mode agreement
alone is insufficient, as is one unchanged-compiler run per input; later work
also needs the per-change comparison in section 8.3.

### M1. CLI, structured notes, and immediate local explanations

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M1a. Comparison harness | Deliver and document section 8.3 before changing the diagnostic API or analysis. Test expected rejection versus crashes/tool failures, primary changes, artifact/IR changes, and controlled path mapping. It compares option-off builds and does not require the new flag. |
| M1b. Diagnostic API and renderer | Add immutable notes, compatibility constructors, and section 2.2 formatting without ownership producers. Test complete synthetic note blocks, no-note output, and the standalone Eclipse parser with real formatter output; audit LSP/IronDoc consumers. Record the architectural decision and API docs now; CLI support remains pending. |
| M1c. Eligibility and readiness | Thread the disabled-default pipeline setting and phase readiness; wire every in-scope emitter, including late validators, dependencies, and bundled sources. Add section 6.7's nullable observer/factory seam and real phase/round callbacks. Test exact limited-analysis notes versus unsupported-detail boundaries and every exclusion, with no detailed collector yet. This can be several emitter-specific commits; the CLI remains unavailable until all rows are covered. |
| M1d. Bounded local evidence | Add nullable collection, source origins, live alias bindings, selected-reason association, and earlier-free path state. Split producer families into commits. Verify guarded construction, snapshot/restore, unavailable-join boundaries, forced exhaustion, local golden notes, and an initial enabled/disabled cost check. Alternative-path histories remain M3a. |
| M1e. First public CLI delivery | Expose the flag only after M1b through M1d pass. Test help, misuse, duplicate flags, compile/link transport, stream/status parity, and library/reduced-mode output. Publish all section 6.6 user docs with actual M1 coverage and update the existing decision. No rich cleanup or callee claims yet. |

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
- Verify section 6.2's nullable lifecycle and shared-empty snapshot paths with
  section 6.7's observer-backed semantic tests and a complete producer/guard audit.
  Check observed/null-observer parity and keep normal entry points observer-free.
  Wire explanation scope independently of missing-free filtering from the start;
  section 5.14's bundled error must get a boundary note in M1.
- Enforce provisional evidence budgets from the first collector version, with
  test-only counters and a forced-exhaustion case. Count snapshot associations
  and auxiliary storage as well as event nodes; do not wait for M3 to cap them.
- Add focused CLI/formatter tests, on/off parity checks, and golden output for
  the alias and double-free examples, following section 2.2's settled output
  conventions and section 8.1's formatting checks.
  Cover section 2.1's compact help wording on standard error with status 2,
  and value-form rejection through the targeted usage path. Section 8.1 defines
  exact stream/message assertions and accepted bare-flag controls.
- Verify section 6.5's attachment and rendering rules and run the standalone
  Eclipse parser verifier on real formatter output. Add exact no-note formatter
  checks for located/unlocated errors and warnings, protecting IronDoc too.
- Deliver every M1 documentation row in section 6.6: create the decision with
  the M1b shared API change and update it with M1e CLI delivery. Document only
  the evidence available in M1, including boundary notes and limited cleanup detail.
- Deliver a focused comparison script and its usage documentation implementing
  section 8.3. It builds the explicit base revision and current candidate, then
  compares option-off behavior in separate processes. Validate that mismatched
  statuses, diagnostics, and selected IR make the script fail.

Exit: useful local notes; no evidence records allocated when disabled; default
primaries and safety outcomes match the change's base revision with the option
off. Selected generated IR matches that base with the option off, and matches
the current build's option-off IR with the option on. Section 8.3 defines the
parent/base selection and permitted path handling.
Help, authoritative guides, shared API documentation, and the new decision
describe this delivered behavior and explicitly identify pending coverage.

### M2. Retaining relationships and immediate escape sites

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M2a. Direct events and selected reasons | Add field/static/array store and uncertainty locations at the exact accepted reason updates. Verify repeated identical escapes, escape/uncertainty precedence, and restore behavior using the reason-selection group; unavailable join detail remains a boundary until M3a. |
| M2b. Calls and missing identity | Preserve argument/receiver roles and spans, including constructor calls and multiline operands, before lowering loses them. Explain the final local summary effect or missing origin without a callee chain. Pair retaining/non-retaining and fresh/published-result controls. |
| M2c. Retaining owners and attachment | Add selected container/wrapper, dependent-helper, and attached-field relationships with stable owner identity and acquisition sites. Verify multiple owners, name reassignment, and accepted borrow termination; do not expose unproved whole-class failure reasons. |
| M2d. Pool-specific contracts | Distinguish checkout, same-pool return, pool destruction, and borrowed payloads using existing proof relationships. Pair direct/helper release with wrong-pool and independent-free failures; the release error remains note-free. Complete section 5.13's helper/pool/wrapper owner checks; its dispatch witnesses remain M4b. |

- Cover field/static stores, constructor escape call sites, known/unknown array
  stores, containers, wrappers, dependent borrows, and attached owned fields.
- Thread operation spans through the section 3 producer inventory, including
  direct escape/uncertainty updates and recursive relationship propagation.
  This is substantial producer plumbing across M1/M2, not a single helper edit.
  Preserve exact argument/receiver locations before invocation lowering loses
  them; per-argument deferred-call associations are completed in M3.
- Identify retaining objects reliably, with type/creation-site fallback.
  Distinguish D084 helpers from D102/D104 pool checkouts using the proof's
  relationship, not their identical primary message. Explain same-pool return
  separately from destruction. For D094/D147 wrapper edges, describe only that
  edge ending on accepted wrapper free; closing it or ending one of several
  borrows does not establish that freeing the child is safe.
- Add call-site notes for receiver/argument escape and notes explaining unknown
  identity. Do not yet promise internal callee paths.
- Verify repeated escapes, escape versus uncertainty, and first-uncertainty
  selection in both orders. Notes follow accepted reason updates, not proximity.
  Repeated identical reason text still replaces the selected escape witness.
- Cover pool creation/checkout/return and borrow termination as evidence for rejected
  frees using existing contracts. The wrong-pool transfer error gets no notes.
- Update the guides' coverage and decision status for the delivered retaining
  relationships and immediate escape sites; internal callee chains remain pending.

Exit: retention and escape notes point at the actual operation and object; safe
cleanup after supported borrow termination remains accepted in both modes.

### M3. Deferred actions and control-flow explanations

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M3a. Joins and earlier-free alternatives | Build on M1d snapshots and M2 event witnesses. Add labeled if/switch/try/catch/exception/flow alternatives, equal-state distinct witnesses, absent/non-reaching paths, and supported aggregate classifications. Verify sections 5.8/5.10, unchanged equality, and bounded nested/sequential joins before adding cleanup-copy context. |
| M3b. Pending actions and results | Add deferred-free registration/binding, captured deferred-call operands, pending yield, and the destructor's local pending-call blocker. Verify compound checks, reassignment, aliases, and exact roles with the deferred/field selections in section 8.2. No synthetic free capture; no whole-class witnesses. |
| M3c. Cleanup exits | Carry scoped return/normal/catch/transfer/yield/exception entry identity through every section 6.3 cleanup route. Combine M3a paths and M3b actions, including nested replacement of a transfer and verified block-end spans. Extend the cleanup-copy baseline with exact labels, per-error caps, and unchanged primary multiplicity. |
| M3d. Loop back edges | Carry the now-established path and cleanup identity into later reclamation validation. Explain both loop primaries at their existing locations; verify the exact loop/yield selection in section 8.2, including body-local and break controls and maybe-freed predecessors. |
| M3e. Control-flow storage gate | Measure section 9 join/snapshot/cleanup families at the specified sizes, including transient allocations and shared evidence. Force exhaustion, verify deterministic omissions and per-copy output caps, and revise provisional storage values if needed. Record results before starting summary witnesses. |

- Run section 8.2's named loop/yield and destructor selections for the affected
  paths, plus its cleanup-copy group. Extend the loop baseline with notes while
  preserving both primaries and their order; retain the pending-call destructor
  rejection until its captured operand is no longer needed.
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
  nested cleanup, recursion limits, permuted candidate/snapshot collections, and
  deterministic truncation across fresh JVMs under section 4's selection rules.
- Measure enabled-mode retained evidence and cumulative allocation for the
  section 9 join/snapshot/cleanup workloads. Adjust provisional storage caps
  using these results and report sharing versus copying costs. Verify limits
  during collection and primary parity when any cap is exhausted.
- Update the guides' coverage and decision status for deferred actions, labeled
  alternatives, and cleanup exits. Keep whole-class proof limitations explicit.

Exit: notes survive snapshot/restore/merge correctly; predecessor facts are not
mixed; comparisons and accepted/rejected outcomes remain unchanged.

### M4. Bounded call, field, and owned-element explanations

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M4a. Summary witness lifecycle | Add bounded analyzer-owned maps and immutable first-discovery dependencies for raw escape and symbolic-return facts. Preserve evidence through final transformations and retire superseded instances. Test internal fact/witness consistency, cycles, all stopping comparisons, disabled maps, and isolated method/fact exhaustion versus the reported aggregate safety stop. Do not expose call chains until M4b validates final selection. |
| M4b. Final call and dispatch chains | Render only final supported facts, with the four-hop/eight-note limits. Complete Chain/Cycle/Case, D096 targets, D170 refinement, helper extraction/pool-release controls, and dependency-to-application spans. Add section 9's unrelated-import note-stability test and run an actual classpath composition check now; the full loader matrix remains M5a. |
| M4c. Whole-class field failures | Separately instrument supported field-rejection predicates with final-instance association and field-load provenance. Verify HolderLocal/PairLocal and bundled Writer-to-user-override evidence, including section 5.14's entry-point variants and actual receiver fallback, using M4b call witnesses when needed. Keep `rejectionReasons`, membership, identities, convergence, and unsupported-cause boundaries unchanged. |
| M4d. Owned-array element failures | Treat the later validator as a separate consumer. Cover every section 3.4 reason family and distinct-entry predicate with its actual failed operation and recognized cleanup; preserve field primaries and one-reason-per-checker behavior. Pair fresh-entry/borrow/resize controls and check artifact source identity. |
| M4e. Whole-program storage gate | Measure all summary/refinement rounds and simultaneously retained analyzers with section 9 chains/recursion. Recheck pass counts, optional-producer guards, retirement, method/fact caps, and the separate invocation safety stop after both field and element producers are present. Require unrelated-import note stability below that stop, and explicit reporting when forced. Record coverage gaps and costs before final integration. |

- Run section 8.2's destructor/owned-field selection for field-proof evidence,
  retaining uncertain-factory/containment rejections and D180 receiver safety.
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
- Apply section 5.13's D096 controls to final possible targets. Preserve the
  reason for conservative target fallback when available, including unknown
  entry inputs without an entry point; never describe a compatible target as
  an observed call. D170 temporary-borrow facts and their witnesses must come
  from the final refined summaries, not an earlier retaining approximation.
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
- Update the guides' coverage and decision status for supported call/field/element
  witnesses, cross-file notes, and remaining summary or truncation boundaries.
- Measure witness storage across all summary/refinement rounds, including
  simultaneously retained and superseded analyzers, using section 9's forwarding
  and recursive controls. Revisit provisional caps with real graph/edge costs;
  keep output limits and proof pass counts unchanged.
- Extend the guard audit to every optional summary/field witness producer, and
  add section 5.14's bundled-field-to-user-override chain using final evidence.

Exit: every displayed call hop and field/element witness comes from the actual
final analysis; missing evidence is stated honestly. Record coverage limits
rather than claiming arbitrary whole-program proof reconstruction.

### M5. Cost, artifact compatibility, and final documentation audit

| Checkpoint | Bounded work and completion evidence |
| --- | --- |
| M5a. Full artifact and CLI integration | Complete section 5.12's legal source/class/archive compile/link matrix, cross-file rendering, identical-basename cases, valid byte-identical class/archive/IR outputs, and the small native behavior/reclamation/trace controls. Earlier checkpoints already exercise affected consumers; this expands the matrix rather than discovering source reconstruction for the first time. |
| M5b. Final cost and coverage sign-off | Consolidate M0/M1/M3/M4 measurements, run the final focused base/off/on cost comparison, and publish numeric storage limits and known boundaries. Audit all milestone bullets, emitter/reason rows, current docs, and the single decision record. An untested promised category remains open; a supported explicit limitation is recorded as such, not silently dropped. |

- Complete source, loose-class, and archive reconstruction checks, including
  notes whose source is inside dependencies and valid artifact parity.
  Apply section 8.4's byte comparisons and native checks; do not weaken archive
  equality with a timestamp allowance or require whole-executable byte equality.
  Exercise ordinary compilation explicitly, including a library free rejected
  only after adding the application's override. Use section 5.12's legal matrix:
  three compilation forms, class/archive links, and a source-compilation output
  round trip. Assert each note's own dependency/application source identity.
- Run the focused performance comparison and disabled-allocation inspection.
  Resolve repeatable normal-mode regressions before claiming the feature ready.
- Record final storage limits and units, their scopes/lifetimes, the M3/M4
  measurements that justify them, and observed truncation coverage. Include
  per-allocation/join/method/fact accounting and the separate aggregate safety
  stop, including whether it tripped. M5 consolidates
  measured choices; it is not the first point at which limits are enforced.
- Finish applicable CLI/API integration checks. Audit the already-published
  section 6.6 documentation, help, and relevant IDK option references against
  delivered behavior and measurements; correct stale coverage or cost claims.
- Finalize the existing M1 decision's status and boundaries. Do not postpone
  recording the shared architecture until this milestone or create a duplicate
  decision. No change to accepted ownership/reclamation rules is intended.
- Update this plan with measured results, completed milestones, and exact
  remaining limitations. Do not mark unsupported categories complete.

Exit: all preceding milestone criteria hold, default and explained compilation
remain semantically equivalent, and measured costs are documented. This does
not authorize another memory-management feature or an unfiltered test run.

## 8. Focused verification and affected contracts

This selection follows the [regression lessons and pre-change review](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes).
Revisit it if implementation touches additional proof producers or consumers.

### 8.1 Required comparisons

Keep historical M0 findings separate from per-change verification. For the
section 3.2 prerequisite, preserve acceptance, primary severity/span, and valid
generated code; record the intentional choice among previously varying messages.
Do not require one arbitrary historical message to match the stable choice.

Use three distinct checks with different jobs:

1. Registered tests compare the current build with the option off and on, using
   separate pipeline instances and otherwise identical inputs/options. Compare
   ordered primary projections: message, source identity, full span, and severity,
   excluding notes. Assert the same acceptance, exit status where exercised,
   output availability, and successful typed IR/LLVM. Check exact note text,
   locations, and ordering against deliberately maintained expectations. Do not
   compare full `Diagnostic` records for primary parity once they carry notes.
2. The section 8.3 procedure compares the base commit of each implementation
   change with its candidate, both with explanations disabled. This catches
   regressions shared by the candidate's on and off modes. It uses two prebuilt
   compilers in separate processes; `scripts/test.sh` normally builds and loads
   only the current revision and does not perform this historical comparison.
3. M0 and section 11 retain dated findings about instability, duplicate counts,
   and reason selection. They are investigation records, not immutable golden
   IR or output files that unrelated future compiler changes must reproduce.

For matching existing options, require identical ordered primaries and safety
outcomes across the appropriate pair. Notes are the only intended diagnostic
difference between current-build modes. Intentional help/API additions have
their own M1 tests rather than an impossible old/new exact-help comparison.
Repeat determinism checks across fresh JVMs, including competing-blocker inputs.
Assert that notes refer to the owner/slot selected by the primary diagnostic;
independently sorted but mismatched evidence is a failure.
Compare successful typed IR/LLVM directly, subject only to section 8.3's declared
path handling for separate builds. Do not normalize away explanation metadata
to make the comparison pass; it must not be present there at all.

Use `UnfreedMode.OFF`, `WARN`, and `ERROR`, and a suppressed-allocation negative
case. Verify valid inputs emit no explanation text, unrelated errors retain
their formatting, failed commands emit no new class/native outputs, and malformed
input still produces diagnostics rather than crashing.

M1 full-output formatter tests must cover a primary and notes at line numbers
with different digit counts (including a cross-file note), unchanged primary
gutters, single-line caret widths, and a multiline span rendered as its first
line with one caret. Compare complete blocks with only section 2.2's CRLF-to-LF
normalization; check final-newline behavior separately for formatter and CLI.
Exercise LF and CRLF inputs to the comparison helper and assert that differences
in indentation, carets, or trailing newlines still fail. Keep a raw platform-
separator assertion and the existing no-note compatibility checks.

As M2/M3 add producers, verify exact argument spans in multiline calls/defer,
condition spans for branch notes, return-exit spans, and the original protected
block's closing brace for normal exits. Include a multiline condition and an
unavailable block boundary to exercise truthful region/fallback rendering.
These are future explanation tests; today's fragment-only formatter test does
not establish this coverage.

M1 CLI tests must use the actual `Main.run` parser and separate output streams:

- `--help` and `-h`: status 2, empty stdout, no `error:` prefix, and both shared
  option lines from section 2.1 in stderr alongside the existing usage summary.
- `--explain-rejected-free=true`, `=false`, `=on`, `=`, and an arbitrary value:
  status 2, empty stdout, exact first stderr line
  `error: --explain-rejected-free does not take a value`, then full usage.
  Do not accept the generic unknown-option message for these forms. Exercise
  source and link invocations without an earlier terminating `--help` option.
- An unknown spelling such as `--explain-rejected-frees` keeps the existing
  `unknown option` usage error; the parser must not treat every prefix match
  as the supported flag. No malformed-option invocation creates output artifacts.
- Accepted source and link controls allow the bare flag and repeated bare
  occurrences. A following source filename remains positional. Compare duplicate
  flags with a single flag; neither changes rejection counts or note duplication.

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
Snapshot equality must match across current modes even when optional evidence
differs. Accepted/rejected outcomes must also match the per-change comparison.
Existing missing-evidence, truncation, and
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
- Use section 6.7's package-private nullable observer to capture actual analyzer
  construction, entered rounds/iterations, final selection, and detached
  summary/proof results for every section 3.1 comparison under on/off modes.
  Require nonempty expected phase events and matching counts/projections, including
  the stable last iteration. No active production telemetry is permitted; the
  guarded test seam is explicitly allowed. Equal final diagnostics alone do not
  prove unchanged convergence.
- Test a removed/transformed summary fact, unavailable dependency, audited
  borrowing override, and a contributing dispatch target that is not the first
  target in the merged list. No stale or mismatched witness may be printed.
- Repeat with reordered helper declarations and multiple causal operations.
  Exact rounds need not match a different source order, but fixed input must
  produce deterministic evidence and equal semantic rounds across option modes.
- Exercise section 4's unspecified-order collections in snapshots, joined
  origins, returned origins, retained-parameter sets, and dispatch candidates.
  Use multiple facts at one source operation, equal locations with distinct
  operand roles, and calls whose callee is later in source. Verify stable selected
  witnesses and causal dependencies without requiring absolute internal ordinals
  to survive unrelated source additions.
- Exercise bounded witness exhaustion across several refinement instances and
  a chain longer than the hop limit. Stop with a boundary without another
  semantic pass, changed acceptance, or a false nonconvergence diagnostic.

For M1d/M3a/M4a, add focused evidence tests with equivalent candidate collections
constructed in different insertion orders, including hash/identity collections
and immutable `of`/`copyOf` results. M3 must exercise snapshot save/restore and
a join with more candidates than its local cap; M4 must exercise discovery ties
and bounded chains. Compare exact retained witness identities, omissions, and
rendered notes, not merely their sorted output after arbitrary selection.
Run representative CLI fixtures in fresh JVMs as well, since immutable collection
iteration can vary between processes. Preserve each fixed input's primaries,
semantic pass counts, and results across explanation modes. Section 6.7 supplies
test observations; no changed semantic container representation is required.

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
  Compare class/archive and emitted IR bytes directly under section 8.4; run
  the unmodified native controls rather than comparing executable bytes.
- Extend a formatter/loader case with identical library/application basenames
  in different paths and spans, so accidentally using the primary source for
  a related note cannot pass. Keep artifact entry spelling supplied by the loader.
- Preserve the usage rejection for `--link` with `--source-path` or source
  inputs. Do not expand CLI input semantics to make an invalid test matrix work.

The skipped-refinement standard-library failures in section 5.6 are another
ordinary-compilation dependency case. They keep only the limited-analysis note;
the real retaining override here receives supported cross-file notes after
completed refinement. Artifact origin alone must not select the limited mode.

For section 5.13's ownership-contract fixtures, extend the existing primary-only
baseline with these explanation checks:

- Iterator notes identify `list` and acquisition at 9:31; pool notes identify
  `pool` and checkout at 19:23. Identical primary text must not collapse these
  into the same return/free advice. The accepted iterator-owner teardown,
  same-pool return, and checked-out pool teardown controls stay accepted.
- SetterFree identifies `holder` and the retained argument at 17:20. Reversing
  the two frees stays accepted; calling `close()` first stays rejected. With
  two retaining holders, freeing only one must not yield a claim that the
  child's remaining borrow has ended or that the child can now be freed.
- Main's original, Quiet-only, and no-entry-point versions remain rejected,
  accepted, and rejected, respectively. The original call at 41:21 names
  `Keeper.accept` and its store at 21:16, never `Stash.accept`. Without an entry
  point, describe compatible possible targets without claiming either was
  called. Removing Keeper's store alone still rejects due to Stash; removing
  both retaining stores accepts. Compare these outcomes with the option off/on.
- Reassigned owner names, absent local names, and missing acquisition evidence
  must produce supported identity/site wording or an explicit boundary. Never
  infer ownership or dispatch targets from a type name or method spelling.

For disabled construction and bundled-source scope, add these implementation
checks alongside the registered baseline in section 5.14:

- Cross enabled/disabled with final/provisional lowering and completed/skipped
  refinement. Assert function-collector presence only for enabled, final, completed
  analysis; disabled snapshots carry the shared empty evidence value and perform
  no evidence copies. Audit allocating arguments as well as guarded calls.
- Keep missing-free source selection unchanged. For bundled `Writer`, assert
  the primary stays at its destructor with notes enabled under each unfreed mode.
  M1 to M3 get a field-proof boundary; M4 adds the supported cross-file witness
  ending at `KeepingWriter.iron:10:16`. The empty-write control stays accepted
  with the option on/off and emits no explanation report.
- In M4c, run all section 5.14 entry-point variants under off/on explanation
  modes and every unfreed mode. No-main, empty-main, and StringWriter-only-main
  compilations with the retaining declaration keep the same bundled primary;
  removing that declaration or its store accepts the specified controls. Require
  call-site evidence for the actual unknown/empty-flow fallback and possible
  retaining target, never a missing-`main` diagnosis or an invented runtime edge
  from `StringWriter.write(65)`. Preserve the boundary if provenance is unavailable.
- Preserve section 5.6's standard-library rejections under skipped refinement:
  exactly one limited-analysis note per eligible primary, with no collector or
  witness chain. Cover late field/element validators as well as function emitters.
- At M4, verify summary/field evidence maps remain absent with the option off,
  and that provisional summary collection follows section 6.4 when enabled.
  Do not apply the function-local readiness rule blindly to summary construction.

### 8.2 Existing regression selections

The following registered tests are relevant starting points, verified by reading
their registrations. Run the group for the machinery actually changed; do not
run every listed group after every small edit.
Check exact names against the runner's `--list` output or its registrations.
An unknown selection prints `error: unknown test: <name>` and returns status 2
before executing tests; a misspelling must never count as a passing selection.

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
  --test 'rejected free respects helper pool wrapper and dispatch contracts' \
  --test 'rejected free in bundled Writer follows retaining user overrides' \
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

M3 loop back edges and pending-yield observers (M0 also runs the first baseline):

```sh
./scripts/test.sh \
  --test 'loop back-edge rejections preserve both primary diagnostics' \
  --test 'finally transfers preserve ownership at destinations and loop back edges' \
  --test 'literal-true loops preserve reclamation proofs'
```

Expected outcomes with explanations off, preserving each test's existing
`UnfreedMode` selection:

| Test | Contract that must remain unchanged |
| --- | --- |
| `loop back-edge rejections preserve both primary diagnostics` | `LoopDemo` rejects with exactly the carried-local error at 6:9 and the unsafe-free back-edge error at 7:13, in that order. No typed program/LLVM is produced. An immediate break or fresh per-iteration allocation accepts. |
| `finally transfers preserve ownership at destinations and loop back edges` | Supported transfer/cleanup controls compile; a free crossed by repeated `continue` rejects across loop forms. Pending yielded references remain observers, so freeing their allocation during cleanup rejects. |
| `literal-true loops preserve reclamation proofs` | Free then break accepts for `while (true)`, `for (;;)`, and `do ... while (true)`; subsequent use/double free rejects, as does free then continue across a back edge. |

For M3 local destructor-field handling and M4 whole-class field-proof witnesses:

```sh
./scripts/test.sh \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'owned buffer fields require a fresh unescaped factory result' \
  --test 'data structure builder cleanup requires ordered containment' \
  --test 'proven destructor receivers preserve mandatory safety'
```

| Test | Milestone and expected unchanged outcome |
| --- | --- |
| `deferred calls retain captures and mandatory ownership proofs` | M3: a destructor cannot free its field while a deferred call retains it, including getter/overload variants. Ending the deferred-call scope before freeing accepts; all existing unfreed-mode checks remain. This is the direct pending-call case, not a whole-class proof witness. |
| `owned buffer fields require a fresh unescaped factory result` | M3 compatibility and M4 field-proof work: cached or externally published factory results still reject destructor reclamation with `field ownership is uncertain`. This test exercises negative factory mutations, not a new note chain. |
| `data structure builder cleanup requires ordered containment` | M3 compatibility and M4 field-proof work: reversed cleanup/declaration order, lost finality, or published builder still reject with `field ownership is uncertain`. |
| `proven destructor receivers preserve mandatory safety` | M3 receiver handling and M4 compatibility: proven receiver access accepts without a redundant null check; nullable/allocating/publishing destructor cases and use after free remain rejected across unfreed modes. |

These selections are fixed pre-change requirements for those milestone paths,
alongside the cleanup-copy group below and the existing accepted library controls.
M3/M4 add option-on primary parity and exact note spans; passing today's tests
alone does not validate future explanation evidence. Revisit the selection only
when implementation scope changes, recording the reason before editing analysis.

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
unimplemented option. The M3/M4 loop and owned-field selections above are already
assigned, not left for implementers to choose later. Exercise existing IDE
parser/API checks if their shared interfaces are touched.

For shared text formatting, also run the standalone Eclipse parser verifier
from [LOCAL_TESTING.md](LOCAL_TESTING.md). Its proposed-note fixtures must keep
one located primary unchanged and ignore secondary locations/messages; mixed
diagnostics preserve neighboring unlocated errors. M1 must additionally feed
actual formatter output to it, and test that no notes are attached to primaries
missing source or span. This does not enable the option in any IDE consumer.

Use one small accepted native fixture at the integration milestone to compare
behavior and reclamation counts with the flag off/on. Source/IR parity is the
primary evidence for a diagnostics-only change; section 8.4 also requires exact
class/archive bytes and specifies the native comparison boundary. Include an
exception/stack-trace control when excluding probe data from a structural check.
If implementation unexpectedly changes hot lowering, stop and revisit scope;
D132/D133 require the relevant
optimized-code inspection and deterministic benchmark before accepting it.

Run `git diff --check` throughout. `scripts/test.sh` already runs the license audit
before building or executing its selection. A successful invocation covers that
audit for the checked state; run `./scripts/check-licenses.sh` separately when
source/licensing changes are verified without that script, or later relevant
edits/generated files invalidate the earlier audit. Pure documentation edits
without licensing impact do not require it. Do not run the full suite or hosted
platform builds for this feature without an explicit human request.

### 8.3 Per-change comparison procedure

M1 supplies the script for this procedure; it is not implemented by this plan.
Run it before committing each change to feature implementation, proof/evidence
plumbing, diagnostic formatting, or the comparison harness itself, selecting
the focused fixtures affected by that change. Documentation-only edits follow
the repository's consistency-check rule and do not require two compiler builds.
This is local verification, not a new full-suite or hosted-build requirement.

1. **Record the pair.** Before implementation, record the full SHA of the clean,
   synchronized `main` revision the change starts from. For uncommitted work it
   is normally `HEAD`, not `HEAD^`; after a single commit it is normally that
   commit's parent. Pass that SHA explicitly rather than resolving a moving
   `origin/main` during the run. Record the candidate SHA or working-tree diff
   identity, fixture hashes, flags, and tool versions. Exclude unrelated edits.
   If integration adds commits that affect compiler behavior, dependencies, or
   selected fixtures, re-establish a corresponding integrated base and rerun the
   affected checks. Never silently accept a changed expected output to clear a diff.
2. **Build independently.** From the canonical checkout, export the base with
   `git archive` to disposable scratch storage and build that snapshot with its
   build script; build the candidate separately. No branch switch, worktree, or
   change to canonical history is required. Invoke the resulting jars directly
   with the same Java 21 toolchain/JVM options, and use the same pinned LLVM,
   target, and optimization settings for link checks. Record build failures as
   blockers, not fixture rejections; keep build logs out of compiler-output diffs.
3. **Hold inputs fixed.** Run the exact same source and dependency inputs under
   both jars, using stable absolute source paths and explicit source/class paths.
   Record stdlib and dependency source identities. Exporting only the compiler
   must not accidentally reuse stale build products or select a different installed
   stdlib. For option-only changes, these library sources should match; if they
   differ, isolate that unrelated change before attributing results. Use unique
   fixture IDs, not basenames, and separate empty output directories per run.
4. **Capture outcomes, not just text.** Omit the explanation flag for both
   compilers, including bases predating it. Capture stdout, stderr, status,
   and emitted-file inventories separately, with bounded process timeouts.
   Each fixture declares acceptance or the expected rejection status. Capture
   expected nonzero statuses explicitly; do not use `|| true` to erase them.
   A crash, timeout, missing tool, or usage error cannot pass as a rejected free.
   Compare ordered diagnostics, their locations/excerpts, and artifact presence.
5. **Compare accepted outputs.** For selected accepted fixtures, compile and link
   with each compiler and `--emit-llvm`, using that run's class outputs. Compare
   class/archive bytes and emitted LLVM directly under section 8.4, and run the
   small native control where relevant. Do not compare whole executable bytes. The
   in-process registered tests separately compare typed IR and LLVM with notes
   off/on. Rejected runs must emit no new class/native/LLVM output. Keep all runs'
   artifacts isolated so stale files cannot satisfy output checks.
6. **Constrain normalization.** Map only the known base/candidate build roots
   and paired output roots to stable logical labels where they appear in paths,
   including stdlib archive display paths. Preserve relative artifact entries,
   source names/content, message text, line/column/span values, and order. For IR,
   handle a path only in a known nonsemantic source-location field; never rewrite
   arbitrary string constants or instructions. Prefer identical logical input
   paths. Class/archive bytes have no timestamp-normalization exception; their
   writers fix entry times. Keep raw outputs and a record of every substitution;
   any unexplained difference fails. Native comparisons follow section 8.4.
7. **Report and preserve evidence.** Record base/candidate identities, selected
   fixtures, outcomes, diffs, and normalization rules. Preserve failure artifacts
   for inspection and clean only script-owned scratch data. When a difference
   is an intentional unrelated compiler change, split it or compare against a
   base containing that reviewed change, then rerun; do not weaken explanation
   parity to accommodate it. Update golden expectations deliberately only for
   separately authorized behavior changes, with their rationale.

The external script compares off against off, so it needs no note-stripping
filter. Current-build on/off checks use structured primaries and dedicated
formatter tests. An `awk` filter keyed on lines beginning `note:` could discard
or misclassify source excerpts and following output; it is not a diagnostic
parser and must not be the oracle. Registered tests may spawn a second JVM,
but loading another revision requires an explicitly separate build/classpath.

### 8.4 Artifact-specific parity

Use the same JDK/toolchain, source contents/names, classpath inputs, archive
entries/options, and native target settings for each paired check. These are
explanation-mode and per-change comparisons, not a promise of reproducibility
across arbitrary JDK, LLVM, or linker versions.

| Output | Required comparison |
| --- | --- |
| `.ironclass` | Exact file bytes for each corresponding relative artifact path, plus the same file inventory. `IronClass.write` fixes each ZIP entry's time with `setTime(0)`; do not ignore differences as timestamps. |
| `.ironjar` | Exact archive bytes for identical packaging inputs/options. `IronJar.write` fixes entry times and writes stored entries. Do not unpack and discard metadata to make a mismatch pass. |
| Emitted LLVM IR | Exact bytes for same-build option-off/on runs with identical logical inputs. Preserve section 8.3's narrowly recorded source-path handling only where separate base/candidate builds require it. Never remove instructions, metadata, or string constants to hide a feature difference. |
| Native executable | Compare the section 8.2 behavior, exit status, reclamation, and applicable exception/trace results using unmodified binaries. Whole-file byte equality is not an acceptance requirement. Use the structural procedure below when investigating or adding native-code parity evidence. |

For class/archive checks, emit into separate clean directories but preserve
logical input names and archive entry paths. Compare matching outputs directly;
the destination directory is not a reason to relax byte equality. A repeated
build control with different time zones can guard the writer assumption. This
does not require timestamp changes to the writer or a new artifact format.

For native structural checks, follow the existing
[OptimizationReportTests](../compiler/src/test/java/ironwood/compiler/backend/OptimizationReportTests.java)
precedent: compare temporary object copies after removing only the identified
pseudo-probe section, and run the original binaries separately. Check all
remaining object bytes, including instructions, relocations, and unwind data.
Record the toolchain, target, section names, and exact exclusion. Do not strip
trace data from delivered files or discard unrelated sections to pass a diff.
The raw LLVM probe section is `__PSEUDO_PROBE,__probes` on Mach-O and
`.pseudo_probe` in ELF objects; the Linux backend renames the latter to
`ironwood_trace` before linking. A name mapping alone does not prove nondeterminism.

If comparing final executables structurally instead, account for their format:
compare code/data sections, symbol/relocation/unwind information, and relevant
load metadata. Differences in derived UUIDs or signatures are not by themselves
code differences, but any exclusion must be identified and justified from the
observed build. Do not call all non-probe file bytes identical without checking
those derived fields. Unexpected differences remain failures to investigate;
never assert that a probe exception validates the omitted trace data. That data
still needs the unmodified exception/trace behavior control.

## 9. Compilation cost and acceptance evidence

The feature's purpose requires testing the ordinary successful build path, not
only demonstrating attractive output on a small failing example.

Measure the change's base with explanations off and the candidate with them
off/on, prebuilt as in section 8.3. M0 timings remain dated observations, not
a moving compiler's permanent performance threshold. Use these workloads:

1. A small successful source compilation.
2. A representative larger successful program, such as OrderBook's compiler
   invocation, with identical source/classpath inputs.
3. A focused failing input with several ownership rejections.
4. The bounded stress families below: nested joins, saved states/cleanup, and
   forwarding/recursive calls. Run the same sources off/on to separate compiler
   input cost from explanation overhead.

**Measurement checkpoints.** M0 measures the standard-library and OrderBook
workloads plus the stress sources with the existing compiler. Count total
snapshot operations separately from the high-water mark of simultaneously
retained snapshots and their allocation entries. Count present allocations,
joins/immediate predecessors, cleanup copies, summary builds and internal rounds,
and outer refinement passes. Static source call-site counts are not execution
counts. Temporary instrumentation must not change proof order or escape into
committed production code; retain the measurement recipe, and use uninstrumented
builds for timing comparisons. These observations justify provisional caps only.
M1 checks its actual collector and forced exhaustion. M3 measures branch/cleanup
storage; M4 measures complete witnesses; M5 publishes final values and results.

Peak retained memory depends on simultaneously reachable states and shared
graphs. Total saves and refinement rounds instead contribute to cumulative
allocation and time. Report both, including evidence-side snapshot/map overhead;
a per-event count alone misses it. Node/edge/association counts enforce budgets,
while heap profiling and process measurements establish their actual cost.
Do not promise constant total compiler or evidence memory as the input program
grows. Record normal local-cap use separately from invocation safety-stop events.
All representative normal workloads must stay below that stop at the selected
default; exceeding it is a reported limit requiring review, not an ordinary
way to select which methods receive witnesses.

**Nested-join stress.** Start with `Nested` from this review: one array allocation
published into four distinct static fields under two levels of `if`/`else`,
then freed. The intended baseline is the conflicting-if-branches rejection;
the four publication sites are alternatives, not a sequential history. M3
adds regression coverage for a deterministic generated family at depths 2, 5,
and about 10, with distinct publication sites at the leaves, and a control with publication
removed. A full depth-10 binary tree has 1,024 source leaves too; exponential
growth in depth alone is not evidence of superlinear growth relative to source.
Also use a linear sequence of branch joins to catch accidental enumeration of
combinations of paths rather than bounded local predecessor evidence.

For the deep rejected fixture, assert all of the following with explanations on:

- Each primary has at most eight notes, including an omission note when path
  detail is truncated. Selected witnesses retain truthful nested path labels;
  omissions do not imply that displayed alternatives exhaust all paths.
- Collector high-water counters stay within the declared provisional caps,
  including snapshot references, labels, and auxiliary bookkeeping. Lower a
  test-only budget to force storage exhaustion even if normal limits fit the
  fixture. The renderer's eight-note limit alone cannot satisfy this assertion.
- Profiled live evidence agrees with the accounting and stays bounded by those
  budgets. Total compiler memory can still rise with AST/proof size; compare
  against the same input with explanations off, and report allocation churn
  separately. Do not hide large transient evidence lists behind small retained
  counts or require total process memory to plateau across growing programs.
- Ordered primaries, source locations, rejection status, and output suppression
  match the option-off run. Accepted controls match IR/output and print no notes.
  Repeated runs select the same bounded witnesses and truncation wording.

**Snapshot and witness stress.** Vary the number of simultaneously present
allocations separately from nesting depth, and include loops and multiple
cleanup copies. This exposes copying of evidence on every saved state even when
nodes themselves are shared. At M4 add forwarding chains beyond four hops,
recursive retaining/non-retaining controls from section 5.11, and multiple
refinement rounds. Count live analyzer roots and their method/fact allowances,
retire superseded evidence, and monitor the separate invocation safety stop.
Report cumulative allocation across discarded instances without charging retired
storage against a live-storage cap. Exhaust local caps and the aggregate stop
separately; neither may create a false all-path claim or alter convergence.
Use bounded fixtures and process timeouts; stress testing does not authorize an
unfiltered suite or arbitrarily increasing source size after the checks pass.

**Unrelated-import stability (M4b/M4e).** Compile the same user rejection, such
as section 5.11's `Chain`, with and without a large generated imported library
whose methods produce many independent escape witnesses. Hold the user source,
paths/spans, options, toolchain, and existing dependencies fixed. Introduce the
import in a companion source so it does not shift the user's note locations;
verify through test-only inspection that the additional methods were actually
loaded, analyzed, and generated witness facts. An ignored import tests nothing.

Use distinct final types and private/static call chains with no shared fields,
overrides, or calls into the user's chain. First assert that the rejected free's
relevant final summaries, dispatch targets, selected reason, and primary remain
unchanged. Unrelated syntax alone does not establish independence: an import
can legitimately add possible dispatch targets, as section 5.14 demonstrates.
Such a change is not a budget-isolation fixture or a promise of identical notes.

With production local caps and the emergency stop not reached, require identical
ordered notes, text, files/full spans, and normalized rendered output for the
user rejection. Verify that unrelated methods have not depleted its method/fact
allowances. Repeat with the library encountered before and after the user chain,
preserving relative order within the chain, and include noise methods that hit
their own local cap. The required user chain must remain complete within its
own limits. Run fresh JVMs and the matching accepted control; enabled/disabled
primaries and outcomes must agree within each input variant.

Separately lower the invocation limit through a test-only hook to force the
safety stop before that chain is complete. Require the explicit invocation-limit
note, bounded retained/transient storage, no stale or unsupported witness, and
unchanged proof results, pass counts, and primary diagnostics. Do not require
identical detailed notes across inputs after this emergency stop, and do not
hide a stop by raising the test limit in the ordinary stability test. Record
actual sizes, accounting, and headroom in M4e; M5 publishes the measured default.

Keep JDK, JVM options, machine, stdlib inputs, and existing compiler flags fixed.
Record warm-up, repeat count, alternating execution order, median wall time and
variation, peak process memory, and compiler allocation profiles where useful.
Time direct compiler invocations separately from `scripts/test.sh` rebuild time.
Separate source analysis cost from LLVM/native linking, which could mask a
frontend regression. Measure dependency reconstruction during both ordinary
compilation and linking separately where it materially differs.

Required evidence:

- No detailed-history allocation sites execute with the option disabled. Verify
  by the section 6.2 construction/guard audit and lifecycle/snapshot tests, extended
  to all optional summary and field evidence. Neither a null field alone, output
  silence, nor absence of sampled allocations establishes the complete guarantee.
  Keep profiling for measured time, memory, and allocation cost, not as the proof
  that disabled evidence construction is unreachable.
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
  Evidence limits are checked while collecting, including temporary aggregation
  and snapshot storage, not only when choosing which notes to print. Record the
  measured cap values rather than declaring M0's provisional choices final.
- Generated code contains no explanation machinery. With identical inputs,
  `.ironclass`, `.ironjar`, and emitted LLVM IR must be byte-identical across
  option modes. Apply section 8.4 to native behavior/structural checks and
  section 8.3 to recorded path differences between separate compiler builds.
  There is no class/archive timestamp allowance or whole-executable byte oracle.

### 9.1 Separate existing issue: reproducible native probe data

Track native reproducibility separately from rejected-free explanations. The
supplied review reports repeated macOS links with identical LLVM IR, code/data sections,
and symbols but differing `__PSEUDO_PROBE,__probes` data, with UUID/signature
differences following the changed contents. Its isolated reproduction attributes
the variation to `llc` in Homebrew LLVM 23.1.0, not `llvm-as`, `opt`, or timestamps.
This is a reported environment-specific observation, not a claim that every
`llc` invocation differs on every supported platform/version.

Existing D183 and `OptimizationReportTests` already recognize probe-record
ordering variation and use test-only object normalization plus native exception
checks. D132 requires preserving the trace metadata in delivered programs.
Linux maps the data to `ironwood_trace`; whether it varies there is unverified
by this review. Do not extrapolate the macOS report to Linux or other targets.

A separate reproducible-native-build investigation should retain exact LLVM
inputs, tool versions/options, raw objects, and section-level differences,
then determine the cause and an appropriate fix. This feature does not change
LLVM lowering, suppress probes, disable signatures, or broaden normalization
to resolve that issue. Section 8.4 defines sufficient feature-parity evidence
while the existing reproducibility issue remains open.

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

### 11.12 Ownership-contract review, 2026-09-23

Reviewed the section 1 decisions and amendments, dependent-borrow and retaining
edge checks in `FunctionAnalyzer`, and `BorrowDispatchAnalysis` against `ede1053`.
The plan now distinguishes reusable helper ownership, originating-pool checkouts,
and caller-owned children borrowed by wrappers. Dispatch notes describe final
possible targets for the current compilation, with conservative fallback rather
than a claim that a particular runtime call occurs.

The new [FreeOwnershipContractTests](../compiler/src/test/java/ironwood/compiler/FreeOwnershipContractTests.java)
records the section 5.13 rejected primaries and accepted controls, including
closing a wrapper, a second retaining owner, and retaining implementations
excluded or admitted by receiver flow. Four focused tests passed on Java 21:

- `rejected free respects helper pool wrapper and dispatch contracts`
- `borrow dispatch uses exact overloads defaults and receiver flow`
- `unfreed diagnostics track receiver-retained allocations`
- `rejected free in dependencies preserves compile and link source locations`

The first verification attempt found missing SPDX headers in generated sources
from section 11.11's dependency fixture. Its generator now uses the blank line
after the package for the header, preserving diagnostic line numbers; loader
content assertions include that header. The rerun passed, including native
dependency controls. A post-generation license audit, exact proposed-note
locations, decision/source links, and diff whitespace checks also passed.

This review changes the plan and tests only. Production ownership and dispatch
behavior are unchanged; owner notes, witness selection, and option-off/on
comparisons remain future implementation requirements.

### 11.13 Documentation-timing review, 2026-09-23

Reviewed the plan against `17d5c43`, the current `Diagnostic` record, language
server translation, IronDocs formatting, and the affected guides. Commits
`30e4e66` and `dfd3c9b` delivered CLI controls with compiler documentation and
D182/D183; D168 also demonstrates a decision whose status tracks milestones.

This review chooses a new decision in M1 alongside the implemented API, then
updates that same entry through M5. Section 6.6 assigns practical guidance to
`MEMORY_MANAGEMENT.md`, detailed explanation limits to `MEMORY.md`, and gives
explicit M1 requirements for compiler docs, README, help, and focused test
guidance. M2 to M4 update coverage as it lands; M5 audits existing documentation.
The proposed coverage paragraph preserves M1's already-required boundary and
limited-analysis notes instead of promising silence for every unsupported chain.

Verification: checked local links, milestone consistency, existing API consumers,
historical commit file lists, and `git diff --check`. This is a plan-only change;
no compiler suite or license audit was needed. The option and related notes
remain unimplemented, and current user guides do not advertise them as available.

### 11.14 CLI help and usage-error review, 2026-09-23

Reviewed `Main.run`, `CommandLine.parse`, `usage`, and `printUsage` against
`d60f9f8`. Direct invocations of the existing compiler jar on Java 21 confirmed
that `--help` returns 2 with usage on stderr and empty stdout; the unimplemented
`--explain-rejected-free=true` currently returns 2 with an unknown-option error
followed by usage, also entirely on stderr.

Section 2.1 now specifies the compact shared-options sentence and a targeted
no-value error for every equals form. M1 and section 8.1 require parser tests
for exact messages, streams, status, empty/arbitrary values, unknown spellings,
and bare/repeated flags in compilation and linking. Existing help behavior is
preserved. This review changes only the plan; the proposed parser and help
changes remain M1 work. Local links, wording consistency, and `git diff --check`
passed; no compiler suite or license audit was needed for this document edit.

### 11.15 Per-change baseline review, 2026-09-23

Reviewed `scripts/test.sh`, `scripts/build.sh`, the existing fresh-JVM diagnostic
regression, and this plan against `e32b86a`. The test script rebuilds and loads
the current compiler; subprocess tests can exercise it again, but an older
revision needs an explicit independent build. This review did not reproduce
the historical deferred-cleanup IR line counts, which are not needed to establish
that unrelated compiler changes can invalidate a fixed M0 output comparison.

M1 now delivers a per-change comparison script following section 8.3; registered
tests retain same-build off/on parity and exact note expectations. M0 findings
stay attributed to their revisions. The procedure preserves exit statuses and
failure evidence, limits path normalization, distinguishes pre-commit `HEAD`
from post-commit parent selection, and avoids text-based note stripping.
Documentation-only changes require consistency checks rather than rebuilds.

Local links, milestone/comparison consistency, and `git diff --check` passed.
This is a plan-only change; no comparison script or compiler behavior was added,
and no compiler builds, suites, or historical IR comparisons were run.

### 11.16 Storage-budget timing review, 2026-09-23

Reviewed snapshot creation/restoration in `FunctionAnalyzer`, summary rounds in
`EscapeSummaryAnalyzer`, and outer refinement in `SemanticAnalyzer` against
`c894d81`. Snapshot creation copies entries for present allocations; future
evidence adds cost dependent on sharing and simultaneous retention. Repeated
summary construction likewise requires measuring live and cumulative costs.

The plan now fixes output caps separately from provisional storage limits. M0
measures workload shape, M1 enforces initial budgets, M3/M4 measure the implemented
collector/witnesses and tune storage, and M5 records final values and evidence.
Section 9 specifies nested-tree, sequential-join, snapshot, and witness stress
families, distinguishing source size and peak retention from allocation churn.

Local links, budget/milestone consistency, and `git diff --check` passed. This
review changes only the plan. No instrumentation, collector, stress fixtures,
or memory measurements were implemented or run; those remain milestone work.

### 11.17 Artifact-parity review, 2026-09-23

Reviewed `IronClass.write`, `IronJar.write`, `NativeBackend` trace-section mapping,
`OptimizationReportTests`, and D132/D183 against `63f3b81`. Both ZIP writers set
entry times to zero; native tests already distinguish probe-order variation
from instruction/relocation/unwind changes and execute unmodified binaries.

Using the existing Java 21 compiler, compiled `examples/deferredcleanup` twice
from identical source paths into different scratch directories under Tokyo and
Los Angeles time zones, then packaged each class directory. All six corresponding
class files and the archives were byte-identical. Scratch files were removed.
This checks existing artifact determinism, not the unimplemented option's parity.

Removed timestamp allowances throughout the plan and added section 8.4's exact
class/archive/IR checks and native comparison policy. Section 9.1 records the
reported macOS `llc` issue separately; the isolated native reproduction and Linux
variability were not tested in this review. Local links, comparison consistency,
and `git diff --check` passed. No production code or regression tests changed.

### 11.18 Disabled construction and bundled-source scope review, 2026-09-23

Reviewed `withUnfreedChecks`, ownership snapshots, `CompilerPipeline` source
selection, and `SemanticAnalyzer.lowerFunctions` against `65b7680`. The existing
nullable tracker is a useful construction pattern, but its input-source filter
must not control explanations. Collector lifecycle also depends on phase
readiness; a null collector must not suppress reduced-mode boundary notes.

The new [FreeBundledSourceTests](../compiler/src/test/java/ironwood/compiler/FreeBundledSourceTests.java)
confirms a retaining `KeepingWriter.write` override produces exactly the bundled
`Writer.scalar` destructor error with no program/LLVM output under all three
unfreed modes. Removing the static store accepts under all three modes without
diagnostics. The primary retains the bundled source identity and destructor span.

Two focused tests passed on Java 21:

- `rejected free in bundled Writer follows retaining user overrides`
- `safe free distinguishes earlier errors from refined dispatch`

License audit, registered test names, local links, proposed note locations, and
`git diff --check` passed. The plan now requires guard/producer review plus
lifecycle tests for disabled construction, keeping profiling for measured costs.
These tests establish current rejection/acceptance only; collector absence and
enabled notes remain future M1/M4 checks. Production compiler code is unchanged.

### 11.19 Diagnostic-consumer review, 2026-09-23

Reviewed `CompilerOutputParser`, the Eclipse builder command, language-server
translation/URI conversion, IronDoc reporting, and `DiagnosticFormatter` against
`6acd1ae`. Eclipse and the language server do not currently request explanations;
IronDoc does not run ownership analysis. The parser finalizes an error at its
first location, so the plan now requires located primaries and complete primary
blocks before notes, with exact no-note formatting compatibility.

Extended the existing standalone `VerifyCompilerOutput` with proposed located
and unlocated note fixtures, cross-file locations, LF/CRLF, and neighboring
unlocated errors. On Java 21 it passed its real-compiler diagnostic check and
all new primary-preservation assertions, without Eclipse or language-server
packaging. M1 must still connect these assertions to real note-aware formatter
output; the synthetic fixture does not implement or validate that future renderer.

Recorded unlocated/client-fallback note presentation and editor-openable archive
URIs as separate future IDE questions, including the existing dependency-primary
URI limitation. License audit, local links, consistency checks, and
`git diff --check` passed. No production parser, IDE, or compiler behavior changed.

### 11.20 Loop and destructor regression selection review, 2026-09-23

Reviewed the registered loop, cleanup, field-factory, containment, and destructor
receiver fixtures against `4853eab`, following the pre-change review in
`POOL_RELEASE_HELPER_REGRESSION.md`. Section 8.2 now assigns exact tests and
expected outcomes to M3/M4. The deferred-call ownership test supplies the direct
pending-call destructor case; factory and containment tests cover uncertain
field proofs, not pending-call note locations.

Added `loop back-edge rejections preserve both primary diagnostics` to pin
`LoopDemo`'s two errors before M3 adds evidence: exact text, source, start
locations, severity, count, and order, with no program/LLVM output. Immediate
break and fresh per-iteration allocation controls compile under `--unfreed=off`.

Six focused tests passed on Java 21:

- `loop back-edge rejections preserve both primary diagnostics`
- `finally transfers preserve ownership at destinations and loop back edges`
- `literal-true loops preserve reclamation proofs`
- `owned buffer fields require a fresh unescaped factory result`
- `data structure builder cleanup requires ordered containment`
- `proven destructor receivers preserve mandatory safety`

The license audit passed within `scripts/test.sh`. Checked all 39 distinct
section 8.2 selections against the built runner's `--list` output; an unknown
selection returned status 2 with the documented error. Local links, consistency
checks, and `git diff --check` passed. Production compiler code is unchanged;
option-on parity and exact explanation notes remain future milestone work.

### 11.21 Output-format convention review, 2026-09-23

Reviewed `DiagnosticFormatter`, `SourceSpan`, parser block/condition spans, the
existing fragment-only formatter test, and Eclipse source-echo parsing against
`fec4047`. The current formatter uses per-location gutters, a first-line,
single-caret fallback for multiline spans, and platform separators. Parsed blocks end
at the closing brace when present, but recovery and synthetic cleanup contexts
do not justify guessing that boundary.

Section 2.2 now fixes those renderer conventions before golden tests, requires
precise supported note spans, and defines CRLF-to-LF golden normalization without
other whitespace changes. M1 and section 8.1 require full-output tests; later
producers must verify multiline operands, branch conditions, and cleanup exits.
Section 6.6 carries the conventions into the M1 decision record.

Checked section 5's complete excerpts against the per-location gutter/caret
rules, local links, text-policy constraints, and `git diff --check`. This review
changes only the plan; no compiler, parser, or test behavior changed, and no
compiler suite or license audit was needed.

### 11.22 Milestone execution review, 2026-09-23

Reviewed sections 1 through 10, the twenty review findings, existing baseline
commits, and the required pre-change review against `3d41fbf`. M1, M3, and M4
had accumulated several independently risky changes without internal stop points.
The blanket pending status also failed to credit committed preparation.

Section 7 now retains M0 through M5 as coverage milestones while defining
ordered, independently verified checkpoints. It credits existing M0 work and
keeps measurement/schema gaps open; puts the comparison harness before API and
analysis changes; separates eligibility, local state, joins, cleanup, loops,
summary lifetimes, field proofs, and the later element validator; and requires
storage measurements before moving beyond control-flow and whole-program work.

The first public CLI delivery follows all M1 gates. The shared API decision and
documentation instead ship at M1b, when that architecture changes; M1e updates
the same decision and delivers user-facing CLI guidance. This refines section
6.6's former single-commit M1 packaging without delaying documentation or claiming
the option exists in intermediate commits.

Checked checkpoint dependencies and coverage against the existing acceptance
bullets and review requirements, plus local links, text policy, and
`git diff --check`. Only this plan changed. No implementation milestone was
started or completed, and no compiler suite or license audit was needed.

### 11.23 Bundled Writer receiver-fallback review, 2026-09-23

Reviewed `BorrowDispatchAnalysis`, its handoff from `SemanticAnalyzer`, bound
targets and combined summaries in `EscapeSummaryAnalyzer`, the field analyzer's
attached-argument rejection, and bundled `Writer`/`StringWriter` against `4137fd8`.
The dispatch analysis visits all lowered bodies and applies its type-compatible
fallback when a call has no observed targets, independently of entry-point
presence. No-entry-point seeding is a distinct source of unknown receiver inputs.
`StringWriter.write(int)` has its own body and does not call `Writer.writeScalar`.

Removed section 5.14's missing-entry-point explanation. M4c now requires the
entry-point matrix and call-site fallback provenance; it may not infer an actual
call to the retaining override or guess an uncalled method as the cause. Exact
final witness assertions remain implementation work, not a claim established
by the current primary-only regression.

Extended `rejected free in bundled Writer follows retaining user overrides`
with no main, empty main, and StringWriter-only main, retaining/non-retaining
variants, and the StringWriter main without the subclass. All 21 analyses passed
on Java 21 across the three unfreed modes: nine rejections preserve the single
bundled destructor primary and no program/LLVM; twelve controls accept without
diagnostics. The focused `scripts/test.sh` invocation, its license audit, local
links, text-policy checks, and `git diff --check` passed. Production compiler and
standard-library code are unchanged.

### 11.24 Evidence-budget isolation review, 2026-09-23

Reviewed the budget, snapshot, analyzer-lifetime, milestone, and stress-test
requirements against `ffeb308`, including `EscapeSummaryAnalyzer`'s traversal
of all supplied types and refinement rounds. One routine invocation quota could
let earlier unrelated facts consume space needed by the rejected-free chain.

Sections 2.3, 6.2, 6.4, and 9 now make method/fact limits primary, retain bounded
local structures and version accounting, and reserve the invocation limit for
an explicitly reported emergency stop. Retired storage does not consume a
permanent cumulative quota. M4a/M4e distinguish local exhaustion, isolation,
and the aggregate stop; M5 records their values and observed events separately.

The planned M4b/M4e import test keeps the user's locations and relevant proof
facts fixed, verifies that the added library really generates witnesses, and
requires identical notes below the stop even when unrelated methods exhaust
their own caps. A separate forced-stop case checks reporting and safety parity.
The collector is unimplemented, so no enabled-memory or note-stability result is
claimed yet. Local links, budget terminology, text policy, and `git diff --check`
were checked; only the plan changed, with no compiler suite or license audit.

### 11.25 Test-observation seam review, 2026-09-23

Reviewed `SemanticAnalyzer` constructors, refinement, and `lowerFunctions`,
`CompilerPipeline` analyzer creation, and escape/symbolic-return/effect rounds
against `2784fd4`. The loop-local pass index and short-lived function analyzers
cannot be inspected by tests through today's public pipeline. Package-local
access alone would not establish the required lifecycle observations.

Section 6.7 now permits a nullable package-private observer and narrow test
factory, naming their injection path, callback sites, actual-field observations,
round-count semantics, and null-default guards. Tests can use real bundled-source
preparation without a public debug API or duplicated loader. M1c introduces the
seam; M1d/M3/M4 extend it alongside collectors and witnesses. Normal entry points
and cost measurements remain observer-free, and test records cannot retain
analysis graphs or influence proof decisions.

The plan now requires non-vacuous phase assertions, observed/null-observer parity,
and final-iteration versus analyzer-rebuild distinctions. Updated the telemetry
prohibition to allow these dormant hooks explicitly. Local links, checkpoint and
observation terminology, text policy, and `git diff --check` were checked. This
is a plan-only change; no observer or compiler behavior is implemented yet.

### 11.26 Unspecified-order collection review, 2026-09-23

Reviewed `OwnershipSnapshot`'s immutable map/set copies and
`EscapeSummaryAnalyzer`'s retained-parameter iteration, environment copies/joins,
and immutable summary-origin sets against `17b1938`. Identity maps are only one
source of unspecified iteration; insertion-order containers can also inherit an
arbitrary order when restored from those copies.

Section 4 now covers all unspecified-order collections, explicit stable keys,
bounded representative selection before truncation, and separation from semantic
traversal. Sections 6.3/6.4 apply this to restored join evidence and M4 discovery
ties while preserving causal dependencies and the actual first derivation.
Section 8.1 requires permuted-collection, truncation, and fresh-JVM checks;
renumbering internal ordinals alone is not an observable explanation change.

Checked ordering/causality consistency, local links, checkpoint identities, text
policy, and `git diff --check`. This changes only the plan; collection-order tests
for explanation evidence remain unimplemented, and no compiler suite or license
audit was needed.
