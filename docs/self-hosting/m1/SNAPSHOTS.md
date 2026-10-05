<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 private snapshot increment

Base: 85c8fa01 (the qualified ArrayList increment). This increment prepares two
compiler-private helpers and strengthens their shared compile-time proofs.
M1.1/M1.2/M1.3 remain incomplete, and no S1/G1 budget is evaluated. Original M0
and J0/D247 identities are unchanged. The source/hash and complete-log ledger is
[snapshot-evidence/manifest.json](snapshot-evidence/manifest.json).

## Storage and behavior

`SnapshotList<E>` owns a private independent list and its backing/iterator,
preserves ordered identity and rejects a null builder. Items are borrowed,
non-null under ArrayList's existing insertion contract. Its size/get/isEmpty
interface does not expose membership storage. Returned aliases conservatively
borrow the snapshot's lifetime. Normal and partial destruction reclaim private
storage, never payloads. Empty and 8/32/128/512-item construction use four
allocations each; ordinary ArrayList.copy still uses three.

`SnapshotBits.copy` uses existing new-plus-or, owning independent primitive
words. Nonempty sets use the existing capacity bound; empty sets use one word.
Negative/overflowed capacity uses a one-word result followed by ordinary or
growth. Logical equality, mutation independence, null input, empty/trailing-zero
8192-bit capacity and the maximum valid index 2147483647 pass. Overflow does not
become a negative-size trap. Capacity preservation is not a public contract.
Ordinary empty/63/64/65/4096-bit copies use two allocations. The maximum-index
fallback grows normally and the dedicated fixture peaks near 512 MiB.

The independent Java 21 `SnapshotReference` checks the same selected List.copyOf
and BitSet logical contracts, including cursor continuation, identity/order,
null, trailing zeros and overflow. It is an original API-level oracle, not an
oracle for Ironwood reclamation or the combined real-source J0 pilot.

## Compile-time safety

The constructor proof requires exactly one owned private final field in a final
ordinary Object-derived owner, no enclosing owner/delegation/initializer, one
null-only guard and one verified fresh-list assignment. It copies actual item
loans from an exact unexposed local ArrayList. Exposed, dependent or unknown
sources retain their whole roots. Mutation/publication/delegation/nonfinal-field
controls retain ordinary exposure. Count helpers must be structurally read-only
when individual item loans are projected; older whole-root factories retain
their previous count contract and both selected existing consumers still pass.

The first getter experiment admitted an alias after builder, snapshot and item
destruction. This is preserved as a failed safety probe. Final reference reads
lend the snapshot owner and unrecognized snapshot methods expose actual payloads.
A proved primitive payload value/hash read permits cleanup after its last use;
publishing methods and overrides do not. Passing a returned alias to another list
retains the snapshot until that list releases it. The current primitive precision
is a single-return private int-field expression, not an unknown-effect exemption.
All unfreed modes reject early items, aliases, nested snapshots and retained
payloads. Mandatory errors may occur at free or the subsequent use.

Conditional receivers and static delegating getters remain conservative. A
conditional choice between independent snapshots keeps both roots blocked by
the existing merged-reference rule, including cleanup after a primitive read.
A static getter forwarding to get exposes payloads through the ordinary unknown
helper-call rule, so later payload cleanup is rejected. Both unsafe alias-after-
retirement variants are rejected in all unfreed modes. These are recorded
limitations, not accepted safe-cleanup witnesses or new borrowing exemptions.
The qualified precision is the direct copied-wrapper read; further consumer
precision must be justified separately by actual selected demand.

Earlier generic self-casts and E[]-to-Object[] count probes hit excluded type
boundaries and are not safety witnesses. Final valid count controls use direct
null/size writes and concrete Object-list overrides. Some guard/virtual-size
definitions fail ordinary internal ownership before caller cleanup; logs identify
these separately. No suppression or default-library warning-mode change is used.
The constructor that publishes its in-progress receiver is already rejected by
the ordinary constructor-publication rule without caller frees; its control is
classified separately too. Captured-owner construction remains conservative.

## Executed checks

The focused 3-test constructor/read/artifact group passes, the affected 7-test
snapshot/root/fresh-field/failure group passes, and the final 5-test group adds
count purity and maximum-index coverage. Final class/archive consumers accept
builder retirement followed by snapshot/item cleanup and reject an alias after
all three retire. Source compilation and links explicitly use --unfreed=warn.
Dedicated retained compile/link transcripts report zero warnings, zero unique
warning sites and zero suppressions. Standard-library rebuilds remain strict.
Representative combined list/bit construction fails cleanly at allocation limits
0 through 10 and succeeds at 11, with zero live delta after every run. Null
exceptions use ordinary thrown-object lifetime; the native storage baseline is
taken after those contract checks. Each null failure additionally must retain
exactly the same live-allocation delta as directly throwing/catching the same
NullPointerException, isolating exception lifetime from failed-wrapper storage.
IronDocs generates two types. License and
diff checks are retained in the ledger.

## Bit-copy cost comparison

The reproducible O3 fixture performs five batches of 100000 copies per width,
checking cardinality and zero live delta. The original one-word initialization
uses 300000 allocations for wider batches; the selected bound uses 200000.
Logical-length initialization avoids growth but scans the final word; scanning
for the first higher bit adds unnecessary traversal for sparse wide inputs.
The selected existing capacity bound avoids those scans.

The final comparison measured medians of 4.207 ms versus 2.637 ms at bit 64 and
8.476 ms versus 7.107 ms at bit 4096, improvements of 37.3% and 16.2%. One-word
cases used the same allocation count and cost about one additional nanosecond per
copy (4-5% in that short microbenchmark); earlier runs varied by a few percent.
This is an initialization tradeoff, not a universal speed claim or a G1 result.
O3 disassembly shows ordinary inlined bit copying, allocation and growth logic;
no compile-time lifetime metadata is emitted as runtime bookkeeping.

Remaining M1 work: value/identity/linked map and set copies with independent
entry pools, immutable keyed projections and operation snapshot composition,
the selected FIFO/LIFO consumers, record/text/numeric/factory/callback/variant
helpers, and the combined helper hash/handoff report. B2 sorting remains
unselected. M2 adds actual frontend and operation consumers and qualifies G1.
