# Ironwood vs Java

## Purpose

Ironwood aims to make a Java programmer feel at home while producing a native,
closed-world executable rather than JVM bytecode. This document gives a
feature-focused comparison of the final Java SE 26 language and the language
model implemented by Ironwood today. Object-oriented features remain the center
of the comparison, but the inventory also covers the rest of the Java language
surface closely enough to expose omitted syntax and semantics rather than
silently treating them as out of scope.

It is not a standard-library API comparison. Language constructs that mention
library-defined types, such as class literals or `assert`, remain in scope;
ordinary library breadth does not. Javadoc is included as an explicit tooling
exception because documentation-comment support is a source-development choice
that should not be left ambiguous.

It has two jobs:

1. show how far Ironwood has progressed toward familiar Java OOP; and
2. make deliberate differences visible when native compilation or explicit
   memory reclamation requires a different rule.

This is an approachable companion to the authoritative technical documents:
[`OBJECT_MODEL.md`](OBJECT_MODEL.md), [`GENERICS.md`](GENERICS.md),
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md), and [`MEMORY.md`](MEMORY.md).

The examples are intentionally small and usually omit packages and a `main`
method. Java files use `.java`; the corresponding Ironwood files use `.iron`.
Unless a difference is noted, familiar Java syntax has the same source meaning
in Ironwood.

## Audit baseline

