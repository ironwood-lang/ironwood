<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1 remaining-work classification

Retained pre-closure analysis. It classified every M1 handoff call, syntax row,
capture row and hash contribution at 035f81e1, before the composition and the
remaining M1.3 items were delivered. Its seven (C) items were then closed as
[CHECKPOINT.md](CHECKPOINT.md) records: C1 by D257, C2 by D259 (snapshot-owned
saved versions rather than the identity-keyed store suggested below, because a
value removed from a map is not a provable fresh allocation), C3 to C6 by D258
and C7 by D260. The text below is otherwise unchanged.

Read-only analysis of the M0 pilot handoff against the current branch
`before-self-hosting` at HEAD 035f81e1. Two M1 increments were committed while
this analysis ran, after the task's delivered list (D248-D254) was written:
8873984d (D255, compiler-private `WorkQueue` FIFO plus qualified iterator
removal, docs/self-hosting/m1/WORKLIST.md) and 035f81e1 (D256,
`TextBlocks.stripIndent`, docs/self-hosting/m1/TEXT.md). Both are counted as
(D) below. When the analysis finished, uncommitted work toward C1 was present
(untracked compiler/src/test/java/ironwood/compiler/OwnershipSnapshotTests.java,
integration-tests/cases/compiler_ownership_snapshot.iron and
compiler_ownership_snapshot_scaling.iron, plus a modified CompilerTests.java;
no new port source). Its fixture header describes a seven-field composition
with D247 slot order "through a copied key list". It is not committed or
qualified, so C1 remains open below.

## Inputs and conventions

- Handoff: docs/self-hosting/m0/pilot-handoff.json.gz, 1,330 calls
  (M1.1 916, M1.2 15, M1.3 399), 723 syntax rows (all M1.3), 65 capture rows
  (all M1.3), 446 hash contributions (M1.1 101, M1.2 345). Every call is
  covered by exactly one row below (the generator rejects uncovered calls).
- Contract citations are `FILE.md:line` under docs/self-hosting/m0/. Report keys
  map as: ast to AST_CONTRACTS.md, frontend to FRONTEND_CONTRACTS.md,
  diagnostics to DIAGNOSTIC_CONTRACTS.md, operation-factory to
  OPERATION_FACTORY_CONTRACTS.md, effects to EFFECT_CONTRACTS.md, worklists to
  WORKLIST_CONTRACTS.md, pilot-ownership to OWNERSHIP_PILOT.md, unfreed to
  UNFREED_CONTRACTS.md, builders to BUILDER_CONTRACTS.md. pilot-frontend rows
  repeat the frontend/AST/diagnostic row at the same line and are cited there.
  Every handoff call carries a non-empty contract (as `reviewed_contract` or
  `contract`); none had to be recovered from the *-reviewed.json.gz files.
- Site lines in the tables are original J0 lines (6bde84df). Since J0,
  RejectedFreeEvidence, UnfreedAllocationTracker, Lexer, Parser, SourceFile and
  the ir/ast/diagnostic packages are unchanged at HEAD (`git diff 6bde84df HEAD`
  is empty for them). FunctionAnalyzer differs (D247 plus M1 proof work):
  J0 snapshotAllocationStates/snapshotOwnership/restoreOwnership/mergeOwnership
  12506/12517/12532/12568 are HEAD 12740/12751/12766/12802; OwnershipSnapshot
  J0 14251 is HEAD 14490; uniqueIncomingArrayStore J0 12939 is HEAD 13173.
  ClosedWorldEffectAnalyzer.reachableBlocks is J0 199 and HEAD 207 (offset +8).
- Stdlib paths are relative to stdlib/src/main/ironwood/ironwood/ (ds/, util/,
  lang/, nio/file/). Port helpers are relative to
  compiler/src/main/ironwood/ironwood/compiler/ (port/). Every (A) mapping names
  a member found by grep in that file; the quoted line is its declaration.
- Classes: (A) existing Ironwood API with the needed semantics; (B) pure source
  rewrite convention in the M2 port, no new helper; (C) new helper/API or
  compiler-private component still required before M2; (D) delivered by an M1
  increment (D248 ArrayList.copy, D249 SnapshotList/SnapshotBits, D250 map
  copies, D251 set copies, D254 keyed snapshots, D255 WorkQueue/iterator
  removal, D256 TextBlocks.stripIndent). Where one pattern's sites need
  different treatments, the pattern is split into labelled rows; each row has
  exactly one class.

## Summary

| Phase | Calls | A calls (rows) | B calls (rows) | C calls (rows) | D calls (rows) |
| --- | --- | --- | --- | --- | --- |
| M1.1 | 916 | 518 (31) | 166 (22) | 106 (16) | 126 (13) |
| M1.2 | 15 | 0 (0) | 0 (0) | 0 (0) | 15 (8) |
| M1.3 | 399 | 304 (39) | 89 (26) | 5 (4) | 1 (1) |

The (C) rows reduce to seven deliverables, C1-C7, listed at the end.

## M1.1 patterns

