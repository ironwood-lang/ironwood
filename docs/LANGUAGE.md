# Ironwood language status

Ironwood is a Java-like, closed-world AOT systems language. Objects and
references follow familiar Java semantics except that Ironwood has no garbage
collector: ordinary reclamation is performed explicitly with compiler-checked
`free`. This document distinguishes implemented behavior from future design so
tests never become the only specification. For the consolidated current,
roadmap, and permanent-non-goal feature matrix, see
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md).

## Implemented language subset

An Ironwood source file uses the `.iron` extension and is UTF-8 text. The current
compiler implements this grammar (semantic restrictions are described below):

```ebnf
compilationUnit = [ packageDeclaration ], { importDeclaration },
                  typeDeclaration, { typeDeclaration }, EOF ;
packageDeclaration = "package", qualifiedName, ";" ;
importDeclaration = "import", [ "static" ], qualifiedName, [ ".", "*" ], ";" ;
qualifiedName = identifier, { ".", identifier } ;
typeDeclaration = classDeclaration | interfaceDeclaration | enumDeclaration | ";" ;
classDeclaration = { modifier }, "class", identifier,
                   [ typeParameters ],
                   [ "extends", referenceType ],
                   [ "implements", referenceType, { ",", referenceType } ],
                   "{", { classBodyDeclaration }, "}" ;
interfaceDeclaration = { modifier }, "interface", identifier,
                       [ typeParameters ],
                       [ "extends", referenceType, { ",", referenceType } ],
                       "{", { interfaceBodyDeclaration }, "}" ;
enumDeclaration = { modifier }, "enum", identifier,
                  [ "implements", referenceType, { ",", referenceType } ],
                  "{", [ enumConstant, { ",", enumConstant }, [ "," ] ],
                  [ ";", { classBodyDeclaration } ], "}" ;
enumConstant = identifier, [ arguments ] ;
classBodyDeclaration = constructorDeclaration | destructorDeclaration | fieldDeclaration
                     | methodDeclaration | typeDeclaration | block ;
interfaceBodyDeclaration = fieldDeclaration | methodDeclaration
                         | typeDeclaration ;
constructorDeclaration = { modifier }, [ typeParameters ], identifier,
                         "(", [ parameter, { ",", parameter } ], ")",
                         "{", [ constructorInvocation ], { statement }, "}" ;
constructorInvocation = superConstructorInvocation | thisConstructorInvocation ;
destructorDeclaration = "destructor", block ;
superConstructorInvocation = "super", arguments, ";" ;
thisConstructorInvocation = "this", arguments, ";" ;
fieldDeclaration = { modifier }, valueType, identifier, [ "=", expression ], ";" ;
methodDeclaration = { methodModifier }, [ typeParameters ], type, identifier,
                    "(", [ parameter, { ",", parameter } ], ")",
                    ( block | ";" ) ;
methodModifier = modifier | overrideDirective | testDirective ;
overrideDirective = "@", "Override" ;
testDirective = "@", "Test" ;
modifier = "public" | "protected" | "private" | "static" | "final"
         | "abstract" | "default" ;
parameter = [ "final" ], valueType, identifier ;
type = valueType | "void" ;
valueType = elementType, [ "[", "]" ] ;
elementType = "byte" | "short" | "int" | "long" | "char"
            | "float" | "double" | "boolean" | referenceType ;
referenceType = qualifiedName, [ typeArguments ],
                { ".", identifier, [ typeArguments ] } ;
typeParameters = "<", typeParameter, { ",", typeParameter }, ">" ;
typeParameter = identifier,
                [ "extends", referenceType, { "&", referenceType } ] ;
typeArguments = "<", [ typeArgument, { ",", typeArgument } ], ">" ;
typeArgument = valueType | "?", [ ( "extends" | "super" ), referenceType ] ;

block = "{", { statement }, "}" ;
statement = block
          | classDeclaration
          | [ "final" ], valueType, identifier, "=", expression, ";"
          | "if", "(", expression, ")", statement, [ "else", statement ]
          | "while", "(", expression, ")", statement
          | "do", statement, "while", "(", expression, ")", ";"
          | "for", "(", [ forInitializer ], ";", [ expression ], ";",
            [ expression, { ",", expression } ], ")", statement
          | "for", "(", [ "final" ], valueType, identifier, ":",
            expression, ")", statement
          | switchStatement
          | identifier, ":", statement
          | "break", [ identifier ], ";"
          | "continue", [ identifier ], ";"
          | "return", [ expression ], ";"
          | "throw", expression, ";"
          | "free", expression, ";"
          | tryStatement
          | expression, ";"
          | ";" ;
tryStatement = "try", block, { catchClause }, [ "finally", block ] ;
catchClause = "catch", "(", [ "final" ], valueType, identifier, ")", block ;
forInitializer = [ "final" ], valueType, identifier, "=", expression
               | expression ;
switchStatement = "switch", "(", expression, ")", "{",
                  { switchGroup }, "}" ;
switchGroup = switchLabel, { switchLabel }, { statement } ;
switchLabel = "case", expression, ":" | "default", ":" ;

expression = assignment ;
assignment = conditional, [ assignmentOperator, assignment ] ;
assignmentOperator = "=" | "+=" | "-=" | "*=" | "/=" | "%="
                   | "<<=" | ">>=" | ">>>=" | "&=" | "^=" | "|=" ;
conditional = logicalOr, [ "?", expression, ":", conditional ] ;
logicalOr = logicalAnd, { "||", logicalAnd } ;
logicalAnd = bitwiseOr, { "&&", bitwiseOr } ;
bitwiseOr = bitwiseXor, { "|", bitwiseXor } ;
bitwiseXor = bitwiseAnd, { "^", bitwiseAnd } ;
bitwiseAnd = equality, { "&", equality } ;
equality = comparison, { ( "==" | "!=" ), comparison } ;
comparison = shift,
             { ( "<" | "<=" | ">" | ">=" ), shift
             | "instanceof", instanceofRhs } ;
instanceofRhs = referenceType
              | [ "final" ], referenceType, identifier ;
shift = additive, { ( "<<" | ">>" | ">>>" ), additive } ;
additive = multiplicative, { ( "+" | "-" ), multiplicative } ;
multiplicative = unary, { ( "*" | "/" | "%" ), unary } ;
unary = ( "+" | "-" | "!" | "~" ), unary
      | ( "++" | "--" ), unary
      | "(", valueType, ")", unary
      | postfix ;
postfix = primary, { "[", expression, "]"
                   | ".", [ typeArguments ], identifier, [ arguments ]
                   | ".", "new", [ typeArguments ], referenceType, arguments,
                     [ "{", { classBodyDeclaration }, "}" ] },
          [ "++" | "--" ] ;
primary = integerLiteral | floatingLiteral | characterLiteral | stringLiteral
        | "true" | "false" | "null" | "this" | "super"
        | qualifiedName, ".", ( "this" | "super" )
        | identifier | identifier, arguments
        | typeArguments, identifier, arguments
        | "new", [ typeArguments ], referenceType, arguments,
          [ "{", { classBodyDeclaration }, "}" ]
        | "new", elementType, "[", expression, "]"
        | "(", expression, ")" ;
arguments = "(", [ expression, { ",", expression } ], ")" ;
```

Whitespace, `//` line comments, and `/* ... */` block comments are ignored.
Identifiers use ASCII letters, digits, `_`, and `$`, and cannot begin with a
digit. Ordinary double-quoted string literals contain UTF-8 text and accept
`\\b`, `\\f`, `\\n`, `\\r`, `\\t`, `\\"`, and `\\\\` escapes; raw line breaks
and other escapes are rejected.

Feature 78 adds cooked `"""` and raw `r"""` text blocks. Both opening
delimiters must be followed by a line terminator, optionally after spaces,
tabs, or form feeds. The opening terminator is not content. Content line
terminators are normalized from CRLF or CR to LF, then incidental indentation
and trailing whitespace are removed with Java text-block layout semantics. A
line terminator before a closing delimiter on its own line is content; placing
the delimiter immediately after the final content character omits it. Any
additional blank line following the opening terminator is retained.

A cooked block interprets the ordinary Ironwood String escapes listed above
after layout processing. Escaping the first quote allows a `"""` content
sequence. A raw block performs no escape processing, although its line endings
and layout are still normalized. Its first `"""` always closes the block and
cannot be escaped. Feature 78 provides neither variable-length delimiters nor
single-line `r"..."` literals. Neither block form interpolates `${...}`, and
comment markers inside a block are content. Missing opening line terminators,
unsupported cooked escapes, and missing closing delimiters are lexical errors.

Source-wide Java `\u...` translation and Unicode identifiers are not
implemented. The four-hex-digit escape accepted inside a character literal is
decoded only there and cannot spell source tokens. Java octal/`\s` escapes and
text-block line continuation are also unsupported. Java-style leading-zero
octal is deliberately excluded by Feature 103, and hexadecimal floating-point
literals remain an open decision under Feature 104. A leading zero does not
select Java octal semantics.

Source locations are one-based. Lexical, syntax, and semantic errors include
the file, line, column, source line, and a caret range.

