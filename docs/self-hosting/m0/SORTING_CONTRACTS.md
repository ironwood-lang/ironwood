<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Sorting, sorted keys and comparator contracts

This review covers 41 exact external declaration patterns, 554 attributed calls
in 72 original source files and 134 named consumers. It extends the discovery
B2 candidate set with both Stream.sorted overloads, min/max and unsigned
long/byte comparison. The ledger preserves each exact source excerpt/type,
callback/capture site and blocking consumer. It reviews API and immediate caller
facts; it does not prove every upstream producer, tie or event order. Those
obligations remain explicitly unresolved before their dependent consumer gate.
No selected frontend or bounded ownership operation needs the B2 list API.
ClosedWorldEffectAnalyzer's sorted observer projection is Java reference output;
the finite native operation projection uses explicit input keys instead.

## Exact declaration contracts

| Exact discovery declarations | Reviewed contract and required private/native treatment |
| --- | --- |
| API0345 ArrayList.sort(Comparator); API0477 List.sort(Comparator) | In-place stable ordering of borrowed elements in owned mutable list storage. A null Java comparator selects natural ordering; comparator failure propagates and does not promise rollback. B2's distinctly named sortWithComparator requires explicit non-null comparison even for empty input and retains the existing E extends Object boundary. Do not broaden Java-shaped sort(null) beyond its existing compile-time boundary. |
| API0374 Comparator.comparing(Function); API0375 comparing(Function,Comparator) | Capture the extractor and optional key comparator until the composed comparator retires; require non-null arguments when composing, compare natural keys or explicitly selected keys and propagate null/key/selector failure. Fresh selector results need an explicit owner; do not allocate a per-comparison wrapper or silently free a possibly retained result. |
| API0376 comparingInt(ToIntFunction); API0377 comparingLong(ToLongFunction) | Extract primitive signed keys and compare by sign/order, without subtraction overflow or boxing. Selected source callbacks borrow nodes/fields/maps through terminal traversal. Callback evaluation is observable if a caller has effects; precomputing keys requires a source purity/ownership proof. |
| API0378 naturalOrder(); API0379 reverseOrder(); API0381 reversed() | Select the actual element domain's order and reverse operand roles. Negating an arbitrary comparison result fails for Integer.MIN_VALUE. Natural String, signed Integer, enum declaration ordinal, provider-specific Path and unsigned GUID/bytes are distinct source domains. |
| API0380 thenComparing(Function); API0382 thenComparing(Comparator); API0383 thenComparingInt(ToIntFunction) | Lexicographic composition invokes the next comparison only after zero from the preceding one. Preserve ordered selector evaluation and null/exception behavior. Equal complete keys retain encounter order in stable sorting; no added address/hash/previously assigned ID tie-breaker is authorized. |
| API0585 TreeMap(); API0586 TreeMap(Map) | Own independent mutable sorted-key backing storage with shallow borrowed keys/values. The Map overload uses natural ordering; it does not inherit an arbitrary runtime source comparator. There are 73 String-key and one Path-key TreeMap constructor sites. Path order/equality has its separate blocked contract below. Values include text, byte arrays and compiler records; no payload copy/free is implied by a map copy. |
| API0587 TreeMap.containsKey(Object); API0591 get(Object); API0596 remove(Object); API0597 size() | Comparator equality defines key membership. Natural-key null queries fail, including empty maps. Null values are admitted, so absent and present-null differ; size is mapping count. remove retires the binding/backing association only, with returned payload borrowed or transferred by the caller's explicit protocol. |
| API0593 put(K,V); API0594 putAll(Map); API0595 putIfAbsent(K,V) | Comparator-zero updates the existing representative key. put returns prior value; putIfAbsent replaces a prior null value. Accept null values, reject invalid natural keys and preserve source/callback exceptions. Bulk insertion owns new backing associations, never input payloads. |
| API0588 entrySet(); API0592 keySet(); API0598 values(); API0590 forEach(BiConsumer) | Ascending key traversal, including values in that key order. Java views are live and borrow their owner; immediate callback traversal borrows keys/values until return. Native caller-local ordered scans must preserve encounter/removal/retention distinctions; an immutable unordered copy is not that boundary. |
| API0589 firstEntry() | Empty yields null; otherwise minimum key. Java returns an immutable independent entry snapshot borrowing its key/value. BridgeAssembler uses the first host only after nonempty input/unique target insertion. A private minimum-key/value helper may avoid the entry object while preserving selection and lifetime. |
| API0599 TreeSet(); API0600 TreeSet(Collection); API0601 TreeSet(Comparator) | Independent sorted membership. The fourteen constructors have eleven String, one Integer, one custom Key and one Path domain; they require distinct comparisons. Comparator-null means natural ordering in Java; private typed helpers must state their explicit comparison. A custom comparator defines equivalence, which can differ from record equals/hash. |
| API0602 TreeSet.add(E); API0603 addAll(Collection); API0604 contains(Object); API0605 isEmpty(); API0606 size() | Comparator-zero deduplicates and retains the first inserted representative, even when record values differ. contains uses that equivalence, not ordinary hash equality. Natural nulls fail; custom comparator null domain follows that comparator. Results describe membership changes/counts and borrow original elements. |
| API0679 Stream.sorted(); API0680 sorted(Comparator) | Lazily retain source/comparison until the terminal operation; ordered streams sort stably. Materialized outputs need independent ordered backing with borrowed elements, including immutable toList results. Natural domains are exactly 19 String, 12 Path and 10 Integer sites. Null explicit comparator fails when creating the stage; null elements follow comparison/terminal contracts. |
| API0675 Stream.min(Comparator); API0674 max(Comparator) | Sequential selection returns the first equal-key representative, empty yields absent and a selected null cannot populate Optional. Preserve comparison/exception and source encounter order. SemanticAnalyzer's narrowest-span and LexicalTypeScopes' deepest-scope selections have observable tie precedence. |
| API0096 Long.compareUnsigned(long,long); API0097 reference to the same overload; API0348 Arrays.compareUnsigned(byte[],byte[]) | Unsigned 64-bit GUID order and unsigned lexicographic byte order, including prefix length, are not signed comparison or String order. Arrays are borrowed read-only during comparison. Selected Long method references use primitive payloads without changing ordering through boxed identity. |

