<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 pre-change review

Entry: accepted M0/S0 at ef896bf6e4f253614f04d1e14dffae9741fc827b,
original J0 6bde84df320e9dbc32977a2b665d214ac09bc2d4, D247
28664736270f19a73c0a79a2f845bac916953471. M1.1/M1.2/M1.3 implement
increments 2/3 and the S1 part of 5. No B2 sort is selected. This work prepares
M2 and cannot establish S1/G1. All changes and commits are local on
before-self-hosting, without remote operations.

## Contracts and consumers

The immutable M0 handoff names 916 storage, 15 traversal and 399 helper calls,
723 syntax sites and 65 captured rows. Its original source ledger, finite
119-declaration frontend and 13-selected/31-restricted operation models remain
reference inputs. Restrictions in OPERATION_MODEL and OPERATION_FACTORY_CONTRACTS
must be enforced before consumer entry; same-file unselected bodies stay later.

ArrayList copies feed ordered AST child membership and private ownership
snapshot values. Copies own fresh backing and iterator state and borrow items.
HashMap/HashSet use value keys; IdentityHashMap/IdentityHashSet use identity keys;
linked families preserve encounter order. Each copy owns separate entries and
pools, leaves the source reusable iterator untouched and rejects the existing
null domain. Private immutable snapshots additionally preserve Java copyOf null
rejection and do not expose mutable storage. Mutable nested vectors are copied;
immutable retained-child versions are shared. D247 slots use ordered independent
membership and non-null keys/values. BitSet starts with new plus or, preserving
logical bits without promising clone capacity.

Selected FIFO consumer is ClosedWorldEffectAnalyzer.reachableBlocks. Queue
storage must clear consumed references, reset/compact without front-shifting per
operation, and preserve seeds, successor and re-enqueue order. Tail-backed LIFO
reads newest first and restores head-to-tail snapshots in reverse storage order.
No broader FunctionAnalyzer scope stack is selected merely by its file location.

Text helpers preserve stripIndent and raw/cooked UTF-16, nullable Character
with char plus presence, exact constructor validation order and reached TypeName
count wrapping. Record equality/hash follows fields, list ordering and member
identity. Fixed-arity helpers cover selected calls, preserving evaluation order.
Immediate/lazy loops and retained observer callbacks preserve capture lifetimes.
Render/locale/split, digests, streams/regex/Optional public APIs remain later.

## Shared machinery and safety

Existing DataStructureSemantics, fresh borrowing factory summaries, symbolic
return origins, temporary list borrows, owned fields/arrays and call-site loan
retirement are consumers of copy implementation shape. First inspect actual
compiler behavior; change analysis only for a demonstrated precision defect.
No copy-name whitelist, source-independence exemption, unknown non-retaining
effect or runtime ownership bookkeeping is permitted. A self-containing source,
item pointing back to a source, escaped iterator, nested snapshot or publishing
callback may retain the source or items. All unfreed modes preserve mandatory
errors. D132/D133 hot paths stay unchanged unless separately qualified.

## Focused verification selection

For each coherent implementation record exact executed test names and logs in
phase evidence. Pair accepted retirement after the last borrower with rejected
free of an item still present in the copy, source still reached through an item,
escaped iterator, nested snapshot and callback capture. Verify normal and
exceptional cleanup, invalid/null input and partial-construction OOM rollback.
Measure empty and geometric copies, independent mutation, source iterator
continuation and allocation/storage costs. Use existing allocator fail injection
and accounting rather than production safety tracking. If shared analysis changes,
repeat affected proofs through source/class/archive reconstruction and include
existing pool return, container clear and borrowed-view tests. Hot lowering changes
require O3 code and the relevant deterministic benchmark.

Port source compile and native link explicitly use --unfreed=warn, retaining
complete logs, per-command and unique-site classifications and suppression sites.
The standard library build remains strict. Run focused IronDocs validation and
license audit for public APIs, git diff --check and generated-text checks before
each commit. Hash helper .iron sources for M2.1's combined real-source bundle;
M2.1 adds Lexer/Parser/AST consumers and fresh Java qualification under fixed M0
caps before G1. M1 does not run native G1 or change retained M0 hashes.

Status: pre-change inspection in progress. No phase checkpoint passes yet.


## Private snapshot constructor increment

ArrayList slice is committed at 85c8fa01. SnapshotList's initial constructor
builds an independent copy into one private final owned field. A source probe
shows the existing unconditional constructor observation marks all builder
items escaped, preventing valid destruction even after the snapshot retires.
The unsafe prototype is conservatively rejected, not a demonstrated unsafe
acceptance. SnapshotBits compiles with existing new-plus-or and failure cleanup.

