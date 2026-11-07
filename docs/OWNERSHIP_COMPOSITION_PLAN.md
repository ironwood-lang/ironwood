<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ownership composition across APIs and collections

Current disposition, 2026-11-07: **prior alternative for managed-heap
reclamation, superseded by GC5-GC7**, accepted in [GC10](GC10_ACCEPTANCE.md).
The original 2026-11-04 audit, proposed syntax and implementation milestones
below are historical and unimplemented. Do not implement owning collections,
consume/adopt syntax or managed-free proofs in parallel with GC.

Shared/cyclic managed graphs now use exact reachability and explicit synchronous
Memory.collect(). GC7 deletes the cited owned-field/element, FunctionOwnership,
OwnershipPaths and OwnershipSnapshot machinery; those historical paths are not
current compiler dependencies. Resource-contract lessons remain: distinguish
retention from explicit closure, preserve acquisition rollback and exception
ordering, expose actual conservative effects, and keep pool/foreign lifetime
protocols and D132/D133 performance requirements. Reuse, stale-slot clearing,
allocation volume and retained snapshot/cache bounds remain relevant to capacity.
See the [current self-hosting handoff](SELF_HOSTING_PLAN.md#current-gc-handoff-2026-11-07).
Java remains authoritative; no new self-hosting attempt is started.

Date: 2026-11-04. Source audit baseline:
`b9cda8871479c7ac515defd6c794b50554fe29e3`.

Original status: proposed implementation plan for recommendation 1 in
[SELF_HOSTING_PLAN.md](SELF_HOSTING_PLAN.md#prerequisites-before-another-migration-attempt).
This document authorizes no language change or renewed compiler migration.
The proposed ownership-composition implementation below was not added. Names
for proposed directives and APIs remain historical design notation, not accepted
syntax. Java remains the bootstrap.

## 1. Goal and recommended scope

Make the following operation practical and statically safe: a helper creates an
object, another API takes responsibility for it, a collection owns that object,
and explicit destruction of the collection releases its owned storage. Borrowed
IR, symbols, strings and other externally owned payloads must remain alive.

Recommend an opt-in, checked ownership contract, followed by one small owning
sequence in `ironwood.ds`. Preserve existing borrowing collections and pools.
Begin with ownership transfer on successful normal return, one consumed argument
per call, exact call targets, private owned fields and exclusive ownership.
Prove this bounded model before adding extraction, maps or polymorphic contracts.

First test the smaller alternative: current fresh factories, owning creation
arrays and confined borrowers might already support a suitable API convention.
If they solve the representative problem without moving construction into every
owner, prefer that convention over new syntax. A new ownership feature must
remove a demonstrated boundary, not merely rename an existing proof.

Success is a general API boundary and a compiler-shaped workload that can use
it safely. It is not an assertion that this feature alone makes I2 fit 16 GiB.
The stopped migration measured allocated-but-not-freed storage, not GC
reachability or the fraction attributable to missing ownership transfer.

## 2. What the audited pre-GC implementation provided

These findings come from reading retained source and specifications. No new
compiler, native benchmark or reproduction was run for this planning document.
M0 must qualify its executable baseline before implementation.

| Existing mechanism | Evidence and consequence |
| --- | --- |
| Fixed responsibility for existing allocations | [MEMORY.md](MEMORY.md): assigning or passing an existing allocation generally borrows it. Fresh unaliased results can establish caller ownership. These are different operations. |
| Destructor-owned private storage | `OwnedArrayFieldAnalyzer` (historical path `compiler/src/main/java/ironwood/compiler/semantic/OwnedArrayFieldAnalyzer.java`) admits encapsulated storage installed from proved fresh origins. It is not a general proof that an incoming parameter becomes owned. |
| Borrowing containers | [DataStructureSemantics](../compiler/src/main/java/ironwood/compiler/semantic/DataStructureSemantics.java) identifies retained insertion arguments. Ordinary `ArrayList.add`, map insertion and set insertion do not transfer payload ownership. |
| Caller-item loans | [CONTAINER_REMOVAL_LOANS.md](CONTAINER_REMOVAL_LOANS.md) explains whole-container release, surviving aliases, duplicate occurrences, map roles and callback hazards. Clearing a container releases eligible loans, not its items. |
| Fresh owning creation arrays | `OwnedArrayElementAnalyzer` (historical path `compiler/src/main/java/ironwood/compiler/semantic/OwnedArrayElementAnalyzer.java`) supports once-recorded fresh children, direct borrowed getters, shallow storage growth and destructor loops. It deliberately excludes arbitrary adopted elements. |
| Helper composition already implemented | [D170 investigation](OWNERSHIP_HELPER_INVESTIGATION.md#authorized-implementation-follow-up) supplies confined temporary borrower and single-root list proofs. Do not reimplement these or describe them as ownership transfer. |
| Pools have a different contract | D104 supersedes D102's external-object transfer and runtime registry. [PoolSemantics](../compiler/src/main/java/ironwood/compiler/semantic/PoolSemantics.java) preserves originating-pool ownership on return. An old test or helper named "transfer" does not establish a general transfer API. |
| Existing caller state | [FunctionAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java) tracks allocation identity, state, retained borrows, pool ownership, dependent views, deferred actions and control-flow snapshots. Its `FREED` state cannot also mean "still alive under another owner." |
| Existing interprocedural facts | [EscapeSummaryAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java) and [SymbolicReturnOriginAnalyzer](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java) separate escape, fresh results and dependent returns. They do not expose a general, checked consume/adopt contract. |

### A concrete compiler boundary

`FunctionOwnership.snapshotOwnership` (historical path `compiler/src/main/ironwood/ironwood/compiler/semantic/FunctionOwnership.iron`)
returns a caller-owned snapshot. The retained
`OwnershipPaths` (historical path `compiler/src/main/ironwood/ironwood/compiler/semantic/OwnershipPaths.iron`)
instead creates snapshots inside `capture`, records them in its own creation
array, lends them and destroys them. Its capture method receives the builders,
maps, evidence and observer needed to construct the snapshot. This is a useful
existing owning implementation, but couples the owner to snapshot construction.

The motivating proposed operation is to create a snapshot through a helper and
then adopt it into a batch owner. The snapshot must preserve its identity and
independent membership. Its destructor must free its copies, never the borrowed
allocation identities, IR or shared child versions described in
`OwnershipSnapshot` (historical path `compiler/src/main/ironwood/ironwood/compiler/semantic/OwnershipSnapshot.iron`).
Explanation-enabled snapshots also require release of saved evidence before
destruction. A generic container must not silently omit that protocol.

## 3. Invariants and exclusions

Preserve D005's mandatory safe-free proof, D006's typed-IR boundary, D083's
destructor/rollback rules, D104's pool behavior, D107's borrowing collections,
D170's helper proofs, and D132/D133's performance constraints.

- No GC, reference counts, ownership registry, checkout flags or runtime alias
  scans. Ownership bookkeeping is compiler data. Real storage and destruction
  work remain ordinary program operations.
- No inference of ownership from a method name, a cast or a comment. A declared
  contract is a checked obligation, not a trusted escape-analysis exemption.
- Existing references remain Java-shaped. The new restrictions apply at explicit
  ownership boundaries; ordinary borrowing calls and collection APIs retain
  their contracts.
- No automatic reclamation of named allocations. Source `free`, existing
  deferred cleanup and explicit owning-collection destruction remain visible.
- No general heap-graph collector or arena. Owning edges form a forest; an item
  may borrow other objects only while their lifetime dependencies remain proved.
  Arbitrary cyclic sibling dependencies are outside the first implementation.
- Initially exclude adoption by constructors, foreign/Bridge calls, static
  destinations, escaping receivers, unknown dispatch, multiple consumed
  arguments, nullable/unproved origins, pooled values and arbitrary field reads.
  Reject unsupported cases at compilation rather than relying on documentation
  or runtime misuse checks.

An accepting decision must explicitly extend the fixed-ownership rule in
`MEMORY.md` for the new opt-in operation. It must preserve D005 and avoid reviving
the pool transfer removed by D104. Until then, normative language rules and the
feature-status matrix do not change.

## 4. Proposed semantic contract

### 4.1 Entry conditions and normal-return consumption

Use provisional compiler-owned parameter directive `@Consumes`, not a general
annotation facility. An ordinary caller-owned, non-null allocation can cross
this boundary only with a proved exclusive reclamation right. Its recursively
owned children follow that right; borrowed dependencies do not become owned.

Initially require a named local input with known allocation identity. Direct
unnamed allocation/factory arguments need a separate full-expression cleanup
check: success must cancel their old cleanup obligation, while a throwing call
must leave them eligible for caller-side temporary cleanup. Reject that form
until this is implemented; never let both the owner and temporary cleanup free
the same allocation.

Before the call, reject live container/field/callback/array aliases, dependent
views, publication, outstanding source deferred frees, ownership conflicts and
possible ownership cycles. Dead local aliases can be tolerated only when the
existing liveness proof establishes that they cannot observe the allocation.
Check identities, including two arguments referring to the same allocation.

The callee receives a provisional exclusive capability. On every normal return
it must have one proved disposition for the input: destroyed, adopted by a known
receiver slot, adopted by a nonconsumed owner parameter, or adopted by a fresh
returned owner. M1 supports destruction only. The other dispositions arrive in
later milestones. Identify destinations by resolved receiver/parameter origins
and field symbols, not by names or a generic "escapes" bit. A forwarding helper
must preserve the same destination and its entry preconditions.

| Exit or event | Required caller-visible result |
| --- | --- |
| Receiver/argument evaluation or class initialization fails before entry | Caller still owns the input; no transfer occurred. |
| Callee exits exceptionally | Input remains live, owned by its caller and unexposed; temporary loans have ended. Other input mutations must respect the declared API behavior. |
| Callee returns normally | Caller loses reclamation and access through pre-transfer references; the validated disposition identifies destruction or the new owner. |
| Outcome cannot be proved | Reject the contract or call; do not choose an optimistic owner. |

This is an ownership guarantee on exceptional exits, not a promise to roll back
every unrelated program side effect. The callee may not publish the input in an
exception or leave it retained by another object on that exit.

After the point where ownership commits, every remaining operation must be
proved unable to let an exception escape, or the method must restore the
original ownership relationship without destroying the input. For the initial
implementation admit only a nonthrowing tail after commit. That includes return
expression evaluation, implicit checks, class initialization, cleanup and
callbacks, not just explicit `throw` statements. A helper that calls `adopt`
and then performs a potentially throwing allocation fails this contract.

For the caller, normal-return consumption deliberately permits catch-only
cleanup. It does not permit an earlier unconditional `defer free` for the input:
that deferred action would still destroy the transferred item on success.
The following is a proposed API sketch, not runnable current Ironwood source;
`makeSnapshot` stands for a separately validated fresh-result factory:

```java
static void collect(OwnedArrayList<Snapshot> batch) {
    Snapshot snapshot = makeSnapshot();
    try {
        batch.adopt(snapshot);
    } catch (Throwable failure) {
        free snapshot;
        throw failure;
    }
}
```

The batch's caller remains responsible for its eventual explicit destruction.
The checker must preserve this behavior after extracting another forwarding
helper; it must not introduce runtime cleanup flags to make the example work.

Contract violations are mandatory errors in every missing-free mode, including
when `@SuppressUnfreed` is present. A successful handoff moves the explicit
cleanup obligation to the destination; it does not report the input as physically
freed. Omitting eventual owner cleanup remains a separate missing-free issue.

An annotation on a caller or an unverified body cannot manufacture the proof.
Start with nonrecursive static/final methods and complete bodies. Exclude
mutually recursive contracts and abstract/interface transfer declarations until
there is a separate checked rule for them.

### 4.2 Aliases, fields and returned owners

On successful transfer, any later use through the original variable or its old
aliases is an error, including `free`, field access, calls and deferred actions.
Reassigning a local to an independent allocation is allowed. Do not emit null
stores or hidden runtime moved-state flags to enforce this rule.

Use provisional `@Owned` only on private instance reference fields of final
classes in the first implementation. It declares a checked ownership slot;
it does not automatically generate source destructor behavior. A class must
explicitly destroy its owned storage, and all stores and destruction paths must
validate. Existing compiler-generated rollback still covers internally created
owned children. Parameter adoption during construction remains rejected.

M2 admits one empty slot and one adoption operation. Its inferred precondition
is that the receiver's slot is empty. Track this for the receiver allocation and
field symbol, not once per class or field name. A second adoption, a possibly
occupied slot at a join or mutation through an unknown alias is rejected. No
runtime empty-slot guard is added merely to enforce the contract. Replacement
is deferred; it needs its own destruction and alias semantics.

Check every legal writer, including nestmates and instantiated generic bodies.
Record slot states such as empty, occupied-by-known-origin and unknown at the
allocation/field pair. An annotation cannot hide an ordinary borrowing store
or make the state of one receiver establish the state of a second receiver.

Getter results are dependent borrows of the new owner. In the first version,
an outstanding observable borrow blocks owner destruction and mutation. Existing
borrow dependencies of an adopted child follow the destination owner; ownership
handoff never erases a dependency on longer-lived IR or other storage.

A helper may return a fresh owner containing an adopted input once body analysis
proves that ownership graph. Freshness belongs to the new root, not to the
adopted input. Returning an existing owned child or forwarding an existing owner
as an owned result is a separate future operation. Never implement it by
setting the current `mayReturnFresh` flag.

### 4.3 A deliberately small owning collection

Propose final `ironwood.ds.OwnedArrayList<E>` as an Ironwood-specific API. It does
not extend `ArrayList` or implement the first version of `Iterable`. Avoid
inheriting borrowing insertion, copied views, reusable cursors or default methods
whose lifetime contracts have not been proved. Names remain provisional.

| Operation | Proposed contract |
| --- | --- |
| Construction | Own the backing array; start with no elements. |
| `adopt(item)` | Consume one caller-owned item on normal completion; preserve caller ownership if growth or another pre-commit operation throws. |
| `size()` | Pure count query. |
| `borrow(index)` | Return an owner-dependent element borrow; bounds failure changes no ownership. |
| Destruction | Destroy each owned item exactly once, then free backing storage. |
| Later `clearAndFree()` | Explicitly destroy all items and empty the owner while retaining capacity; added only after reusable-storage proofs pass. |

The sequence's dense element storage is the ownership record. Do not add a
second registry or runtime duplicate scan. Static transfer checks prevent a
caller from adopting the same object twice. Existing `ArrayList` copies, map
keys and values, set members and pool checkouts remain borrowing contracts.

The initial backing-storage proof should extend the existing creation-array
model in one named, bounded way: an entry can be a validated consumed argument,
not only an internally fresh result. Validate every store and every read path;
do not grant this solely because a class is named `OwnedArrayList`.
An owned array field owns its array container; element ownership needs this
separate storage proof and explicit destructor loop. Ordinary array `free`
remains shallow.

Prove the sequence invariant structurally: each active slot has one owned item,
slots contain distinct ownership identities, and append writes only the next
inactive slot. Each successful insertion consumes its incoming capability.
Use a creation-family/slot invariant for loops, rather than enumerating runtime
items or conflating every allocation at one source site. If the existing
creation-array proof cannot support that extension soundly, M3 is blocked.
Bounds-checked borrowed getters also need a validated read-only guard and
owner-dependent result; the new class name is insufficient for either proof.

Growth must allocate first, shallow-copy references within the same ownership
root, publish the replacement storage and free only the old array container.
An exception before commit must preserve the old logical contents and item
ownership. Copied references in confined temporary storage are relocation,
not a second adoption. This requires proof, not a general `arraycopy` exemption.

Bulk reset needs a further proof: destroyed slots cannot later be traversed by
destruction, reads or growth. Use explicit detachment/clearing or a validated
active-prefix model. Do not reuse the borrowing list's size-only `clear()` as if
it destroyed objects. Any field clearing needed for actual destruction is
ordinary program work, not permission for runtime misuse bookkeeping.

Maps, sets and individual removal are intentionally later work. Equal keys can
have different identities, one value can occupy several roles, callbacks may
publish references, and a borrowed removal result is not an owned extraction.
The existing [removal-loan analysis](CONTAINER_REMOVAL_LOANS.md) remains the
starting point for that separate design.

### 4.4 Why this scope is preferred

| Alternative | Assessment |
| --- | --- |
| Current owner creates all children internally | First baseline; often sufficient and already implemented. Prefer it if helper construction remains reasonably independent. |
| Infer arbitrary ownership transfer from ordinary calls | Too ambiguous for existing aliasing code and exception behavior. Do not silently change old APIs. |
| Transfer at call entry on both success and failure | Viable alternative, but requires the callee to clean up or retain ownership on every failure. Do not mix this with normal-return consumption in the initial feature. |
| Make existing pools accept external objects | Conflicts with D104 and encourages registry/checkout machinery; not selected. |
| Add reference counts or a collector | Different runtime and language tradeoff; this plan does not require either. |
| General arena or owned extraction | Potential later work, with non-escape, graph and detachment obligations of its own. Not a shortcut around this proof. |

## 5. Compiler changes and affected consumers

The implementation stays in the Java bootstrap. Do not reconstruct the discarded
native compiler port as part of this feature. Retained Ironwood pilot sources
are consumers and test inputs, not a second production analyzer to maintain.

Contract checking must avoid circular self-justification. Parse directives as
claims. Check a consuming body under its stated exclusive-input precondition,
then prove its exit obligations; callers may use only validated summaries.
For the initial acyclic static/final call graph, validate callees before callers.
An explicit owned class supplies a storage invariant to check constructors,
every writer, readers and destruction against. Publish that invariant only when
the complete class proof succeeds. Do not feed an unverified declaration into
the existing inference loop as an already established owned-field fact.

| Area | Required work |
| --- | --- |
| Parser and AST | Extend parameter/field directive parsing with exact placement, duplicate and unsupported-form errors. Preserve source spans. `Parameter` currently records only type, `final`, name and spans; ordinary modifiers and general annotations must remain distinct. |
| Symbols and generics | Preserve contracts through `CallableSymbol` substitution, declaration identity, type-variable substitution and diagnostics. Ownership does not distinguish overload signatures. Reject an ownership-only overload; do not lose contracts through widening or casts. |
| Contract proof | Add one small immutable summary for validated entry requirements, normal dispositions, exceptional preservation and borrow dependencies. Derive it from bodies and completed effects; keep escape, return freshness and ownership disposition separate. |
| Field/element proof | Extend `OwnedArrayFieldAnalyzer` and `OwnedArrayElementAnalyzer` for explicit, validated adopted origins. Reuse encapsulation, destructor-effect and reentry checks. Do not widen every fresh-origin predicate. |
| Caller analysis | Extend `FunctionAnalyzer` allocation facts, owner relationships, pending actions and normal/exceptional edges. Preserve actual allocation identity while changing its reclamation authority. Source-variable access after transfer needs a distinct state from physical deallocation. |
| Joins and loops | Extend ownership snapshots, restore and merge together. Agreeing owners survive; incompatible or maybe-consumed states cannot grant a free or another transfer. Track dynamic creation families without treating every loop iteration as the same concrete object. |
| Shared interprocedural consumers | Escape, symbolic returns, temporary borrowers/lists, pool release, unfreed diagnostics, owned-field validation and final lowering must consume the same validated contract. Neither `fresh` nor `non-retaining` alone implies transfer. |
| Refinement | Integrate in `SemanticAnalyzer` without a new whole-program rebuild for every call. Admit facts only after their prerequisites validate. Initial unknown/incomplete facts must reject unsafe uses. Fail without an ownership verdict on nonconvergence. |
| Typed IR and LLVM | Represent the compile-time ownership contract/proof at the typed boundary so IR checks can inspect it. Calls, stores, `IrFreeInstruction` and destructor operations keep ordinary runtime semantics; do not implement ownership by LLVM text patches. |
| Artifacts and final link | Format-1 artifacts retain source. Reconstruct and revalidate contracts from those bodies at link, including changed providers. Do not trust stale producer summaries or add a binary format merely for annotations. An older compiler must reject unsupported source. |
| Diagnostics and tooling | Identify the consumption site, original owner, conflicting alias or earlier commit, and relevant exceptional operation. TYPE, explicit LINK and IDE analysis must agree. |

Audit `finishPoolTransfer`, `poolOwners` and similarly named legacy paths before
reusing them: they encode originating-pool and dependent-borrow behavior, not
the full new contract. Keep pool-specific facts separate from a general owner
relation. Avoid copying a large analysis framework or adding another parallel
set of ownership exceptions inside the already large `FunctionAnalyzer`.

For virtual/interface support later, validate the declaration contract and
every reachable implementation, including defaults and substituted generic
members. Incompatible effects must fail; an unknown target must not borrow the
best target's contract. The first milestone may reject the form entirely.

## 6. Safety and compatibility review before implementation

This is the required pre-change review from
[POOL_RELEASE_HELPER_REGRESSION.md](POOL_RELEASE_HELPER_REGRESSION.md#before-a-substantial-change).
Revisit it when scope changes. No broad suite substitutes for these pairs.

| Safe case to accept | Nearby unsafe case to reject |
| --- | --- |
| A checked consuming helper destroys its exclusive input | An ordinary borrowed parameter is freed, or the caller uses any old alias after a successful call |
| A throwing pre-commit path preserves caller ownership | A callee destroys/adopts the input and then lets an exception escape |
| Fresh helper result adopted into a known empty owner | Adoption into a second owner, a possibly occupied slot, itself or an owning ancestor |
| Independent owned children transfer with their root | A borrowed field, pooled checkout or element of an ordinary borrowing container is adopted |
| Getter result is used while its owner remains live | Freeing or mutating that owner while a local, field, array slot, callback, return or deferred action still observes the view |
| Borrowed IR outlives an adopted snapshot and its batch | Transfer erases an external borrow dependency or destroys the borrowed IR |
| Storage growth relocates the same owned entries | Old and replacement arrays both become item owners, or failure destroys caller-owned entries |
| A fresh item is created and adopted once on every iteration | Duplicate adoption, maybe-consumed branch reuse or a loop backedge with conflicting authority |
| A helper forwards a validated consuming call | The helper publishes the input, uses it after commit or calls unknown/retaining code afterward |
| Explicit batch cleanup includes evidence release | Destructor reentry, saved-evidence publication, allocation or outward throw is ignored to admit cleanup |

Also cover receiver null checks, argument evaluation order, later-argument
failure, class-initialization failure, early returns, catch/finally/defer,
pending yields, casts, generic erasure, primitive type arguments and all three
missing-free modes. Source, class-directory and archive reconstructions must
agree; changed provider bodies must invalidate earlier acceptance. Never execute
the body of a rejected unsafe program as a native test.

Existing exact primary selections to choose by milestone include:

- `pool ownership rejects dangling and conflicting aliases`
- `pool release helper proofs preserve mandatory safety`
- `pool release helper proofs survive artifact reconstruction`
- `owning pools roll back allocation failures without leaks`
- `data structures destroy internal pools and preserve caller items`
- `private backing arrays are freed only after proven detachment`
- `field loans reject reentry through implicit code loops handlers and cleanup`
- `data structure release proofs reject surviving aliases`
- `data structure caller loans survive allocation failures`
- `temporary constructor helpers preserve mandatory ownership proofs`
- `temporary list helpers preserve element owners`
- `temporary borrower proofs survive artifact reconstruction`
- `borrow dispatch rejects retaining and unknown receiver flows`

Run only the relevant exact names through `scripts/test.sh --test 'EXACT NAME'`,
plus new focused parser, semantic, typed-IR, native and negative owners. No
unfiltered compiler/platform suite or hosted build is part of this plan.

## 7. Milestones and stop criteria

Each milestone is a separately reviewable local change with a frozen focused
input selection, implementation, paired regressions and concise result notes.
Finish a gate before expanding the contract. The following effort ranges are
planning estimates, not measured completion promises or authorization; re-estimate
after M0. This is several weeks of language/compiler work, not a small container
cleanup. Do not spend the whole estimate before demonstrating the first gate.

| Milestone | Deliverable and gate | Initial effort estimate |
| --- | --- | --- |
| M0: reproduce the boundary | Reduce snapshot helper-to-owner handoff to a small fixture. Compare caller cleanup, D170 helper inference and an existing fresh creation-array owner. Record current accepts/refusals and exact aliases/exception paths. Freeze baseline outputs and benchmark sizes. Prefer existing semantics if they suffice. | 2-4 engineering days |
| M1: consume-only helper | Proposed directive on one exclusive non-null parameter of a static/final nonrecursive void method. Prove normal destruction and exceptional preservation; reject post-call aliases and deferred frees. Complete parser/AST/symbol/typed ownership handling with no runtime bookkeeping. | 1-2 engineering weeks |
| M2: one owned slot | Explicit private owned slot in a final receiver, empty-slot precondition, adoption, dependent getter and explicit destructor. Preserve owned children and external borrows. Qualify a fresh returned owner and a forwarding helper. No constructor adoption, replacement or extraction. | 1-2 engineering weeks |
| M3: owning sequence | New narrow `OwnedArrayList`, adopted element origins, exact-once destruction, borrowed indexed reads and failure-safe growth. Allocation failure at every storage-growth boundary must preserve ownership. No inherited borrowing API. | 2-3 engineering weeks |
| M4: repeated-use and compiler pilot | Add and prove explicit bulk reset if needed. Adapt one retained snapshot-batch fixture to helper creation plus adoption; preserve capture/path order and saved-evidence release with explanation both off and on. Compare to the best current legal owner-factory implementation. | 1-2 engineering weeks |
| M5: public qualification | Close source/class/archive and changed-provider checks, optimized-code/allocation gates, language/library contracts and focused regression selections. Publish the limits and decide which later ownership operation is justified. | 3-5 engineering days |

M0 is the immediate next task if implementation is authorized. If the smaller
existing-feature convention solves it, stop the syntax branch of this plan and
qualify that convention. If normal-return consumption requires runtime ownership
flags or cannot be checked with a bounded proof, stop M1 and revisit the contract.
If owning-array adoption requires unsound alias exemptions, stop M3. Do not
replace a failed gate with a larger memory allowance or a special case naming
the compiler's classes.

Do not start maps, general ownership-return/extraction, callback-owning APIs,
constructor adoption or arbitrary polymorphic transfer to rescue a failed pilot.
Each needs a demonstrated consumer and a separate design increment. Preserve
existing collection and pool behavior throughout.

## 8. Evidence that would make the feature useful

Use three comparisons: the best current legal owner-factory implementation,
the candidate helper/adoption implementation, and a compile-only currently
unsupported handoff. An intentionally retaining legal variant may illustrate
cost, but is not an adequate performance baseline by itself.

Freeze at least three input sizes and repeated batch counts after M0 measures
what is practical. Exercise branches and joins, not only a chain of empty nodes.
Expected results include exact snapshot content, identity, order, retained public
versions, evidence behavior and destructor counts. Treat externally borrowed
payloads as deliberately retained in both variants.

Required observations:

- After a completed batch is explicitly destroyed, managed live-allocation
  counts return to the pre-batch level except documented retained outputs. Repeated
  batches must not accumulate owner/item storage. This does not prove absence
  of every leak or measure GC reachability.
- Allocation counts and time are compared with the best legal baseline. Adoption
  itself adds no helper allocation, ownership table, dynamic alias check or
  reference-count update. Necessary vector growth and item destruction remain.
- Charged footprint and RSS are recorded independently of object counts. RSS
  need not return to baseline because allocators can retain pages; after warmup
  repeated identical batches must show a bounded high-water footprint.
- At O3 inspect the actual calls, append/growth and destruction code. Ownership
  proof metadata must disappear. Compare with the equivalent manually arranged
  owner/factory code and the relevant deterministic benchmark under D132/D133.
- Record proof passes, compilation time and compiler memory on increasing inputs.
  A feature that simplifies source but introduces another refinement cycle or
  pathological analysis growth fails the scalability gate.

M0 must turn these into numeric budgets and acceptance tolerances before
candidate measurements. Keep reports compact; raw logs and images are ignored.
Run focused checks once per relevant source change and repeat only for a new
failure or unresolved concern. A kernel result permits this ownership feature
to progress; it does not reopen migration or establish whole-compiler capacity.

## 9. Documentation and acceptance

At a milestone's accepted implementation, update `MEMORY.md`,
`LANGUAGE_SPECS.md`, `COMPILER.md`, `STDLIB.md`, the new API documentation and
the relevant compatibility docs. Apply the behavioral-contract review to every
admitted public call, including casts, generics and inherited members. Maintain
source spans and diagnostics. Add an explicit numbered decision for the accepted
semantics; state exactly which earlier restrictions it extends or supersedes.

Implementation should be original first-party source under `MIT OR Apache-2.0`.
Extending `ironwood.ds` needs no imported collection framework. Run license and
tracked-file checks when source or evidence is added. No current feature matrix
entry is promoted by this planning document.

The final feature result must say which API handoffs now work, which safety
cases remain rejected, what repeated-use memory and proof costs were observed,
and which useful compiler lifetime boundary was simplified. Only a later,
separately authorized self-hosting checkpoint can establish I2/I3 readiness.
