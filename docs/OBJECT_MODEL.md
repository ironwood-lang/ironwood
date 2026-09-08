# Ironwood source-level object model

## Status and purpose

This document defines Ironwood's completed Java-shaped source-level object
model. The features described through [Closed-world lowering
requirements](#closed-world-lowering-requirements) are implemented, validated
semantics rather than a future design sketch.

The current implemented subset remains authoritative in
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md) and the detailed implemented grammar
remains in [`LANGUAGE.md`](LANGUAGE.md). Compiler architecture is described in
[`COMPILER.md`](COMPILER.md), accepted historical choices are recorded in
[`DECISIONS.md`](DECISIONS.md), milestone history is in
[`ROADMAP.md`](ROADMAP.md), and alias and reclamation rules are in
[`MEMORY.md`](MEMORY.md). A less technical table with paired Java and Ironwood
examples is in [`IRONWOOD_VS_JAVA.md`](IRONWOOD_VS_JAVA.md).

The governing rule is:

> Prefer the result a Java programmer expects unless it conflicts with
> closed-world native compilation, Ironwood's explicit safe-`free` model, or an
> explicit project non-goal.

The result is source familiarity and object-oriented completeness, not JVM
compatibility. These rules introduce no bytecode, runtime class loading,
reflection, garbage collector, or JIT.

## Completion boundary

The source-level object model is complete for this phase because all of the
following work together:

1. concrete and abstract classes, final classes and methods, Java-shaped
   overriding, overloading, inheritance, and interface polymorphism;
2. instance field initialization, blank-final definite assignment, and
   Java-shaped field hiding and selection;
3. complete object-oriented `this` and `super` forms, including direct
   superclass member selection and qualified enclosing-instance access;
4. static nested types, member inner classes, nested interfaces, local classes,
   and anonymous classes;
5. enclosing-instance and effectively-final local capture with nest-based
   private access;
6. interface constants and abstract, default, static, and private interface
   methods with deterministic Java-shaped default resolution;
7. the reference-generic model described below, including bounds, generic
   methods and constructors, wildcards, capture conversion, inference, diamond,
   and nested owner types; and
8. preservation of these semantics through source discovery, `.ironclass`,
   `.ironjar`, typed IR, native execution, closed-world dispatch,
   devirtualization, and safe-`free` analysis.

Completion was established with positive behavior tests and focused rejection
diagnostics, native execution at `-O0` through `-O3`, and source-path,
class-path, and archive round trips. Parser acceptance alone is not completion.