Integral literals are decimal, `0x`/`0X` hexadecimal, or `0b`/`0B` binary,
accept underscores between digits, and use `L`/`l` for `long`; otherwise they
have type `int`. Hexadecimal and binary literals may use every selected-width
bit as an exact two's-complement pattern: an unsuffixed spelling accepts up to
32 bits and a suffixed spelling accepts up to 64. Decimal literals retain the
signed-magnitude range rule, including the special minimum value after unary
`-`.
Decimal floating literals use a decimal point, an `e`/`E` exponent, or an
`F`/`f`/`D`/`d` suffix. `F`/`f` selects `float`; the default and `D`/`d` select
`double`. Single-quoted character literals contain exactly one UTF-16 code unit
and accept the ordinary Java single-character escapes plus `\\u` followed by
four hexadecimal digits. Malformed separators, exponents, escapes, and
out-of-range integral literals are compile-time errors.

### Compilation units, classes, interfaces, and entry point

A source file belongs to its declared package, or to the unnamed package when it
has no package declaration. It may declare multiple top-level classes,
interfaces, and enums, matching Java's language rule rather than imposing a
stricter style rule. At most one may be `public`, and a public type `Order` must be declared in
`Order.iron`. Top-level types may otherwise be package-private (no access
modifier); `private`, `protected`, and `static` top-level types are rejected.
The examples follow the recommended Java convention of one top-level type per
file.

Type names resolve from lexical declarations, already-qualified names,
single-type or single-static member-type imports, the current package, implicit
`ironwood.lang`, and ordinary or static on-demand imports according to Java's
shadowing spaces. Conflicting single imports and ambiguous on-demand matches are
errors. Imported top-level package-private types and inaccessible member types
remain unavailable outside their permitted context. Member type lookup follows
lexical, inherited, imported, and qualified owner context for static nested
classes, member inner classes, nested interfaces, and implicitly static member
enums. Local classes have block scope; anonymous classes have compiler-assigned
identity only. `ironwood.lang.Object`, `ironwood.lang.String`, and
`ironwood.lang.System` are implicitly visible standard-library types.
`System.out` has public type `ironwood.io.PrintStream`, so a source that names
`PrintStream` directly uses an ordinary import. These
compiler-owned canonical types cannot be redefined by application sources or
classpath artifacts.

`import static p.Owner.member;` imports every accessible static field, method,
or member type of that name; `import static p.Owner.*;` makes all accessible
static members of the canonical owner available on demand. Repeated imports of
the same declaration are harmless. A single-static field shadows same-name
static-on-demand fields, and a single-static method shadows an on-demand method
with the same invocation signature; ordinary lexical fields and methods shadow
imports. A single imported member type follows the corresponding Java type-name
shadowing and conflict rules. Otherwise, multiple fields or member types are
ambiguous, while method candidates enter the existing fixed-arity overload and
generic-inference planner. Missing, non-static, inaccessible, conflicting, and
ambiguous imports receive source-local diagnostics.

Imported constant variables remain compile-time constants in initializers and
classic-switch labels. Loads, stores, and calls for nonconstant members retain
the declaring type's ordinary active-use initialization. Static imports add no
runtime value or ownership behavior. Their owner and member-type dependencies
are discovered from source paths, class directories, individual `.ironclass`
files, and `.ironjar` archives; final linking retains only reachable code.

A compiler invocation forms one closed-world compilation set. It may contain
multiple explicitly named source files and additional referenced sources found
through the source path. For a type `com.test.Foo`, a source-path root is searched
for `com/test/Foo.iron`. The default source path is `.`, so a source in a
conventional package tree is discoverable when the compiler runs from its
package root. Source-path entries take precedence over compiled class entries on
the classpath.

Every compiled top-level class, interface, or enum uses the same `.ironclass`
format.
This includes the class declaring `public static int main(String[] args)` or
`public static void main(String[] args)`;
entry-point metadata does not change the class format. A classpath directory is
a package root, so
`com.test.Foo` maps to `com/test/Foo.ironclass`. Format 1 stores validated type
and optional entry-point metadata plus an embedded source payload. Final native
compilation reparses, rechecks, and lowers referenced class units together with
the application, preserving closed-world semantics without runtime loading. The
payload is a bootstrap representation rather than a stable binary ABI. Member,
local, and anonymous declarations are reconstructed from their owning top-level
payload and are not separately addressable classpath artifacts.

A class may extend one class and implement any number of interfaces. An
interface may extend any number of interfaces. Extending or implementing the
wrong kind of type, repeating a direct parent, and class or interface inheritance
cycles are errors. `ironwood.lang.Object` is the unique root class. Every other
class implicitly extends `Object` when it has no explicit superclass, and its
constructor therefore begins with an implicit `Object()` call. Interfaces do not
spell a superclass, but every interface value denotes an object and widens to
`Object`.

Classes may be concrete or abstract and may be final; instance methods may be
abstract or final under the Java-shaped consistency rules in
[`OBJECT_MODEL.md`](OBJECT_MODEL.md). A concrete class must resolve every
transitive abstract class and interface obligation.

An enum is an implicitly final nominal reference type with the Java-shaped
`ironwood.lang.Enum<E>` base and may implement interfaces. It cannot be generic,
explicitly extend a type, or be subclassed in source. Member enums are
implicitly static, and a member enum in an interface is implicitly public.
Local enums remain unsupported. Under Feature 74, each constant-specific class
body is a compiler-owned final subtype; this does not permit named source
subclasses.

Interfaces support implicitly public static-final constant fields and public
abstract, public default, public static, private instance, and private static
methods. A class implementation wins over a default; otherwise the unique
most-specific inherited default wins. Unrelated surviving defaults are an
error, a more-specific abstract redeclaration can re-abstract a default, and a
permitted direct superinterface default may be selected with
`InterfaceName.super.method(...)`. Protected and package-private interface
methods and interface constructors remain invalid.

An executable selects one `main` method with signature `public static int
main(String[] args)` or `public static void main(String[] args)`. The parameter
name is not significant, and
`ironwood.lang.String[]` is the equivalent fully qualified spelling. A
no-argument `main()` is not an entry point and is rejected when it is the
selected class's only `main` declaration. Native linking is requested
explicitly with `--link`, which accepts no source files and requires
`--main-class <qualified-name>`. The named class is the initial class loaded
from the compile-time classpath and must exist there; its valid
`main(String[] args)` supplies the native entry method. Methods and constructors may be
overloaded by parameter types; a result type is not part of a method signature.
Static methods may be called unqualified from their declaring class or through a
class name. Forward calls are allowed. Ordinary class compilation does not
require an entry point and may contain any number of valid main classes. Link
mode emits no class artifacts and never discovers source dependencies.

The entry array contains one immutable `ironwood.lang.String` for each process
argument after the executable name; native `argv[0]` is not exposed. The array
is non-null, including when it is empty, and its elements are non-null. Native
argument bytes are decoded as UTF-8 into Ironwood's UTF-16 string
representation. Each invalid input byte becomes U+FFFD. The array and
strings are ordinary runtime-created allocations that remain allocated until
process termination; they are not implicitly collected. A returned `int`
becomes the native process exit status. Normal completion of a `void` entry
returns status 0.

### Object and interface references

A class or interface name is a nullable reference type. Assignment, parameter
passing, and return copy the reference rather than the object. `null` converts
to every reference type but not to a primitive type.

Implicit widening conversions include subclass to superclass, class to an
implemented interface, subinterface to superinterface, and every class,
interface, or array reference to `ironwood.lang.Object`. Primitives are not
objects and do not widen to `Object`. These conversions
apply to initialized locals, assignments, fields, arguments, and returns and
are explicit no-op conversion instructions in compiler-owned IR. Cast syntax
uses the same generic-aware type grammar. Identity casts and casts that spell an
already-valid widening reference conversion remain no-op reference conversions.
A checked cast between non-parameterized class/interface references, or to a
generic type whose arguments are all unbounded wildcards, is accepted
when the complete closed program contains at least one concrete runtime type
compatible with both source and target. `null` succeeds and remains null; a
non-null mismatch throws `ironwood.lang.ClassCastException` through the ordinary
language exception mechanism. A statically disjoint cast is rejected instead of
emitting an always-failing operation. Successful checked casts preserve the same
reference and allocation identity, including for safe-`free` analysis.

Concrete parameterized narrowing casts are accepted only when the source type
and complete hierarchy prove every overlapping runtime implementation has the
exact target generic view, leaving only raw nominal membership to test. For
example, a `Source<String>` to `Target<String>` cast can be valid under that
proof. A cast from unconstrained `Object` to `Box<String>`, a conflicting
generic view, or a type-variable-dependent erased argument is rejected as
unchecked. `(Box<?>) value` remains an erased reifiable cast. Reifiable array
targets use exact recursive descriptor identity: `(int[][]) value` and
`(Box<?>[]) value` are checked casts, while `(Box<String>[]) value` is rejected.
Distinct invariant array types are statically disjoint. Casts between numeric
primitive types are accepted and lower to explicit typed conversion IR.

Equality and inequality apply binary numeric promotion to numeric operands,
accept two booleans, a reference and `null`, two
`null` literals, or reference types whose possible concrete classes overlap in
the closed compilation set. Unrelated class references and reference types with
no possible common concrete value are rejected.

`value instanceof Type` requires a reference or `null` operand and a declared
class, interface, or reifiable array target. A generic target must spell an
unbounded wildcard in every argument, such as `value instanceof HashMap<?, ?>` or
`value instanceof HashMap<?, ?>[][]`; concrete parameterized, raw-generic, and
type-variable targets are not reifiable and are rejected. Nominal tests use raw
membership because generic arguments have no runtime identity. Array tests use
exact recursive array descriptor identity. Both return false for `null`; the
target does not perform a cast.

