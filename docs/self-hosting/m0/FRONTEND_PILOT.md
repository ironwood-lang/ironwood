<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Selected frontend construction pilot

M2.1 ports in-memory SourceFile.of/lineText, SourcePosition/SourceSpan,
Lexer(SourceFile).lex(), Parser(SourceFile,tokens).parse(), and the diagnostic
value/error gate. Inputs are already strictly decoded UTF-8 with a nonempty
logical basename; adapter acquisition failures are separately retained. Source
identity is borrowed by tokens/AST/diagnostics until structural comparison ends.
The pilot owns source line-list storage, lexer/parser builders, independent
child-list backing storage, copied values and output text. Retiring builders
must not reclaim shared source or nodes still referenced by output snapshots.
Future cleanup qualification must cover failure as well as successful return.

The original attributed call closure is in pilot-frontend-reviewed.json.gz:
376 explicit methods/field-initializer groups, 3,154 calls, 868 external calls
covering 68 exact declaration patterns. Each external site joins its reviewed
frontend, AST or diagnostic contract. Method references add their target just as
direct calls do. Field initialization is included per reached file. Generated
record accessors/constructors and pure enum/primitive leaf bodies remain named
in the ledger even when they contain no attributed calls. That absence is not
permission to omit them. This is a static source closure, not runtime coverage.

All six captured rows belong to three Parser Optional.orElseGet sites:
parseCompilationUnit borrows imports/declarations, parseTry borrows catches/body,
and parseIf borrows thenBranch/ifKeyword. They execute lazily and immediately,
only when the primary location is absent. Explicit presence branches preserve
that timing without retained supplier allocation. Constructor stream projections
and static method references have no captured locals but still preserve ordered
evaluation under their existing source contracts. Native immediate/retained
callback allocation and safe/unsafe cleanup evidence remain required in M2.2;
the effect observer supplies the retained case separately from this frontend.

The eight reached uninitialized Parser locals have explicit M1.3 rewrites:
kind/end in interface-member parsing, progress in directive parsing, base in
parseType, delimiter/arrow in switch labels, operator in binary parsing, and
elementType in array creation. Initialize references to null, booleans to false
and the enum to its existing guarded default before the original assignments;
keep the branch/loop assignments and guard every later read exactly as the
source does. Do not turn a default initializer into a new accepted parse path.
The seven reached type patterns are ordinary instanceof bindings already
supported, not type-pattern switch arms. The single private varargs declaration
is contiguousKinds, whose two/three-token callers need fixed arity. Record
declarations and nullable literals retain the finite data/constructor contracts.

## Independent model and dispatch boundary

frontend-model-schema.json is a checked finite declaration/field schema. It is
independent of the corpus and ReferenceCapture's reflective wire dispatch.
ModelCapture discovers declarations from 103 pinned source files and their
compiled classes: 98 records, 10 enums, six sealed interfaces and five ordinary
classes, 119 declarations total. DocumentationComment is included even though
the selected Lexer entry point disables documentation retention: its construction
appears in the conservative call closure. Every reached production method owner,
including generated leaves, must have an independent model treatment. Each
record lists ordered fields with complete
generic types; each enum lists all constants; each sealed interface lists its
permitted variants; each ordinary class lists its declared storage. These lists
come from model declarations, not serializer arms or observed output.

The selected port includes all ordinary AST data and constructors, all ten private
parser records, token/result/source/diagnostic values, and lexer/parser/source
construction state. Six declarations are explicitly later-only: DeclaredType,
DeclaredTypes, PatternFlow and its three nested result records. Those remain
B1/B7 M3.1 before S3. TypeName displayName/displayReference, splitting and the
locale-dependent original private renderer are also later M3.1 helpers; they
are not reached by this construction closure. Reached TypeName constructors
still require exact count wrapping, literal dot counting and validation order.
SourceFile.read and DiagnosticFormatter remain B3/B7 M3.3 before S4. This
boundary does not advertise a reduced public API or declare whole modules portable.

M1.3 must declare each concrete value's explicit construction and field traversal
in native code. SourceSpan/Position and record fields retain their reviewed value
semantics, SourceFile retains identity, and list membership remains ordered.
For each rewritten consumer, compare its explicit treatments against the same
finite model. Mutable class state is never silently sent through a generic
structural fallback. Native record hashing/equality is required where lookup or
convergence observes it, rather than equating objects by rendered output.

The [model qualification](model-probe/qualification.json) preserves two matching
fresh original-J0 discoveries and raw commands/logs. Raw TSV stdout is gzip
retained with byte counts and SHA-256, preserving empty final fields exactly
without introducing trailing whitespace in tracked text. The verifier rejects an
added untreated variant, a removed treatment, and omitted record fields. The
same model must gate native handlers before M2 equivalence; this Java check does
not establish native dispatch or exercise every constructor. The expanded
31-source corpus remains the chosen executable differential corpus, with its
positive/malformed tokens/spans/AST/errors. Its 57 observed record kinds do not
replace these independent model declarations.

Required M1 slices are B1 independent ordered shallow list copies, private
empty/singleton factories and constructor collection copies; B7 record/enum
data, nullable escape presence, private stripIndent, primitive segment-count
storage, fixed-arity contiguousKinds and direct ordered/lazy loop rewrites.
Keyword lookup is private text-to-enum lookup only. No B2 sorting is reached by
the frontend. Existing primitive/text/Path operations remain at their exact
reviewed overloads. Stack, allocation and wall limits will be attached to the
selected volume/control/type workloads before native evaluation.