| Pattern | Java API | Calls | Consumer sites (J0 file:line) | Reviewed contract (M0 citation) | Class | Ironwood mapping, difference or required work |
| --- | --- | --- | --- | --- | --- | --- |
| API0331 | `ArrayList.ArrayList()` | 55 | Parser 51, Lexer 3, SourceFile 1 | Independent shallow ordered builder storage, element identity retained; caller retires storage, never the AST elements. [FRONTEND_CONTRACTS.md:52] | A | `ironwood.ds.ArrayList` `public ArrayList()` (ds/ArrayList.iron:30). Difference: `add` rejects null with IllegalArgumentException (ds/ArrayList.iron:76-78), java.util.ArrayList accepts null; no selected site adds a null literal (grep of Parser/Lexer/SourceFile) |
| API0334 | `ArrayList.ArrayList(Collection<? extends E>)` | 3 | Parser:800, Parser:1758, Parser:2837 | Independent shallow ordered mutable copy before mutation; a live wrapper or reused iterator is not a copy. [FRONTEND_CONTRACTS.md:52] | B | All three sources are immutable record lists, not builders: `parsedBody.statements()` (Block), `pending.labels()` (ParsedSwitchLabels copies with List.copyOf, Parser.java:3112-3115), `firstArguments` (ParsedTypeArguments copies with List.copyOf, Parser.java:3090-3093). In native these are `SnapshotList`, so D248 `ArrayList.copy()` does not apply; rewrite as `new ArrayList<E>()` plus an indexed `SnapshotList.size()/get(i)` loop (port/SnapshotList.iron:34,48) |
| API0351 | `BitSet.BitSet()` | 15 | ClosedWorldEffectAnalyzer 15 | Parameter-index vectors, initially empty; all positions including beyond one word. [EFFECT_CONTRACTS.md:19] | A | `ironwood.util.BitSet` `public BitSet()` (util/BitSet.iron:12) |
| API0352 | `BitSet.cardinality()` | 2 | ClosedWorldEffectAnalyzer:611, ClosedWorldEffectAnalyzer:613 | Monotone OR; cardinality change detects added bits. [EFFECT_CONTRACTS.md:20] | A | `public int cardinality()` (util/BitSet.iron:343) |
| API0353 | `BitSet.clear(int)` | 1 | ClosedWorldEffectAnalyzer:320 | clear outside storage does nothing; applied to a private copy. [EFFECT_CONTRACTS.md:19] | A | `public void clear(int bitIndex)` (util/BitSet.iron:165); returns unchanged outside storage, as required |
| API0354 | `BitSet.clone()` | 5 | ClosedWorldEffectAnalyzer:295, ClosedWorldEffectAnalyzer:605, ClosedWorldEffectAnalyzer:619, ClosedWorldEffectAnalyzer:620, ClosedWorldEffectAnalyzer:621 | Independent logical copy via new plus or; capacity not observed; Summary copies all three vectors. [EFFECT_CONTRACTS.md:21] | D | D249 `SnapshotBits.copy(BitSet)` (port/SnapshotBits.iron:21): fresh BitSet plus `or`; logical content only, capacity not preserved (allowed by the contract) |
| API0355 | `BitSet.get(int)` | 2 | ClosedWorldEffectAnalyzer:161, ClosedWorldEffectAnalyzer:168 | get outside storage is false. [EFFECT_CONTRACTS.md:19] | A | `public boolean get(int bitIndex)` (util/BitSet.iron:236); false outside storage |
| API0357 | `BitSet.nextSetBit(int)` | 6 | ClosedWorldEffectAnalyzer:281, ClosedWorldEffectAnalyzer:282, ClosedWorldEffectAnalyzer:311, ClosedWorldEffectAnalyzer:312, ClosedWorldEffectAnalyzer:325, ClosedWorldEffectAnalyzer:326 | Ascending scan, -1 when exhausted. [EFFECT_CONTRACTS.md:20] | A | `public int nextSetBit(int fromIndex)` (util/BitSet.iron:260); -1 when exhausted |
| API0358 | `BitSet.or(BitSet)` | 17 | ClosedWorldEffectAnalyzer 17 | Monotone, commutative, idempotent union. [EFFECT_CONTRACTS.md:20] | A | `public void or(BitSet set)` (util/BitSet.iron:371) |
| API0359 | `BitSet.set(int)` | 1 | ClosedWorldEffectAnalyzer:117 | set grows storage. [EFFECT_CONTRACTS.md:19] | A | `public void set(int bitIndex)` (util/BitSet.iron:115); grows storage |
| API0364 | `Collection.removeIf(Predicate<? super E>)` | 8 | FunctionAnalyzer:12600, FunctionAnalyzer:12602, RejectedFreeEvidence:419, RejectedFreeEvidence:421, RejectedFreeEvidence:428, RejectedFreeEvidence:431, RejectedFreeEvidence:434, RejectedFreeEvidence:437 | Immediate sequential predicate, delete every matching entry, no witness selection; order-independent (E3). [OWNERSHIP_PILOT.md:86] | B | Explicit loop over the live or mutable ds map: `iterator()` yields values, key via `getCurrIteratorKey()`, removal via `Iterator.remove()` (ds/LinkedHashMap.iron:192,444 and LinkedHashMapIterator.iron:59; ds/IdentityHashMap.iron:108,418 and IdentityHashMapIterator.iron:71; ds/HashMap.iron:109,424 and HashMapIterator.iron:71). Predicates read snapshots with D254 `get`. Loop body must not traverse the same map (shared reusable iterator and map-level current key) |
| API0365 | `Collection.stream()` | 18 | FunctionAnalyzer 9, TypeName 2, TypeReference 2, Parser 2, Diagnostic 1, IrForeignCallInstruction 1, ClosedWorldEffectAnalyzer 1 | Synchronous ordered traversal, short-circuit and reached-null behavior preserved, no callback survives. [AST_CONTRACTS.md:42; DIAGNOSTIC_CONTRACTS.md:18; OPERATION_FACTORY_CONTRACTS.md:34; FRONTEND_CONTRACTS.md:62; EFFECT_CONTRACTS.md:16; OWNERSHIP_PILOT.md:86] | B | Direct ordered loops with early return; no stream object. Receivers: TypeName/TypeReference segment counts (primitive storage, item C5), Diagnostic.hasErrors, IrForeignCallInstruction VOID check (fixed factory), Parser destructor findFirst, effect foreignOrigins, FunctionAnalyzer.mergeOwnership |
| API0367 | `Collections.<E>newSetFromMap(Map<E,Boolean>)` | 1 | FunctionAnalyzer:12573 | Joined is an identity set; private identity-set helper replaces Boolean boxing. [OWNERSHIP_PILOT.md:87] | A | `ironwood.ds.IdentityHashSet` `public IdentityHashSet()` (ds/IdentityHashSet.iron:35); `add` returns boolean (ds/IdentityHashSet.iron:102). Replaces Boolean-valued map membership |
| API0392 | `HashMap.HashMap(Map<? extends K,? extends V>)` | 2 | RejectedFreeEvidence:414, RejectedFreeEvidence:415 | Independent shallow mutable copies with value-key equality; copies retire backing only. [OWNERSHIP_PILOT.md:89] | C | Sources are `saved.arrayStores()`/`saved.retentions()`, which in native are D254 `SnapshotMap` values with no traversal. Needs a mutable independent copy from a snapshot (item C2); keys need ArraySlot/Retention value equality/hash (item C3) |
| API0414 | `IdentityHashMap.IdentityHashMap()` | 2 | FunctionAnalyzer:12507, FunctionAnalyzer:12573 | Reference-identity keys for allocation nodes. [OWNERSHIP_PILOT.md:87] | A | `ironwood.ds.IdentityHashMap` `public IdentityHashMap()` (ds/IdentityHashMap.iron:39) |
| API0415 (FunctionAnalyzer snapshot sites) | `IdentityHashMap.IdentityHashMap(Map<? extends K,? extends V>)` | 2 | FunctionAnalyzer:12520, FunctionAnalyzer:12521 | Copies own backing, borrow keys/values. [OWNERSHIP_PILOT.md:87] | D | D254 `SnapshotIdentityMap(IdentityHashMap)` (port/SnapshotIdentityMap.iron:18) performs the independent copy through D250 `IdentityHashMap.copy()`; the intermediate Java copy disappears |
| API0415 (RejectedFreeEvidence merge sites) | `IdentityHashMap.IdentityHashMap(Map<? extends K,? extends V>)` | 4 | RejectedFreeEvidence:411, RejectedFreeEvidence:412, RejectedFreeEvidence:413, RejectedFreeEvidence:416 | Copies own backing, borrow keys/values; merge starts from the first incoming saved version (E3). [OWNERSHIP_PILOT.md:87] | C | Sources are `saved.origins()/bindings()/events()/joins()`, D254 `SnapshotIdentityMap` in native, which exposes no traversal or mutable copy (item C2) |
| API0416 | `IdentityHashMap.clear()` | 4 | RejectedFreeEvidence:482, RejectedFreeEvidence:483, RejectedFreeEvidence:484, RejectedFreeEvidence:487 | Clear membership only. [OWNERSHIP_PILOT.md:87] | A | `public void clear()` (ds/IdentityHashMap.iron:368); does not free keys or values |
| API0418 | `IdentityHashMap.entrySet()` | 4 | RejectedFreeEvidence:419, RejectedFreeEvidence:421, RejectedFreeEvidence:428, RejectedFreeEvidence:437 | removeIf intersection over mutable copies (E3). [OWNERSHIP_PILOT.md:87] | B | Same iterator-removal loop as API0364 on the mutable IdentityHashMap merge copies |
| API0419 | `IdentityHashMap.get(Object)` | 4 | RejectedFreeEvidence:497, RejectedFreeEvidence:512, RejectedFreeEvidence:527, RejectedFreeEvidence:542 | Identity counter lookup (E7). [OWNERSHIP_PILOT.md:87] | A | `public E get(K key)` (ds/IdentityHashMap.iron:258); null key throws IllegalArgumentException (keys are non-null here). Value type changes with API0502 |
| API0420 | `IdentityHashMap.put(K,V)` | 8 | RejectedFreeEvidence:260, RejectedFreeEvidence:261, RejectedFreeEvidence:359, RejectedFreeEvidence:360, RejectedFreeEvidence:503, RejectedFreeEvidence:518, RejectedFreeEvidence:533, RejectedFreeEvidence:549 | Identity-keyed put (E7). [OWNERSHIP_PILOT.md:87] | A | `public E put(K key, E value)` (ds/IdentityHashMap.iron:313); returns previous value; null key/value rejected with IllegalArgumentException |
| API0421 | `IdentityHashMap.putAll(Map<? extends K,? extends V>)` | 4 | RejectedFreeEvidence:458, RejectedFreeEvidence:459, RejectedFreeEvidence:460, RejectedFreeEvidence:463 | Clear then transfer selected facts after one atomic reservation; order-independent (E4). [OWNERSHIP_PILOT.md:87] | C | `copyIntoCurrent` receives the Saved snapshot maps on restore (RejectedFreeEvidence.java:391-392) and mutable copies on merge (443-444). The snapshot path needs entry traversal of D254 `SnapshotIdentityMap` (any order, E4) (item C2); the mutable path is an ordinary iterator loop |
| API0422 | `IdentityHashMap.remove(Object)` | 6 | RejectedFreeEvidence:266, RejectedFreeEvidence:351, RejectedFreeEvidence:499, RejectedFreeEvidence:514, RejectedFreeEvidence:529, RejectedFreeEvidence:544 | Identity-keyed removal. [OWNERSHIP_PILOT.md:87] | A | `public E remove(K key)` (ds/IdentityHashMap.iron:340) |
| API0423 | `IdentityHashMap.size()` | 8 | RejectedFreeEvidence:366, RejectedFreeEvidence:366, RejectedFreeEvidence:366, RejectedFreeEvidence:367, RejectedFreeEvidence:474, RejectedFreeEvidence:474, RejectedFreeEvidence:474, RejectedFreeEvidence:475 | Association counts for budgets. [OWNERSHIP_PILOT.md:87] | A | `public int size()` (ds/IdentityHashMap.iron:114) |
| API0424 | `IdentityHashMap.values()` | 8 | RejectedFreeEvidence:374, RejectedFreeEvidence:375, RejectedFreeEvidence:376, RejectedFreeEvidence:379, RejectedFreeEvidence:476, RejectedFreeEvidence:477, RejectedFreeEvidence:478, RejectedFreeEvidence:481 | Order-independent retain/release scans (E2, E5). [OWNERSHIP_PILOT.md:87] | A | All eight receivers are live evidence maps (save 374-379, clearCurrent 476-481): `IdentityHashMap` implements `Iterable<E>` over values (ds/IdentityHashMap.iron:418). Loop bodies touch only the counter tables, never the traversed map |
| API0428 | `LinkedHashMap.LinkedHashMap()` | 5 | ClosedWorldEffectAnalyzer:20, ClosedWorldEffectAnalyzer:22, ClosedWorldEffectAnalyzer:114, ClosedWorldEffectAnalyzer:200, ClosedWorldEffectAnalyzer:554 | First insertion order kept, replacement keeps position; origins/conversions are primitive-key lookups. [EFFECT_CONTRACTS.md:24] | A | String-keyed `functions`, `summaries`, `blocks`: `ironwood.ds.LinkedHashMap()` (ds/LinkedHashMap.iron:41); `put` on an existing key replaces the value without moving it (ds/LinkedHashMap.iron:270-283). Integer-keyed `origins`/`conversions` are lookup-only: `ironwood.ds.IntMap<E>()` (ds/IntMap.iron:39, get 213, put 250) |
| API0430 | `LinkedHashMap.LinkedHashMap(Map<? extends K,? extends V>)` | 2 | FunctionAnalyzer:12519, FunctionAnalyzer:12520 | Independent shallow copies; linked copy keeps encounter order. [OWNERSHIP_PILOT.md:89] | D | D254 `SnapshotLinkedMap(LinkedHashMap)` for knownArraySlots (D247 order) and `SnapshotMap(HashMap)` for borrowedOwnedFields (port/SnapshotLinkedMap.iron:18, port/SnapshotMap.iron:18). Note: `SnapshotMap` requires a `HashMap` source while the live field is a LinkedHashMap (FunctionAnalyzer.java HEAD:274) that `markAttachedOwnedFieldLoansUncertain` (HEAD:12721-12734, outside the pilot) iterates in order |
| API0438 | `LinkedHashSet.LinkedHashSet()` | 1 | ClosedWorldEffectAnalyzer:202 | Reachable labels, content equality, first insertion kept. [EFFECT_CONTRACTS.md:22] | A | `ironwood.ds.LinkedHashSet()` (ds/LinkedHashSet.iron:27), String content equality |
| API0440 | `LinkedHashSet.LinkedHashSet(Collection<? extends E>)` | 1 | FunctionAnalyzer:12638 | Independent mutable copy; retained child versions are shared immutable versions. [OWNERSHIP_PILOT.md:89] | C | Source is an immutable retained-child version (`Set.copyOf` result, D254 `SnapshotIdentitySet` in native), which has no traversal (item C1) |
| API0441 | `List.<E>copyOf(Collection<? extends E>)` | 81 | Parser 10, ClassDeclaration 9, AnonymousClassBody 5, InterfaceDeclaration 5, ConstructorDeclaration 3, InterfaceMethodDeclaration 3, MethodDeclaration 3, CallExpression 2, CompilationUnit 2, NewExpression 2, QualifiedSuperConstructorExpression 2, SuperConstructorInvocation 2, SwitchExpression 2, SwitchGroup 2, ThisConstructorInvocation 2, TypeName 2, TypeReference 2, IrFunction 2, LexResult 2, ArrayInitializerExpression 1, Block 1, CatchClause 1, EnumConstant 1, ForStatement 1, ModernSwitchStatement 1, SwitchRule 1, SwitchStatement 1, TryStatement 1, TypeParameter 1, Diagnostic 1, IrBasicBlock 1, IrCallInstruction 1, IrForeignCallInstruction 1, IrType 1, ParseResult 1, ClosedWorldEffectAnalyzer 1, RejectedFreeEvidence 1, SourceFile 1 | Independent immutable ordered shallow membership, null source/element rejection, structural sequence equality where compared. [AST_CONTRACTS.md:34; DIAGNOSTIC_CONTRACTS.md:17; OPERATION_FACTORY_CONTRACTS.md:28; FRONTEND_CONTRACTS.md:53; EFFECT_CONTRACTS.md:27; OWNERSHIP_PILOT.md:85] | D | D249 `SnapshotList(ArrayList)` (port/SnapshotList.iron:27). Notes: (1) the source must be an `ArrayList`; sites that receive another record snapshot (for example Parser.java:1965-1966 passes `invocation.typeArguments()/arguments()` into SuperConstructorInvocation) need an indexed copy into an ArrayList first; (2) `List<Integer>` segment-count copies (TypeName:18, TypeReference:14, Parser:2857, Parser:2892) need primitive storage (item C5); (3) Java rejects nulls with NullPointerException, ds.ArrayList rejects at insertion with IllegalArgumentException; (4) SnapshotList has no equals/hashCode; no selected M2.1/M2.2 consumer compares record child lists (grep), so none is required yet |
| API0442 | `List.<E>of()` | 49 | Parser 18, TypeName 8, ClosedWorldEffectAnalyzer 3, ConstructorDeclaration 2, InterfaceMethodDeclaration 2, MethodDeclaration 2, TypeReference 2, Diagnostic 2, IrType 2, CallExpression 1, ClassDeclaration 1, NewExpression 1, QualifiedSuperConstructorExpression 1, SuperConstructorInvocation 1, ThisConstructorInvocation 1, TypeParameter 1, FunctionAnalyzer 1 | Private immutable empty value, sequence semantics; shared empty storage needs an explicit lifetime, not a per-AST free. [AST_CONTRACTS.md:35; DIAGNOSTIC_CONTRACTS.md:17; OPERATION_FACTORY_CONTRACTS.md:29; FRONTEND_CONTRACTS.md:54; EFFECT_CONTRACTS.md:27; OWNERSHIP_PILOT.md:85] | C | No delivered empty factory. Requires a private immutable empty list with an explicit shared lifetime (item C4) |
| API0443 | `List.<E>of(E)` | 3 | Parser:2612, ClosedWorldEffectAnalyzer:378, ClosedWorldEffectAnalyzer:488 | Private immutable singleton rejecting null; boxed int count becomes primitive storage. [FRONTEND_CONTRACTS.md:54; EFFECT_CONTRACTS.md:27] | C | No delivered singleton factory (item C4). Parser.java:2612 is `List.of(typeArguments.size())`, a boxed int that becomes primitive segment storage (item C5) |
| API0454 | `List.add(E)` | 190 | Parser 167, Lexer 21, SourceFile 2 | Append order; return value discarded. [FRONTEND_CONTRACTS.md:55] | A | `public void add(E element)` (ds/ArrayList.iron:107); Java returns boolean, discarded at every site |
| API0457 | `List.addAll(Collection<? extends E>)` | 7 | Parser:238, Parser:303, Parser:396, Parser:1770, Parser:2851, Parser:2870, Parser:2886 | Ordered append from a separate, non-self source. [FRONTEND_CONTRACTS.md:55] | B | No `addAll`; explicit indexed loop over the separately retained source (`size()/get(i)` on ArrayList or SnapshotList), evaluating the source once |
| API0462 | `List.get(int)` | 48 | Parser 40, ClosedWorldEffectAnalyzer 7, SourceFile 1 | Borrowed element read, bounds failure preserved. [FRONTEND_CONTRACTS.md:56; EFFECT_CONTRACTS.md:28] | A | `public E get(int index)` (ds/ArrayList.iron:159, IndexOutOfBoundsException at 71-74) and D249 `SnapshotList.get(int)` (port/SnapshotList.iron:48) |
| API0463 | `List.getFirst()` | 23 | Parser 16, ClosedWorldEffectAnalyzer 3, FunctionAnalyzer 3, UnfreedAllocationTracker 1 | Every reached access follows a nonempty fact; empty input must not silently change exception type. [FRONTEND_CONTRACTS.md:57; EFFECT_CONTRACTS.md:28; OWNERSHIP_PILOT.md:85; UNFREED_CONTRACTS.md:16] | B | Rewrite to `get(0)` under the reviewed nonempty facts (parser invariants, `isRenderedStringRelease` count checks, mergeOwnership `isEmpty` guards, tracker empty-list return) |
| API0464 | `List.getLast()` | 6 | Parser:151, Parser:164, Parser:1502, Parser:1785, Parser:1785, Parser:2004 | Every reached access follows a nonempty or last-token fact. [FRONTEND_CONTRACTS.md:57] | B | Rewrite to `get(size() - 1)` under the reviewed nonempty facts |
| API0467 | `List.isEmpty()` | 40 | Parser 27, SwitchExpression 2, TypeName 2, ClosedWorldEffectAnalyzer 2, FunctionAnalyzer 2, CatchClause 1, SwitchGroup 1, SwitchRule 1, IrType 1, UnfreedAllocationTracker 1 | Pure count read. [AST_CONTRACTS.md:36; OPERATION_FACTORY_CONTRACTS.md:32; FRONTEND_CONTRACTS.md:56; EFFECT_CONTRACTS.md:28; OWNERSHIP_PILOT.md:85; UNFREED_CONTRACTS.md:16] | A | `public boolean isEmpty()` (ds/ArrayList.iron:282); D249 `SnapshotList.isEmpty()` (port/SnapshotList.iron:54) |
| API0471 | `List.removeFirst()` | 2 | Parser:804, Parser:808 | Guarded leading constructor invocation removal; removes membership only. [FRONTEND_CONTRACTS.md:58] | A | `public E removeFirst()` (ds/ArrayList.iron:128-132), NoSuchElementException when empty; shifts once, which the contract accepts (not a FIFO) |
| API0475 | `List.size()` | 49 | Parser 34, ClosedWorldEffectAnalyzer 7, TypeName 2, TypeReference 2, RejectedFreeEvidence 2, FunctionAnalyzer 1, SourceFile 1 | Pure count read. [AST_CONTRACTS.md:36; FRONTEND_CONTRACTS.md:56; EFFECT_CONTRACTS.md:28; OWNERSHIP_PILOT.md:85] | A | `public int size()` (ds/ArrayList.iron:276); D249 `SnapshotList.size()` |
| API0480 (OwnershipSnapshot) | `Map.<K,V>copyOf(Map<? extends K,? extends V>)` | 5 | FunctionAnalyzer:14260, FunctionAnalyzer:14261, FunctionAnalyzer:14262, FunctionAnalyzer:14263, FunctionAnalyzer:14264 | Independent immutable non-null membership; identity allocation keys, value slot/text keys. [OWNERSHIP_PILOT.md:90] | D | D254 wrappers: `SnapshotIdentityMap` (states, retainedBorrows, poolOwners), `SnapshotLinkedMap` (knownArraySlots, D247), `SnapshotMap` (borrowedOwnedFields). The seven-field composition itself is item C1 |
| API0480 (RejectedFreeEvidence.save) | `Map.<K,V>copyOf(Map<? extends K,? extends V>)` | 6 | RejectedFreeEvidence:371, RejectedFreeEvidence:371, RejectedFreeEvidence:371, RejectedFreeEvidence:372, RejectedFreeEvidence:372, RejectedFreeEvidence:372 | Six independent shallow saved versions (E2). [OWNERSHIP_PILOT.md:90] | D | D254 `SnapshotIdentityMap` for origins/bindings/events/joins and `SnapshotMap` for arrayStores/retentions; the Saved store, traversal and retirement are item C2; ArraySlot/Retention value keys are item C3 |
| API0480 (IrCallInstruction) | `Map.<K,V>copyOf(Map<? extends K,? extends V>)` | 1 | IrCallInstruction:21 | Only empty specialization enters the finite factory. [OPERATION_FACTORY_CONTRACTS.md:29] | B | Private factory stores empty specialization membership without hash traversal; general Map.copyOf stays M3.1 |
| API0481 | `Map.<K,V>entry(K,V)` | 49 | Lexer 49 | Forty-nine fixed keyword keys, only read is getOrDefault(lexeme, IDENTIFIER); prefer private switch/static lookup. [FRONTEND_CONTRACTS.md:59] | B | Private keyword lookup with String content equality and IDENTIFIER fallback, no per-token allocation: a `switch (lexeme)` (String switch is supported, docs/LANGUAGE_SPECS.md:344, dispatch by source-ordered equality, :381) or a once-built static `ironwood.ds.HashMap<String, TokenKind>` read with `get` |
| API0482 | `Map.<K,V>of()` | 3 | IrCallInstruction:21, IrCallInstruction:29, IrCallInstruction:36 | Empty Map factory supplies no order observation. [OPERATION_FACTORY_CONTRACTS.md:29] | B | Empty specialization membership in the private factory |
| API0487 | `Map.<K,V>ofEntries(Map.Entry<? extends K,? extends V>...)` | 1 | Lexer:15 | Fixed keyword table, no traversal. [FRONTEND_CONTRACTS.md:59] | B | Folded into the API0481 keyword lookup |
| API0488 | `Map.clear()` | 8 | FunctionAnalyzer:12544, FunctionAnalyzer:12546, FunctionAnalyzer:12548, FunctionAnalyzer:12550, FunctionAnalyzer:12623, FunctionAnalyzer:12633, RejectedFreeEvidence:485, RejectedFreeEvidence:486 | Restore clears then replaces membership. [OWNERSHIP_PILOT.md:91] | A | `clear()` on the ds maps (ds/LinkedHashMap.iron, ds/IdentityHashMap.iron:368, ds/HashMap.iron:374) |
| API0490 | `Map.computeIfAbsent(K,Function<? super K,? extends V>)` | 2 | ClosedWorldEffectAnalyzer:385, ClosedWorldEffectAnalyzer:610 | Mapper only on miss, not retained; explicit private cache lookup/insert and primitive-id map. [EFFECT_CONTRACTS.md:23] | B | Explicit lookup then insert: `targetCache` identity map `get`/`put`; `origins` IntMap `get`, `put(new BitSet())` on miss |
| API0494 (live and states sites) | `Map.entrySet()` | 5 | FunctionAnalyzer:12536, FunctionAnalyzer:12600, FunctionAnalyzer:12602, RejectedFreeEvidence:431, RejectedFreeEvidence:434 | Owned mutable current maps, borrowed immutable incoming maps; restore clears then replaces. [OWNERSHIP_PILOT.md:91] | B | Live/mutable sites: iterator-removal loops (FunctionAnalyzer J0 12600/12602, RejectedFreeEvidence 431/434). Restore of `states` (J0 12536) can iterate the append-only `allocations` list and call `snapshot.states().get(a)` (see the seven-field section), so no snapshot traversal is needed there |
| API0494 (retainArrayStores) | `Map.entrySet()` | 1 | RejectedFreeEvidence:292 | Delete every non-live slot entry through the iterator (E1). [OWNERSHIP_PILOT.md:91] | D | D255 qualified `ironwood.ds.HashMap.iterator()` plus `getCurrIteratorKey()` and `Iterator.remove()` for `retainArrayStores` (docs/self-hosting/m1/WORKLIST.md) |
| API0494 (snapshot entry traversal) | `Map.entrySet()` | 2 | FunctionAnalyzer:12606, FunctionAnalyzer:12613 | Common-slot insertion preserves path precedence then current-store order (D247). [OWNERSHIP_PILOT.md:91] | C | Entry traversal of the first incoming snapshot: knownArraySlots in D247 insertion order (J0 12606, DELTA.md row 12605) and borrowedOwnedFields (J0 12613). D254 wrappers expose no traversal (item C1) |
| API0496 | `Map.forEach(BiConsumer<? super K,? super V>)` | 3 | FunctionAnalyzer:12625, FunctionAnalyzer:12637, FunctionAnalyzer:12646 | Pool first owner by incoming path order, conflict blocks both; retained-child union; lost-slot blocking in path then slot order. [OWNERSHIP_PILOT.md:91] | C | All three receivers are snapshots: `path.poolOwners()`, `path.retainedBorrows()`, `path.knownArraySlots()` (ordered, D247 row 12646). Needs snapshot entry traversal (item C1) |
| API0497 (live receivers) | `Map.get(Object)` | 3 | ClosedWorldEffectAnalyzer:208, ClosedWorldEffectAnalyzer:377, FunctionAnalyzer:12647 | Absence means null because values are non-null. [EFFECT_CONTRACTS.md:25; OWNERSHIP_PILOT.md:91] | A | Live receivers: `blocks.get`/`functions.get` (ds/LinkedHashMap.iron:262), `knownArraySlots.get` (live LinkedHashMap, J0 12647) |
| API0497 (SnapshotKey lookups) | `Map.get(Object)` | 5 | RejectedFreeEvidence:243, RejectedFreeEvidence:252, RejectedFreeEvidence:305, RejectedFreeEvidence:385, RejectedFreeEvidence:404 | Saved version chosen by proof-snapshot identity (E7). [OWNERSHIP_PILOT.md:91] | C | `snapshots.get(new SnapshotKey(...))` becomes a lookup in a strong identity-keyed saved-version store with explicit retirement (item C2) |
| API0497 (snapshot receivers) | `Map.get(Object)` | 15 | RejectedFreeEvidence 9, FunctionAnalyzer 6 | Keyed lookup on immutable incoming/saved maps. [OWNERSHIP_PILOT.md:91] | D | D254 `get` on `SnapshotIdentityMap`/`SnapshotLinkedMap`/`SnapshotMap` (port/SnapshotIdentityMap.iron:37 and peers); allocation-free; value-keyed sites need ArraySlot/Retention value operations (item C3) |
| API0499 | `Map.getOrDefault(Object,V)` | 5 | Lexer:197, ClosedWorldEffectAnalyzer:279, ClosedWorldEffectAnalyzer:307, ClosedWorldEffectAnalyzer:605, FunctionAnalyzer:12638 | Defaults evaluated eagerly in Java; private native lookup may skip unused default construction. [FRONTEND_CONTRACTS.md:59; EFFECT_CONTRACTS.md:25; OWNERSHIP_PILOT.md:91] | B | Lexer: folded into API0481. Effect `summaries`/`origins`: `get` plus explicit default without constructing an unused empty value (EFFECT_CONTRACTS.md:25 permits this). FunctionAnalyzer J0 12638: null check instead of `Set.of()` default |
| API0501 | `Map.keySet()` | 2 | FunctionAnalyzer:12574, FunctionAnalyzer:12621 | Joined identity membership; live-slot membership test. [OWNERSHIP_PILOT.md:91] | B | J0 12574 `states().keySet()`: iterate the append-only `allocations` list and test `containsKey` on each incoming states snapshot (seven-field section). J0 12621 `knownArraySlots.keySet()`: pass the live LinkedHashMap and use `containsKey` (ds/LinkedHashMap.iron:244) |
| API0502 | `Map.merge(K,V,BiFunction<? super V,? super V,? extends V>)` | 4 | RejectedFreeEvidence:493, RejectedFreeEvidence:508, RejectedFreeEvidence:523, RejectedFreeEvidence:538 | Identity reference counters, primitive add/subtract, balanced to zero (E7). [OWNERSHIP_PILOT.md:91] | B | No boxed Integer exists (stdlib ironwood/lang/Integer.iron has only static members). Keep identity-keyed primitive counts: a private mutable int cell per key, or a counter field on the private evidence object; increment/decrement with primitive arithmetic |
| API0503 (live maps) | `Map.put(K,V)` | 10 | ClosedWorldEffectAnalyzer:44, ClosedWorldEffectAnalyzer:46, ClosedWorldEffectAnalyzer:89, ClosedWorldEffectAnalyzer:118, ClosedWorldEffectAnalyzer:201, ClosedWorldEffectAnalyzer:558, FunctionAnalyzer:12510, FunctionAnalyzer:12609, FunctionAnalyzer:12616, FunctionAnalyzer:12640 | Insertion/replacement. [EFFECT_CONTRACTS.md:24; OWNERSHIP_PILOT.md:91] | A | `put` on ds maps (ds/LinkedHashMap.iron:270, ds/IdentityHashMap.iron:313, ds/IntMap.iron:250); returns previous value, so `summaries.put` still yields `previous` for the fixed-point comparison |
| API0503 (saved-version store) | `Map.put(K,V)` | 1 | RejectedFreeEvidence:370 | Saved version keyed by proof-snapshot identity. [OWNERSHIP_PILOT.md:91] | C | `snapshots.put(new SnapshotKey(proofSnapshot, retired), new Saved(...))` becomes a strong saved-version insert (item C2) |
| API0504 | `Map.putAll(Map<? extends K,? extends V>)` | 6 | FunctionAnalyzer:12545, FunctionAnalyzer:12547, FunctionAnalyzer:12549, FunctionAnalyzer:12551, RejectedFreeEvidence:461, RejectedFreeEvidence:462 | Restore clears then replaces membership; slot restore preserves current-store order. [OWNERSHIP_PILOT.md:91] | C | FunctionAnalyzer restore (J0 12545-12551) transfers from snapshots into live maps; knownArraySlots must replay D247 insertion order (DELTA.md row 12545). RejectedFreeEvidence 461/462 transfer from Saved snapshot maps on restore. Needs snapshot entry traversal (items C1, C2) |
| API0505 | `Map.putIfAbsent(K,V)` | 1 | FunctionAnalyzer:12626 | First pool owner per incoming path; conflict blocks both owners. [OWNERSHIP_PILOT.md:91] | B | `previous = poolOwners.get(value); if (previous == null) poolOwners.put(value, owner)`; values are non-null |
| API0507 | `Map.remove(Object)` | 1 | RejectedFreeEvidence:576 | Remove the selected dead version by identity before release scans (E6). [OWNERSHIP_PILOT.md:91] | C | RejectedFreeEvidence:576 removes a dead saved version selected by the ReferenceQueue; becomes explicit retirement of a saved version (item C2) |
| API0510 | `Map.size()` | 10 | RejectedFreeEvidence:367, RejectedFreeEvidence:367, RejectedFreeEvidence:455, RejectedFreeEvidence:455, RejectedFreeEvidence:455, RejectedFreeEvidence:456, RejectedFreeEvidence:456, RejectedFreeEvidence:456, RejectedFreeEvidence:475, RejectedFreeEvidence:475 | Association counts for one atomic reservation. [OWNERSHIP_PILOT.md:91] | A | `size()` on ds maps and D254 wrappers (port/SnapshotMap.iron:25 and peers) |
| API0511 (live receivers) | `Map.values()` | 5 | ClosedWorldEffectAnalyzer:87, RejectedFreeEvidence:377, RejectedFreeEvidence:378, RejectedFreeEvidence:479, RejectedFreeEvidence:480 | Function insertion order for fixed-point rounds; order-independent retain/release scans. [EFFECT_CONTRACTS.md:24; OWNERSHIP_PILOT.md:91] | A | Effect `functions.values()`: `LinkedHashMap.iterator()` yields values in insertion order (ds/LinkedHashMap.iron:444). RejectedFreeEvidence save/clearCurrent on live `arrayStores`/`retentions`: `HashMap.iterator()` (ds/HashMap.iron:424) |
| API0511 (snapshot receivers) | `Map.values()` | 12 | RejectedFreeEvidence 12 | Order-independent retain/release of saved payload counters (E4, E6). [OWNERSHIP_PILOT.md:91] | C | Values traversal of Saved snapshot maps: copyIntoCurrent on restore (464-469) and retireCollected (581-586). D254 wrappers expose none (item C2) |
| API0512 (retainArrayStores) | `Map.Entry.getKey()` | 1 | RejectedFreeEvidence:295 | Current-entry identity during removal scan (E1). [OWNERSHIP_PILOT.md:91] | D | D255: key through `HashMap.getCurrIteratorKey()` during the qualified retain loop |
| API0512 (snapshot traversal) | `Map.Entry.getKey()` | 4 | FunctionAnalyzer:12608, FunctionAnalyzer:12609, FunctionAnalyzer:12615, FunctionAnalyzer:12616 | D247 common-slot insertion order. [OWNERSHIP_PILOT.md:91] | C | Keys of first-incoming snapshot entries (knownArraySlots ordered, borrowedOwnedFields) (item C1) |
| API0512 (live and states sites) | `Map.Entry.getKey()` | 9 | FunctionAnalyzer:12537, FunctionAnalyzer:12601, FunctionAnalyzer:12603, RejectedFreeEvidence:419, RejectedFreeEvidence:422, RejectedFreeEvidence:429, RejectedFreeEvidence:432, RejectedFreeEvidence:435, RejectedFreeEvidence:438 | Keyed predicate over current entries. [OWNERSHIP_PILOT.md:91] | B | Live/mutable iterator loops: key via `getCurrIteratorKey()`; states restore via the `allocations` list |
| API0514 (retainArrayStores) | `Map.Entry.getValue()` | 1 | RejectedFreeEvidence:296 | Current-entry value released after removal (E1). [OWNERSHIP_PILOT.md:91] | D | D255: value is the result of `HashMap` iterator `next()` |
| API0514 (snapshot traversal) | `Map.Entry.getValue()` | 4 | FunctionAnalyzer:12608, FunctionAnalyzer:12609, FunctionAnalyzer:12615, FunctionAnalyzer:12616 | D247 common-slot insertion order. [OWNERSHIP_PILOT.md:91] | C | Values of first-incoming snapshot entries (item C1) |
| API0514 (live and states sites) | `Map.Entry.getValue()` | 11 | RejectedFreeEvidence 8, FunctionAnalyzer 3 | Keyed predicate over current entries. [OWNERSHIP_PILOT.md:91] | B | Live/mutable iterator loops: value from `next()`; states restore via `allocations` plus `get` |
| API0553 | `Set.<E>copyOf(Collection<? extends E>)` | 5 | FunctionAnalyzer:12521, FunctionAnalyzer:12640, FunctionAnalyzer:14265, FunctionAnalyzer:14266, UnfreedAllocationTracker:74 | Independent immutable non-null membership; identity allocation elements. [OWNERSHIP_PILOT.md:90; UNFREED_CONTRACTS.md:15] | D | D254 `SnapshotIdentitySet(IdentityHashSet)` (port/SnapshotIdentitySet.iron:17). Notes: tracker `live` must be an `IdentityHashSet` (Java LinkedHashSet, order not observed per UNFREED_CONTRACTS.md:15); J0 12640 builds a retained-child version whose shared lifetime is item C1 |
| API0554 | `Set.<E>of()` | 2 | FunctionAnalyzer:12522, FunctionAnalyzer:12638 | Empty immutable membership. [OWNERSHIP_PILOT.md:90] | B | J0 12522: empty `SnapshotIdentitySet` built from an empty `IdentityHashSet` (or an explicit absent-tracker branch); J0 12638: null check instead of a `Set.of()` default |
| API0567 | `Set.add(E)` | 1 | ClosedWorldEffectAnalyzer:207 | Visited labels by content equality. [EFFECT_CONTRACTS.md:22] | A | `public boolean add(E value)` (ds/LinkedHashSet.iron:85) |
| API0569 (joined) | `Set.addAll(Collection<? extends E>)` | 1 | FunctionAnalyzer:12574 | Joined identity membership. [OWNERSHIP_PILOT.md:92] | B | J0 12574 `joined.addAll(states().keySet())` becomes an `allocations`-list scan (seven-field section) |
| API0569 (snapshot sources) | `Set.addAll(Collection<? extends E>)` | 5 | FunctionAnalyzer:12553, FunctionAnalyzer:12636, FunctionAnalyzer:12639, UnfreedAllocationTracker:79, UnfreedAllocationTracker:87 | Union of exposed/retained sets; tracker restore and first-predecessor seed. [OWNERSHIP_PILOT.md:92; UNFREED_CONTRACTS.md:15] | C | Union/copy from snapshot sets: exposedContainerContents (J0 12553, 12636), retained-child version (12639), tracker restore/merge (UnfreedAllocationTracker:79, 87). D254 `SnapshotIdentitySet` has no traversal (item C1) |
| API0571 | `Set.clear()` | 4 | FunctionAnalyzer:12552, FunctionAnalyzer:12634, UnfreedAllocationTracker:78, UnfreedAllocationTracker:83 | Clear live membership; elements not reclaimed. [OWNERSHIP_PILOT.md:92; UNFREED_CONTRACTS.md:15] | A | `public void clear()` (ds/IdentityHashSet.iron:114) |
| API0572 (effect) | `Set.contains(Object)` | 2 | ClosedWorldEffectAnalyzer:130, ClosedWorldEffectAnalyzer:141 | Reachable-label membership. [EFFECT_CONTRACTS.md:22] | A | `public boolean contains(E value)` (ds/LinkedHashSet.iron:91) |
| API0572 (retainArrayStores) | `Set.contains(Object)` | 1 | RejectedFreeEvidence:295 | Live-slot membership predicate (E1). [OWNERSHIP_PILOT.md:92] | D | D255: `liveSlots.contains(key)` in the qualified retain loop; with the live LinkedHashMap passed instead of its key set this is `containsKey` (ds/LinkedHashMap.iron:244) |
| API0578 | `Set.iterator()` | 1 | RejectedFreeEvidence:292 | Removal scan iterator (E1). [OWNERSHIP_PILOT.md:92] | D | D255: `ironwood.ds.HashMap.iterator()` (ds/HashMap.iron:424) qualified for this loop |
| API0583 | `Set.retainAll(Collection<?>)` | 1 | UnfreedAllocationTracker:88 | Universal predecessor intersection; order irrelevant to membership. [UNFREED_CONTRACTS.md:15; OWNERSHIP_PILOT.md:92] | B | Loop over live `IdentityHashSet.iterator()` (ds/IdentityHashSet.iron:176) removing items for which D254 `SnapshotIdentitySet.contains` (port/SnapshotIdentitySet.iron:36) is false; removal via IdentityHashSetIterator.iron:42 |

