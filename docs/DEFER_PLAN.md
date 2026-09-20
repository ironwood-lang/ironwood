<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Block-scoped defer implementation plan

Status: Detailed contract reviewed and accepted on 2026-09-19. Milestone 1's
internal call checkpoint is accepted; see [focused evidence](DEFER_CALLS_VERIFICATION.md).
The maintainer selected Stage 2, deferred free and combined verification, on
2026-09-19; see [combined evidence](DEFER_FREE_VERIFICATION.md). After that review,
the maintainer selected only Milestone 2 step 1, performance verification.
That stage has passed its focused acceptance checks on the measured host and
is ready for review; see [performance evidence](DEFER_PERFORMANCE_VERIFICATION.md).
Example/project adoption and integration remain pending and require further
maintainer direction.

The maintainer explicitly instructed that this task use the local branch
`new-defer-keyword` in the canonical checkout. That instruction overrides
[AGENTS.md](../AGENTS.md)'s default main-only, main-fast-forward, integration,
and push workflow for this task. Do not "repair" the branch by switching to or
updating `main`, or by creating a worktree. Continue verifying the canonical
checkout and origin URLs, preserving human edits, and running focused checks.
Local commits are permitted; pushing or merging into `main` is not authorized.

Keep the branch's existing base throughout planning, internal checkpoints, and
both milestones. Fetching is permitted, but do not rebase onto or merge from
`origin/main` before or after individual checkpoint or milestone commits.
Only after all planned implementation, documentation, verification, performance
acceptance, and project adoption are complete may final integration be considered.
Wait for the maintainer's explicit instruction before rebasing or merging with
`main`; completion alone does not authorize either operation. If a final rebase
onto `origin/main` is directed, do it at that point, not between milestones.
The maintainer will explicitly direct any merge into `main`; no automatic rebase,
merge, or push is part of this plan.

## 1. Goal and boundaries

Allow a programmer to place an explicit cleanup operation beside acquisition
without wrapping every allocation in another `try`/`finally`. Preserve
compiler-proven reclamation, existing exception behavior, and the performance
of equivalent handwritten cleanup.

The accepted direction is one new keyword, `defer`, with cleanup at the end of
the enclosing block. There is no `scoped` modifier, automatic cleanup for plain
`new`, special `AutoCloseable` behavior, ownership-transfer syntax, or
try-with-resources. Java's declaration and existing-variable `try (...)` forms
remain rejected. Ordinary `finally` remains available.

