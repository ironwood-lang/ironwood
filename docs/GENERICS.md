# Ironwood generics design

## Status and scope

This document defines Ironwood's implemented generic model: shared native
layouts for reference arguments and closed-world native value specialization
for primitive arguments. The model is validated through semantic diagnostics,
typed IR, native execution, safe-`free` analysis, and source/class/archive
reconstruction.

It is a Java-shaped source model adapted to Ironwood's closed-world,
ahead-of-time compilation and explicit-reclamation rules. It deliberately does
not reproduce raw types, unchecked conversions, JVM signature attributes,
runtime generic reflection, or erasure-driven heap pollution.

This design covers exact reference and primitive arguments, bounds, wildcards
and capture, generic methods and constructors, invocation inference, diamond
construction, overload resolution, reifiability, casts, nested generic types,
and final-program native specialization.

## Design invariants

The following rules govern the implemented model:

1. Source and compiler-owned typed IR retain exact generic types. Erasure is an
   ABI operation, not an early type-checking shortcut.
2. A generic declaration has one native layout, type descriptor, dispatch
   table, and method body for all reference instantiations. Used primitive
   shapes receive deterministic specialized counterparts.
3. Every permitted reference argument has native pointer representation; every
   primitive argument retains its exact native value representation.
4. Generic classes and interfaces are invariant. Variance is expressed with
   Java-shaped use-site wildcards.
5. No runtime registry of parameterized types is introduced. Reference shapes
   use raw nominal membership; primitive shapes do not expose the raw generic
   wildcard membership that would imply a pointer ABI, but do retain exact
   specialized membership for source-provable casts.
6. Raw types and unchecked conversions are rejected rather than accepted with
   warnings.
7. Generic conversions must preserve allocation identity for safe-`free`
   analysis. Changing a static generic view never changes the native address.
8. Closed-world compilation materializes required primitive layouts, callable
   bodies, and dispatch slots before LLVM lowering, then may eliminate unused
   shapes. This never boxes a value or changes exact source typing.

## Current compiler behavior

The compiler supports:

- generic classes, interfaces, methods, and constructors with exact reference
  arguments, upper/intersection/dependent bounds, and first-bound erasure;
- all eight primitive arguments for type parameters with no explicit bound,
  including explicit/inferred class, interface, method, constructor, and
  diamond instantiations;
- explicit, invariant reference arguments, including nested parameterized
  types, reference arrays, and class type variables;
- exact substitution through fields, constructors, method signatures,
  generic superclass and interface edges, overriding, overload selection, and
  dispatch;
- the implicit `Object` upper bound and access to `Object` methods through an
  unbounded type variable;
- `?`, `? extends`, and `? super`, with fresh capture conversion for receivers
  and invocation arguments and safe producer/consumer access;
- explicit callable type arguments, argument/receiver/expected-type inference,
  least-containing parameterization, and diamond construction;
- generic owner/member types for static nested, inner, local, and anonymous
  declarations;
- fixed-arity invocation for ordinary and generic callables;
- reifiable `G<?>` casts and `instanceof` tests using raw nominal membership
  for reference-shaped instances;
- generic reference arrays such as `new T[length]`;
- source-provable concrete parameterized casts whose only runtime question is
  raw reference-shaped or exact primitive-specialized membership; and
- enums as ordinary final reference arguments and as implementations of
  parameterized interfaces; an enum declaration itself cannot introduce type
  parameters in the current enum model; and
- one shared native implementation per reference shape plus deterministic
  primitive class/callable/dispatch specializations with native value fields,
  parameters, returns, phis, equality, and array elements.

The compiler deliberately rejects raw types, `new T()`, non-reifiable
`instanceof` targets, and casts whose erased arguments cannot be proved from
source and the complete closed-world hierarchy. Primitive arguments are
rejected for explicitly bounded parameters, wildcard conversions, and generic
bodies that require reference-only operations.