## M1.2 patterns

| Pattern | Java API | Calls | Consumer sites (J0 file:line) | Reviewed contract (M0 citation) | Class | Ironwood mapping, difference or required work |
| --- | --- | --- | --- | --- | --- | --- |
| API0319 | `ArrayDeque.ArrayDeque()` | 1 | ClosedWorldEffectAnalyzer:203 | Q20 FIFO; storage owner releases membership, not labels. [WORKLIST_CONTRACTS.md:17; EFFECT_CONTRACTS.md:18] | D | D255 `WorkQueue<E>()` (port/WorkQueue.iron:24) |
| API0321 | `ArrayDeque.add(E)` | 8 | ClosedWorldEffectAnalyzer:204, ClosedWorldEffectAnalyzer:210, ClosedWorldEffectAnalyzer:212, ClosedWorldEffectAnalyzer:213, ClosedWorldEffectAnalyzer:215, ClosedWorldEffectAnalyzer:216, ClosedWorldEffectAnalyzer:220, ClosedWorldEffectAnalyzer:221 | Append at tail, reject null, return discarded, growth failure leaves queue unchanged. [WORKLIST_CONTRACTS.md:18; EFFECT_CONTRACTS.md:18] | D | D255 `WorkQueue.add(E)` (port/WorkQueue.iron:38), void, NullPointerException on null, growth installs the new ring first |
| API0322 | `ArrayDeque.add(E)` | 1 | ClosedWorldEffectAnalyzer:218 | Bound reference executes immediately. [WORKLIST_CONTRACTS.md:18; EFFECT_CONTRACTS.md:18] | D | D255 `WorkQueue.add(E)`; the `ifPresent(pending::add)` around it is an M1.3 presence branch (API0531) |
| API0325 | `ArrayDeque.isEmpty()` | 1 | ClosedWorldEffectAnalyzer:205 | Pure count read. [WORKLIST_CONTRACTS.md:23; EFFECT_CONTRACTS.md:18] | D | D255 `WorkQueue.isEmpty()` (port/WorkQueue.iron:63) |
| API0329 | `ArrayDeque.removeFirst()` | 1 | ClosedWorldEffectAnalyzer:206 | Remove head, guarded by isEmpty, no front shifting. [WORKLIST_CONTRACTS.md:21; EFFECT_CONTRACTS.md:18] | D | D255 `WorkQueue.removeFirst()` (port/WorkQueue.iron:52), NoSuchElementException when empty, no shifting |
| API0425 | `Iterator.hasNext()` | 1 | RejectedFreeEvidence:293 | Iterator removal legality and current-entry identity (E1). [OWNERSHIP_PILOT.md:88] | D | D255: `HashMapIterator.hasNext()` via `HashMap.iterator()` |
| API0426 | `Iterator.next()` | 1 | RejectedFreeEvidence:294 | Current entry identity (E1). [OWNERSHIP_PILOT.md:88] | D | D255: `HashMapIterator.next()` returns the value; key from `HashMap.getCurrIteratorKey()` |
| API0427 | `Iterator.remove()` | 1 | RejectedFreeEvidence:297 | Remove every non-live entry, balance counters (E1). [OWNERSHIP_PILOT.md:88] | D | D255: `HashMapIterator.remove()` (ds/HashMapIterator.iron:71-77). Illegal second remove throws NoSuchElementException rather than IllegalStateException; not reached |

