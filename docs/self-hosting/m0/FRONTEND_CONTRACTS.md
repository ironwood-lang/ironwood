<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Reviewed frontend use-site contracts

Scope: original J0 `lexer`, `parser`, and `source` Java packages. The 60 exact
external member patterns below are resolved declarations, including inherited
members and instantiated argument types. Their complete source ranges/callers
are in the attributed inventory. This review does not classify callers of the
same member in other packages. AST constructors called by the parser have a
separate reached-closure review; package boundaries alone do not establish S1.
No library or language implementation is added by this record.

All native replacements borrow source text and existing tokens/AST elements.
The invocation owns builders, copied backing storage, result objects and newly
created text. Final AST/diagnostic outputs retain their borrowed source/elements
until output comparison ends. A copy owner may retire its own storage only after
all snapshots using it are retired; shallow container destruction must not free
borrowed elements. Failure must release partially built owned storage without
releasing borrowed elements. Ordinary invalid source produces source diagnostics
in encounter order, rather than leaking an internal bounds/null exception.

## Primitive text and source operations

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / fixture |
| --- | --- | --- |
| API0003 `Array.length` | `Parser.contiguousKinds` reads a non-null varargs array's primitive length. This is an allocation-free length read. The array allocation belongs to the Java call expression, not to `length`. Rewrite the private 2/3-token calls using fixed arity or caller-owned arrays; compare adjacent UTF-16 end/start offsets as well as token kinds. | B7, M1.3, parser / adjacent and separated shifts, nested generic closing tokens |
| API0033 `Character.digit(char,int)`; API0034 `isDigit(char)`; API0039 `isWhitespace(char)`; API0042 `toString(char)` | Exact char overloads already exist in `ironwood.lang.Character`. Lexer radix checks require `-1` for non-digits and the selected radix, Unicode digit continuation and Java-21 whitespace. Identifier start stays ASCII. `toString` creates owned one-code-unit text for diagnostics, including isolated surrogates; no code-point overload is substituted. | B7, M1.3, lexer / Unicode continuation, malformed numeric and escape fixtures |
| API0101 `Math.max(int,int)` | Existing primitive int overload. Lexer/parser clamp source offsets/lookbehind; preserve int evaluation and result, with no boxing/storage. | B7, M1.3, source spans / empty and malformed first-token fixtures |
| API0130 `String.charAt(int)`; API0152 `length()`; API0148 `isEmpty()` | Existing methods use UTF-16 units. Callers guard EOF/lookahead and retain original source offsets; supplementary characters occupy two units. Malformed source recovery must preserve those guards. No UTF-8 byte indexing or code-point indexing. Pure reads retain nothing and allocate no helper. | B7, M1.3, lexer/source / supplementary text, CRLF, EOF recovery |
| API0136 `String.equals(Object)` | Existing content equality, false on null/non-String. Keyword/path-name comparisons must use text content rather than identity. It does not allocate or retain the compared object. | B7, M1.3, lexer/parser / equal separately created source-name and keyword text |
| API0143 `String.indexOf(int)`; API0144 `indexOf(int,int)`; API0164 `startsWith(String,int)` | Existing exact signatures. Lexer searches punctuation/newline and tests delimiters from bounded source positions. Preserve not-found `-1`, from-index clamping, prefix null failure and offset semantics. No regex or allocation. | B7, M1.3, lexer / raw/cooked delimiters, unterminated comments/text blocks |
| API0169 `String.substring(int)`; API0170 `substring(int,int)` | Existing signatures, half-open UTF-16 bounds and caller-owned text. Token/source-line results remain stable after builder mutation. Invalid caller bounds throw; lexer/parser guards must keep bad source on the diagnostic path. | B7, M1.3, source/lexer / empty final line, CR/LF/CRLF, malformed source |
| API0157 `String.replace(char,char)`; API0158 `replace(CharSequence,CharSequence)` | Existing signatures. `normalizeLineTerminators` first replaces CRLF literally with LF, then remaining CR with LF. Preserve evaluation order and literal replacement, including empty target behavior if admitted elsewhere. These frontend arguments are non-null fixed String literals; no regex, arbitrary CharSequence or locale behavior is needed here. Each result's ownership follows the String API, with source/result identity permitted only where that API returns it. | B7, M1.3, lexer / mixed CRLF/CR/LF text blocks |
| API0166 `String.stripIndent()` | Absent. Private lexer normalization must preserve the existing String contract on normalized text: minimum incidental indentation across nonblank lines and the final line, trailing whitespace removal, blank-line handling, final-newline behavior and UTF-16 whitespace. Decode cooked escapes after this step; raw blocks keep escapes. Do not replace it with `trim`, strip every leading space, or add a public Formatter/text facade. Owner allocates the result and retires intermediate normalization text. | B7, M1.3, lexer / cooked/raw indentation, blank final line, tabs, `\\s` and line continuation |
| API0176 `String.valueOf(char)` | Existing exact primitive overload. Character tokens carry one UTF-16 code unit as text; preserve zero and surrogate units. The token retains the owned text result. | B7, M1.3, lexer / NUL, surrogate and malformed character literals |
| API0178 `StringBuilder()`; API0179 `StringBuilder(String)`; API0180 `append(char)`; API0185 `append(String)`; API0190 `toString()` | Existing exact members. Builders mutate in encounter order, own mutable storage and must produce independent text snapshots. String constructor/append borrow inputs; append(String) handles null as its documented text contract, although source frontend arguments are present. Retire builder storage after token/name text is frozen, including failure. Do not free returned String with the builder. | B7, M1.3, lexer/parser / escapes, qualified names, repeated result after builder mutation |
| API0184 `StringBuilder.append(Object)` | Lexer `scanString` and `decodeTextBlockEscapes` select this Java overload because `decodeSimpleEscape` returns boxed Character. Both guard null before append. Native port uses explicit presence plus primitive char, then `append(char)`. Preserve unsupported-escape diagnostic and fallback escaped-character append. Character-literal unboxing is guarded too. No boxed Character library/ownership facade is required. | B7, M1.3, lexer / supported and unsupported String/char/text-block escapes |
| API0073 `IllegalArgumentException(String)`; API0076 `IllegalStateException(String)` | Existing exception classes/signatures. These represent internal constructor/cursor invariant violations, not ordinary parse failure. Message Strings are retained by exception objects. Keep validation and failure type; do not use these to bypass source diagnostics. Record-class rewrites must preserve constructor checks. | B7, M1.3, source/parser / invalid span/cursor and AST constructor contract checks |

