# System output source and provenance review

This review records the licensing and architecture gate for D044 before the
Ironwood implementation of `System.out.println(...)` was written. The governing
policies are [`LICENSE_MECHANICS`](../LICENSE_MECHANICS) and
[`OPENJDK_PORTING.md`](OPENJDK_PORTING.md).

This remains the historical D044 review for the initial stdout operation. D086
independently implements the later stderr, primitive/Object overload, flush,
error-state, environment, and clock surface; its current classification and
boundaries are in [`STDLIB_U1_SOURCE_REVIEW.md`](STDLIB_U1_SOURCE_REVIEW.md).

## Provenance decision

`ironwood.lang.System` and `ironwood.io.PrintStream` are independent
Java-compatible Ironwood implementations under
`SPDX-License-Identifier: MIT OR Apache-2.0`. The public Java-shaped field and
method names are compatibility targets. No OpenJDK implementation body,
comment, Javadoc, test, or distinctive internal structure is translated or
adapted.

The implementation uses Ironwood's existing compiler-owned typed IR and narrow
native output boundary. It does not introduce an OpenJDK-derived file, so no
entry belongs in the OpenJDK-derived file ledger and no OpenJDK corresponding
source is added to Ironwood distributions.

## Exact upstream inspection

For API and mechanism classification only, the following OpenJDK 21 Update
files were inspected at immutable commit
`54f2095960f01d957f2335cafa0defb956e13a2c` (tag `jdk-21.0.8+9`):

- [`src/java.base/share/classes/java/lang/System.java`](https://github.com/openjdk/jdk21u/blob/54f2095960f01d957f2335cafa0defb956e13a2c/src/java.base/share/classes/java/lang/System.java)
- [`src/java.base/share/classes/java/io/PrintStream.java`](https://github.com/openjdk/jdk21u/blob/54f2095960f01d957f2335cafa0defb956e13a2c/src/java.base/share/classes/java/io/PrintStream.java)

Each exact file header expressly says that Oracle designates the file as
subject to the Classpath Exception. That makes derivation legally reviewable,
but D044 deliberately chooses an independent implementation instead.

## Relevant member classification

| Upstream surface or mechanism | Category | Ironwood treatment |
| --- | --- | --- |
| `System.out` public field | JVM-initialized field | Provide the familiar `public static final PrintStream` surface as an immortal compiler/runtime-owned singleton. |
| `registerNatives`, `setOut0`, and phased system initialization | Native method and VM bootstrapping | Do not port. Closed-world native code emits the one initialized stream object without native registration or a VM startup phase. |
| `setOut` and mutable standard-stream replacement | Security-manager and VM behavior | Unsupported in this slice. `out` remains fixed and final. |
| Construction from file descriptors, buffered streams, selected charsets, and host properties | Native/portable I/O stack plus VM configuration | Do not port. Ironwood retains its synchronous UTF-16-to-UTF-8 stdout boundary and deterministic replacement behavior. |
| `PrintStream.println(String)` | Portable public behavior implemented over a complex stream stack upstream | Implement independently as the initial facade operation, ending the supplied UTF-16 text with one newline through typed IR. |
| Other `println` overloads | Portable conversions and formatting | Add only independently when Ironwood supports the required conversion semantics. They are not inferred from or translated from upstream bodies. |
| `PrintStream` constructors, buffering, encoding selection, `write`, `print`, formatting, append, flush, close, and error state | Portable stream APIs with I/O, charset, synchronization, and formatting dependencies | Deferred until the corresponding Ironwood stream and formatting abstractions exist. |
| `System.arraycopy` and `identityHashCode` | JVM intrinsic/native methods upstream | Already implemented independently through Ironwood typed intrinsics and its native runtime boundary. |
| `currentTimeMillis`, `nanoTime`, environment/properties, inherited channel, and native-library loading | Native method, platform service, reflection, caller-sensitive, or dynamic behavior | Deferred; native-library loading and caller-sensitive JVM behavior are not part of this slice. |
| Security-manager, class-loader, module-bootstrap, reflection, and shared-secret machinery | VM/dynamic behavior | Deliberately unsupported. |
| `gc` and finalization services | GC/finalization behavior | Deliberately unsupported because Ironwood has no collector or finalization model. |

This classification does not promise future implementation of deferred OpenJDK
surface. Each later library slice requires its own provenance and architecture
review.
