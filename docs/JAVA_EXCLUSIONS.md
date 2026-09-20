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
| Try-with-resources syntax | ❌ Deliberately excluded | Resources are declared normally and closed in an explicit `finally` or by a [deferred void call](LANGUAGE.md#explicit-deferred-cleanup-milestone-1). This keeps cleanup order visible and keeps `close()`, which releases an external resource, separate from `free`, which reclaims object memory. Both Java resource-header forms remain rejected. |
| Cleanup failure handling | 💡 Ironwood design | If an operation such as `read()` throws first and cleanup such as `close()` throws second, the first exception remains primary. The second and any later failures are recorded as secondary exceptions in occurrence order. This rule applies to ordinary `finally` and deferred actions, not only resource cleanup. |
| Enums and control flow | ✅ Supported | Java-shaped enums, classic and modern non-pattern `switch`, switch expressions, enhanced `for`, labeled flow, and ordinary statements are available. |
| Strings, literals, and comments | ✅ Supported | Java-shaped strings, concatenation, text blocks, the documented escape set, and comments are supported, with raw text blocks added as an Ironwood convenience. |
| Packages and imports | ✅ Supported | Named and unnamed packages, ordinary and wildcard imports, static imports, visibility, and public-type filename rules retain familiar Java shapes. |
| Reusable libraries | 💡 Ironwood design | `.ironclass` and `.ironjar` files provide compile-time reuse. The final link rebuilds them into one optimized native program instead of loading or linking Java binaries at runtime. |
| Garbage collection | ❌ Purposefully excluded | Ordinary objects are not reclaimed merely because they become unreachable. Ironwood therefore has no collector pauses or runtime GC tuning. |
| Deterministic memory reclamation | 💡 Ironwood design | A compiler-accepted `free` runs the destructor chain and releases an allocation only after proving that no live alias can observe it afterward. `defer free name;` schedules that same proven-safe operation for explicit block exit and keeps the local binding unchanged until cleanup. |
| Javadoc tooling | 💡 Focused alternative | IronDocs supports a practical Javadoc-style subset and generates Markdown. The full Javadoc and custom-doclet ecosystem is outside the current scope. |
| Raw generic types, unchecked casts, and array covariance | ❌ Deliberately excluded | These legacy compromises can weaken type safety and defer failures to runtime. Ironwood rejects them at compile time. |
| Automatic boxing and unboxing | ❌ Deliberately excluded | An innocent-looking conversion can create a hidden wrapper with no clear owner or reclamation point. Ironwood keeps primitives as values and specializes generic code instead. |
| Varargs | ❌ Deliberately excluded | An expanded varargs call creates a hidden array that may escape or allocate repeatedly on a hot path. Use fixed-arity overloads or an explicit caller-owned array. |
| Lambdas, closures, and method references | ❌ Deliberately excluded | They can hide captured-object allocation and ownership. Anonymous classes provide an explicit object and lifetime for callback behavior. |
| General annotations and annotation processing | ❌ Deliberately excluded | An open-ended metadata, processor, and reflection subsystem would enlarge the language and runtime. Ironwood provides only narrow compiler-owned directives such as `@Override`, `@Test`, and `@SuppressUnfreed`. |
| Records and sealed types | ❌ Deliberately excluded | Records hide object-policy choices behind generated code. Sealed syntax is not needed for the compiler to know and optimize the complete hierarchy. |
| Runtime reflection and `Class` objects | ❌ Deliberately excluded | Reflection is possible in theory, but its metadata and invocation machinery would retain dead code, enlarge the runtime, and weaken whole-program optimization. |
| Runtime class loading and dynamic proxies | ❌ Closed-world boundary | Types cannot arrive after the final native link. A JVM-like class loader or runtime code generator would contradict Ironwood's ahead-of-time execution model. |
| Threads, monitors, `synchronized`, `volatile`, and atomics | ❌ Purposeful product decision | Ironwood favors predictable single-threaded hot paths and a small runtime over shared-memory concurrency complexity. Event-driven networking remains planned work required by N1, not an available socket capability. The [blocking TCP foundation](NETWORKING_MIGRATION_PLAN.md#relationship-to-n1-and-future-event-loop-networking) supports clients and sequential server examples; a blocking call stalls the process's application work. Isolated native processes remain an architectural alternative, not an in-language threading or process-management API. |
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

The networking migration applies this rule to extension APIs as well.
It replaces Java's boxed `SocketOptions` protocol with primitive-specialized
generic option hooks, while retaining the requested global socket factory hooks
despite their Java 17 deprecation. UDP-selecting constructor overloads remain
absent because UDP is outside the migration. Deprecation alone is not the
criterion. Milestone 1 implements this representative TCP extension protocol; see
[D152](DECISIONS.md#d152---establish-socket-extension-contracts-in-the-first-tcp-milestone)
and the [extension contract](NETWORKING_MIGRATION_PLAN.md#socketimpl-protocol-and-ownership)
for option, delegation, and lifetime boundaries.

The planned `Enumeration<E extends Object>` and reference-only networking
helpers declare their bounds explicitly under accepted
[D160](DECISIONS.md#d160---declare-networking-reference-bounds-without-disabling-primitive-options).
Primitive enumeration arguments fail at the caller boundary. `SocketOption<T>`
and its generic option methods deliberately remain unbounded for native
primitive values, as do the existing general-purpose `Iterator` and `Iterable`
interfaces. See the [networking bound rules](NETWORKING_MIGRATION_PLAN.md#generic-parameter-bounds).

The accepted [networking result contracts](NETWORKING_MIGRATION_PLAN.md#non-stream-results-and-input-ownership)
also distinguish cached address/inventory borrows from fresh accepted sockets,
endpoint snapshots, and resolver results. Interface metadata views borrow from
an explicit query owner. Arrays and `ironwood.ds` collections retain their
ordinary element-ownership rules; read-only access does not imply ownership.
[D154](DECISIONS.md#d154---define-network-result-ownership-and-connection-allocation-budgets)
records these choices, copied exception messages, and the requirement to measure
complete connection allocations as well as steady-state I/O.

The accepted [fixed networking policies](NETWORKING_MIGRATION_PLAN.md#fixed-networking-policies)
replace Java property configuration with enumerated conventions: dual-stack
capability, IPv4-first address preference, the pinned default IPv4 literal
grammar, OS name services without an Ironwood DNS cache, and explicit proxy
configuration. SOCKS5 is the default; SOCKS4 remains an explicit choice, with
no automatic V5-to-V4 retry. Credentials are supplied explicitly, and optional
host-info exception enrichment stays disabled. Networking keys do not enlarge
the native `System.getProperty` subset, and no property setter or Java network
configuration-file reader is introduced. These are accepted policies under
[D155](DECISIONS.md#d155---fix-networking-property-conventions-explicitly), not
additional Java configuration APIs. Milestones 2 and 4 now implement the DNS
and explicit-proxy portions under those conventions.

Accepted [D159](DECISIONS.md#d159---name-explicit-proxy-credential-factories-as-ironwood-extensions)
names `Proxy.socks5(address, username, password)` and
`Proxy.httpConnectBasic(address, username, password)` as **Ironwood extensions**.
They accept explicit credential bytes, return owned immutable proxy settings,
and work through `Socket(Proxy)` and the planned TLS/downloader proxy path.
They replace the selected uses of Java's `Authenticator` callback mechanism;
`Authenticator` and `PasswordAuthentication` are omitted, not partially ported.
See the [credential extension contract](NETWORKING_MIGRATION_PLAN.md#ironwood-proxy-credential-extensions)
for encoding, ownership, and proxy-only authentication behavior.

The planned `ironwood.net.tls.TlsClient` and HTTPS downloader require certificate
and hostname/IP verification, but exclude CRL/OCSP revocation checking, automatic
system trust-store discovery, session resumption, and TLS 1.3 early data. Trust
comes from bundled roots or an explicit custom bundle replacing those roots;
there is no fallback to ambient trust. Every new connection performs a full
handshake, whose success does not establish revocation status. These are accepted planned
boundaries under [D158](DECISIONS.md#d158---bound-tls-trust-revocation-and-session-behavior),
not implemented TLS features or a full JSSE compatibility claim. See the
[TLS scope](NETWORKING_MIGRATION_PLAN.md#tls-client-scope-and-exclusions).