## Source acquisition boundary

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / fixture |
| --- | --- | --- |
| API0250 `StandardCharsets.UTF_8`; API0275 `Files.readString(Path,Charset)` | `SourceFile.read` admits only explicit UTF-8, so use existing strict-UTF-8 `Files.readString(Path)` after package spelling changes. The Charset-taking overload does not exist and must not be advertised as supported. Preserve malformed-byte IO failure, path/null failures and owned complete text result. There is no retained stream handle. In-memory S1 uses already acquired text; source acquisition is exercised separately, before the native driver. | B3/B7, M3.3 before S4 source-only driver, `SourceFile.read` / invalid UTF-8, missing file, empty file |
| API0288 `Path.of(String,String...)` | Only `Path.of(displayPath)` is admitted in `SourceFile.of`. Existing single-String Path factory supplies this use without varargs. Preserve invalid path and null behavior; do not imply native support for Java's varargs overload. The SourceFile owns its new display Path. | B7, M1.3, source / ordinary and package-info logical names |
| API0287 `Path.normalize()`; API0299 `toAbsolutePath()`; API0284 `getFileName()`; API0302 `toString()` | Existing Path methods. `read` captures absolute lexical normalization, without `toRealPath`/symlink resolution. Parser uses basename exactly to detect `package-info.iron`. A rooted/no-basename path is not a valid selected in-memory source identity; acquisition failures must remain classified instead of dereferencing an absent filename. Retain the SourceFile's Path as long as source identities/spans are observed, and retire temporary Paths according to existing alias-return ownership. | B3/B7, M1.3 for logical name and M3.3 acquisition, source/parser / package-info, dot segments, root/no-basename error |