## Type-parameter model

### Declarations and scope

Classes, interfaces, methods, and constructors may declare type
parameters:

```java
class Box<T> { }

interface Index<K, V> { }

static <T extends CharSequence> T first(T left, T right) { ... }

class Holder<T> {
    <U extends T> Holder(U initial) { ... }
}
```

A type parameter is in scope throughout its declaration's complete parameter
list and bounds, allowing recursive bounds such as:

```java
interface Comparable<T> {
    int compareTo(T other);
}

class Node<T extends Comparable<T>> { }
```

Method and constructor type parameters have their own scope and may shadow a
class type parameter, as in Java. A static member cannot use a class type
parameter, but a static generic method may use its own type parameters.

Each semantic type variable has an owner-qualified identity. Member variables
must also include a stable declaration identity so two methods that both spell
`T` do not share a type variable accidentally. Diagnostics display the source
name, while substitutions and equality use the qualified identity.

### Upper and intersection bounds

An omitted bound means `extends ironwood.lang.Object`.

A type variable whose bounds include `ironwood.lang.AutoCloseable` may invoke
`close()` as an ordinary interface call. Its checked thrown type is enforced at
the generic declaration and invocation like any other explicit call. There is
no compiler-recognized resource-list syntax.

```java
class Unbounded<T> { }
class Numeric<T extends Number> { }
class Sorted<T extends Base & Comparable<T> & Serializable> { }
```

An intersection bound follows Java's structural rules:

- the first bound may be a class, interface, or type variable;
- at most one class may appear, and if present it must be first;
- every remaining bound must be an interface;
- primitive and wildcard bounds are illegal;
- repeated erasures and conflicting parameterizations of the same generic
  supertype are illegal; and
- a direct cyclic bound is illegal, while a well-founded F-bound such as
  `T extends Comparable<T>` is legal.

A value of type `T` exposes the members guaranteed by the intersection of its
bounds. Member resolution must merge those members using the same override,
return compatibility, accessibility, and inherited-conflict rules used for an
ordinary class or interface receiver.

When a parameterized type is used, every actual argument must satisfy every
declared bound after the declaration's complete type-argument substitution has
been applied. Bound validation therefore handles recursive and mutually
referencing parameters rather than checking each bound in isolation.

### Erasure of a type variable

The erasure of an unbounded variable is `Object`. The erasure of a bounded
variable is the erasure of its first upper bound:

```text
T                         -> Object
T extends Base            -> Base
T extends Base & Marker   -> Base
T extends Marker          -> Marker
```

The other intersection members remain available to source type checking but do
not alter the native parameter representation. This first-bound erasure is used
for linkage names, erased signature-clash detection, dispatch-slot identity,
and native callable signatures.

## Wildcard and capture model

### Use-site variance

Ironwood supports Java-shaped unbounded, upper-bounded, and
lower-bounded wildcards:

```java
Box<?> any;
Box<? extends Number> producer;
Box<? super Integer> consumer;
```

Declaration-site variance is not added. `Box<String>` is not a subtype of
`Box<Object>`. Instead, wildcard containment permits conversions such as:

```text
Box<String>  -> Box<?>
Box<Integer> -> Box<? extends Number>
Box<Number>  -> Box<? super Integer>
Box<Object>  -> Box<? super Integer>
```

For a generic declaration `G<P>`, an exact argument remains invariant. A source
argument is accepted by `? extends U` when it is a subtype of `U`, and by
`? super L` when it is a supertype of `L`. Wildcard-to-wildcard containment is
computed from their upper and lower bounds rather than by treating every
wildcard as `Object`.

### Capture conversion

Each wildcard occurrence is capture-converted when members are selected from a
receiver. A capture is a fresh semantic type variable with:

- upper bound `Object` and no non-null writable lower bound for `?`;
- upper bound `U` and no non-null writable lower bound for `? extends U`; or
- upper bound `Object` and writable lower bound `L` for `? super L`.

