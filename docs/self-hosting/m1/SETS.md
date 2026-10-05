<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 independent set copy increment

Base: 98974945, the qualified map-copy increment. This completes the three
selected set-copy families. Private keyed snapshots, operation composition,
FIFO/LIFO consumers and B7 helpers remain; M1.1/M1.2/M1.3 stay open. This does
not evaluate S1/G1 or alter immutable M0/J0/D247 evidence.

## Contract and storage

Original distinctly named `copy()` helpers construct the same set family from
an independent private backend map copy and separately owned reusable iterator.
Non-null items remain borrowed. Storage destruction never frees caller items.
Value copies rerun hash/equality callbacks and propagate their exceptions;
identity copies invoke neither callback. Linked copies preserve insertion order.
No source cursor is reset or advanced. Callbacks must not mutate source
membership during traversal. Existing public constructor contracts remain.

The copied map uses load factor 1.0 and `max(1, size())` initial capacity.
Unused source pool capacity is not copied. Empty copies use ten allocations;
8/32/128/512-item copies use 17/41/137/521, one entry per item plus nine support
objects. The outer set and iterator add two allocations to the qualified map
copy. Measured bucket and both pool-array capacities are `max(1, size())`.
This is allocation/storage scaling evidence, not a timing or G1 claim.

HashSet and LinkedHashSet's existing lazy process-lived filler objects are
initialized before private-storage baselines. The OOM fixture includes both
initializer allocations, updating the baseline after each successful initializer
and checking every limit 0 through 80: 0-79 fail cleanly, 80 succeeds. Both class
and archive executables cover outer set, map, pool preloading and iterator
construction. Normal scaling fixtures reclaim every item, array and container.

## Compile-time proof and boundaries

The fresh factory proof accepts only the actual private copying constructor:
Object superclass, no capture/delegation/field initializer, two private owned
fields, a final backend map, the proved map-copy assignment and one fresh owned
iterator. The iterator constructor must confine its owner argument and publish
neither owner nor receiver. A bodyless backend copy produces ordinary diagnostics
and no proof. General constructor observation is unchanged.

Callback requirements travel with borrowing inputs through factory delegation
and copied-wrapper construction. Actual value-item hash/equality dispatch must
prove non-retention at the caller; identity inputs have no callback obligation.
Exact unexposed local sets transfer their current item loans. All set items are
possible callback keys. Nested/self dependencies persist, and exposed/dependent
or unknown sources keep a whole-root loan. No runtime metadata, safety tracking
or LLVM lowering change is introduced.

Clear/destruction may retire membership loans subject to real aliases;
individual removal does not discharge them. Iterator aliases keep their owner
alive. Lookup on a copy of an already exposed source remains conservative and
can expose its whole-source fallback loan, blocking subsequent source retirement.
That failed combined cursor/lookup cleanup probe is retained as boundary
evidence. Native cursor and lookup/order checks use separate containers and
reclaim both. Ordinary cursor/lookup tests use literal or explicit process-lived
inputs; they do not claim heap-input retirement. Separate lifetime and scaling
tests prove complete owned input cleanup.

## Qualification

Focused tests pair safe source/result/item cleanup and delegated factories with
early item, self, nested-key, returned-view and hash/equality publication rejection
under every unfreed mode. Conditional insertion and copy-of-copy retain nested
key publication; identity counterparts reclaim everything. Two constant-hash
keys reach the publishing equality override. Changed source mutation/publication
constructors receive no proof. Direct iterator-constructor publication is
rejected by the existing in-progress-construction rule even without caller
frees and is a definition control. Publishing reset bodies are admitted without
caller frees; ordinary confinement/ownership checks reject later retirement.

Source/class/archive checks cover independence, collisions, equal-but-distinct
dynamic identity keys, linked order, source cursor continuation, geometric
allocations, all representative OOM limits and ordinary throwing hash callbacks.
Both value families propagate the original preallocated exception on the second
copy callback, reclaim partial storage and preserve the source cursor. Identity
copy executes zero callbacks. The independent Java 21 oracle checks the selected
logical contracts, not native ownership or free semantics.

The affected map and private-list snapshot regressions, strict library build,
public IronDocs, license audit and diff check are recorded with exact commands,
complete logs, warning classifications and source/artifact identities in
[the set evidence ledger](set-evidence/manifest.json). Port source compilation and
native linking explicitly use `--unfreed=warn`; the library build remains strict.
