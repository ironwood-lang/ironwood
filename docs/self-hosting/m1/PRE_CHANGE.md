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