This boundary does not include the features listed under
[Roadmap and deliberate exclusions](#roadmap-and-deliberate-exclusions).

## Implemented support summary

| Area | Implemented semantics |
| --- | --- |
| Classes | Concrete and abstract classes, single inheritance, and final classes |
| Methods | Overloading, structural overriding, abstract/final methods, and virtual/interface dispatch |
| Fields | Instance initializers, initializer blocks, blank-final analysis, and Java-shaped hiding |
| `this` | Current object, `this(...)`, and qualified enclosing `Outer.this` |
| `super` | Constructor chaining, direct superclass fields/methods, and qualified interface defaults |
| Types | Top-level, static nested, member inner, nested-interface, local, and anonymous types |
| Interfaces | Constants plus abstract, default, static, and private methods |
| Generics | Bounded invariant reference parameters, wildcards/capture, inference, diamond, and owners |
| Annotations | Explicitly excluded |

## Hands-on example catalog

Every object-model feature completed by D046 has a focused project under
`examples`. Each project is deliberately small, uses the normal
`src/main/ironwood` layout, and has its own `compile.sh`, `link.sh`, and
`run.sh`. The compile step emits package-structured `.ironclass` files, the
link step consumes only those files through `-cp` and produces an optimized
native executable, and the run step verifies exit status `42`.

To inspect and test one feature, substitute its directory from the table:

```console
$ cd examples/abstractclasses
$ ./compile.sh
$ ./link.sh
$ ./run.sh
exit status: 42
```

To compile, link, and run the entire catalog:

```console
$ ./examples/test-object-model.sh
PASS: 30 object-model examples
```

| Feature implemented | Example source | What its native result verifies |
| --- | --- | --- |
| Abstract classes and methods | [`AbstractClasses.iron`](../examples/abstractclasses/src/main/ironwood/org/ironwood/abstractclasses/AbstractClasses.iron) | A concrete subclass satisfies an abstract obligation, inherits concrete behavior, and dispatches through an abstract base reference. |
| Final classes and final methods | [`Finality.iron`](../examples/finality/src/main/ironwood/org/ironwood/finality/Finality.iron) | Valid final declarations behave normally; commented counterexamples show the rejected extension and override forms. |
| Instance initialization order | [`Initialization.iron`](../examples/initialization/src/main/ironwood/org/ironwood/initialization/Initialization.iron) | Superclass fields/blocks/constructor precede subclass fields/blocks/body, and `this(...)` delegation runs initialization once. |
| Blank-final fields and final bindings | [`FinalBindings.iron`](../examples/finalbindings/src/main/ironwood/org/ironwood/finalbindings/FinalBindings.iron) | Constructor assignment of a blank final plus final parameters, locals, catch parameters, and capture. |
| Field hiding | [`FieldHiding.iron`](../examples/fieldhiding/src/main/ironwood/org/ironwood/fieldhiding/FieldHiding.iron) | Hidden fields keep separate storage and selection follows the receiver's compile-time type; `this` and `super` select exact owners. |
| Direct superclass access | [`SuperAccess.iron`](../examples/superaccess/src/main/ironwood/org/ironwood/superaccess/SuperAccess.iron) | `super(...)`, `super.field`, and `super.method(...)` all select the direct superclass without virtual dispatch. |
| Qualified inner-superclass construction | [`QualifiedSuper.iron`](../examples/qualifiedsuper/src/main/ironwood/org/ironwood/qualifiedsuper/QualifiedSuper.iron) | A subclass of an inner class uses `outer.super(...)` to select and initialize its superclass enclosing instance. |
| Static nested classes | [`StaticNestedTypes.iron`](../examples/staticnested/src/main/ironwood/org/ironwood/staticnested/StaticNestedTypes.iron) | A static nested class is constructed without an enclosing instance and can use permitted enclosing static state. |
| Member inner classes and `Outer.this` | [`InnerClasses.iron`](../examples/innerclasses/src/main/ironwood/org/ironwood/innerclasses/InnerClasses.iron) | Explicit `outer.new Inner(...)`, implicit inner construction, and qualified outer access retain the correct enclosing object. |
| Nested interfaces | [`NestedInterfaces.iron`](../examples/nestedinterfaces/src/main/ironwood/org/ironwood/nestedinterfaces/NestedInterfaces.iron) | Interfaces nested in classes and interfaces have qualified nominal identities and participate in ordinary implementation dispatch. |
| Local classes | [`LocalClasses.iron`](../examples/localclasses/src/main/ironwood/org/ironwood/localclasses/LocalClasses.iron) | A block-scoped local subclass has a constructor, superclass call, methods, and a deterministic link-time identity. |
| Anonymous classes | [`AnonymousClasses.iron`](../examples/anonymousclasses/src/main/ironwood/org/ironwood/anonymousclasses/AnonymousClasses.iron) | Anonymous class extension and interface implementation support overriding, defaults, enclosing state, and captured locals. |
| Qualified generic anonymous construction | [`QualifiedAnonymous.iron`](../examples/qualifiedanonymous/src/main/ironwood/org/ironwood/qualifiedanonymous/QualifiedAnonymous.iron) | An arbitrary primary selects the exact generic member owner and supplies a distinct enclosing operand to its anonymous subclass. |
| Distinct anonymous enclosing operands and evaluation order | [`AnonymousEnclosing.iron`](../examples/anonymousenclosing/src/main/ironwood/org/ironwood/anonymousenclosing/AnonymousEnclosing.iron) | The qualified primary runs once before arguments; the inner superclass retains its explicit outer while the anonymous body retains a different lexical outer. |
| Anonymous-class diamond | [`AnonymousDiamond.iron`](../examples/anonymousdiamond/src/main/ironwood/org/ironwood/anonymousdiamond/AnonymousDiamond.iron) | Constructor arguments and the expected type infer an anonymous superclass parameterization without exposing synthetic type variables. |
| Effectively-final lexical capture | [`LexicalCapture.iron`](../examples/lexicalcapture/src/main/ironwood/org/ironwood/lexicalcapture/LexicalCapture.iron) | Local and anonymous classes capture explicit-final parameters, effectively-final locals, and their enclosing instance. |
| Source-nest private access | [`NestAccess.iron`](../examples/nestaccess/src/main/ironwood/org/ironwood/nestaccess/NestAccess.iron) | Member, local, and anonymous classes directly access private members within one top-level source nest. |
| Member types inside lexical classes | [`LexicalMemberTypes.iron`](../examples/lexicalmembertypes/src/main/ironwood/org/ironwood/lexicalmembertypes/LexicalMemberTypes.iron) | Local and anonymous classes may themselves contain inner classes, interfaces, and implementations. |
| Captured-reference safe-`free` aliases | [`CapturedAliases.iron`](../examples/capturedaliases/src/main/ironwood/org/ironwood/capturedaliases/CapturedAliases.iron) | An unretained allocation can be freed, while the commented invalid form shows that a hidden captured alias prevents freeing its referent. |
| Interface fields and method kinds | [`InterfaceMembers.iron`](../examples/interfacemembers/src/main/ironwood/org/ironwood/interfacemembers/InterfaceMembers.iron) | Constants, abstract/default/static/private-instance/private-static methods, generic overrides, and qualified default calls work together. |
| Interface default resolution | [`InterfaceDefaults.iron`](../examples/interfacedefaults/src/main/ironwood/org/ironwood/interfacedefaults/InterfaceDefaults.iron) | Class-wins, most-specific default, explicit interface-super, re-abstraction, and concrete conflict resolution. |
| Upper, intersection, dependent, and recursive bounds | [`BoundedGenerics.iron`](../examples/boundedgenerics/src/main/ironwood/org/ironwood/boundedgenerics/BoundedGenerics.iron) | Bound members are callable, intersection constraints are enforced, and an F-bound dispatches with exact substitution. |
| Generic methods and constructors | [`GenericCallables.iron`](../examples/genericcallables/src/main/ironwood/org/ironwood/genericcallables/GenericCallables.iron) | Explicit and inferred method/constructor variables survive interface overriding, dispatch, nested inference, and expected types. |
| Wildcards and argument capture | [`WildcardCapture.iron`](../examples/wildcardcapture/src/main/ironwood/org/ironwood/wildcardcapture/WildcardCapture.iron) | `?`, `? extends`, and `? super` provide safe read/write views, including fresh capture conversion at generic calls. |
| Generic inference and overload specificity | [`GenericInference.iron`](../examples/genericinference/src/main/ironwood/org/ironwood/genericinference/GenericInference.iron) | Nested, expected-type, and return-only generic-factory inference resolve type variables per candidate before the most-specific overload is selected. |
| Diamond inference | [`DiamondInference.iron`](../examples/diamond/src/main/ironwood/org/ironwood/diamond/DiamondInference.iron) | Class, dependent, intersection, and generic-constructor constraints infer exact construction types. |
| Nested generic owner/member types | [`NestedGenerics.iron`](../examples/nestedgenerics/src/main/ironwood/org/ironwood/nestedgenerics/NestedGenerics.iron) | Inner types retain exact owner and member arguments while static nested types start a fresh generic boundary. |
| Diamond on inner and anonymous-inner construction | [`InnerDiamond.iron`](../examples/innerdiamond/src/main/ironwood/org/ironwood/innerdiamond/InnerDiamond.iron) | Explicit/implicit enclosing instances, explicit constructor variables, diamond, and anonymous inner subclasses compose. |
| Source-provable generic casts | [`GenericCasts.iron`](../examples/genericcasts/src/main/ironwood/org/ironwood/genericcasts/GenericCasts.iron) | A closed-world-proven parameterized cast succeeds, while a nominally overlapping runtime object raises catchable `ClassCastException`. |
| `Throwable` type enforcement | [`ThrowableTypes.iron`](../examples/throwabletypes/src/main/ironwood/org/ironwood/throwabletypes/ThrowableTypes.iron) | A bounded throwable variable can be thrown, a final reifiable catch works, and a generic owner may contain a non-generic static exception with a generic method. |

The examples are positive programs so that all three scripts succeed. Where a
feature is principally a rejection rule, its source contains a commented
invalid form. The authoritative negative checks, including abstract/final
misuse, final reassignment, missing enclosing instances, non-final capture,
default conflicts, invalid bounds, ambiguous inference, unsafe casts, generic
exception classes, and unsafe captured-reference `free`, run with:

```console
$ ./scripts/test.sh
```

The compile/link separation is intentional evidence for artifact preservation.
In particular, local and anonymous types are reconstructed while linking from
their owning top-level `.ironclass`; the linker does not receive `.iron` source
inputs and performs no runtime class loading.

## Classes, abstraction, and finality

Runnable examples: [`abstractclasses`](../examples/abstractclasses),
[`finality`](../examples/finality), and
[`initialization`](../examples/initialization).

### Abstract classes and methods

- A class declared `abstract` cannot be instantiated.
- An abstract class may have fields, constructors, concrete methods, abstract
  methods, nested types, and static members.
- An abstract instance method has no body and supplies an implementation
  obligation to concrete subclasses.
- A concrete class must provide a valid implementation for every inherited
  abstract class method and every unresolved interface method.
- An abstract subclass may leave an inherited obligation unresolved or
  deliberately re-abstract an inherited concrete/default method.
- Constructors are never abstract and are used normally by concrete
  subclasses.
- An abstract method cannot be `private`, `static`, or `final`.
- A method with a body is not abstract. Native/compiler intrinsics remain an
  internal implementation mechanism rather than source-level abstract bodies.

### Final classes and methods

- A final class cannot be extended.
- A final instance method cannot be overridden.
- Static and private methods are not overridden; they are resolved without
  virtual dispatch.
- `abstract final` is invalid for a class or method.
- Override checking continues to enforce matching parameter signatures,
  compatible covariant reference returns, instance/static form, and no access
  narrowing.
- Every source-declared inherited instance override or interface implementation
  requires the exact built-in `@Override` directive, and a marked method with no
  valid inherited instance target is rejected. Structural validity remains
  independently checked by the compiler.
- `@Override` is method-only, takes no arguments, and may be on the same line,
  a preceding line, or interleaved with ordinary modifiers. It is not an
  annotation instance and does not add general annotation support.

Runnable example:
[`OverrideDirective.iron`](../examples/overridedirective/src/main/ironwood/org/ironwood/overridedirective/OverrideDirective.iron).

### Construction

Object allocation remains zero-initialized before constructor execution. A
constructor invokes exactly one constructor in the same class or direct
superclass as its first construction action:

- `this(...)` delegates to another constructor in the same class;
- `super(...)` invokes a direct-superclass constructor; and
- an omitted invocation means implicit `super()`.

Recursive `this(...)` delegation is invalid. Instance initialization occurs
exactly once in the constructor chain that ultimately invokes `super(...)`.
Constructor dispatch is never virtual.

### Destruction and construction rollback

A class may declare one `destructor { ... }` body. It is not a Java override or
ordinary method: it has no name, modifiers, parameters, result, overloads, or
explicit return. Interfaces and enum objects do not declare destructors.

`free` selects the dynamic class's destructor entry. The declared body completes
before the direct superclass destructor, producing deterministic derived-to-root
order. A body may directly free a private reference field only when the
closed-world ownership summary proves that storage remains confined to the
dying owner. It may not allocate, publish/resurrect `this`, or allow an
exception to escape.

Failed construction uses a distinct compiler-generated rollback chain. It
releases proven owned fields of the incomplete base-first object in reverse
order, normally destroys completed owned child objects, and raw-deallocates the
receiver without running that incomplete receiver's source destructor.
Publishing in-progress `this` is rejected, and a `this(...)` chain shares one
rollback obligation.

An encapsulated reusable helper may remain owned by its containing object while
a method returns a dependent borrow. This is how `ironwood.ds` collections lend
their cached iterator and primitive holder without transferring ownership. The
caller cannot free the helper; owner destruction invalidates it, and any escaped,
uncertain, or later-observed borrow prevents successful compilation. See
[Owned Helper Borrows](OWNED_HELPER_BORROWS.md).

Runnable example:
[`ExplicitReclamation.iron`](../examples/reclamation/src/main/ironwood/org/ironwood/reclamation/ExplicitReclamation.iron).

## Fields and initialization

Runnable examples: [`initialization`](../examples/initialization),
[`finalbindings`](../examples/finalbindings), and
[`fieldhiding`](../examples/fieldhiding).

### Instance initialization order

For one class in a constructor chain, Ironwood follows Java's observable order:

1. storage for the complete object has already been zero-initialized;
2. the selected direct-superclass constructor completes;
3. this class's instance field initializers and instance initializer blocks run
   once, in textual order; and
4. the remaining constructor body runs.

A `this(...)` delegating constructor does not run initializers a second time.
Initializer expressions use ordinary instance context and may refer to earlier
initialized members subject to normal forward-reference and definite-assignment
rules.

D055 subsequently adds deterministic runtime static initialization. Named
classes accept source-ordered static blocks, runtime-valued field initializers
execute in textual order, and active uses initialize each declaring type once
after its superclass/default-method-interface prerequisites. Compile-time
primitive constants remain exempt, named nested types initialize independently,
and the implementation uses private closed-world state rather than JVM loading.

### Final instance fields

- A final field may be initialized at its declaration or assigned as a blank
  final by a constructor.
- Every normal completion path of every non-delegating constructor must assign
  each blank final exactly once.
- A delegating `this(...)` constructor relies on its target constructor's
  definite assignment and cannot assign the same final again.
- Reading a blank final before definite assignment is invalid.
- Assigning a final field outside its permitted initialization context is
  invalid.
- Definite-assignment checks include branches, loops, abrupt completion,
  `try`/`catch`/`finally`, and constructor delegation.

Finality does not make an object transitively immutable. A final reference
cannot be redirected after initialization, but the referenced object may remain
mutable.

### Field hiding

Fields are selected statically and are never overridden:

- a subclass may declare a field with the same name as an accessible inherited
  field;
- each instance declaration has its own distinct storage slot;
- an unqualified field name or `this.field` selects according to the current
  class's lexical and inherited lookup;
- `super.field` selects the accessible declaration found from the direct
  superclass search;
- `expression.field` uses the compile-time type of `expression`, not its runtime
  class; and
- `Type.field` selects a static field declared or inherited for that type.

The compiler must diagnose ambiguous inherited interface constants and illegal
access, but must not reject ordinary class field hiding merely because two
different declarations share a name. Field layout and IR identity must include
the declaring class so hidden fields never alias accidentally.

## `this` and `super`

Runnable examples: [`superaccess`](../examples/superaccess),
[`qualifiedsuper`](../examples/qualifiedsuper), and
[`innerclasses`](../examples/innerclasses).

### `this`

- Unqualified `this` denotes the current object of the innermost instance
  class.
- `this(...)` is constructor delegation and is permitted only as the first
  construction action.
- `Outer.this` denotes the lexically enclosing instance of `Outer` when such an
  instance exists.
- `Outer.this` is invalid from a static context, a static nested type, or a
  context whose enclosing-instance chain contains no `Outer`.
- A nested object has its own `this`; unqualified `this` never silently denotes
  an outer object.

### `super`

- `super(...)` invokes a direct-superclass constructor.
- `super.field` performs direct superclass field selection.
- `super.method(...)` invokes the selected accessible superclass implementation
  directly and bypasses virtual dispatch.
- `InterfaceName.super.method(...)` directly invokes an inherited default from
  a permitted direct superinterface and is subject to the interface rules
  below.
- A qualified enclosing instance may be supplied where Java requires one to
  construct or subclass a non-static inner class.

`super` is not an object value: it cannot be stored, returned, compared, or
passed as an argument by itself.

## Nested and anonymous types

Runnable examples: [`staticnested`](../examples/staticnested),
[`nestedinterfaces`](../examples/nestedinterfaces),
[`innerclasses`](../examples/innerclasses),
[`localclasses`](../examples/localclasses),
[`anonymousclasses`](../examples/anonymousclasses),
[`qualifiedanonymous`](../examples/qualifiedanonymous),
[`anonymousenclosing`](../examples/anonymousenclosing),
[`anonymousdiamond`](../examples/anonymousdiamond), and
[`lexicalmembertypes`](../examples/lexicalmembertypes).

All nested, local, and anonymous types are ordinary closed-world nominal types
after semantic lowering. They receive deterministic compiler identities and
participate in layouts, type membership, dispatch, exception matching,
devirtualization, and tree shaking like top-level types. No runtime class
generation is involved.

### Static nested types and nested interfaces

- A static nested class has no enclosing-instance reference.
- It may access enclosing static members under normal access rules, but not
  enclosing instance members without an explicit object.
- It does not inherit access to an enclosing class's type parameters.
- A member interface is implicitly static.
- A type declared in an interface is implicitly `public static`.
- Member types obey Java-shaped public, protected, package-private, and private
  access. Public filename rules apply only to top-level types.

### Member inner classes

A non-static member class is an inner class:

- every instance is associated with one enclosing instance;
- construction supplies that instance explicitly or from the current lexical
  enclosing context;
- the enclosing reference behaves as an immutable hidden field;
- inner methods may access enclosing instance members through the enclosing
  chain; and
- qualified construction and qualified `Outer.this` make the selected
  enclosing instance explicit where needed.

The hidden reference is compiler-owned and does not appear in source overload
signatures. It is nevertheless a real reference for escape and safe-`free`
analysis.

### Local classes

- A local class is declared in a block and is in scope from its declaration to
  the end of that lexical scope.
- It has no source-level access modifier and cannot be static.
- It may extend one class, implement interfaces, declare members and
  constructors, and use the same abstract/final rules as a member class.
- A local class declared in an instance context may capture the required
  enclosing instance. One declared in a static context has no implicit
  enclosing instance.
- Its deterministic internal name is not a source-level API.

### Anonymous classes

An anonymous class expression creates one unnamed class and one instance:

- a class target is extended and its selected constructor receives the
  expression's arguments;
- an interface target is implemented;
- the body may declare fields, initializer blocks, and methods, but no
  constructor or named nested self-type;
- ordinary override, abstract-obligation, access, and default-method rules
  apply; and
- enclosing-instance and local capture use the same mechanism as local classes.

When anonymous construction uses diamond, a declared non-private instance
method must override an inherited method; private helpers remain permitted.
This keeps the inferred anonymous supertype denotable without exposing
compiler-created type-variable names.

For `primary.new Inner(...) { ... }`, `primary` is evaluated exactly once and
null-checked before constructor arguments. Its static type selects the exact
member declaration and owner parameterization; the compiler never guesses from
a global simple name. A lexical enclosing receiver retained by the anonymous
body and the explicit receiver required by its inner superclass remain distinct
hidden operands when they denote different objects.

Anonymous classes do not imply lambdas, runtime proxies, or generated runtime
code.

## Enclosing instances, captures, and nest access

Runnable examples: [`lexicalcapture`](../examples/lexicalcapture),
[`nestaccess`](../examples/nestaccess), and
[`capturedaliases`](../examples/capturedaliases).

### Effectively-final capture

A local class or anonymous class may capture a local variable or parameter only
when it is explicitly `final` or effectively final. Explicit `final` is
implemented for both locals and parameters and prohibits every subsequent
plain, compound, prefix, or postfix write.

- A local initialized once and never assigned again is effectively final.
- A parameter is effectively final when it is never assigned in the body.
- Plain assignment, compound assignment, and prefix/postfix update after the
  initial assignment make a variable non-effectively-final.
- Capture copies the value at object construction. Capturing a reference copies
  the reference, not the referenced object.
- Captured values are exposed in the nested body as read-only lexical bindings.
- Capture analysis is lexical and control-flow aware; it is not inferred from
  whether a later assignment happens to execute at runtime.

Captured values lower to immutable compiler-owned fields and hidden constructor
arguments. Hidden parameters must not affect source overload resolution,
diagnostics, or public signatures.

### Safe-`free` interaction

Enclosing and captured references are aliases under Ironwood's normal memory
model. Constructing an object that retains such a reference must create an
explicit escape edge in compiler analysis. Therefore:

- an allocation retained by a live inner, local, or anonymous object cannot be
  accepted by a later `free` merely because the original local is dead;
- freeing the inner object runs only its declared destructor chain; it does not
  infer ownership of its enclosing instance or captured objects; and
- conservative rejection remains correct when the compiler cannot prove that
  every retained alias is dead.

Compiler-owned hidden fields do not create a second ownership system.

### Nest-based private access

All types lexically enclosed by the same top-level type form one source nest.
Members of that nest may access one another's private members, subject to normal
instance/static context and type checking. This is resolved directly by the
compiler; it does not require synthetic public accessors or runtime nest
metadata.

Local and anonymous classes declared inside a nest use the same private-access
rule. Package and protected access continue to follow the rules in
[`LANGUAGE.md`](LANGUAGE.md).

## Interfaces

Runnable examples: [`interfacemembers`](../examples/interfacemembers) and
[`interfacedefaults`](../examples/interfacedefaults).

### Interface fields

Every interface field is implicitly `public static final` and must have an
initializer accepted by Ironwood's supported compile-time constant model.
Interface fields do not occupy instance layout. They are inherited names, not
virtual members, and ambiguous inherited constants require qualification.

This phase does not expand interface constants into a general runtime class
initialization mechanism.

### Interface method kinds

An interface may declare:

- a public abstract instance method, with no body;
- a public default instance method, with a body;
- a public static method, with a body;
- a private instance method, with a body; and
- a private static method, with a body.

Public is implicit for abstract and default methods. Protected and
package-private interface methods are invalid. Static and private methods are
not inherited and never occupy virtual/interface dispatch slots. Private
methods are callable only from permitted code in their declaring interface's
nest.

### Default-method resolution

For one erased instance signature:

1. an applicable concrete class method wins over every interface default;
2. otherwise, a default from a more-specific subinterface wins over defaults
   inherited from its ancestors;
3. unrelated surviving defaults are a compile-time conflict until the class or
   subinterface overrides them;
4. an abstract redeclaration in a more-specific interface may re-abstract an
   inherited default; and
5. an explicit `InterfaceName.super.method(...)` selects a permitted direct
   superinterface default without virtual dispatch.

A concrete class must have one valid implementation after these rules. Default
methods are ordinary closed-world functions and may be devirtualized when the
receiver target is known. They do not require JVM bridge machinery at runtime;
any source-required generic bridge behavior is represented in compiler-owned
semantic and IR metadata.

Interface declarations may not provide defaults that illegally conflict with
the public instance contract of `Object`. Existing Java-shaped `Object` methods
remain class methods and retain class-method precedence.

## Enum object model

Runnable example: [`enums`](../examples/enums).

An enum is an implicitly final nominal reference type with the Java-shaped
`ironwood.lang.Enum<E>` base and may implement interfaces. Top-level and
implicitly static member enums are supported; interface member enums are
implicitly public.
Generic and local enums, explicit enum superclass clauses, external construction
with `new`, and named source subclassing are rejected by the current compiler.
Feature 74 implements constant-specific bodies as compiler-owned final subtypes.

Each source constant is an implicitly public static-final field backed by one
compiler-emitted immortal object. Constant arguments select a private enum
constructor, and ordinary instance fields, initializers, methods, interface
dispatch, nested members, casts, arrays, reference generics, and identity
operations retain their existing object-model rules. Constants are constructed
in declaration order before source static actions and acquire their visible
field only after successful construction, preserving D055 partial-state and
failure behavior.

A bodied constant's public field retains the enum type while its immortal object
uses the hidden subtype's layout, descriptor, initialization, and dispatch. The
selected private enum constructor initializes the enum prefix before the body
fields and initializer blocks. Per-constant concrete checks permit every body
to implement a shared interface or enum abstract method independently. Members
unique to a body remain invisible through the enum-typed constant field.

The synthesized `name()` and `ordinal()` members are final; default
`toString()` returns the name unless source supplies an override. Static
`valueCount()`, `valueAt(int)`, and `valueOf(String)` expose allocation-free
order and lookup. Static `values()` returns a fresh caller-owned array in
declaration order. The array is shallowly reclaimable, while its enum-constant
elements remain immortal.
Classic enum `switch` dispatches by the constant's immutable ordinal after one
null check and accepts only unqualified labels of the selector's exact enum
type.

## Generics

Runnable examples: [`boundedgenerics`](../examples/boundedgenerics),
[`genericcallables`](../examples/genericcallables),
[`wildcardcapture`](../examples/wildcardcapture),
[`genericinference`](../examples/genericinference),
[`diamond`](../examples/diamond), [`nestedgenerics`](../examples/nestedgenerics),
[`innerdiamond`](../examples/innerdiamond), and
[`genericcasts`](../examples/genericcasts). Native primitive specialization is
demonstrated by [`primitivegenerics`](../examples/primitivegenerics).

Ironwood implements Java-shaped reference generics with a
closed-world native implementation. It does not require JVM generic metadata.
An unbounded type parameter may additionally receive any Ironwood primitive;
the final closed world emits a native value specialization without boxing.

### Source semantics

- Generic classes, interfaces, methods, and constructors may declare type
  parameters.
- Type parameters may have class and interface upper bounds, including
  dependent bounds, with the same single-class-bound restriction Java
  programmers expect.
- Reference types remain invariant by default.
- Unbounded, upper-bounded, and lower-bounded wildcards are supported.
- Capture conversion gives each wildcard expression the appropriate safe
  read/write view.
- Generic inheritance, overriding, overload resolution, return types, fields,
  constructors, casts, and dispatch apply exact substitution.
- Method and constructor inference and diamond construction choose a unique
  solution or produce an ambiguity/constraint diagnostic.
- Nested generic types preserve both owner and member type arguments.
- A parameter with no explicit upper bound accepts exact primitive arguments
  in classes, interfaces, methods, constructors, inference, and diamond.
- Explicitly bounded parameters remain reference-only, including
  `T extends Object`.
- `instanceof` remains limited to reifiable targets. Ironwood need not add
  runtime generic metadata solely to imitate erased JVM checks.
- The compiler rejects a cast it cannot implement soundly under the selected
  reification model rather than silently introducing Java-style unchecked raw
  behavior.

The native backend shares reference-generic implementations while retaining
exact source types through semantic analysis and typed IR. Primitive positions
materialize specialized layouts, bodies, arrays, and polymorphic dispatch slots
with their exact native widths. Compiler-owned adaptation at dispatch
boundaries is not a visible source member and does not introduce JVM bridge
metadata, wrappers, or a runtime generic registry.

### Reference type patterns

Feature 66 extends plain `instanceof` with a named binding, for example
`value instanceof Box<?> box` or `value instanceof int[] values`. A successful
binding is the original reference with its tested static type: there is no
second cast, object, wrapper, ownership transfer, descriptor, or dispatch
mechanism. Definite-match scope follows Java-shaped boolean, branch, guard, and
supported-loop control flow. Final and effectively-final pattern locals may be
captured by local or anonymous classes, and those hidden fields remain ordinary
aliases under safe-`free`. Non-reifiable generics, primitive patterns, unnamed
and record patterns, and reference patterns in `switch` remain outside this
feature.

### Varargs exclusion

Varargs are not part of Ironwood's source object model. Expanded calls require
a compiler-created array that may have no source-level owner and therefore no
reliable explicit-reclamation point. The compiler diagnoses `T...` parameters;
programs use explicit array parameters or selected fixed-arity overloads. No
hidden argument array reaches typed IR or native lowering.

### Deliberate generic limits

Runnable positive boundary example:
[`throwabletypes`](../examples/throwabletypes).

Raw generic types are not supported. They weaken the static model and exist
primarily for Java compatibility with pre-generics code, which Ironwood does not
need.

Primitive generic arguments use closed-world native specialization. They do not
enter wildcard views: a primitive-specialized object lacks raw generic
membership so `value instanceof Box<?>` is false for that shape and cannot
create a pointer-ABI escape hatch. Exact specialized parent membership remains
available to an otherwise source-provable cast. A specialized body is rejected
if it needs `null`, an `Object` conversion/member, throwing the value, or
another reference-only operation. Automatic boxing/unboxing remains
deliberately excluded because it would hide wrapper allocations without
reliable source-level ownership.

A generic class cannot directly or indirectly extend `Throwable`. Throws may
use a type variable only when its bounds prove `Throwable`; catches require a
reifiable `Throwable` class rather than a type variable or parameterization.
Generic callables within an exception class remain valid. A multi-catch may
combine pairwise-disjoint reifiable exception classes; its one implicitly-final
binding has their least common nominal supertype and remains an ordinary
capturable reference alias. Precise rethrow is a compile-time checked-type fact,
not a new runtime object or object-model union type.

## Closed-world lowering requirements

The implementation may desugar source constructs, but the following boundaries
are mandatory:

- nested/member/local/anonymous types receive deterministic nominal identities;
- hidden enclosing/capture fields and constructor operands are represented in
  compiler-owned semantic data and typed IR;
- source-visible constructor and method signatures remain distinct from hidden
  lowered ABI parameters;
- default/private/static interface bodies become ordinary typed-IR functions;
- abstract declarations never produce callable bodies or concrete dispatch
  entries;
- finality and default selection constrain dispatch-target construction and
  devirtualization;
- field identities include their declaring type so hiding is layout-safe;
- source and class discovery index nested types without treating them as
  runtime-loadable classes;
- every synthetic reference participates in escape summaries and safe-`free`
  analysis; and
- enum constants remain explicit immortal operands whose constructor calls,
  source-visible static stores, hidden name/ordinal fields, and switch ordinal
  loads remain in compiler-owned typed IR.

One suitable internal naming scheme uses binary-style identities such as
`package.Outer$Member` plus deterministic lexical suffixes for local and
anonymous types. Internal names are not source syntax and may be changed before
the compiled format becomes a stable ABI.

## Diagnostics and verification

Completion coverage includes:

- abstract obligations, re-abstraction, invalid instantiation, and final
  extension/override failures;
- final-field definite assignment through constructor delegation and
  exceptional control flow;
- hidden fields selected through `this`, `super`, and differently typed
  receivers;
- static nested, generic nested, inner, local, and anonymous types across
  access boundaries;
- qualified outer access and construction, including missing-enclosing-instance
  errors;
- valid final/effectively-final capture and every assignment form that
  invalidates capture;
- same-nest private access and forbidden non-nest access;
- interface constants and abstract/default/static/private methods;
- class-wins, most-specific-default, re-abstraction, explicit-interface-super,
  and unrelated-default conflict cases;
- bounded generic inference, wildcard capture, nested owner substitution,
  generic override compatibility, and rejected unsound casts;
- checked method/constructor/interface throws contracts, generic throws
  substitution, override narrowing, unreachable checked catches, disjoint
  multi-catch alternatives, implicitly-final capture, and precise rethrow;
- ordinary `AutoCloseable.close()` bounds, explicit `try`/`finally` cleanup,
  first-failure propagation, ordered secondary exceptions, and the separation
  of `close()` from explicit object-memory `free`;
- fixed-arity overload selection;
- enum construction order, stable identity/name/ordinal, interface and generic
  use, constant-specific subtype layout/initialization/dispatch, per-constant
  obligations, lookup failures, no-allocation invariants, exact enum-switch
  labels, and rejection of construction/subclassing/mutation;
- recursive invariant array types, exact reifiable array tests/casts, and
  visible child allocation/detachment ownership;
- named `instanceof` bindings across boolean/statement flow, reifiable generic
  and exact-array targets, lexical capture, artifact reconstruction, and typed
  alias ownership; and
- safe-`free` rejection for enclosing/captured aliases, plus positive cases
  where no capture occurs.

Representative programs run natively at every supported optimization level and
survive separate compilation and archive reconstruction. Local and anonymous
types are reconstructed from their owning top-level format-1 source payload;
they are not emitted as independently addressable `.ironclass` entries.

## Completed extension and deliberate exclusions

D064 extends the completed object-model boundary with mandatory `@Override`.
Every declared inherited instance override or interface implementation uses the
exact built-in directive, and every directive must identify a valid inherited
instance target. Its placement is modifier-like, but it is not an annotation.

The following are deliberately excluded and will not be supported without an
explicit future reversal:

- annotations, annotation declarations, annotation processing, and annotation
  reflection; the built-in `@Override` and testing-only `@Test` directives are
  not annotations;
- variable-arity parameters and expanded varargs calls;
- raw generic types, legacy unchecked raw conversions, non-reifiable generic
  tests, and unprovable parameterized casts;
- automatic boxing/unboxing and Java array covariance;
- arbitrary runtime reflection, runtime member invocation, and reflective
  construction;
- runtime class loading, user class loaders, runtime-generated classes,
  bytecode generation, and dynamic proxies;
- records, sealed types, reference type patterns for `switch`, record/unnamed
  patterns, preview primitive patterns, lambdas, method references, and closure
  syntax;
- threads, thread-local storage, object monitors, `synchronized`, source-level
  `volatile`, atomics, and concurrent collections;
- JVM serialization and `Object.clone()`/`Cloneable` behavior, monitor metadata,
  finalization, and garbage-collector-dependent reference types; a user-defined
  method named `clone` remains an ordinary method with no inherited or compiler
  semantics;
- Java's process-global `String.intern()` pool for arbitrary runtime Strings;
  compile-time literal pooling remains implemented; and
- a stable binary object ABI or permanent nested-type mangling scheme.

Static imports are implemented under Feature 86 for accessible static fields,
methods, and member types. They reuse ordinary member access, overload,
initialization, and safe-`free` behavior and add no object-layout, allocation,
ownership, dispatch, or runtime rule. Ownership-safe array initializer syntax
is implemented under Feature 89. Other unrelated conveniences remain outside
this object-model decision unless separately classified.

Compile-time source generation or a future explicit native FFI may be designed
separately. Neither changes these exclusions by implication.

## Relationship to standard-library work

This document defines language semantics only. It does not authorize or require
new standard-library APIs. Feature 75 literal pooling and implemented Feature
76 concatenation are language/compiler semantics; a future application-owned
interner would be an ordinary collection rather than a `String.intern()`
contract. The object model is complete and validated, so any separately
authorized future library work can rely on these Java-shaped abstraction,
nesting, interface, and reference-generic rules.
