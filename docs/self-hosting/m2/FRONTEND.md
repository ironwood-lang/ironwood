<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M2.1 native frontend slice

Base: 97417cb4 (the M1 checkpoint). This settles M2.1: the selected Java
frontend (FRONTEND_PILOT) runs natively and matches J0. D261 records the
decision; the ledger is [frontend-evidence/manifest.json](frontend-evidence/manifest.json)
and the combined source manifest is [SOURCE_BUNDLE.json](SOURCE_BUNDLE.json).
This does not establish S1/G1, which also needs M2.2 and M2.3.

## Shape

The port lives in `compiler/src/main/ironwood/ironwood/compiler/`: `source`
(`SourceFile.of`, `lineText`, positions, spans), `diagnostic` (diagnostics and
notes), `lexer` (tokens, the lexer and its result), `parser` (the parser, its
ten private records and its result) and `ast` (every selected record, enum and
sealed root). With the M1 helpers and the seven M1 library sources it forms
the 125-unit, 13,164-line source bundle.

| Java | Native |
| --- | --- |
| record | final class, private final fields and accessors in record order, compact-constructor checks in the same order and messages |
| `Optional<T>` component | nullable `T` with presence branches |
| `List<T>` component | frozen `SnapshotList<T>` the node borrows; `List<Integer>` is `SnapshotInts` |
| `List.copyOf` in a record constructor | `AstLists`: a fresh independent copy, or the element type's process-lived empty constant for an empty builder |
| sealed root | interface plus a variant enum; consumers dispatch with an exhaustive `switch` (D260) |
| `destructor` component | `destructorDeclaration`, because `destructor` is a keyword |
| keyword map, nullable escapes, `stripIndent`, varargs `contiguousKinds` | D258 keyword `switch`, `CharEscapes`, `TextBlocks`, fixed-arity overloads |

The [closure reconciliation](frontend-evidence/logs/closure-reconciliation.json.gz)
maps all 376 methods of the M0 call closure: 306 to native members of the same
arity, 44 field-initializer groups to native fields, one to `CharEscapes`, two
span accessors to position pairs, and 23 arity changes, each with its rewrite
(secondary record constructors, fixed-arity overloads, builders passed in, and
the index-range numeric checks). The [model reconciliation](frontend-evidence/logs/model-reconciliation.json.gz)
treats all 119 model declarations: 97 with matching fields, 10 enums with the
same constants, 6 sealed roots and the 6 later-only declarations.

## Lifetimes

The parser owns every builder (`ArrayList`, `IntArrayList`, `StringBuilder`)
and every private holder record and retires each with `defer` or `free`, so a
failure leaves none behind. Nodes, frozen lists, tokens, spans and text are
invocation-lived, as the self-hosting plan's lifetime table prescribes for the
syntax tree. A node keeps the frozen list it stores: freeing that list later is
rejected even after the node is freed (the D253 conservative limit), so only
builders and nodes are retired.

Java relies on its collector for objects the parser builds and then drops.
Most such drops are rewritten away (D261 conventions). What remains is counted
exactly and is invocation-lived:

| Residue | Allocations | Inputs affected |
| --- | --- | --- |
| receiver name of `Name.this` or `Name.super` | 1 (dotted names add their chain) | 10 tracked sources |
| `expr.super(...)` statement: the parsed expression and its span | 2 | 2 tracked sources |
| class subtree discarded when a class body never closes | the subtree | the two truncated workloads |

The two truncated workloads leave exactly their abandoned class: TruncatedParse
abandons 25 allocations (14 for `int main(String[] args)`: the `int` type, the
parameter's name text, segment counts, span, reference type, array span and
type, parameter and span, and parameter list; then 11 for the body: the
literal, return, `if` and its span, statement list, block, method and its span),
and UnterminatedBlock 29 (the same 14, a local declaration of 8, statement list,
block, method and span).

## Equivalence

Every comparison is byte for byte on the four `ReferenceCapture` outputs
(tokens, lexical diagnostics, AST, parse diagnostics) and, for the J0 runs of
`FrontendReference`, on any parser exception's class.