This produces the familiar producer/consumer behavior:

```java
Box<? extends Number> source = ...;
Number number = source.get();
source.set(null);              // accepted
// source.set(number);         // rejected

Box<? super Integer> sink = ...;
sink.set(integer);             // accepted
Object value = sink.get();
```

The null literal is assignable through every wildcard-dependent write. A
non-null value may be written through a lower-bounded capture only when it is
assignable to that capture's lower bound. Reads use the capture's upper bound.

Captures are fresh for a receiver/member-lookup context. They are not global
singletons keyed only by the spelling `?`. This prevents values read from one
unknown instantiation from being written into an unrelated unknown
instantiation. The compiler may conservatively reject an operation when it
cannot prove two uses share the necessary capture.

### Generic subtyping and assignability

Subtype traversal retains exact substitutions on every superclass and
interface edge. To compare different raw declarations, the compiler first
projects the actual type onto the expected raw supertype and then compares its
arguments using invariant equality or wildcard containment.

Type variables and captures participate through their upper and lower bounds;
they are not treated as dynamically typed values. Ironwood arrays remain
statically invariant, including arrays whose element is a type variable. This
intentionally avoids Java's reference-array covariance and its runtime store
checks.

## Generic methods and constructors

### Declarations

Type parameters precede the method return type or constructor name:

```java
static <T> T identity(T value) { return value; }

<T extends Comparable<T>> T maximum(T left, T right) { ... }

class Box<E> {
    <T extends E> Box(T value) { ... }
}
```

Generic method variables are available in the return type, parameter types,
local declarations, casts, and body. Generic constructor
variables are available in the constructor parameters and body, but do not
parameterize the constructed class independently of its class arguments.

Calls may provide explicit type arguments:

```java
String text = Util.<String>identity(value);
String other = object.<String>convert(value);
```

The compiler checks explicit argument arity and bounds before ordinary
invocation applicability.

### Native sharing and overriding

A generic method or constructor has one native body. Invocation substitutes
source types for checking and typed IR views, while the call target remains the
single erased linkage symbol.

Override comparison alpha-renames member type variables by declaration order.
Thus `<T> T identity(T)` and `<U> U identity(U)` have the same generic shape
when their substituted bounds agree. Erased signatures still determine native
dispatch identity and name clashes. Two source declarations that erase to the
same parameter signature are rejected.

Ironwood does not need JVM bridge methods merely to change one reference type
to another: all reference parameters have the same native pointer
representation, and a dispatch slot can map its erased contract directly to
the closed-world implementation. Source override safety is nevertheless
checked before that mapping is emitted.

## Inference and diamond

### Invocation inference

When explicit method type arguments are absent, inference operates
separately for every overload candidate. It uses:

- actual argument types against formal parameter types;
- the receiver's exact class substitution;
- declared upper and intersection bounds;
- wildcard captures;
- the expected target type when the invocation is a poly expression.

Constraints are reduced recursively through arrays and exact generic
supertypes. The core relations are equality, lower-bound, and upper-bound
constraints on inference variables. A solution must satisfy all declaration
bounds after substitution.

Expected-type constraints are propagated from at least these contexts:

- local-variable and field initializers;
- simple assignment;
- method return expressions;
- casts and conditional-expression result typing;
- constructor and method arguments once a candidate formal is known; and
- nested generic invocations.

A callable type variable does not need to occur in a value parameter when the
expected result supplies enough evidence. The expected return type also flows
into diamond inside the generic method body:

```java
static <T> Box<T> createBox() {
    return new Box<>();
}

Box<String> strings = createBox();
Box<Object> objects = createBox();
```

Each invocation independently infers `T` from its target. This is ordinary
compile-time inference, not an unchecked cast or a reified runtime type test.

