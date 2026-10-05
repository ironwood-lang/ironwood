<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M3.1 stack, order, value and callback helpers

Status: passes ([D264](../../DECISIONS.md#d264---provide-the-m31-stack-order-value-and-callback-helpers),
with the split bounds and prefix copies of [D266](../../DECISIONS.md#d266---bring-split-bounds-prefix-copies-and-ir-names-forward-to-m31)).
These compiler-private helpers cover the M3.1 collection, worklist,
sorted-container, value and callback demands of the M0 inventory that no
existing API or recorded convention already covers. The evidence run is in
[helpers-evidence/manifest.json](helpers-evidence/manifest.json), reproduced
by [run-evidence.sh](helpers-evidence/run-evidence.sh). All sources are
original and use the default license.

## Consumer mapping

| Java demand (inventory) | Native treatment | First consumers |
| --- | --- | --- |
| LIFO `ArrayDeque` push/pop/peek, iteration, `List.copyOf`, `restoreDeque` (Q21-Q41) | `ScopeStack`: `push`, `pop`, `peek`, `get(depth)` innermost first, `snapshot()`, `restore()` | FunctionAnalyzer scopes and contexts, FinalFieldAssignmentAnalyzer, EffectivelyFinalCaptureAnalyzer, EscapeSummaryAnalyzer, OwnedArrayFieldAnalyzer, the generic solver's Tarjan stack |
| FIFO `ArrayDeque` (Q01-Q11, Q28, Q40, Q42-Q44), `ArrayDeque(Collection)`, `addAll` | D255 `WorkQueue`; seeding and `addAll` are ordered `add` loops; two drained queues stay two queues | ClosedWorldPruner, specializers, SelectiveInlining, CFG closures |
| `TreeMap`/`TreeSet` with String keys, `sorted()` over Strings | hash or linked container for lookup; at each observation copy the keys into a list by indexed `add` and `sortWithComparator(StringOrder)` (D263) | BridgeApiFacts, observer projections, semantic display-name sites |
| `sorted(Comparator)`, `List.sort`, `ArrayList.sort` | a named comparator class and `sortWithComparator`; composed `comparing`/`thenComparing` become one `compare` with the same key order | AnonymousParentBinder, LexicalTypeScopes, EscapeSummaryAnalyzer witnesses, Tarjan components |
| `Stream.min`/`max` | `Extremes.minimum`/`maximum`: first equal candidate wins, null when empty | FunctionAnalyzer free witnesses, SemanticAnalyzer scopes, LexicalTypeScopes |
| `Set.copyOf` queried for membership | `SnapshotSet`; traversal keeps a separate ordered member list (D257) | semantic snapshot membership |
| `List.of` with one to four items, longer literals | `Lists.single`, `Lists.of`; longer literals fill a builder and freeze it | AST/IR constructors |
| `String.split` on one literal character (`TypeName`, `TypeResolver`, path lists) | `Splits.bounds` field bounds plus `Splits.field` for kept substrings (D266) | `TypeName.displayReference` (S2), `TypeResolver` (S3), `CommandLine.parsePathList` (S4) |
| `subList(0, count)` copied into record components | `Lists.prefix` for `SnapshotList` and `SnapshotInts` (D266) | `TypeName.qualifierReference` |
| `getClass().getSimpleName()` of IR records in diagnostics | `IrModel.javaName` (D265, D266) | Bridge analyzer diagnostics |
| record `equals`/`hashCode` over list and map components | `Lists.equal`/`hash`; `Maps.equal`/`hash` over a `SnapshotMap` and its key list | IR records with `List` and `Map<String, ...>` components (`IrCallInstruction`, `IrImmortalObject`) |
| `BooleanSupplier`, `Function`, `Predicate`, `Consumer`, `Supplier`, `BiConsumer`, `Function.identity`/`UnaryOperator.identity` | `BooleanSource`, `Mapper`, `Condition`, `Action`, `Source`, `PairAction`, `IdentityMapper` | flow helpers, IrCfgRenamer, TemporaryBorrowAnalysis, reclaimInOrder, l-values, clone observers |
| omitted generic bounds | every port type and method parameter spells `extends Object` (audited) | all port generics |

The four compiler-defined callback interfaces (`SemanticAnalyzerFactory`,
`FunctionAnalyzer.LValueWriter`, `AnonymousParentBinder.PlanningContextFactory`,
`InvocationPlanningContext.ExpressionProbe`) keep their own domain signatures
when their consumers are ported; only `@FunctionalInterface` is dropped.

## Results

| Check | Result |
| --- | --- |
| Stack differential | 3,000 seeded push, pop, peek, full and outer traversals, saves, restores, innermost-first searches and clears equal a Java 21 `ArrayDeque` with `List.copyOf` snapshots and `restoreDeque`. |
| Order and value differential | `StringOrder` signs over all 225 pairs of fifteen strings (empty, prefixes, U+0000, U+00E9, U+E000, a supplementary pair, isolated surrogates, U+FFFF) and a stable sort equal `String.compareTo` and `List.sort`; first-tie minimum and maximum over every prefix of a tied sequence, from lists and snapshots, equal `Stream.min`/`max`; list and map equality and hashes equal `List.of` and `Map.copyOf`; set membership equals `Set.copyOf`. |
| Split differential | Every string over `{a, b, '.', '/'}` up to length five, split on `.` and `/` with limits -1, 0, 1, 2 and 3 (13,650 cases, including ten empty results), equals Java 21 `String.split`. |
| Prefix copies | Item and int prefixes of every length, their hashes and the out-of-range rejection equal `List.copyOf(subList)`. |
| Callback differential | The flow helper restores its depth after a throwing source; retained renamer mappers number fresh values on first encounter and pass labels through unchanged; the scan stops after the first match; actions run in reverse candidate order; the l-value reads before the right-hand side and writes after it; the retained clone observer sees original then clone. The transcript equals the `java.util.function` reference. |
| Allocation | A stack costs two allocations; pushes within capacity, pops, peeks, depth reads and a restore into sufficient capacity cost none; growth costs one. A named or capturing callback costs one allocation and a call none. |
| Failure | Every allocation limit from 0 up to the first succeeding limit fails cleanly: a failed push leaves the stack unchanged, and the live-allocation count returns to its baseline. |
| Ownership pairs | All modes: stacks, snapshots, set snapshots and fixed-arity lists retire; freeing a stacked or snapshotted item, using a freed stack or snapshot, freeing a callback before its holder and freeing captured state while its callback can run are rejected. |
| Reference bounds | All 35 port generic declarations spell a bound; `ScopeStack<int>`, `SnapshotSet<long>`, `Mapper<int, ...>` and `SnapshotList<boolean>` are rejected. |
| Existing consumers | `Lists.iron` gained methods, so its consumers were rerun: the three M1 value-helper tests and the nine M2 pilot tests pass. |

All fixtures compile and link with `--unfreed=warn` and no diagnostics, from
classes and archive at `-O3`.

## Retained boundaries

- Stacked and queued items escape conservatively; their owners keep them
  alive, as the analyzers' contexts are analyzer- or invocation-lived.
- `Lists.single` and `Lists.of` expose their items; a list of fresh items
  keeps them invocation-lived.
- Captured callback state stays invocation-lived.
- A list lent to a helper, copied and lent again through the copy in the same
  frame stays unfreeable there (M1); the fixtures give each copy its own frame.
- A named local alias of a callback result keeps its referent observable until
  the end of its block; read the result as a temporary or in a helper frame.
- The first `HashSet` performs one process-lived class-initialization
  allocation.
