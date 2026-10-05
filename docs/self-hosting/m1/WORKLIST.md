<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 worklist and iterator-removal increment

Base: fa202046 (D254). This closes the M1.2 worklist and iterator sites named
by the M0 handoff: twelve `ArrayDeque` calls in
`ClosedWorldEffectAnalyzer.reachableBlocks` (WORKLIST_CONTRACTS Q20) and the
three `Iterator` calls in `RejectedFreeEvidence.retainArrayStores`. No stack
consumer is reached by the pilot, so none is added. D255 records the decision;
the ledger is [worklist-evidence/manifest.json](worklist-evidence/manifest.json).

## FIFO

`ironwood.compiler.port.WorkQueue<E>` keeps a power-of-two ring, a head index
and a count. `add` appends at the tail and `removeFirst` takes the head without
shifting, so a take is constant time and allocation-free. Consumed slots are
reused, which bounds the ring by peak membership rather than by every request
ever queued; this is the cursor requirement in WORKLIST_CONTRACTS. Growth
doubles a full ring in head-to-tail order and frees the old ring. The new ring
is installed before membership changes, so an allocation failure leaves every
queued item in place. Null items and empty takes fail as `ArrayDeque` does.

Items are borrowed. The existing escape analysis treats a queued item as
escaped, so freeing it while queued is rejected, and so is freeing it after the
queue is destroyed. The selected consumer queues IR block labels whose owners
outlive the analysis, so this conservative contract suffices for M2.

## Iterator removal

`retainArrayStores` keeps live slots and removes every other entry while
iterating. `ironwood.ds.HashMap` already provides iterator `remove()` and
`getCurrIteratorKey()`, so no new API is needed. The fixture forces one chain
of eight colliding String keys plus four distinct keys and removes heads,
middles, tails, none and all. The consumer's result is the surviving
membership and removal count, not the visit order, so the comparison is
order-independent.

## Evidence

| Check | Result |
| --- | --- |
| Java parity | `WorkQueueReference` (Java `ArrayDeque` plus the analyzer's exact reachability loop) and `IteratorRetainReference` (the exact retain loop) print the native transcripts byte-for-byte in four fresh JVMs each |
| Allocations | Construction allocates 2 (object and ring); 1,000 cycles of 16 adds and 16 takes allocate 0; the 17th item allocates 1 ring and frees the old one |
| Failures | Null add and empty take keep exactly the ordinary exception's live delta; OOM limits 0-3 (label table, object, ring, growth) exit 42 with storage reclaimed and order intact, 4-8 complete with 43 |
| Ownership | Off/warn/error: borrowed use accepted; freeing a queued item, using a freed queue and freeing an item after queue use rejected |
| Artifacts | Queue, failure and retain fixtures compile and link from classes and archive at `-O3` with zero `--unfreed=warn` diagnostics |
| IronDocs | One type generated without diagnostics |