The absence of a unique safe solution is a diagnostic that asks for explicit
type arguments. The compiler does not guess `Object` merely to make an
otherwise ambiguous call compile.

Ironwood deliberately has no automatic boxing, lambdas, or method references.
The inference engine therefore never synthesizes hidden wrapper allocations or
functional-interface constraints. When an unbounded inference variable selects
a primitive, that exact value type becomes a closed-world specialization
request rather than an `Object`-bounded wrapper conversion.

### Diamond construction

Diamond is legal only in a class-instance creation expression:

```java
Box<String> box = new Box<>("value");
Pair<String, Item> pair = new Pair<>("key", item);
```

The parser preserves diamond as an inference request; it must not collapse
`new Box<>()` into a raw `new Box()`. Constructor argument constraints, class
bounds, and the expected target type jointly infer the class arguments. The
selected constructor may itself declare independent type parameters, which are
inferred after the class arguments are established.

If no class arguments can be inferred safely, the programmer must write them.
Diamond never enables a raw type or an unchecked conversion.

## Interaction with overload resolution

Overload resolution follows this order:

1. collect accessible candidates by name and receiver;
2. apply the receiver's class-type substitution;
3. validate explicit member type arguments or infer each generic candidate;
4. test fixed-arity applicability using normal Ironwood invocation conversions;
5. compare the instantiated formal parameter types to select the unique most
   specific candidate; and
6. diagnose no-match or ambiguity with the inferred candidate signatures.

An unqualified call may obtain static candidates through a single-member or
on-demand static import. Those declarations change only candidate collection:
a lexical member method shadows imported methods, and a single-static method
shadows an on-demand method with the same signature. Every remaining imported
generic or non-generic overload then follows the same candidate-local inference,
primitive-specialization, accessibility, applicability, and most-specific rules
listed above. No import-specific generic ABI or runtime representation exists.

Because Ironwood has no boxing conversion, Java's strict and loose invocation
phases currently have the same reference behavior. They remain conceptually
separate as Java-shaped applicability stages, but do not imply that boxing will
be added later.

Inference is part of candidate applicability, not a transformation performed
after an overload has already been selected. Even a name with one declaration
is checked for applicability; no single-candidate shortcut bypasses arity,
bound, or inference validation.

## Reifiability, casts, and `instanceof`

### Reifiable types

A type is reifiable when runtime nominal metadata is sufficient to test it.
For reference-shaped generics, these are:

- non-generic class and interface types;
- a parameterized type whose arguments are all unbounded wildcards, such as
  `HashMap<?, ?>`; and
- an array whose element type is recursively reifiable, such as `int[][]` or
  `HashMap<?, ?>[]`.

Raw types are not included because Ironwood does not support them. A bounded
wildcard parameterization such as `Box<? extends Number>`, a concrete
parameterization such as `Box<String>`, and a type variable are not reifiable.
Every reachable array shape receives an exact erased descriptor, so reifiable
array tests and downcasts are implemented without reifying generic arguments.
Thus `Box<?>[][]` is targetable while `Box<String>[]`, bounded-wildcard arrays,
and type-variable arrays are not runtime test targets.

### `instanceof`

`instanceof` accepts a reifiable nominal or array target. Nominal tests use raw
closed-world membership; array tests compare the complete exact erased array
descriptor. Therefore `value instanceof Box<?>` and
`value instanceof Box<?>[][]` are valid, while all of the following remain
errors:

```java
value instanceof Box<String>
value instanceof Box<? extends Number>
value instanceof T
```

No runtime type-argument registry is added to make these tests possible. A
primitive-specialized instance deliberately lacks `G<?>` membership, so this
otherwise legal test is false rather than creating a pointer-shaped wildcard
view over native value storage.