`value instanceof Type name` combines that test with a named reference binding.
The operand is still evaluated once and `null` still does not match. On the
matching path, `name` is a typed alias of the tested value rather than a cast,
copy, allocation, or ownership transfer. `instanceof final Type name` makes the
binding nonassignable. The binding is definitely matched through Java-shaped
`!`, `&&`, `||`, conditional-expression arms, `if` branches and non-completing
guards, and `while`/basic-`for` bodies and exits that cannot be bypassed by a
reachable `break`. It is visible only on paths where success is proved.
Same-name bindings whose true or false scopes overlap are rejected. Pattern
targets obey the same reifiability rules as plain `instanceof`; primitive,
unnamed `_`, record, and switch patterns are not accepted.

Field access and instance calls use `.`. After the receiver and any call
arguments are evaluated in source order, a null instance receiver raises a
catchable `NullPointerException`. Raw addresses and pointer arithmetic are not
exposed in source.

`Object` declares the Java-shaped public methods `equals(Object)`, `hashCode()`,
and `toString()`. Unless a subclass overrides them, `equals` compares reference
identity, repeated `hashCode` calls return the same opaque identity hash for the
allocation's lifetime, and `toString` returns
`<qualified-runtime-type-name>@<lowercase-hex-identity-hash>`. The hash may
collide and does not expose a source-level address. The default runtime-created
`toString` result is a fresh caller-owned String and may be reclaimed with
`free` after all aliases are dead. `Serializable`, `Cloneable`,
`Object.clone()`, finalization, reflection through `Class`, and monitors are not
implemented.

Ironwood has no universal or compiler-generated object-copy operation. A type
that supports copying defines an ordinary copy constructor, `copy()` method, or
domain-specific operation and documents whether each referenced value or
external resource is shared or duplicated and who owns the result. The name
`clone` is legal but has no special meaning: a declared `clone()` is analyzed
and dispatched exactly like any other method and does not bypass construction.

`ironwood.lang.System.identityHashCode(Object)` returns zero for `null` and the
same opaque allocation-identity hash used by the default `Object.hashCode()` for
every non-null object. It is a static compiler/runtime intrinsic and never
dispatches to a class override. `System.allocationCount()` returns a monotonic
process-wide `long` diagnostic count of successful ordinary object, array, and
runtime-created String allocations. Immortal compiler-emitted string literals
and runtime-private native exception wrappers are not language allocations and
are excluded. The counter is observational: reading it neither allocates nor
resets it, so a program can compare snapshots around a warmed-up operation to
verify its allocation-free steady state. `System.arraycopy(Object, int, Object, int,
int)` copies between arrays with `memmove` overlap semantics. The current
conservative form requires both runtime operands to have the same exact
invariant array descriptor; it does not perform Java's per-element covariant
reference checks because Ironwood arrays are not covariant.

### Generic types

Classes, interfaces, methods, and constructors may declare type parameters. An
omitted bound admits exact reference arguments and all eight primitive
arguments; upper bounds may contain one class
followed by interfaces, including dependent and well-founded recursive bounds:

```java
interface Source<T> {
    T get();
}

class Box<T extends CharSequence> implements Source<T> {
    private T value;
    Box(T value) { this.value = value; }
    @Override public T get() { return value; }
    void set(T value) { this.value = value; }
    T[] array(int length) { return new T[length]; }
}

Box<String> box = new Box<>("value");

static <T extends CharSequence> T first(T value) {
    return value;
}
```

Arguments are exact and invariant. Explicitly bounded parameters remain
reference-only, including `T extends Object`. Exact substitutions flow through
owners, fields, constructors, methods, inheritance, overload selection,
overriding, and dispatch. A static member cannot use its owner's variables, but
a static generic method may declare its own.

Reference arguments share one pointer-shaped native declaration. A used
primitive argument materializes a deterministic closed-world native layout and
callable shape: `Box<int>` stores an `i32`, `Box<long>` stores an `i64`, and
mixed shapes retain pointers only in their reference positions. Primitive
generic inference, diamond, constructors, arrays, and polymorphic calls use the
same exact source substitutions. No boxing, wrapper, runtime tag, generic
registry, or hidden allocation is introduced.

A primitive-specialized body may transport, compare, store, return, or array
its value. It is rejected if it requires `null`, conversion to `Object`, an
`Object` member, throwing the value, or other reference-only behavior.
Primitive shapes do not carry their raw generic declaration's wildcard
membership, preventing a pointer-shaped `G<?>` view from observing native value
storage. They retain exact specialized parent identities for source-provable
casts. Static generic-declaration state and one-time initialization remain shared.

Members available through a type variable are the members guaranteed by all of
its bounds. The first bound supplies ABI erasure; other intersection bounds
remain available to source checking. Repeated erasures, cycles, invalid bound
ordering, and conflicting exact generic views are rejected.

Unbounded, upper-bounded, and lower-bounded wildcards provide Java-shaped
use-site variance:

```java
Box<String> strings = new Box<String>("value");
Box<? extends CharSequence> source = strings;
CharSequence value = source.get();
Box<? super String> sink = strings;
sink.set("other");
```

Each receiver and argument wildcard occurrence receives a fresh capture with
its upper and lower bounds. Reads use the upper bound; writes require the lower
bound, while `null` remains writable through every reference capture. Capture
identity is stable across one planned invocation but is never shared between
unrelated wildcard expressions.

Callable type arguments may be explicit or inferred per overload candidate
from arguments, receiver substitutions, declaration bounds, wildcard captures,
and an expected target. Diamond performs the analogous class inference before
constructor inference. Ambiguous or unsatisfied constraints are diagnosed
instead of defaulting unsafely to `Object`.

Ironwood does not support varargs. A parameter declaration containing `...`
receives a direct diagnostic recommending an explicit array parameter. Calls
are fixed-arity and never synthesize a hidden argument array; libraries use
explicit arrays or selected fixed-arity overloads instead.

Static nested types have only their own variables. A member inner type retains
both the exact owner arguments and its own. Local and anonymous classes retain
the callable and owner variables in lexical scope; anonymous diamond preserves
an internal inferred superclass view without exposing synthetic variable names.
With anonymous diamond, every declared non-private instance method must override
an inherited method; private helpers remain permitted.

The compiler retains parameterized types, wildcards, captures, and variables
through AST, semantic analysis, and typed IR. Native lowering shares one layout,
descriptor, dispatch table, and body for each raw declaration because every
permitted argument has pointer layout; a type parameter erases to its first
bound only at the native ABI. Runtime identity is consequently the raw
class identity, with no reified generic registry or runtime class loading.

Raw uses, automatic boxing/unboxing, `new T()`, non-reifiable `instanceof`, and
casts whose erased arguments cannot be source-proven are deliberately rejected.
Raw types exist in Java primarily for compatibility with code written before
generics and would reintroduce unchecked conversions and heap pollution.
Non-reifiable tests and unchecked casts would require pervasive runtime generic
metadata or an unsafe warning escape; Ironwood chooses neither. Primitive type
arguments remain on the roadmap through native specialization and
allocation-free value layouts, not hidden wrapper allocation.
Wildcard-parameterized types cannot be constructed or used as superclass or
superinterface declarations. A generic class cannot directly or indirectly
extend `Throwable`; catch targets cannot be type variables or non-reifiable
parameterizations. Overloads whose signatures
collide after type-variable erasure are also rejected. `new T[length]` is
supported for a reference type parameter; the array remains statically
invariant and does not carry a reified component-class check.

### Arrays

Ironwood implements recursive arrays for every primitive type plus class,
interface, generic-reference, type-parameter, and array element types.
`new T[length]` accepts `byte`, `short`, `char`, or `int` after unary numeric
promotion, zero-initializes every element, and returns a nullable array
reference. `new T[length][]` allocates only that named outer array and initializes
every child slot to `null`; a later sized dimension such as `new T[2][3]` is
rejected so source syntax never hides separately owned child allocations.
`array[index]` accepts the same promoted index types, reads or writes an element,
and `array.length` is a read-only `int`. A negative allocation length raises
`NegativeArraySizeException`; a load or store outside
`0 <= index < length` raises `ArrayIndexOutOfBoundsException`; and `.length`,
load, or store through a null array raises `NullPointerException`. Each is an
ordinary catchable unchecked exception with the source operation in its trace.

An array variable or field declaration may use `{ element, ... }`, and an array
creation expression may use `new T[] { element, ... }`; a trailing comma is
permitted and `{}` creates a zero-length array. The declared or explicit array
type supplies the contextual element type. Each element uses ordinary
assignment conversion and is evaluated and stored left to right after the
current array has been allocated. A nested `{ ... }` is valid only when that
element type is itself an array and recursively creates one ordinary child
allocation with its exact written length.

Initializer-created children use the same constant-slot ownership proof as
separately named children. Loading a child from a known local constant slot
preserves its allocation identity; overwriting the slot with `null` or freeing
the outer container detaches it. An attached slot blocks child reclamation,
escaped containers conservatively escape their children, and outer-array
reclamation never recursively frees them. Each written brace level is therefore
an ordinary tracked allocation rather than an unowned hidden rectangular-array
mechanism.

Array assignment is invariant: `Child[]` does not convert to `Base[]`, even
when `Child` extends `Base`. A reference-array element still accepts ordinary
assignment-compatible values such as a `Child` in a `Base[]`. `null` converts
to every array type, and array `==`/`!=` compare identity. Every array widens to
`Object`, supports the three inherited `Object` methods, and may be tested with
`array instanceof Object`. Java-style array covariance is deliberately absent:
it is statically unsound, needs runtime store failures, and complicates alias and
reclamation reasoning. A reifiable array `instanceof` or checked cast compares
the complete exact descriptor, including every nested array dimension and the
erased leaf type. `null` fails a test and passes a cast; a non-null mismatch
throws `ClassCastException`. Array-specific methods beyond the documented
intrinsics are not implemented.

