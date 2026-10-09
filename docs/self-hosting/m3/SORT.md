<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M3.1 list sort (B2)

Status: passes. `ironwood.ds.ArrayList.sortWithComparator(Comparator<? super
E>)` is B2's list-level sort ([D263](../../DECISIONS.md#d263---sort-array-lists-stably-with-an-explicit-comparator)).
It is the only public library change in M3.1 and the sorting primitive behind
the tree-container rewrites and the S3/S4 sort consumers. One evidence run on
a fresh `git archive` of d077d1ff passed every step; its
[manifest](sort-evidence/manifest.json) hashes the sources and retains the
compressed logs, and [run-evidence.sh](sort-evidence/run-evidence.sh)
reproduces it.

## Contract and behavioral review

| Question | Answer |
| --- | --- |
| Java behavior admitted | `List.sort(Comparator)` with a non-null comparator: stable, in place, comparator exceptions propagate. Every admitted argument is a `Comparator<? super E>` reference; no widening or inherited default applies, because Ironwood's `Comparator` declares only `compare`. |
| Deliberate differences | A distinct name, no `sort`, no natural-order fallback: `null` throws `NullPointerException`, even for an empty list. No `ConcurrentModificationException` (no runtime misuse tracking, D132/D133): a comparator must not mutate the list, and a traversal in progress continues by index. An inconsistent comparator yields a permutation rather than Java's optional `IllegalArgumentException`. |
| Compile-time boundary | `list.sort(c)`, `list.sort(null)` and `list.toArray()` do not compile (`omissions` test). D122's Comparable-bounded array sorts are unchanged. |
| Storage | Only `[0, size())` is compared. Size, capacity, growth and the iterator index are unchanged. At most sixteen elements: no workspace. Otherwise one `size() / 2` reference array, freed on every exit. |
| Failure | A comparator exception or workspace allocation failure leaves exactly the same elements; the workspace is freed. |
| Ownership | Not an audited container operation: the call exposes stored elements (they cannot be freed afterwards), and comparator effects stay ordinary. List, comparator and workspace retire normally. |
| Provenance | Original Ironwood implementation, default license. No OpenJDK source was consulted; Java behavior was observed through `java.util.ArrayList.sort`. |

## Results

| Check | Result |
| --- | --- |
| Java differential | 720-line transcript (15 sizes 0-4,096, four key ranges, four input shapes, three comparators including a two-key comparator) equals Java 21 `ArrayList.sort` byte for byte in four fresh JVMs, from classes and archive at `-O3` with `--unfreed=warn` and no diagnostics. |
| Native contract | Null comparator at sizes 0, 1, 40; stale records in cleared and removed slots never compared; refilling to capacity after sorting allocates nothing; the iterator continues at its index; failure at every comparison point for sizes 12 and 100 keeps a permutation and the live-allocation count; a `Comparator<Shape>` sorts `ArrayList<Record>` stably; a comparator that grows the list mid-sort (freeing the old array) keeps every original element once. |
| Allocation | 0 allocations for at most sixteen elements, exactly 1 otherwise, live count unchanged after each sort; the failure sweep fails construction and growth at limits 0-8, the workspace at limit 9 with membership intact, and succeeds from limit 10. |
| Ownership pairs | All three unfreed modes: list and comparator retire after sorting (direct and interface-typed comparators); freeing an element after sorting, freeing it while listed, freeing what a retaining or publishing comparator kept, and using a freed list or comparator are all rejected. |
| Existing consumers | 8 ArrayList, pool, generic-bound, copy and artifact consumers pass unchanged. |
| Hot path | With one comparator implementation the O3 merge loop inlines the devirtualized comparator into a single `cmp`; with two, the compiler's guarded devirtualization tests both types and falls back to one indirect call. Bounds and null checks branch to outlined failures; there is no call or bookkeeping on the valid path. |
| Non-callers | `ds_list_copy` links the same 199 functions with identical bodies before and after; only closed-world type IDs, dispatch slots and string-constant names are renumbered, as for any library addition. |
| IronDocs | `ArrayList` generated without diagnostics. |

Timing series from the evidence run (fixed-seed random keys, best of five,
macOS arm64 M5, one-minute load 2.89 and no overlapping work; comparison
counts are deterministic, and an earlier isolated run agreed within 5%):

| n | Comparisons | Per element | ns per element |
| --- | --- | --- | --- |
| 1,024 | 10,779 | 10.5 | 31.3 |
| 16,384 | 237,379 | 14.5 | 77.4 |
| 131,072 | 2,290,615 | 17.5 | 65.4 |
| 1,048,576 | 21,473,953 | 20.5 | 86.9 |

Comparisons grow as n log2 n and sorted input takes n - 1 at every size, so
no size shows quadratic growth. The records are scattered in memory, so time
per element also reflects cache misses. There is no recorded B0 sort budget;
the series is reference evidence for later consumers.

## Commands

```sh
./scripts/test.sh --test 'list comparator sort keeps storage and exposes sorted items conservatively' \
  --test 'list comparator sort omits natural-order and array adapters' \
  --test 'list comparator sort matches Java List.sort across artifacts' \
  --test 'list comparator sort unwinds every allocation failure'
docs/self-hosting/m3/sort-evidence/run-evidence.sh COMMIT
```

## Retained limit and next consumers

Elements stored in a sorted list stay exposed. Only lists whose elements share
one lifetime root could gain from an audited proof, because a multi-root
`get` already exposes elements; no M3 consumer needs that proof. The
tree-container rewrites and stream `sorted()` replacements in M3.1, the
semantic sort sites of S3 and `SharedTraceOrder`/`OptimizedTraceMetadata` at
S4 use this method.