Feature 66 applies the same target rules to named patterns. For example,
`value instanceof Box<?> box` and `value instanceof Box<?>[][] boxes` bind
precisely typed aliases on successful paths, while patterns targeting
`Box<String>`, `Box<? extends Number>`, or `T` remain errors. The binding does
not reify or copy arguments, allocate a wrapper, or create membership for a
primitive specialization. Its capture eligibility and allocation identity are
the same as those of an ordinary local alias.

### Casts

Widening generic conversions require no runtime test. A narrowing cast to a
reifiable target performs the existing raw nominal membership check and throws
`ClassCastException` on failure.

A checked cast from `Object` to a reifiable nominal target cannot succeed on a
primitive specialization through `G<?>`. A checked cast to a reifiable array
target compares exact erased
descriptor identity at every dimension. `Object` to `Box<?>[][]` is therefore
checkable, while `Object` to `Box<String>[][]` is not. Arrays remain invariant:
distinct array descriptors do not become convertible merely because their leaf
reference types are related.

Ironwood also permits a non-reifiable narrowing cast when its generic
arguments are already proven by the source types and the only runtime question
is raw reference-shaped or exact primitive-specialized membership. For example,
a cast from `Source<String>` to
`Box<String>` can be checked if every possible overlapping `Box` implements
the exact `Source<String>` contract. Closed-world hierarchy analysis performs
that proof.

The same proof may select a primitive-specialized descriptor. For example, a
non-generic marker implemented only by a concrete subtype of `Box<int>` can be
checked back to `Box<int>` using that exact specialized identity; it does not
grant `Box<?>` membership.

The following remains an unchecked conversion and is rejected:

```java
Object value = ...;
Box<String> box = (Box<String>) value;
```

Runtime membership can establish `Box`, but it cannot establish `String`.
Ironwood does not accept the cast with a warning, and it does not provide an
annotation to suppress that warning. Casts involving type variables are
accepted only when ordinary static assignability already proves them; a cast
whose correctness would depend on an erased argument is rejected.

## Deliberate exclusion: varargs

Ironwood does not support `T...` parameters or expanded variable-arity calls.
Those calls require a compiler-created array with no ordinary source-level
owner. In a language without garbage collection, the hidden allocation can
remain unreclaimed after every invocation and produce unbounded growth in hot
code. Pooling, implicit cleanup, and escape-dependent stack promotion would add
ownership rules that Ironwood deliberately avoids at this stage.

The parser consumes a parameter ellipsis only to produce a clear unsupported-
feature diagnostic. No varargs flag enters the AST, callable model, inference,
overload resolution, IR, or native ABI. Programs and libraries use an explicit
array parameter or selected fixed-arity overloads, keeping every array
allocation visible to source-level lifetime management.

## Nested and inner generic types

Nested and inner generic types use the following implemented rules.

A static nested type has only its own type parameters and cannot refer to an
enclosing class's parameters:

```java
class Outer<T> {
    static class Nested<U> { }
}
```

A non-static inner type has its own parameters plus the exact parameterization
of its enclosing instance:

```java
class Outer<T> {
    class Inner<U> {
        T outerValue;
        U innerValue;
    }
}

Outer<String>.Inner<Item> value;
```

The enclosing instance is a compiler-managed hidden receiver. Its exact outer
arguments participate in member substitution, bounds, inheritance, inference,
and access checks. They remain compile-time types and do not add runtime generic
metadata. Two inner values with different outer arguments share the same raw
nested declaration and native layout while remaining distinct static types.

Local and anonymous classes capture any permitted enclosing type variables in
the same semantic environment used for captured values. An anonymous class
retains the exact parameterized superclass or interface it implements.
Diamond with anonymous construction is accepted only when constructor
inference and every generated override signature are denotable without an
unchecked conversion. A non-private instance method declared by such an
anonymous class must override an inherited method; private helpers are allowed.
Otherwise explicit class arguments are required.
Constructor and target-type inference establish the anonymous body's exact
inherited type view before override and body checking. For example, a diamond
inferred as `Box<String>` checks an override of `T get()` against `String`,
including the type of `super.get()`. This does not relax override compatibility
or reclamation rules.

