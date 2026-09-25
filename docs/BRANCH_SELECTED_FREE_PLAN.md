<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Branch-selected allocation reclamation plan

Status: Proposed on 2026-09-25. Not accepted, not implemented. This document
records the pre-change review required by
[AGENTS.md](../AGENTS.md#verification) and the
[regression lessons](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes)
before any change to the shared ownership analysis. Nothing here authorizes
implementation; the maintainer selects milestones explicitly.

## 1. Problem

A reference that is assigned a different fresh allocation on each branch of a
structured join cannot be freed, and the abandoned allocations are never
reported by `--unfreed`. All three ordinary Java forms are affected:

```java
Shape shape = big ? new Circle() : new Square();
free shape; // rejected: no proven fresh allocation origin
```

```java
Shape shape = null;
if (big) {
    shape = new Circle();
} else {
    shape = new Square();
}
free shape; // rejected for the same reason
```

```java
Shape shape = switch (kind) {
    case 0 -> new Circle();
    default -> new Square();
};
free shape; // rejected for the same reason
```

Verified on the current compiler on 2026-09-25: each `free` fails with
`cannot prove free of 'shape' safe: value is not a known allocation created by
new in this method, returned by a proven fresh factory, or a proven detached
private backing array`. With the `free` removed, `--unfreed=error` compiles all
three programs with no diagnostic, so the allocations leak silently. The same
program written as a factory method, `return big ? new Circle() : new Square();`,
is freeable by its caller because symbolic return analysis proves the result
fresh on every path. The gap is therefore intra-procedural only.

### Cause

`mergeAllocationIdentity` in `FunctionAnalyzer` gives a merged reference an
identity only when every incoming operand designates the same allocation, or
when the non-null operands are dependent borrows of one root. Two distinct
sites produce no identity for the phi, and each site is blocked with
"allocation may still be observed through a merged reference". A blocked site
leaves the ACTIVE state, so the missing-free tracker no longer observes it.
This is the documented conservative core from D027, which lists "values crossing
structured control flow" among rejected cases. It is a limit, not a defect, but
it contradicts the intent of D140 because the omission is invisible.

## 2. Goal and boundaries

Accept `free` and `defer free` for a reference selected by a structured,
mutually exclusive join when every alternative is a fresh allocation that no
other observer can reach. Report such a reference through the ordinary
missing-free diagnostic when it is abandoned. Change nothing for programs the
compiler accepts today.

Out of scope for the first version, and therefore unchanged:

- Loop headers and back edges, including `for`, `while`, `do`, and enhanced
  `for` joins. The existing loop rule, "cannot prove free safe across loop back
  edge", remains.
- Joins that include exceptional predecessors: `try`/`catch`, `finally` copies,
  and cleanup exits. Joins that merely occur inside a protected region are
  ordinary joins and are covered.
- Switch statement exits, labeled transfers, and `yield` from nested
  statements. Only the switch expression result phi is covered.
- Joins where any incoming value is `null`, a parameter, a field load, a
  borrowed helper, or an owned-field view. The existing nullable-view and
  borrowed-root rules remain.
- Ownership transfer, exact-type recovery for the selected object, and any
  runtime metadata. The exact class remains unknown at compile time, exactly as
  for a proven fresh factory result of a non-final return type.

## 3. Pre-change review

### 3.1 Invariants that must survive

- D005 and D027: `free` is accepted only when no later observation is possible
  through a local, parameter, return, field, array element, static, closure,
  call-mediated alias, widened reference, exception wrapper, catch value, or
  pending cleanup path. Conservative rejection remains correct.
- D083: an accepted object `free` runs the runtime-selected most-derived
  destructor chain. Closed-world destructor effects are resolved from the
  operand's static type; `ClosedWorldEffectAnalyzer` already does this for
  every `IrFreeInstruction`, so a phi operand with an interface static type
  needs no new mechanism.
- D140 and D145: missing-free findings are diagnostic only, reported in final
  lowering, and never weaken safety. `@SuppressUnfreed` follows the tracked
  allocation of a declaration's initializer.
- D168: a local with a pending deferred free cannot be reassigned; captured
  references stay visible to lifetime analysis until cleanup finishes.
- D132 and D133: no per-operation runtime bookkeeping. This change emits only
  ordinary `IrFreeInstruction` operations that the programmer wrote.
- Typed IR and LLVM output for every program accepted today must be byte-for-byte
  unchanged. The change only turns rejections into acceptances and silences into
  warnings.

Accepted new semantics, distinct from implementation details: a merged
reference over distinct fresh, unobserved alternatives is one owned allocation
of unknown exact class. Everything in section 4 about identity objects,
redirection, and snapshots is implementation and may change.

### 3.2 Machinery changed and its consumers

Producer: `FunctionAnalyzer.mergeAllocationIdentity`, reached from three sites
for the covered joins: the conditional expression result phi, the switch
expression result phi, and `mergeValue` for locals merged by
`mergeEnvironment` at `if.merge`. The same helper is also reached from other
`mergeEnvironment` callers (switch statements, loop exits, labeled transfers)
and from expression-level merges; those callers must keep today's behavior
through an explicit join kind.

Consumers of allocation identity that will see the new merged identity:

| Consumer | How it is affected | Required behavior |
| --- | --- | --- |
| `lowerFreeOperand` proof chain | Receives a merged identity from `allocationOf` | Ordinary checks apply unchanged: state, dependent borrow, deferred actions, retaining owners, yield, array slots, local aliases |
| Ownership snapshots and `mergeOwnership` | The merged identity is created after the incoming snapshots are merged | Later joins treat it like any allocation; the retired alternatives keep a blocking state so a stray lookup never frees them |
| `UnfreedAllocationTracker` | Alternatives were registered at their `new` or fresh-call sites | Consume the alternatives, register the merged identity at the join, keep `@SuppressUnfreed` and naming working through the declaration path |
| `RejectedFreeEvidence` and `--explain-rejected-free` | Origins, reasons, joins, and bindings are keyed by identity | Record an origin for the merged identity; keep alternative origins reachable for notes; never crash with explanations enabled |
| Deferred free and deferred call operands | Look up identities through `allocationOf` | A deferred free of the merged local behaves like a deferred free of a factory result |
| `knownArraySlots`, `retainedBorrows`, `poolOwners`, `borrowedOwnedFields`, `exposedContainerContents`, `pendingYieldAllocations` | Keyed by identity | Coalescing is refused when any alternative appears in any of these; after coalescing, none can gain the alternative |
| Typed-IR passes: `ClosedWorldEffectAnalyzer`, `TemporaryBorrowAnalysis`, `FreshArrayElementAnalysis`, `BorrowDispatchAnalysis`, `PrimitiveGenericSpecializer`, `IrCfgRenamer`, `LlvmEmitter` | See an `IrFreeInstruction` whose operand is a phi result | All already accept arbitrary reference operands; destructor targets resolve from the static type |
| Escape and symbolic-return summaries | Compute facts over typed IR independently of `FunctionAnalyzer` identities | Unchanged; they already prove `return c ? new A() : new B()` fresh. Verify that a freed phi inside a helper does not change a caller-visible summary |
| Class and archive reconstruction | Reanalyze preserved source at link | Same source, same analysis; verify with a source, loose-class, and archive link |
| Language server diagnostics | Report the same findings | Warning appears for an abandoned merged reference; no other change |

### 3.3 Safe and unsafe pairs

Each accepted form is paired with the nearest unsafe variant. The unsafe
variant must be rejected in `--unfreed=off`, `warn`, and `error`.

| Safe, must be accepted | Unsafe or unsupported, must be rejected |
| --- | --- |
| `Shape s = c ? new Circle() : new Square(); free s;` | `Shape s = c ? new Circle() : null; free s;` (null alternative, unchanged rule) |
| `if (c) { s = new Circle(); } else { s = new Square(); } free s;` | Same, but `else { s = new Square(); other = s; }` then `free s;` (live local alias) |
| Same with both classes declaring destructors, each observed natively | Same, but one branch stores `s` into a static field (escape) |
| `switch` expression with two `new` arms, then `free s;` | Same, but one arm yields an existing parameter |
| Nested: `s = c ? (d ? new A() : new B()) : new C(); free s;` | Same, but one branch frees its allocation before the join (`MAYBE_FREED`) |
| `defer free s;` after the join, exception thrown later, cleanup observed | `free s; s.use();` and `free s; free s;` (post-free use, double free) |
| One alternative is a proven fresh factory result, the other is `new` | One alternative is a `list.add` borrow or an array slot store in that branch |
| `@SuppressUnfreed Shape s = c ? new A() : new B();` silences the finding | Abandoned merged reference without the directive now warns in `warn` and fails in `error` |
| Both branches select the same existing allocation, `c ? a : a` | Loop header join: `s = new A(); while (...) { s = new B(); } free s;` stays rejected |
| Same-class alternatives `c ? new A() : new A()` | `try { s = new A(); } catch (E e) { s = new B(); } free s;` stays rejected in v1 |

Equivalent forms to compare: the three source shapes in section 1 must accept
and reject identically, and the factory-method form must keep its current
caller-side acceptance.

### 3.4 Focused checks and expected outcomes

New registered tests, proposed names:

- `branch-selected allocations accept free and deferred free` (semantic, all
  three unfreed modes, safe column of 3.3, typed IR contains the free).
- `branch-selected allocations reject aliases escapes and partial frees`
  (semantic, unsafe column of 3.3, three modes, 30 case/mode assertions).
- `branch-selected allocations report abandonment and honor suppression`
  (unfreed tracker: warning text and span at the join, error mode failure,
  `@SuppressUnfreed` on the declaration, no finding after `free`).
- `branch-selected frees run natively with destructors` (native `-O3`: two
  classes with destructors printing their name, program run with and without
  an argument, live-allocation baseline restored, deferred free on a throwing
  path).
- `branch-selected frees survive artifact reconstruction` (source, loose
  class, and archive links produce identical native behavior).
- `branch-selected frees explain rejected alternatives` (explanations enabled:
  alias, escape, and partial-free rejections print notes and never crash;
  existing join-note tests unchanged).

Existing focused tests to run unchanged, because they cover the machinery
touched:

```sh
./scripts/test.sh \
  --test 'safe free accepts local allocation and ended aliases' \
  --test 'safe free rejects live aliases and escaped allocations' \
  --test 'safe free rejects unknown identities and uncertain control flow' \
  --test 'safe free rejects double free and post-free use' \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'safe free lowers to inspectable typed IR and LLVM' \
  --test 'safe free runs natively' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'deferred free emits independent typed cleanup copies' \
  --test 'rejected frees identify the matched deferred-free binding' \
  --test 'unfreed diagnostics identify abandoned allocations' \
  --test 'unfreed diagnostics preserve retained and reclaimed allocations' \
  --test 'unfreed options preserve native output and artifact diagnostics' \
  --test 'rejected free keeps borrowed owner uncertainty on its accepted merge path' \
  --test 'rejected free preserves branch reclamation and field proof boundaries' \
  --test 'rejected free distinguishes incoming branch facts without changing join reasons' \
  --test 'rejected free explains both ordinary branch witnesses' \
  --test 'rejected free explains switch dispatch and continuation paths' \
  --test 'rejected free explains conditional and short-circuit expression paths' \
  --test 'rejected free explains try catch and exception predecessors' \
  --test 'rejected free explains loop condition and break predecessors' \
  --test 'rejected free bounds nested joins and discloses incomplete alternatives' \
  --test 'control-flow evidence stays bounded across generated joins and cleanup' \
  --test 'safe free accounts for reference-array element aliases' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'modern switch lowers integral enum String null and result phis through typed IR' \
  --test 'conditional expressions use target types and common supertypes' \
  --test 'conditional and switch arguments use unambiguous parameter types'
git diff --check
```

Expected outcome: every listed test passes without modification. A listed test
that needs its expectation changed is a signal that accepted behavior moved,
and requires maintainer review before the change proceeds.

Unchanged-output check: compile the `examples/` and `projects/` sources that
contain no branch-selected free before and after the change and compare typed
IR and LLVM text. A diff means the change touched an accepted program.

### 3.5 Unverified boundaries, stated

- The first version does not prove that every join kind excluded in section 2
  is unreachable through the new code path; it relies on the explicit join kind
  passed by the three covered call sites. The negative tests in 3.3 for loops
  and `try`/`catch` protect that boundary.
- The interaction with `yield` from a nested block inside a switch expression
  arm (pending yield allocations) is refused by the observer check rather than
  modeled. A future version may relax this.
- Explanation notes for the merged identity are functional, not exhaustive.
  D184's bounded-evidence limits apply.

## 4. Design

### 4.1 Contract

At a covered join, the merged reference gains a fresh identity when all of the
following hold on every incoming path, checked against that path's own
environment and ownership snapshot before merging:

1. The incoming operand designates an allocation with origin `LOCAL_NEW` or
   `FRESH_CALL`, state `ACTIVE`, present, and not a dependent borrow.
2. The alternatives are pairwise distinct. Paths that designate the same
   allocation keep today's same-identity rule instead.
3. No incoming operand is `IrNull`.
4. On that path, no local other than the local being merged maps to the
   alternative. For an expression result phi, no local maps to it at all.
5. The alternative does not appear as a key or value in known array slots,
   retained borrows, pool owners, borrowed owned fields, exposed container
   contents, pending yield allocations, or any pending deferred operand or
   deferred free.
6. The alternative has no recorded borrowed helpers of its own
   (`finalBorrowedFields` empty, no retained-borrow entry keyed by it).

When the conditions hold, the analyzer creates one `AllocationInfo` with the
`FRESH_CALL` origin at the current control-flow depth, sets `constructedType`
to the shared exact type when every alternative has the same non-null exact
type and to `null` otherwise, records its origin at the join span, and maps the
phi result to it. When any condition fails, behavior is exactly today's:
alternatives are blocked with the merged-reference reason and the phi gets no
identity.

### 4.2 Retiring the alternatives

Each alternative is retired so that no later lookup can free it separately:

- `allocationsByOperand` entries for the incoming operands are re-pointed to
  the merged identity.
- A `mergedInto` map records alternative to merged identity. `allocationOf`
  follows it after the existing pool-owner chain, with the same bounded loop,
  so a stale operand held by evidence or by a branch-local still resolves to
  the merged identity. This is what makes the result order-independent: an
  alias merged earlier or later at the same join resolves to the same identity
  and is caught by the ordinary local-alias check at `free`.
- The alternative's state becomes `UNCERTAIN` with the reason "allocation
  identity was merged into a branch-selected reference". This is defense in
  depth; the redirect should make the state unreachable.
- The missing-free tracker consumes each alternative and registers the merged
  identity at the join span with the description "branch-selected allocation".
  The declaration path then names it after the local as it does for any
  initializer, and `@SuppressUnfreed` suppresses it through the same call.

### 4.3 Where the check runs

`mergeAllocationIdentity` gains a join-kind parameter. Only the three covered
sites pass the coalescing kind:

- the conditional expression result phi after its own environment merge;
- the switch expression result phi after `mergeEnvironment`;
- `mergeValue` when called from the `if.merge` environment merge with no
  exceptional or loop predecessors.

All other callers pass the existing kind and keep current behavior. The
per-path checks in 4.1 need each path's environment and snapshot, which the
`BranchFlow` records already carry; the conditional and switch sites keep their
branch environments in scope at the merge.

### 4.4 Free, defer, and explanations

Freeing the merged identity goes through `lowerFreeOperand` unchanged. The
emitted `IrFreeInstruction` carries the phi operand; LLVM lowering dispatches
the destructor at runtime, and closed-world effects resolve from the static
type. A deferred free binds the local as today. For explanations, the merged
identity's origin is the join span, and a `Join` record lists each alternative
with its own creation site so a rejected free can show "created here or here".

### 4.5 What does not change

No runtime code, no metadata, no allocator change, no new syntax, no new
option. Programs accepted today produce identical typed IR, LLVM, and native
executables.

## 5. Milestones and gates

### Milestone 0: contract review

The maintainer reviews sections 2 through 4. Open questions to decide:

- Whether `if`/`else` joins are included in the first version or only the two
  expression phis. The if/else form is the most common in Java-style code, so
  this plan includes it.
- Whether a `null` alternative should be supported later as a nullable owned
  result, mirroring nullable fresh factory results.
- The exact diagnostic wording for the merged origin and for abandoned merged
  references.

### Milestone 1: expression phis

Implement the contract for the conditional and switch expression result phis.
Add the semantic, unfreed, native, and reconstruction tests restricted to those
forms. Gate: all new and listed tests pass; typed IR and LLVM equality holds for
the unchanged-output check.

### Milestone 2: if/else joins

Extend to `mergeValue` at `if.merge`. Add the if/else rows of section 3.3 and
the order-independence case: two locals assigned in both branches, one aliasing
the other in one branch, in both declaration orders. Gate: same as Milestone 1
plus the explanation test.

### Milestone 3: documentation and decision

Record decision D185 in `docs/DECISIONS.md` refining D027's structured
control-flow exclusion and D140's coverage. Update `docs/MEMORY.md` (ownership
and missing-free sections), `docs/LANGUAGE.md` (explicit memory reclamation),
`docs/EXPLAIN_REJECTED_FREE.md` (merged-reference reason and new origin note),
and the `README.md` memory-management note if the Hello World example changes.
Add a focused example under `examples/` only if the maintainer selects it.

## 6. Estimated size and risk

Roughly 150 to 250 lines in `FunctionAnalyzer`, mostly the per-path observer
check and the retirement bookkeeping, plus tests and documentation. Risk is
moderate: the change is confined to one producer, but that producer feeds every
reclamation proof. The paired tests, the unchanged-output check, and the
explicit join kind are the controls. A failure in any listed existing test
stops the work for review rather than being fixed by adjusting the test.
