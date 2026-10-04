<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Finite ownership and effect model

operation-model-schema.json defines the selected private input boundary independently
of observed corpus variants and serializer output. The discovery roots are every
original IR source, FunctionAnalyzer, ClosedWorldEffectAnalyzer, RejectedFreeEvidence,
UnfreedAllocationTracker, SemanticAnalysisObserver, UnfreedMode and source values.
Reflection describes nested declarations, record fields in declaration order,
every enum constant, every permitted sealed variant and ordinary declared fields.
The discovery tag class includes ordinary interfaces; methods/callback semantics
remain in the reviewed source ledgers. No native reflection facility is implied.

The 132 original source files describe 233 declarations: 181 records, 27 enums,
five sealed roots and twenty ordinary classes/interfaces. Two fresh JVMs per
seed agree byte-for-byte. Every frozen source hash is verified against its
identity, and current source must match ordered J0-D247. FunctionAnalyzer's D247
implementation delta preserves this model shape; the source hashes remain
distinct. Gzip stdout preserves empty trailing TSV fields, exact bytes and SHA-256.

Every declaration has one explicit role and gate:

| Role | Count | Treatment |
| --- | --- | --- |
| Selected | 13 | All named fields have a reviewed value/identity, copy and lifetime contract; prepare B1/B7 M1.1/M1.3 before M2.2. |
| Restricted | 31 | Only the exact private fixture roles below are admitted; enforce them before M2.2. Other roles remain B1/B7 M3.1 before S3. |
| Later-only | 189 | The operation factory cannot admit this named type. It needs explicit field/dispatch treatment before its S2/S3 consumer, reconciled by M6.3. |

AllocationInfo remains an identity node with LOCAL_NEW origin, null owned-field
and constructed-type payloads, empty candidate/final-field membership and present
true. The fixture varies creation depth, state/reason/detached and all seven
OwnershipSnapshot fields. ACTIVE/ESCAPED branches yield UNCERTAIN joins. General
FRESH_CALL/OWNED_FIELD/ONE_OF origins and FREED/MAYBE_FREED proof consumers remain
excluded from this operation workload. Separate source safe/unsafe tests do not
turn this bounded operation factory into a full reclamation proof analyzer.
AllocationStateSnapshot and ArraySlot retain their value/composite-key contracts.
The seven snapshot fields retain independent backing storage and borrow nodes;
immutable retained-child versions remain shared until all consumers retire.

The effect input uses unique Findex functions with owner Node, METHOD kind and
the original default source identity `<unknown>.iron`. Parameters and results are
IrValueReference values of `IrType.reference("Node")`; its type arguments are
empty and element/erasure/wildcard fields absent. Each function has one entry
block, ordered direct/foreign calls and an IrReturnTerminator with a reference
result. Direct calls carry DIRECT kind and empty specialization/devirtualization
metadata. Foreign calls use the fixed compiler-owned callback target, matching
non-void result, ordered reference arguments and absent invocation context.
The private fixed-name factory needs no regex engine. General admitted foreign
constructor calls remain outside this finite private fixture's contract.
Class inputs are empty. Every other IrInstruction/IrOperand/IrTerminator variant
is explicitly rejected before this analyzer entry; there is no permissive
fallback. The explicit factory roles cover both chains and actual call cycles.

Effect/summary flags and all five BitSet fields have explicit copy/mutation roles.
The retained observer is nullable and only emits EFFECT/INITIAL rounds for this
fixture; it cannot change facts. Other observer enum cases and broad semantic
analysis retain later gates. SourceFile is the reviewed in-memory value, with
file acquisition still outside the operation scope.

The explanation store uses its six selected association maps and strong saved
version keys. Explicit version identity/retirement replaces Java weak references
and queues under B1/B7; it grants no native reclamation exemption. Event inputs
are REASON with absent call payload. Join alternatives are empty; omitted zero
and classification/complete have the fixed reference values. FieldLoad, Call,
JoinAlternative and full witness objects are later-only, rather than silently
treated as null-compatible implemented visitors. Limits describe all five
fields, while the selected workload uses only its three configured budgets.
The WARN diagnostic tracker uses registration order/live intersection and empty
findings/suppression in branch resources. Its broader diagnostic behavior has
the separate unfreed-contract qualification.

qualify-operation-model.py compares every declaration, field and treatment with
independent discovery. It rejects a removed declaration, removed consumer,
unknown declaration, removed record field and omitted permitted instruction
variant. Current tooling/source hashes, six commands, exact logs and all five
negative outcomes are retained in operation-model-probe. Preceding discoveries
and tooling changes remain in ignored scratch evidence. The previous frontend
finite model stays unchanged and supplies the source/AST construction boundary.

This is M0 input/model evidence. M1/M2 must implement the same finite validation
and explicit visitor treatments, pass the missing-treatment checks with native
consumers, preserve the Java facts and prove temporary retirement. This record
does not implement native dispatch, whole FunctionAnalyzer translation, or a
resource budget. Global API/capture/hash classifications still precede S0 closure.