`System.arraycopy` accepts the arrays through `Object`, then validates null,
array kind, exact element type/width, signed positions and length, and both
ranges before copying. A zero-length copy at an array end is valid. Invalid
calls terminate predictably with `NullPointerException`,
`IllegalArgumentException`, or `IndexOutOfBoundsException`-named diagnostics at
the current runtime failure boundary.

### Strings and standard streams

A String literal, including a cooked or raw text block, has type
`ironwood.lang.String`. Literals are pooled by decoded value within the final
program, immutable, immortal, and stored as contiguous UTF-16 code units in
compiler-emitted static memory. Equal values deduplicate across ordinary
literals, both text-block forms, source, `.ironclass`, and `.ironjar` inputs.
`==` and `!=` retain normal reference-identity semantics.
`String.equals(Object)` and `hashCode()` use Java-compatible UTF-16 content
semantics, and `toString()` returns the same immutable reference.

`String.length()` returns the number of UTF-16 code units, so a supplementary
Unicode scalar occupies two positions. `charAt(int)` returns one code unit and
throws catchable `StringIndexOutOfBoundsException` for a negative or too-large
index. `byteLength()` returns the exact UTF-8 byte count used for interop and
standard output. Paired surrogates encode as one supplementary scalar; an
unpaired surrogate is retained by String/StringBuilder operations and encoded
as U+FFFD by native text output. String.getBytes() instead uses Java's UTF-8
encoder replacement byte `?` (D117).

`substring(int)` and `substring(int, int)` validate Java-shaped UTF-16 ranges
and return a fresh caller-owned ordinary String, including for an empty or
whole-value range. The compiler/runtime range operation copies directly into
one exact-size String allocation and never borrows the source's storage.
`toCharArray()` likewise returns a fresh caller-owned array containing the
exact UTF-16 code units. These results retain fresh allocation identity through
closed-world wrapper calls and may be reclaimed with `free` when no alias
remains.

U1 makes `String` final and adds copy and checked `char[]` constructors. Each
copies into one distinct caller-owned String, borrows rather than retains its
source, and validates before allocation. Search, prefix/suffix, containment,
subsequence, `getChars`, and lexicographic `Comparable<String>` operations use
UTF-16 code-unit indices; code-point search recognizes supplementary pairs.
`concat` and character/integer/long text factories return caller-owned ordinary
Strings. Fixed boolean text and literals remain immortal.

D117 adds everyday String whitespace operations, literal replacement, offset
searches, repeat, byte/char snapshots and explicit joins. No-argument Unicode
case conversion uses fixed `en_US` behavior; no `ironwood.util.Locale` or
mutable locale state exists. Byte APIs use UTF-8. Transformations preserve the
fresh caller-owned result convention; see `STDLIB.md` and
`DIFFERENCES_FROM_JAVA.md` for exact method boundaries.

`CharSequence` declares `length()`, `charAt(int)`, `subSequence(int, int)`, and
`toString()`. Mutable
`StringBuilder` implements it with Java-shaped empty, capacity, String, and
CharSequence constructors; length/capacity/index/setLength/ensureCapacity;
chainable append/insert overloads for char, whole/ranged char arrays, String,
whole/ranged CharSequence, Object, boolean, int, long, float, and double;
`delete`, `deleteCharAt`, `replace`, `setCharAt`, surrogate-aware `reverse`,
whole/offset `indexOf`, `isEmpty`, and immutable `substring`/`subSequence`/`toString`
snapshots (D118). Growth uses
`old * 2 + 2`, at least the requested capacity, with overflow rejection. D061
implements source `+`/`+=` concatenation as Feature 76;
constant expressions join the final-program literal pool, while each maximal
dynamic chain produces one ordinary exact-size non-interned allocation rather
than a hidden builder graph. Object operands use `toString()` temporarily.
Fresh owned renderings are reclaimed after use and during exceptional cleanup,
while borrowed renderings are preserved.
`StringBuilder.toString()` returns a caller-owned fresh String that never
aliases builder storage. Freeing a proven fresh builder runs its destructor and
reclaims the backing array; failed construction rolls back a completed backing
array before reclaiming the incomplete builder.

D119 deliberately makes `StringBuilder.setLength(int)` return its receiver
instead of Java's void, enabling reset-and-append chains. Truncation, zero
filling, bounds failures and allocation behavior are unchanged; the result is
an alias of the existing builder, not a fresh allocation.

String literals are canonicalized by the compiler across the final linked
program; no runtime method populates that finite pool. Java's process-global
`String.intern()` is deliberately unsupported under Feature 77 because it would
retain arbitrary ordinary Strings through hidden aliases. A future explicit
application-owned interner remains ordinary collection design rather than a
`String` method.

`ironwood.lang.System.out` and `System.err` are non-null `public static final`
references to distinct immortal `ironwood.io.PrintStream` objects. Their
`print`/`println` overloads encode String/CharSequence/Object/primitive values to UTF-8
synchronously; `println` appends one LF and null prints `null`. `flush()` and
`checkError()` expose the native stream state. The objects are emitted in the
native image, excluded from allocation counting, and cannot be explicitly
freed. Passing an object to `print(Object)` or `println(Object)` borrows it when
every possible closed-world `toString()` target is proven not to publish its
receiver. Such an object may still be reclaimed afterward. A genuinely
publishing or unknown target blocks that `free`. `System.lineSeparator()` is
the immortal LF String on supported macOS
and Linux hosts. A non-null `System.getenv(String)` result is a fresh
caller-owned String. `currentTimeMillis()` is wall-clock time and `nanoTime()`
is a monotonic duration source with an arbitrary origin. `System.in`, stream
replacement/construction, formatting, and close semantics remain deferred.

The Ironwood-specific `CharSequence` overloads read `length()` once and then
stream `charAt(int)` results without calling `toString()` or creating a managed
snapshot. They combine surrogate pairs before UTF-8 encoding. String arguments
continue to select the more-specific String overload, while an Object-typed
argument continues to select the Object overload.

### Fields and initialization

Fields may have primitive, class-reference, interface-reference, or array types.
Instance fields may have declaration initializers, and a class body may contain
instance initializer blocks. A named class body may also contain `static { ... }`
initializer blocks; interfaces and anonymous-class bodies may not. Every static
field uses one closed-world global whose storage begins at the type's zero or
`null` value. Runtime-valued field initializers and static blocks execute once,
in textual order, when the declaring type is initialized.

Compile-time `static final` primitive constants accept the existing literal,
operator, conditional, numeric-cast, and accessible constant-reference forms.
They are preinitialized and reading one does not initialize its owner. Every
other initializer is ordinary runtime Ironwood code and may call methods,
construct objects and arrays, and read runtime state. The compiler owns the
preinitialized immortal `System.out` and `System.err` singletons as separate
intrinsics. A blank
class `static final` may instead be assigned exactly once by its declaring
type's static initialization actions and must be definitely assigned when they
complete; interface fields require initializers. Illegal forward references and
self-references in same-type static initialization are rejected. An instance
final may use a declaration initializer or be a blank final
assigned exactly once on every normally completing nondelegating constructor
path. Reads before definite assignment and later assignment/update are errors;
`this(...)`, branches, loops, and abrupt paths participate in the proof.

Initialization is an active-use operation. The selected entry type initializes
before `main`; construction initializes the constructed class; and a static
method call or nonconstant static-field read/write initializes the member's
declaring owner. Merely mentioning a type, testing or casting it, reading a
compile-time constant, or qualifying an inherited member through a subtype does
not initialize that subtype. A class initializes its superclass first, followed
by the source-order, depth-first superinterface prerequisites that declare or
lead to default methods. Directly initializing an interface does not initialize
its parents. Member, static nested, and local named types have independent state.

Each retained type progresses privately through uninitialized, initializing,
initialized, or failed state. A reentrant request for an initializing type
returns immediately and can observe partial zero/null state. If an unchecked
exception escapes, the type becomes failed and later active uses throw the same
object, preserving its first trace and any secondary failures. Static
initialization has no `throws` clause, so checked exceptions must be caught
inside it. Ironwood has no threads, so initialization uses no lock or waiting
protocol.

Every allocation is zero-initialized before construction. The complete object
is laid out base-first. For each class, its superclass constructor completes,
then that class's field initializers and initializer blocks run once in textual
order, then the remaining constructor body runs. A `this(...)` delegation does
not repeat initialization.

Within a body, an unqualified name first resolves to an active local or parameter
and then to a lexically selected declared or inherited field; instance fields
still require `this` to be available, while static fields do not. Fields are
hidden, never overridden: `expression.field` uses the receiver's static type,
`super.field` begins at the direct superclass, and each declaration retains a
distinct owner-qualified layout identity. `Type.field` is the qualified static
form.
Accessing a static field through an object or an instance field through a type is
rejected. The left side of assignment must be a local, parameter, mutable
accessible field, or array element, and its value must be assignment-compatible.
For field and array compound assignments or updates, the receiver and index are
evaluated exactly once before the right operand. Storing a tracked reference in
a static field publishes it and blocks a later safe `free`; a static reference
load has unknown allocation provenance.

### Constructors, `super`, `new`, and `this`