JDK facts are checked against the pinned Java 21 library and local src.zip members
TreeMap, TreeSet, Comparator, ReduceOps, BinaryOperator and UnixPath. Only their
identity hashes and contract facts are retained; no upstream algorithm, comments,
documentation or tests are copied or translated. The probe is independently
written under the default Ironwood license.

## Source-specific ordering boundaries

| Actual source consumers | Source facts, unresolved upstream obligation and required fixture |
| --- | --- |
| AnonymousParentBinder.bindingOrder | Ascending enclosing-allocation span width, then start offset, then type name. Binding order affects enclosing-instance availability. Preserve source positions/name ties; test nested/adjacent anonymous parents before B2/B7 M3.1's S3 consumer. |
| LexicalTypeScopes.resolve, resolveVariable, innermostScope; SemanticAnalyzer callable-scope min sites | Depth descending and declaration offset/ordinal descending for lookup; deepest/narrowest eligible scope for min/max. Source null/name/span guards precede selection. Stable equal candidates can choose a different symbol; trace original scope/ordinal producers before qualifying shadowing/tied-span cases at M3.1 before S3. |
| LocalClassDiscovery and EffectivelyFinalCaptureAnalyzer body-element collectors | Start offset, kind-order, end offset. Traversal schedules declarations/reads and affects capture identity. Same-span role precedence is part of the key; arbitrary container/ID order cannot replace it. B2/B7 M3.1 before S3, same-start/end and nested body fixture. |
| FunctionAnalyzer free witnesses/minima | Allocation-list position, slot index/container allocation-list position, or LocalSymbol.id. The ordinary slot probe's minimum-index precedence is distinct from D247 current-store explanation order. Allocation registration/local ID provenance and equal keys remain upstream gates before general S3 reclamation; preserve paired safe/unsafe witness fixtures at M3.1. |
| EscapeSummaryAnalyzer.finishRawWitnesses; SymbolicReturnOriginAnalyzer.commitWitnesses | Event counter then primitive role or effect/role/detail text. Sorting is after event assignment; it cannot repair an event sequence that came from unordered traversal. Trace event producers and first-witness selection before B1/B2/B7 M3.1 before S3; repeated/permuted event-tie fixture. |
| GenericInferenceSolver, GenericCastSafety, GenericMostSpecificSelector, SemanticAnalyzer display-name sites | Type display/name keys, natural component names and rendered ambiguous candidates. Component sort is mutable; emitted diagnostic lists and representative types retain tie/producer obligations. B2/B7 M3.1/M3.2 before S3, same display name/different structured type and ambiguity fixtures. |
| SymbolicReturnOriginAnalyzer origin/borrowed-origin lists | Enum kind ordinal, parameter index, helper type and textual borrowed field. Field null rendering differs from empty text; preserve enum declaration order and all tie components. B1/B2/B7 M3.1 before S3, same kind/index and nullable borrowed field fixture. |
| InitializedTypeSpecializer roots | Export boundary first, local benefit descending, linkage name. Selection consumes a finite specialization budget and assigns group IDs after sorting. Linkage names supply the final semantic tie; audit demand/benefit producers before B2/B7 M3.3 before S4, equal benefit/different linkage and input permutation fixture. |
| OptimizedTraceMetadata.inject | Unsigned GUID sort, then reject equal GUID/different name collisions before output. GUID assignment precedes sorting and uses MD5; stable equal names and underscore aliases have distinct source facts. B2/B5/B7 M3.3 before S4, unsigned extremes/intentional GUID collision fixture. |
| SharedTraceOrder.ordered | Unsigned GUID then unsigned raw group bytes. Equal complete keys write identical bytes, and output clones the original object before replacement. No nested record/code reordering is admitted. B2/B7 M3.3 before S4, GUID collision/raw byte tie plus malformed Mach-O/ELF extents fixture. |
| IronDoc and DocModel/MarkdownDoclet | Qualified type names, package/type TreeMap keys and member start offsets. Duplicate diagnostics and output membership can depend on source discovery and equal-name encounter order; native filesystem input order must be qualified first. B2/B3/B7 M5.4 before S6, duplicate documented types, equal member spans and Unicode filename fixture. |
| IronJar.create/collect/index | Natural String archive/type keys and separately provider-natural Path license/discovery order. Payload maps own backing and borrow ClassPayload/byte arrays through archive publication; typeEntries Map.copyOf is membership, not preserved sorted iteration. B1/B2/B3/B6 M5.3 before S6, reordered files, duplicate keys, null/empty values and Unicode paths fixture. |
| BridgeAssembler.assemble | TreeMap String target keys choose minimum-target host and ascending host traversal. putIfAbsent rejects repeated targets before firstEntry. Input emptiness is checked; common payload and metadata comparisons follow selection. B1/B2/B6 M6.2 before S7, reversed target inputs and duplicate/empty target set fixture. |
| BridgeGeneration/package/producer/distribution/input/source dictionaries | String natural keys determine generation serialization, generated file membership and duplicate insertion. Owned dictionary backing borrows immutable text or mutable payload arrays until its consumer retires. Copying into later Map.copyOf can erase traversal order; trace the specific serialization/publication boundary before B1/B2/B5/B6 M6.1/M6.2 before S7. Fixture: input/file permutation and same key/different payload. |
| BridgeCustomSnapshotLayout.create | TreeSet Key order is name then Java descriptor; comparator-zero can differ from Key's name/full-IrType record equality. First representative matters before sequential slot IDs and indexed lookup. General descriptor collisions/custom projection producer order remain unresolved. B1/B2/B7 M6.1 before Bridge consumer qualification, same name/descriptor with different structured type and reverse insertion fixture. |
| Bridge root/array/enum/callback source generators | Signed integer input/slot indices, callable linkage, field layout index/name or IrType display/reference name. These keys order emitted native/Java source, field roots and ABI slots. Do not derive ties from already unordered IDs. B1/B2/B7 M6.1 before M6.2/S7, duplicate/equal index, nullable field text and class input permutation fixtures. |
| Semantic BridgeApiFacts/retention/non-reclamation/cleanup summaries | TreeMap String type keys; name/parameter text/owner callable keys; holder/field keys; sorted failure text and retention Site.toString. These helpers are reached during S3, earlier than standalone Bridge production. B1/B2/B7 M3.1 before S3, same callable text with distinct owner and repeated retention-site fixture. |
| Effect/escape/owned-array/summary-witness observer projections | TreeMap String linkage keys canonicalize test projections. They do not prove primary fixed-point or witness-event order. Finite M2 output uses explicit input keys; full S3 observer consumers remain B2 M3.1, projection key collision/reverse insertion fixture. |