Qualify a structural constructor proof only for the actual private copied-field
shape: final ordinary owner, Object superclass, no capture/delegation/field
initializer, null-only input guard, one private final owned field initialized
by a body-proved fresh list factory, and no other body operation. Map factory
inputs back to the constructor parameter; successful construction borrows the
actual payloads through the snapshot owner. Exceptional construction rolls back
private storage and cannot publish a result or erase source loans. Mutation,
publication, delegation and unrecognized bodies retain ordinary exposure.

Focused cases: independent source retirement followed by snapshot/item cleanup;
rejected early item, source self-item, nested snapshot and returned-alias frees;
null/empty construction; every constructor/copy allocation-failure boundary;
publishing or mutating constructor/guard/getter controls; equivalent source and
class/archive consumer reconstruction. Rerun affected constructor-borrow, owned
fresh-field, pool rollback and copied-list loans only. No runtime bookkeeping,
source-independence exemption or unknown non-retaining effect is introduced.


Count-query review: constructor capacity and loop bounds need membership purity
when a factory projects a container's individual item loans. Keep the existing
non-retaining count contract for flat/root-borrow factories, whose results keep
the whole input root alive. Add a structural no-mutation count predicate only
for container-element projections, without method-name exemptions. Include
changed capacity/size self-item insertion, clearing/removal and publishing or
virtual query targets. The first generic self-cast probe hit an excluded checked
cast and is not a valid mutation witness; use an Object[] alias with an Object
list or a concrete Object-list subclass for admitted self insertion instead.
Focused existing consumers additionally include 'flat interface snapshots
preserve owned element borrows' and 'snapshot navigation and fresh cursors
preserve root borrows'. Preserve original source/class/archive shapes.

The attempted Object[] view of the generic E[] backing is also excluded by the
current type boundary and produces uncertain owned-array errors. Preserve both
invalid attempts as historical probe failures, not valid mutation controls.
The final controls remove those casts/views, use null/size membership writes and
concrete Object-list virtual methods, and independently require source admission
without frees before asserting the reclamation failure where ordinary ownership
admits the definition. Guard publication and two virtual size definitions fail
ordinary internal ownership first; record those separately rather than as
caller-loan witnesses. No unsupported cast/view qualifies a negative control.

The initial snapshot returned-alias test demonstrates an actual unsafe acceptance:
a copied wrapper's untracked getter result survived snapshot, builder and item
destruction. Add a narrow body-proved owned-list read, lending reference results
under the copied wrapper. Mark these constructor-proved allocations as copied
item owners so unrecognized methods conservatively expose their actual payloads.
Primitive delegated reads cannot mutate membership. Qualify direct field reads,
nested/returned aliases, constructor publication/observation/delegation/field
controls and late snapshot/payload publication through source and artifacts.
This metadata exists only during compilation and adds no runtime tracking.

Payload observation review: a getter alias is conservatively tied to the wrapper,
but a body-proved non-retaining primitive read of that payload must not expose
the wrapper's entire membership. Accept only the single-return primitive private
int-field expression shape for this increment and verify every resolved dispatch
target. Keep publication, unknown effects and publishing overrides conservative.
Pair value/hash reads followed by cleanup with publication and retained aliases
through another list. Bit snapshots compare one-word growth, logical-length bounds
and existing capacity bounds; qualify empty/trailing-zero storage and the maximum
valid bit index, whose int length/capacity overflow must not become a size trap.

Join/delegation review: pair a conditional receiver selecting two independently
constructed snapshots and a single-return static delegating getter. Observe
before cleanup in the safe case, reject a returned alias after retirement under
every unfreed mode, and preserve publication/unknown effects. Any precision must
derive from the same actual read body and existing one-of lifetime identities;
do not treat a join or helper name as non-retaining by convention.

## Independent map copy increment

First qualify value, identity and linked maps using their existing insertion
contracts and fresh constructors. Traverse private buckets or insertion links
directly, without resetting the source iterator. Destination entries and pools
are independent; keys and values retain their original identity. Value maps
recompute hashes and invoke ordinary key callbacks, so publishing callbacks
must keep the ordinary conservative effects. Identity maps do not invoke them.
Constructor sizing avoids ordinary geometric growth; measure actual allocation
counts before deciding whether lazy pool initialization is needed.

If a safe source retirement probe fails, extend the structural fresh factory
proof only for the observed traversal and insertion bodies. Keep exposed and
dependent source roots, nested/self/backlink loans, unknown helper bodies and
publishing virtual callbacks conservative. Check safe and unsafe cases in every
unfreed mode, source/class/archive reconstruction, iterator continuation,
colliding value keys, equal identity keys, linked order, empty/geometric sizes,
independent mutations and each allocation-failure boundary. Existing entry-pool,
pool-helper and list-copy checks cover shared consumers. Set and private keyed
snapshot composition remain a separate increment until map ownership qualifies.