## Independent lists, factories and presence

| Exact discovery IDs / declarations | Reviewed admitted behavior and selected treatment | Owner, phase, first consumer / fixture |
| --- | --- | --- |
| API0331 `ArrayList()`; API0334 `ArrayList(Collection<? extends E>)` | Default constructor exists; collection constructor is absent. Parser copies constructor bodies, resource lists and first type arguments before mutation. M1 needs independent shallow ordered storage, with element identity retained, permitting Java constructor null elements where caller data permits them. A live wrapper or reused iterator is not a copy. A caller owns new storage and must retire it without releasing AST elements. | B1, M1.1, parser / constructor invocation extraction, resource copy, qualified generic arguments |
| API0441 `List.copyOf(Collection<? extends E>)` | Absent. LexResult, ParseResult, SourceFile lines and reached AST constructors require independent stable shallow child-list snapshots, encounter order, null-source/element rejection and structural sequence equality where record values compare them. Reuse is allowed only for already immutable independent storage. `Collections.unmodifiableList` alone is a live wrapper and cannot satisfy this. | B1, M1.1, lexer/parser/reached AST / builder mutation after snapshot and null rejection |
| API0442 `List.of()`; API0443 `List.of(E)` | Absent. Private empty/singleton helpers preserve immutable sequence semantics and reject a null singleton. `List.of(typeArguments.size())` boxes int in Java; native segment counts use primitive storage and value equality, not reference boxing. No unspecified hash encounter order applies to lists. | B1/B7, M1.1/M1.3, parser/TypeName / empty/singleton children and segment validation |
| API0454 `List.add(E)`; API0457 `addAll(Collection<? extends E>)` | `ironwood.ds.ArrayList.add` returns void, while Java List.add returns boolean; frontend discards it. Preserve append order. `addAll` is absent: explicit index loop over independently retained input; preserve evaluation once and null-source failure. Compiler-owned elements are borrowed, no per-element allocations. All current frontend addAll operands are separate parse-result/segment lists rather than self-addition. | B1, M1.1, parser / ordered multi-element child lists, separate source and destination |
| API0462 `List.get(int)`; API0475 `size()`; API0467 `isEmpty()` | Existing ArrayList operations after type/package rewrite. Bounds checks retain Java failure behavior for internal contract errors. Valid parser lookahead never allocates or changes source ordering. Native generic primitive segment-count lists must retain int value semantics. | B1/B7, M1.1/M1.3, parser/source / EOF lookahead, empty and multi-element lists |
| API0463 `List.getFirst()`; API0464 `getLast()` | Absent. Every selected parser access has a preceding nonempty fact, successful parse-result fact, or explicit first/last-token invariant. Rewrite to indexed get(0)/get(size-1) under that fact. A general helper, if exposed to empty input, must retain NoSuchElementException rather than silently choose IndexOutOfBoundsException. Lexer always emits EOF. | B7, M1.3, parser / empty unit, failed package declaration, malformed arguments, final token |
| API0471 `List.removeFirst()` | Existing ArrayList.removeFirst supplies both constructor-body extraction sites, guarded nonempty and invocation type. It shifts children once; this is not a FIFO worklist. It returns a borrowed element and removes only list membership. Preserve the original parsed Block snapshot before mutating the independent body list. | B1, M1.1, parser / leading this/super constructor invocation and ordinary first statement |
| API0481 `Map.entry(K,V)`; API0487 `Map.ofEntries(Entry...[])`; API0499 `Map.getOrDefault(Object,V)` | Lexer KEYWORDS contains forty-nine fixed non-null distinct text keys and enum values. Its only read is `getOrDefault(lexeme,IDENTIFIER)`; no traversal escapes. String content equality/hash, not identity, determines lookup. Prefer a private switch/static lookup over implementing Map factories, entries and varargs for S1. Preserve identifier fallback and all keyword spellings; no per-token table/entry allocation. General Map.getOrDefault would distinguish absent from present-null, but this table cannot contain null values. | B7, M1.3, lexer / all keywords plus equal separately built identifiers, colliding text keys |
| API0522 `Optional.empty()`; API0523 `of(T)`; API0524 `ofNullable(T)`; API0532 `isEmpty()`; API0535 `orElse(T)`; API0537 `orElseThrow()` | These exact members exist. Keep present non-null versus empty, of(null) rejection, nullable parser failures, eager evaluation of orElse's argument and empty orElseThrow's NoSuchElementException. Existing native Optional allocates even for empty; M1 may choose private presence fields for hot parser results to avoid wrapper allocation while preserving record equality/hash. AST output retains present elements; presence wrappers borrow them. Retire wrapper/state without freeing children. | B7, M1.3, parser/reached AST / successful/failed optional children, empty throw, value equality |
| API0526 `Optional.map(Function)`; API0536 `orElseGet(Supplier)` | Absent. Parser uses immediate span projection and a lazy fallback, never stores these callbacks. Replace with explicit presence branches: evaluate projection exactly once only on presence, evaluate fallback exactly once only on absence. Java map converts a null projection to empty and requires a non-null mapper even for empty; current accessors return spans. The fallback captures imports/declarations/catches/body/thenBranch only within this call. Preserve constructor/diagnostic evaluation order and avoid callback/wrapper allocations. | B7, M1.3, parser / package/import span, finally/else span, absent fallback side effects |
| API0365 `Collection.stream()`; API0670 `Stream.findFirst()` | Both selected uses are `destructors.stream().findFirst()` on a source-ordered list. Replace with an empty/present first-element result; no general stream or deque is required. Preserve the first source destructor and null rejection of findFirst (parser produces non-null declarations). The result borrows its AST element. | B7, M1.3, parser / absent/single/multiple destructors and source-order diagnostics |