### Selected FIFO sites (WORKLIST_CONTRACTS.md Q20)

Only `ClosedWorldEffectAnalyzer.reachableBlocks` is selected; no stack consumer
is reached (WORKLIST.md, D255). Lines are J0 / HEAD.

| Call | J0 / HEAD line | Operation | Required semantics |
| --- | --- | --- | --- |
| A19904 | 203 / 211 | `new ArrayDeque<>()` | Independent mutable FIFO membership owned by the call; release membership, never labels (WORKLIST_CONTRACTS.md:17) |
| A19905 | 204 / 212 | `add(function.blocks().getFirst().label())` | Seed with the entry label; `getFirst` is guarded by the IR invariant that a function has at least one block (EFFECT_CONTRACTS.md:28) |
| A19907 | 205 / 213 | `isEmpty()` | Pure count, no allocation (WORKLIST_CONTRACTS.md:23) |
| A19908 | 206 / 214 | `removeFirst()` | Take head, guarded by `isEmpty`; empty throws NoSuchElementException; no front shifting (WORKLIST_CONTRACTS.md:21, 90) |
| A19911 | 210 / 218 | `add(jump.target())` | Append at tail, null rejected, boolean result discarded (WORKLIST_CONTRACTS.md:18) |
| A19912, A19913 | 212-213 / 220-221 | `add(trueTarget)`, `add(falseTarget)` | True before false (WORKLIST_CONTRACTS.md:55) |
| A19914 | 215 / 223 | `add(defaultTarget)` | Default before cases |
| A19916 | 216 / 224 | `cases().forEach(arm -> add(arm.target()))` | Cases in list order; immediate callback becomes a loop (M1.3 API0092) |
| A19918 | 218 / 226 | `unwindTarget().ifPresent(pending::add)` | Bound reference executes immediately; becomes a presence branch (M1.3 API0531) |
| A19919, A19920 | 220-221 / 228-229 | `add(normalTarget)`, then `add(unwindTarget)` if `mayUnwind` | Normal before admitted unwind |

