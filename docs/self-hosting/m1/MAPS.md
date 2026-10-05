<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 independent map copy increment

Status: the value, identity and linked map-copy slice qualifies. Full M1.1,
M1.2 and M1.3 remain open. Set copies, private keyed snapshots, operation
composition, selected worklists and B7 helpers remain. This does not evaluate
S1/G1 or alter immutable M0/J0/D247 evidence.

## Contract and implementation

Original Ironwood implementations add distinctly named `copy()` helpers to
HashMap, IdentityHashMap and LinkedHashMap. They preserve non-null shallow
key/value identities and each family's comparison rules. Direct bucket or
insertion-chain traversal leaves source iterator state untouched. Destination
buckets, entries, pool arrays, builder and reusable iterator are independent.
Linked copies retain insertion order; value copies rerun ordinary hash/equality
callbacks and propagate exceptions after cleanup. Identity copies invoke no
key callbacks. Callbacks must not mutate source membership during traversal.
Existing public constructors and insertion contracts remain unchanged.

Copies use the existing preloaded pool and load factor 1.0, with at least one
bucket. At 8/32/128/512 members each family uses 15/39/135/519 allocations,
exactly one entry per member plus seven support objects. Empty copies use
eight allocations. Bucket and both pool reference-array capacities are
`max(1, membership)` in these measured cases. No geometric copy growth occurs.
This records allocation/storage scaling, not a timing improvement or G1 result.
Every measured owned item, array and map is reclaimed in the scaling fixture.

## Compile-time proof and boundaries

The structural factory proof checks the fresh constructor, primitive capacity
expression, explicit failure cleanup, complete direct source traversal,
key/value projection and existing audited destination insertion target. An
unknown, mutating or publishing body retains ordinary conservative effects;
method spelling alone grants no borrowing exemption. Actual callback dispatch
must prove non-retention for value-key maps. Call-site resolved generic arguments
are preserved when the nominal result matches the proved allocation type.

Copies transfer actual current key/value lifetime loans from an exact unexposed
local map. Dependent, exposed and unknown source roots remain whole-root loans.
Nested/self dependencies persist. Possible key roots are monotonic observation
metadata, intersected with current retained loans before exposing nested key
contents. They never permit reclamation and cannot shrink on a branch restore.
A whole-root fallback becomes a possible key root for subsequent copies, rather
than treating an empty metadata set as proof that unknown keys are absent.
Ordinary nested values retain loans without being treated as callback receivers.
No snapshot format, runtime state, runtime safety tracking or LLVM lowering changes.

Individual removal does not discharge compiler loans. Clear/destruction can
retire membership loans, subject to actual retained aliases and publication.
Ordinary map getters/cursors still conservatively expose caller-owned keys.
Failed heap-key cleanup probes are preserved as boundary evidence, not accepted
cleanup witnesses. Native cursor/identity tests use two explicit global dynamic
equal String keys; callback-failure tests use three explicit global keys and a
preallocated global exception. Baselines start after those legitimate process-
lived inputs, isolate private copied-storage cleanup and do not claim input
retirement. Separate direct copy-loan tests prove full input/map cleanup.

## Qualification

Focused checks pair safe cleanup with early item, nested value, self-item and
publishing hash/equality override rejection under every unfreed mode. A two-key
constant-hash equals control actually reaches publication. Conditional insertion
and copy-of-copy preserve nested-key publication rejection, while identity-key
counterparts and ordinary nested-value full cleanup pass. Changed traversal,
source clearing and publication bodies do not obtain the item-projection proof.
The clearing control is a conservative shape boundary, not an executed dangling
reference. No-free controls independently establish source admission.

Source, class and archive inputs include the actual three map implementations.
Native checks preserve colliding value keys, equal-but-distinct dynamic identity
keys, linked order, source cursor continuation and independent mutations.
All allocation limits 0 through 65 fail cleanly; 66 succeeds, across class and
archive failure executables. An ordinary hash callback throws on the second
copy insertion for both value map families; the original exception propagates,
failed destination storage has zero live delta and the source cursor continues.
The Java 21 independent oracle checks the selected logical contracts and
exception behavior, not native ownership or `free` semantics.

The final five-test group also passes existing list-copy nested-loan and pool
helper safety checks. Formatting-only fixture edits rerun the affected artifact
group. IronDocs generates all three public types, the license audit passes and
`git diff --check` passes. Source compilation and linking explicitly use
`--unfreed=warn`; the library build remains `--unfreed=error`. Complete logs,
warning classifications, exact selectors, source hashes and bootstrap/runtime
artifact identities are retained in [the evidence ledger](map-evidence/manifest.json).