| Input | Units | Files compared | Mismatches |
| --- | --- | --- | --- |
| 36 frozen frontend workloads against their retained J0 captures | 36 | 144 | 0 |
| combined source bundle, fresh J0 | 125 | 625 | 0 |
| every tracked `.iron` source, fresh J0 | 759 | 3,795 | 0 |
| seeded mutations (truncations, deletions, insertions, swaps), fresh J0 | 3,000 (1,940 with diagnostics) | 15,000 | 0 |

Cooked strings and text blocks, Unicode digit continuation, supplementary
characters, CRLF line counting and malformed escapes, radixes and comments are
covered by the corpus and mutations. `Character.isDigit`, `isWhitespace` and
`digit(_, 16)` equal Java 21 for all 65,536 UTF-16 units
([CharacterClasses.iron](frontend-evidence/CharacterClasses.iron)).

The native adapter computes no digest: `SourceFile` wire identity is the file
name and the input's UTF-8 SHA-256, which the harness supplies and the J0 tool
verifies against the decoded text.

## Resources

All runs use the PILOT_BUDGETS procedure: 8,176-KiB soft and hard stack limits
set before exec, `/usr/bin/time -l`, a 2-second runner timeout, two fresh
repeats. Each run reads, lexes, parses, retains everything and writes every
output file. The reference measurements below come from M2.3's isolated run
([QUALIFICATION.md](QUALIFICATION.md#measurement-isolation)); this record's own
runs overlapped a stray helper process and are kept, in brackets and in the
retained logs, as provisional.

| Case | Runs | Maximum wall | Maximum RSS | Budget |
| --- | --- | --- | --- | --- |
| 36 workloads | 72 | 0.024 s [0.040 s] | 17.5 MB [17.5 MB] | 2 s, 1 GiB |
| whole bundle in one invocation | 2 | 0.270 s [0.297 s] | 138.7 MB [140.8 MB] | 2 s, 1 GiB |
| J0 on the bundle (reference, not budgeted) | 2 | 1.438 s [1.649 s] | 414.9 MB [417.9 MB] | |

All 74 records pass `check-pilot-budget.py` in both runs. The lex-and-parse
phase of the bundle takes 10.4-10.5 ms and retains 405,188 allocations.
Doubling the bundle in one invocation scales linearly: 1, 2, 4 and 8 copies
take 0.21-0.26, 0.44-0.45, 0.92-0.93 and 1.87-1.92 seconds (0.010 to 0.084 s of
phase) and 139 to 498 MB; 16 copies (2,000 units) reach the 2-second cap,
mostly writing 800 MB of comparison output.

Stack: bisecting the external limit, every case completes at 128 KiB or less.
Loops128 needs 128 KiB (fails at 112), Control128 112, Loops64 80, Control64 64,
the bundle 48 and the rest 32.

Temporaries: an allocation census walks every retained output once, counting
each distinct object at its calibrated cost (a frozen list 4, an int sequence
2, a runtime string 1, any other object 1) with literals and shared empties
pre-marked. The live-allocation increase over the phase equals the census for
every accepted workload and for the whole bundle; the two truncated workloads
differ by exactly their abandoned subtrees.

## Tests and safety

Five focused tests (D261): the variant enums equal the Java sealed permits and
the ported enums keep their constants; removing a wire treatment or adding an
untreated variant fails compilation in every mode; paired ownership controls
in every mode accept retiring builders, nodes and the token list once the
parser is gone, and reject freeing a frozen list a node holds, using a freed
lex result, freeing it while the parser borrows it, and freeing a token read
from a snapshot; native output equals the in-process Java frontend from classes
and archive with the exact census residue; and a lex-and-parse run under every
allocation limit from 0 to 1,895 fails with a caught `OutOfMemoryError`, while
1,896 completes. The three M1 value-helper tests rerun for the extended `Lists`.

The pilot compiles and links under `--unfreed=warn` with no findings, so its
missing-free classification is empty; M2.3 records it with the other pilots.

## Reproduce

[run-evidence.sh](frontend-evidence/run-evidence.sh) rebuilds a fresh tree,
runs every check above and the license audit. The tools are
`scripts/self-hosting/m2-frontend-differential.py`, `measure-m2-frontend.py`,
`bisect-m2-stack.py`, `check-m2-frontend-budgets.py` and
`generate-frontend-literals.py`, with the J0 counterpart
`scripts/self-hosting/FrontendReference.java`.
