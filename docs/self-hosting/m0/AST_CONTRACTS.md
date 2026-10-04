<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Reached AST contracts

Scope: all 91 frozen production files in `ironwood.compiler.ast`, including
helper methods and convenience constructors. This is a source review of the
original J0, not a native implementation. Every external member pattern in this
package is joined to its actual attributed uses by `review-ast.py`. A pattern
shared with the frontend is reviewed here for these different callers.

The retained [effective JVM settings](jvm-effective.txt.gz) record
`user.language=en` and `user.country=US` for the original profile. The locale
probe deliberately changes and restores the JVM default to retain the Turkish
rendering difference. An explicit ASCII primitive-spelling convention is a
selected future treatment, requiring a decision before M1 implementation.

## Exact dependencies

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / required fixture |
| --- | --- | --- |
| API0003 `Array.length`; API0101 `Math.max(int,int)` | TypeName rendering reads the literal-dot split array length and clamps its count offset at zero. Preserve primitive int evaluation, split encounter order and offset calculation. These operations borrow storage and retain nothing. | B7, M1.3, TypeName display / leading, interior and trailing dot segments |
| API0031 `Character.MAX_VALUE`; API0032 `MIN_VALUE` | CharacterLiteralExpression validates the inclusive UTF-16 code-unit range 0..65535, including surrogate units. Preserve IllegalArgumentException outside this range; do not reinterpret it as a Unicode scalar or byte. Use primitive bounds, no Character object. | B7, M1.3, character constructor / -1, 0, surrogate, 65535, 65536 |
| API0061 `Enum.name()`; API0172 `String.toLowerCase()` | Only TypeName's primitive/void enum spellings reach these operations. Original default-locale lowercasing is locale-sensitive (INT differs in Turkish). The pinned qualification uses English; record this limitation. Select explicit ASCII language spellings for the native private renderer, with a documented convention before implementation. Do not claim a general equivalent no-argument lowercase API. Enum identity and stable names remain required by the wire format; temporary name/lowercase text must be retired. | B7, M1.3, TypeName primitive display / every primitive and void; English/Turkish baseline distinction |
| API0073 `IllegalArgumentException(String)` | Constructor checks are ordinary value contract failures, retaining the message in the exception. Preserve validation order, type and message, including TypeName's sum check before its negative-count check and TypeReference's combined check. Bad parsed source must still use parser diagnostics. | B7, M1.3, AST construction / each rejected invariant and paired valid value |
| API0083 `Integer.intValue()` reference; API0672 `Stream.mapToInt(ToIntFunction)`; API0656 `IntStream.sum()` | Segment-count lists reject null through copyOf, so unboxing is safe after the copy. Sum has Java int wrapping, not checked arithmetic or long accumulation. Use ordered primitive int storage and value equality; no boxed Integer facade. TypeName and TypeReference reject mismatched totals. | B1/B7, M1.1/M1.3, type constructors / zero, negative, mismatch, wrapping total |
| API0131 `String.chars()`; API0651 `IntStream.filter(IntPredicate)`; API0650 `IntStream.count()` | Only dot counting: inspect UTF-16 units, count literal '.', cast long count to int then add one. Replace with a private loop preserving this result and null failure. Supplementary text must not change dot positions/counts. Each segment count occupies primitive storage. No general stream object is needed. | B7, M1.3, qualified types / empty, supplementary, multiple and trailing dots |
| API0135 `String.endsWith(String)`; API0150 `lastIndexOf(int)`; API0169 `substring(int)`; API0170 `substring(int,int)` | Existing exact members. Explicit-member qualifier uses the literal suffix "." plus member text; import splitting and qualifier extraction use the final dot and half-open UTF-16 bounds. Preserve empty/not-found paths and String content behavior. Substrings become retained result text, distinct from temporary concatenation storage. | B7, M1.3, imports/TypeName / wildcard/static import, absent qualifier and nested generic qualifier |
| API0136 `String.equals(Object)` method; API0137 same member reference | PatternFlow compares label/name content, false for null or a different type. The bound label::equals callback is immediate, non-retained and only evaluated for a present label. Preserve shadowing by an equal separately created label, rather than reference identity. | B7, M1.3, labeled flow / same text distinct objects, nested shadowing, absent label |
| API0147 `String.isBlank()`; API0148 `isEmpty()` | Existing members with UTF-16 whitespace semantics. Blank type/reference binding/parameter names fail their specific checks; empty package prefixes omit the dot. Nullable name validation differs: TypeName rejects null as IllegalArgumentException, TypeReference reaches NullPointerException, imports do not prevalidate. Do not homogenize constructors' checks. | B7, M1.3, AST names / blank, null and empty package |
| API0161 `String.split(String)` | Only constant regex "\\." is admitted in TypeName.displayReference. Use a private literal-dot scanner, preserving Java zero-limit trailing-empty suppression, leading/interior empty segments and the renderer's count-offset rule. The constructor counts all dots even when rendering drops trailing empty segments. Arbitrary constructor names are not restricted to parser-produced identifiers, so preserve these admitted cases or enforce a narrower private boundary before using it. No public regex subsystem is needed. Owner retires segment storage after freezing display text. | B7, M1.3, TypeName display / A., .A, A..B, dot-only and generic-segment alignment |
| API0178 `StringBuilder()`; API0180 `append(char)`; API0185 `append(String)`; API0190 `toString()` | Existing members. Build display text in segment/type-argument order, preserving comma/space and wildcard/array recursion. The returned text snapshot survives builder mutation/retirement. Builder borrows appended Strings; recursively produced display text needs explicit temporary cleanup after append. | B7, M1.3, nested display / nested generics, arrays, bounded/unbounded wildcard |
| API0331 `ArrayList()`; API0334 `ArrayList(Collection<? extends E>)` | Default constructor exists; collection constructor is absent. DeclaredTypes copies enclosing paths; PatternFlow copies then appends conflict/binding lists. These need independent shallow mutable storage preserving order and element identity. Java's constructor permits null elements, unlike the subsequent List.copyOf. Retire mutable builders after immutable result creation, including failure. | B1, M1.1, declared traversal/pattern flow / mutation after copy, nested paths and ordered conflicts |
| API0335 `ArrayList.add(E)`; API0454 `List.add(E)`; API0457 `addAll(Collection<? extends E>)` | Both add declarations return boolean in Java, discarded at all AST sites. Native add returns void. Append in encounter order; addAll needs an explicit loop and evaluates its non-null separate source once. PatternFlow union concatenates, including repeated entries; it is not mathematical set union. Builders own storage and borrow AST elements. | B1, M1.1, primitive counts/PatternFlow / repeated bindings and conflict order |
| API0441 `List.copyOf(Collection<? extends E>)` | Absent. Every compact constructor copy creates independent immutable ordered membership, rejects null collection/elements and retains shared child identity. Sequence equality/hash are structural. A live unmodifiable wrapper is insufficient. Release list storage without freeing children shared with other AST/semantic references. Null-to-empty defaults apply only where explicitly written; they do not apply to every child list. | B1, M1.1, all compact constructors / post-construction mutation, null source/element, equal separate lists |
| API0442 `List.of()`; API0443 `List.of(E)` | Absent. Private immutable empty/singleton values preserve sequence equality and reject a null singleton. CatchClause's singleton constructor and PatternFlow's one binding use borrowed AST values. Primitive count defaults need no boxing. Shared immutable empty storage must have an explicit lifetime, rather than being freed per AST. | B1/B7, M1.1/M1.3, constructor defaults / empty children and null single catch type |
| API0462 `List.get(int)`; API0467 `isEmpty()`; API0475 `size()` | Existing ArrayList members after representation rewrite. Indexed access returns a borrowed child or primitive count. Preserve half-open bounds and size, without helper allocation on reads. Constructor count validation establishes renderer indexing for ordinary qualified names; retain exceptional edge-name tests separately. | B1/B7, M1.1/M1.3, TypeName and declared traversal / first/last/multi-segment counts |
| API0463 `List.getFirst()`; API0464 `getLast()` | Absent. DeclaredType's enclosing path, TypeName/TypeReference counts and PatternFlow's last block statement are guarded nonempty. CompilationUnit.declaration is unguarded and admits an empty unit: keep NoSuchElementException, even if this convenience is omitted from a selected pilot. A bare indexed replacement would change that failure. | B7, M1.3, unit/helper methods / empty package-info unit and empty block/path |
| API0478 `List.subList(int,int)` | Absent. TypeName/TypeReference inspect a prefix or pass it immediately to a constructor that copies it. Use indexed ranges or independent prefix storage; preserve bounds and order. No live view escapes these AST helpers. The qualifier retains copied child membership, not a borrowed view into a retired temporary list. | B1/B7, M1.1/M1.3, generic qualifier / zero/nonzero final counts and mutation independence |
| API0428 `LinkedHashMap()`; API0497 `Map.get(Object)`; API0505 `putIfAbsent(K,V)` | PatternFlow.overlap has one private names map. Non-null String keys use content equality; non-null Binding values make get-null an absence test. Traverse the first list in order and keep its first binding for each name; traverse the second list in order to emit conflicts. No map iteration/view/return escapes. Native private lookup may use get plus conditional put, without a general null-value putIfAbsent contract. Retire map storage after copying conflicts; it borrows bindings. | B1/B7, M1.1/M1.3, overlap / colliding Aa/BB names, repeated first name and reversed second list |
| API0522 `Optional.empty()`; API0523 `of(T)`; API0532 `isEmpty()`; API0533 `isPresent()`; API0535 `orElse(T)`; API0537 `orElseThrow()` | Existing exact members. Normalize null wrappers only where compact constructors do so. of rejects null; empty throw uses NoSuchElementException; orElse evaluates its argument eagerly (here constants). Presence wrappers borrow AST objects and use contained value equality/hash; private presence fields may avoid allocations while retaining that contract. | B7, M1.3, optional fields / null defaults, present value and absent guarded access |
| API0526 `Optional.map(Function)`; API0529 `filter(Predicate)` | Absent. Immediate package-name/boolean projections and label equality become explicit presence branches. Map turns a null projection into empty; filter preserves the original contained value when true. Current callbacks return non-null package names or boxed booleans and borrow statement/label context; none is stored. Use primitive boolean results, not Boolean or Function facades. Preserve short-circuiting and callback evaluation order. | B7, M1.3, flow/package helpers / absent labels, true/false projections, shadowed break |
| API0092 `Iterable.forEach(Consumer)`; API0365 `Collection.stream()`; API0666 `Stream.anyMatch(Predicate)` | Callbacks execute synchronously over ordered AST lists; overlap insertion and switch-rule conflicts have mutations, while completion/break/count predicates are pure recursive tests. Rewrite to loops preserving source order and early termination. No callback survives the call or owns its captured context. Recursion depth remains a measured M0.3 obligation. | B7, M1.3, PatternFlow/type validation / side-effect order and deep control flow |
| API0669 `Stream.filter(Predicate)`; API0660 `Stream.map(Function)`; API0681 `toList()` | ClassDeclaration's convenience constructor selects fields by static flag in original order, casts the same FieldDeclaration objects to initialization interfaces, and freezes list membership. It does not construct new initializer nodes. Stream.toList permits null generally, but these callbacks cannot return null after dereferencing valid fields; null fields fail before the primary constructor. Preserve both projections and child identity. | B1/B7, M1.1/M1.3, class construction / interleaved static/instance fields and null field failure |
| API0659 `Stream.flatMap(Function)` | Only ordered switch groups to their ordered statement lists, followed by short-circuit break-to-label search. Use nested loops, preserving group/statement order and label shadowing. Child lists are borrowed; no combined temporary list or stream is required. | B7, M1.3, switch flow / break in later group and nested same-label scope |
| API0646 `Collectors.joining(CharSequence)`; API0658 `Stream.collect(Collector)` | DeclaredType.enclosingBinaryName joins enclosing declaration names with fixed "$" in lexical outer-to-inner order, adding the package prefix. Use a private builder loop and retire temporary text. No sorting or general collector API; a top-level type returns null before joining. | B7, M1.3, declared identities / top-level, package and multiple nesting levels |