## Reached representation obligations

Parser-created AST records, `Diagnostic`/notes and source-position/span records
must become explicitly validated value classes or primitive/presence structures.
Keep field-specific value equality/hash and identity references: SourceFile is an
identity object; SourcePosition/SourceSpan are values; lists compare ordered
contents; optional values compare their elements. Equal-looking SourceFiles
must not collapse into one source identity. All record constructors' defensive
copies and checks belong to this boundary, including TypeName's null-list defaults,
nonnegative segment counts, exact sum/count checks, wildcard/array validation and
qualified-name segmentation. A parser subset must state its excluded AST variants
and fail variant coverage when a reached variant is omitted.

`TypeName` reaches primitive segment counts, dot counting, segment rendering and
qualifier slicing. Use private literal-dot scanning, not regex, preserving the
existing segment-count validation and first/last-segment order. Its `List<Integer>`
is a value list; use primitive int storage. A convenience that copies BitSet via
existing `or` is a different M2 requirement, not a frontend dependency.

There is no ArrayDeque or field-retained callback in these three frontend
packages. Immediate Optional projections and list-stream selection are reviewed
above. Semantic snapshot/effect work has both scope stacks and a FIFO CFG
worklist and remains separately inventoried. The lack of a frontend deque does
not authorize replacing that CFG FIFO with repeated shifting removeFirst.

This table specifies required fixtures; it does not claim they have all passed.
The retained BranchJoin/SlotOrder pair covers the original branch-state safety
outcomes. M0.3 must add the text, syntax, value and scale corpus before S0 exits.