Common obligations: duplicates may be re-enqueued and are filtered by
`reachable.add`; amortized O(1) end operations; storage tracks peak live
membership; growth failure leaves the queue unchanged; queue storage retires at
the analysis boundary including failure; labels are borrowed
(WORKLIST_CONTRACTS.md:18, 90-98; EFFECT_CONTRACTS.md:18; PILOT_HANDOFF.md:22).
Under OPERATION_MODEL.md:43-52 the admitted input has one entry block with a
return terminator, so only A19904-A19908 execute in M2.2; the other successor
branches are rejected inputs but remain in the ported source.

Status: delivered by D255 `WorkQueue<E>` (port/WorkQueue.iron: constructor 24,
`add` 38, `removeFirst` 52, `isEmpty` 63, `size` 69, `clear` 75), with Java
`ArrayDeque` transcript parity for the analyzer's CFG order (WORKLIST.md).
Observation for review: `removeFirst` and `clear` reset `head`/`count` but leave
the consumed reference in its ring slot until the slot is reused
(port/WorkQueue.iron:52-61, 75-79). D255 relies on the existing analysis
treating queued items as escaped (WORKLIST.md, "Items are borrowed"), whereas
EFFECT_CONTRACTS.md:18 and WORKLIST_CONTRACTS.md:91 say consumed membership
must be cleared promptly. Whether a stale slot reference meets that wording is
not decided by any M1 record.

### RejectedFreeEvidence iterator sites (OWNERSHIP_PILOT.md:88, SNAPSHOT_CONTRACTS.md:46 E1)

| Call | Line (J0 = HEAD) | Java | Native mapping (D255) |
| --- | --- | --- | --- |
| A28183 | 293 | `stores.hasNext()` on `arrayStores.entrySet().iterator()` (292) | `ironwood.ds.HashMap.iterator()` (ds/HashMap.iron:424) then `hasNext()` |
| A28184 | 294 | `stores.next()` returning the entry | `next()` returns the Site value; the ArraySlot key is `arrayStores.getCurrIteratorKey()` (ds/HashMap.iron:109) |
| A28188 | 297 | `stores.remove()` | `Iterator.remove()` implemented by HashMapIterator.iron:71-77 |

Required semantics: delete every entry whose key is not a live slot, release one
association and the Site count per removed entry, no early witness, no reserve
inside the scan (E1). Constraints of the native mapping: the map has one shared
reusable iterator and a map-level current key, so the loop body must not
traverse `arrayStores` (it touches only `liveUnits`, the budget and
`siteReferences`); a second `remove()` throws NoSuchElementException instead of
Java's IllegalStateException (not reached); `liveSlots.contains(key)` becomes
`containsKey` on the live knownArraySlots LinkedHashMap passed instead of its
key set; ArraySlot keys need value equality/hash (C3). The same pattern exists
at `clearRetainingOwner` (328-337) but is not a selected call.

## M1.3 patterns