A constructor has the same name as its class and no result type. A class may
overload constructors by parameter types. Two constructors with the same
parameter signature are duplicates. A class without a declared constructor
receives a synthesized no-argument constructor with the class's visibility.

Every non-root constructor invokes its direct superclass constructor first. An
explicit `super(arguments);` is allowed only as the first constructor action. If
omitted, the compiler inserts `super();`; synthesized constructors do the same.
A constructor may instead delegate to another constructor of the same class
with `this(arguments);` as its first action. The selected delegation eventually
invokes a superclass constructor; recursive delegation cycles are compile-time
errors. A missing, inaccessible, ambiguous, or argument-incompatible constructor
is a compile-time error. A root constructor cannot invoke `super`.
`super.field` selects the accessible direct-superclass field and
`super.method(...)` directly invokes the selected superclass implementation,
bypassing virtual dispatch. `InterfaceName.super.method(...)` selects a
permitted direct-superinterface default.

`new Class(arguments)` allocates and zeroes the full object, installs its runtime
type identity, invokes the selected constructor chain, and produces the class
reference. Abstract classes and interfaces cannot be instantiated. Diamond may
infer class arguments; explicit or inferred generic constructor arguments are
checked before invocation. An anonymous body creates a deterministic
closed-world subclass or interface implementation and forwards to the selected
super constructor.

`this` is available in constructors and instance methods and has the declaring
class's reference type. `Outer.this` walks a valid lexical enclosing-instance
chain; qualified or implicit inner construction supplies the corresponding
hidden enclosing receiver. It is illegal when that chain is unavailable or
from a static context. A constructor may use
`return;` or fall through, but cannot return a value; superclass construction has
already occurred before either path.

### Destructors and failed construction

An ordinary, abstract, nested, local, or anonymous class may declare exactly
one unmodified `destructor { ... }`. Interfaces, enums, and enum-constant bodies
cannot declare one. A destructor has no parameters, result type, modifiers,
overloads, override directive, or explicit `return`.

An accepted object `free` invokes the runtime-selected most-derived destructor.
Normal completion continues directly with the superclass destructor, so cleanup
order is derived-to-root and every declared body runs exactly once. A
destructor may free a compiler-proven owned field directly; that field is
cleared before its child destructor runs. Array `free` has no destructor body
and still releases only the array container.

Closed-world typed-IR effects require every reachable destructor path to be
allocation-free, prevent `this` publication or resurrection, and reject an
exception that can escape. Calls that may throw are permitted only when the
exception is handled inside the destructor. A runtime-observed destructor
escape is a fatal invariant violation.

If construction throws, the incomplete receiver's source destructor does not
run. The compiler emits a separate rollback callable that releases zero-or-more
already acquired owned fields in reverse acquisition/layout order, including
normal destruction of completed child objects, then raw-deallocates the
incomplete receiver and rethrows the exact original exception. `this(...)`
delegation still rolls the allocation back exactly once. Publishing in-progress
`this` is a compile-time error.

Static nested classes carry no enclosing receiver. Member inner classes carry
one immutable hidden enclosing reference. Block-scoped local classes and
anonymous classes may additionally capture final or effectively-final locals
and parameters; any plain, compound, prefix, or postfix write invalidates
capture. Captures become compiler-owned hidden fields/constructor operands and
are ordinary aliases for escape and safe-`free` analysis. Types in the same
top-level lexical nest may directly access one another's private members.

### Enums

An enum body's comma-separated constants precede an optional semicolon and
ordinary class members. A constant may pass fixed arguments to a selected enum
constructor. Enum constructors are implicitly private; unmodified or explicit
`private` spelling is accepted, while `public`, `protected`, generic, varargs,
and explicit `super(...)` enum constructors are rejected. `this(...)`
delegation is supported, and a private no-argument constructor is synthesized
when none is declared. Source cannot construct an enum with `new`, assign a
constant field, or derive a named subclass. A constant-specific class body
becomes a compiler-owned final subtype. It may declare ordinary instance fields
and initializer blocks, methods, and member types, but not a constructor,
abstract method, or static initializer block. The enum may declare an abstract
instance method only when every constant has a body that supplies it; all
interface and abstract obligations are checked on each concrete constant type.

Each constant is a `public static final` field backed by one compiler-emitted
immortal object with its concrete enum or hidden-subtype layout and descriptor
identity. The objects
do not use the Ironwood allocator, cannot be passed to `free`, and do not change
`System.allocationCount()`. Enum initialization first constructs constants in
declaration order, storing a constant field only after its constructor
completes, then executes source static fields and blocks. Reentrant code can
therefore see completed earlier constants and `null` for the current or later
ones. Normal D055 prerequisites and exact-object failure caching apply; checked
exceptions must be caught before they can escape initialization.

Every enum synthesizes `public final String name()`, `public final int
ordinal()`, default `public String toString()`, `public static int
valueCount()`, `public static E valueAt(int)`, `public static E
valueOf(String)`, and `public static E[] values()`. A source `toString()`
override replaces the default. Invalid
index, unknown name, and null name lookups throw
`ironwood.lang.IllegalArgumentException`. Each `values()` call returns a fresh,
mutable, caller-owned array in declaration order. Its elements are immortal
enum constants, so the caller reclaims only the shallow array. Ordered code may
still use allocation-free `valueCount()` and `valueAt(int)` traversal.

Enums widen to `Object` and implemented interfaces and otherwise participate
in ordinary overloads, arrays, reference generics, casts, `instanceof`, and
identity `==`/`!=`. Unrelated enum types never interconvert. A classic enum
`switch` requires unqualified labels from the selector's exact enum type,
evaluates and null-checks the selector once, and dispatches by declaration
ordinal. It retains the integral switch's fallthrough, scope, `break`, and
return rules; qualified/wrong-enum labels, null labels, and exhaustiveness-based
completion are rejected.

### Member lookup, overriding, dispatch, and visibility

Member lookup begins at the receiver's static type and proceeds through its
ancestors. Private members remain local to their declaring class and are not
override candidates. Same-name methods with different parameter signatures are
overloads; an inherited method is overridden only by the same full parameter
signature. An instance override may preserve its return type or narrow a
reference return to a subtype. It cannot narrow visibility or change between
static and instance form. Interface implementations must be public instance
methods with a compatible signature. A final method cannot be overridden; a
final class cannot be extended. Every source method that overrides an inherited
instance method or implements or redeclares an inherited interface method must
carry the exact built-in directive `@Override`; omission is an error. A marked
method with no valid inherited instance target is also an error. The directive
may be interleaved with method modifiers or placed on a preceding line. It is
method-only, takes no arguments, and does not introduce general annotation
syntax or semantics. Abstract obligations, interface defaults, re-abstraction, and
erased generic signature clashes are resolved before concrete dispatch tables
are built.

The exact built-in directive `@Test` may mark a concrete, parameterless,
non-generic instance method returning `void`. It is valid only in a concrete,
non-generic subclass of `ironwood.testing.TestSuite` that is top-level or
static, has a no-argument constructor, and does not declare its own
`run(int)` or `main(String[] args)`. The compiler generates those two methods:
`run(int)` dispatches the marked methods in source declaration order, while
`main(String[] args)` creates one suite and one `TestRunner`, invokes the runner
for each method under its exact source name, and returns `TestRunner.finish()`. The
testing archive remains an explicit compile-time and link-time dependency.
`@Test` is contextual compiler syntax, not an annotation or an ordinary
reserved identifier. The identifier `Test` remains legal elsewhere.

Overload applicability is planned candidate by candidate, including generic
inference, capture conversion, exact owner substitution, and fixed-arity
applicability. Ironwood selects the single most-specific instantiated signature.
If there is no applicable signature, or if unrelated applicable signatures
remain equally specific, compilation fails at the call.

Static calls, constructor calls, and private instance calls are direct. Other
class instance calls are virtual and interface-typed calls use interface-call IR.
Both indirect forms use the same compiler-assigned, whole-program signature slot
table at runtime; interface references remain ordinary object references rather
than fat pointers. Closed-world class-hierarchy analysis replaces a virtual or
interface call with a direct call exactly when every possible concrete receiver
resolves the signature to one linkage target.

Fields, constructors, and class methods may be `public`, `protected`, `private`,
or package-private. `private` access is limited to the declaring top-level
source nest, including member, local, and anonymous types and access through
another instance. Package-private members are
accessible only within the declaring package. `protected` additionally permits
access from subclasses in another package, subject to Java's receiver rule: an
instance member accessed from such a subclass must use `this` or a receiver whose
static type is that subclass or one of its subclasses. Override
access-narrowing rules are enforced across packages.

### Exceptions

`throw expression;` evaluates its expression and raises that object through the
native stack. The standard library provides `ironwood.lang.Throwable`, whose
no-argument and `String` constructors establish a nullable message exposed by
`getMessage()`. `Error` and `Exception` form its two initial branches;
`OutOfMemoryError` extends `Error`, while `RuntimeException` and the common
argument, state, index, unsupported-operation, arithmetic, null-pointer,
class-cast, and no-such-element exceptions form the unchecked branch.
Constructor-supplied and single-assignment mutable causes,
`printStackTrace()`/`printStackTrace(PrintStream)`, `fillInStackTrace()`, and
fresh public stack-trace snapshots are supported. Java suppressed-exception
arrays, mutable stack-trace replacement, and serialization remain absent.
The common cause-bearing roots and wrappers provide no-argument, message,
cause, and message/cause constructor forms; a cause-only constructor derives
its message from the cause's description.
Ironwood also exposes ordered secondary exceptions and automatic uncaught
source traces as described below.