This inventory was audited against the final
[Java Language Specification, Java SE 26 Edition](https://docs.oracle.com/javase/specs/jls/se26/html/index.html),
dated 2026-02-17. The audit walks the language families in JLS Chapters 3–18:
lexical structure; types and conversions; names, packages, imports, and modules;
classes and interfaces; arrays; exceptions; execution and binary compatibility;
statements and expressions; definite assignment; the memory model; and type
inference. It also checks the separate
[Javadoc documentation-comment specification](https://docs.oracle.com/en/java/javase/26/docs/specs/javadoc/doc-comment-spec.html).

Java SE 26 has one preview language family: primitive types in patterns,
`instanceof`, and `switch`. It is isolated as Feature 108, so the preview does
not blur the separate decisions for stable `instanceof` type patterns, modern
non-pattern `switch`, pattern `switch`, or record/unnamed patterns. A preview
does not become an Ironwood commitment merely because the audit records it.
String templates were withdrawn after Java SE 22 and are neither a final nor
preview Java SE 26 feature, so their absence is not an Ironwood language gap.

| Java SE 26 specification area | Comparison coverage |
| --- | --- |
| JLS 3: lexical structure | Features 75, 78–84, 103–104 |
| JLS 4–6: types, conversions, names, scope, and access | Features 2, 5, 9–11, 17, 20–22, 36, 39–55, 58, 66, 75–76, 84, 88, 99, 106–108 |
| JLS 7: packages, imports, and modules | Features 5, 37, 85–87 |
| JLS 8–9: classes, interfaces, enums, records, and annotations | Features 1–38, 40–52, 56, 58–64, 69–70, 74, 94–97, 101, 107 |
| JLS 10: arrays | Features 53–55, 89, 93 |
| JLS 11–12: exceptions and execution | Features 10, 57–58, 67–73, 92–94, 96, 100, 105 |
| JLS 13: binary compatibility | Feature 98 |
| JLS 14–16: statements, expressions, patterns, and definite assignment | Features 7–9, 15–21, 31–36, 43–49, 53–60, 64–66, 72, 76, 84, 88–94, 99–100, 102, 106–108 |
| JLS 17: threads and the memory model | Features 70, 101 |
| JLS 18: type inference | Features 41–48, 51 |

JLS Chapters 1–2 and 19 describe specification organization and grammar
presentation rather than additional end-user language families. The table is a
coverage map, not a claim that Ironwood implements every section assigned to a
row; each row's icon and detailed scope state the actual result.

## Status legend

- ✅ **Feels like Java:** implemented, tested, and working now with the familiar
  Java source form and ordinary semantics described by the scoped feature. The
  native backend and explicitly documented edge behavior may differ without
  requiring an Ironwood-specific rewrite for the feature's central use.
- ❌ **Not supported:** Ironwood currently chooses not to support the named Java
  feature. A future explicit project decision may revisit that choice, but a
  manual rewrite using other language features does not change this status.
- ⏳ **Pending:** Ironwood support is planned or committed, but design or
  implementation work remains. The feature is not yet usable as described.
- 💡 **Ironwood way:** Ironwood does not support the exact Java mechanism, but
  an intentional, implemented, tested, and documented Ironwood-native design
  provides the corresponding capability in a different form. A possible or
  incidental workaround does not qualify.

An icon classifies the whole feature as scoped by its row and detailed section.
A separate row may isolate a deliberately unsupported part of an otherwise
Java-feeling feature. When a central source form or ownership contract instead
uses an official Ironwood-native design, the row is 💡 rather than ✅. Click any
feature number in the table to jump to its Java/Ironwood example and
explanation.

## Feature summary

| Compared feature                                                                   | Ironwood status                                                                                                                                              |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [1](#feature-1). Classes and object construction                                   | ✅ Java-shaped classes, `new`, fields, constructors, and methods.                                                                                             |
| [2](#feature-2). `Object` root, references, and `null`                             | ✅ Every reference type widens to `Object`; assignments copy references and references may be `null`.                                                         |
| [3](#feature-3). Instance fields, methods, and composition                         | ✅ Mutable state, behavior, object composition, and delegation use Java-shaped references and calls.                                                          |
| [4](#feature-4). Static fields and methods                                         | ✅ Static state and behavior work now; automatic class initialization is listed separately.                                                                   |
| [5](#feature-5). Encapsulation, access control, and package access                 | ✅ `public`, `protected`, package-private, and `private`, including Java's protected-receiver rule.                                                           |
| [6](#feature-6). Constructors and default constructors                             | ✅ Declared constructors, implicit default constructors, and superclass construction.                                                                         |
| [7](#feature-7). Constructor overloading and `this(...)` delegation                | ✅ Overloaded constructors and first-action delegation, with cycle detection.                                                                                 |
| [8](#feature-8). Instance initialization order                                     | ✅ Superclass initialization, field initializers, initializer blocks, and constructor bodies run in Java order.                                               |
| [9](#feature-9). `final` fields, locals, and parameters                            | ✅ Initialized and blank-final fields plus final local/parameter bindings and definite-assignment checks.                                                     |
| [10](#feature-10). Garbage collection                                              | 💡 Ironwood has no automatic collector; compiler-checked `free` runs deterministic class destruction, while failed construction uses compiler-generated rollback. |
| [11](#feature-11). Explicit safe `free`                                            | 💡 Ironwood proves one allocation unobservable, including cleanup on return, exception, `break`, `continue`, and `yield`, runs its destructor chain, then releases it. |
| [12](#feature-12). Single class inheritance                                        | ✅ One direct superclass, as in Java; multiple class inheritance is not allowed.                                                                              |
| [13](#feature-13). Abstract classes and methods                                    | ✅ Abstract stateful bases, abstract obligations, and concrete implementations.                                                                               |
| [14](#feature-14). Final classes and methods                                       | ✅ Final classes cannot be extended and final methods cannot be overridden.                                                                                   |
| [15](#feature-15). Method overriding and dynamic dispatch                          | ✅ Structural override checking with native virtual dispatch and closed-world devirtualization.                                                               |
| [16](#feature-16). Method overloading                                              | ✅ Candidate-based overload resolution for fixed-arity methods and constructors.                                                                              |
| [17](#feature-17). Covariant reference returns                                     | ✅ An override may return a narrower reference type.                                                                                                          |
| [18](#feature-18). `super(...)`, `super.field`, and `super.method(...)`            | ✅ Direct superclass construction and non-virtual member selection.                                                                                           |
| [19](#feature-19). Field hiding                                                    | ✅ Parent and child fields remain distinct; selection uses the receiver's compile-time type.                                                                  |
| [20](#feature-20). Class and interface polymorphism                                | ✅ Widening references and virtual/interface calls preserve runtime behavior.                                                                                 |
| [21](#feature-21). Class/interface casts and classic `instanceof`                  | ✅ Reifiable nominal casts and tests work for ordinary class and interface references.                                                                        |
| [22](#feature-22). Root `Object` methods                                           | ✅ `equals(Object)`, `hashCode()`, and `toString()` are ordinary overridable methods.                                                                         |
| [23](#feature-23). Implementing multiple interfaces                                | ✅ A class may implement multiple interfaces.                                                                                                                 |
| [24](#feature-24). Multiple interface inheritance                                  | ✅ An interface may extend multiple interfaces.                                                                                                               |
| [25](#feature-25). Interface constants                                             | ✅ Interface fields are `public static final` compile-time constants.                                                                                         |
| [26](#feature-26). Abstract, default, static, and private interface methods        | ✅ All current Java interface method kinds used by the object model are supported.                                                                            |
| [27](#feature-27). Default-method resolution and `Interface.super`                 | ✅ Class-wins, most-specific default, re-abstraction, conflict checks, and explicit default selection.                                                        |
| [28](#feature-28). Static nested classes                                           | ✅ No enclosing instance; normal nested access and generic boundaries.                                                                                        |
| [29](#feature-29). Member inner classes and `Outer.this`                           | ✅ Every inner instance retains its exact enclosing object.                                                                                                   |
| [30](#feature-30). Qualified inner-superclass construction                         | ✅ `outer.super(...)` supplies the enclosing object required by an inner superclass.                                                                          |
| [31](#feature-31). Local classes                                                   | ✅ Block-scoped named classes with constructors, inheritance, interfaces, and capture.                                                                        |
| [32](#feature-32). Anonymous classes                                               | ✅ Anonymous class extension and interface implementation with ordinary override rules.                                                                       |
| [33](#feature-33). Qualified generic anonymous construction                        | ✅ An arbitrary receiver selects the exact generic inner member being anonymously extended.                                                                   |
| [34](#feature-34). Anonymous enclosing operands and evaluation order               | ✅ Qualified receiver and arguments run once in familiar order with the exact lexical enclosing object; Feature 105 isolates allocation-failure timing.       |
| [35](#feature-35). Diamond with anonymous classes                                  | ✅ Supported with Java's restriction that new non-private instance methods must override inherited methods.                                                   |
| [36](#feature-36). Effectively-final local capture                                 | ✅ Local and anonymous classes capture final or effectively-final locals and parameters.                                                                      |
| [37](#feature-37). Same-nest private access                                        | ✅ Types under one top-level source type may access one another's private members.                                                                            |
| [38](#feature-38). Nested interfaces and member types in lexical classes           | ✅ Nested interfaces and types inside member, local, and anonymous contexts participate normally in OOP.                                                      |
| [39](#feature-39). Captured references and reclamation safety                      | 💡 Ironwood extends Java-shaped capture with safe-`free` analysis that treats hidden capture and enclosing references as real aliases.                        |
| [40](#feature-40). Generic classes and interfaces                                  | ✅ Invariant reference-type generics with exact source typing.                                                                                                |
| [41](#feature-41). Upper, intersection, dependent, and recursive bounds            | ✅ Java-shaped reference bounds, including F-bounds.                                                                                                          |
| [42](#feature-42). Generic methods and constructors                                | ✅ Explicit or inferred callable type arguments.                                                                                                              |
| [43](#feature-43). Type inference and diamond                                      | ✅ Argument, receiver, expected-type, nested-call, and constructor inference.                                                                                 |
| [44](#feature-44). Return-only generic method inference                            | ✅ A result type variable can be inferred entirely from the expected target even when no argument mentions it.                                                |
| [45](#feature-45). Wildcards and capture conversion                                | ✅ `?`, `? extends`, and `? super` with safe read/write views.                                                                                                |
| [46](#feature-46). Exact generic owner/member types                                | ✅ Inner/member types retain both owner and member substitutions; static nested types start a fresh boundary.                                                 |
| [47](#feature-47). Diamond on inner and anonymous-inner construction               | ✅ Diamond composes with explicit/implicit enclosing instances and generic constructors.                                                                      |
| [48](#feature-48). Reifiable generic tests and source-provable parameterized casts | ✅ `G<?>` tests and casts whose concrete type arguments are proved from the closed world work now.                                                            |
| [49](#feature-49). Unchecked generic casts and non-reifiable `instanceof`          | ❌ Ironwood rejects unchecked casts it cannot prove; its supported reifiable and source-provable casts are separate safe operations.                          |
| [50](#feature-50). Raw generic types                                               | ❌ Raw types and their unchecked conversions are deliberately unsupported.                                                                                    |
| [51](#feature-51). Primitive generic arguments through native specialization       | 💡 Unbounded parameters accept primitives through closed-world native value specialization with no boxing or hidden wrapper allocation.                        |
| [52](#feature-52). Automatic boxing and unboxing                                   | ❌ Ironwood performs no automatic boxing or unboxing; explicit wrapper code is not autoboxing support.                                                        |
| [53](#feature-53). One-dimensional invariant arrays                                | ✅ Primitive/reference arrays, checked indexing, `.length`, widening to `Object`, and explicit container `free` work now.                                     |
| [54](#feature-54). Java array covariance                                           | ❌ Arrays are invariant; Ironwood does not support Java's covariant array conversions.                                                                         |
| [55](#feature-55). Multidimensional arrays and exact array casts/tests             | 💡 Recursive array types and exact casts/tests retain Java-shaped syntax, while explicit separately owned children replace Java's rectangular allocator.     |
| [56](#feature-56). Varargs                                                         | ❌ Varargs declarations and expanded calls are unsupported; passing an ordinary explicit array is not varargs.                                                |
| [57](#feature-57). Exceptions as `Throwable` objects                               | ✅ Java-shaped objects, causes, public source traces, root categories, `throw`, typed `catch`, and native unwinding.                                         |
| [58](#feature-58). Checked exceptions and `throws` declarations                    | ✅ Methods, constructors, interfaces, generic throws, overrides, call sites, and checked catches are compiler-enforced.                                       |
| [59](#feature-59). General annotations                                             | ❌ Annotation declarations, processing, and runtime reflection remain excluded; narrow compiler-owned directives are not annotations.                        |
| [60](#feature-60). Mandatory `@Override` directive                                 | 💡 Ironwood uses Java's exact marker spelling but requires it on every declared override or interface implementation, with an error in either direction.     |
| [61](#feature-61). Enums                                                           | ✅ Java-shaped declarations, a shared `Enum<E>` base, fresh `values()` arrays, immortal constants, and allocation-free traversal helpers.                       |
| [62](#feature-62). Records                                                         | ❌ Record declarations and their synthesized members are unsupported; writing an ordinary class is not record support.                                       |
| [63](#feature-63). Sealed classes and interfaces                                   | ❌ Ironwood has no source-level sealed hierarchy contract; closed-world compiler knowledge is not an equivalent language feature.                             |
| [64](#feature-64). Lambdas, closures, and method references                        | ❌ Lambda and method-reference syntax and semantics are unsupported; anonymous classes remain a separate supported feature.                                  |
| [65](#feature-65). Classic `switch`                                                | ✅ Integral and enum statements support constant labels, explicit fallthrough, `break`, and one optional `default`.                                           |
| [66](#feature-66). Reference type patterns for `instanceof`                        | ✅ Named reifiable reference patterns provide Java-shaped definite-match scope, evaluated-once aliases, and allocation-neutral typed lowering.                |
| [67](#feature-67). Runtime reflection                                              | ❌ Unrestricted reflection conflicts with Ironwood's chosen small, closed-world native runtime; compile-time generation may cover specific needs.             |
| [68](#feature-68). Runtime class loading and dynamic proxies                       | ❌ Types cannot arrive after native link, and Ironwood will not add a JVM-like loader or runtime code generator.                                              |
| [69](#feature-69). Static initializer blocks and automatic class initialization    | ✅ Named classes have source-ordered static blocks and runtime field initializers with deterministic one-time active-use initialization.                       |
| [70](#feature-70). Threads, object monitors, and `synchronized`                    | ❌ Excluded for the foreseeable future; maybe one day.                                                                                                        |
| [71](#feature-71). Java serialization, cloning, and finalization                   | ❌ Java serialization, `Object.clone()`/`Cloneable` machinery, and GC-triggered finalization are unsupported; copying is an ordinary type-owned API.            |
| [72](#feature-72). Try-with-resources                                              | 💡 Ordinary `try`/`finally` makes closing and safe `free` explicit, preserves the first failure, and retains later failures in occurrence order.              |
| [73](#feature-73). Uncaught exception stack traces                                 | ✅ Construction-time source traces, public printing/refresh, and automatic primary/secondary uncaught reports (D121).                        |
| [74](#feature-74). Enum constant-specific class bodies                             | ✅ Per-constant bodies use immortal compiler-owned final subtypes with ordinary fields, initialization, methods, member types, and dispatch.                  |
| [75](#feature-75). Double-quoted String literals and literal pooling               | ✅ Decoded-equal literals are immutable, immortal UTF-16 `String` singletons in the final linked program.                                                      |
| [76](#feature-76). String concatenation with `+` and `+=`                          | ✅ Java-shaped conversion, grouping, evaluation order, constant folding, and one ordinary runtime result allocation per dynamic chain.                         |
| [77](#feature-77). Runtime `String.intern()`                                       | ❌ Arbitrary runtime strings do not enter an unbounded immortal global pool; an explicit application-owned interner is the preferred model.                    |
| [78](#feature-78). Cooked and raw text blocks                                      | 💡 Java-shaped `"""` layout gains escape-free `r"""` blocks while retaining finite compile-time pooling and no interpolation.                               |
| [79](#feature-79). Javadoc documentation comments and generation (IronDocs)        | 💡 `irondoc` implements a Javadoc-style `/** ... */` subset with declaration association, common tags, selected-member links, and GitHub Markdown output. Full Javadoc tooling remains deferred. |
| [80](#feature-80). Ordinary line and block comments                                | ✅ `//` and non-nesting `/* ... */` comments are ignored lexically with Java-shaped boundaries.                                                               |
| [81](#feature-81). Unicode escapes and Unicode identifiers                         | ❌ Source is UTF-8, but identifiers are ASCII-only and Java's pre-tokenization `\u...` translation is deliberately absent.                                    |
| [82](#feature-82). Additional Java escape sequences                                | ❌ Octal escapes, `\s`, and text-block line continuation are unsupported; cooked literals use Ironwood's documented fixed escape set.                         |
| [83](#feature-83). Binary integer literals                                         | ✅ `0b`/`0B` literals support separators, `L`/`l`, Java-shaped width selection, and full-width two's-complement bit patterns.                                  |
| [84](#feature-84). Java-width primitives, conversions, and operators               | ✅ Java-width values, promotion, casts, operators, left-to-right evaluation, and defined integer/floating edge behavior work now.                              |
| [85](#feature-85). Packages, ordinary imports, and compilation units               | ✅ Named/unnamed packages, single-type and wildcard imports, qualified names, and Java-shaped public-type filename rules work now.                             |
| [86](#feature-86). Static imports                                                   | ✅ Single-member and on-demand imports resolve accessible static fields, methods, and member types with Java-shaped lookup and conflicts.                      |
| [87](#feature-87). Module declarations and module imports                          | ❌ Ironwood has no Java Platform Module System source declarations, readability graph, exports/opens directives, or `import module` form.                      |
| [88](#feature-88). Local inference and Java declarator conveniences                | ❌ `var`, unnamed `_` variables, optional local initializers, multiple field/local declarators, receiver parameters, and post-name array brackets are unsupported. |
| [89](#feature-89). Array initializer syntax                                        | ✅ Declaration and `new T[]` initializers infer exact lengths, convert and evaluate elements in order, and track every nested child allocation for safe `free`. |
| [90](#feature-90). Remaining statement forms and labeled transfers                 | ✅ `do`/`while`, enhanced `for`, empty and labeled statements, labeled transfers, and cleanup-preserving transfers through `finally` work now.                 |
| [91](#feature-91). Modern non-pattern `switch`                                     | ✅ Arrow rules, expressions, `yield`, comma labels, String/null selectors, and non-pattern exhaustiveness work now.                                            |
| [92](#feature-92). Multi-catch and precise rethrow                                 | ✅ Disjoint union catches, implicitly-final bindings, and precise rethrow from final or effectively-final catch parameters work now.                           |
| [93](#feature-93). Class literals and runtime `Class` objects                      | ❌ `T.class`, primitive/array class literals, and source-visible runtime class objects are unsupported.                                                       |
| [94](#feature-94). Flexible constructor bodies                                     | ❌ Ironwood retains the pre-Java-25 rule that explicit `this(...)` or `super(...)` construction must be the first constructor action.                          |
| [95](#feature-95). Local enum and interface declarations                           | ❌ Local normal classes work, but block-local enums and interfaces do not; local records remain covered by Feature 62.                                        |
| [96](#feature-96). Compact source files and expanded `main` forms                  | ❌ Declared classes accept classic public-static `void` or `int` String-array mains, but compact classes and instance or parameterless mains are unsupported.   |
| [97](#feature-97). Other Java special-purpose declaration modifiers                | ❌ `native`, `strictfp`, `synchronized`, and `transient` are not Ironwood modifiers; `volatile` is separated as Feature 101.                                  |
| [98](#feature-98). Java binary compatibility across library evolution              | 💡 `.ironclass` and `.ironjar` enable compile-time reuse, but final link reconstructs one closed world instead of promising Java `.class` binary compatibility. |
| [99](#feature-99). Intersection types in cast expressions                          | ❌ Intersection bounds work, but a cast cannot introduce a Java `A & B` intersection type; this exclusion is confirmed for now.                               |
| [100](#feature-100). Catchable ordinary implicit runtime safety failures           | ✅ Null use, invalid array operations, negative array lengths, and `throw null` raise catchable Java-shaped exceptions.                                       |
| [101](#feature-101). `volatile` fields and Java memory visibility                  | ❌ Source-level `volatile` is deliberately unsupported because Ironwood has no threads or shared-memory interoperability model in which it has useful semantics. |
| [102](#feature-102). `assert` statements                                           | ❌ Ironwood deliberately omits Java's runtime-enabled or disabled assertion statement; use explicit validation and failure control flow.                       |
| [103](#feature-103). Java-style octal integer literals                             | ❌ A leading zero remains decimal in Ironwood; Java's implicit octal notation is deliberately unsupported.                                                    |
| [104](#feature-104). Hexadecimal floating-point literals                           | ❌ Exact Java `0x...p...` floating literals are unsupported while their independent promotion decision remains open.                                          |
| [105](#feature-105). Catchable allocation failure and `OutOfMemoryError`           | 💡 Source-evaluated allocation exhaustion raises one immortal reusable error through bounded emergency delivery with ownership-safe timing.                  |
| [106](#feature-106). Reference type patterns in `switch`                           | ❌ Type-pattern case labels, guards, dominance, and pattern-specific exhaustiveness are unsupported; non-pattern modern switch is implemented as Feature 91.   |
| [107](#feature-107). Record patterns and unnamed patterns                          | ❌ Record deconstruction and `_` pattern bindings are unsupported independently of where a pattern appears.                                                   |
| [108](#feature-108). Primitive types in patterns, `instanceof`, and `switch`       | ❌ Java SE 26's preview primitive-pattern family is recorded separately and is not an Ironwood commitment.                                                     |

## Pending-feature priority

No numbered feature is currently pending. D082 completes Feature 91 without
promoting Feature 104. D098 implements Feature 79 as the scoped IronDocs subset;
remaining Javadoc facilities are deferred without selecting another target.

D069 creates the first three commitments without ranking them. Allocation
exhaustion is not part of Feature 100; D070 commits its separate Feature 105
contract without assigning a rank.

D067 originally confirmed Features 79, 81, 82, 87, 88, 91, and 93–97 as ❌. It
removes `volatile` from Feature 97 and records it independently as Feature 101,
also ❌ until a later decision promotes it. No confirmed exclusion is an
implementation target.

D068 removes `assert` from the still-open Feature 90 and records it separately
as confirmed-❌ Feature 102.

D069 also confirms Feature 99 as ❌ for now, splits Java-style octal notation
into confirmed-excluded Feature 103, and splits hexadecimal floating-point
literals and allocation exhaustion into still-open Features 104 and 105.

D070 promotes Feature 105 to ⏳ with the bounded native-exhaustion contract
detailed below. D071 promotes Features 86, 89, 90, and 101 to unranked ⏳
commitments without selecting an implementation target. Hexadecimal
floating-point literals under Feature 104 remain the sole open promotion
decision from the D070 candidate set.

D072 separates the former Feature 66 umbrella into stable reference type
patterns for `instanceof` under Feature 66, reference type patterns in `switch`
under Feature 106, record/unnamed patterns under Feature 107, and preview
primitive patterns under Feature 108. Feature 91 remains exclusively the
non-pattern modern `switch` surface. All five remain ❌ until individually
promoted, and D072 selects no implementation target.

D073 promotes Feature 66 and Feature 91 to ⏳, returns Feature 101 to ❌, confirms
Features 106–108 as excluded, and ranks all nine pending features in the order
shown above. The ranking records roadmap priority but selects no implementation
target.

D074 implements Feature 100 and removes it from the pending table. The eight
remaining entries retain D073's relative order, now numbered 1 through 8. This
status update selects no subsequent implementation target. The comparison now
contains 58 ✅, 10 💡, 32 ❌, and 8 ⏳ entries.

D075 implements Feature 66 and removes it from the pending table. The seven
remaining entries retain their relative order, now numbered 1 through 7. This
status update selects no subsequent implementation target. The comparison now
contains 59 ✅, 10 💡, 32 ❌, and 7 ⏳ entries.

D076 implements Feature 90 and removes it from the pending table. The six
remaining entries retain their relative order, now numbered 1 through 6. This
status update selects no subsequent implementation target. The comparison now
contains 60 ✅, 10 💡, 32 ❌, and 6 ⏳ entries.

D077 implements Feature 92 and removes it from the pending table. The five
remaining entries retain their relative order, now numbered 1 through 5. This
status update selects no subsequent implementation target. The comparison now
contains 61 ✅, 10 💡, 32 ❌, and 5 ⏳ entries.

D078 implements Feature 89 and removes it from the pending table. The four
remaining entries retain their relative order, now numbered 1 through 4. This
status update selects no subsequent implementation target. The comparison now
contains 62 ✅, 10 💡, 32 ❌, and 4 ⏳ entries.

D079 implements Feature 83 and removes it from the pending table. The three
remaining entries retain their relative order, now numbered 1 through 3. This
status update selects no subsequent implementation target. The comparison now
contains 63 ✅, 10 💡, 32 ❌, and 3 ⏳ entries.

D080 implements Feature 86 and removes it from the pending table. The two
remaining entries retain their relative order, now numbered 1 through 2. This
status update selects no subsequent implementation target. The comparison now
contains 64 ✅, 10 💡, 32 ❌, and 2 ⏳ entries.

D081 implements Feature 105 and removes it from the pending table. Feature 91
is the sole remaining pending entry and becomes priority 1. This status update
selects no subsequent implementation target. The comparison now contains 64 ✅,
11 💡, 32 ❌, and 1 ⏳ entries.

D082 implements Feature 91 and removes the final entry from the pending table.
The comparison now contains 65 ✅, 11 💡, 32 ❌, and 0 ⏳ entries. Feature 104
remains an open promotion decision with its current ❌ status, and no subsequent
implementation target is selected.

D083 adds deterministic class destructors, closed-world destructor and
constructor-publication effects, fresh factory and owned-field proofs,
failed-construction rollback, and current-live allocation accounting. It
refines the implemented Ironwood alternatives under Features 10 and 11 without
changing any numbered feature status or the matrix totals.

D084 adds dependent borrows for compiler-owned reusable iterators and primitive
holders. Collection APIs stay Java-shaped and allocation-free per traversal;
the owner destructor reclaims the cached helper, while independent helper
`free`, post-owner use, and escaped or uncertain borrows are rejected. It
further refines Features 10 and 11 without changing matrix totals.

D104 refines the same Ironwood alternatives with creation-only owning object
pools. `get()` lends a value and `release()` returns it. Destruction includes all
builder-created objects, even checked-out values, but excludes external objects. Compiler lifetime checks
reject dangling uses. Features 10 and 11 and all matrix totals are unchanged.

D107 permits explicit caller reclamation after known local data structures
release all item loans through clear or destruction. Unmodifiable views borrow
their backing lists until view destruction. This refines Features 10 and 11
without changing their status or any matrix total.

## Core classes, objects, and lifetime

<a id="feature-1"></a>

### 1. Classes and object construction

Java:

```java
class Point {
    int x;
    Point(int x) { this.x = x; }
    int value() { return x; }
}

Point point = new Point(42);
```

Ironwood:

```java
class Point {
    int x;
    Point(int x) { this.x = x; }
    int value() { return x; }
}

Point point = new Point(42);
// Same source shape; the result is a native object, not a JVM object.
```

<a id="feature-2"></a>

### 2. `Object` root, references, and `null`

Java:

```java
class Item { }

Item first = new Item();
Item alias = first;       // Copies the reference.
Object widened = first;
Item missing = null;
```

Ironwood:

```java
class Item { }

Item first = new Item();
Item alias = first;       // Also copies the reference, not the object.
Object widened = first;   // ironwood.lang.Object is implicit.
Item missing = null;
```

<a id="feature-3"></a>

### 3. Instance fields, methods, and composition

Java:

```java
class Engine { int start() { return 42; } }
class Car {
    private Engine engine;
    Car(Engine engine) { this.engine = engine; }
    int start() { return engine.start(); }
}
```

Ironwood:

```java
class Engine { int start() { return 42; } }
class Car {
    private Engine engine;
    Car(Engine engine) { this.engine = engine; }
    int start() { return engine.start(); }
}
// Composition copies and retains an Engine reference, not the Engine object.
```

<a id="feature-4"></a>

### 4. Static fields and methods

Java:

```java
class Ids {
    private static int next;
    static int nextId() { return ++next; }
}
```

Ironwood:

```java
class Ids {
    private static int next;
    static int nextId() { return ++next; }
}
// Static members work. See the automatic class-initialization section.
```

<a id="feature-5"></a>

### 5. Encapsulation, access control, and package access

Java:

```java
public class Account {
    private int balance;
    protected int rawBalance() { return balance; }
    int packageBalance() { return balance; }
    public int balance() { return balance; }
}
```

Ironwood:

```java
public class Account {
    private int balance;
    protected int rawBalance() { return balance; }
    int packageBalance() { return balance; }
    public int balance() { return balance; }
}
// Public top-level type names must also match their .iron filename.
```

<a id="feature-6"></a>

### 6. Constructors and default constructors

Java:

```java
class Empty { }                 // Receives Empty().
class Value {
    int amount;
    Value(int amount) { this.amount = amount; }
}
```

Ironwood:

```java
class Empty { }                 // Also receives Empty().
class Value {
    int amount;
    Value(int amount) { this.amount = amount; }
}
```

<a id="feature-7"></a>

### 7. Constructor overloading and `this(...)` delegation

Java:

```java
class Size {
    int width;
    int height;
    Size(int both) { this(both, both); }
    Size(int width, int height) {
        this.width = width;
        this.height = height;
    }
}
```

Ironwood:

```java
class Size {
    int width;
    int height;
    Size(int both) { this(both, both); }
    Size(int width, int height) {
        this.width = width;
        this.height = height;
    }
}
// Recursive this(...) chains are rejected at compile time.
```

<a id="feature-8"></a>

### 8. Instance initialization order

Java:

```java
class Base {
    int value = 1;
    Base() { value = value + 1; }
}
class Child extends Base {
    int extra = 10;
    { extra = extra + 10; }
    Child() { extra = extra + value; }
}
```

Ironwood:

```java
class Base {
    int value = 1;
    Base() { value = value + 1; }
}
class Child extends Base {
    int extra = 10;
    { extra = extra + 10; }
    Child() { extra = extra + value; }
}
// Base initialization completes before Child field/block initialization.
```

Runnable Ironwood example: [`Initialization.iron`](../examples/initialization/src/main/ironwood/org/ironwood/initialization/Initialization.iron).

<a id="feature-9"></a>

### 9. `final` fields, locals, and parameters

Java:

```java
class Order {
    private final int id;
    Order(int id) { this.id = id; }
    int copy(final int value) {
        final int result = value;
        return result;
    }
}
```

Ironwood:

```java
class Order {
    private final int id;
    Order(int id) { this.id = id; } // Blank final assigned exactly once.
    int copy(final int value) {
        final int result = value;
        return result;
    }
}
```

Runnable Ironwood example: [`FinalBindings.iron`](../examples/finalbindings/src/main/ironwood/org/ironwood/finalbindings/FinalBindings.iron).

<a id="feature-10"></a>

### 10. Garbage collection

Java:

```java
Buffer buffer = new Buffer();
use(buffer);
buffer = null;
// The GC may eventually reclaim the unreachable Buffer.
```

Ironwood way for memory reclamation:

```ironwood
Buffer buffer = new Buffer();
use(buffer);
free buffer; // Runs Buffer's destructor chain, then releases the allocation.
```

Java garbage collection tracks reachability automatically and lets the
collector choose when to reclaim an unreachable object. Ironwood has no garbage
collector, reference counting, or other automatic collector fallback. Memory
reclamation is manual through `free`, which the compiler accepts only after it
proves the allocation has no observable live alias. If no such proof is
possible, the allocation remains until process exit. A constructor that throws
does not create a source value: compiler-generated rollback instead reclaims
the receiver and proven-owned partial state without running the incomplete
receiver's source destructor; completed owned children are destroyed normally.

<a id="feature-11"></a>

### 11. Explicit safe `free`

Java:

```java
Buffer buffer = new Buffer();
use(buffer);
// Java has no source operation that deterministically frees this object.
```

Ironwood way:

```ironwood
class Buffer {
    private byte[] storage = new byte[4096];

    destructor {
        free storage;
    }
}

Buffer buffer = new Buffer();
use(buffer);
free buffer;
// The compiler accepts free only when no live alias can use this exact
// heap allocation afterward.
// free buffer;        // Rejected: the allocation was already freed.
// use(buffer);        // Rejected: the old value is dead, not null.
buffer = new Buffer(); // Allowed: plain assignment reuses the variable.
free buffer;
```

Cleanup may also cover every normal and abrupt exit from a structured region:

```ironwood
Buffer scoped = new Buffer();
try {
    use(scoped);
} finally {
    free scoped;
}
```

`free` is Ironwood's compiler-checked extension for reclaiming heap-object or
array-container memory. For an object it selects the most-derived runtime type,
runs each declared destructor from derived class to root, and then releases the
object allocation. A destructor may explicitly free only compiler-proven-owned
fields and must be allocation-free, non-escaping, non-resurrecting, and unable
to throw outward. Arrays have no destructor body. Destruction does not
implicitly traverse arbitrary referenced objects or close an external resource. It
does not assign `null` to the target variable: the freed value becomes
compiler-tracked dead state, so a second `free` and every later observation are
rejected. A plain assignment may reuse the variable because it replaces the
dead value without reading it and starts a separate allocation lifetime.

Compiler-proven-owned reusable helpers are a narrow compositional case rather
than arbitrary graph traversal. For example, an `ArrayList` owns its cached
iterator even though `iterator()` lends that object to ordinary Java-shaped
code. The caller cannot free the iterator. Completed traversal may be followed
by `free list;`; the list destructor destroys the iterator, and any later
iterator observation is rejected. If the iterator or a nested reusable holder
escapes somewhere observable or uncertain, `free list;` is rejected instead.
See [Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

Known local bundled data structures also track loans to caller-owned items.
After every retaining container clears or is destroyed, the caller may explicitly
free an item if no other alias escaped or still retains it. Individual removal,
exposed element/iterator aliases and uncertain callbacks remain conservative;
the hybrid list requires destruction because its package accessor can read
inactive slots. Containers never destroy those caller items. An unmodifiable
view must be destroyed before its backing list can be freed. These are compiler
proofs with no runtime ownership bookkeeping (D107).

Feature 11 is 💡 rather than ✅ because `free` is an Ironwood-specific source
operation, not Java syntax. It is the implemented language mechanism behind
Feature 10's deterministic memory-reclamation alternative. The ownership proof
tracks the compiler-generated copies of a `finally` block independently across
normal completion, return, catch completion, exceptional unwinding, `break`,
`continue`, and `yield`. It therefore accepts an exactly-once cleanup such as the
example above without
weakening double-free, post-free-use, live-alias, or escape rejection. Transfer
destinations retain the post-cleanup state; an allocation possibly freed on an
incoming path cannot be observed. Loop back edges reject newly freed carried
references and incompatible ownership for a repeated free, while per-iteration
local allocations may be freed before `continue`. A pending reference-valued
`yield` remains an alias that cleanup cannot free.

## Inheritance and polymorphism

<a id="feature-12"></a>

### 12. Single class inheritance

Java:

```java
class Animal { int legs() { return 4; } }
class Dog extends Animal { }
// A class cannot extend two classes.
```

Ironwood:

```java
class Animal { int legs() { return 4; } }
class Dog extends Animal { }
// The same single-class-inheritance rule applies.
```

<a id="feature-13"></a>

### 13. Abstract classes and methods

Java:

```java
abstract class Shape {
    abstract int area();
    int doubledArea() { return area() * 2; }
}
class Square extends Shape {
    @Override int area() { return 21; }
}
```

Ironwood:

```java
abstract class Shape {
    abstract int area();
    int doubledArea() { return area() * 2; }
}
class Square extends Shape {
    @Override int area() { return 21; }
}
// new Shape() is rejected; concrete subclasses must satisfy all obligations.
```

Runnable Ironwood example: [`AbstractClasses.iron`](../examples/abstractclasses/src/main/ironwood/org/ironwood/abstractclasses/AbstractClasses.iron).

<a id="feature-14"></a>

### 14. Final classes and methods

Java:

```java
final class Token { }
class Base {
    final int identity() { return 42; }
}
```

Ironwood:

```java
final class Token { }
class Base {
    final int identity() { return 42; }
}
// Extending Token or overriding identity() is a compile-time error.
```

Runnable Ironwood example: [`Finality.iron`](../examples/finality/src/main/ironwood/org/ironwood/finality/Finality.iron).

<a id="feature-15"></a>

### 15. Method overriding and dynamic dispatch

Java:

```java
class Animal { int sound() { return 1; } }
class Dog extends Animal {
    @Override int sound() { return 42; }
}
Animal animal = new Dog();
int result = animal.sound(); // 42
```

Ironwood:

```java
class Animal { int sound() { return 1; } }
class Dog extends Animal {
    @Override int sound() { return 42; }
}
Animal animal = new Dog();
int result = animal.sound(); // 42
// Omitting @Override from Dog.sound() is a compile-time error.
```

<a id="feature-16"></a>

### 16. Method overloading

Java:

```java
class Formatter {
    int size(int value) { return 1; }
    int size(String value) { return value.length(); }
}
```

Ironwood:

```java
class Formatter {
    int size(int value) { return 1; }
    int size(String value) { return value.length(); }
}
// Ironwood overloads are fixed-arity; see the varargs section.
```

<a id="feature-17"></a>

### 17. Covariant reference returns

Java:

```java
class Animal { }
class Dog extends Animal { }
class Factory { Animal create() { return new Animal(); } }
class DogFactory extends Factory {
    @Override Dog create() { return new Dog(); }
}
```

Ironwood:

```java
class Animal { }
class Dog extends Animal { }
class Factory { Animal create() { return new Animal(); } }
class DogFactory extends Factory {
    @Override Dog create() { return new Dog(); }
}
```

<a id="feature-18"></a>

### 18. `super(...)`, `super.field`, and `super.method(...)`

Java:

```java
class Base {
    protected int value = 20;
    Base(int extra) { value += extra; }
    int result() { return value; }
}
class Child extends Base {
    Child() { super(1); }
    @Override int result() { return super.result() + super.value; }
}
```

Ironwood:

```java
class Base {
    protected int value = 20;
    Base(int extra) { value += extra; }
    int result() { return value; }
}
class Child extends Base {
    Child() { super(1); }
    @Override int result() { return super.result() + super.value; }
}
// super.method() bypasses virtual dispatch and selects the direct superclass.
```

Runnable Ironwood example: [`SuperAccess.iron`](../examples/superaccess/src/main/ironwood/org/ironwood/superaccess/SuperAccess.iron).

<a id="feature-19"></a>

### 19. Field hiding

Java:

```java
class Base { int value = 10; }
class Child extends Base { int value = 32; }

Child child = new Child();
Base base = child;
int a = child.value; // 32
int b = base.value;  // 10
```

Ironwood:

```java
class Base { int value = 10; }
class Child extends Base { int value = 32; }

Child child = new Child();
Base base = child;
int a = child.value; // 32
int b = base.value;  // 10
// Fields are selected statically; they are never virtually overridden.
```

Runnable Ironwood example: [`FieldHiding.iron`](../examples/fieldhiding/src/main/ironwood/org/ironwood/fieldhiding/FieldHiding.iron).

<a id="feature-20"></a>

### 20. Class and interface polymorphism

Java:

```java
interface Runner { int run(); }
class Task implements Runner { @Override public int run() { return 42; } }

Runner runner = new Task();
int result = runner.run();
```

Ironwood:

```java
interface Runner { int run(); }
class Task implements Runner { @Override public int run() { return 42; } }

Runner runner = new Task();
int result = runner.run();
// The native call may be devirtualized when the closed world proves one target.
```

<a id="feature-21"></a>

### 21. Class/interface casts and classic `instanceof`

Java:

```java
Animal animal = new Dog();
if (animal instanceof Dog) {
    Dog dog = (Dog) animal;
}
```

Ironwood:

```java
Animal animal = new Dog();
if (animal instanceof Dog) {
    Dog dog = (Dog) animal;
}
// Ordinary class/interface casts and tests use the same source form.
```

This part is fully supported. Ironwood checks the closed-world nominal type
descriptor at runtime: `null instanceof Dog` is false, a compatible cast
succeeds, and an incompatible supported cast raises `ClassCastException`.
Generic type arguments and array descriptors are separate cases below because
they require different runtime information.

<a id="feature-22"></a>

### 22. Root `Object` methods

Java:

```java
class Key {
    int id;
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return id; }
    @Override public String toString() { return "Key"; }
}
```

Ironwood:

```java
class Key {
    int id;
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return id; }
    @Override public String toString() { return "Key"; }
}
// These are normal virtual ironwood.lang.Object methods.
```

## Interfaces

<a id="feature-23"></a>

### 23. Implementing multiple interfaces

Java:

```java
interface Readable { int read(); }
interface Writable { int write(); }
class Device implements Readable, Writable {
    @Override public int read() { return 20; }
    @Override public int write() { return 22; }
}
```

Ironwood:

```java
interface Readable { int read(); }
interface Writable { int write(); }
class Device implements Readable, Writable {
    @Override public int read() { return 20; }
    @Override public int write() { return 22; }
}
```

<a id="feature-24"></a>

### 24. Multiple interface inheritance

Java:

```java
interface Left { int left(); }
interface Right { int right(); }
interface Both extends Left, Right { }
```

Ironwood:

```java
interface Left { int left(); }
interface Right { int right(); }
interface Both extends Left, Right { }
```

<a id="feature-25"></a>

### 25. Interface constants

Java:

```java
interface Limits {
    int MAX = 42; // Implicitly public static final.
}
```

Ironwood:

```java
interface Limits {
    int MAX = 42; // Also public static final.
}
// The initializer must fit Ironwood's compile-time constant model.
```

<a id="feature-26"></a>

### 26. Abstract, default, static, and private interface methods

Java:

```java
interface Service {
    int execute();
    default int doubled() { return helper(execute()); }
    static int version() { return 1; }
    private int helper(int value) { return value * 2; }
    private static int normalize(int value) { return value; }
}
```

Ironwood:

```java
interface Service {
    int execute();
    default int doubled() { return helper(execute()); }
    static int version() { return 1; }
    private int helper(int value) { return value * 2; }
    private static int normalize(int value) { return value; }
}
```

Runnable Ironwood example: [`InterfaceMembers.iron`](../examples/interfacemembers/src/main/ironwood/org/ironwood/interfacemembers/InterfaceMembers.iron).

<a id="feature-27"></a>

### 27. Default-method resolution and `Interface.super`

Java:

```java
interface Left { default int value() { return 20; } }
interface Right { default int value() { return 22; } }
class Both implements Left, Right {
    @Override public int value() { return Left.super.value() + Right.super.value(); }
}
```

Ironwood:

```java
interface Left { default int value() { return 20; } }
interface Right { default int value() { return 22; } }
class Both implements Left, Right {
    @Override public int value() { return Left.super.value() + Right.super.value(); }
}
// Without this override, unrelated defaults are a compile-time conflict.
```

Runnable Ironwood example: [`InterfaceDefaults.iron`](../examples/interfacedefaults/src/main/ironwood/org/ironwood/interfacedefaults/InterfaceDefaults.iron).

## Nested, local, and anonymous types

<a id="feature-28"></a>

### 28. Static nested classes

Java:

```java
class Outer {
    static int seed = 40;
    static class Nested {
        int value() { return seed + 2; }
    }
}
Outer.Nested value = new Outer.Nested();
```

Ironwood:

```java
class Outer {
    static int seed = 40;
    static class Nested {
        int value() { return seed + 2; }
    }
}
Outer.Nested value = new Outer.Nested();
// No hidden enclosing Outer instance is retained.
```

Runnable Ironwood example: [`StaticNestedTypes.iron`](../examples/staticnested/src/main/ironwood/org/ironwood/staticnested/StaticNestedTypes.iron).

<a id="feature-29"></a>

### 29. Member inner classes and `Outer.this`

Java:

```java
class Outer {
    int base;
    Outer(int base) { this.base = base; }
    class Inner {
        int value() { return Outer.this.base + 2; }
    }
}
Outer outer = new Outer(40);
Outer.Inner inner = outer.new Inner();
```

Ironwood:

```java
class Outer {
    int base;
    Outer(int base) { this.base = base; }
    class Inner {
        int value() { return Outer.this.base + 2; }
    }
}
Outer outer = new Outer(40);
Outer.Inner inner = outer.new Inner();
// The hidden enclosing reference is a real alias for safe-free analysis.
```

Runnable Ironwood example: [`InnerClasses.iron`](../examples/innerclasses/src/main/ironwood/org/ironwood/innerclasses/InnerClasses.iron).

<a id="feature-30"></a>

### 30. Qualified inner-superclass construction

Java:

```java
class Outer {
    class Base { Base(int value) { } }
}
class Child extends Outer.Base {
    Child(Outer outer) { outer.super(42); }
}
```

Ironwood:

```java
class Outer {
    class Base { Base(int value) { } }
}
class Child extends Outer.Base {
    Child(Outer outer) { outer.super(42); }
}
// outer supplies the enclosing instance required by Outer.Base.
```

Runnable Ironwood example: [`QualifiedSuper.iron`](../examples/qualifiedsuper/src/main/ironwood/org/ironwood/qualifiedsuper/QualifiedSuper.iron).

<a id="feature-31"></a>

### 31. Local classes

Java:

```java
int calculate(int base) {
    class Local {
        int value() { return base + 2; }
    }
    return new Local().value();
}
```

Ironwood:

```java
int calculate(int base) {
    class Local {
        int value() { return base + 2; }
    }
    return new Local().value();
}
// Local receives a deterministic closed-world identity; no runtime class is generated.
```

Runnable Ironwood example: [`LocalClasses.iron`](../examples/localclasses/src/main/ironwood/org/ironwood/localclasses/LocalClasses.iron).

<a id="feature-32"></a>

### 32. Anonymous classes

Java:

```java
interface Value { int get(); }
int base = 40;
Value value = new Value() {
    @Override public int get() { return base + 2; }
};
```

Ironwood:

```java
interface Value { int get(); }
int base = 40;
Value value = new Value() {
    @Override public int get() { return base + 2; }
};
// The anonymous implementation exists at compile/link time, never at runtime.
```

Runnable Ironwood example: [`AnonymousClasses.iron`](../examples/anonymousclasses/src/main/ironwood/org/ironwood/anonymousclasses/AnonymousClasses.iron).

<a id="feature-33"></a>

### 33. Qualified generic anonymous construction

Java:

```java
class Outer<T> {
    class Inner<U> {
        Inner(U value) { }
        U value(U input) { return input; }
    }
}
Outer<String> outer = new Outer<>();
Outer<String>.Inner<Integer> inner = outer.new Inner<Integer>(42) { };
```

Ironwood:

```java
class Outer<T> {
    class Inner<U> {
        Inner(U value) { }
        U value(U input) { return input; }
    }
}
Outer<String> outer = new Outer<>();
Outer<String>.Inner<Integer> inner = outer.new Inner<Integer>(42) { };
// The receiver selects the exact Outer<String>.Inner<Integer> owner view.
```

Runnable Ironwood example: [`QualifiedAnonymous.iron`](../examples/qualifiedanonymous/src/main/ironwood/org/ironwood/qualifiedanonymous/QualifiedAnonymous.iron).

<a id="feature-34"></a>

### 34. Anonymous enclosing operands and evaluation order

Java:

```java
class Outer {
    class Inner { Inner(int value) { } }
    Outer select(Outer other) { return other; }
    int argument() { return 42; }
    Inner create(Outer other) {
        return select(other).new Inner(argument()) { };
    }
}
```

Ironwood:

```java
class Outer {
    class Inner { Inner(int value) { } }
    Outer select(Outer other) { return other; }
    int argument() { return 42; }
    Inner create(Outer other) {
        return select(other).new Inner(argument()) { };
    }
}
// select(other) runs once, before argument(); its result is null-checked before
// arguments, and remains distinct from the anonymous body's lexical Outer.
```

Feature 34 covers receiver identity, immediate null checking, argument order,
and lexical-enclosing identity. Feature 105 separately specifies the intentional
Ironwood allocation-attempt timing once exhaustion becomes catchable.

Runnable Ironwood example: [`AnonymousEnclosing.iron`](../examples/anonymousenclosing/src/main/ironwood/org/ironwood/anonymousenclosing/AnonymousEnclosing.iron).

<a id="feature-35"></a>

### 35. Diamond with anonymous classes

Java:

```java
class Box<T> {
    T value;
    Box(T value) { this.value = value; }
    T get() { return value; }
}
Box<String> box = new Box<>("value") {
    @Override String get() { return super.get(); }
};
```

Ironwood:

```java
class Box<T> {
    T value;
    Box(T value) { this.value = value; }
    T get() { return value; }
}
Box<String> box = new Box<>("value") {
    @Override String get() { return super.get(); }
};
// With anonymous diamond, a new non-private instance method must override.
```

Runnable Ironwood example: [`AnonymousDiamond.iron`](../examples/anonymousdiamond/src/main/ironwood/org/ironwood/anonymousdiamond/AnonymousDiamond.iron).

<a id="feature-36"></a>

### 36. Effectively-final local capture

Java:

```java
int create(int parameter) {
    int local = 2;
    class Captured {
        int value() { return parameter + local; }
    }
    return new Captured().value();
}
```

Ironwood:

```java
int create(int parameter) {
    int local = 2;
    class Captured {
        int value() { return parameter + local; }
    }
    return new Captured().value();
}
// A later assignment or ++/-- of parameter/local would make capture illegal.
```

Runnable Ironwood example: [`LexicalCapture.iron`](../examples/lexicalcapture/src/main/ironwood/org/ironwood/lexicalcapture/LexicalCapture.iron).

<a id="feature-37"></a>

### 37. Same-nest private access

Java:

```java
class Outer {
    private int secret = 42;
    class Inner {
        int reveal() { return secret; }
    }
}
```

Ironwood:

```java
class Outer {
    private int secret = 42;
    class Inner {
        int reveal() { return secret; }
    }
}
// The compiler resolves source-nest access directly; no synthetic accessor.
```

Runnable Ironwood example: [`NestAccess.iron`](../examples/nestaccess/src/main/ironwood/org/ironwood/nestaccess/NestAccess.iron).

<a id="feature-38"></a>

### 38. Nested interfaces and member types in lexical classes

Java:

```java
void work() {
    class Local {
        interface Source { int get(); }
        class Value implements Source { @Override public int get() { return 42; } }
    }
}
```

Ironwood:

```java
void work() {
    class Local {
        interface Source { int get(); }
        class Value implements Source { @Override public int get() { return 42; } }
    }
}
// Lexical classes may themselves contain ordinary nested/member types.
```

Runnable Ironwood examples: [`NestedInterfaces.iron`](../examples/nestedinterfaces/src/main/ironwood/org/ironwood/nestedinterfaces/NestedInterfaces.iron) and [`LexicalMemberTypes.iron`](../examples/lexicalmembertypes/src/main/ironwood/org/ironwood/lexicalmembertypes/LexicalMemberTypes.iron).

<a id="feature-39"></a>

### 39. Captured references and reclamation safety

Java:

```java
Box box = new Box();
Runnable reader = new Runnable() {
    @Override public void run() { box.read(); }
};
// GC traces both box and the hidden captured reference.
```

Ironwood way:

```java
Box box = new Box();
Reader reader = new Reader() {
    @Override public int read() { return box.read(); }
};
// free box; // Rejected while reader's hidden captured alias may still observe it.
```

Runnable Ironwood example: [`CapturedAliases.iron`](../examples/capturedaliases/src/main/ironwood/org/ironwood/capturedaliases/CapturedAliases.iron).

Feature 39 is 💡 because Java relies on garbage collection to keep captured
objects alive, while Ironwood makes the same hidden references participate in
its implemented compile-time proof for explicit `free`.

## Generics

<a id="feature-40"></a>

### 40. Generic classes and interfaces

Java:

```java
interface Source<T> { T get(); }
class Box<T> implements Source<T> {
    T value;
    Box(T value) { this.value = value; }
    @Override public T get() { return value; }
}
```

Ironwood:

```java
interface Source<T> { T get(); }
class Box<T> implements Source<T> {
    T value;
    Box(T value) { this.value = value; }
    @Override public T get() { return value; }
}
```

Reference arguments use one shared pointer-shaped native layout. Feature 51
separately allows an unbounded parameter such as `T` to receive a primitive and
materializes the required native value shape in the final closed world.

<a id="feature-41"></a>

### 41. Upper, intersection, dependent, and recursive bounds

Java:

```java
interface Named { int name(); }
interface Ranked { int rank(); }
class Pair<A extends Named & Ranked, B extends A> {
    int score(B value) { return value.name() + value.rank(); }
}
```

Ironwood:

```java
interface Named { int name(); }
interface Ranked { int rank(); }
class Pair<A extends Named & Ranked, B extends A> {
    int score(B value) { return value.name() + value.rank(); }
}
```

Runnable Ironwood example: [`BoundedGenerics.iron`](../examples/boundedgenerics/src/main/ironwood/org/ironwood/boundedgenerics/BoundedGenerics.iron).

<a id="feature-42"></a>

### 42. Generic methods and constructors

Java:

```java
class Box<T> {
    T value;
    <U extends T> Box(U value) { this.value = value; }
    static <V> V identity(V value) { return value; }
}
```

Ironwood:

```java
class Box<T> {
    T value;
    <U extends T> Box(U value) { this.value = value; }
    static <V> V identity(V value) { return value; }
}
```

Runnable Ironwood example: [`GenericCallables.iron`](../examples/genericcallables/src/main/ironwood/org/ironwood/genericcallables/GenericCallables.iron).

<a id="feature-43"></a>

### 43. Type inference and diamond

Java:

```java
class Box<T> {
    T value;
    Box(T value) { this.value = value; }
}
static <T> T choose(T value) { return value; }

Box<String> box = new Box<>(choose("value"));
```

Ironwood:

```java
class Box<T> {
    T value;
    Box(T value) { this.value = value; }
}
static <T> T choose(T value) { return value; }

Box<String> box = new Box<>(choose("value"));
// Candidate-local inference also uses the expected Box<String> target.
```

Runnable Ironwood examples: [`GenericInference.iron`](../examples/genericinference/src/main/ironwood/org/ironwood/genericinference/GenericInference.iron) and [`DiamondInference.iron`](../examples/diamond/src/main/ironwood/org/ironwood/diamond/DiamondInference.iron).

<a id="feature-44"></a>

### 44. Return-only generic method inference

Java:

```java
interface List<E> { }
class ArrayList<E> implements List<E> { }

class Lists {
    public static <T> List<T> legacySource() {
        return new ArrayList<>();
    }
}

List<String> value = Lists.legacySource();
List<Integer> another = Lists.legacySource();
```

Ironwood:

```java
interface List<E> { }
class ArrayList<E> implements List<E> { }

class Lists {
    public static <T> List<T> legacySource() {
        return new ArrayList<>();
    }
}

List<String> value = Lists.legacySource();   // T is String.
List<Integer> another = Lists.legacySource(); // T is Integer.
```

This is a compile-time generic guarantee, not a method dynamically returning an
arbitrary unrelated type. Each call's expected target independently determines
`T`, and the method body must type-check for every permitted `T`. The declared
`List<T>` return also gives `new ArrayList<>()` the evidence needed to infer
`ArrayList<T>`. No cast, runtime generic test, or boxing is involved.

The inference feature is implemented and tested. The current standard library
has `ironwood.ds.ArrayList<E>` but no `ironwood.ds.List<E>` interface; the same
pattern works today with an `ArrayList<T>` return or with application-defined
`List<E>`/`ArrayList<E>` types like those above. Separately, an object allocated
inside a called method is not currently eligible for caller-side `free` under
the conservative same-function allocation-identity proof, so repeated factory
allocation needs an ownership-aware API design rather than relying on inference.

Runnable Ironwood example: [`GenericInference.iron`](../examples/genericinference/src/main/ironwood/org/ironwood/genericinference/GenericInference.iron).

<a id="feature-45"></a>

### 45. Wildcards and capture conversion

Java:

```java
Box<? extends Animal> producer = dogBox;
Animal animal = producer.get();

Box<? super Dog> consumer = animalBox;
consumer.set(new Dog());
```

Ironwood:

```java
Box<? extends Animal> producer = dogBox;
Animal animal = producer.get();

Box<? super Dog> consumer = animalBox;
consumer.set(new Dog());
// The same producer/consumer read-write restrictions are checked statically.
```

Runnable Ironwood example: [`WildcardCapture.iron`](../examples/wildcardcapture/src/main/ironwood/org/ironwood/wildcardcapture/WildcardCapture.iron).

<a id="feature-46"></a>

### 46. Exact generic owner/member types

Java:

```java
class Outer<T> {
    class Inner<U> { T outerValue; U innerValue; }
    static class Nested<V> { V value; }
}
Outer<String>.Inner<Label> inner;
Outer.Nested<Label> nested;
```

Ironwood:

```java
class Outer<T> {
    class Inner<U> { T outerValue; U innerValue; }
    static class Nested<V> { V value; }
}
Outer<String>.Inner<Label> inner;
Outer.Nested<Label> nested;
// Inner retains String and Label; static Nested starts without Outer<T>.
```

Runnable Ironwood example: [`NestedGenerics.iron`](../examples/nestedgenerics/src/main/ironwood/org/ironwood/nestedgenerics/NestedGenerics.iron).

<a id="feature-47"></a>

### 47. Diamond on inner and anonymous-inner construction

Java:

```java
class Outer<T> {
    class Inner<U> { Inner(U value) { } }
}
Outer<String> outer = new Outer<>();
Outer<String>.Inner<Label> first = outer.new Inner<>(new Label());
Outer<String>.Inner<Label> second = outer.new Inner<>(new Label()) { };
```

Ironwood:

```java
class Outer<T> {
    class Inner<U> { Inner(U value) { } }
}
Outer<String> outer = new Outer<>();
Outer<String>.Inner<Label> first = outer.new Inner<>(new Label());
Outer<String>.Inner<Label> second = outer.new Inner<>(new Label()) { };
```

Runnable Ironwood example: [`InnerDiamond.iron`](../examples/innerdiamond/src/main/ironwood/org/ironwood/innerdiamond/InnerDiamond.iron).

<a id="feature-48"></a>

### 48. Reifiable generic tests and source-provable parameterized casts

Java:

```java
Object value = new Box<String>("value");
if (value instanceof Box<?>) {
    Box<?> box = (Box<?>) value;
}
// Java may also permit unchecked casts with a warning.
```

Ironwood:

```java
Object value = new Box<String>("value");
if (value instanceof Box<?>) {
    Box<?> box = (Box<?>) value;
}
// A concrete Box<String> cast is also accepted when the complete source
// hierarchy proves that every possible matching Box carries String.
```

Runnable Ironwood example: [`GenericCasts.iron`](../examples/genericcasts/src/main/ironwood/org/ironwood/genericcasts/GenericCasts.iron).

`Box<?>` is reifiable for reference-shaped instantiations because its runtime
question is only “is this a pointer-shaped `Box`?” Ironwood can answer that from
nominal type metadata. Primitive-specialized instances deliberately do not
carry this wildcard membership: otherwise a native value field or return could
be observed through a pointer-shaped wildcard ABI. A concrete parameterized
cast such as `Box<String>` is accepted when closed-world source analysis proves
the `String` argument, leaving only the same nominal runtime question. These
safe forms are implemented now.

<a id="feature-49"></a>

### 49. Unchecked generic casts and non-reifiable `instanceof`

Java:

```java
class Text { }
class NumberValue { }
class Box<T> {
    private T value;
    Box(T value) { this.value = value; }
    T get() { return value; }
    void set(T value) { this.value = value; }
}

Object unknown = new Box<NumberValue>(new NumberValue());

// Java warns, but accepts this. At runtime it checks only "is this a Box?".
Box<Text> claimed = (Box<Text>) unknown;

// The cast above succeeds. This later read fails with ClassCastException.
Text text = claimed.get();
// claimed.set(new Text()) would instead pollute the Box<NumberValue>.

// unknown instanceof Box<Text>  // Java rejects this non-reifiable test.
boolean someBox = unknown instanceof Box<?>; // This reifiable test is legal.
```

Ironwood (unchecked operation not supported; safe operations shown):

```java
Object unknown = receiveUnknownValue();

if (unknown instanceof Box<?>) {
    Box<?> box = (Box<?>) unknown;
    Object item = box.get(); // Safe: no unsupported type argument is claimed.

    if (item instanceof Text) {
        Text checked = (Text) item;
        Box<Text> validated = new Box<Text>(checked);
        consumeTextBox(validated);
        free validated;
    }
}

// Prefer a statically typed boundary when the producer already knows the type:
void consumeTextBox(Box<Text> box) {
    Text text = box.get();
}
```

#### What Java actually guarantees

Java erases the arguments of ordinary generic objects. At runtime,
`Box<Text>` and `Box<NumberValue>` are both only `Box`. Consequently Java:

| Operation                                             | Java behavior                                                  | Ironwood behavior                            |
| ----------------------------------------------------- | -------------------------------------------------------------- | -------------------------------------------- |
| `unknown instanceof Box<?>`                           | Allowed and checks the reifiable raw class.                    | Allowed for reference-shaped instances; false for primitive specializations. |
| `unknown instanceof Box<Text>`                        | Compile-time error because `Text` cannot be tested at runtime. | Compile-time error for the same reason.      |
| `(Box<Text>) unknown` with no proof of `Text`         | Allowed with an unchecked warning; runtime checks only `Box`.  | Compile-time error.                          |
| A cast whose exact generic view is proved from source | Allowed.                                                       | Allowed using Ironwood's closed-world proof. |

The unchecked Java cast is therefore not a complete runtime check. It is a
request for the compiler to trust the programmer despite missing evidence. It
can make a `Box<NumberValue>` appear statically as `Box<Text>`; the mistake may
surface much later as `ClassCastException`, or a write may pollute a structure
observed through another correctly typed alias.

#### Why Ironwood excludes the unchecked escape hatch

Ironwood shares one native layout and implementation for all reference
instantiations of a generic declaration. Its runtime descriptor knows “this is
a `Box`,” but does not carry an arbitrary tuple such as `<Text>`. Given only an
`Object`, the runtime therefore has no evidence with which to validate
`Box<Text>`.

Ironwood technically could copy Java and emit a warning while checking only the
raw `Box`. The project deliberately refuses to do that because it would turn an
unverified programmer assertion into a trusted static type, permit heap
pollution, and move failures away from their cause. Ironwood has no legacy
pre-generics binaries that require this compromise and no warning-suppression
escape hatch intended to normalize it.

Fully reifying generic arguments is also technically possible, but it is a
different and substantially heavier runtime model. Every relevant allocation,
constructor, nested generic view, interface conversion, wildcard capture, and
compiled-library boundary would need type-argument metadata or tokens. That
would change object metadata and the generic ABI, increase retained code/data,
and weaken the simplicity and tree-shaking benefits of the current erased
reference-generic implementation. Ironwood will not pay that pervasive cost
solely to support a test that Java itself does not support.

#### Advantage or disadvantage?

For type safety, this is an **Ironwood advantage**: unverifiable casts fail at
compile time instead of compiling with a warning and potentially failing later.
For source compatibility, it is a **deliberate disadvantage**: some legacy Java,
reflection-heavy frameworks, deserializers, and loosely typed `Object` APIs rely
on unchecked casts and will require redesign.

Programmers can instead retain `Box<?>`, validate contained values and construct
a correctly typed representation, require `Box<Text>` at the API boundary, or
use a cast that Ironwood's closed-world source analysis can prove. These are
safe supported operations, but they do not constitute support for Java's
unchecked cast or non-reifiable test.

<a id="feature-50"></a>

### 50. Raw generic types

Java:

```java
Box raw = new Box("value"); // Legal legacy form, usually with warnings.
Object value = raw.get();
```

Ironwood (raw form not supported):

```java
// Box raw = new Box("value");
// Rejected: every use of a generic type must supply type arguments.
Box<String> box = new Box<String>("value");
```

Java permits raw use so source and binaries written before Java generics can
continue to interoperate, normally with warnings. Ironwood could implement the
same behavior, but it would require unchecked conversions, weaker member
typing, and heap-pollution escape hatches. Ironwood has no pre-generics history
to preserve, so raw types are deliberately excluded. Supply exact type
arguments, or use `<?>` when the argument is genuinely unknown, as the safer
alternative.

<a id="feature-51"></a>

### 51. Primitive generic arguments through native specialization

Java:

```java
Box<Integer> box = new Box<>(42); // int is boxed as Integer.
int value = box.get();            // Integer is unboxed as int.
```

Ironwood:

```java
Box<int> box = new Box<>(42);
int value = box.get();
// int lives directly in specialized storage; no Integer is allocated.
```

All eight Ironwood primitives are valid exact arguments for a type parameter
that has no explicit bound. This applies to classes, interfaces, methods,
constructors, explicit type arguments, ordinary inference, and diamond. An
explicit bound remains reference-only, even `T extends Object`, because member
access and conversion promised by that bound require a reference.

The final closed world specializes only the native shapes it uses. `Box<int>`
receives an `i32` field plus specialized constructor/method bodies;
`Pair<int, String>` uses an `i32` and a pointer. Reference positions continue
to share the existing pointer representation, so `Pair<int, String>` and
`Pair<int, Object>` do not multiply merely because their reference arguments
differ. Static state and one-time initialization still belong to the one source
declaration. Generic arrays receive matching native element layouts and exact
descriptors.

There is no wrapper, tag, generic runtime registry, or loader. A generic body
that only transports, compares, stores, returns, or arrays its value can be
specialized directly. A body that uses `null`, converts the value to `Object`,
invokes an `Object` member, throws the value, or otherwise requires reference
semantics receives a source diagnostic when requested with a primitive shape.
Primitive instantiations are invariant and cannot widen or cast to `Box<?>`;
their wildcard test is false so a value-shaped object cannot be called through
the pointer-shaped wildcard ABI. Source-provable exact casts use the specialized
descriptor identity instead of the excluded raw wildcard membership.

This is classified as an **Ironwood way** because Java does not accept
`Box<int>` at all: Java's corresponding source normally uses `Box<Integer>` and
boxing. Ironwood gains allocation-free value storage and calls, at the cost of
potentially emitting several closed-world native layouts and bodies. Format-1
`.ironclass` files and `.ironjar` archives retain source and reconstruct the
needed shapes at final link rather than promising a stable separately compiled
specialization ABI. Safe `free` continues to track the generic container as an
ordinary reference allocation; primitive contents create no aliases.

Runnable Ironwood example:
[`PrimitiveGenerics.iron`](../examples/primitivegenerics/src/main/ironwood/org/ironwood/primitivegenerics/PrimitiveGenerics.iron).

<a id="feature-52"></a>

### 52. Automatic boxing and unboxing

Java:

```java
int number = 42;
Object object = number; // Implicitly allocates or obtains an Integer wrapper.
int copy = (Integer) object;
```

Ironwood (automatic conversion not supported; explicit code shown):

```java
final class IntValue {
    private int value;
    IntValue(int value) { this.value = value; }
    int value() { return value; }
}

IntValue object = new IntValue(42); // Allocation and owner are visible.
int copy = object.value();
free object;
```

Java automatically converts between primitives and wrapper references, and a
conversion may allocate or obtain a wrapper behind otherwise ordinary-looking
source. In a language without GC, a hidden wrapper created during assignment,
argument passing, return conversion, overload resolution, or inference would
have no reliable source-level owner or reclamation point. Ironwood therefore
will not support automatic boxing or unboxing. Use the primitive directly,
primitive-specialized APIs, or an explicitly constructed and explicitly freed
wrapper when reference identity is genuinely required.

## Arrays, calls, and exceptions

<a id="feature-53"></a>

### 53. One-dimensional invariant arrays

Java:

```java
Dog[] dogs = new Dog[2];
Object object = dogs;
Dog first = dogs[0];
```

Ironwood:

```java
Dog[] dogs = new Dog[2];
Object object = dogs;
Dog first = dogs[0];
free dogs; // Frees the container, never the referenced Dog elements.
```

This base array slice supports one-dimensional primitive, class, interface,
generic-reference, and type-variable arrays. They are zero-initialized,
bounds-checked, identity-bearing references that widen to `Object`. Feature 55
adds recursive array element types without changing these rules.

<a id="feature-54"></a>

### 54. Java array covariance

Java:

```java
Dog[] dogs = new Dog[2];
Animal[] animals = dogs;
animals[0] = new Cat(); // Compiles, then throws ArrayStoreException.
```

Ironwood (covariant conversion not supported):

```java
Animal[] animals = new Animal[2];
animals[0] = new Dog();
animals[1] = new Cat();
// Choose the required element type when allocating the array.
```

Java lets `Dog[]` widen to `Animal[]`, then needs an `ArrayStoreException` check
because another alias can try to store a `Cat` into the underlying `Dog[]`.
That historical pre-generics compromise is statically unsound. Ironwood excludes
array covariance so element typing, aliases, and explicit reclamation remain
predictable. Allocate an `Animal[]` when heterogeneous animal values are
required, or keep the array as `Dog[]` when only dogs are valid.

<a id="feature-55"></a>

### 55. Multidimensional arrays and exact array casts/tests

Java:

```java
Animal[][] grid = new Animal[2][2]; // Allocates the outer and both child arrays.
Object value = new Dog[2];
if (value instanceof Dog[]) {
    Dog[] dogs = (Dog[]) value;
}
```

Ironwood way:

```java
Animal[][] grid = new Animal[2][];
Animal[] firstRow = new Animal[2];
grid[0] = firstRow; // Every child allocation remains visible and named.

Object value = new Dog[2];
if (value instanceof Dog[]) {
    Dog[] dogs = (Dog[]) value;
}

grid[0] = null;
free firstRow;
free grid;
```

Ironwood implements multidimensional arrays as recursive invariant arrays:
`Animal[][]` is exactly an array of `Animal[]` references. An array creation may
size only its outer dimension. `new Animal[2][]` therefore allocates one named
outer container with null child slots; `new Animal[2][2]` is rejected because
it would hide two separately owned child allocations.

Array `instanceof` and checked casts compare the object's exact compiler-emitted
array descriptor at every dimension. They accept reifiable targets such as
`Dog[]`, `int[][]`, and erased `Box<?>[][]`; they reject non-reifiable targets
such as `Box<String>[]`. `null` fails `instanceof` and succeeds as a cast, while
a mismatched checked cast throws `ClassCastException`. No array covariance or
runtime array-store check is introduced.

The safe-`free` proof tracks a child stored in a known local array slot. The
child cannot be freed while the slot still contains it, but a direct overwrite
with `null` proves detachment. Dynamic indices, calls that can observe array
elements, copies, and escaped containers remain conservative. Freeing an outer
array never recursively frees a child. The runnable
[`multidimensionalarrays`](../examples/multidimensionalarrays) example exercises
the exact tests/cast and explicit child lifecycle.

Feature 55 is 💡 rather than ✅ because the complete feature includes
construction: its recursive array types and exact casts/tests use familiar Java
syntax, but Ironwood deliberately rejects Java's multi-allocation
`new T[outer][inner]` form and implements explicit, separately owned child
allocation as the supported Ironwood way.

<a id="feature-56"></a>

### 56. Varargs

Java:

```java
static int count(String... values) { return values.length; }
int result = count("one", "two"); // Compiler creates a hidden String[].
```

Ironwood (varargs not supported; an ordinary array shown):

```java
// static int count(String... values) { return values.length; }
// Rejected: "varargs are not supported; declare an explicit array parameter instead".

static int count(String[] values) { return values.length; }
String[] values = new String[2];
int result = count(values); // Allocation and lifetime are visible to the caller.
free values;
```

Java rewrites each expanded call into an implicit array construction. The
problem for Ironwood is reclamation, not declaration syntax: the caller has no
name for that fresh allocation, the callee may retain or return it, and the
compiler cannot generally insert `free` after the call. A hot call site could
therefore allocate indefinitely. Pooling adds alias, recursion, and reentrancy
rules without solving escaping arrays. Ironwood excludes varargs. Fixed-arity
overloads and ordinary explicit arrays may let an application be rewritten, but
they are not varargs declarations or expanded varargs calls and do not make
Feature 56 supported.

<a id="feature-57"></a>

### 57. Exceptions as `Throwable` objects

Java:

```java
class Failure extends RuntimeException { }

try {
    throw new Failure();
} catch (RuntimeException failure) {
    recover(failure);
}
```

Ironwood:

```java
class Failure extends RuntimeException { }

try {
    throw new Failure();
} catch (RuntimeException failure) {
    recover(failure);
}
// Matching uses the Ironwood nominal hierarchy during native unwinding.
```

Runnable Ironwood example: [`ThrowableTypes.iron`](../examples/throwabletypes/src/main/ironwood/org/ironwood/throwabletypes/ThrowableTypes.iron).

`Throwable.toString()` includes the concrete class name and non-null localized
message, including through printing, concatenation, and builder append. Its
default `getLocalizedMessage()` delegates to the virtual `getMessage()`.
See the [T3 compatibility review](STDLIB_THROWABLE_REVIEW.md) for the contract,
native ownership, and [D121 review](STDLIB_STACK_TRACE_REVIEW.md) for public trace printing.
D122 adds single-assignment `initCause` and fresh caller-owned
`getStackTrace()` arrays whose immutable elements live in compiler storage.

<a id="feature-58"></a>

### 58. Checked exceptions and `throws` declarations

Java:

```java
class CheckedFailure extends Exception { }
class Reader {
    String read() throws CheckedFailure { throw new CheckedFailure(); }
}
```

Ironwood (implemented):

```java
class CheckedFailure extends Exception { }
class Reader {
    String read() throws CheckedFailure { throw new CheckedFailure(); }
}
// Callers must catch or declare CheckedFailure.
```

Ironwood classifies a `Throwable` subtype as checked unless it derives from
`RuntimeException` or `Error`. A checked exception raised explicitly or listed
by the compile-time selected method or constructor must be handled by an
enclosing compatible catch or by the current callable's `throws` clause.
Declarations are supported on class and interface methods and constructors;
throwable-bounded method type variables may appear in `throws` and are
substituted after generic invocation inference.

An overriding method may omit or narrow checked exceptions but may not add a
broader or unrelated checked exception. Checked catches that cannot match any
checked exception from their try body are rejected; unchecked catch targets
remain legal without a statically declared source. Format-1 `.ironclass`
payloads and `.ironjar` archives preserve the source declaration, so source-
path, classpath, archive, and final closed-world link analysis enforce the same
contract. The existing native unwind representation is unchanged: checkedness
is entirely compile-time, while implicit runtime/library failures remain
unchecked or retain their documented fail-fast behavior.

Runnable Ironwood example:
[`CheckedExceptions.iron`](../examples/checkedexceptions/src/main/ironwood/org/ironwood/checkedexceptions/CheckedExceptions.iron).
Multi-catch and precise rethrow are implemented under Feature 92. Exception
causes and Java-style suppression are separate from Feature 58. Java-shaped
uncaught and public stack traces are implemented under Feature 73, D054, D121,
and D122.

## Deliberate differences and deferred Java features

<a id="feature-59"></a>

### 59. General annotations

Java:

```java
@interface Audited { }

@Audited
class PaymentService { }
```

Ironwood (annotations not supported; an ordinary interface shown):

```java
interface Audited { }

class PaymentService implements Audited { }
// Express behavior through types, keywords, configuration, or generated source.
```

Java annotations can attach user-defined metadata to declarations for compiler,
processor, framework, or runtime-reflection consumption. Ironwood deliberately
excludes annotation declarations, processing, retention policies, and reflective
annotation metadata because they would create an open-ended metadata and code-
generation subsystem. Use ordinary interfaces and language features for type
contracts, explicit configuration for data, or build-time source generation
when code generation is truly needed. Override intent and native test
registration use the narrow built-in `@Override` and `@Test` directives. They
do not introduce a general annotation grammar or annotation semantics. See the
next section for `@Override` and [Testing Ironwood code](TESTING.md) for
`@Test`.

<a id="feature-60"></a>

### 60. Mandatory `@Override` directive

Java:

```java
class Child extends Base {
    @Override
    int value() { return 42; }
}
```

Ironwood way:

```java
class Child extends Base {
    @Override int value() { return 42; }
}
```

Ironwood recognizes the exact, case-sensitive spelling `@Override` as a
built-in method directive, not as an instance of general annotation syntax. The
lexer keeps `@` separate and the modifier parser recognizes `Override`
contextually, leaving the name available in ordinary source contexts. Forms
such as `@Override()`, qualified names, user-defined annotations, annotation
arguments, and placing the directive on a type, field, constructor, or
initializer are rejected.

Unlike Java, the directive is mandatory. Every source method declaration that
overrides an inherited instance method or implements or redeclares an inherited
interface method must carry `@Override`; omission is a compiler error. A method
carrying `@Override` must have a valid inherited instance target; a typo, an
unrelated new method, or a static method therefore produces a compiler error.
Ordinary structural rules still diagnose final-method overrides, incompatible
returns, reduced visibility, and static/instance mismatches precisely. A class
that merely inherits an implementation satisfying an interface declares no new
method and consequently has no directive to write.

`@Override` may be on the method's line or on a preceding line and may be
interleaved with ordinary method modifiers; whitespace and comments do not
change its meaning. It applies to class, interface, abstract, default,
anonymous-class, and enum constant-body method declarations. Compiler-generated
methods are exempt because they have no source declaration.

Runnable Ironwood example:
[`OverrideDirective.iron`](../examples/overridedirective/src/main/ironwood/org/ironwood/overridedirective/OverrideDirective.iron).

<a id="feature-61"></a>

### 61. Enums

Java:

```java
enum State {
    READY, RUNNING, DONE
}
```

Ironwood:

```java
enum State {
    READY, RUNNING, DONE
}
```

Enums are distinct final nominal reference types whose direct superclass is
the Java-shaped `Enum<E>` base. Top-level and implicitly static member
enums may implement interfaces and declare fields, initialization blocks,
methods, member types, and private constructors. Constants are constructed in
declaration order before user static actions. Each constant is an immortal
compiler-emitted singleton, so identity, declaration order, constructor state,
casts, `instanceof`, interfaces, arrays, and reference-generics work without an
ordinary allocation or change to `System.allocationCount()`.

Every enum provides `name()`, `ordinal()`, default `toString()`,
`valueCount()`, `valueAt(int)`, `valueOf(String)`, and `values()`. Invalid
index/name and null name lookup throw `IllegalArgumentException`. Each
`values()` call returns a fresh mutable caller-owned array in declaration order;
the caller may reclaim that shallow array without reclaiming its immortal enum
elements. Ordered traversal may instead use allocation-free `valueCount()` and
`valueAt(int)`. Source cannot use `new` on an enum, subclass one, assign its
constant fields, declare a generic or local enum, or expose a public/protected
enum constructor. Feature 74 adds constant-specific bodies as compiler-owned
final subtypes.

Classic enum `switch` uses unqualified constants of the selector's exact enum
type. It evaluates and null-checks the selector once, dispatches on immutable
ordinal, and retains D056 fallthrough/scope/transfer rules without adding
exhaustiveness or pattern semantics.

Runnable Ironwood example:
[`Enums.iron`](../examples/enums/src/main/ironwood/org/ironwood/enums/Enums.iron).

Feature 61 is ✅ because declarations, construction, members, the shared base,
fresh-array `values()`, and switching use the familiar Java source model. The
additional allocation-free `valueCount()`/`valueAt(int)` traversal is an
Ironwood extension. Constants remain immortal, only enum declarations may
derive from `Enum<E>`, and callers explicitly reclaim a returned `values()`
array when its lifetime ends.

<a id="feature-62"></a>

### 62. Records

Java:

```java
record Point(int x, int y) { }
```

Ironwood (records not supported; an ordinary class shown):

```java
final class Point {
    private final int x;
    private final int y;
    Point(int x, int y) { this.x = x; this.y = y; }
    int x() { return x; }
    int y() { return y; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof Point)) { return false; }
        Point point = (Point) other;
        return x == point.x && y == point.y;
    }
    @Override public int hashCode() { return x * 31 + y; }
}
```

Java records synthesize final component fields, accessors, construction,
equality, hashing, and string representation for a data-carrier declaration.
Ironwood will not support that compiler-synthesis feature unless the project
explicitly reverses the decision: records are not required for OOP completeness
and hide important object-policy choices. An application can write an ordinary
final class with explicit accessors, equality, hashing, copying, and formatting,
but that is not record support.

<a id="feature-63"></a>

### 63. Sealed classes and interfaces

Java:

```java
sealed interface Result permits Success, Failure { }
final class Success implements Result { }
final class Failure implements Result { }
```

Ironwood (sealed types not supported; a limited class idiom shown):

```java
abstract class Result {
    private Result() { }

    static final class Success extends Result {
        Success() { super(); }
    }

    static final class Failure extends Result {
        Failure() { super(); }
    }
}
// The private base constructor limits subclasses to this source nest.
```

Java's `sealed` and `permits` syntax lets an API name the only source types that
may extend or implement a base. Ironwood deliberately excludes this additional
type-taxonomy syntax: the native linker already knows the complete hierarchy for
optimization, but that knowledge does not enforce a source-level API contract.
An abstract base with a private constructor and nested final implementations can
limit construction in one particular class design, but it does not implement
Java's general sealed hierarchy contract. An interface cannot express an
identical public extension restriction in Ironwood.

<a id="feature-64"></a>

### 64. Lambdas, closures, and method references

Java:

```java
interface Action { int run(); }
Action lambda = () -> 42;
Action reference = service::value;
```

Ironwood (lambdas, closures, and method references not supported):

```java
interface Action { int run(); }
Action action = new Action() {
    @Override public int run() { return 42; }
};
int result = action.run();
free action; // The generated object has an explicit source owner.
// Lambda, closure, and method-reference syntax is deliberately excluded.
// Anonymous classes are the explicit object-oriented alternative.
```

Java converts lambda and method-reference expressions to target functional-
interface instances and may create hidden objects containing captured values.
Those invisible objects create ownership and reclamation questions while adding
a separate target-typing and invocation model. Ironwood deliberately excludes
that syntax and semantics. An explicitly allocated anonymous class can sometimes
be used to rewrite an application, but it is a separate supported feature, not
lambda, closure, or method-reference support.

<a id="feature-65"></a>

### 65. Classic `switch`

Java:

```java
static final int READY = 1;
static final int RUNNING = 2;

int code(int state) {
    switch (state) {
        case READY: return 1;
        case RUNNING: return 2;
        default: return 3;
    }
}
```

Ironwood:

```java
static final int READY = 1;
static final int RUNNING = 2;

int code(int state) {
    switch (state) {
        case READY: return 1;
        case RUNNING: return 2;
        default: return 3;
    }
}
```

Classic colon-form `switch` is implemented for `byte`, `short`, `char`, and
`int`. The selector is evaluated once. Case labels are side-effect-free
compile-time integral constants compatible with the selector; accessible
`static final` primitive constants and final local constants are accepted,
duplicate converted values are rejected, and there may be at most one
`default`. Referencing a compile-time static constant as a label does not
initialize its declaring type.

Consecutive labels share a statement group. Execution enters the selected
group and falls through later groups in source order until an abrupt statement
or the closing brace; `break` exits the nearest loop or switch, while
`continue` still targets the nearest enclosing loop. The switch body is one
lexical scope, so direct entry at a later label cannot read a value declared in
an earlier group unless every incoming path initialized it. Return analysis
accounts for default/no-match paths and abrupt groups.

D056 preserves labels and spans in the AST, lowers dispatch into a dedicated
typed `IrSwitchTerminator`, and maps that terminator mechanically to LLVM
`switch`. The behavior is stable from `-O0` through `-O3` and reconstructs from
source paths, class directories, individual `.ironclass` files, and `.ironjar`
archives. It adds no runtime API, allocation, reflection metadata, or class
loading. D057 extends the same terminator to exact enum selectors after one
null check and hidden ordinal load; labels are unqualified constants of that
selector type. Modern non-pattern forms are recorded separately by Feature 91,
reference type patterns in `switch` by Feature 106, and other pattern families
by Features 66, 107, and 108.

Runnable Ironwood example:
[`ClassicSwitch.iron`](../examples/classicswitch/src/main/ironwood/org/ironwood/classicswitch/ClassicSwitch.iron).

<a id="feature-66"></a>

### 66. Reference type patterns for `instanceof`

Java:

```java
if (value instanceof Dog dog) {
    return dog.sound();
}

if (!(value instanceof Cat cat)) {
    return 0;
}
return cat.sound(); // Flow analysis knows that cat matched.
```

Ironwood:

```java
if (value instanceof Dog dog && dog.sound() > 0) {
    return dog.sound();
}

if (!(value instanceof final Cat cat)) {
    return 0;
}
return cat.sound();
```

Feature 66 implements Java's stable named reference type pattern in the right
operand of `instanceof`. The operand is evaluated once; a successful non-null
match both tests the value and initializes a flow-scoped local variable. An
optional `final` modifier gives the binding the same assignment restriction as
other final locals.

Definite-match scope follows `!`, `&&`, `||`, and conditional-expression arms.
It also covers the matching `if`/`else` branch, a negated guard's following
statements when the other branch cannot complete normally, loop bodies and
updates, and the code after a negated `while` or basic `for` condition when no
reachable loop `break` can bypass the match. Conflicting same-name bindings on
overlapping boolean paths and uses outside definite-match scope are diagnosed.

The binding is a typed SSA alias of the existing nominal-membership or exact-
array-descriptor test result. It introduces no allocation, repeated operand
evaluation, runtime registry, ownership transfer, or new C ABI. Reifiable
nominal, `G<?>`, and recursively reifiable array targets are accepted; concrete
or bounded parameterizations, type variables, primitives, unnamed `_`
bindings, record patterns, primitive patterns, and pattern switch remain
outside this feature. Safe-`free` tracks the matched alias as the same source
allocation, including reclaiming a fresh `new` through the in-scope binding.
Local and anonymous classes may capture a final or effectively-final matched
binding under the existing capture rules. Format-1 `.ironclass` source payloads
and `.ironjar` inputs reconstruct the same flow rules at final link.

This feature no longer includes any form of `switch`. Feature 91 covers modern
non-pattern switch rules and expressions, while Feature 106 covers reference
type patterns in `switch`. Record and unnamed patterns are Feature 107, and Java
SE 26's preview primitive-pattern family is Feature 108. D075 marks Feature 66
complete and selects no subsequent implementation target. See
[JLS 15.20.2](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.20.2)
and [JLS 6.3.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-6.html#jls-6.3.1).

Runnable Ironwood example:
[`InstanceOfPatterns.iron`](../examples/instanceofpatterns/src/main/ironwood/org/ironwood/instanceofpatterns/InstanceOfPatterns.iron).

<a id="feature-67"></a>

### 67. Runtime reflection

Java:

```java
Class<?> type = value.getClass();
Method method = type.getMethod("run");
Object result = method.invoke(value);
```

Ironwood (runtime reflection not supported):

```java
// No Class<?> runtime object, Method.invoke, reflective field mutation, or
// reflective construction exists. Call known interfaces or methods directly.
Runner runner = (Runner) value;
int result = runner.run();
```

Java exposes runtime class/member objects and can discover, invoke, construct,
or mutate by name. Static compilation does not make that theoretically
impossible, but Ironwood deliberately will not emit the extensive metadata and
generic invocation stubs it requires. They would enlarge the mandatory runtime,
retain otherwise dead members, weaken static analysis, and undermine tree
shaking. Use known interfaces, explicit registries, or build-time generated
direct calls instead.

<a id="feature-68"></a>

### 68. Runtime class loading and dynamic proxies

Java:

```java
Class<?> plugin = Class.forName(name);
Object proxy = Proxy.newProxyInstance(loader, interfaces, handler);
```

Ironwood (runtime loading and dynamic proxies not supported):

```java
// Class.forName(...), user class loaders, and runtime-generated proxies do not
// exist. Every implementation must be present when the native program links.
Plugin plugin = registry.select(name); // An ordinary closed-world design.
```

Java can load a previously unknown class by name and generate a proxy class
during execution. Ironwood excludes both because its AOT contract fixes every
reachable type and implementation at native link time. Use an explicit
closed-world registry or ordinary hand-written adapter/proxy class; the compiler
can then see, validate, optimize, and retain exactly those candidates.

<a id="feature-69"></a>

### 69. Static initializer blocks and automatic class initialization

Java:

```java
class Registry {
    static Service service;
    static { service = discoverService(); }
}
```

Ironwood:

```java
class Registry {
    static Service service;
    static { service = discoverService(); }
}
// The field initializer and block run once before the first active use.
```

Named classes support static initializer blocks, and classes and interfaces may
use runtime-valued static field initializers. Each retained type has private
one-time state. Entry into `main`, construction, a static-method call, or a
nonconstant static-field read or write initializes the declaring type before the
active operation. Compile-time `static final` primitive constants remain exempt.

Initialization first handles the superclass and the source-order, depth-first
default-method interface prerequisites, then executes the type's field
initializers and blocks in textual order. Reentrant cycles return from a type
already being initialized and may observe zero/null or partial state. If an
unchecked exception escapes, the type caches and rethrows that exact object on
later active uses; checked failures must be caught inside the initializer.
Member, static nested, local, and other named nested types initialize
independently. The implementation uses compiler-owned typed IR and private
closed-world LLVM state, not a class loader, reflection registry, or JVM linkage
phase. With no threads, no initialization lock protocol is required.

Runnable Ironwood example:
[`StaticInitialization.iron`](../examples/staticinitialization/src/main/ironwood/org/ironwood/staticinitialization/StaticInitialization.iron).

<a id="feature-70"></a>

### 70. Threads, object monitors, and `synchronized`

Java:

```java
int readSafely() {
    synchronized (lock) {
        return value;
    }
}
```

Ironwood (shared-memory threads and monitors not supported):

```java
interface Task { void run(); }

void runSequentially(Task[] queue) {
    int index = 0;
    while (index < queue.length) {
        queue[index].run();
        index++;
    }
}
// Use one event loop, or multiple isolated native processes when needed.
```

Java can execute shared-memory threads concurrently and coordinate them through
object monitors, `synchronized`, `wait`/`notify`, atomics, and concurrent
collections. Ironwood excludes that entire shared-memory threading model for
the foreseeable future, avoiding a concurrency memory model and monitor runtime.
There is no equivalent parallel workaround inside one Ironwood process; design
programs around a single-threaded event loop for now.

<a id="feature-71"></a>

### 71. Java serialization, cloning, and finalization

Java:

```java
class Snapshot implements Serializable, Cloneable {
    protected Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    protected void finalize() { closeNativeHandle(); }
}
output.writeObject(snapshot);
```

Ironwood (these Java mechanisms are not supported):

```java
class Snapshot {
    private int version;

    Snapshot(int version) {
        this.version = version;
    }

    Snapshot(Snapshot source) {
        this.version = source.version; // This type explicitly chooses its copy semantics.
    }

    Snapshot copy() {
        return new Snapshot(this);
    }

    byte[] encode() {
        return SnapshotFormat.encode(this); // Explicit, schema-aware format.
    }

    void close() {
        closeNativeHandle(); // Ordinary type-owned method; not inherited from Object.
    }
}
```

These Java facilities have different meanings but share the same ❌ status:

- Java object serialization automatically writes and reconstructs object graphs
  through `Serializable` plus runtime/private-field machinery. Ironwood has no
  Java-compatible serialization facility. An application may write a
  schema-specific encoder, but that is ordinary application code, not language
  or library support for Java serialization.
- `Object.clone()` allocates a field-by-field copy without running an ordinary
  constructor and uses `Cloneable` to authorize that machinery. Ironwood has no
  inherited `Object.clone()`, `Cloneable`, compiler-generated copying, or runtime
  clone operation. A type may instead expose an ordinary copy constructor,
  `copy()`, or a domain-specific name such as `duplicate()`. That API must define
  whether referenced state is shared or copied, how external resources are
  handled, and who owns and may `free` each resulting allocation.

  `clone` is not a reserved or privileged method name. A class may declare a
  normal method named `clone()`, but it has only the access, dispatch, override,
  exception, allocation, and safe-`free` behavior written in ordinary Ironwood
  source. It does not inherit a field-copying implementation or acquire any
  relationship to `Object` or `Cloneable`. Future array or collection copying
  APIs may be added for concrete library needs, but they will be explicit
  ordinary APIs rather than a universal cloning protocol. Feature 71 therefore
  remains ❌: application-defined copying is not support for Java cloning.
- Finalization lets a garbage collector invoke an object's finalizer after the
  object becomes unreachable. Ironwood has no collector and therefore no
  finalizer trigger or finalization mechanism. Ironwood's source destructor is
  instead invoked only by a compiler-accepted explicit `free`; it is never
  reachability-triggered and therefore does not implement Java finalization.
  Calling a resource type's own `close()` method in `finally` can provide
  deterministic external-resource cleanup, but `close()` is not an `Object`
  member or a universal runtime hook and is distinct from both finalization and
  object-memory destruction.

<a id="feature-72"></a>

### 72. Try-with-resources

Java:

```java
try (InputStream input = Files.newInputStream(path)) {
    return input.read();
}
```

Java's construct both declares the resource and installs compiler-generated
cleanup. If the body throws `A` and `close()` throws `B`, Java propagates `A`
and records `B` in `A`'s suppressed-exception list. That is the right failure
priority, but Java applies it only to try-with-resources; the equivalent
ordinary `try`/`finally` still discards `A` and propagates `B`.

Ironwood way, using an application-defined resource type:

```ironwood
Resource resource = new Resource(1, false);
try {
    use(resource);
} finally {
    try {
        resource.close();
    } finally {
        free resource;
    }
}
```

Ironwood deliberately excludes the complete `try (...)` resource construct,
including Java's declaration form and the shorter existing-variable form. A
resource is created by an ordinary declaration, used in an ordinary `try`, and
closed by an ordinary `finally`. Allocation, ownership, cleanup order, and the
reclamation point therefore remain visible in normal statement order. The
nested cleanup above keeps the two operations distinct: `close()` performs the
resource-specific cleanup, while compiler-proven `free` reclaims the wrapper
even if `close()` throws. The same ownership-proven cleanup works when `break`,
`continue`, or `yield` exits the protected region.
The effect of a resource-specific `close()` method is defined by that type; it
commonly releases an external resource, but never inherently implies `free`,
recursive reclamation, or garbage collection. A checked exception declared by
`close()` is caught or declared like any other checked call.

Ironwood also changes the exception-versus-exception rule that made old-style
Java cleanup unsafe. If the protected body throws `A` and the `finally` block
then throws `B`, `A` remains the primary exception. `B` is appended to an
ordered list of secondary exceptions associated with `A`, and propagation of
`A` continues. The list is distinct from the cause chain: the later cleanup
failure did not cause the earlier body failure. If there was no pending
exception when `finally` began, its exception is the primary exception as
usual.

| Protected body | `finally` block | Ironwood result |
| -------------- | --------------- | --------------- |
| completes      | completes       | Continue normally. |
| throws `A`     | completes       | Propagate `A`. |
| completes      | throws `B`      | Propagate `B`. |
| throws `A`     | throws `B`      | Propagate `A` with `B` recorded as a secondary exception. |

Runnable four-program Ironwood example:
[`trycatchfinallyexception`](../examples/trycatchfinallyexception/README.md).
Its throwing executables catch the propagated exception in `main` long enough
to verify its type and secondary list, then rethrow the same primary object so
Feature 73 prints the preserved trace. Their run scripts expect status `1` and
compare the complete diagnostic; the normal case prints its result and exits
with status `42`.

Later failures from nested cleanup regions are flattened onto the primary's
list in occurrence order, so the first failure remains primary while no failure
is lost. Each exception
may still have its own genuine cause. This rule applies to exceptions from any
statement in `finally`; it does not recognize `close()` specially. Cleanup that
must continue after an earlier cleanup failure still requires a nested
`try`/`finally`, because ordinary statements after a throwing statement are not
executed. Safe-free analysis follows those same paths: compiler-generated
copies of a cleanup body receive independent ownership state, and states are
merged only where control flow truly rejoins. Conflicting escape or alias states
remain a compile-time rejection rather than becoming a runtime double-free
guard.

This is a better fit for Ironwood than Java's try-with-resources design because
it fixes the underlying `finally` behavior instead of adding a second cleanup
construct. It keeps allocation and cleanup explicit, preserves the first and
normally most relevant failure, retains every later failure for diagnostics,
and does not misuse exception causality. The tradeoff is more source text than
Java's compact resource header, especially for several resources.

`Throwable.getSecondaryExceptionCount()` reports how many later failures were
retained, and `getSecondaryException(int)` reads them by occurrence order
without allocating an array. Association is compiler-controlled; there is no
public mutator. An invalid index terminates with a deterministic bounds
diagnostic. The native runtime keeps the ordered association in runtime-private
metadata that does not count as an Ironwood allocation, while uncaught
diagnostics print the primary and each secondary with its optional message and
independently captured source trace.

The parser rejects either form of `try (...)` with a migration diagnostic.
`ironwood.lang.AutoCloseable` is the standard-library interface that declares
`close()` for generic resource contracts; `ironwood.lang.Object` does not.
Implementations define the method's cleanup behavior, and neither the interface
nor the method name receives special compiler behavior. The application-defined
`Resource` shape above is demonstrated by
[`DeterministicResources.iron`](../examples/resources/src/main/ironwood/org/ironwood/resources/DeterministicResources.iron).

The U3 library also implements `ironwood.io.Closeable` and byte/character
streams. Their `close()` releases external resources while a separate proven
`free` reclaims the stream object and its owned buffers. Borrowed wrappers must
be freed before their underlying objects; closing a wrapper alone does not
release that dependency. The runnable
[`streaming`](../projects/streaming/README.md) project demonstrates nested
cleanup for cat, wc, cp, and an interactive prompt. The standalone
[`minitee`](../projects/minitee/README.md) project adds a custom `OutputStream`
decorator that borrows two outputs and reuses one copy buffer across abstract
stream calls, with append mode and ordered failure cleanup.

<a id="feature-73"></a>

### 73. Uncaught exception stack traces

Java:

```text
example.ReadFailure: read failed
    at example.Reader.read(Reader.java:18)
    at example.Service.load(Service.java:31)
    at example.Main.main(Main.java:9)
```

Ironwood:

```text
uncaught Ironwood exception: org.ironwood.example.ReadFailure: read failed
    at org.ironwood.example.Reader.read(Reader.iron:18)
    at org.ironwood.example.Service.load(Service.iron:31)
    at org.ironwood.example.Main.main(Main.iron:9)
```

An uncaught Ironwood exception prints `uncaught Ironwood exception:
<qualified-type>`, appends its message when non-null, prints its frames from the
failure outward, then reports directly associated secondary exceptions in
occurrence order with each one's own message and trace. The process exits with
status 1. D121 captures traces during ordinary Throwable construction through
virtual `fillInStackTrace()`, including exceptions never thrown. Rethrows preserve
that snapshot; explicit refresh replaces it and returns the same receiver.
Throwable constructor/fill frames are omitted. Other constructors are named
`<init>` and nested/anonymous owners keep their `$`-qualified names.

`e.printStackTrace()` writes the description and trace to `System.err` without
the fatal prefix or process exit. The PrintStream overload accepts the existing
stdout/stderr streams. Virtual descriptions and causes, common frame suffixes,
and cycles are handled; Ironwood cleanup failures use `Secondary:`. A no-op
`fillInStackTrace` override can keep a Throwable stackless. See the
[public trace review](STDLIB_STACK_TRACE_REVIEW.md).

D132 supersedes D054's shadow-frame mechanism with on-demand native unwind and
LLVM pseudo-probe decoding. Ordinary calls and returns execute no trace
bookkeeping instructions. The read-only metadata yields the same source-level
report from `-O0` through `-O3`, including when LLVM inlines native code, and
after source-path, `.ironclass`, or `.ironjar` reconstruction. Only the stable `.iron`
basename is retained, not an absolute build path. Runtime trace allocations do
not count as Ironwood allocations, and metadata failure degrades to `<trace
unavailable>` without changing propagation. Private trace storage is released
with compiler-approved Throwable reclamation or failed construction.
`getStackTrace()` returns a fresh caller-owned array of immutable
compiler-emitted StackTraceElement values. Mutable replacement remains absent;
failures outside compiler-defined source boundaries remain separate from
language exception delivery.

Feature 105's reusable emergency error extends
the ordinary construction-time rule. Each automatic allocation-failure occurrence may
refresh that singleton's bounded emergency trace after an earlier occurrence is
no longer unwinding; rethrows within one occurrence preserve its failing site.
A retained alias therefore denotes the singleton and may observe metadata from
a later automatic occurrence rather than a permanently distinct failure object.

Runnable Ironwood example:
[`StackTraces.iron`](../examples/stacktraces/src/main/ironwood/org/ironwood/stacktraces/StackTraces.iron).

<a id="feature-74"></a>

### 74. Enum constant-specific class bodies

Java:

```java
interface Operation {
    int apply(int a, int b);
    String symbol();
}

enum BasicOperation implements Operation {
    ADD("+", "Addition") {
        @Override public int apply(int a, int b) { return a + b; }
    },
    SUBTRACT("-", "Subtraction") {
        @Override public int apply(int a, int b) { return a - b; }
    },
    MULTIPLY("*", "Multiplication") {
        @Override public int apply(int a, int b) { return a * b; }
    };

    private final String symbol;
    private final String description;

    BasicOperation(String symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    @Override public String symbol() { return symbol; }
    public String description() { return description; }
}
```

Ironwood:

```java
enum BasicOperation implements Operation {
    ADD("+", "Addition") {
        @Override public int apply(int a, int b) { return a + b; }
    },
    SUBTRACT("-", "Subtraction") {
        @Override public int apply(int a, int b) { return a - b; }
    },
    MULTIPLY("*", "Multiplication") {
        @Override public int apply(int a, int b) { return a * b; }
    };

    private final String symbol;
    private final String description;

    BasicOperation(String symbol, String description) {
        this.symbol = symbol;
        this.description = description;
    }

    @Override public String symbol() { return symbol; }
    public String description() { return description; }
}
```

Feature 74 accepts the class body after an individual constant. Following
[JLS 8.9.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.9.1),
each bodied constant has a compiler-owned final anonymous subtype of the
enum. The constant's visible field retains the enum type, while its object uses
the subtype's layout, type descriptor, initialization, and dispatch entries.
The compiler checks each constant as a concrete type, so an interface method
may be supplied separately by every constant body even when the enum body has
no shared implementation. An abstract instance method may likewise be declared
in the enum body only when every constant body supplies it. Members unique to a
constant body are not visible through the enum-typed constant field; callable
body methods must override an accessible enum or interface member.

The body supports the ordinary Ironwood anonymous-class member surface:
instance fields and initializer blocks, methods, and member types. It cannot
declare a constructor, abstract method, or static initializer block. Hidden
construction first runs the selected private enum constructor and then the
subtype's field and block initialization. Constants remain immortal and
allocation-count neutral; construction failure retains exact-object caching and
source traces, source code still cannot subclass an enum, and this internal
finite hierarchy does not add source-level sealed classes.

Typed IR records both the declared enum type and concrete storage type. LLVM
emits the concrete immortal layout and descriptor, while lookup, traversal,
identity, ordinal switch, virtual/interface dispatch, safe-`free`, tree shaking,
and source/class/archive reconstruction retain their established contracts.

Runnable Ironwood example:
[`Enums.iron`](../examples/enums/src/main/ironwood/org/ironwood/enums/Enums.iron).

<a id="feature-75"></a>

### 75. Double-quoted String literals and literal pooling

Java:

```java
String first = "hello";
String second = "hello";
boolean same = first == second; // true
```

Ironwood:

```java
String first = "hello";
String second = "hello";
boolean same = first == second; // true
```

Ironwood already decodes each supported double-quoted literal at compile time
and pools equal decoded values once across the final linked closed world,
including source, `.ironclass`, and `.ironjar` inputs. Each retained value is one
immutable, immortal UTF-16 `ironwood.lang.String` object in compiler-emitted
storage. Repeated literals therefore have the same identity, do not affect
`System.allocationCount()`, and cannot be passed to `free`. Content operations
remain Java-shaped: `length()` and `charAt(int)` use UTF-16 code units,
`equals(Object)` and `hashCode()` use content, and `==` remains reference
identity. `byteLength()` is an Ironwood UTF-8 interop extension.

This matches the canonical-identity result of Java literal interning described
by [JLS 3.10.5](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.5),
but Ironwood performs it as a final-link compiler operation rather than a
runtime method call. Ironwood's ordinary literal syntax has a smaller escape
set than Java; Feature 75 records that double-quoted form, while Feature 78 adds
cooked and raw text blocks whose decoded values enter the same finite pool.

Runnable Ironwood example:
[`ArraysStringsAndIo.iron`](../examples/foundations/src/main/ironwood/org/ironwood/foundations/ArraysStringsAndIo.iron).

<a id="feature-76"></a>

### 76. String concatenation with `+` and `+=`

Java:

```java
String result = a + " + " + b + " = " + (a + b);
```

Ironwood:

```java
String result = a + " + " + b + " = " + (a + b);
```

Feature 76 implements Java-shaped String `+` and corresponding `+=`. When either
binary operand has type `String`, the other operand receives String conversion:
`null` becomes `"null"`, primitive values receive their ordinary text form, and
a non-null reference uses `toString()`. Grouping remains left-associative,
numeric subexpressions remain numeric until a String operand is reached, and
operands are evaluated exactly once from left to right. Full support includes
all Ironwood primitive types, including Java-matching float/double text for
finite values, signed zero, infinities, and NaN. It does not introduce automatic
boxing.

String `+=` evaluates its target once and stores the newly allocated or folded
String under the existing assignment rules. It does not implicitly reclaim the
previous value; as with any overwritten reference, source code must perform any
provable `free` before discarding an owned ordinary allocation.

A concatenation that is a compile-time String constant expression is folded
into Feature 75's final-program literal pool. A dynamic concatenation produces a
new ordinary immutable `String` allocation and is not pooled. Ironwood
evaluate and convert the operands in order, compute the exact UTF-16 result
length, and write one source-visible result object directly. A maximal
uninterrupted concatenation chain shares that one result allocation; it does
not lower through a hidden ordinary `StringBuilder` or hidden backing array.
Compiler allocation provenance identifies the result, so a nonescaping local
may satisfy safe-`free`; an escaping result remains allocated under the existing
rules.

The compiler represents dynamic chains with a dedicated typed-IR instruction.
LLVM lowers it to an ordered descriptor array and a native two-pass operation
that validates the total length, allocates the exact String object once, and
writes its UTF-16 tail. Source, class-directory, individual-class, and archive
inputs preserve the behavior; tree shaking does not retain `StringBuilder` just
because concatenation is used. Native tests cover `-O0` through `-O3`, constant
identity, conversions, ordering, allocation counts, and safe `free`. Java
likewise distinguishes pooled constant expressions from newly created dynamic
concatenation results; see
[JLS 15.18.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.18.1)
and [JLS 12.5](https://docs.oracle.com/javase/specs/jls/se26/html/jls-12.html#jls-12.5).

Runnable Ironwood example:
[`StringConcatenation.iron`](../examples/stringconcatenation/src/main/ironwood/org/ironwood/stringconcatenation/StringConcatenation.iron).

<a id="feature-77"></a>

### 77. Runtime `String.intern()`

Java:

```java
String canonical = runtimeValue.intern();
```

Ironwood (`String.intern()` deliberately unsupported):

```java
// String canonical = runtimeValue.intern();
```

Java's `String.intern()` adds an arbitrary runtime String to a privately held
canonical pool when no equal entry exists. Ironwood does not expose that API.
Adding an ordinary reclaimable String to a process-global pool would create a
permanent hidden alias, invalidate local reclamation, and allow input-dependent
unbounded retention. It would also require runtime pool state unrelated to the
compiler's finite literal set. Java relies on garbage collection to manage the
resulting pool and String lifetimes; Ironwood deliberately has no collector to
remove unreachable pool entries. Feature 75 already provides canonical identity
where the compiler can prove and emit it, and Feature 76 adds folded String
constant expressions to the same finite pool.

Programs that genuinely need runtime canonicalization should use an explicit,
application-owned interner with a chosen capacity, lifetime, and reclamation
policy. Such a reusable collection may be considered during standard-library
design, but it would not change Feature 77 or masquerade as Java's global
`String.intern()` contract. The Java API contract is documented by
[`String.intern()`](https://docs.oracle.com/en/java/javase/26/docs/api/java.base/java/lang/String.html#intern()).

<a id="feature-78"></a>

### 78. Cooked and raw text blocks

Java:

```java
String trailing = """
    Hello
    world
    """;  // "Hello\nworld\n"

String leading = """

    Hello
    world""";  // "\nHello\nworld"
```

Ironwood:

```java
String trailing = """
    Hello
    world
    """;  // "Hello\nworld\n"

String leading = """

    Hello
    world""";  // "\nHello\nworld"

String regex = r"""
    \d+\s+\w+
    """;  // "\\d+\\s+\\w+\n" in escaped notation
```

Both forms require a line terminator after the opening delimiter; spaces,
tabs, and form feeds may appear between the delimiter and that terminator. The
opening terminator is structural and is not content. CRLF and CR source
terminators are normalized to LF, then Java-style incidental indentation is
removed. The common leading margin considers nonblank content lines and an
indented closing-delimiter line, trailing whitespace is removed, and blank
lines remain blank. These transformations explain both examples above: a
terminator immediately before a closing delimiter on its own line remains in
the value, while a delimiter immediately after `world` contributes no final
terminator. An additional blank line after the mandatory opening terminator is
ordinary content and therefore produces the leading `\n`.

The ordinary `"""` form is a cooked text block. After newline and indentation
processing, it decodes Ironwood's existing String escapes: `\b`, `\f`, `\n`,
`\r`, `\t`, `\"`, and `\\`. Escapes are processed last, so escaped whitespace
does not affect margin calculation. A content sequence of three quotes can be
written by escaping at least its first quote.

The lowercase prefix in `r"""` is adjacent and forms one raw-text-block token.
Raw blocks perform no escape processing: each backslash is an ordinary content
character. Layout normalization still applies, so “raw” means escape-free, not
byte-for-byte preservation of source indentation or line endings. The first
`"""` sequence always closes a raw block and cannot be escaped; Feature 78 has
no variable-length delimiter. It also does not introduce single-line `r"..."`
literals.

Neither form interpolates expressions. `${name}` remains literal text, and
`//` or `/* ... */` inside a block never begins a comment. A missing opening
line terminator, an unsupported cooked escape, or a missing closing delimiter
is a compile-time diagnostic.

Both forms have type `ironwood.lang.String`. Their decoded UTF-16 values use
Feature 75's immutable, immortal final-program pool, including deduplication
against ordinary literals and across source, `.ironclass`, and `.ironjar`
inputs. They create no runtime allocation, do not add runtime interning state,
and participate in Feature 76 constant folding like ordinary literals.

Java specifies newline normalization, incidental-indentation removal, and
escape processing for its cooked text blocks in
[JLS 3.10.6](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.6).
Feature 78 is 💡 because Ironwood adds the official escape-free `r"""` form and
deliberately keeps it non-interpolating.

Runnable Ironwood example:
[`TextBlocks.iron`](../examples/textblocks/src/main/ironwood/org/ironwood/textblocks/TextBlocks.iron).

<a id="feature-79"></a>

### 79. Javadoc documentation comments and generation (IronDocs)

Java (traditional Javadoc):

```java
/**
 * Returns the current value.
 * @return the value
 */
public int value() { return 42; }
```

Ironwood (implemented IronDocs subset):

```java
/**
 * Returns the current value.
 * @return the value
 */
public int value() { return 42; }
```

D098 implements the familiar block-comment form for declared types and members.
The `irondoc` command associates comments with parsed declarations, understands
common block/inline tags, resolves selected-type/member links, and generates
Markdown API pages with package navigation, signatures, tables, and examples.
Supported command options preserve familiar `javadoc` spellings and meanings.
Feature 79 is 💡 for this implemented IronDocs subset, not a claim of full
Javadoc compatibility.

The [versioned standard-library reference](api/README.md) includes the first
authored page, `ArrayObjectPool`. Regenerate and commit the current version with:

```sh
./scripts/update-irondocs.sh --commit
```

The complete example embedded in that class's comment is extracted and run
natively by the compiler suite. The generated pages are also checked for
reproducibility and link integrity. See [IRONDOCS.md](IRONDOCS.md) for supported
syntax and options.

Java's broader traditional and Markdown documentation surface is specified in
the [Java SE 26 documentation-comment specification](https://docs.oracle.com/en/java/javase/26/docs/specs/javadoc/doc-comment-spec.html).
Consecutive `///` comments, inherited documentation, arbitrary HTML, DocLint,
custom doclets, and the remaining Javadoc facilities are deferred. Ordinary
compilation still treats documentation text as trivia; IronDocs does not change
executable language semantics.

<a id="feature-80"></a>

### 80. Ordinary line and block comments

Java:

```java
int answer = 42; // line comment
/* block
   comment */
```

Ironwood:

```java
int answer = 42; // same line-comment boundary
/* same non-nesting
   block-comment boundary */
```

Ironwood supports Java-shaped `//` line comments and `/* ... */` block
comments. They are removed by lexical analysis, block comments do not nest, and
comment-looking text inside a String, character literal, or text block remains
literal content. This scoped feature is ✅. Documentation semantics are
separate under Feature 79. See [JLS 3.7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.7).

<a id="feature-81"></a>

### 81. Unicode escapes and Unicode identifiers

Java:

```java
class Caf\u00e9 { }
\u0063lass EscapedKeyword { } // becomes: class EscapedKeyword
```

Ironwood:

```java
String label = "Café"; // Direct UTF-8 text is supported.
// Non-ASCII identifiers and pre-tokenization \u... escapes are unsupported.
```

Ironwood source files are UTF-8 and may contain Unicode text in literals and
comments, but identifiers are deliberately restricted to ASCII letters,
digits, `_`, and `$`, with a non-digit first character. Ironwood also does not
perform Java's early Unicode-escape translation, so a `\u0063` sequence cannot
spell a keyword, delimiter, line terminator, or identifier character before
tokenization. The small `\uNNNN` form accepted inside an Ironwood character
literal is a literal decoder, not Java's source-wide translation.

Feature 81 is ❌. Keeping source structure visible in the UTF-8 file avoids the
surprising lexical effects of source-wide escapes; expanding the identifier
repertoire would be a separate future decision. See
[JLS 3.1–3.3](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.1).

<a id="feature-82"></a>

### 82. Additional Java escape sequences

Java:

```java
String octal = "\141";
String visibleSpace = "end\s";
String joined = """
    one\
    line
    """;
```

Ironwood:

```java
String raw = r"""
    \141 \s \
    """; // Backslashes are literal data.
```

Cooked Ironwood strings and text blocks accept `\b`, `\f`, `\n`, `\r`,
`\t`, `\"`, and `\\`; character literals additionally accept `\'` and the
local four-hex-digit form described in Feature 81. Java's octal escapes,
space escape `\s`, and text-block line continuation are unsupported and
diagnosed in cooked literals. Raw `r"""` blocks are the Ironwood choice when
backslashes should remain data, but they are not an implementation of those
Java escapes. Feature 82 is ❌. See
[JLS 3.10.7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.7).

<a id="feature-83"></a>

### 83. Binary integer literals

Java:

```java
int mask = 0b1010_0101;
long flags = 0B1111_0000L;
```

Ironwood:

```java
int mask = 0b1010_0101;
long flags = 0B1111_0000L;
```

Ironwood implements Java-shaped binary integer literals with the `0b` or `0B`
prefix, underscores between binary digits, and the ordinary optional `L`/`l`
suffix. An unsuffixed literal has type `int` and accepts up to 32 significant
binary digits; a suffixed literal has type `long` and accepts up to 64
significant digits. Leading zero digits do not affect the range. As with
hexadecimal integers, a full-width spelling is interpreted as the exact
two's-complement bit pattern, so 32 or 64 leading-one bits can denote a negative
value. Binary literals participate in ordinary constant folding, overload
selection, static constants, classic-switch labels, typed IR, and native
lowering without a new runtime operation.

Malformed prefixes, non-binary digits, underscores next to the prefix or
suffix, trailing underscores, and values wider than the selected type receive
source diagnostics. The runnable
[`binaryliterals`](../examples/binaryliterals) example covers separators,
full-width signed patterns, static constants, classic switch, suffix-based
typing, and the unchanged decimal meaning of a leading zero. Feature 83 is ✅.

Java's leading-zero octal integers and hexadecimal floating-point literals are
separate Features 103 and 104 so their different readability and exact-value
tradeoffs can be decided independently. See
[JLS 3.10.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.1).

<a id="feature-84"></a>

### 84. Java-width primitives, conversions, and operators

Java:

```java
byte small = 40;
int answer = (small + 2) << 1;
double ratio = answer / 2.0;
boolean ready = ratio == 42.0 && answer != 0;
```

Ironwood:

```java
byte small = 40;
int answer = (small + 2) << 1;
double ratio = answer / 2.0;
boolean ready = ratio == 42.0 && answer != 0;
```

Ironwood supports all eight Java-width primitive types, unary and binary
numeric promotion, representable constant narrowing, explicit numeric casts,
arithmetic, shifts, comparisons, equality, bitwise and short-circuit boolean
operators, `?:`, assignments, and prefix/postfix updates. Operand and argument
evaluation is left-to-right. Integer overflow wraps, shift distances are
masked, division edge cases are defined, and floating operations preserve
Java-shaped NaN and conversion behavior. Feature 84 is ✅ for that scoped
surface; boxing remains separately excluded by Feature 52.
See [JLS 4–5](https://docs.oracle.com/javase/specs/jls/se26/html/jls-5.html)
and [JLS 15](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html).

<a id="feature-85"></a>

### 85. Packages, ordinary imports, and compilation units

Java:

```java
package app;

import library.Value;
import library.helpers.*;

public class Main { }
class Helper { }
```

Ironwood:

```java
package app;

import library.Value;
import library.helpers.*;

public class Main { }
class Helper { }
```

Named and unnamed packages, qualified type names, single-type imports,
type-import-on-demand declarations, same-package lookup, multiple top-level
types, and Java's public-type filename rule work now. `ironwood.lang` is
implicitly visible in the same role that `java.lang` has in Java. Compiled
`.ironclass` and `.ironjar` inputs participate in the same name resolution.
Feature 85 is ✅. Static and module imports are separate Features 86 and 87.
See [JLS 7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html).

Runnable Ironwood example:
[`InheritanceAndInterfaces.iron`](../examples/inheritance/src/main/ironwood/org/ironwood/inheritance/InheritanceAndInterfaces.iron).

<a id="feature-86"></a>

### 86. Static imports

Java:

```java
import static tools.Constants.ANSWER;
import static tools.MathTools.*;

int value = twice(ANSWER);
```

Ironwood:

```java
import static tools.Constants.ANSWER;
import static tools.MathTools.*;

int value = twice(ANSWER);
```

Ironwood accepts both single-static and static-on-demand declarations for
accessible static fields, methods, and member types. A single import shadows an
on-demand import in the corresponding field, method-signature, or type name
space; ordinary lexical declarations still shadow imports. Duplicate imports
are harmless, while conflicting member types, ambiguous fields or overloads,
missing members, and inaccessible members receive source-local diagnostics.
Generic and overloaded static methods use the ordinary invocation planner.
Imported constant variables retain compile-time folding and case-label
behavior; an active use of an imported nonconstant field or method retains the
declaring type's normal one-time initialization. Imports add no runtime object,
allocation, IR operation, or ABI. Source-path, `.ironclass`, and `.ironjar`
discovery preserve the declarations while final-link tree shaking retains only
reachable owners and members. Feature 86 is ✅. See
[JLS 7.5.3–7.5.4](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html#jls-7.5.3).

Runnable Ironwood example:
[`StaticImports.iron`](../examples/staticimports/src/main/ironwood/org/ironwood/staticimports/StaticImports.iron).

<a id="feature-87"></a>

### 87. Module declarations and module imports

Java:

```java
import module java.sql;

module app.core {
    requires data.api;
    exports app.publicapi;
}
```

Ironwood:

```java
package app.core;
import data.api.Connection;
// Dependencies are compile-time source/class/archive inputs, not modules.
```

Ironwood has packages and deterministic compile-time archives, but no Java
Platform Module System language model: no modular compilation unit,
`module-info` source, readability graph, `requires`, `exports`, `opens`,
`uses`, `provides`, unnamed-module semantics, or Java SE 25
`import module` declaration. Runtime module loading would also conflict with
the final closed world. Feature 87 is ❌; a future Ironwood package/distribution
design need not reproduce JPMS. See
[JLS 7.5.5 and 7.7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-7.html#jls-7.5.5).

<a id="feature-88"></a>

### 88. Local inference and Java declarator conveniences

Java:

```java
var answer = compute();
int later;
int first = 1, second = 2;
int[] values = new int[2];
int legacy[] = new int[2];
int _ = consume(); // unnamed variable in its permitted contexts
```

Ironwood:

```java
int answer = compute();
int first = 1;
int second = 2;
int[] values = new int[2];
int ignored = consume();
```

Every Ironwood local declaration names exactly one variable, spells its type,
and supplies an initializer. `var` inference, Java 22 unnamed `_` variables,
uninitialized locals governed by later definite assignment, comma-separated
field or local declarators, and brackets after a variable name are unsupported.
Receiver parameters and legacy post-parameter-list result brackets are also
absent; they primarily serve annotations or old source compatibility that
Ironwood does not have. Ironwood currently treats `_` as an ordinary identifier
rather than an unnamed variable. Feature 88 is ❌. Explicit initialized
declarations keep SSA and ownership provenance direct, although `var` could be
reconsidered independently. See
[JLS 8.3](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.3),
[JLS 8.4.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.4.1),
and [JLS 14.4](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.4).

<a id="feature-89"></a>

### 89. Array initializer syntax

Java:

```java
int[] first = { 20, 22 };
int[] second = new int[] { 20, 22 };
int[][] grid = { { 1 }, { 2 } };
```

Ironwood:

```java
int[] first = { 20, 22 };
int[] second = new int[] { 20, 22 };
int[][] grid = { { 1 }, { 2 } };
```

Both forms infer the exact outer length, accept an optional trailing comma,
apply ordinary assignment conversion to each contextually typed element, and
evaluate and store elements from left to right. An empty initializer creates a
zero-length ordinary array. A nested brace creates exactly one ordinary child
array at that source position; recursive braces therefore create one allocation
per written brace level, not Java's rectangular multi-dimension allocation.

Every nested child is entered in the same compiler-owned constant-slot
provenance map as an explicitly allocated Feature 55 child. Loading it from a
known slot preserves its allocation identity; overwriting that slot with
`null`, or freeing the outer container, proves detachment so a source local may
free the child. An attached slot blocks the free, an escaped outer container
recursively escapes its tracked children, and freeing an outer container never
recursively frees them. Initializer lowering uses the existing typed array
allocate/store IR and native allocation boundary; it adds no hidden builder,
runtime ownership table, or implicit reclamation.

The runnable [`arrayinitializers`](../examples/arrayinitializers) example covers
both surface forms, empty and nested arrays, contextual reference conversion,
left-to-right calls, exact allocation counts, and explicit child detachment and
reclamation. Feature 89 is ✅. See
[JLS 10.6](https://docs.oracle.com/javase/specs/jls/se26/html/jls-10.html#jls-10.6).

<a id="feature-90"></a>

### 90. Remaining statement forms and labeled transfers

Java:

```java
outer:
do {
    for (Item item : items) {
        if (done(item)) break outer;
    }
} while (retry());

;
```

Ironwood:

```java
outer:
do {
    for (Item item : items) {
        if (done(item)) break outer;
    }
} while (retry());

;
```

Ironwood implements `do`/`while`, enhanced `for` over arrays and
`ironwood.lang.Iterable`, the empty statement, labeled statements, and labeled
`break`/`continue`. An enhanced-for operand is evaluated once. Array traversal
uses checked access in ascending index order; `Iterable` traversal calls
`iterator()` once and then uses `hasNext()`/`next()`. The loop borrows that
iterator and creates no hidden allocation or implicit `free`; an `Iterable`
producer retains ownership, normally by resetting and returning a reusable
iterator stored in the producer as the bundled collections do.

Labeled and unlabeled `break`/`continue` execute every crossed `finally` from
inner to outer before reaching their target. An abrupt cleanup supersedes the
pending transfer, matching the existing return/exception cleanup model.
Feature 11's explicit `free` is supported on those paths when ownership is
proved safe. Destinations receive the post-cleanup state, including the `for`
update or `do` condition reached by `continue`. Back-edge checks prevent a
freed allocation from being reused on the next iteration; allocating and
freeing a body-local object on every iteration is supported. See the runnable
[`transfer reclamation` regression](../integration-tests/cases/finally_transfer_reclamation.iron)
for labeled cleanup order, jump replacement, and allocation-balance checks.
Iteration variables have ordinary block scope, support `final` and capture,
and participate in the same type, definite-flow, and ownership analysis as
other locals. Assertions remain separately excluded by Feature 102. See the
runnable [`statements`](../examples/statements) example and
[JLS 14](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html).

<a id="feature-91"></a>

### 91. Modern non-pattern `switch`

Java:

```java
int result = switch (command) {
    case "start", "resume" -> 42;
    case null -> 0;
    default -> {
        yield -1;
    }
};
```

Ironwood:

```java
int result = switch (command) {
    case "start", "resume" -> 42;
    case null -> 0;
    default -> {
        yield -1;
    }
};
```

Feature 65 deliberately implements the classic colon-label statement over
integral primitives and exact enums. Feature 91 implements only modern switch
structure that does not inspect a value with a type or record pattern: arrow
rules, `switch` expressions, `yield`, comma-separated labels, String selectors,
`case null`, and the exhaustiveness needed by a switch expression. Reference
type-pattern labels, guards, dominance, and pattern-specific exhaustiveness are
now isolated as Feature 106. Record/unnamed patterns are Feature 107, and
preview primitive patterns are Feature 108.

Selectors are evaluated once. Integral and enum cases retain typed
`IrSwitchTerminator` dispatch; String cases compare pooled compile-time
constants in source order. A selector without `case null` receives the ordinary
catchable null check, while `case null, default` shares one rule exactly as in
Java. Arrow rules never fall through. Colon-form statements retain Feature
65's explicit fallthrough, and colon-form switch expressions use `yield`.

Every switch expression either declares `default` or covers every constant of
its exact closed-world enum selector. Result expressions and `yield` values use
target typing when available, otherwise numeric promotion, null/reference
merging, or the closed-world least upper reference type, then meet in a typed
SSA phi. A `yield` executes each crossed `finally` before reaching that phi; an
abrupt cleanup supersedes the pending result. Cleanup may free a separate
owned temporary; the result join retains those ownership effects. A pending
reference-valued result remains observable and cannot itself be freed by that
cleanup. Selector evaluation and result aliases retain Ironwood's explicit
ownership rules. The construct adds no hidden allocation, runtime dispatch
table, or native ABI. Format-1 source artifacts reconstruct the same semantics, and LLVM
optimization remains enabled from `-O0` through `-O3`.

Pattern labels receive a direct unsupported diagnostic and remain Features
106–108. See the runnable [`modernswitch`](../examples/modernswitch) example,
[JLS 14.11](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.11)
and [JLS 15.28](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.28).

<a id="feature-92"></a>

### 92. Multi-catch and precise rethrow

Java:

```java
try {
    read();
} catch (FormatFailure | TransportFailure failure) {
    throw failure; // Precisely inferred as one of the alternatives.
}
```

Ironwood:

```java
try {
    read();
} catch (FormatFailure | TransportFailure failure) {
    throw failure; // Precisely inferred as one of the alternatives.
}
```

An Ironwood catch parameter may name one reifiable `Throwable` subtype or a
`|`-separated union of disjoint alternatives. Duplicate alternatives and
subtype/supertype pairs in one union are rejected, each alternative receives
the ordinary checked-catch reachability analysis, and the shared binding has
the least common nominal supertype of the alternatives. A union binding is
implicitly final and remains capturable by local or anonymous classes.

Rethrowing a final or effectively-final single-type catch binding uses the
exact checked types that can reach that catch, including generic `throws`
substitution, rather than the binding's broader declared type. Assignment or
update removes that precision for an ordinary single-type binding. Union
dispatch lowers to existing type tests joined by boolean OR and enters one
shared catch body; it creates no exception object, runtime operation, or
ownership transfer. The existing object, captured trace, native unwind,
`finally`, and secondary-failure behavior are unchanged. Format-1 `.ironclass`
and `.ironjar` inputs reconstruct the same rules at final link.

Runnable Ironwood example:
[`MultiCatchAndPreciseRethrow.iron`](../examples/multicatch/src/main/ironwood/org/ironwood/multicatch/MultiCatchAndPreciseRethrow.iron).
See
[JLS 11.2 and 14.20](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.20).

<a id="feature-93"></a>

### 93. Class literals and runtime `Class` objects

Java:

```java
Class<Widget> widgetType = Widget.class;
Class<Integer> intType = int.class;
Class<int[]> arrayType = int[].class;
```

Ironwood:

```java
if (value instanceof Widget) {
    Widget widget = (Widget) value;
}
```

Ironwood emits private closed-world type descriptors for casts, dispatch,
catching, and `instanceof`, but does not expose those descriptors as
source-visible objects. `T.class`, primitive, `void`, and array class
literals, `ironwood.lang.Class` values, and Java's associated reflective type
identity are unsupported. Feature 93 is ❌. Compile-time metadata or generation
may expose narrowly designed capabilities later without creating Java's runtime
`Class` model. See
[JLS 15.8.2](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.8.2).

<a id="feature-94"></a>

### 94. Flexible constructor bodies

Java:

```java
Positive(int value) {
    if (value <= 0) {
        throw new IllegalArgumentException();
    }
    super(value);
}
```

Ironwood:

```java
Positive(int value) {
    super(value); // Must remain the first constructor action.
    if (value <= 0) {
        throw new IllegalArgumentException();
    }
}
```

Java SE 25 permanently allowed a restricted constructor prologue before an
explicit `this(...)` or `super(...)` invocation, with early-construction
rules preventing premature use of the new instance. Ironwood retains the older
first-action rule and has no constructor prologue. Feature 94 is ❌ and is a
reasonable future candidate, but it requires exact interaction with blank
finals, capture, checked exceptions, and allocation ownership. See
[JLS 8.8.7](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.8.7).

<a id="feature-95"></a>

### 95. Local enum and interface declarations

Java:

```java
void run() {
    enum State { READY, DONE }
    interface Value { int get(); }
}
```

Ironwood:

```java
void run() {
    class LocalValue {
        int get() { return 42; }
    }
}
```

Feature 31 implements local normal classes, including capture. Ironwood
explicitly rejects block-local enum and interface declarations. Local records
are already excluded with records under Feature 62, and local annotation
interfaces with annotations under Feature 59. Feature 95 is ❌; it records the
remaining JLS 14.3 distinction without implying that top-level/member enums or
nested interfaces are missing. See
[JLS 14.3](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.3).

<a id="feature-96"></a>

### 96. Compact source files and expanded `main` forms

Java:

```java
void main() {
    System.out.println("Hello");
}
```

Ironwood:

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello");
    }
}
```

Java SE 25 permanently added compact source files with an implicitly declared
class and launchable instance `main` methods that need not be `public` or
`static` and may omit the `String[]` parameter; a Java entry method still
returns `void`. Ironwood requires an explicit type declaration and accepts the
classic `public static void main(String[] args)` form. Normal completion returns
native status 0. Ironwood also accepts `int` in place of `void` as an extension
whose return value supplies the native process status. The argument array
excludes `argv[0]`. Feature 96 remains ❌ for Java's compact classes and expanded
instance or parameterless launch forms. See
[JLS 8.1.8](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.1.8)
and the
[Java SE 26 compact-source guide](https://docs.oracle.com/en/java/javase/26/language/compact-source-files-instance-main-methods.html).

<a id="feature-97"></a>

### 97. Other Java special-purpose declaration modifiers

Java:

```java
native int nativeValue();
synchronized void update() { }
transient int cached;
strictfp double calculate() { return 1.0 / 3.0; }
```

Ironwood:

```java
double calculate() { return 1.0 / 3.0; }
// No native/synchronized/transient/strictfp modifiers.
```

Ironwood has no `native` method declaration; a future FFI requires an explicit
Ironwood design. `synchronized` belongs to Java's excluded thread/monitor model,
while `transient` belongs to excluded Java serialization. `strictfp` is
unnecessary because Ironwood floating-point lowering already uses deterministic
IEEE operations without fast-math assumptions. These spellings are rejected
rather than accepted as misleading no-ops. Feature 97 remains ❌. `volatile` is
important enough to decide independently and is now Feature 101. See
[JLS 8.3.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.3.1)
and [JLS 8.4.3](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.4.3).

<a id="feature-98"></a>

### 98. Java binary compatibility across library evolution

Java:

```java
// Existing .class clients may continue linking after changes that JLS 13
// defines as binary compatible.
```

Ironwood:

```java
// .ironclass and .ironjar inputs are reconstructed into one final closed world.
```

Ironwood supports separate source compilation into uniform `.ironclass` files,
deterministic `.ironjar` archives, and classpath reuse. Its current format
embeds validated source so native link can reconstruct, recheck, tree-shake, and
lower the complete reachable program. It is not a stable ABI, does not promise
Java `.class` binary-evolution rules, and performs no runtime symbolic linking.
Feature 98 is 💡: compile-time reuse exists in a form suited to a closed-world
native compiler, while Java binary compatibility itself remains unsupported.
See [JLS 13](https://docs.oracle.com/javase/specs/jls/se26/html/jls-13.html).

<a id="feature-99"></a>

### 99. Intersection types in cast expressions

Java:

```java
((Readable & Resettable) value).reset();
```

Ironwood:

```java
interface ReadableAndResettable extends Readable, Resettable { }

ReadableAndResettable both = (ReadableAndResettable) value;
both.reset();
```

Feature 41 supports intersection bounds on type parameters, but Ironwood's cast
grammar accepts one target type and cannot introduce Java's non-denotable
`A & B` cast type. Declaring a nominal combined interface can model some
programs, but it is not intersection-cast support and requires objects to
implement that exact nominal interface. Feature 99 is confirmed ❌ for now. It
is not required for intersection bounds or ordinary generic type inference,
which are already supported. A later source-compatibility case could reopen it,
but no implementation is committed. See
[JLS 4.9 and 15.16](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.16).

<a id="feature-100"></a>

### 100. Catchable ordinary implicit runtime safety failures

Java:

```java
try {
    values[-1] = item;
} catch (ArrayIndexOutOfBoundsException expected) {
    recover();
}
```

Ironwood:

```java
try {
    values[index] = item;
} catch (ArrayIndexOutOfBoundsException expected) {
    recover();
}
```

Ironwood language operations now throw catchable exceptions for failures such
as dereferencing a null receiver, accessing an array through null or outside
its bounds, allocating an array with a negative length, or evaluating
`throw null`. A surrounding `catch` can recover with the same Java-shaped
category shown below.

| Failing operation | Catchable exception |
| --- | --- |
| Instance field or method use through `null` | `NullPointerException` |
| Array `.length`, load, or store through `null` | `NullPointerException` |
| Array load or store with an index outside `0..length - 1` | `ArrayIndexOutOfBoundsException` |
| Array allocation with a negative length | `NegativeArraySizeException` |
| `throw null` | `NullPointerException` |

Checked casts and runtime integer division by zero already used catchable
Ironwood exceptions. Feature 100 brings the remaining ordinary safety
boundaries through the same compiler-owned exceptional control-flow,
source-trace, `catch`, and `finally` machinery. Typed null, array-bounds, and
array-length predicates branch to failure-only construction of the matching
exception; the old fail-fast null and bounds runtime entry points are no longer
part of generated code. This preserves source evaluation order, first/secondary
failure ordering, and ordinary exception allocation accounting.

The runnable
[`CatchableRuntimeFailures.iron`](../examples/runtimefailures/src/main/ironwood/org/ironwood/runtimefailures/CatchableRuntimeFailures.iron)
example catches null use, an invalid array index, a negative array length, and
`throw null` in a native program.

Creating one of these exception objects may use ordinary allocation. If that
allocation itself fails, the result follows the separate Feature 105 contract
rather than silently expanding Feature 100.

Allocator exhaustion is explicitly outside this feature because throwing when
ordinary allocation is unavailable needs a different reliability and recovery
contract. Feature 105 records that decision separately. See
[JLS 11.1.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-11.html#jls-11.1.1).

<a id="feature-101"></a>

### 101. `volatile` fields and Java memory visibility

Java:

```java
class State {
    private volatile boolean ready;
}
```

Ironwood (`volatile` not supported):

```java
class State {
    private boolean ready; // Ordinary field; no cross-thread visibility contract.
}
```

Java `volatile` reads and writes participate in the Java Memory Model, including
visibility, ordering, atomicity, and happens-before guarantees. Ironwood
currently rejects the modifier, and ordinary fields must not be interpreted as
providing those guarantees. Because Feature 70 excludes source-level threads,
monitors, and `synchronized`, and Ironwood has no shared-memory native
interoperability model, no source observer can distinguish a Java-shaped
volatile field from an ordinary field.

Feature 101 is therefore ❌, superseding D071's earlier pending commitment. It
must not be reinterpreted as C or LLVM `volatile`, which is not Java's
inter-thread synchronization contract. The decision may be reconsidered only
with a defined thread model or an explicit shared-memory native interoperability
model. It remains separate from Feature 97's excluded serialization,
strict-floating-point, synchronization, and FFI modifiers. See
[JLS 8.3.1.4](https://docs.oracle.com/javase/specs/jls/se26/html/jls-8.html#jls-8.3.1.4)
and [JLS 17.4](https://docs.oracle.com/javase/specs/jls/se26/html/jls-17.html#jls-17.4).

<a id="feature-102"></a>

### 102. `assert` statements

Java:

```java
assert denominator != 0 : "denominator must not be zero";
```

Ironwood:

```java
if (denominator == 0) {
    throw new IllegalStateException("denominator must not be zero");
}
```

Java assertions may be enabled or disabled at runtime, so the condition and
optional detail expression may not be evaluated at all. When enabled and false,
the statement throws `AssertionError`. Ironwood deliberately omits this
execution-mode-dependent construct: validation that matters should use ordinary
control flow and an explicit failure, which remains active in every build and
optimization mode. Feature 102 is ❌ and is not under consideration for
promotion. See
[JLS 14.10](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.10).

<a id="feature-103"></a>

### 103. Java-style octal integer literals

Java:

```java
int permissions = 0755; // Decimal 493.
int eight = 010;
```

Ironwood:

```java
int permissions = 493;
int eight = 8;
```

Ironwood deliberately does not give a leading zero an implicit numeric base.
Java-style octal makes visually decimal-looking text change value, so explicit
decimal or hexadecimal notation is clearer. Feature 103 is confirmed ❌; it is
not part of the implemented binary-literal Feature 83. See
[JLS 3.10.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.1).

<a id="feature-104"></a>

### 104. Hexadecimal floating-point literals

Java:

```java
double three = 0x1.8p1;
double binaryUlp = 0x1.0p-52;
```

Ironwood:

```java
double three = 3.0;
double binaryUlp = 2.220446049250313E-16;
```

Java hexadecimal floating-point notation writes a binary significand and a
power-of-two exponent, which is valuable when source must spell an IEEE-754
value or boundary exactly. Ironwood currently accepts decimal floating-point
literals only. Feature 104 remains ❌ while its independent promotion decision
is open; implemented Feature 83 does not include it. See
[JLS 3.10.2](https://docs.oracle.com/javase/specs/jls/se26/html/jls-3.html#jls-3.10.2).

<a id="feature-105"></a>

### 105. Catchable allocation failure and `OutOfMemoryError`

Java:

```java
try {
    int[] values = new int[requestedSize];
    use(values);
} catch (OutOfMemoryError exhausted) {
    releaseCaches();
}
```

Ironwood:

```java
try {
    int[] values = new int[requestedSize];
    use(values);
} catch (OutOfMemoryError exhausted) {
    releaseOwnedCapacity();
}
```

Ironwood automatically delivers `OutOfMemoryError` for failed source-evaluated
ordinary object and array allocations and for operations, such as dynamic
String concatenation, that
allocate a source-visible result. Failure means the native allocator returned
no storage or an allocation-size calculation overflowed. An operating-system
overcommit kill, fatal memory-access signal, or other failure outside an
Ironwood allocation boundary cannot be converted into a language exception.
Pre-entry runtime setup and runtime-private bookkeeping also remain outside
catchable source behavior.

The existing `ironwood.lang.OutOfMemoryError` class remains available for an
ordinary explicitly constructed and thrown error. Feature 105 additionally
converts failed native source allocations automatically; it adds no
standard-library type.

The implicit failure uses one compiler-emitted immortal `OutOfMemoryError` with
a null message. It has no source owner, cannot be freed, does not increment the
Ironwood allocation count, and may have the same reference identity on later
failures. Runtime-private emergency storage delivers it without relying on the
ordinary allocator. That storage must support one active implicit allocation
failure, including one required primary/secondary association with an earlier
or later failure propagating through `finally`. A second allocation failure
while the implicit error is unwinding terminates with a deterministic diagnostic
rather than recursively attempting another unsafe unwind. Once it has landed in
a source catch, a later automatic occurrence may reuse the singleton and replace
its bounded emergency trace and association state.

Trace capture guarantees the exact failing allocation-site frame without
allocating. Additional caller frames may use bounded emergency capacity and may
be truncated explicitly; nonessential runtime metadata allocation continues to
degrade rather than recursively throwing. A source catch may free allocations
it already owns and retry, but neither recovery nor a successful retry is
guaranteed.

Ironwood retains its ownership-safe evaluation order: an object-construction
receiver and constructor arguments are evaluated before the ordinary object
allocation is attempted, so an argument failure cannot strand hidden allocated
storage. Array dimensions and dynamic String operands are likewise evaluated
before their result allocation. This intentionally differs from Java's object-
allocation failure timing. A failed allocation creates no ordinary Ironwood
object, increments no allocation count, and creates nothing that source must
free.

Feature 105 is implemented and separate from Feature 100. Its immortal error,
bounded emergency delivery, and ownership-safe evaluation timing make it an
Ironwood-way 💡 rather than a claim of exact Java VM behavior. The runtime's
`IRONWOOD_ALLOCATION_LIMIT` diagnostic variable supplies a deterministic test
boundary; it is not a source API or a guarantee that arbitrary resource
exhaustion can recover. The runnable
[`CatchableAllocationFailure.iron`](../examples/allocationfailure/src/main/ironwood/org/ironwood/allocationfailure/CatchableAllocationFailure.iron)
example exercises object, array, and dynamic String failures. See
[JLS 11.1.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-11.html#jls-11.1.1),
[JLS 15.9.4](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.9.4),
and [JLS 15.10.2](https://docs.oracle.com/javase/specs/jls/se26/html/jls-15.html#jls-15.10.2).

<a id="feature-106"></a>

### 106. Reference type patterns in `switch`

Java:

```java
int sound(Object value) {
    return switch (value) {
        case Dog dog when dog.ready() -> dog.sound();
        case Cat cat -> cat.sound();
        case null -> 0;
        default -> -1;
    };
}
```

Ironwood (pattern switch not supported):

```java
int sound(Object value) {
    if (value == null) { return 0; }
    if (value instanceof Dog) {
        Dog dog = (Dog) value;
        if (dog.ready()) { return dog.sound(); }
    }
    if (value instanceof Cat) {
        return ((Cat) value).sound();
    }
    return -1;
}
```

Feature 106 is specifically the use of named reference type patterns as switch
labels, together with guards, dominance, applicability, flow-scoped bindings,
and pattern-specific exhaustiveness. It does not include arrow rules, switch
expressions, `yield`, comma-separated constant labels, or String selection;
those are the independent non-pattern mechanisms in Feature 91. This separation
allows Feature 91 to be implemented without committing pattern matching, and
lets any future Feature 106 implementation build on Feature 66's completed
reference type-pattern semantics.

Record deconstruction and unnamed patterns remain Feature 107 even when used in
a switch label. Primitive selectors and patterns from the Java SE 26 preview
remain Feature 108. Feature 106 remains ❌. D073 makes no roadmap commitment:
without records or sealed source hierarchies, its strongest exhaustive-
decomposition use cases are absent, while ordinary virtual/interface dispatch
already covers most open-hierarchy behavior. It may be reconsidered if those
dependencies or a compelling type-dispatch use case change. See
[JLS 14.11](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.11)
and [JLS 14.30](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.30).

<a id="feature-107"></a>

### 107. Record patterns and unnamed patterns

Java:

```java
if (value instanceof Point(int x, int y)) {
    return x + y;
}

int xCoordinate = switch (value) {
    case Point(int x, _) -> x;
    default -> 0;
};
```

Ironwood (record and unnamed patterns not supported):

```java
// Point is an ordinary class because Feature 62 records remain excluded.
if (value instanceof Point) {
    Point point = (Point) value;
    return point.x() + point.y();
}
```

Record patterns test a record and recursively deconstruct its components;
unnamed `_` patterns intentionally discard a matched component or binding.
They are pattern vocabulary rather than a synonym for either `instanceof` or
`switch`, so Feature 107 records them once and allows either surrounding
construct to depend on them. Record patterns also require a decision to support
records under Feature 62. Unnamed pattern variables are distinct from Feature
88's broader unnamed-variable and declarator-convenience bundle. D073 confirms
Feature 107 as ❌ while records remain excluded by Feature 62; unnamed patterns
can be split later if independently justified. See
[JLS 14.30.1](https://docs.oracle.com/javase/specs/jls/se26/html/jls-14.html#jls-14.30.1).

<a id="feature-108"></a>

### 108. Primitive types in patterns, `instanceof`, and `switch`

Java SE 26 preview:

```java
if (value instanceof byte narrowed) {
    use(narrowed);
}

String rating(double value) {
    return switch (value) {
        case 0.0 -> "zero";
        case double number when number > 0.0 -> "positive";
        default -> "negative or NaN";
    };
}
```

Ironwood (preview primitive patterns not supported):

```java
byte narrowed = (byte) value;
if ((int) narrowed == value) {
    use(narrowed);
}
```

Java SE 26's sole preview language family extends testing conversions,
`instanceof`, pattern matching, and switch selection to every primitive type.
Its exactness, dominance, NaN, signed-zero, and narrowing rules form one
cross-cutting preview rather than stable reference-pattern or switch behavior.
Feature 108 keeps it out of Features 66, 91, and 106 so none of those decisions
silently commits preview syntax. Feature 108 remains ❌. D073 confirms that
Ironwood will not commit Java preview pattern semantics; the feature may be
reconsidered after Java finalizes it and an Ironwood use case justifies the
numeric edge rules. Recording it for audit completeness is not a commitment.
See the
[Java SE 26 preview specification](https://docs.oracle.com/en/java/javase/26/docs/specs/primitive-types-in-patterns-instanceof-switch-jls.html).

## Reading the relationship correctly

The large block of ✅ entries is intentional: classes, inheritance,
polymorphism, interfaces, nested types, anonymous types, and reference generics
use familiar Java source forms and programmer-visible behavior for the feature
slice each row claims. Ironwood-specific capture reclamation is classified
separately as 💡.

The remaining icons make current support and design choices explicit:

1. **Implemented Ironwood ways:** mandatory `@Override` turns Java's optional
   marker into a bidirectional compiler contract; native primitive-generic value
   specialization avoids hidden boxes; compiler-checked `free`, derived-to-root
   destructors, failed-construction rollback, and capture-aware alias analysis
   provide deterministic reclamation without a collector;
   multidimensional arrays use explicit separately owned children; and ordinary
   `try`/`finally` supports ownership-proven exactly-once `free`, including on
   `break`/`continue`/`yield` exits, preserves the first failure, and retains later
   cleanup failures without Java's
   try-with-resources construct; cooked text blocks gain
   an escape-free, non-interpolating raw form; bounded immortal allocation-
   failure delivery works without relying on the exhausted allocator; and
   compile-time `.ironclass`/`.ironjar` reuse replaces Java's evolving binary-
   linkage contract.
2. **Completed modern control flow:** non-pattern arrow rules, String/null
   selection, expressions, `yield`, and enum exhaustiveness are implemented by
   Feature 91 without enabling any pattern-switch family.
3. **Currently unsupported features:** unchecked and raw generics, automatic
   boxing, array covariance, varargs, records, sealed types, lambdas and method
   references, pattern `switch`, record/unnamed patterns, preview primitive
   patterns, `volatile` fields, general annotations, Java serialization/
   cloning/finalization, unrestricted runtime reflection, runtime class loading
   and generated proxies, runtime `String.intern()`, and shared-memory threads/
   monitors are not supported. Feature 79 implements the initial IronDocs subset;
   broader Javadoc facilities remain deferred. The Java SE 26 audit also records
   source-wide Unicode escapes and Unicode identifiers, octal integers, module
   imports and declarations, declarator sugar, class literals,
   flexible constructor bodies, local enums/interfaces, compact
   source/expanded entry forms, special-purpose modifiers, and intersection
   casts. Hexadecimal floating-point literals remain the open decision under
   Feature 104. Java's runtime-selectable `assert` statement is separately
   excluded by Feature 102.
   Manual application rewrites do not change those statuses.

Ironwood should continue to copy Java's names, source shape, and behavior where
those choices remain honest. When a Java feature would conceal allocation,
require JVM dynamism, or impose GC semantics, the difference should be explicit
and documented rather than approximated under a deceptively compatible API.

## Verifying the implemented features

The paired snippets in this document explain the relationship. The runnable,
end-to-end proof lives in the focused example projects cataloged by
[`OBJECT_MODEL.md`](OBJECT_MODEL.md#hands-on-example-catalog). Run all 30 with:

```console
$ ./examples/test-object-model.sh
PASS: 30 object-model examples
```

The primary compiler suite also checks the invalid forms described above:

```console
$ ./scripts/test.sh
PASS: 456 compiler tests
```