| Pattern | Java API | Calls | Consumer sites (J0 file:line) | Reviewed contract (M0 citation) | Class | Ironwood mapping, difference or required work |
| --- | --- | --- | --- | --- | --- | --- |
| API0003 | `Array.length` | 2 | Parser:2482, Parser:2485 | Fixed arity for the private 2/3-token callers; compare adjacent UTF-16 offsets. [FRONTEND_CONTRACTS.md:26] | B | Replace varargs `contiguousKinds(TokenKind...)` (Parser.java:2481) with fixed two- and three-argument overloads; keep kind and adjacent offset checks |
| API0031 | `Character.MAX_VALUE` | 1 | CharacterLiteralExpression:10 | Inclusive UTF-16 unit bound 0..65535, IllegalArgumentException outside. [AST_CONTRACTS.md:22] | A | `Character.MAX_VALUE` (lang/Character.iron:15) |
| API0032 | `Character.MIN_VALUE` | 1 | CharacterLiteralExpression:10 | Inclusive UTF-16 unit bound. [AST_CONTRACTS.md:22] | A | `Character.MIN_VALUE` (lang/Character.iron:13) |
| API0033 | `Character.digit(char,int)` | 1 | Lexer:370 | Exact char overloads; -1 for non-digits. [FRONTEND_CONTRACTS.md:27] | A | `public static int digit(char value, int radix)` (lang/Character.iron:188) |
| API0034 | `Character.isDigit(char)` | 7 | Lexer:126, Lexer:126, Lexer:215, Lexer:221, Lexer:231, Lexer:345, Lexer:644 | Unicode digit continuation. [FRONTEND_CONTRACTS.md:27] | A | `public static boolean isDigit(char value)` (lang/Character.iron:104) |
| API0039 | `Character.isWhitespace(char)` | 1 | Lexer:102 | Java 21 whitespace. [FRONTEND_CONTRACTS.md:27] | A | `public static boolean isWhitespace(char value)` (lang/Character.iron:221) |
| API0042 | `Character.toString(char)` | 1 | Lexer:656 | Owned one-unit text, including isolated surrogates. [FRONTEND_CONTRACTS.md:27] | A | `public static String toString(char value)` (lang/Character.iron:56) |
| API0073 | `IllegalArgumentException.IllegalArgumentException(String)` | 49 | TypeName 13, IrType 13, IrForeignCallInstruction 4, TypeReference 2, RejectedFreeEvidence 2, ArrayCreationExpression 1, BreakStatement 1, CatchClause 1, CharacterLiteralExpression 1, ContinueStatement 1, SwitchExpression 1, SwitchGroup 1, SwitchRule 1, TypeParameter 1, TypePatternBinding 1, Diagnostic 1, IrFunction 1, Parser 1, SourcePosition 1, SourceSpan 1 | Constructor contract failures keep type, order and message. [AST_CONTRACTS.md:24; DIAGNOSTIC_CONTRACTS.md:15; OPERATION_FACTORY_CONTRACTS.md:35; FRONTEND_CONTRACTS.md:38] | A | `public IllegalArgumentException(String message)` (lang/IllegalArgumentException.iron:15); validation order is a port obligation. Operation-factory sites are rejection paths only (OPERATION_FACTORY_CONTRACTS.md:35) |
| API0076 | `IllegalStateException.IllegalStateException(String)` | 4 | Parser:939, Parser:2140, Parser:2181, Parser:2211 | Internal parser invariant failures. [FRONTEND_CONTRACTS.md:38] | A | `public IllegalStateException(String message)` (lang/IllegalStateException.iron:15) |
| API0083 | `Integer.intValue()` | 2 | TypeName:51, TypeReference:16 | Unboxing after null-rejecting copy; ordered primitive int storage. [AST_CONTRACTS.md:25] | B | Loop over primitive segment-count storage (item C5); no unboxing remains |
| API0086 | `Integer.sum(int,int)` | 4 | RejectedFreeEvidence:493, RejectedFreeEvidence:508, RejectedFreeEvidence:523, RejectedFreeEvidence:538 | Primitive integer counters, not boxed identity. [OWNERSHIP_PILOT.md:83] | B | Primitive `+ 1` on the counter representation chosen for API0502 |
| API0092 | `Iterable.forEach(Consumer<? super T>)` | 27 | RejectedFreeEvidence 18, ClosedWorldEffectAnalyzer 5, FunctionAnalyzer 3, UnfreedAllocationTracker 1 | Synchronous ordered traversal, no retained callback. [EFFECT_CONTRACTS.md:16; OWNERSHIP_PILOT.md:86; UNFREED_CONTRACTS.md:16] | B | Direct ordered loops. Twelve RejectedFreeEvidence receivers (copyIntoCurrent 464-469 on restore, retireCollected 581-586) are snapshot values whose traversal is item C2; FunctionAnalyzer J0 12534 iterates the `allocations` ArrayList |
| API0101 | `Math.max(int,int)` | 5 | Lexer:309, Parser:3035, RejectedFreeEvidence:93, RejectedFreeEvidence:565, RejectedFreeEvidence:568 | Primitive clamp and high-water max. [FRONTEND_CONTRACTS.md:28; OWNERSHIP_PILOT.md:83] | A | `public static int max(int first, int second)` (lang/Math.iron:26) |
| API0130 | `String.charAt(int)` | 25 | Lexer 22, SourceFile 2, Parser 1 | UTF-16 unit reads with existing guards. [FRONTEND_CONTRACTS.md:29] | A | `public char charAt(int index)` (lang/String.iron:702) |
| API0131 | `String.chars()` | 2 | TypeName:207, TypeReference:21 | Dot counting over UTF-16 units only. [AST_CONTRACTS.md:26] | B | Private loop counting `charAt(i) == '.'`, then `+ 1`; null receiver still fails |
| API0136 | `String.equals(Object)` | 13 | Parser 13 | Content equality, false for null/non-String. [FRONTEND_CONTRACTS.md:30] | A | `public boolean equals(Object other)` (lang/String.iron:720), content equality |
| API0143 | `String.indexOf(int)` | 5 | Lexer:266, Lexer:309, Lexer:309, Lexer:319, Parser:189 | Not-found -1, no allocation. [FRONTEND_CONTRACTS.md:31] | A | `public int indexOf(int character)` (lang/String.iron:179) |
| API0144 | `String.indexOf(int,int)` | 1 | Lexer:321 | From-index clamping. [FRONTEND_CONTRACTS.md:31] | A | `public int indexOf(int character, int fromIndex)` (lang/String.iron:185); negative fromIndex clamps to 0 |
| API0147 | `String.isBlank()` | 6 | TypeName:19, TypeParameter:11, TypePatternBinding:9, Diagnostic:16, IrFunction:15, IrType:33 | Java whitespace semantics; distinct per-constructor null handling. [AST_CONTRACTS.md:29; DIAGNOSTIC_CONTRACTS.md:15; OPERATION_FACTORY_CONTRACTS.md:32] | A | `public boolean isBlank()` (lang/String.iron:75), per-unit `Character.isWhitespace(char)`; equivalent to Java because no supplementary code point is whitespace. Factory names are fixed literals (OPERATION_FACTORY_CONTRACTS.md:32) |
| API0148 | `String.isEmpty()` | 11 | Lexer 10, Parser 1 | UTF-16 length read. [FRONTEND_CONTRACTS.md:29] | A | `public boolean isEmpty()` (lang/String.iron:49) |
| API0152 | `String.length()` | 16 | Lexer 14, SourceFile 2 | UTF-16 length read. [FRONTEND_CONTRACTS.md:29] | A | `public int length()` (lang/String.iron:37) |
| API0154 | `String.matches(String)` | 1 | IrForeignCallInstruction:34 | Fixed literal target; no regex engine. [OPERATION_FACTORY_CONTRACTS.md:33] | B | The private factory admits only the literal `ironwood_bridge_callback_effect` (String equality at the input boundary); the general regex `matches` contract stays M3.1 and must not be replaced by a prefix test |
| API0157 | `String.replace(char,char)` | 1 | Lexer:563 | CRLF then CR normalization order. [FRONTEND_CONTRACTS.md:33] | A | `public String replace(char oldChar, char newChar)` (lang/String.iron:348) |
| API0158 | `String.replace(CharSequence,CharSequence)` | 1 | Lexer:563 | Literal CRLF replacement. [FRONTEND_CONTRACTS.md:33] | A | `public String replace(CharSequence target, CharSequence replacement)` (lang/String.iron:354) |
| API0164 | `String.startsWith(String,int)` | 1 | Lexer:618 | Delimiter test from bounded offsets. [FRONTEND_CONTRACTS.md:31] | A | `public boolean startsWith(String prefix, int offset)` (lang/String.iron:299) |
| API0166 | `String.stripIndent()` | 1 | Lexer:519 | Exact Java stripIndent on normalized text; cooked escapes decoded afterwards. [FRONTEND_CONTRACTS.md:34] | D | D256 `TextBlocks.stripIndent(String)` (port/TextBlocks.iron:29), 20,012 Java 21 differential inputs (docs/self-hosting/m1/TEXT.md) |
| API0169 | `String.substring(int)` | 6 | Lexer:284, Lexer:292, Lexer:311, Lexer:314, Lexer:325, SourceFile:61 | Half-open UTF-16 bounds, caller-owned text. [FRONTEND_CONTRACTS.md:32] | A | `public String substring(int beginIndex)` (lang/String.iron:86) |
| API0170 | `String.substring(int,int)` | 10 | Lexer:178, Lexer:196, Lexer:246, Lexer:267, Lexer:276, Lexer:310, Lexer:324, Lexer:519, Lexer:589, SourceFile:53 | Half-open UTF-16 bounds. [FRONTEND_CONTRACTS.md:32] | A | `public String substring(int beginIndex, int endIndex)` (lang/String.iron:92) |
| API0176 | `String.valueOf(char)` | 1 | Lexer:413 | One UTF-16 unit text, including NUL and surrogates. [FRONTEND_CONTRACTS.md:35] | A | `public static String valueOf(char value)` (lang/String.iron:509) |
| API0178 | `StringBuilder.StringBuilder()` | 2 | Lexer:434, Lexer:526 | Owned empty mutable UTF-16 backing. [FRONTEND_CONTRACTS.md:36; BUILDER_CONTRACTS.md:13] | A | `public StringBuilder()` (lang/StringBuilder.iron:12) |
| API0179 | `StringBuilder.StringBuilder(String)` | 3 | Parser:2810, Parser:2835, Parser:2866 | Copies initial content, rejects null. [FRONTEND_CONTRACTS.md:36; BUILDER_CONTRACTS.md:13] | A | `public StringBuilder(String initial)` (lang/StringBuilder.iron:27); null throws NullPointerException (lang/StringBuilder.iron:538-541) |
| API0180 | `StringBuilder.append(char)` | 7 | Lexer:447, Lexer:461, Lexer:530, Lexer:543, Parser:2823, Parser:2847, Parser:2884 | One UTF-16 unit, receiver returned. [FRONTEND_CONTRACTS.md:36; BUILDER_CONTRACTS.md:14] | A | `public StringBuilder append(char character)` (lang/StringBuilder.iron:206), returns receiver |
| API0184 | `StringBuilder.append(Object)` | 2 | Lexer:463, Lexer:545 | Explicit presence plus primitive char, then append(char); preserve unsupported-escape diagnostic and fallback. [FRONTEND_CONTRACTS.md:37; BUILDER_CONTRACTS.md:17] | C | Both sites append a boxed `Character` from `decodeSimpleEscape` (Lexer.java:417-429, used at 382, 456, 539). Ironwood has no boxed Character or Integer instances (lang/Character.iron, lang/Integer.iron are static-only), so a primitive-presence escape decoder is needed, then `append(char)` (item C6) |
| API0185 | `StringBuilder.append(String)` | 3 | Parser:2823, Parser:2847, Parser:2884 | Content copy, null text contract. [FRONTEND_CONTRACTS.md:36; BUILDER_CONTRACTS.md:15] | A | `public StringBuilder append(String text)` (lang/StringBuilder.iron:215); null appends "null" (lang/StringBuilder.iron:239) |
| API0190 | `StringBuilder.toString()` | 5 | Lexer:470, Lexer:548, Parser:2826, Parser:2856, Parser:2891 | Independent text snapshot. [FRONTEND_CONTRACTS.md:36; BUILDER_CONTRACTS.md:20] | A | `public String toString()` (lang/StringBuilder.iron:527) |
| API0197 | `System.identityHashCode(Object)` | 1 | RejectedFreeEvidence:111 | Snapshot referent identity, never structural equality. [OWNERSHIP_PILOT.md:84] | C | SnapshotKey identity hash disappears with a strong identity-keyed saved-version store (`IdentityHashMap` keyed by the proof snapshot) (item C2) |
| API0205 | `ref.ReferenceQueue.poll()` | 1 | RejectedFreeEvidence:575 | Queue only chooses dead version keys; native uses explicit retirement. [OWNERSHIP_PILOT.md:84] | C | ReferenceQueue polling is replaced by explicit retirement calls at the snapshot owner's last use and at close (item C2) |
| API0206 | `ref.WeakReference.WeakReference(T,ref.ReferenceQueue<? super T>)` | 1 | RejectedFreeEvidence:110 | No GC or runtime lifetime exemption. [OWNERSHIP_PILOT.md:84] | C | WeakReference replaced by strong invocation-owned saved versions with explicit retirement (item C2) |
| API0284 | `Path.getFileName()` | 1 | Parser:142 | Basename test for package-info.iron. [FRONTEND_CONTRACTS.md:46] | A | `Path getFileName()` (nio/file/Path.iron:24, UnixPath.iron:67) |
| API0288 | `Path.of(String,String...)` | 1 | SourceFile:29 | Single display path only. [FRONTEND_CONTRACTS.md:45] | A | `static Path of(String first)` (nio/file/Path.iron:9); Java resolves the varargs overload with no extra parts |
| API0302 | `Path.toString()` | 1 | Parser:142 | Basename text comparison. [FRONTEND_CONTRACTS.md:46] | A | `String toString()` (nio/file/Path.iron:67, UnixPath.iron:275) |
| API0516 | `Objects.<T>requireNonNull(T)` | 4 | IrForeignCallInstruction:26, IrForeignCallInstruction:27, IrForeignCallInstruction:28, IrForeignCallInstruction:30 | Ordered non-null validation in the foreign-call constructor. [OPERATION_FACTORY_CONTRACTS.md:31] | A | `public static <T> T requireNonNull(T object)` (util/Objects.iron:25), NullPointerException |
| API0517 | `Objects.<T>requireNonNull(T,String)` | 2 | Diagnostic:19, Diagnostic:20 | Severity then notes, field-name message. [DIAGNOSTIC_CONTRACTS.md:16] | A | `public static <T> T requireNonNull(T object, String message)` (util/Objects.iron:32), NullPointerException(message) |
| API0521 | `Objects.nonNull(Object)` | 1 | FunctionAnalyzer:12578 | Filters absent incoming state only. [OWNERSHIP_PILOT.md:82] | B | `Objects.nonNull` is absent (util/Objects.iron); use `state != null` in the merge loop |
| API0522 (operation factory) | `Optional.<T>empty()` | 5 | IrCallInstruction:17, IrCallInstruction:19, IrCallInstruction:36, IrForeignCallInstruction:22, IrReturnTerminator:12 | Replace Optional containers with explicit presence. [OPERATION_FACTORY_CONTRACTS.md:30] | B | Explicit presence fields in the private factory records |
| API0522 (frontend/AST) | `Optional.<T>empty()` | 58 | Parser 29, ArrayCreationExpression 3, BreakStatement 2, ClassDeclaration 2, ConstructorDeclaration 2, ContinueStatement 2, ForStatement 2, NewExpression 2, AnonymousClassBody 1, CallExpression 1, CompilationUnit 1, EnumConstant 1, FieldDeclaration 1, IfStatement 1, InstanceOfExpression 1, InterfaceMethodDeclaration 1, MethodDeclaration 1, ReturnStatement 1, SuperConstructorInvocation 1, SwitchLabel 1, TryStatement 1, ParseResult 1 | Present non-null versus empty; presence wrappers borrow children. [AST_CONTRACTS.md:40; FRONTEND_CONTRACTS.md:60] | A | `public static <T> Optional<T> empty()` (util/Optional.iron:21). Difference: allocates a new wrapper per call (Java shares one); private presence fields are allowed instead |
| API0523 | `Optional.<T>of(T)` | 15 | Parser 14, ArrayCreationExpression 1 | of rejects null. [AST_CONTRACTS.md:40; FRONTEND_CONTRACTS.md:60] | A | `public static <T> Optional<T> of(T value)` (util/Optional.iron:27), NullPointerException on null |
| API0524 | `Optional.<T>ofNullable(T)` | 15 | Parser 15 | Nullable parser results. [FRONTEND_CONTRACTS.md:60] | A | `public static <T> Optional<T> ofNullable(T value)` (util/Optional.iron:33) |
| API0526 | `Optional.<U>map(Function<? super T,? extends U>)` | 6 | Parser:161, Parser:569, Parser:1501, Parser:1541, Parser:2903, ClosedWorldEffectAnalyzer:260 | Immediate projection, no stored callback. [FRONTEND_CONTRACTS.md:61; EFFECT_CONTRACTS.md:30] | B | Explicit presence branch evaluating the projection once on presence (Parser: span projections; effect: filter then mergeOrigin, primitive boolean result) |
| API0529 | `Optional.filter(Predicate<? super T>)` | 1 | ClosedWorldEffectAnalyzer:260 | Filter then map evaluation order. [EFFECT_CONTRACTS.md:30] | B | Presence branch: predicate then merge only for a reference result |
| API0531 | `Optional.ifPresent(Consumer<? super T>)` | 2 | ClosedWorldEffectAnalyzer:175, ClosedWorldEffectAnalyzer:218 | Immediate consumer. [EFFECT_CONTRACTS.md:30] | B | Presence branch calling the consumer body directly |
| API0532 | `Optional.isEmpty()` | 4 | Parser:143, Parser:1495, Parser:1821, ClosedWorldEffectAnalyzer:177 | Presence test. [FRONTEND_CONTRACTS.md:60; EFFECT_CONTRACTS.md:30] | A | `public boolean isEmpty()` (util/Optional.iron:52) |
| API0533 (operation factory) | `Optional.isPresent()` | 2 | IrForeignCallInstruction:31, IrForeignCallInstruction:37 | Explicit presence. [OPERATION_FACTORY_CONTRACTS.md:30] | B | Explicit presence fields in the private factory records |
| API0533 (AST) | `Optional.isPresent()` | 6 | ArrayCreationExpression:15, ArrayCreationExpression:15, BreakStatement:14, BreakStatement:14, ContinueStatement:14, ContinueStatement:14 | Presence test. [AST_CONTRACTS.md:40] | A | `public boolean isPresent()` (util/Optional.iron:46) |
| API0535 | `Optional.orElse(T)` | 8 | Parser:569, Parser:1888, Parser:1901, Parser:2903, ClosedWorldEffectAnalyzer:260, ClosedWorldEffectAnalyzer:495, ClosedWorldEffectAnalyzer:498, ClosedWorldEffectAnalyzer:501 | Eager argument evaluation. [FRONTEND_CONTRACTS.md:60; EFFECT_CONTRACTS.md:30] | A | `public T orElse(T other)` (util/Optional.iron:58); arguments are eager constants |
| API0536 | `Optional.orElseGet(Supplier<? extends T>)` | 3 | Parser:161, Parser:1501, Parser:1541 | Lazy fallback only on absence; no supplier allocation. [FRONTEND_CONTRACTS.md:61] | B | Presence branch evaluating the fallback exactly once on absence; the three captures stay local |
| API0537 (operation factory) | `Optional.orElseThrow()` | 2 | IrForeignCallInstruction:31, IrForeignCallInstruction:38 | Guarded extraction. [OPERATION_FACTORY_CONTRACTS.md:30] | B | Presence fields; extraction guarded by presence |
| API0537 (Parser) | `Optional.orElseThrow()` | 2 | Parser:150, Parser:1822 | Empty throws NoSuchElementException. [FRONTEND_CONTRACTS.md:60] | A | `public T orElseThrow()` (util/Optional.iron:64), NoSuchElementException("No value present") |
| API0540 | `OptionalInt.ifPresent(IntConsumer)` | 1 | ClosedWorldEffectAnalyzer:319 | Primitive presence/index, no boxed Integer. [EFFECT_CONTRACTS.md:31] | B | `exactParameter` returns a primitive index or -1; caller clears the copied origin bit when not -1 |
| API0650 | `stream.IntStream.count()` | 2 | TypeName:207, TypeReference:21 | Dot counting. [AST_CONTRACTS.md:26] | B | Part of the API0131 dot-count loop |
| API0651 | `stream.IntStream.filter(IntPredicate)` | 2 | TypeName:207, TypeReference:21 | Dot counting. [AST_CONTRACTS.md:26] | B | Part of the API0131 dot-count loop |
| API0656 | `stream.IntStream.sum()` | 2 | TypeName:50, TypeReference:16 | Wrapping int sum. [AST_CONTRACTS.md:25] | B | Loop with Java int wrapping over primitive segment counts (item C5) |
| API0660 | `stream.Stream.<R>map(Function<? super T,? extends R>)` | 2 | FunctionAnalyzer:12576, FunctionAnalyzer:12661 | Lazy per-element calls in incoming order. [OWNERSHIP_PILOT.md:86] | B | Ordered loop over `incoming` |
| API0665 | `stream.Stream.allMatch(Predicate<? super T>)` | 4 | FunctionAnalyzer:12584, FunctionAnalyzer:12597, FunctionAnalyzer:12607, FunctionAnalyzer:12614 | allMatch(empty) is true; value equality of state snapshots. [OWNERSHIP_PILOT.md:86] | B | Ordered loop, true for empty, short-circuit on first false; `first::equals` needs AllocationStateSnapshot value equality (item C3) |
| API0666 | `stream.Stream.anyMatch(Predicate<? super T>)` | 7 | TypeName:54, TypeReference:15, Diagnostic:45, IrForeignCallInstruction:41, FunctionAnalyzer:12589, FunctionAnalyzer:12600, FunctionAnalyzer:12602 | Early termination, reached-null behavior. [AST_CONTRACTS.md:42; DIAGNOSTIC_CONTRACTS.md:18; OPERATION_FACTORY_CONTRACTS.md:34; OWNERSHIP_PILOT.md:86] | B | Ordered loop, short-circuit on first true |
| API0669 | `stream.Stream.filter(Predicate<? super T>)` | 2 | ClosedWorldEffectAnalyzer:337, FunctionAnalyzer:12576 | Ordered filter. [EFFECT_CONTRACTS.md:32; OWNERSHIP_PILOT.md:86] | B | Inline filter in the loop |
| API0670 | `stream.Stream.findFirst()` | 2 | Parser:272, Parser:2659 | First source destructor; result borrows element. [FRONTEND_CONTRACTS.md:62] | B | First-element presence from `isEmpty()`/`get(0)` on the source-ordered destructor list |
| API0671 | `stream.Stream.forEach(Consumer<? super T>)` | 1 | ClosedWorldEffectAnalyzer:337 | Immediate consumer, ordered. [EFFECT_CONTRACTS.md:32] | B | Ordered loop |
| API0672 | `stream.Stream.mapToInt(ToIntFunction<? super T>)` | 2 | TypeName:50, TypeReference:16 | Primitive int projection. [AST_CONTRACTS.md:25] | B | Loop over primitive storage (item C5) |
| API0681 | `stream.Stream.toList()` | 2 | FunctionAnalyzer:12576, FunctionAnalyzer:12661 | Frozen ordered result, nulls filtered first. [OWNERSHIP_PILOT.md:86] | B | Owned ordered result list (`ArrayList`) built by the loop; J0 12661 list of `unfreedLive` snapshot references (borrowed) |