The static type of every thrown expression must derive from `Throwable`. A type
variable may be thrown only when its bounds prove that requirement. Primitive
values, `void` expressions, and unrelated references cannot be thrown. The
`null` literal and a null throwable reference are accepted as throw operands
and raise a catchable `NullPointerException` at the throw statement.
Subtypes of `RuntimeException` and `Error` are unchecked. Every other
`Throwable` subtype is checked. Class methods, interface methods, and
constructors accept a comma-separated `throws` clause. A checked exception from
an explicit `throw` or from the compile-time selected method/constructor must be
caught by an enclosing compatible catch or declared by the current callable.
The check uses the callable selected after overload resolution and generic
substitution, so `<E extends Exception> void fail(E value) throws E` preserves
its exact invocation contract. A throws type must be a non-parameterized
`Throwable` class or a suitably bounded type variable.

A try statement contains one or more typed catch clauses, a `finally` block, or
a combination of them. It requires at least one catch or finally clause. Java's
`try (...)` resource syntax is deliberately rejected; resources are created by
ordinary declarations and closed explicitly in `finally`. A catch type must be an accessible
reifiable `Throwable` class or a `|`-separated union of such classes; type
variables and parameterized catch targets are invalid. Alternatives in one
union must be pairwise disjoint: duplicates and subtype/supertype pairs are
rejected. A generic class cannot
directly or indirectly extend `Throwable`, although a generic method or a
non-generic static nested type in a generic owner remains valid. The runtime concrete
object is tested against each catch type in source order using the same
transitive nominal class/interface membership as `instanceof`. Union
alternatives are tested with the same existing membership operation and join
one shared body. A later catch is
rejected when an earlier catch has the same type or a supertype of it. The catch
variable is non-null and visible only inside that catch block. A single-type
binding has its declared type and may be declared `final`; a union binding has
the least common nominal supertype of its alternatives and is implicitly
final. Both forms may be captured by local or anonymous classes. A direct
`throw failure;` of a final or effectively-final catch binding uses the exact
checked types that can reach that catch after prior-catch filtering and generic
`throws` substitution. Assigning or updating an ordinary single-type binding
restores its broader declared thrown type. Rethrow preserves the same caught
language object;
exceptions thrown by a catch body are considered only by enclosing try
statements, never by sibling catches.
An overriding method may omit or narrow inherited checked exceptions, but it
cannot introduce an incompatible checked exception. A catch for a checked type
is rejected when no checked exception from its try body can overlap it; a
narrow catch remains reachable when an invocation declares a broader compatible
exception, although it does not fully handle that broader contract. Catches that
admit unchecked exceptions remain legal without a declared source.

`finally` executes exactly once after normal completion, a handled or propagating
exception, or a `return` from its associated try/catch. A return expression is
evaluated before the cleanup. If `finally` completes normally, the pending
completion continues. A return from `finally` retains its existing precedence.
A throw from `finally` supersedes a pending normal completion or return, but it
does not replace an exception already being propagated. If the protected body
or catch is propagating primary exception `A` and `finally` throws `B`, `B` is
appended to `A`'s ordered secondary-exception list and `A` continues. Nested
cleanup failures are flattened onto that same list in occurrence order. This rule applies to every
exception escaping `finally`; `close()` has no special compiler status.
Constructors that throw do not yield their allocated reference, and superclass
construction still completes before derived body code can execute.

`Throwable.getSecondaryExceptionCount()` returns the number of later failures
in that flat list.
`Throwable.getSecondaryException(int)` reads one by occurrence order without
allocating an array; an invalid index terminates with a deterministic bounds
diagnostic. The secondary list is distinct from cause: a later cleanup failure
did not cause the earlier primary failure. Association is compiler-controlled;
there is no public mutator. If `finally` begins without a pending exception, an
exception escaping it is primary and has no automatically added secondary.

Resource closure and allocation reclamation remain independent. A programmer
places `resource.close()` in `finally` to release an external capability and may
place a separate explicit `free` in the same cleanup when safe-free analysis
proves the wrapper allocation dead. Closing never recursively frees the wrapper
or anything it references. Multiple cleanup operations that must all run need
nested `try`/`finally` regions; for example, an inner `finally` can free the
wrapper even if `close()` throws. Ordinary statements following a throwing
statement are not executed. Cleanup crossed by `break`, `continue`, or `yield`
may contain a proven-safe `free`; its effects are visible at the destination.
A pending reference-valued `yield` keeps its allocation observable during
cleanup, so cleanup cannot free that result.

Native propagation uses the platform unwind ABI on the supported macOS and
Linux targets. An exception that reaches the generated native `main` after all
required `finally` blocks have run prints `uncaught Ironwood exception:
<qualified-type>`, followed by `: <message>` only when the message is non-null,
then one `at <qualified-callable>(<file>.iron:<line>)` entry per captured frame
from the failure outward. It prints each directly associated secondary
exception in occurrence order with its own optional message and trace, then
terminates with status 1. Checkedness adds no runtime tag: the same native
unwinding path carries checked and unchecked objects.

Ordinary Throwable construction invokes virtual `fillInStackTrace` to capture
its trace (D121). Throwing or rethrowing preserves that snapshot; calling
`fillInStackTrace()` replaces it and returns the same receiver. A no-op override
can keep an exception stackless. Constructor and fill frames belonging to the
captured Throwable's class hierarchy at the top of capture are omitted. The
innermost frame records construction or explicit refresh, while outer frames
record the corresponding call expression's line. Constructors use `Owner.<init>`
and nested or
anonymous owners retain their deterministic `$`-qualified identities. Only the
stable source basename is printed, never an absolute build path. Compiler-
inserted frame operations remain through `-O0` to `-O3` and describe source
calls even when LLVM inlines native machine code. Runtime-private trace storage
does not count as an Ironwood allocation and degrades to `<trace unavailable>`
if metadata reservation fails. `printStackTrace()` writes to `System.err`; its
PrintStream overload supports the existing stdout/stderr streams. It uses
virtual descriptions and causes, common-tail compression and cycle detection;
cleanup failures are labeled `Secondary:`. No mutable trace replacement is
exposed. `getStackTrace()` returns a fresh
caller-owned reference array. Its immutable StackTraceElement values and their
text fields are compiler-emitted process-lifetime objects, so callers reclaim
the array but not its elements.

### Primitive values and operators

Ironwood has signed two's-complement `byte` (8 bits), `short` (16), `int` (32),
and `long` (64), unsigned UTF-16-code-unit `char` (16), IEEE-754 binary32
`float`, IEEE-754 binary64 `double`, and logical `boolean`. Primitives are value
types and are never `Object` values. Integer operations wrap at their promoted
width and carry no implicit overflow traps.

Unary numeric promotion converts `byte`, `short`, and `char` to `int`. Binary
numeric promotion first applies that rule, then chooses `double`, `float`,
`long`, or `int` in descending order. It governs arithmetic, numeric comparison,
numeric equality, and integral bitwise operations. Unary `+`, unary `-`, and
`~` use unary promotion. `&`, `^`, and `|` also retain the separate two-boolean
form. `!`, `&&`, and `||` require booleans; the latter two short-circuit.

Integral division truncates toward zero and remainder has the dividend's sign.
Zero throws `ironwood.lang.ArithmeticException`; neither zero nor signed
`MIN_VALUE / -1` reaches LLVM undefined behavior. The overflow quotient is the
same minimum value and its remainder is zero for both `int` and `long`.
`int` shift distances are masked by 31 and `long` distances by 63; `>>` is
signed and `>>>` zero-fills. Floating arithmetic uses ordinary non-fast-math
IEEE operations. Ordered comparisons are false for NaN, `!=` is true for NaN,
and positive and negative zero compare equal.

Assignment, parameter, and return conversions include Java primitive widening.
Assignment contexts additionally narrow an integral constant expression of
type `byte`, `short`, `char`, or `int` to `byte`, `short`, or `char` when its
value is representable; invocation does not perform that constant narrowing.
Explicit numeric casts allow both widening and narrowing. Floating-to-`int` or
`long` casts truncate toward zero, map NaN to zero, and saturate outside the
target range. Floating casts to `byte`, `short`, or `char` first perform the
Java `int` conversion and then narrow. Compound assignments and `++`/`--`
implicitly convert their promoted result back to the lvalue type.

Conditions for `if`, `while`, `do`/`while`, and classic `for` must be boolean; numeric and reference
values are not truthy. A classic `switch` selector is evaluated exactly once
and must have type `byte`, `short`, `char`, or `int`. Every `case` is a
side-effect-free compile-time integral constant compatible with the selector;
equal converted values and multiple `default` labels are rejected.

The conditional expression `condition ? whenTrue : whenFalse` evaluates exactly
one branch. Numeric branches use binary numeric promotion; other branches must
have the same primitive type, the same reference
type, a reference and `null`, or two references where one widens to the other;
the result uses that common type and an SSA phi. Assignment is a right-associative
expression. Plain and compound assignments are implemented for locals, fields,
and array elements; `++` and `--` have their Java prefix/postfix value behavior
and accept any numeric lvalue. A discarded expression is legal only when it is an
assignment, increment/decrement, method call, or object creation.

