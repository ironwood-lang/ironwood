<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Private callback contracts and retained captures

Scope: all nine exact `java.util.function` member patterns (105 calls), all
22 callbacks with captured symbols directly passed to constructors (67 capture
rows), one additionally traced callback passed through a method into retained
fields, and ten selected private callback fields. Capture rows count referenced symbols,
not heap objects or native allocations. Several implicit member references
denote the same captured receiver. This review does not classify all 3,054
capture rows: the remaining 2,986 method-argument rows still need their stream,
comparator, factory and helper retention/evaluation contracts.

## Exact functional-member contracts

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, readiness, first consumer / required fixture |
| --- | --- | --- |
| API0607 `BiConsumer.accept(T,U)` | Two clone notifications in InitializedTypeSpecializer/EnumArgumentSpecializer. Pass original then clone, once at the written successful clone-publication point; preserve thrown callback failures. NativeLinkTransformation's callback retains source descriptors in its map. The passes retain the callback field for their complete run; this is not an immediate non-retaining method-argument contract. Use a private clone-observer interface or direct helper, with no public BiConsumer facade. | B7, M3 before S4; M6 for Bridge origin attestation / clone order, colliding/unresolved source and callback failure |
| API0608 `BiFunction.apply(T,U)` | DocComment.render invokes the link service in input encounter order, with true for link and false for plainlink. Java boxes Boolean; native service uses primitive boolean. Render does not store the callback, but it invokes it repeatedly and appends its returned text. Preserve argument evaluation, failure timing and output ownership; appended generated text requires retirement when no longer referenced. | B7, M5 before S6 / mixed link/plainlink, error and independent output |
| API0609 `BooleanSupplier.getAsBoolean()` | FunctionAnalyzer's two flow helpers increment controlFlowDepth, invoke once, and decrement in finally even when the callback throws. Preserve primitive result and receiver-state changes; replacing with an eager precomputed boolean would change nesting. Private direct helper or boolean-returning interface, without boxing. | B7, M3 before S3 / nested lowering, false result and failure counter restoration |
| API0610 `Consumer.accept(T)` | reclaimInOrder invokes the action only after an Accepted mandatory FreeProof, in reverse candidate order over repeated progress rounds. It does not retain the callback. The action may mutate analyzer/IR state; preserve that mutation point and mandatory proof, and do not assume its argument is non-retained. | B7, M3 before S3 / dependent temporaries, unsafe proof refusal and cleanup order |
| API0611 `Function.identity()`; API0615 `UnaryOperator.identity()` | Non-capturing identity services: BridgeJavaSources flattens its existing streams and IrCfgRenamer uses identity labels in Bridge scanners. Preserve exact input reference/value, including null if independently admitted; do not create new nodes. Native direct loops/identity helpers need no facade or per-operation callback allocation. An allocated shared implementation needs an explicit owner/lifetime; Java caching is not a native allocation guarantee. | B7, M3 before S4; M6 before S7 / identity aliases, ordered stream flattening |
| API0612 `Function.apply(T)` | IrCfgRenamer invokes retained value/label mappers at the written SSA/label fields, preserving call order, identity passthrough of non-SSA operands and returned replacement values. BridgeProducer invokes retained bootstrap generation first for the unpaired template hash, then actual build output; LlvmByteViewEmitter calls borrowed operand/scratch services in emission order (scratch mutates name state). PrimitiveGenericSpecializer calls its borrowed instruction factory exactly once on either original or substituted operand. BridgeCallbackBatching.clone applies its borrowed rewrite to instruction lists and invoke calls before returning frozen blocks. Preserve callback failures and actual result aliasing; do not presume returned objects are independently owned. | B7, M3 before S3/S4; M6 before S7 / mapper side effects, repeated SSA IDs, labels, factory branch and template/build output |
| API0613 `Predicate.test(T)` | TemporaryBorrowAnalysis.hasCandidate visits block instructions then invoke call, returning at the first true result. UnfreedAllocationTracker.observe visits origin insertion order after suppression/live guards, then updates findings. Neither helper stores the predicate. Native private boolean services/direct walkers preserve short circuiting, side effects, order and thrown failures. FunctionAnalyzer.preferEligible passes a predicate to a stream terminal that finishes inside the helper; that stream contract remains a separate use-site review. | B7, M3 before S3 / first instruction match, invoke-only match, suppressed origins and ordered warnings |
| API0614 `Supplier.get()` | LValue stores a read service and invokes it later; compound assignment/update timing differs from plain assignment. Other FunctionAnalyzer supplier helpers invoke once in their own scope/depth/cleanup control flow. Preserve normal and exceptional paths, result aliasing and analyzer state at invocation time. emitWithRenderedStringCleanup invokes its emitter before restoring regions; a thrown Java callback is not automatically a new cleanup path absent in J0. Use private typed services or explicit tagged LValue operations. | B7, M3 before S3 / target evaluation once, RHS side effects, compound read, prefix/postfix and exceptional scope exit |