## Syntax rows (all M1.3)

| Kind | First consumer | Rows | Required treatment (handoff) | Class | Notes |
| --- | --- | --- | --- | --- | --- |
| NULL | M2.1 frontend | 393 | Supported literal; presence/equality/dereference stay caller obligations | A | Language feature; no helper |
| NULL | M2.2 operations | 92 | Same | A | Language feature |
| RECORD | M2.1 frontend | 49 | Explicit ordered fields, compact-constructor evaluation, value equality/hash versus member identity | B | Final classes with the same field order and constructor checks in the same order. A grep of Parser, Lexer, SourceFile and Diagnostic finds no record `equals`/`hashCode` use, so value operations are not observed by the M2.1 closure (FRONTEND_PILOT.md:76-78 requires them only where lookup or convergence observes them) |
| RECORD | M2.1 frontend | 2 | Same | C | SourcePosition (Y12410) and SourceSpan (Y12411): value equality is observed by M2.2 (Site equality, Binding span comparison at RejectedFreeEvidence.java:425); item C3 |
| RECORD | M2.2 operations | 7 | Same | C | ClosedWorldEffectAnalyzer.Summary, FunctionAnalyzer.AllocationStateSnapshot, ArraySlot, RejectedFreeEvidence.Site, Retention (C3); OwnershipSnapshot (C1); Saved (C2) |
| RECORD | M2.2 operations | 14 | Same | B | IrBasicBlock, IrCallInstruction, IrForeignCallInstruction, IrFunction, IrParameter, IrReturnTerminator, IrType, IrValueReference (fixed factory data, presence fields), Effect, Limits, RejectedFreeEvidence.Binding (compared field by field), Event and Join (identity), UnfreedAllocationTracker.Origin |
| RECORD | M2.2 operations | 8 | Same | none | Attribution mismatch, see below |
| TYPE_PATTERN | both | 40 | Supported instanceof binding | A | Language feature (docs/LANGUAGE_SPECS.md reference instanceof patterns) |
| TYPE_PATTERN | M2.2 operations | 3 | Selector once; ordered instanceof arms; finite variant coverage | B | Pattern `switch` at J0 ClosedWorldEffectAnalyzer.java:149-154 (Y06621-Y06623) becomes an ordered instanceof chain with a single selector evaluation and `null` default; coverage check is item C7 |
| LAMBDA | M2.1 8, M2.2 32 | 40 | Direct loop/helper or private callback with explicit capture lifetime | B | All are immediate or lazy-within-expression |
| METHOD_REFERENCE | M2.1 7, M2.2 30 | 37 | Preserve receiver evaluation at binding, primitive results and failures | B | Direct calls inside loops; `Integer::sum` per API0086 |
| ENHANCED_FOR_VARIABLE | M2.2 | 25 | Supported binding; preserve producer order and borrowed lifetime | A | Language feature over lists and ds `Iterable` maps (values). FunctionAnalyzer J0 12605 and 12612 iterate snapshot entries and depend on C1; J0 12535 and 12575 use the `allocations` rewrite |
| UNINITIALIZED | M2.1 8, M2.2 3 | 11 | Explicit initial value, unchanged guarded assignments | B | Parser 603, 615, 926, 1129, 1849, 1850, 2149, 2499 per FRONTEND_PILOT.md:34-40; ClosedWorldEffectAnalyzer 79, 126; RejectedFreeEvidence 574 disappears with C2 |
| VAR | M2.2 | 1 | Explicit declared type | B | RejectedFreeEvidence 292 becomes `Iterator<Site>` from `HashMap.iterator()` (D255 mapping) |
| VARARGS | M2.1 | 1 | Fixed arity | B | Parser 2481 `contiguousKinds`, see API0003 |

Attribution mismatch: eight M2.2 RECORD rows name declarations outside the
selected consumers and outside operation-model-schema.json: Y01568
PatternFlow.Binding (a frontend-model later-only declaration, FRONTEND_PILOT.md:63-65),
Y03357 BridgeJavaSources.Binding, Y03904 BridgeRetentionContract.Site, Y05086
AnonymousParentBinder.Binding, Y06015 BridgeRetentionAnalyzer.Origin, Y06021
BridgeRetentionAnalyzer.Summary, Y10076 LexicalTypeScopes.Binding, Y12131
TemporaryListBorrowAnalysis.Site. Their simple names coincide with selected
records (Binding, Site, Origin, Summary). They need no M1 helper, but the M1
checkpoint should record them as excluded rather than silently skip them.

## Capture rows (all M1.3)

All 65 rows are immediate or lazy-within-expression captures; none is retained
past its call. The retained SemanticAnalysisObserver service is a separate
compiler interface (EFFECT_CONTRACTS.md:107-118) and ports as an ordinary
interface (B).

| Consumer | Rows | Captured | Class | Treatment |
| --- | --- | --- | --- | --- |
| Parser parseCompilationUnit, parseTry, parseIf | 6 | imports/declarations, catches/body, thenBranch/ifKeyword | B | Explicit presence branches evaluate the fallback once on absence (FRONTEND_PILOT.md:24-30; API0536) |
| FunctionAnalyzer.mergeOwnership | 19 | allocation, entry, first, incoming, joined, knownArraySlots, poolOwners, reason | B | Ordered loops; the snapshot sources they traverse are C1 |
| RejectedFreeEvidence save, copyIntoCurrent, retireCollected | 18 | `this` in retain/release method references | B | Direct calls in loops; snapshot value traversal for copyIntoCurrent (restore) and retireCollected is C2 |
| RejectedFreeEvidence.merge | 6 | selected maps | B | removeIf loops (API0364) |
| UnfreedAllocationTracker.merge | 1 | live | B | retainAll loop (API0583) |
| ClosedWorldEffectAnalyzer (constructor, summarize, reachableBlocks, propagateResultOrigins, callEffect, foreignOrigins, targets) | 15 | maps, vectors, pending, this | B | Loops and presence branches; `targets` cache becomes get/put (API0490) |

## Hash contributions (M1.1 101, M1.2 345)

These rows are traversal-order obligations on consumers, not API demands.
198 are scoped order-independent contributions and 248 are unresolved in
general but resolved under the bounded operation restrictions. Consumers:
mergeOwnership 79, RejectedFreeEvidence.save 54, copyIntoCurrent 54,
IrForeignCallInstruction 40, IrCallInstruction 38, RejectedFreeEvidence.merge 36,
snapshotOwnership 25, restoreOwnership 22, IrType 18, clearCurrent 18,
retireCollected 18, OwnershipSnapshot 15, tracker merge 9, tracker restore 6,
foreignOrigins 4, tracker snapshot 3, retainArrayStores 3, IrFunction 2,
propagateResultOrigins 1, callEffect 1. Consequences for M1: traversal added
to the RejectedFreeEvidence saved maps may be unordered (E1-E7); knownArraySlots
traversal must follow D247 insertion order; pool-owner and joined-state order is
admitted only under the one-conflict, empty-JoinPath, pre-stopped-budget fixture
(OWNERSHIP_PILOT.md:41-56, 94-105). Each row's `required_resolution` asks for
collision/resize/permutation fixtures before the dependent consumer, which the
C1/C2 evidence must include.

## OwnershipSnapshot fields: operations and implied API

Live state and snapshot operations, FunctionAnalyzer HEAD lines unless marked
J0. RejectedFreeEvidence and UnfreedAllocationTracker lines are unchanged
since J0. D254 wrappers expose only `size`, `isEmpty`, `get`/`containsKey` or
`contains` (port/Snapshot*.iron); they borrow keys and values and offer no
traversal (KEYED.md: "Ordered traversal and restore are deliberately absent").

| Field | Live state | Snapshot (Java; D254 wrapper) | snapshotOwnership 12751-12764 | restoreOwnership 12766-12794 | mergeOwnership 12802-12897 | Needed beyond D254 |
| --- | --- | --- | --- | --- | --- | --- |
| states | Not a map: mutable `present/state/blockingReason/detached` fields of AllocationInfo nodes listed in `allocations` (ArrayList, final, 248; only `add` at 3514, 5823, 5840, 6007, 6282, 6400, 7224, 9249, 9403) | `snapshotAllocationStates` 12740-12749 builds an IdentityHashMap of new AllocationStateSnapshot values for present nodes in list order; `Map.copyOf` at 14499; `SnapshotIdentityMap` | Build and copy | `allocations.forEach(present = false)` 12768, then for every snapshot entry set the node's four fields 12769-12777 (order-independent, SNAPSHOT_CONTRACTS.md:71-72) | Restore `before`; `joined` identity set = union of incoming key sets 12807-12808; per joined node: non-null incoming values in incoming order 12810-12812, value `equals` 12818, `anyMatch(mayBeFreed)` 12823, `selectedReason` 12828-12830, `allMatch(detached)` 12831 | No traversal if restore and join iterate the append-only `allocations` list and call `get`/`containsKey` (snapshot keys are a subset of `allocations`; join order changes only the optional-evidence prefix, which the pilot fixture excludes, OWNERSHIP_PILOT.md:98-100). Needs AllocationStateSnapshot value equality (C3) and ownership/retirement of the state value objects, which D254 wrappers only borrow (C1) |
| knownArraySlots | `LinkedHashMap<ArraySlot, AllocationInfo>` 249; ArraySlot is container identity plus int index (14521) | D247 `copyArraySlots` 14508-14515: ordered, null-rejecting, unmodifiable; `SnapshotLinkedMap` | Ordered copy (J0 12519 plus 14261 replaced by D247) | `clear` 12778, `putAll(snapshot)` 12779 in snapshot insertion order (DELTA.md row 12545) | Live `removeIf` where any incoming `get(key) != value` 12834-12835; for entries of `incoming.getFirst()` in order, `put` when all incoming agree 12839-12845 (DELTA.md row 12605); `retainArrayStores(knownArraySlots.keySet())` 12855; per path `forEach` lost-slot scan 12879-12894 calling `uniqueIncomingArrayStore` 13173-13184 (`get`, `arrayStore(path, slot)`, Site `equals`) (DELTA.md row 12646) | Ordered key/value traversal of `SnapshotLinkedMap` (or an ordered copy-into-destination for restore plus an ordered cursor for the merge scans); ArraySlot value equality/hash (C3) |
| borrowedOwnedFields | `LinkedHashMap<String, AllocationInfo>` 274 | `new LinkedHashMap` 12754 then `Map.copyOf` 14501 (order discarded); `SnapshotMap` (requires a `HashMap` source, port/SnapshotMap.iron:18) | Copy | `clear`/`putAll` 12780-12781 | Live `removeIf` 12836-12837; first-incoming entries `put` when all agree 12846-12852 | Entry traversal of `SnapshotMap` (order unconstrained in the pilot; restored order is later-only, OWNERSHIP_PILOT.md:91). Decide live type: a `HashMap` live map feeds `SnapshotMap` but changes the order seen by `markAttachedOwnedFieldLoansUncertain` 12721-12734 (outside the pilot); a `LinkedHashMap` live map needs `SnapshotLinkedMap` instead |
| retainedBorrows | `IdentityHashMap<AllocationInfo, Set<AllocationInfo>>` 252; values are immutable `Set.copyOf` child versions (8299, 9549, 12570) | `new IdentityHashMap` 12754 then `Map.copyOf` 14502, sharing child versions; `SnapshotIdentityMap` with `SnapshotIdentitySet` values | Copy, children shared | `clear`/`putAll` 12782-12783 | `clear` 12867; per path `forEach((owner, borrowed) -> merged = new LinkedHashSet<>(getOrDefault(owner, Set.of())); merged.addAll(borrowed); put(owner, Set.copyOf(merged)))` 12871-12875 | Entry traversal of `SnapshotIdentityMap`; element traversal of `SnapshotIdentitySet` child versions; an explicit shared lifetime for child versions referenced by the live map and many snapshots (OPERATION_MODEL.md:36-38) |
| poolOwners | `IdentityHashMap<AllocationInfo, AllocationInfo>` 275 | `new IdentityHashMap` 12755 then `Map.copyOf` 14503; `SnapshotIdentityMap` | Copy | `clear`/`putAll` 12784-12785 | `clear` 12857; for path in incoming order, `forEach((value, owner) -> putIfAbsent`, conflict selects both owners blocked) 12858-12866 | Entry traversal of `SnapshotIdentityMap`; within-path order only selects the blocked-event order for the single admitted conflict (OWNERSHIP_PILOT.md:41-42, 100) |
| exposedContainerContents | `LinkedHashSet<AllocationInfo>` 253 (identity equality; AllocationInfo has no `equals`) | `Set.copyOf` 12755 and 14504; `SnapshotIdentitySet` (requires an `IdentityHashSet` source) | Copy | `clear`/`addAll(snapshot)` 12786-12787 | `clear` 12868; per path `addAll` 12870 | Element traversal of `SnapshotIdentitySet`; live set becomes `IdentityHashSet` (order not output-selecting, OWNERSHIP_PILOT.md:101-102) |
| unfreedLive | Tracker `live` `LinkedHashSet<A>` (UnfreedAllocationTracker.java:23) | `snapshot()` `Set.copyOf(live)` 73-75 or `Set.of()` when no tracker (12756), copied again at 14505; `SnapshotIdentitySet` | Copy | `unfreed.restore` 12767: `live.clear(); live.addAll(snapshot)` 77-80 | `unfreed.merge(incoming unfreedLive list)` 12895: `clear`; return if empty; `addAll(first)`; `retainAll` each 82-89 | Element traversal of `SnapshotIdentitySet` (restore, first-predecessor seed); `retainAll` uses existing live iterator removal plus `contains`; tracker `live` becomes `IdentityHashSet` (UNFREED_CONTRACTS.md:15) |