Operator precedence, from tightest to loosest, is postfix member access/calls,
postfix update, unary/prefix update/cast, multiplication/division/remainder,
addition/subtraction, shifts, ordering/`instanceof`, equality, bitwise `&`, `^`,
`|`, logical `&&`, logical `||`, conditional, then assignment. Binary operators
are left-associative except conditional and assignment, which associate right.
The lexer deliberately retains consecutive `>` characters as individual tokens:
the type parser consumes nested generic closes, while expression parsing composes
contiguous `>>`, `>>>`, `>>=`, and `>>>=` sequences contextually.

### Locals, scopes, and control flow

Local variables require an initializer and have lexical block scope. Their type
may be primitive, class, interface, or array. A local or parameter declared
`final` cannot be assigned, compound-assigned, or updated after initialization;
such bindings and bindings with no later write are capture-eligible. A local
cannot redeclare a parameter or
another local whose scope is active; sibling blocks may reuse a name after the
earlier scope ends. A classic `for` initializer may be empty, a local declaration,
or an expression; its local scope covers the condition, body, updates, and ends
after the loop. The condition and update clauses may be empty. A classic
`switch` body is one lexical scope shared by all groups. Direct entry at a later
label does not initialize declarations in an earlier group, so reads without a
value on every incoming path are rejected. Nested blocks give groups separate
scopes. Locals declared in an `if`, `else`, `while`, `do`/`while`, `for`, try, catch, or
finally body do not escape that body. A catch variable follows the same active
name-conflict rule and exists only in its catch body.

Every reachable path through a value-returning method must return an
assignment-compatible value. A `void` method may use `return;` or fall through.
Statements after a path has unconditionally returned are rejected as
unreachable. An `if`/`else` terminates a path when both branches terminate.
Consistent with Java, constant `if` conditions do not by themselves make the
following statement unreachable: `if (true) return;` and `if (1 == 1) return;`
still permit following statements.

A `while` or `do`/`while` with literal `true`, or a classic `for` with literal
`true` or no condition, has no condition-false exit. Without a reachable `break`
that exits that loop, it cannot complete normally: following statements are
rejected as unreachable, and a value-returning method needs no trailing return
on that path.
Reachable breaks include those inside constant `if` branches, following Java's
source reachability rules. Unlabeled breaks from nested loops or switches do not
exit the outer loop. Crossed `finally` blocks must complete normally for a pending break
to reach its destination; a surrounding exception handler may provide a
separate continuation after the guarded statement. Other loop conditions
retain conservative exit handling; this does not infer nontermination from
variable values, method bodies, or general constant expressions (D144).

Classic `switch` selects the
matching case or `default`, permits consecutive labels, and falls through later
groups until abrupt completion or the closing brace. An unmatched switch
without `default` completes normally; a switch with `default` and only abrupt
entry paths can satisfy value-return analysis. `break` exits the nearest loop
or switch. `continue` rechecks the nearest enclosing `while` condition, checks
the nearest `do`/`while` condition, or enters the nearest classic/enhanced
`for` update step, including when written in a switch nested in that loop.
A labeled transfer resolves to the lexically enclosing matching statement;
labeled `continue` requires that statement to be a loop. A `throw` terminates
its current path. Try and catch
fallthrough paths participate in the same return analysis, and an always-abrupt
`finally` determines the completion of the whole statement.

Integral selectors accept compile-time integral case constants. Enum selectors
accept only unqualified constants of the selector's exact enum type, perform one
null check, and dispatch by ordinal. Enum coverage does not make a switch
exhaustive for return analysis.

Modern non-pattern switch supports arrow-rule statements and switch
expressions. A selector is evaluated once and may have type `byte`, `short`,
`char`, `int`, one exact enum type, or `String`. Case labels are compatible
compile-time constants; one rule may contain comma-separated constants.
`case null` is valid for enum and String selectors, and the exact combined form
`case null, default` shares one rule. Without a null label, a null reference
selector raises the ordinary catchable `NullPointerException`. Arrow rules do
not fall through. Colon-form statements retain classic fallthrough, while
colon-form switch expressions transfer a result with `yield`.

A switch expression must declare `default` unless it covers every constant of
its exact closed-world enum selector. Arrow expressions and `yield` values are
checked in the available target context; otherwise their result type is merged
by numeric promotion, null/reference conversion, or closed-world least upper
reference type. All normal result paths meet in one typed value. A `yield`
crossing `finally` runs every cleanup from inner to outer before completing the
expression, and an abrupt cleanup supersedes that pending result. An unlabeled
`break` cannot exit a switch expression; labeled outer transfers and loop
`continue` retain their ordinary targets. Reference results remain subject to
the same conservative alias and safe-`free` proof as other structured
expression flow. Pattern labels, guards, record/unnamed patterns, and preview
primitive patterns remain deliberately unsupported Features 106–108.

`do`/`while` executes its body before its boolean condition. Enhanced `for`
evaluates its source expression once. An array source is null-checked and read
from index zero through its immutable length with ordinary bounds-checked
loads. An `Iterable<T>` source is null-checked, calls `iterator()` once, and
then calls `hasNext()` and `next()` with normal generic substitution. The loop
borrows the returned `Iterator` without allocating or freeing it; the producer
retains ownership, normally through a reusable iterator stored in the
container. The iteration variable has its declared element-compatible type,
may be `final`, and has body scope.

Return and labeled or unlabeled loop transfers are lowered through every
pending `finally` cleanup crossed on the path. Cleanup runs inner-to-outer; an
abrupt cleanup supersedes a pending transfer. A loop transfer that stays inside
an already-active outer try/finally remains direct.

## Explicit memory reclamation

The compiler defaults to `--unfreed=warn` for proven local allocation
abandonment. `--unfreed=error` rejects the same findings; `--unfreed=off` disables
this diagnostic without weakening safe-`free` checks. These modes do not insert
cleanup. Intentional omissions remain legal in the default mode, and an absence
of warnings does not establish leak freedom. Coverage and conservative omissions
are specified in [the memory model](MEMORY.md) and D140.

Ironwood does not reclaim ordinary objects based on reachability. An allocation
created by `new` remains allocated until a compiler-proven-safe
`free expression;` reclaims that exact allocation or the process terminates.
Omitting `free` is legal, but the allocation remains unreclaimed; a program that
continues allocating without enough successful `free` operations eventually
exhausts memory and terminates under the runtime's allocation-failure policy.

`free` does not clear only one variable or infer recursive ownership from every
field. For an object it runs the declared derived-to-root destructor chain;
arrays have no destructor. Ordinary assignments retain Java-like
aliasing. A `free` is accepted only when the compiler proves that no subsequent
observation is possible through any local, argument, return value, field,
static/global, array element, closure, call-mediated alias, widened class or
interface reference, native exception wrapper, catch value, or pending cleanup
path. When proof is uncertain, compilation fails at the `free`; removing it is
safe but retains the allocation.

One source `finally` block may be lowered into mutually exclusive cleanup copies
for normal, return, catch, exceptional, `break`, `continue`, and `yield` paths.
The ownership proof snapshots each predecessor independently, so an exactly-once
`free` in that source block
is not mistaken for a double free merely because the compiler emits several
copies. A join that may contain a freed allocation forbids its observation or
reclamation. Loops additionally check back edges: body-local allocations may be
recreated and freed each iteration, but a newly freed reference cannot be
carried into the next iteration. Reclaiming an entry allocation inside a loop
requires unchanged live ownership and identity on every continuing path.

After a successful `free local;`, `local` is not automatically set to `null`.
Its previous value is semantically dead: reading it, comparing it, passing it,
returning it, or freeing it again is rejected. A plain assignment that does not
read the old value may reuse the variable and begins tracking the replacement
value independently. Compound assignment reads the old value and therefore
cannot reuse a dead value.

The proof accepts a local reference to an object or array allocation created by
`new`, a closed-world-proven fresh-or-null factory result, and identities that
remain exact through supported structured control-flow joins.
It also accepts a local holding the previous allocation of a compiler-proven
exclusive private backing-array field after that field has been replaced by a
fresh array. Field eligibility is closed-world: every write installs a directly
created array, and no method may publish the field's container reference through
a return, throw, another field or array, construction, or a retaining/uncertain
call. Array element access and `.length` do not publish the container.

The proof tracks local reference conversions and aliases, permits an alias after
that alias's lexical scope ends, and uses conservative closed-world summaries
for direct and devirtualized calls. Such a call may observe a local allocation
without blocking `free` only when its implementation does not retain, return,
throw, or otherwise escape the reference. A call made while a borrowed backing
array is still installed in its field is conservatively considered potentially
reentrant; the field must be detached before the call or the later `free` is
rejected.