Stable nested declaration identities, not display names alone, must qualify
type variables and native linkage. This prevents distinct `Outer<T>.Inner<U>`
declarations or member-local declarations from colliding after erasure.

## Compiler architecture requirements

The implementation centralizes generic rules rather than duplicating them
across declaration collection and expression lowering.

The semantic model includes:

- declared type-variable symbols with stable identity, upper/intersection
  bounds, lower bounds for captures, and first-bound erasure;
- wildcard types that retain variance and their source bound;
- fresh capture variables for member lookup;
- reusable exact-supertype projection and wildcard-containment operations;
- a generic inference solver that instantiates one overload candidate at a
  time; and
- expected-type propagation for poly expressions and diamond.

Class substitution and member substitution must compose without replacing a
method's independent variables. Callable views retain the declaration's native
linkage and erased dispatch key while exposing instantiated source parameter
and return types to call checking.

Format-1 `.ironclass` files embed source, so this model does not by itself
require an artifact-format bump. Dependency discovery must, however, scan class,
interface, method, and constructor bounds; explicit type arguments; wildcard
bounds; and nested generic qualifiers. It must treat every in-scope type
parameter as a variable rather than a nominal source dependency. Classpath and
`.ironjar` round trips must reconstruct and revalidate the same bounds and
inference results.

## Generic `throws` declarations

A method or constructor type variable may appear in `throws` when its upper
bounds prove that it derives from `Throwable`:

```java
static <E extends Exception> void raise(E failure) throws E {
    throw failure;
}
```

Invocation inference substitutes the selected callable's thrown types together
with its parameter and result types. The caller therefore sees the inferred
exception type, not merely the variable's erasure or upper bound. An unbounded
variable, a variable whose bounds do not derive from `Throwable`, and a
parameterized exception class are rejected. Generic exception classes remain
excluded, and catch targets remain reifiable nominal classes rather than type
variables. Multi-catch accepts several such nominal alternatives but does not
make a union type available elsewhere. Precise rethrow records the exact checked
types reaching a final or effectively-final catch after generic `throws`
substitution, so a broad catch around a generic call may rethrow the inferred
concrete exception contract without declaring the broad catch type.

Alpha-equivalent generic overrides adapt their throws variables to the inherited
declaration before compatibility is checked. Each checked exception in the
override must be a subtype of one permitted by the inherited method; unchecked
`RuntimeException` and `Error` branches do not constrain overriding.

## Native primitive specialization

An unbounded declared parameter, whose source declaration omits `extends`, may
receive `boolean`, `byte`, `short`, `char`, `int`, `long`,
`float`, or `double`. An explicit bound, including `extends Object` or a bound
depending on another variable, is reference-only. Primitive arguments are
exact and invariant; wildcards remain pointer-shaped reference views.

The semantic pipeline first performs ordinary exact substitution, inference,
overload resolution, ownership checking, and typed-IR construction. A dedicated
typed-IR pass then discovers used primitive shapes and materializes:

- specialized class/interface layouts and descriptors for every distinct
  primitive-position/kind shape, while all reference positions share `ptr`;
- specialized constructors and methods with native primitive parameters,
  returns, fields, phis, equality, and arrays;
- specialized generic-callable dispatch slots where a virtual or interface
  method's own type parameters select primitive ABIs; and
- exact array descriptors whose leaf is the specialized nominal shape.

The unspecialized declaration retains its one static state and initializer.
Format-1 class and archive inputs reconstruct source into the final closed
world, so specialization is deterministic at link time and does not claim a
stable separately compiled ABI. Tree shaking removes unused shapes and bodies.