These exact interface members have no universal null/input/output ownership
contract beyond their implementation. A null service fails when invoked;
nonnull validation belongs only to constructors which actually perform it.
Inputs and returns may be retained by callback bodies (for example LValue writes
place operands into environment or IR). Native analysis must prove each actual
effect and lifetime. No borrowed-argument or unknown-effect exemption is added.

## Reviewed retained fields

| ID | Exact original field | Holder and invocation lifetime / selected treatment |
| --- | --- | --- |
| F01 | IrCfgRenamer.java:9 values | Mapper survives construction through all instruction/block/terminator traversal. Keep receiver/counter/map/replacement captures until the renamer's last use; returned IR can retain input/replacement operands beyond the renamer. Private typed mapper or direct visitor. |
| F02 | IrCfgRenamer.java:10 labels | Same holder lifetime; identity or prefixed label mapping preserves label text/value and traversal order. Constructor does not reject null mappers; preserve admitted failure timing if exposing this helper. |
| F03 | BridgeProducer.java:195 bootstrap | Projection returns the callback, so captures outlive projection(). It is invoked during nativeInputs and later source publication. Generation, Java projection and adapter values remain live through both; returned generated text has its own lifetime. |
| F04 | EnumArgumentSpecializer.java:24 cloned | Passed through specialize and stored for the whole run; invoked after constructing a clone, before recording/returning transformation results. Same observer can be shared by both specialization passes. |
| F05 | InitializedTypeSpecializer.java:30 cloned | Same passed observer, retained through run and clone notifications. Retire pass storage before observer/captured state; no asynchronous dispatch is present. |
| F06 | CompilerPipeline.java:23 analyzerFactory | Nullable field, null selects direct SemanticAnalyzer construction. Non-null factory survives pipeline construction/repeated analyze calls; package-private injection is a Java qualification seam. It returns a newly selected analyzer; omission from a native slice must be explicit rather than an assumed no-retain contract. |
| F07 | AnonymousParentBinder.java:36 contextFactory | Non-null checked field, called by bindOne after construction for successive deferred anonymous types. Captures SemanticAnalyzer receiver and live hierarchy, which can change between calls. A copied frozen hierarchy would change behavior. Returned planning contexts require their own actual lifetime/effect review. |
| F08 | InvocationPlanningContext.java:185 expressionProbe | Non-null checked InferenceRequest component retaining InvocationPlanner receiver. Synchronous infer recursively probes argument expressions, sometimes twice after expected-type refinement. Keep request/probe/planner and planning state live across recursion; return structured results without freeing retained type plans. |
| F09 | FunctionAnalyzer.java:14291 read | LValue component; captures target state and FunctionAnalyzer receiver. Used after resolveLValue returns, before RHS for compound assignment, and for update. Private tagged target may remove callbacks, preserving evaluation/exception order. |
| F10 | FunctionAnalyzer.java:14292 write | LValue component using existing private LValueWriter.accept(IrOperand,SourceSpan,SourceSpan). Writes after RHS; array/plain field null/bounds validation stays at the original write point, with correct spans, escape tracking and emitted IR. Never free operand solely because the write returns. |

SemanticAnalyzerFactory, InvocationPlanningContext.ExpressionProbe,
AnonymousParentBinder.PlanningContextFactory and FunctionAnalyzer.LValueWriter
are compiler-defined interfaces. Retain their ordinary signatures without
@FunctionalInterface. Reference bounds and primitive parameters/results must
match the selected named/anonymous implementation. B7 supplies only the actual
private service types; no public java.util.function compatibility layer.

## Exact captured callback sites

Each row is source-reviewed, with actual captured symbols/types and UTF-16
positions retained in the joined ledger. The family field names the lifetime
contract above. All allocations/capture cleanup require native evidence later.