[D168](DECISIONS.md#d168---plan-explicit-block-scoped-defer) partially supersedes
[D051](DECISIONS.md#d051---ordinary-finally-preserves-the-first-exception)
to allow explicit deferred operations while retaining the distinction between
`close()`, pool release, and `free`, and preserving the first exception.
The performance constraints of D132 and D133 remain mandatory.

Both forms below are implemented by Milestone 1. Milestone 2's performance stage
is verified locally for review. Example/project adoption remains pending.

## 2. Accepted version-one syntax

Support two statement forms:

```java
byte[] buffer = new byte[1024 + 4];
defer free buffer;

Socket client = server.accept();
defer free client;
defer client.close();
```

The accepted grammar is `defer free identifier;` or
`defer methodInvocation;`. The first form names an existing local reference;
the second requires a method whose selected return type is `void`.

These are the reviewed version-one boundaries:

- A `defer` must be a direct statement of an explicit source block. Method,
  constructor, initializer, destructor, loop, conditional, labeled,
  `try`/`catch`/`finally`, and switch-arm blocks follow the same scope rule.
  Existing restrictions on those contexts continue to apply.
- An unbraced conditional or loop body cannot be a `defer` statement. A switch
  case must introduce a block around its deferred operations. Reject ambiguous
  placement with a diagnostic asking for braces; do not invent an implicit
  cleanup lifetime or a conditional registration flag.
- A deferred free initially accepts a local name only. Deferred field frees,
  array-element frees, and fresh-expression frees are outside this initial
  syntax; ordinary `free` and `finally` retain their existing capabilities.
  Reject reassignment of that local while its deferred free is pending. This
  restricts the binding, not mutation of the referenced object or array.
  A parameter is not made freeable by being named in `defer`.
- Deferred calls use ordinary member lookup, access checks, overload resolution,
  generic specialization, and static, virtual, interface, or `super` dispatch.
  Existing invocation rules are not replaced with a cleanup-specific resolver.
- Reject deferred blocks, assignments, declarations, standalone construction,
  non-void calls, control-transfer statements, and another `defer` as the action.
  Multiple cleanup operations use multiple statements.
- The accepted void-only boundary limits initial implementation and testing of
  returned-value provenance and discarded-result diagnostics across deferred
  cleanup paths. It is an implementation-scope choice, not a missing language
  policy or an inherent safety requirement.
  [D140](DECISIONS.md#d140---warn-by-default-about-proven-abandoned-allocations)
  already governs provably abandoned owned results through
  `--unfreed=off|warn|error`, with its existing conservative analysis limits and
  without automatic reclamation. Supporting non-void deferred calls would reuse
  that policy and ordinary discarded-call semantics at cleanup execution.
  The cost is excluding useful calls such as boolean-returning
  `defer set.remove(x);` and fluent `defer buffer.clear();` returning `this`,
  even though discarding those results creates no abandoned owned allocation.
  Such cleanup must use ordinary `finally` or an explicit void helper that
  discards the result under existing rules. The reviewed version-one contract
  accepts that scope restriction.
- Reserve `defer` as a keyword, with the compatibility impact below. Do not
  reserve or introduce `scoped`.

The identifier audit at planning revision `c4e72ad` found no `defer` occurrences
in `.iron` files under `stdlib`, `examples`, `projects`, `integration-tests`, or
`compiler`, nor in the compiler's Java test sources containing inline Ironwood
fixtures. No identifier migration is needed in the audited repository sources.
Recheck sources added before implementation.

Contextual recognition was considered: recognize `defer` in the new statement
forms while keeping it an ordinary identifier elsewhere. Ironwood already uses
this approach for `SuppressUnfreed` after `@`, as described in
[MEMORY.md](MEMORY.md#per-allocation-suppression). That alternative would reduce
identifier compatibility breakage. The plan favors a reserved token for simpler
statement recognition and error recovery, given the absence of current source
conflicts. Contextual recognition remains technically viable; reservation is a
design tradeoff, not a language limitation. The external-artifact cost remains
as described below.

Reservation also affects previously built artifacts: format-1 `.ironclass`
files embed source that is re-lexed and parsed when loaded, including class
units inside `.ironjar` archives, as described in [COMPILER.md](COMPILER.md).
An older artifact using `defer` as an identifier will therefore be rejected when
its source is loaded by the new compiler, even if format 1 remains supported;
rename that identifier in its source and rebuild it. The bundled stdlib archive is
rebuilt from source by `scripts/build.sh`, and the audit found no such identifier
in that source, so there is no current bundled-stdlib conflict. This audit does
not establish compatibility for external artifacts.

## 3. Execution contract

### Block lifetime and order

An action becomes active only after execution reaches the declaration and
binds its free target or successfully captures its call operands. It executes
exactly once when that block is left by fallthrough, `return`, exception
propagation, or a crossed
`break`, `continue`, or `yield`. Leaving an inner block does not execute actions
belonging to an outer block that remains active.

Within a block, actions execute in reverse declaration order. Inner-block
actions finish before outer-block actions. An action declared in a loop body
finishes when that iteration leaves the body; it never accumulates until method
exit. There is no cleanup handle, cancellation operation, or dynamically sized
stack of pending actions.

Classic switch fallthrough does not extend an explicit block's lifetime. In
`case 1: { ... } case 2:`, deferred actions in the first block finish at its
closing brace before normal fallthrough enters `case 2`. There is no implicit
`break`; a propagating cleanup failure takes the exception path instead.
Direct entry at `case 2` leaves the skipped block's actions inactive. Keep this
rule explicit in [LANGUAGE.md](LANGUAGE.md) when documenting the implemented
feature; its current note is clearly marked as planned behavior.

Only reached actions run. If allocation or another earlier statement fails,
later actions never become active. If a deferred operand cannot be captured,
that action never becomes active, but earlier active actions still execute.
Cleanup has the same process-termination limits as ordinary `finally`: signals,
fatal runtime termination, and direct process exit do not acquire a new unwind
guarantee.

### Operand capture

For `defer free buffer`, bind the action to the resolved local and forbid writes
to that binding while the action is pending. Thus
`defer free buffer; buffer = new byte[8];` is a compilation error, independently
of `--unfreed`, rather than freeing the first array and abandoning its replacement.
Reject even self-assignment while pending. Array-element and object-field
mutation remain subject to ordinary safety rules; the object is not frozen.

The restriction starts when execution reaches the deferred-free declaration
and ends when that action executes. It applies to the same resolved local in
nested blocks, branches, operand expressions, and intervening source `finally`
bodies. It does not make the local permanently `final`: assignment before the
declaration remains legal, and after cleanup a plain assignment may reuse a
still-in-scope local without reading its freed value, as in
[LANGUAGE.md](LANGUAGE.md). Existing ownership and loop proofs still apply.
Use a different local, or complete cleanup in an inner block before reusing the
original local, when a replacement allocation is needed.

For a deferred call, evaluate the receiver and arguments once, in ordinary
left-to-right invocation-evaluation order, when execution reaches `defer`.
Capture primitive values and reference identities, not copies of referenced
objects. Later field or array-content mutations remain visible to the eventual
call, while permitted later reassignment of a receiver or argument local does
not change its capture.
Evaluation, conversions, and nested calls needed to produce operands happen
now; the selected outer method invocation happens on scope exit.

The receiver null check and any class initialization required by that outer
invocation occur when the action executes. There is no null-skipping cleanup
rule. Class initialization and failures caused by the operand expressions
themselves occur during capture. Dispatch retains the originally selected
method contract and the captured receiver's dynamic type.

The execution-time null check is a deliberate design choice: capture records
operands, while invocation and its receiver validation belong to cleanup. This
matches handwritten `finally` that invokes an earlier saved receiver. A null
capture therefore activates the action after its arguments are evaluated;
subsequent body statements still run. When cleanup executes, its null failure
is primary if no exception is pending, or secondary to a body or earlier
cleanup failure under D051. Remaining cleanup actions still run. The diagnostic
must retain the deferred call's source span. An action that never executes
does not report an outer receiver null failure.

The cost is delayed diagnosis, potentially as a secondary exception. A
capture-time check would fail before the remaining body runs and leave this
action inactive; that is a different contract, not just a lowering optimization.
It could reduce duplicated check code across cleanup copies. Those copies are
mutually exclusive, however, so execution-time checking requires at most one
outer receiver check per reached action execution, before any optimization.
Do not infer a runtime speedup from the number of emitted copies. Redundant
checks may be eliminated when proved safe, but an observable null failure must
not move to capture time. Section 8 requires the same timing in parity fixtures.

Preserve the ownership and lifetime of real receiver and argument temporaries:
dynamic String concatenation results and proven-fresh factory or `toString()`
results, following [MEMORY.md](MEMORY.md). Captured results must remain live
until the delayed call completes, subject to the existing escape and cleanup
rules on every exit. Freshness alone does not authorize automatic reclamation;
ordinary owned results still need explicit proven-safe cleanup. Name such a
result in a local and schedule its free before scheduling the call that uses it.

Distinguish captured results from intermediate text used to compute them. Fresh
`toString()` text consumed by concatenation retains its existing narrow cleanup
protocol after copying, including on later conversion or allocation failure;
only the resulting captured operand needs its lifetime extended. Borrowed,
immortal, or mixed-provenance results gain no free exemption. This introduces
neither callback/action allocation nor a general temporary-reclamation rule.
Comparisons with handwritten code must include equivalent operand evaluation,
temporary lifetime, null checks, and initialization timing.

### Exceptions and surrounding handlers

Each action must run even if a previously executed action throws. For example,
the socket declarations above execute `close()` and then attempt the proven-safe
`free` even when closing fails. A flat list of statements in one `finally` is
not an equivalent implementation.

Preserve D051's existing behavior:

- If a body exception is pending, it remains the primary object. Cleanup
  failures become its secondary exceptions in execution order.
- If no exception is pending, the first cleanup failure becomes primary and
  subsequent cleanup failures become its ordered secondary exceptions.
- A cleanup failure supersedes pending normal completion, return, or a loop
  transfer, while the remaining cleanup actions still run.
- A deferred call's checked exceptions participate in ordinary catch-or-declare
  checking. Capture-time and invocation-time failures both need correct edges.

Actions in a `try` body execute before its enclosing catch dispatch, so those
catches can handle cleanup failures as well as body failures. Actions in a
catch body execute before that body leaves; sibling catches do not catch them.
Actions in an explicit `finally` body finish as that body exits and retain the
same primary/secondary rules as equivalent nested ordinary cleanup. An inner
catch that handles a failure without leaving the owning block does not execute
that block's deferred operations early.

## 4. Ownership and safety

Deferring an operation does not transfer ownership or establish a new borrowing
exemption. The existing free, escape, constructor, destructor, pool, and
dependent-helper rules remain authoritative. Unknown effects remain unknown.

Represent active call captures and pending deferred-free bindings explicitly
in compiler analysis:

- A captured receiver or reference argument remains observable until its action
  executes. Freeing it earlier, directly or through an owning object, must fail
  whenever the future action could observe reclaimed storage.
- A deferred free keeps its target binding unchanged and available until cleanup,
  without freeing anything or creating another reference alias at declaration.
  Track the pending action by local-symbol identity, not spelling; reject a write
  whenever that free may still be pending on a reachable path. Cover assignment
  statements and expression/lvalue writes, including nested operand evaluation.
  Include this state in exit snapshots and joins, and remove the restriction
  only in paths where the action executes. Check the free against every actual
  cleanup predecessor, including exceptional paths. Enforce the write guard
  entirely at compile time, with no runtime check or tracking storage.
- Otherwise-safe reads and mutation through the reference remain legal before
  cleanup. A return or reference-valued `yield` that would expose the reclaimed
  object, an escaping alias, a later
  observer in another cleanup action, or an unsafe loop back edge must fail.
- An earlier manual free or another reachable deferred free of the same
  allocation must be rejected. Mutually exclusive generated cleanup copies
  must not be mistaken for a second runtime free.
- The free form uses the existing local as its target, with no synthetic alias
  to exempt from the proof. Ordinary aliases and still-pending call captures
  remain real observers; preventing reassignment does not establish exclusive
  ownership or make early/manual/double free safe. Consume call captures in
  execution order and preserve their identity/liveness checks, without blanket
  exemptions for generated locals or for everything at a scope boundary.
- Apply scope lifetime precisely: an outer local still observable after an
  inner block cannot be reclaimed merely because that inner block ends.
  Expire only aliases proven dead, preserve pending expression operands, and
  merge ownership after cleanup rather than before it.
- Scheduled frees must participate in `--unfreed` diagnostics and preserve
  per-allocation `@SuppressUnfreed` provenance. None of `off`, `warn`, `error`,
  or a suppression annotation may relax mandatory safety rejection.

Pool reuse stays explicit and follows the existing `ObjectPool` API:

```java
Message message = pool.get();
defer pool.release(message);
```

Pool-owned objects are released to their pool, not freed by the borrower.
Scheduling release adds no runtime duplicate-return check or new compile-time
promise about all pool-contract misuse. Preserve current pool obligations and
effect summaries; the pool and its borrowed builder must stay alive as required.

Inside constructors and destructors, existing rules still apply. Constructor
delegation remains first. Active deferred actions in a constructor body run as
their owning blocks exit, before a failure leaves the constructor invocation
and reaches the `new` expression's rollback edge described in
[MEMORY.md](MEMORY.md). This ordering applies both to body failures and to a
deferred action that makes otherwise successful construction fail: remaining
active cleanup actions run first, preserving primary/secondary exception order,
then failed-constructor rollback runs under its existing rules. Local cleanup
does not replace or duplicate rollback.

A destructor may neither allocate nor let exceptions escape through deferred
calls. `defer free` accepts only a local name, so neither `this` nor a field is a
valid direct target. The relevant safety check is indirect: a local alias of
`this` or a still-attached owned field must not gain permission to be freed
merely because it is captured by `defer`.

## 5. Compiler implementation approach

Use a dedicated source AST statement with accurate spans, then explicit typed
capture and cleanup planning before backend emission. The semantic model should
be equivalent to protecting the remaining block tail with nested cleanup
regions, one per reached action. Capture evaluation occurs outside its own
region and inside any regions already active.

Use the existing `finally` exit machinery for execution order, ownership
snapshots, pending results, and secondary exceptions, after generalizing its
cleanup payload as described below. Do not implement semantics by rewriting
LLVM text or by allocating closures. Keep source declarations and their scopes
visible to diagnostics; an early textual nesting rewrite must not accidentally
change name lookup, declaration scope, or capture evaluation.

### Cleanup-action representation and replay

The existing `FunctionAnalyzer.FinallyContext` carries a source `Block` plus
outer exception and finally contexts. Its consumers re-lower that block for
each exit path. A deferred call cannot be implemented by putting its
original operand expressions in a synthetic block: each replay would evaluate
those expressions again instead of using the values captured at `defer`.

Generalize the context's payload to an explicit compiler-owned cleanup-action
variant, retaining the existing surrounding-context and unwind model. Proposed
variants are:

- **Source finally:** retain the source `Block` and lower it normally for each
  copy, including its ordinary reads of locals at cleanup time.
- **Deferred free:** retain the resolved local symbol, its type and source span,
  and the existing allocation metadata. Use that unchanged binding's operand
  in each cleanup copy with the current path's ownership proof; do not perform
  name lookup again or introduce a separate reference capture. Keep the binding
  available until this action is emitted and enforce its pending write guard.
- **Deferred call:** retain the resolved invocation contract, dispatch kind,
  typed receiver/argument captures, and source spans. Emit the invocation and
  its execution-time checks from those captures without lowering receiver or
  argument expressions again. Preserve their existing ownership obligations.

These are immutable compiler data, not allocated actions in the native program.
Call capture definitions are emitted where execution reaches `defer`; activate
that context only after capture succeeds. A deferred free validates its local
and activates the pending write guard without emitting a capture copy.
Preserve stable call capture identities and path-correct SSA bindings so every
call cleanup use is dominated by its capture definition. Any necessary join
mapping must use the capture's value rather than
the source local's later value. A defer inside a replayed source `finally` binds
its free target or captures its call operands for that reached execution of the
source block, not through globally cached action data shared across executions
or loop iterations.

Introduce one cleanup-action emission entry point and route all existing
consumers through it: `completeReturnThrough`, `lowerFinallyBody`,
`lowerFinallyForPendingException`, `completeYieldThrough`, and
`completeTransferThrough`, including normal and catch fallthrough callers.
The protected context used to collect secondary exceptions must preserve the
same action payload while replacing only its surrounding exception context.

Re-emission produces fresh instructions, result identifiers, basic blocks, and
exception edges for each copy as needed; it does not replay capture expressions
or splice a previously emitted mutable instruction sequence into another block.
Use independent ownership snapshots for mutually exclusive copies. Keep mutable
free/escape state out of the shared action descriptor, and consume pending-action
state and call captures in the executing path without marking sibling copies
consumed. Ordinary source `finally` and typed deferred actions must coexist in
one ordered cleanup stack
in the compiler, with no new runtime stack or registration operation.

### Split operand preparation from invocation emission

`InvocationPlanner` performs side-effect-free typing and selection; it does not
emit IR. Its receiver and argument plans do not themselves separate capture
from execution. `FunctionAnalyzer.lowerSelectedCall` currently combines both,
including checked-exception analysis, null checks, type initialization, call
effects, and dispatch emission. Refactor that lowering into two explicit
operations, provisionally named `prepareInvocationOperands` and
`emitPreparedInvocation`. Apply the same boundary to the ordinary `lowerCall`,
`lowerSuperCall`, and `lowerInterfaceSuperCall` paths where applicable, preserving
their supported forms and diagnostics rather than assuming every call already
passes through `lowerSelectedCall`.

| Operation | At a deferred declaration | In each cleanup copy |
| --- | --- | --- |
| Select and validate the call | Reuse ordinary overload/access checking, inference, substitutions, and receiver/argument plans; enforce the accepted void-only boundary. Save the selected contract and dispatch metadata. | Use that contract without repeating source lookup or overload selection. |
| `prepareInvocationOperands` | Evaluate the value qualifier/receiver and arguments in ordinary order; perform operand conversions and nested calls, including their checks, initialization, and effects. Save typed values and converted call operands, with allocation identities, bindings, and spans. A value qualifier for a static call is evaluated here but is not an invocation receiver. Activate the deferred action only after successful preparation. | Never lower source operand expressions or repeat their conversions. Read the saved bindings. |
| `emitPreparedInvocation` | Do not emit the outer invocation, its receiver null check, its type-initialization barrier, or its call effects. | Use the current path's captured operands; perform the required receiver null check or static target initialization, apply invocation-time ownership/effect analysis, and emit fresh direct/devirtualized/virtual/interface call IR through `emitCall` with the cleanup's exception context. Preserve generic substitutions and direct `super` dispatch. |

The prepared-call data must retain both typed values needed by effect analysis
and the converted operands needed by call IR. At each emission, preserve the
existing `recordResolvedCall`, `recordKnownBorrowDispatch`, and unknown-call
escape behavior, plus reentrancy, pool/container transfer, and missing-free
analysis. Apply effects of nested operand calls during preparation and effects
of the deferred outer call at cleanup, including the existing distinction
between effects possible before failure and transfers completed only on
success. Do not leave mutable pending-call state armed between capture and
cleanup or cache a path's ownership conclusions in the prepared-call data.

Checked-exception checking remains compile-time analysis, not a runtime capture
or cleanup operation. During semantic preparation, check the deferred target's
declared exceptions against the handlers enclosing its owning block's cleanup
and the enclosing method's `throws`, preserving observed-exception information
for catch analysis. Nested calls in operands use the capture site's handlers.
Cleanup copies retain the action's exception context and source diagnostic
provenance; an inner handler active at a particular exit must not accidentally
make the deferred call legal or catch its failure. Re-emission must not produce
duplicate diagnostics for the same source action.

Ordinary immediate calls compose preparation and emission consecutively;
deferred calls store the prepared operands and emit later through the cleanup
action variant. Keep this refactor focused, with existing immediate-call
ordering, safety, and generated-code regressions retained.

Relevant existing code and work areas:

| Area | Planned work |
| --- | --- |
| `lexer/TokenKind.java`, `lexer/Lexer.java` | Reserve `defer` and preserve token spans. |
| `ast/Statement.java`, new defer AST representation, `parser/Parser.java` | Parse both forms, enforce placement, and recover after malformed input. Keep the shared resource-header rejection path and test both declaration and existing-variable forms. |
| `semantic/InvocationPlanner.java`, `semantic/InvocationPlan.java` | Reuse side-effect-free selection and receiver/argument/conversion plans. Keep operand and invocation IR emission in `FunctionAnalyzer`. |
| `semantic/FunctionAnalyzer.java` call lowering | Split fused call lowering into `prepareInvocationOperands` at capture and `emitPreparedInvocation` in each cleanup copy. Preserve immediate-call behavior, checked-exception contexts, dispatch, initialization, and ownership/effect timing across planned and ordinary call paths. |
| `semantic/FunctionAnalyzer.java` and focused supporting types | Generalize `FinallyContext` from a source block to source-finally/deferred-free/deferred-call action variants; route every exit consumer through common action emission. Integrate block tails, call captures, pending-free write guards across assignment statements and lvalue writes, cleanup ordering, checked exceptions, pending results, and independent exit snapshots. Avoid a parallel unwinding framework or unrelated restructuring. |
| Effect and source visitors | Audit `EscapeSummaryAnalyzer`, final-field/effectively-final analysis, owned-array and fresh-result analysis, local-class discovery, `PatternFlow`, and `TypeDependencyScanner`; ensure capture-time and cleanup-time effects are not omitted or conflated. |
| Typed IR, specialization, reachability, backend | Prefer existing calls, free instructions, and cleanup CFG. Retain methods used only by deferred calls, specialize generic calls, and preserve source locations without a runtime registration ABI. |
| Source/class/archive loading | Reconstructed `.ironclass` and `.ironjar` source must reproduce the same parsing, checks, and native behavior. Cover format-1 re-lexing of older artifacts with keyword collisions as well as ordinary round trips. Verify before assuming no format change is required. |

Compiler source paths in this table are relative to
`compiler/src/main/java/ironwood/compiler/`. Enumerate all statement walkers
during implementation rather than assuming this table is exhaustive.

## 6. Milestones and completion gates

### Review gate: accept this detailed contract

Goal: settle the user-visible rules before implementing them.

- Review the two forms, local-only deferred free and void-call boundaries,
  explicit-block placement, the pending-free reassignment restriction, early
  call operand capture, and delayed invocation checks.
- Weigh the void-only implementation scope against excluding boolean-returning
  and fluent cleanup calls; use existing discarded-call lowering and D140 as
  the baseline rather than assuming a new return-value policy is needed.
- Review primary/secondary failure behavior, scope-relative catch placement,
  the execution-time null-check rationale and tradeoff, and the capture
  identity/liveness model.
- Review the cleanup-action variants, all context replay sites, capture
  dominance, and independent ownership state for mutually exclusive copies.
- Review the call-lowering split, checked-exception contexts, and the placement
  of operand effects versus deferred invocation effects.
- Confirm the verification and performance acceptance criteria below.
- Resolve changes in this document and D051/D168 before selecting implementation.

Planning deliverable: this document, D168, D051's narrow supersession note, and
the clearly marked planned switch-fallthrough note in `LANGUAGE.md`.
No compiler, runtime, standard-library, or example changes belong to this gate.

### Milestone 1: complete language semantics and safety

Status: both stages implemented and reviewed before selection of Milestone 2 step 1.

Goal: both defer forms work end to end through native compilation, with the
complete supported exit and safety contract. A parser-only feature or an
implementation that handles only normal returns does not complete this stage.

#### Internal checkpoint: deferred calls and exit integration

Status: accepted by the maintainer before selecting Stage 2;
[focused verification](DEFER_CALLS_VERIFICATION.md).

Goal: review the analyzer generalization and call form before adding the free
form. This is a checkpoint within Milestone 1, not a third milestone or a
completed language feature.

1. Add token/AST/parser support for the call form, placement rules, and focused
   positive and negative syntax tests. Keep `defer free` rejected with a clear
   not-yet-implemented diagnostic during this checkpoint.
2. Generalize the cleanup-context payload for source-finally and deferred-call
   actions, routing every exit replay site through common emission. Preserve
   source-finally behavior and its focused regressions.
3. Split fused call lowering into operand preparation and invocation emission,
   preserving immediate-call behavior first. Implement typed call captures and
   deferred invocation across normal, return, exceptional, loop-transfer, and
   yield exits, including nested source `finally` and cleanup failures. Verify
   activation, SSA dominance, independent copies, and exception/effect timing.
4. Integrate the call form's capture lifetimes, escape/effect summaries, pending
   return/yield operands, constructor-exit ordering, and missing-free analysis.
   Existing `free` must reject reclamation while a pending call can observe the
   allocation. Mandatory safety cannot wait for deferred-free implementation.
5. Complete the visitors, specialization, reachability, and source/class/archive
   round trips needed by deferred calls. Run the applicable call-form subset of
   section 7's parser, semantic, typed-IR, native, and negative tests, plus the
   affected ordinary-call, `finally`, and resource-header rejection regressions.

Checkpoint gate: the call form works across all supported exits, safe cases
pass, and unsafe cases remain rejected in every `--unfreed` mode. Create a
separate local commit and present the analyzer/call changes with exact focused
verification results for review before beginning the free form. Document the
partial implementation accurately; deferred free, full Milestone 1 completion,
performance acceptance, and project adoption remain pending.

#### Complete Milestone 1: deferred free and combined verification

Status: implemented locally; [combined verification](DEFER_FREE_VERIFICATION.md).
Stop for maintainer review before Milestone 2.

1. After checkpoint review, add the deferred-free syntax and cleanup-action
   variant with bound local targets, pending-write guards, and existing free
   proofs on every cleanup predecessor. Preserve assignment-before-registration
   and supported reassignment-after-cleanup behavior.
2. Verify interaction with call captures, ordinary aliases, escape/effect
   summaries, pending return/yield values, constructor rollback, and missing-free
   diagnostics. Extend the affected visitors and artifact reconstruction to the
   free form without weakening the call checkpoint's guarantees.
3. Run the remaining semantic/native matrix and focused combined-form cases,
   including close-before-free ordering on failure, alias conflicts, and
   independent ownership state across cleanup copies. Rerun checkpoint tests
   affected by these changes and record exact results.

Milestone exit gate: focused tests pass; safe examples are accepted and unsafe
examples are rejected in every diagnostic mode; no runtime cleanup registry or callback
allocation exists; valid source/class/archive inputs agree. Complete the
normative language and memory documentation for this implementation stage,
while marking final performance acceptance and project adoption pending.

### Milestone 2: prove performance parity and demonstrate adoption

Status: step 1 verified locally for maintainer review;
[performance evidence](DEFER_PERFORMANCE_VERIFICATION.md). Adoption steps remain
pending and are not authorized by this performance stage.

Goal: verify equivalent code costs and demonstrate the intended improvement in
readability without changing application behavior.

1. Build paired handwritten and deferred fixtures and collect the evidence in
   section 8. Resolve code-generation or safety regressions before adoption.
2. Add a focused example showing a buffer, separate close/free actions, and a
   pool checkout/release. Explain order, exceptional exits, and expected output.
   Update [examples/README.md](../examples/README.md)'s example table and the
   hard-coded counts in both sample `test-all.sh` summary lines (`TOTAL` and `PASS`).
   Recompute the total from the then-current example directories discovered by
   `examples/test-all.sh`; keep the table and summary consistent with that count.
3. After explicit implementation selection, carefully adapt only cleanup in
   [Server.iron](../projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/Server.iron),
   rereading the then-current file first.
   Preserve the maintainer's code, 1 KiB limit, stdout choices, EOF framing,
   blocking behavior, diagnostics, exit codes, and allocation-free `reply`.
   Keep `Socket client = server.accept();` outside the per-client `try`.
   At the start of that `try` body, before acquiring streams or calling `reply`,
   register `defer free client;` followed by `defer client.close();`. Both
   actions belong inside this body, not at loop-body scope: LIFO cleanup must
   close and then free the client before the existing `catch (IOException e)`
   dispatches. A close failure must still be printed and allow the next loop
   iteration; an accept failure must still propagate to the listener handler
   and produce exit status 74.
   Leave `reply`'s logic intact; do not turn this into a networking redesign.
4. Run the project's compile/link/test workflow and native allocation probe.
   Add a deterministic server-shaped failure fixture proving that close failure
   still attempts free before the per-client catch and permits another accept,
   while accept failure reaches the outer handler and exits 74.
   Update the example/project documentation and record performance evidence.
5. Complete the documentation/status audit in section 9 and the scoped checks.

Exit gate: performance acceptance passes, the actual example/project behavior
is preserved, focused verification is reproducible, and documentation describes
implemented behavior accurately. This does not authorize bulk stdlib rewrites,
another language feature, a release, or a full test suite.

## 7. Focused verification matrix

Add a focused `DeferTests` group registered in `CompilerTests`, small native
fixtures in `integration-tests/cases/`, and exact selectable names. Use expected
event sequences and equivalent Ironwood `try`/`finally` as the behavior oracle;
Java is not an oracle for `defer` or Ironwood reclamation.

| Concern | Required evidence |
| --- | --- |
| Syntax and diagnostics | Both forms; `defer` identifiers rejected; missing action/semicolon; forbidden placement and action kinds, including boolean, fluent receiver, and fresh-owned non-void results under the accepted void-only boundary; primitive/unknown free targets; inaccessible or invalid invocations; both Java resource-header forms still rejected. |
| Scope and order | Empty and populated blocks; LIFO across several actions; nested blocks; branch-local actions; no execution before declaration; loop iteration cleanup; labeled and unlabeled transfers; braced switch arms; classic-switch block cleanup before fallthrough, exceptional propagation instead of fallthrough, and no activation when direct case entry skips the block; inner handled exceptions that keep the block active. |
| Call captures | Exactly-once receiver/argument evaluation and order; primitive and reference reassignment preserves saved values when no pending deferred free forbids the write; later object mutation; operand evaluation failure; null receiver at cleanup; class-initialization timing/failure; dynamic String concatenation and proven-fresh factory or `toString()` results used as receivers/arguments survive until the delayed call completes; intermediate concatenation text keeps its existing cleanup on success/failure; borrowed, immortal, and mixed results are not incorrectly reclaimed; static, virtual, interface, generic, and `super` calls. |
| Null receiver timing | A null capture with successful argument evaluation activates the action and permits later body effects; its cleanup failure is primary on otherwise normal exit or secondary to a pending body/cleanup failure, with remaining actions attempted and the deferred call's source span retained. A failing operand expression instead prevents activation. Compare event order and exception identity/secondary order with the handwritten equivalent. |
| Deferred-free bindings | Reject pending-target writes via statements, expressions, self-assignment, nested branches, and intervening source finally in every `--unfreed` mode; accept otherwise-safe reassignment before registration or after inner-block cleanup, including supported loop reuse, and mutation of live array elements/fields; distinguish local-symbol identities; preserve early/double-free and live-alias rejection without a synthetic free capture. |
| Completion | Normal fallthrough, return values, pending reference returns, `throw`, caught/rethrown failures, `break`, `continue`, and `yield`; nonterminating paths never execute unreachable cleanup; existing unreachable-code and definite-assignment rules remain consistent. |
| Failure ordering | Body failure plus multiple cleanup failures; cleanup failure during return/transfer; original exception identity and secondary occurrence order; close failure still followed by free; actions inside catch/finally; checked failures at capture and cleanup; source traces point to real defer/call sites. |
| Memory safety | Safe local array/object cleanup; fresh factory results; expired aliases; two captures of one object with valid use-before-free ordering; reject reverse ordering, early/manual/double free, escaping or returned aliases, owner free with pending dependent stream/view, unsafe branch joins and back edges, and unknown retaining effects. |
| Ownership boundaries | Constructor-body deferred actions finish before the `new` rollback edge for both body failures and cleanup-only failures, preserving exception identity/order and exactly-once rollback; destructor restrictions; borrowed parameters and pooled objects cannot gain a free exemption; release precedes pool destruction; argument temporaries are reclaimed only when proven safe; all three `--unfreed` modes and suppression preserve mandatory errors. |
| Cleanup-action replay | One call capture evaluation before several possible exit paths; every emitted cleanup copy uses saved call operands or the unchanged free-target binding with valid SSA dominance and independent ownership/pending-write state; failed captures activate no action; source-finally reads remain late while deferred call captures remain early; defers inside source-finally copies register on each reached execution; return, exception, loop-transfer, and yield consumers all support every action variant. |
| Call-lowering split | Capture-side nested calls/conversions and cleanup-side null checks, static target initialization, and outer calls have distinct IR and failure edges; outer ownership effects occur only at invocation, including exceptional effects and successful transfers; checked-exception acceptance uses the action's handlers without duplicate replay diagnostics; planned, ordinary, and special-receiver immediate calls retain their behavior. |
| Typed IR and artifacts | Real cleanup CFG with expected capture/use order; no executed cleanup at declaration; reachability for cleanup-only callees and generic specialization; valid and invalid source-path, class-path, archive, and final-link reconstruction agree; format-1 class and archive fixtures built before reservation with `defer` identifiers are rejected during source reload. |
| Native behavior | Event logs, exit codes, allocations, live allocations, destructor counts, first/later failures, exact socket output, and reusable pool state at `-O3`; no external networking service required. |

Focused existing test names worth retaining in the selection include:

- `try-with-resources syntax is deliberately rejected`
- `malformed resource syntax retains parser diagnostics`
- `finally preserves primary exceptions and exposes secondary exceptions`
- `safe free tracks ownership independently across duplicated finally paths`
- `finally transfers preserve ownership at destinations and loop back edges`
- `first-failure finally semantics survive source-path class-path and archive round trips`
- `first-failure finally runs at O3`

Retain the resource-header tests' rejection and recovery coverage, not an
immutable diagnostic string. When the recommendation changes as described in
section 9, update the existing full-message expectation in
`CompilerTests.tryWithResourcesSyntaxIsRejected` with it.

Confirm names using `./scripts/test.sh --list`. Use repeated exact `--test`
arguments and add only relevant existing regressions when an affected component
requires them. Follow [LOCAL_TESTING.md](LOCAL_TESTING.md): native integration
fixtures run at `-O3`; do not multiply this into an optimization-level matrix,
run unfiltered compiler/platform suites, or add hosted cross-platform builds.
After failures, rerun only failing or newly affected selections.

## 8. Performance acceptance

The baseline is semantically equivalent handwritten `try`/`finally`,
including the same early call captures, unchanged free targets, checked calls,
class-initialization points, resource lifetime, and allocation/free or pool
operations. Comparing against late-read locals or omitted exceptional cleanup
is invalid.

For nullable instance receivers, both fixtures save the receiver and arguments
at the same point without an outer receiver null check, then invoke through the
saved receiver during cleanup. Do not prevalidate only the handwritten fixture.
Exercise successful non-null calls and deterministic null failures, including
a body failure that remains primary with the null failure secondary; measure
failure cases separately. Inspect both emitted check duplication/code size and
the checks actually executed on corresponding paths, including equivalent
check elimination for receivers proved non-null. Multiple cleanup copies alone
are not evidence of extra executed checks or of performance parity.

Prepare deterministic paired workloads for:

1. Block exit with a simple void cleanup call and an observable checksum.
2. One allocation per iteration followed by free, with matching live counts.
3. Reuse of a preallocated pool item, released at each iteration's block exit.
4. Multiple deferred actions/captures and nested blocks with early return and
   loop transfers. Use fixed action counts (1, 2, 4, and 8) and record each
   variant's reachable exit kinds to expose cleanup-copy growth.
5. A fixed failure schedule with multiple failing cleanups, measured separately
   from successful steady-state execution.

Avoid socket latency and stdout in timed regions. Use identical fixed workloads,
toolchain flags, inputs, checksums, and warmup; alternate paired run order and
report elapsed time, ratios, and run-to-run spread. Keep enough observable work
to detect accidental optimizer deletion. Reuse `ironwood.bench` where practical
without introducing a new benchmark framework.

Inspect the relevant optimized LLVM and `-O3` machine-code regions using the
pinned LLVM 23 toolchain. Account for symbol renaming, code folding, equivalent
layout, inlining, and existing necessary null/initialization checks; compare
instructions, calls, spills, and stack usage rather than requiring byte-for-byte
binary equality.

For the multi-defer fixture, compare the direct nested handwritten baseline
and, where equivalent, a flatter handwritten cleanup structure. Equivalence
requires the same capture/activation timing, lifetimes, LIFO order, attempted
remaining cleanups after failure, exception precedence, and pending transfers.
A plain list that skips later cleanup after an exception is not a valid baseline.
Document the equivalence argument or why a flatter form cannot preserve it.

Measure executable text bytes in the final linked binaries using the selected
LLVM toolchain's section inspection and disassembly. Report the relevant text
sections, such as ELF `.text` or Mach-O `__TEXT,__text`, plus code bytes for the
fixture functions and any outlined cleanup helpers. Include inlined cleanup in
its caller's size and count folded/shared code once. Use the same harnesses,
link settings, and reachable application operations for each comparison. Report byte deltas and
ratios for each action-count/exit variant, and attribute differences to cleanup
copies, helpers, checks, inlining, or alignment. Report changed unwind or other
non-code sections separately; total executable file size is not a text-size
measurement.

Acceptance requires:

- No defer-specific heap allocation, native-heap action storage, dynamic cleanup
  stack, registration helper, registry lookup, TLS access, synchronization, or
  per-operation runtime tracking. Fixed compile-time capture/cleanup metadata
  must not turn into such bookkeeping in generated programs.
- Matching managed allocation counts and expected live-object counts, including
  the first successful call. Inspect generated/runtime calls as well, because
  managed counters alone cannot establish absence of native allocations.
- Equivalent necessary work on successful paths. The keyword must not add
  capture copies, spills, calls, or activation checks beyond those required by
  the equivalent handwritten semantics. Captured values may naturally remain
  live longer than in a different program that does not need those values.
- No avoidable executable-text growth attributable to defer lowering against
  the equivalent handwritten baselines, including the flatter form where valid.
  Investigate excess cleanup-copy growth as actions or exits increase, even if
  timings match. Explain layout/alignment differences with disassembly; resolve
  unavoidable code-size regressions with the maintainer before acceptance.
- No reproducible slowdown attributable to defer lowering. There is no blanket
  percentage allowance: investigate timing differences with repeated paired
  evidence and disassembly. Resolve any unavoidable cost with the maintainer
  before accepting it; do not trade away safety to meet timing.
- Existing exceptional-path secondary metadata is permitted on its current
  terms. It must not migrate onto successful paths or acquire defer-specific
  allocation or retention overhead.

Keep generated artifacts in ignored `target/` or `workspace/` output. Record
reproduction commands, compiler revision, toolchain/host, exact selected tests,
allocation results, text-size tables, relevant disassembly findings, and timing
observations in a focused verification document linked from this plan. Do not claim validation
for untested platforms or equate a single timing sample with parity.

## 9. Documentation and delivery

During implementation, synchronize `LANGUAGE.md`, `LANGUAGE_SPECS.md`,
`MEMORY.md`, `COMPILER.md`, relevant Java compatibility documents, the example
index, and affected project guides. Add concise formatting guidance for the two
forms. Keep implementation and pending work clearly distinguished in the roadmap
and D051/D168. The resource-header diagnostic can recommend ordinary `finally` or
explicit `defer` only after defer is implemented. Change `Parser.parseTry`'s
shared diagnostic and the full-message expectation in
`CompilerTests.tryWithResourcesSyntaxIsRejected` together. Keep both resource
forms rejected and preserve the malformed-header recovery assertions; retain
the tests listed in section 7 while updating the expected recommendation.
Run the two focused resource-syntax tests after that implementation change.

Search every occurrence of feature 72 and try-with-resources in
`IRONWOOD_VS_JAVA.md`, including the matrix, section prose, snippets, and summary.
Its Java construct remains unsupported; the Ironwood alternative gains explicit
deferred actions. Revise the old rationale opposing every second cleanup
construct without incorrectly claiming Java resource syntax was implemented.
Do not mark any unrelated feature complete or select the next implementation.

Use original source under the repository's default license. Implementation
changes need focused behavior tests, `git diff --check`, and
`./scripts/check-licenses.sh` as required by `AGENTS.md`. The completed planning
gate required documentation checks only; implementation checkpoints require the
applicable focused behavior and optimized-code checks above.

Each internal checkpoint and milestone ends with a local reviewable commit and
evidence summary on `new-defer-keyword`, without rebasing or integrating
`origin/main` between them. Preserve unrelated human edits and report blockers
rather than weakening the contract.

After both milestones and their reviews are complete, report the local branch's
results and wait for the maintainer's explicit integration instruction. Do not
automatically rebase, merge with `main`, or push. If a final rebase is directed,
fetch `origin` then and rebase `new-defer-keyword` onto `origin/main`. Resolve
conflicts while preserving the reviewed behavior and human changes, and run
focused checks affected by the integration, including performance/code-size
checks where their evidence is invalidated. Record results against the resulting
revision. Perform only the integration and publishing operations explicitly
directed by the maintainer.

Milestone 1 was reviewed before performance-stage selection. The completed
performance stage now awaits review. Adoption and integration require explicit
maintainer direction; Milestone 2 as a whole remains incomplete.
