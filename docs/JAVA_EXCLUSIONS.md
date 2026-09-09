# Java Support and Exclusions

Ironwood strives to make native development feel like Java. It keeps Java's familiar
object model and everyday source style, while leaving out features that depend
on a JVM, garbage collection, runtime dynamism, hidden allocation, or a
shared-memory concurrency model.

This guide highlights the Java features Ironwood preserves, the mechanisms it
adapts for native development, and the complexity it deliberately leaves
behind. For the complete Java SE 26 feature audit, see
[Ironwood vs Java](IRONWOOD_VS_JAVA.md).

> The choices below reflect Ironwood's current product direction and may evolve when compelling use cases and community feedback justify a change.

| Java feature area | Ironwood status | Product rationale |
| --- | --- | --- |
| Java-shaped object-oriented programming | ✅ Supported | Classes, constructors, access control, inheritance, interfaces, polymorphism, overloading, casts, `instanceof`, static members, and initialization retain familiar Java shapes. |
| Nested, inner, local, and anonymous classes | ✅ Supported | These work with final or effectively final capture. Ironwood also tracks hidden enclosing references for safe reclamation. |
| Safe generics | ✅ Supported | Generic types and methods, bounds, wildcards, inference, and diamond are available without Java's unsafe legacy escape hatches. |
| Primitive generic arguments | 💡 Ironwood design | Types such as `Box<int>` are specialized directly into native value storage. This avoids boxing and wrapper allocation. |
| Arrays | 💡 Ironwood design | Primitive, reference, and multidimensional arrays are supported. Arrays are invariant, and child arrays are allocated explicitly so type safety, ownership, and lifetime remain visible. |
| Exceptions | ✅ Supported | Checked and unchecked exceptions, `throw`, `catch`, `finally`, causes, and source stack traces retain familiar Java shapes. |
| Try-with-resources syntax | ❌ Deliberately excluded | Resources are declared normally and closed in an explicit `finally`. This keeps cleanup order visible and keeps `close()`, which releases an external resource, separate from `free`, which reclaims object memory. |
| Cleanup failure handling | 💡 Ironwood design | If an operation such as `read()` throws first and cleanup such as `close()` throws second, the first exception remains primary. The second and any later failures are recorded as secondary exceptions in occurrence order. This rule applies to every `finally`, not only resource cleanup. |
| Enums and control flow | ✅ Supported | Java-shaped enums, classic and modern non-pattern `switch`, switch expressions, enhanced `for`, labeled flow, and ordinary statements are available. |
| Strings, literals, and comments | ✅ Supported | Java-shaped strings, concatenation, text blocks, the documented escape set, and comments are supported, with raw text blocks added as an Ironwood convenience. |
| Packages and imports | ✅ Supported | Named and unnamed packages, ordinary and wildcard imports, static imports, visibility, and public-type filename rules retain familiar Java shapes. |
| Reusable libraries | 💡 Ironwood design | `.ironclass` and `.ironjar` files provide compile-time reuse. The final link rebuilds them into one optimized native program instead of loading or linking Java binaries at runtime. |
| Garbage collection | ❌ Purposefully excluded | Ordinary objects are not reclaimed merely because they become unreachable. Ironwood therefore has no collector pauses or runtime GC tuning. |
| Deterministic memory reclamation | 💡 Ironwood design | A compiler-accepted `free` runs the destructor chain and releases an allocation only after proving that no live alias can observe it afterward. |
| Javadoc tooling | 💡 Focused alternative | IronDocs supports a practical Javadoc-style subset and generates Markdown. The full Javadoc and custom-doclet ecosystem is outside the current scope. |
| Raw generic types, unchecked casts, and array covariance | ❌ Deliberately excluded | These legacy compromises can weaken type safety and defer failures to runtime. Ironwood rejects them at compile time. |
| Automatic boxing and unboxing | ❌ Deliberately excluded | An innocent-looking conversion can create a hidden wrapper with no clear owner or reclamation point. Ironwood keeps primitives as values and specializes generic code instead. |
| Varargs | ❌ Deliberately excluded | An expanded varargs call creates a hidden array that may escape or allocate repeatedly on a hot path. Use fixed-arity overloads or an explicit caller-owned array. |
| Lambdas, closures, and method references | ❌ Deliberately excluded | They can hide captured-object allocation and ownership. Anonymous classes provide an explicit object and lifetime for callback behavior. |
| General annotations and annotation processing | ❌ Deliberately excluded | An open-ended metadata, processor, and reflection subsystem would enlarge the language and runtime. Ironwood provides only narrow compiler-owned directives such as `@Override` and `@Test`. |
| Records and sealed types | ❌ Deliberately excluded | Records hide object-policy choices behind generated code. Sealed syntax is not needed for the compiler to know and optimize the complete hierarchy. |
| Runtime reflection and `Class` objects | ❌ Deliberately excluded | Reflection is possible in theory, but its metadata and invocation machinery would retain dead code, enlarge the runtime, and weaken whole-program optimization. |
| Runtime class loading and dynamic proxies | ❌ Closed-world boundary | Types cannot arrive after the final native link. A JVM-like class loader or runtime code generator would contradict Ironwood's ahead-of-time execution model. |
| Threads, monitors, `synchronized`, `volatile`, and atomics | ❌ Purposeful product decision | Ironwood favors predictable single-threaded hot paths and a small runtime over shared-memory concurrency complexity. Use an event loop or isolated native processes. Nonblocking networking is planned library work. |
| Java serialization, universal cloning, and finalization | ❌ Deliberately excluded | These mechanisms rely on reflective object graphs, hidden field copying, or garbage-collector callbacks. Types should define explicit formats, copy operations, resource cleanup, and ownership. |
| Runtime `String.intern()` | ❌ Deliberately excluded | A global pool for arbitrary input creates hidden aliases and potentially unbounded retention. Use an explicit application-owned interner with a chosen capacity and lifetime. |
| JPMS modules and Java binary compatibility | ❌ Closed-world boundary | Ironwood dependencies are compile-time inputs, not runtime modules or Java `.class` files. The final link rechecks and optimizes the complete program instead of preserving JVM linkage rules. |
| Pattern `switch`, record patterns, and preview primitive patterns | ❌ Not currently supported | Ordinary `instanceof` type patterns and modern non-pattern `switch` are already supported. The larger pattern families add complexity without a compelling current need, and Java preview syntax is not an Ironwood commitment. |
| Runtime-selectable `assert` statements | ❌ Deliberately excluded | Validation should not silently disappear with a runtime flag. Use explicit checks and failure control flow that remain active in every build. |
| Selected Java syntax and modifiers | ❌ Mixed: deliberate and current scope | This includes `var`, compact sources, expanded `main`, flexible constructor prologues, local enums and interfaces, intersection casts, Unicode source conveniences, octal and hexadecimal floating-point literals, plus `native`, `strictfp`, and `transient`. These are not gaps in Ironwood's core object model. |

The rule is simple: Ironwood follows Java where the result remains suitable for a
small, high-performance native runtime. When a Java feature would hide
allocation, weaken type safety, require runtime dynamism, or assume garbage
collection, Ironwood makes the difference explicit.