| ID | Exact original callback | Reviewed holder/evaluation contract | Owner, readiness, first consumer / required fixture |
| --- | --- | --- | --- |
| C01 | BridgeProducer.java:207 | F03 owned-callback projection: generation, Java declarations and adapter holder retained through template/build generation. | B7, M6 before S7 / owned callback producer, two bootstrap calls |
| C02 | BridgeProducer.java:216 | F03 synchronous callback projection: generation, Java projection and adapters retained. | B7, M6 before S7 / synchronous callback producer |
| C03 | BridgeProducer.java:227 | F03 permanent-object projection: generation, projected declarations and adapters retained. | B7, M6 before S7 / permanent roots and object projection |
| C04 | BridgeProducer.java:238 | F03 copied-value projection: generation, Java projection and native values retained. | B7, M6 before S7 / copied-value bootstrap output |
| C05 | EnumArgumentSpecializer.java:122 | F01 scanner captures mutable next[]; visits parameter/block values before rename. | B7, M3 before S4 / max SSA ID across all reached variants |
| C06 | EnumArgumentSpecializer.java:126 | F01 mapper captures parameter/replacement; same SSA id substitutes exact replacement, others preserve identity. | B7, M3 before S4 / repeated parameter use and distinct other value |
| C07 | InitializedTypeSpecializer.java:200 | F01 scanner captures mutable next[]; return original values while observing IDs. | B7, M3 before S4 / parameter/body ID maxima |
| C08 | InitializedTypeSpecializer.java:210 | F01 mapper captures parameterIds, values and next[]; parameter identity preserved, other IDs assigned on first encounter and reused. | B7, M3 before S4 / repeated IDs and first-encounter numbering |
| C09 | InitializedTypeSpecializer.java:212 | F02 mapper captures label prefix; maps every label to prefix plus body suffix. | B7, M3 before S4 / colliding existing prefix and branch labels |
| C10 | BridgeCallbackBatching.java:136 | F01 scanner captures maximum[]; identity labels separately supplied by UnaryOperator.identity. | B7, M6 before S7 / primitive proxy scan and batch SSA IDs |
| C11 | BridgeCallbackCarrierLifetime.java:133 | F01 transient renamer captures used[] and aliases; invocation ends before observing used flag, one instruction per holder. | B7, M6 before S7 / carrier alias in each admitted instruction variant |
| C12 | BridgeCallbackContextLowering.java:156 | F01 scanner captures maximum[]; used to allocate context value after complete block scan. | B7, M6 before S7 / context ID after all previous SSA values |
| C13 | FunctionAnalyzer.java:7785 | F09 local read captures symbol, name and analyzer receiver; readLocal runs at invocation time. | B7, M3 before S3 / compound local read before RHS |
| C14 | FunctionAnalyzer.java:7786 | F10 local write captures symbol/analyzer; environment.put retains the written operand in current flow state. | B7, M3 before S3 / RHS changes environment before write |
| C15 | FunctionAnalyzer.java:7907 | F09 array read captures resolved target/type/expression/analyzer; emits load and tracks alias at read time. | B7, M3 before S3 / array target/index evaluated once |
| C16 | FunctionAnalyzer.java:7913 | F10 array write captures target/access/plainAssignment/analyzer; plain validation after RHS, then tracking/store. | B7, M3 before S3 / invalid target versus RHS side-effect timing |
| C17 | FunctionAnalyzer.java:7935 | F09 instance field read captures receiver/field/span/analyzer; emits and tracks owned-field load. | B7, M3 before S3 / compound field read and ownership alias |
| C18 | FunctionAnalyzer.java:7941 | F10 instance field write captures receiver/field/validation/analyzer; chooses null check or not-freed check, detaches ownership, marks escape, stores. | B7, M3 before S3 / plain versus compound receiver validation |
| C19 | FunctionAnalyzer.java:7956 | F09 static read captures field/span/analyzer; ensures initialization when required before load. | B7, M3 before S3 / initializer effect before static read |
| C20 | FunctionAnalyzer.java:7964 | F10 static write captures field/analyzer; ensures initialization, marks escaped value, stores at write time. | B7, M3 before S3 / static initialization/RHS/write ordering |
| C21 | InvocationPlanner.java:879 | F08 this::plan stored by InferenceRequest through context.infer; recursive/expected-refined probes observe the same planner. | B7, M3 before S3 / recursive generic inference and second probe |
| C22 | SemanticAnalyzer.java:1104 | F07 factory captures receiver and hierarchy, reused by binder through the changing deferred-parent loop. | B7, M3 before S3 / nested qualified anonymous types and updated hierarchy |
| C23 | NativeLinkTransformation.java:32 | F04/F05 observer captures sources map, passed through optimize into both retained pass fields; records/rejects clone origins. Both holders are confined to optimize, not returned in IrProgram/transformation. Sources map is still consumed afterward to build origins; its values can be retained by that result. METHOD_INVOCATION context does not prove non-retention. | B7, M3 before S4; M6 before S7 / source mapping across both passes and callback failure |

## Qualification boundaries

`review-callbacks.py` checks all captured constructor sites, this additional
method-to-field path, all nine functional member patterns and source hashes of
sites/holders/helper consumers. It fails for missing/stale reviews. It does not
grant those contracts to unrelated stream/comparator callbacks. Stream pipelines
can retain intermediate callbacks until terminal traversal; comparator factories
can retain key extractors after returning. Their invocation order and lifetime
must be reviewed at each actual consumer, not classified from parent syntax.

M0.3 must state which real retained-service workload is selected for M1/M2.
Native allocation counts, failure cleanup and safe/unsafe capture reclamation
are not measured by this source review. Safe retirement requires holder/callback
and captured state to be unobservable under the actual proof; freeing a callback
does not free captured objects or automatically remove every alias. No Java
lambda caching, GC, borrow exemption or weakened mandatory safety is assumed.