The allocation probe additionally shows a fresh map factory result retaining
the declaration's generic key parameter instead of the typed call's actual key
type. Later insertion/removal therefore loses the existing key-callback proof
and merges an escaped state into conditional cleanup. Preserve the typed result's
arguments only when its nominal type exactly matches the proved fresh type.
Paired publishing-key/override tests must still reject actual unsafe callbacks.

Nested-value review: value-key copy callbacks may observe keys, not values. The
existing key/value loan union cannot distinguish them. Record a monotonic set of
possible key roots on each allocation, updated by audited insertion and copied
with factory membership. Intersect that set with the current retained loans
before callback exposure. This is an observation superset, never a reclamation
proof: joins/restores can retain extra possible keys without dropping any real
key, and clear still retires loans through the existing snapshot-tracked map.
No runtime state or snapshot format is added. Pair complete nested-value cleanup
with nested-key content publication and branch/clear/reinsert controls.

## Independent set copy increment

Map copies are qualified at 98974945. First probe a private set copying
constructor: a separate map copy followed by a new reusable set iterator.
This should avoid source cursor resets and unnecessary temporary storage.
Inspect constructor publication and private owned-field effects before extending
any proof. Preserve value/identity/linked semantics, borrowed items, failure
rollback and actual callback effects; no constructor/name exemption is allowed.
Pair complete source/result/item cleanup with early item, nested/self, callback
publication and returned-view lifetime rejection under every unfreed mode.
Measure empty/geometric allocations, independent mutations, linked order and
source cursor continuation, and qualify representative OOM/callback failures
through source/class/archive inputs. Existing map/list/pool consumers cover
shared machinery only if implementation actually changes their analysis.

The direct source probes reject item cleanup because the copying constructor
is reached through an unrecognized fresh factory. Qualify only its actual two
assignments: a body-proved private backend map copy and a separately owned,
confined iterator constructed with the new result owner. Do not suppress general
constructor observation. Carry callback requirements on borrowing inputs through
factory delegation and copied-wrapper construction; actual value-key dispatch
must still prove non-retention before any item projection is applied. Set items
are all possible key roots. Existing map callback and private snapshot checks
cover this shared input metadata, alongside iterator constructor/reset publication
controls, source-field mutation and no-free admission checks.

Set qualification: three families preserve full item cleanup, callback boundaries
and independent source/class/archive storage. Direct iterator publication is an
ordinary construction-definition error. Reset publication is admitted without
caller frees but blocks retirement; it receives no fresh copy proof. Abstract
backend-copy controls produce ordinary diagnostics without an internal failure.
The conservative combined exposed-source cursor/lookup cleanup remains recorded.
Native scaling, every OOM limit 0-80, ordinary callback exceptions, selected Java
contracts, map/snapshot/pool consumers, IronDocs and licensing pass. Full M1
remains open; the [set record](SETS.md) retains detailed commands and identities.

## Set construction rollback correction

The publishing-reset control without explicit caller frees is insufficient.
On review, a reset that publishes its owner and then throws admits source,
class and archive reconstruction under off/warn; error only reports a missing
free. No accepted unsafe native program was executed. Automatic constructor
rollback can retire the observable in-progress parent. This blocks acceptance
of the set increment until corrected; keyed-read work is paused.

Require confinement before passing an in-progress constructor receiver to a
helper constructor. Preserve ordinary confined owner capture, and follow direct,
converted, joined and returned receiver aliases rather than requiring a literal
this expression. Pair the published-owner failure with confined throwing helper
rollback and normal iterator construction. Reconstruct source/class/archive
inputs under all unfreed modes, requiring a mandatory safety diagnostic. Recheck
the strict library build, set/list/map copies, private snapshots and pool safety.
No runtime bookkeeping, preserved dangling object or weakened free rule is
permitted. The broader constructor API is not being redesigned.

The focused follow-up qualifies the correction from staged compiler sources,
preserving the paused keyed draft outside its producing compiler. Source/class/
archive rejection passes for all three set families in all modes. Direct,
converted, branch/loop, returned and virtual/interface aliases reject publication;
confined counterparts remain accepted. Native confined helper attempts allocate
four objects/arrays on both paths and restore their private-storage baseline.
The valid fixture's LLVM module is byte-identical before and after the check.
The strict library build and focused map/set/list/snapshot/pool consumers pass.
The old admission log is retained and never used as native execution evidence.
See D252 and [the correction ledger](set-rollback-evidence/manifest.json).