A compiler-proven-owned private helper may be returned as a dependent borrow
without transferring its allocation. This is the model used by reusable
collection iterators and primitive holders. The owner destructor may free the
helper. Source cannot free the borrowed helper independently; any later helper
use is rejected as use-after-free. A local borrow need not leave lexical scope
before owner destruction when it has no later observation. An exact wrapper
method may return the borrow while preserving its dependency on the original
owner. Storing, returning it out of the current allocation region, capturing,
or passing the borrow to a retaining or uncertain call instead marks the owner
escaped and rejects its `free`. Concrete helper provenance is carried
through its Java-shaped interface reference so ordinary closed-world-proven
`Iterator` calls do not create a false escape. Constructor containment of an
owner backlink is accepted only when the freshly installed owned helper keeps
that backlink private and never republishes it. Merging helper borrows from
different possible owners conservatively blocks `free` of every possible owner
while the merged borrow remains observable. Full scenarios are specified in
[Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

The current proof rejects a primitive, borrowed/mixed-origin call result,
caught exception, or other value without known owned allocation identity.
Owned fields are freeable directly only from their declaring destructor; an
arbitrary field expression remains ineligible. A private array is not sufficient by itself:
a getter, aliased parameter assignment, publication to another storage location,
or uncertain call makes the field ineligible. The proof also rejects a live
local alias, free before field replacement, conditional-only replacement,
field/return/throw escape, escaping constructor or call, genuinely polymorphic
call with uncertain targets, second free, and every later use through a tracked
local. Path-sensitive joins retain an identity only when every incoming path is
compatible. A detached owned backing array may cross a migration loop when its
SSA identity is unchanged. For a known local array and constant index, the
proof also records a directly stored child allocation. That child is not
freeable while the slot retains it; directly overwriting the slot with `null`
or freeing the outer container proves detachment. These conservative boundaries
may be relaxed by stronger proof without changing the safety rule.

Freeing an array releases only its container and never recursively releases its
reference elements. Constant-slot stores and loads through a known local array
preserve the tracked child allocation identity. Dynamic indices, escaped
containers, calls that can observe elements, and bulk copies remain
conservative and escape affected children. Primitive array elements introduce
no aliases.
`System.arraycopy` may retain element aliases in its destination. Until the
safe-`free` proof models per-element provenance across this bulk operation, a
locally allocated tracked destination is conservatively treated as escaped and
cannot subsequently be freed. The exclusive-backing-field proof separately
tracks container identity: exact `System.arraycopy` does not publish either
array container, so a detached old backing container can still be freed after a
copy. This does not make any copied element freeable.

The runtime may release its own implementation storage, such as transient native
exception wrappers, because those wrappers are not Ironwood objects. A failed
constructor runs compiler-generated rollback for every completed
compiler-proven-owned field in reverse installation order, then raw-deallocates
the incomplete receiver without running its source destructor. Construction
that may publish in-progress `this` is rejected.

## Specified but not implemented

- No numbered feature is currently committed and pending. D082 completes
  Feature 91 without selecting a subsequent target.
- Feature 104 hexadecimal floating-point literals remains the sole open
  promotion decision from the D070 candidate set.
- Broader general-purpose formatting and richer library APIs
- Method-level reachability/tree shaking and broader language-specific
  optimization beyond class-granular standard-library inclusion and the initial
  exact singleton-target devirtualization

Features 55, 76, 74, 51, 60, and 78 are complete under D060, D061, D062, D063,
D064, and D065.
D066 adds the documentation-only Java SE 26 Features 79–100 audit without
changing compiler behavior, creating a pending status, or selecting a target.
D067 confirms the selected exclusions and separates `volatile` as Feature 101;
the open items remain ❌ until an explicit decision promotes them.
D068 removes `assert` from the still-open Feature 90 and confirms it separately
as Feature 102; the open set is otherwise unchanged.
D069 commits Features 83, 92, and 100 without ranking them, confirms
intersection casts and Java-style octal literals as excluded Features 99 and
103, and separates still-open hexadecimal floating-point and allocation-failure
decisions as Features 104 and 105.
D070 commits Feature 105's bounded immortal allocation-failure contract without
ranking it or changing current compiler/runtime behavior. Its ownership-safe
evaluation order means the implemented feature is expected to be 💡.
D071 commits Features 86, 89, 90, and 101 without ranking them or changing
current compiler/runtime behavior. Static imports are compile-time lookup work;
array initializers and enhanced iteration must preserve explicit ownership;
transfers through `finally` must preserve cleanup; and `volatile` requires a
real atomic memory-ordering contract rather than LLVM volatile operations.
D072 narrows Feature 66 to reference type patterns for `instanceof`, keeps
Feature 91 exclusively non-pattern modern `switch`, and records pattern switch,
record/unnamed patterns, and preview primitive patterns separately as excluded
Features 106–108. It changes no current behavior or pending rank.
D073 commits Features 66 and 91, returns Feature 101 to excluded, confirms
Features 106–108 as deliberately excluded, and ranks the nine pending features
100, 66, 90, 92, 89, 83, 86, 105, and 91. It selects no implementation target.
D074 implements Feature 100 with catchable null, array-bounds, negative-length,
and null-throw failures while retaining the existing exception allocation,
trace, catch, `finally`, and secondary-failure contracts. It selects no
subsequent implementation target.
D075 implements Feature 66 with definite-match scope, typed SSA aliases,
capture and safe-`free` integration, reifiable nominal/generic/array targets,
artifact reconstruction, and native `-O0` through `-O3` behavior. It adds no
runtime operation or allocation and selects no subsequent implementation
target.
D076 implements Feature 90 with Java-shaped `do`/`while`, array and `Iterable`
enhanced `for`, empty and labeled statements, labeled transfers, and
inner-to-outer `finally` cleanup for every crossed `break`/`continue`. It adds
no hidden allocation or runtime operation, reconstructs from `.ironclass` and
`.ironjar`, and selects no subsequent implementation target.
D077 implements Feature 92 with Java-shaped disjoint union catches,
implicitly-final shared bindings, precise rethrow from final or
effectively-final catch parameters, typed union dispatch, and artifact
reconstruction. It adds no hidden allocation or runtime operation and selects
no subsequent implementation target.
D078 implements Feature 89 with declaration and `new T[]` initializers,
contextual element conversion, exact lengths, left-to-right typed stores,
recursive brace allocations, and constant-slot safe-`free` provenance. It adds
no runtime operation or implicit reclamation, reconstructs from `.ironclass`
and `.ironjar`, and selects no subsequent implementation target.
D079 implements Feature 83 with `0b`/`0B` tokens, binary-digit separator
validation, `L`/`l` typing, exact 32/64-bit two's-complement interpretation,
shared constant decoding, typed constants, and ordinary LLVM integer lowering.
It adds no runtime operation or ownership behavior, reconstructs from
`.ironclass` and `.ironjar`, and selects no subsequent implementation target.
D080 implements Feature 86 with single-member and on-demand static imports for
accessible fields, methods, and member types. It preserves Java-shaped
shadowing, duplicates, ambiguity, overload/generic selection, constant use,
active-use initialization, source/class/archive discovery, and tree shaking.
It adds no runtime operation, allocation, ownership rule, or native ABI and
selects no subsequent implementation target.
D081 implements Feature 105 with one compiler-emitted immortal
`OutOfMemoryError`, unwind-capable object, array, and String-result allocation
operations, bounded runtime-private trace and association storage, and explicit
source-catch state release. Exhaustion preserves evaluation order and
primary/secondary failure precedence, creates no owned object, and does not
increment the allocation count. A second allocation failure while the implicit
error remains active terminates deterministically. Feature 91 is the sole
remaining pending feature; no subsequent target is selected.
D082 implements Feature 91 with evaluated-once integral, enum, and String
selection; null and comma labels; non-fallthrough arrow rules; exhaustive
switch expressions; typed result phis; cleanup-preserving `yield`; shared
constant, initialization, exception, ownership, and typed-IR mechanisms; and
format-1 artifact reconstruction. It adds no runtime operation, hidden
allocation, or native ABI, leaves Features 106–108 excluded, and selects no
subsequent target. No numbered feature remains pending.

## Experimental

Nothing in the current source tree is designated experimental.

## Deliberately unsupported

- Runtime class loading and user-defined class loaders
- JVM bytecode as a program distribution or execution format
- JIT compilation and JVM deoptimization machinery
- Unrestricted runtime reflection and runtime-generated proxy/code facilities
- Raw pointers and pointer arithmetic in ordinary source code
- JNI as a compatibility requirement
- Garbage collection, automatic boxing/unboxing, Java array covariance, raw
  generic types, unchecked parameterized casts, and non-reifiable `instanceof`
- Varargs, because expanded calls hide an owning array allocation
- Javadoc facilities beyond the D098 IronDocs subset: `///` documentation,
  inherited documentation, DocLint, custom doclets, and HTML output. `irondoc`
  associates `/** ... */` comments with declarations, processes supported tags
  and links, and generates Markdown; see [IRONDOCS.md](IRONDOCS.md). Ordinary
  compilation continues to treat documentation comments as trivia.
- Java's runtime-selectable `assert` statement; use ordinary conditions and
  explicit thrown failures for validation that must run consistently
- Java-style leading-zero octal literals and intersection cast expressions
- General annotations. The compiler-owned `@Override` and `@Test` directives
  are not annotation instances. Records and sealed types; lambdas, closures,
  and method references; reference type patterns for `switch`;
  record/unnamed patterns; and preview primitive patterns are also unsupported.
- Threads, monitors, `synchronized`, source-level `volatile`, and a
  multithreaded memory model
- Java object serialization machinery, `Object.clone()`/`Cloneable` machinery,
  and GC finalization. Explicit type-owned copy operations remain ordinary code;
  even a method named `clone()` receives no special treatment
- Java's process-global `String.intern()` pool. Compile-time literals retain
  canonical identity; runtime canonicalization belongs in an explicit
  application-owned collection

## Decisions intentionally deferred

The final object header and stable ABI, a stable serialized `.ironclass` IR/ABI,
Windows exception lowering, future
non-null types, overflow checking modes
beyond the implemented wrapping operators,
runtime-created string ownership transfer across call boundaries, broader String transformation APIs,
package/module distribution model, and native FFI remain open. The
Milestone 7 descriptor/array/string layouts, unified signature table,
opaque-pointer LLVM lowering, language-owned unwind wrapper, and retaining
allocator are documented bootstrap choices rather than a stable external ABI.