RejectedFreeEvidence operations driven by the same snapshots:

| Operation | Lines | Operations on snapshot and live state | Needed beyond D254 |
| --- | --- | --- | --- |
| `save(snapshot)` | 364-381 (called from snapshotOwnership 12757-12758) | `retireCollected`; sum six live sizes; one atomic reservation; `snapshots.put(new SnapshotKey(snapshot, retired), new Saved(six Map.copyOf, units, associations))`; values traversal of the six live maps incrementing identity refcounts (E2) | Strong identity-keyed saved-version store keyed by the OwnershipSnapshot object; live values traversal is existing ds iteration; primitive refcounts (API0502) |
| `restore(snapshot)` | 383-393 (from restoreOwnership 12788-12789) | Look up Saved by snapshot identity; `clearCurrent` (values traversal of live maps decrementing refcounts, then `clear`, E5); `copyIntoCurrent(saved maps)`: reservation, `putAll` from each saved map, values traversal of each saved map incrementing refcounts (E4) | Entry traversal (or copy-into) and values traversal of `SnapshotIdentityMap`/`SnapshotMap`, any order |
| `merge(incoming)` | 395-447 (from mergeOwnership 12806) | For each incoming snapshot in list order: absent Saved clears and returns false; first one: mutable copies of the six saved maps (411-416); later ones: `removeIf` keeping Site value equality, Binding allocation/source identity plus span value, Event/Join identity (419-438); then `clearCurrent` and `copyIntoCurrent(common)` (E3, E4) | Mutable independent copy from each saved snapshot map; Site/SourceSpan value equality (C3) |
| `retainArrayStores(liveSlots)` | 291-302 (from mergeOwnership 12855) | Iterator removal scan (E1) | Delivered (D255) |
| `retireCollected` | 573-589 | Poll ReferenceQueue; remove dead Saved; release units/associations; values traversal of the six saved maps decrementing refcounts (E6) | Explicit `retire(snapshot)` with the same release, called at the snapshot's last use; values traversal of saved maps |
| `close` | 591-609 | Clear every store and release remaining units once | Retire all saved versions |

Identity comparisons (`!=`/`==` on AllocationInfo, Event, Join, SourceFile) need
no API: Ironwood reference comparison is identity. Value comparisons that do
need explicit code are AllocationStateSnapshot (12818), Site (RejectedFreeEvidence
419, 432, 435; uniqueIncomingArrayStore 13180), SourceSpan (425) and the
ArraySlot/Retention map keys; all are C3.

## Other closure items found (not helpers)

- M1 checkpoint report: hash the delivered helper `.iron` sources for the M2.1
  combined bundle and list remaining B1/B2/B7 work (PILOT_HANDOFF.md:74-79,
  PILOT_BUDGETS.md:44-48, BEFORE_SELF_HOSTING_PLAN.md:377-381).
- Record the eight attribution-mismatch RECORD rows as excluded.
- Rewrite conventions that the M2 port depends on and that no M1 record yet
  fixes: keyword lookup form (API0481), presence representation for Optional
  fields in the operation factory (API0522/0533/0537), primitive counter
  representation (API0502), record-to-class conversion with constructor check
  order (RECORD rows), and the `allocations`-list rewrite for states.
- Existence of every (A) member was verified by grep. Behavioral equivalence of
  (A) members (for example Character digit/whitespace tables, Path basename) was
  not executed in this analysis; the M0 contracts state them as existing exact
  overloads (FRONTEND_CONTRACTS.md:27-46).

## Remaining (C) deliverables, in dependency order

1. **C3 Record value operations** (M1.3). Explicit `equals`/`hashCode` with no
   allocation and `equals(null) == false` for: AllocationStateSnapshot (state
   enum, null-safe reason text value, detached bit); ArraySlot (container
   reference identity plus int index; key of knownArraySlots and arrayStores);
   RejectedFreeEvidence.Site (SourceFile identity plus SourceSpan value);
   Retention (owner/child identity pair; retentions key); SourcePosition and
   SourceSpan (primitive values); ClosedWorldEffectAnalyzer.Summary (two flags
   plus logical BitSet equality via util/BitSet.iron:399, compared with a
   possibly null previous at J0 ClosedWorldEffectAnalyzer.java:98). Citations:
   SNAPSHOT_CONTRACTS.md:16-17, 20, 22, 24; FRONTEND_CONTRACTS.md:66-75;
   FRONTEND_PILOT.md:72-79; PILOT_HANDOFF.md:23; operation-model-schema.json
   selected rows for AllocationStateSnapshot, ArraySlot, Site, Retention,
   Summary, SourcePosition, SourceSpan.
2. **C1 Seven-field OwnershipSnapshot composition** (M1.1/M1.2). A
   compiler-private owner of the seven D254 wrappers listed above, built
   all-or-nothing from the live state (null source: NullPointerException with
   no retained storage), owning the AllocationStateSnapshot values it creates,
   and retired explicitly after its last branch/loop/restore/evidence consumer
   without freeing AllocationInfo nodes. Required read capabilities: ordered
   key/value traversal of SnapshotLinkedMap (restore, first-path common slots,
   per-path lost-slot scan, D247 order); key/value traversal of SnapshotMap and
   SnapshotIdentityMap; element traversal of SnapshotIdentitySet; each
   traversal read-only, independent of any live map's shared iterator, and
   lending the snapshot for returned aliases (D249 convention). Required
   lifetime: shared immutable retained-child versions referenced by the live
   retainedBorrows map and by many snapshots need one explicit owner (for
   example invocation-owned retirement) rather than per-snapshot frees. Live
   type decisions: exposedContainerContents and tracker `live` as
   IdentityHashSet; borrowedOwnedFields HashMap versus SnapshotLinkedMap.
   Evidence: seven-field Java projection (OWNERSHIP_PILOT.md:109-116),
   SlotOrder/BranchJoin D247 ordering, collision/permutation fixtures, OOM
   rollback, safe/unsafe pairs (free of a node still held by a snapshot
   rejected; alias after snapshot retirement rejected) in every unfreed mode.
   Citations: PILOT_HANDOFF.md:21-22; OWNERSHIP_PILOT.md:17-23, 89-97, 152-156;
   SNAPSHOT_CONTRACTS.md:15-19, 71-83; OPERATION_MODEL.md:29-38;
   ordered/DELTA.md rows 12545, 12605, 12646; UNFREED_CONTRACTS.md:15;
   docs/self-hosting/m1/KEYED.md (traversal deferred to this composition).
3. **C2 RejectedFreeEvidence saved-version store** (M1.1/M1.3). Replace
   SnapshotKey/WeakReference/ReferenceQueue/identityHashCode with a strong
   identity-keyed map from OwnershipSnapshot to Saved (four SnapshotIdentityMap,
   two SnapshotMap, units, associations); an explicit `retire(snapshot)` with
   the E6 release (units, associations, saved payload refcounts) called at the
   snapshot's last use, and `close` retiring all; mutable independent copies
   (or entry traversal) from saved maps for merge and restore; values traversal
   of saved maps; primitive identity refcounts without boxed Integer. An unknown
   or retired snapshot keeps today's truthful fallback (`snapshotTruncated`,
   return false). No GC assumption, registry for misuse detection, or free-proof
   exemption. Citations: SNAPSHOT_CONTRACTS.md:25-31, 46-52;
   OWNERSHIP_PILOT.md:84; OPERATION_MODEL.md:60-67; operation-model-schema.json
   RESTRICTED RejectedFreeEvidence$SnapshotKey; PILOT_HANDOFF.md:23.
4. **C7 Finite-model validation and variant coverage** (M1.3). Native input
   validation that rejects every non-selected role before analyzer entry
   (instruction/terminator/operand variants, enum cases, nonempty
   class/type/specialization input, invalid budgets) and a build-time check that
   fails when a model variant is untreated or a treatment is removed, applied to
   the rewritten pattern switch (J0 ClosedWorldEffectAnalyzer.java:149-154) and
   the frontend model treatments. Citations: PILOT_HANDOFF.md:23, 41-46;
   OPERATION_MODEL.md:21-27, 40-52, 80-83; OPERATION_FACTORY_CONTRACTS.md:45-50;
   FRONTEND_PILOT.md:46-79; BEFORE_SELF_HOSTING_PLAN.md:370-373.
5. **C4 Private empty and singleton immutable lists** (M1.1/M1.3). An immutable
   empty list with an explicit shared lifetime (never freed per AST node) and a
   singleton factory rejecting null with NullPointerException, readable through
   the same size/get/isEmpty contract as SnapshotList; 49 `List.of()` and 2
   object `List.of(E)` sites. Citations: AST_CONTRACTS.md:35;
   FRONTEND_CONTRACTS.md:54; FRONTEND_PILOT.md:91-92; EFFECT_CONTRACTS.md:27;
   DIAGNOSTIC_CONTRACTS.md:17.
6. **C5 Primitive segment-count storage** (M1.1/M1.3). An independent
   immutable ordered int sequence for TypeName/TypeReference and parser
   segment counts: copy from a builder, size/get, empty and singleton forms,
   Java int-wrapping sum by loop, negative-count check order preserved; replaces
   `List<Integer>` at TypeName:16-18, TypeReference:12-14, Parser:2612, 2836-2857,
   2892. Citations: FRONTEND_CONTRACTS.md:54, 77-80; AST_CONTRACTS.md:25-26;
   FRONTEND_PILOT.md:67-68, 93.
7. **C6 Escape-decoder primitive presence** (M1.3). Replace
   `Character decodeSimpleEscape(char, boolean)` (Lexer.java:417-429) with a
   private decoder returning a UTF-16 unit or an explicit absent value (for
   example int -1), used at Lexer.java:382 (char literal), 456 (string) and 539
   (cooked text block) with `append(char)`; preserves the unsupported-escape
   diagnostic and fallback append; no allocation. BUILDER_CONTRACTS.md:17 places
   this in M1.3 before the M2.1 CookedEscapeUnits fixture; D256's boundary
   (DECISIONS.md D256) leaves cooked decoding itself to M2.1 port work.
   Citations: FRONTEND_CONTRACTS.md:37; BUILDER_CONTRACTS.md:17;
   PILOT_HANDOFF.md:23; m1/PRE_CHANGE.md:37-38.
