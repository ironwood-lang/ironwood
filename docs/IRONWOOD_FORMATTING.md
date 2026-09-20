# Ironwood formatting

This file records the canonical formatting and source-style rules for Ironwood
code. Apply these rules when authoring or revising `.iron` files. A formatting
preference must not change program semantics.

## Instance field access

Qualify every access to an instance field with `this.`, including reads,
writes, array access, and arguments passed to another callable. Do not apply
this rule to local variables, parameters, static fields, or members accessed
through another expression.

## Blank lines between members

Place one blank line between a member declaration and the Javadoc for the next
member. Do not place a blank line between Javadoc and the declaration it
documents.

Place one blank line immediately inside the opening brace of every multiline
type or callable body. This includes classes, interfaces, enums, constructors,
methods, destructors, and anonymous type bodies. Represent an empty type or
callable body in multiline form with one blank interior line. Expand non-empty
one-line constructors, methods, and destructors to multiline form, retaining
the blank line immediately after the opening brace.

## Control-flow blocks

The no-leading-blank-line exception applies only to control-flow blocks. Do
not place a blank line immediately after the opening brace of an `if`, `else`,
`for`, `while`, `switch`, `try`, `catch`, or `finally` block. Types, methods,
constructors, and destructors continue to require the blank line specified
above.

When an `if` has an `else` or `else if`, use braces around each branch and
format each branch over multiple lines:

```java
if (condition) {
    doFirstThing();
} else {
    doSecondThing();
}
```

## Deferred cleanup

Place each `defer` void call or `defer free name;` on its own line directly inside
an explicit block. Use braces even for a conditional or loop body containing only
a deferred action.
Order declarations opposite to their desired cleanup order. Keep separate
operations separate. To close before freeing, declare `defer free resource;`
before `defer resource.close();`. Keep the free target binding unchanged until
cleanup; prefer a separate local for a later allocation.

## Standalone blocks

Avoid standalone statement blocks in ordinary code and documentation examples.
Place deferred cleanup in the existing method, loop, conditional, or try body
when extending the allocation's lifetime to that body's exit is acceptable.
Do not add a block merely to reproduce the former `finally` boundary.

Retain a smaller scope when its cleanup must finish before a later operation,
when alias expiration is required for safe reclamation, or when a focused test
specifically verifies block-exit behavior. Explain that boundary. A meaningful
helper method can also provide an early cleanup boundary; avoid artificial
control flow introduced only to hide a standalone block. Instance initializer
blocks and required control-flow bodies are separate language constructs.

## Single-statement `if` bodies

Write an `if` or `else if` whose body is exactly one simple statement on one
line without braces when it has no `else` or `else if` branch:

```java
if (condition) statement;
```

Do not place that statement on a following line. Nested control statements and
comments are not simple statements for this rule. The braced multiline form
above takes precedence whenever an `else` or `else if` branch is present.