## Representation and traversal boundaries

All record fields retain their existing Java value semantics. SourceSpan contains
only value positions; source identity is carried separately by CompilationUnit
and later diagnostic/semantic records. SourceFile remains an identity reference:
separately created equal-looking SourceFiles must not compare equal. Child records and immutable child sequences
compare structurally, while PatternFlow's explicit `earlier.expression() !=
binding.expression()` requires expression identity even for equal-looking nodes.
Native rewrites must distinguish those operations. Shared children, spans and
source objects are borrowed; copying membership does not transfer child ownership.

Required-list fields are copied in Block, compilation/type/member declarations,
initializations, calls/new/constructor invocations, loops, catch/try, switches,
arrays, DeclaredType and PatternFlow.Result. Only the written null defaults become
empty (type arguments/counts, selected type-parameter/thrown lists and Optional
fields). Catch types and switch labels are nonempty. SwitchExpression chooses
groups versus rules according to arrowRules; an empty selected list is admitted.
Break/Continue require label and labelSpan presence to agree. ArrayCreation
requires exactly one of length/initializer. TypeParameter and TypePatternBinding
require nonblank names, with binding additionally requiring a non-null nameSpan.
TypeName preserves reference/array/wildcard discrimination, non-void array
elements, bounded/unbounded wildcard rules, nonnegative counts and exact
count/argument/name-segment relations. TypeReference has its own check order.

DeclaredTypes uses source-order pre-order: top-level declaration, then each
member subtree. Source names use '.' nesting, binary names use '$'.
Each returned enclosing path is an independent immutable snapshot. Class field
projection keeps source order within each static/instance category.

PatternFlow recursively analyzes left before right, concatenates conflicts in
the written overlap order, and preserves repeated bindings. It swaps true/false
facts for negation, chooses the written AND/OR facts, and traverses arrow rules
in order. Completion uses the final block statement, left-to-right OR, and
non-completing finally precedence. Break searches preserve current-loop stopping
rules and label shadowing. Conflict.span is always the later binding's nameSpan.

There are no hash-backed origins or propagated hash traversals in this package
in the attributed discovery. This does not prove downstream semantic consumers
of AST values are order-independent. `review-ast.py` rejects changed source
hashes, uncovered external patterns or a new AST hash dependency. The selected
AST variant inventory and recursion/resource measurements still belong to the
full M0.2/M0.3 gate; this review alone does not close them.

`AstContractProbe` invokes original constructors and helpers against the pinned
J0 JAR. Its 84 checks cover snapshot independence/null rejection, structural
child equality versus source/expression identity, UTF-16 char bounds, selected
constructor failures, wrapping primitive count totals, dot-split edges, nested
generic/wildcard rendering, locale differences, first-binding/collision/conflict
order, completion/label shadowing, field projection identity and declared
pre-order/binary names. Four fresh qualified processes match in
[ast-probe-expanded/qualification.json](ast-probe-expanded/qualification.json).
The earlier 74-check probe is retained separately under `ast-probe`; each stores
the exact probe source in gzip matching its recorded SHA-256. These checks do
not claim exhaustive constructor/variant coverage or resource qualification.