Generic bodies are checked again after substitution. Value transport, equality,
storage, return, and arrays specialize without allocation. `null`, reference
conversion, `Object` member use, throwing a primitive value, or dispatching on
it as a receiver is diagnosed at the source operation. No wrapper, runtime tag,
parameterized registry, or hidden allocation is introduced. Safe `free` tracks
only the containing reference allocation; primitive contents create no aliases.
Destructor and constructor-rollback functions specialize with the same exact
owner shape as constructors and methods. A generic owned reference field keeps
its substituted nominal descriptor; a primitive field has no child rollback or
destructor obligation.

## Deliberate exclusions

The following are not part of the implemented reference-generics model:

- **Raw types.** A generic declaration always requires explicit or inferred
  arguments. Raw types exist for Java's pre-generics compatibility and would
  require unchecked conversions and weakened member types that Ironwood does
  not need.
- **Unchecked conversions and casts.** Ironwood rejects operations whose
  generic correctness cannot be proved statically and cannot be checked by
  available runtime metadata. It does not knowingly permit heap pollution merely
  with a warning.
- **Unchecked-warning annotations.** General annotations are not planned, and
  Ironwood does not add `SuppressWarnings` or similar loopholes around the
  static model.
- **Varargs.** Variable-arity parameters are excluded because expanded calls
  would create hidden arrays without reliable source-level ownership.
- **Runtime generic reflection.** Parameterized instantiations do not acquire
  runtime `Class` objects, registries, or dynamically loadable identities.
- **Declaration-site variance.** Variance remains Java-shaped and use-site.
- **Wildcard construction or inheritance.** `new Box<?>`, `extends Box<?>`, and
  implementing a wildcard-parameterized interface remain invalid.
- **`new T()`.** A type variable carries bounds, not a runtime constructor
  token. Construction uses an explicit factory or another ordinary object
  design.
- **Generic exception types.** A generic class cannot directly or indirectly
  extend `Throwable`, and a catch target cannot be a type variable or
  non-reifiable parameterization. Exception classes may still declare generic
  methods/constructors, and a non-generic static nested exception class may
  appear in a generic owner.
- **Automatic boxing/unboxing.** A primitive argument never becomes an
  `Object`-bounded inference variable through an implicit wrapper allocation.
  Hidden wrappers would have unclear source-level ownership and reclamation.

These exclusions are intentional source-language choices, not temporary JVM
limitations.

## Acceptance criteria

The model's completion coverage includes:

- single, multiple, recursive, and intersection bounds, including invalid
  cycles, ordering, repeated erasure, and conflicting generic supertypes;
- `?`, `? extends`, and `? super` reads, writes, containment, and independent
  captures;
- generic instance/static methods and constructors, explicit member arguments,
  shadowed type-parameter names, and alpha-equivalent overrides;
- throwable-bounded generic `throws`, invocation substitution, and override
  compatibility;
- inference from arguments, exact supertypes, nested invocations, return
  targets, assignments, conditionals, and diamond constructors;
- generic/non-generic fixed-arity overload selection, including candidates
  introduced by single-member and on-demand static imports, ambiguity, and
  erased signature clashes;
- all eight primitive kinds, explicit and inferred arguments, diamond and
  generic constructors, mixed primitive/reference shapes, primitive arrays,
  direct/virtual/interface dispatch, and invalid reference-only bodies;
- safe and rejected generic casts plus every reifiable and non-reifiable
  `instanceof` category;
- nested, static nested, inner, local, and anonymous generic-type interactions;
- typed-IR preservation, reference first-bound ABI erasure, primitive native
  specialization, dispatch, and native execution at every optimization level;
- allocation-identity preservation through captures, inferred calls, casts,
  fresh/alias return summaries, specialized destructors and rollback, and
  conservative safe-`free` rejection;
  and
- source-path, class-directory, individual `.ironclass`, `.ironjar`, tree-
  shaking, relocated-package, and release-package round trips.

These areas are covered through positive behavior, rejection behavior, native
lowering, and artifact round trips. New generic features must meet the same
standard before documentation marks them supported.