## Natural Path ordering is a separate blocked contract

The twelve natural Path stream sites, IronDoc.sources TreeSet<Path>,
MarkdownDoclet.render TreeMap<Path,String> and reverse-order cleanup comparators use
the provider's order, not the order of path.toString(). The qualified Unix
provider compares encoded path bytes unsigned; Java String uses UTF-16 units.
U+E000 versus a supplementary emoji demonstrates opposite order. The original
Ironwood UnixPath.compareTo delegates to String.compareTo, and the focused
original J0 native fixture confirms the difference: its two fresh executions
return one where Java Unix expects zero. Both owned paths are safely reclaimed,
and compilation/linking with unfreed error succeeds. This is recorded mismatch
evidence, not API equivalence or a completed fix.

Natural/reverse Path sites block the affected B2/B3/B7 consumer until either the
existing path API or an explicitly named compiler-private comparator has a
reviewed admitted-provider order. Preserve normalization/distinct semantics and
child-before-parent cleanup. Driver NativeBackend/BridgeNativeSupport paths need
this slice at M4.1 before M4.3 native orchestration; the source-only S4 shell route
does not claim their port. IronJar/IronDoc need it before M5.3/M5.4; standalone
Bridge inputs need it before M6.2. Bring forward only an actually reached path
slice if a source-only consumer needs it earlier. Other host/provider profiles
need their own contract rather than inheriting this macOS observation.

