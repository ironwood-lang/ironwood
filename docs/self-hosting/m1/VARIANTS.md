<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M1.3 finite input model and variant coverage

Base: 353268c7 (D259). This settles the M1.3 obligations to "rewrite any pattern
dispatch reached by the pilot and establish its variant coverage check" and to
"enforce selected/restricted model roles and input rejection before M2"
(PILOT_HANDOFF; OPERATION_MODEL). D260 records the decision; the ledger is
[variants-evidence/manifest.json](variants-evidence/manifest.json).

## Mechanism

Ironwood rejects a `switch` expression over an enum that neither covers every
constant nor declares `default`. `OperationVariants` uses that rule as the
coverage check: each closed list is an enum, and each admission function names
every rejected variant in an explicit `false` arm. Adding a variant, deleting a
rejection or dropping an admitted arm stops compilation; there is no fallback
that could admit an unreviewed variant.

| List | Java source | Admitted |
| --- | --- | --- |
| `Instruction` (85) | `IrInstruction` permits | `CALL`, `FOREIGN_CALL` |
| `Terminator` (7) | `IrTerminator` permits | `RETURN` |
| `Operand` (6) | `IrOperand` permits | `VALUE_REFERENCE` |
| `CallKind` | `IrCallKind` | `DIRECT` |
| `CallableKind` | `IrCallableKind` | `METHOD` |
| `TypeKind` | `IrType.Kind` | `REFERENCE` |
| `AllocationOrigin` | `FunctionAnalyzer.AllocationOrigin` | `LOCAL_NEW` |
| `AllocationState` | `FunctionAnalyzer.AllocationState` | inputs `ACTIVE`, `ESCAPED`; join results also `UNCERTAIN` |
| `EventKind` | `RejectedFreeEvidence.EventKind` | `REASON` |
| `AnalyzerKind`, `AnalyzerPhase` | `SemanticAnalysisObserver` | `EFFECT`, `INITIAL` |
| `UnfreedMode` | `UnfreedMode` | `WARN` |

The effect analyzer's pattern `switch` (J0 ClosedWorldEffectAnalyzer.java:149-154)
becomes an ordered `instanceof` chain with one selector evaluation and the
`null` default. The M2.2 input factory consults these functions before analyzer
entry; M2.1 applies the same pattern to the frontend's AST variants.

## Evidence

| Check | Result |
| --- | --- |
| Java tie-in | Every list equals the live sealed permits (normalized names) or enum constants obtained by reflection |
| Fails closed | Removing `UNREACHABLE` from a rejection arm, adding an untreated `PHANTOM` terminator and deleting the `REASON` arm each fail compilation in off/warn/error with the exhaustiveness diagnostic |
| Admission | The native fixture prints exactly the admitted sets above from classes and archive at `-O3`, zero `--unfreed=warn` diagnostics, exit 42 |
| IronDocs | Thirteen types (the class and its twelve enums) generated without diagnostics |
