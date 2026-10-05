<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Private operation factory source contracts

The finite factory in OPERATION_MODEL.md constructs native data for M2.2. It
does not port the public Java IR constructor surface. Its source reference is
KernelCapture's REF and effect function/block/call construction: reference Node,
ordered pindex parameters, Findex METHOD functions, one entry block, DIRECT
calls with absent specialization/devirtualization, fixed foreign target
ironwood_bridge_callback_effect, reference results and absent invocation context.
Every type argument/element/erasure/wildcard field is empty or absent as recorded
in operation-model-schema.json. Other inputs require an explicit rejection before
this analyzer entry. The Java resources execute these actual constructors across
the recorded chain/cycle sizes; independent model discovery checks every field.
Neither evidence substitutes for M2's native input validation or retirement proof.

The source join selects 51 exact external calls in restricted constructors, with
14 declaration patterns. The compact constructors also contain branches for
excluded roles. A source span in this ledger does not require implementing those
branches for the private factory. The ordinary Java constructors and every
general producer retain B1/B7 M3.1 before S2/S3, including nonempty specialization,
non-reference kinds, arbitrary target names/context, void arguments and general
nullable inputs. Only the explicit factory data treatment enters B1 M1.1 and
B7 M1.3 before M2.2. No public regex, Optional or Map facade is selected.

| Exact declarations | Source contract and bounded factory treatment |
| --- | --- |
| API0441 | List.copyOf makes independent ordered shallow non-null membership. IrType has empty arguments; calls borrow ordered reference arguments; IrFunction copies ordered parameters and its one block; IrBasicBlock copies ordered direct/foreign instructions. Factory builders retire only after copies and their borrowed fields reach the last analyzer/output consumer. Neither copy nor immutable membership transfers payload ownership. General null-list/null-member construction remains rejected by Java and later source consumers. |
| API0442; API0482; API0480 | Empty List/Map factories supply no encounter-order observation. IrCallInstruction's specialization is absent/empty; native data stores empty membership without a hash traversal. General Map.copyOf rejects null source/key/value and uses ordinary key equality, with unspecified iteration, but no nonempty specialization input enters this factory. Its general equality/order and borrowed payload lifetime remain M3.1 obligations. |
| API0522; API0533; API0537 | Replace Optional containers with explicit presence. Direct devirtualization/context are absent; foreign/return results are present where the factory supplies them; the cycle's backedge has absent result. IrReturnTerminator and IrCallInstruction normalize null Optional containers to absence, whereas IrForeignCallInstruction requires the containers non-null. Context orElseThrow is guarded by presence; result type access is guarded by result presence. The private factory never evaluates an absent extraction. General constructor default/null and failure order remain later. |
| API0516 | IrForeignCallInstruction validates non-null result wrapper, return type and span before copying arguments, then non-null context wrapper. The fixed factory supplies each in that original order with context absent. Native data/input validation preserves the selected fields without granting an ownership or non-reclamation exemption. Arbitrary partially valid constructors need their later ordered-failure fixture. |
| API0147; API0467 | IrType rejects a blank nominal name and forbids arguments on non-reference kinds; IrFunction requires a nonblank source filename. Factory names are literal Node and the source identity is the original default <unknown>.iron. Type arguments are empty and kind REFERENCE. These facts permit fixed data construction; no arbitrary Unicode name/blank predicate is admitted by this private factory. General nominal/type-parameter/array/wildcard guards remain later, rather than being silently accepted. |
| API0154 | The original foreign constructor matches the fixed callback prefix and ASCII suffix. This factory constructs only the literal ironwood_bridge_callback_effect, so it requires no regex engine and admits no caller-supplied target. Arbitrary target/context constructor behavior is outside this native input boundary and remains M3.1. Do not replace the original general matches contract with a permissive prefix test. |
| API0365; API0666 | The foreign constructor reads copied arguments sequentially and rejects a reached VOID argument. Every factory argument is an IrValueReference with the same Node reference type. Direct data construction may use the guaranteed reference field; an unknown/VOID role must be rejected before the selected entry. General anyMatch order, reached nulls and short circuiting retain the original source contract at the later constructor gate. |
| API0073 | Original constructors raise IllegalArgumentException for invalid IR roles, missing/blank names, foreign target/context/result/argument mismatch and nonpositive evidence limits/budgets. The finite input contract rejects excluded roles and uses positive fixed budgets before entry. These exception sites are not new successful native paths. Preserve selected failure/rejection and keep general constructor diagnostics/failure precedence blocked before S2/S3. |

General hash origins discovered flowing into these constructors are not factory
inputs. Their paths stay unresolved at their original producer and general data
constructor gates. The finite factory uses empty type/specialization membership
and ordered owned parameter/block/instruction/argument builders, so it cannot
admit any of those original nonempty producer graphs. The hash join must retain
both facts: conditional factory exclusion and an unresolved general producer,
without calling either a global order-independent proof.

Required M2 controls reject an added instruction/terminator/type role, nonempty
type/specialization/class input, unsupported call/context/target, invalid budget,
and a missing constructor field treatment. Validate before invoking selected
analysis, compare accepted fields/results against the frozen Java runs, and
pair accepted retirement with rejected frees of still-observed builders,
borrowed operands, snapshots and observer captures. No native result is claimed.