D087 already specifies lexical UTF-16 values with strict UTF-8 native spelling
and a fixed POSIX model without providers; D093 preserves that owned-String
spelling surface. The reference mismatch is a blocked compiler-demand contract
against those decisions, not permission to introduce provider machinery, raw
filename subsystems or Java bucket emulation. Select the smallest actual caller
treatment before that consumer is implemented, retaining any changed Java
comparison baseline. IronDoc.sources inserts toRealPath results into its Path
set for deduplication and ordered source processing. MarkdownDoclet.render
uses destination Path keys to order returned generated document contents. Both
need their key equivalence, null domain and output traversal qualified at M5.4.

## Borrowing, retention and qualification

Comparator factories retain extractors/captured values until the final comparator
holder retires. Stream.sorted retains that comparator lazily until terminal
traversal; TreeSet(Comparator) retains it for the set lifetime. Immediate scans
and sorting borrow element storage while callbacks run. The ledger names captured
symbols/types and exact enclosing call/consumer, with unresolved retention paths
blocking that consumer. No unknown callback is assumed non-retaining. Owned result
lists/maps/sets retire only independent backing; payload/source/IR nodes survive
as long as any output, field, callback or snapshot still borrows them.

The independent B2ContractProbe passes 59 checks in four fresh pinned JVMs:
nullable TreeMap values/membership, copied backing, immutable first-entry snapshots,
custom comparator-zero first representatives, stable equal-key lists, first min/
max ties, UTF-16 names, provider Path distinction, null composition/items and
extreme reverse/unsigned keys, and Path-key TreeMap/TreeSet order, equality,
first representatives and null queries. It does not invoke a native B2 helper. Existing
native Path mismatch evidence is separate and has no resource/pilot budget claim.
Compiler selector/producer/tie fixtures above still precede their consumer gates;
the probe does not certify all 554 caller outcomes or global hash traversal order.

The source ledger requires every exact declaration to have a proof, pins original
and current source identities, and preserves literal UTF-16 source ranges. Focused
review and Java qualification do not implement stable list sorting, generic
callback borrowing, TreeMap/TreeSet facades or later migration consumers. M0's
remaining global API/syntax/capture/hash classifications still precede S0 closure.
