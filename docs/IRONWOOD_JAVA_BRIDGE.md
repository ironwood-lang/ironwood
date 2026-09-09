# Ironwood Java Bridge Proposal

This document proposes how ordinary Java programs can call Ironwood native
code with no hand-written glue. It is a design proposal, not an accepted
decision: nothing described here is implemented, no `DECISIONS.md` entry has
been accepted for it, and it changes no supported status in
[`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md). The working name for the feature is
**ironbind**; the flag and tool names below are placeholders.

The implementation observations behind this proposal were checked directly
against the compiler and runtime, including the LLVM IR emitted for
`examples/objects`.

## 1. Summary

The recommendation in one paragraph: do not write a JNI layer, and do not
design a general FFI. Add a shared-library link mode to `ironwoodc`, treat the
public API of the exported packages as the export surface, and let the compiler
generate *both ends* of the bridge: C-ABI trampolines in LLVM IR on the native
side, and a Java facade with the same package, class, method, and enum names on
the Java side. Transport is the JDK's Foreign Function and Memory API (final in
JDK 22, LTS in JDK 25); a compiler-generated JNI adapter is a fallback for
older JDKs. The Java program uses `org.example.orderbook.OrderBook` exactly as it
would use a Java class. Swapping a jar switches an application from a Java
engine to the native Ironwood engine.

What makes this more than "generate bindings":

1. **Ironwood objects never move and are never collected.** A reference is a
   stable native address for the object's whole life. A Java handle is a `long`.
   No pinning, no global references, no handle tables.
2. **The compiler owns both ends, so "no stable ABI" stops mattering.** The
   facade and the library are one paired build. The API lives in generated Java
   source; the ABI (offsets, sizes, symbol names, build hash) lives in a manifest
   embedded in the library and read at load time.
3. **Ironwood's closed-world analyses already compute what a binding generator
   needs.** Fresh-versus-borrowed results decide which Java objects get
   `close()`. Argument-retention summaries decide which Java references the
   facade must keep alive. Non-retention proofs decide when a Java array may be
   passed zero-copy. Call-graph reachability decides when a call may use the
   JDK's fast "critical" path. Checked `throws` clauses become checked Java
   exceptions. This is the novel part: ownership analysis shapes a foreign API.
4. **The existing native `main` wrapper is already the trampoline shape.** It
   calls the type-initialization ensure routine, invokes the source method, and
   catches every Ironwood exception in a landing pad. Export trampolines are the
   same code with a status word instead of `exit(1)`.
5. **Allocation-free crossings both ways.** `long` handles, per-thread scratch
   blocks, flyweight views in callbacks, and Ironwood-owned byte arrays exposed
   to Java as memory segments keep the steady state at zero Java allocations,
   which is what low-latency Java applications need.

The big-picture framing: Ironwood becomes the **typed off-heap layer for
latency-critical Java**. Instead of hand-written flyweights over `ByteBuffer`
and `Unsafe`, the hot path is real code with classes, inheritance, generics,
exceptions, and deterministic reclamation, compiled by LLVM, and called from
Java at the cost of one native call.

## 2. Why Ironwood is unusually easy to bridge

These are facts about the current implementation, not hopes.

| Fact | Where it is | Why it matters for a bridge |
| --- | --- | --- |
| Objects are `calloc` allocations with a leading descriptor pointer; nothing moves them and nothing collects them. | `runtime/src/ironwood_runtime.c`, [`MEMORY.md`](MEMORY.md) | A native address is a valid handle for the object's whole life. Java can hold it as a `long`. |
| Every Ironwood function is emitted with `internal` linkage. Only the native `main` wrapper is external. | `LlvmEmitter` | A shared library needs only a small, explicit, compiler-chosen export surface. Nothing leaks by accident. |
| The program object is built with `--relocation-model=pic`, the runtime with `-fPIC`. | `NativeBackend` | The objects are already position independent. A shared-library link is a driver-flag change, not a code-generation change. |
| The native `main` wrapper calls `ironwood.initialize.<Type>`, invokes the entry, and catches everything with `landingpad ... catch ptr null`, then `ironwood_exception_take`. | Emitted IR for `examples/objects` | An export trampoline is this exact shape. Exceptions must never unwind into a JVM frame, and this pattern already stops them. |
| `String` layout is `{ descriptor, i32 utf16Length, i32 utf8Length, u16 units[] }` with the tail at byte offset 16. Array layout puts elements at byte offset 32. Both are static-asserted. | `ironwood_runtime.c` | Java can read Ironwood strings and arrays directly through a memory segment. |
| Type descriptors carry the qualified name, destructor and rollback entries, and dispatch tables. `ironwood.destroy` runs the derived-to-root destructor chain, then `ironwood_deallocate`. | `LlvmEmitter`, [`COMPILER.md`](COMPILER.md) | A generic "destroy this handle" entry point exists in spirit already. |
| Type initialization is lazy and per type, through ensure routines that any entry point can call. | [`COMPILER.md`](COMPILER.md), emitted IR | There is no global initialization order problem. Any exported method can be the first call. |
| The `@Test` directive synthesizes ordinary methods (`run(int)`, `main`) that enter typed IR like user code. | `TestHarnessSynthesizer`, D110 | Precedent for compiler-generated glue that adds no annotations, reflection, or runtime registry. D045 explicitly leaves compile-time source generation open. |
| Fresh-result, borrowed-result, retention, escape, and non-retention callback proofs are computed to a fixed point for every callable. | `SymbolicReturnOriginAnalyzer`, `EscapeSummaryAnalyzer`, D094, D096, D107 | The binding generator does not need new analyses. It needs to *read* existing ones. |
| Throwables carry source traces with callable, `.iron` file, and line, exposed through `getStackTrace()` and runtime trace functions. | D121, `ironwood_runtime.h` | Java stack traces can show Ironwood frames. |
| The bootstrap compiler is a Java 21 program, and the IDK bundles a JDK. | `compiler/`, `packaging/` | It can emit Java source or class files with no extra toolchain. |
| D001 already says the final link produces "a native executable **or native library**." | [`DECISIONS.md`](DECISIONS.md) | The shared-library mode is inside the accepted architecture. |

Two documents set the boundaries this design respects. `LANGUAGE_SPECS.md`
says native interoperability "will use an Ironwood-specific FFI and explicit
unsafe boundary if and when it is designed" and that "raw native addresses must
not leak into ordinary Ironwood code." `STDLIB_ROADMAP.md` says "a private
standard-library native bridge is acceptable. A general public FFI is not
required." The design below is not a general FFI. No Ironwood source gains a
pointer, a `native` keyword, or an annotation. The only new native-facing code
is compiler-synthesized, exactly like the `@Test` harness.

## 3. The design: same API, native engine

### 3.1 Developer experience

Nothing changes in Ironwood source. The exported package is the input. The
commands below use an illustrative `org.example.orderbook` package.

```sh
ironwoodc --source-path src/main/ironwood -d target/classes \
    src/main/ironwood/org/example/orderbook/*.iron
ironwoodc --link --shared --export org.example.orderbook -cp target/classes \
    -o target/liborderbook.dylib --java-facade target/generated-java -O3
```

The second command produces:

- `target/liborderbook.dylib` (or `.so`): the closed-world program with no `main`,
  plus one exported C-ABI trampoline per public callable of the exported
  package, one destroy entry per exported class, a handful of bridge helpers,
  and an embedded manifest.
- `target/generated-java/org/example/orderbook/*.java`: a Java facade with the
  same package and type names. `OrderBook`, `Order`, `PriceLevel`, the nested
  enums `Side` and `TimeInForce`, the interface `OrderBookListener`, and the
  exception classes.

The Java program contains no bridge-specific code beyond its imports:

```java
import org.example.orderbook.OrderBook;
import org.example.orderbook.Order.Side;
import org.example.orderbook.Order.TimeInForce;

OrderBook book = new OrderBook("AAPL", new MyJavaListener()); // Java class implementing the generated interface
book.createLimit(1001L, "1", 1, Side.BUY, 200, 150.44, TimeInForce.DAY);
long best = book.getBestBidPrice();
book.close();
```

Loading is implicit. The facade jar carries the native library under
`META-INF/ironwood/native/<os>-<arch>/`; the static initializer of the first
facade class used extracts and loads it, binds every downcall handle once, and
verifies that the library's embedded build hash matches the facade. This is
where `System.loadLibrary` calls conventionally live in Java libraries, and it
keeps the bridge invisible in ordinary application code. An explicit library
object exists only for the advanced cases in section 8.

This is the same source a Java programmer would write against a pure-Java
`OrderBook`. Constructors are constructors, methods are methods, enums are
enums, listeners are interfaces. The one visible difference is that objects
Java owns have `close()`, explained in section 3.5.

### 3.2 The three layers

```text
Java program
  -> generated Java facade (same names; long handles; per-thread bridge block)
  -> FFM downcall / upcall method handles (or generated JNI in compatibility mode)
  -> compiler-emitted C-ABI trampolines (ensure init, invoke, catch-all, status)
  -> ordinary Ironwood closed world (unchanged objects, dispatch, runtime)
```

**Native export layer.** For each exported callable the LLVM emitter writes one
external, default-visibility function. Parameters use the Java-side ABI: `i64`
for handles, exact primitive widths otherwise. The body is the `main` wrapper
pattern: call the owner type's ensure routine, `invoke` the internal Ironwood
function, return the value on the normal edge, and on the unwind edge take the
exception object, store it in the per-thread bridge block, and return zero. The
runtime is compiled with hidden visibility in this mode so `ironwood_allocate`
and friends do not become global symbols of the JVM process.

**Transport.** FFM downcall handles bound once at class initialization from
`SymbolLookup.libraryLookup`. Upcall stubs for callbacks. Handles cross as
`ValueLayout.JAVA_LONG`, so the hot path creates no `MemorySegment` objects.

**Java facade.** Final classes holding a `long handle`. Static methods for
static methods. Enums mirrored by ordinal. Interfaces mirrored as interfaces.
Exceptions mirrored as Java exceptions. Javadoc produced from the IronDocs
comments the compiler already parses.

### 3.3 The per-thread bridge block

Every trampoline takes one extra `i64`: the address of a small native block the
Java side owns per thread (allocated once, from an arena). After a call the
facade reads a status `int` from that block through one global memory segment
(`MemorySegment.NULL.reinterpret(Long.MAX_VALUE)`, a plain load, no downcall).
Nonzero means an exception is pending and its handle is in the block. The block
also carries scratch buffers for string marshalling. This avoids out-parameter
segments, struct returns, and a second call per operation.

### 3.4 Exceptions, both directions

Ironwood to Java: the trampoline stores the exception object; the facade builds
a Java exception of the mirrored class (`OrderBookListenerException` in Java
extends `IronwoodException`), copies the message, and fetches the source frames
from the runtime trace so the Java stack trace reads:

```text
org.example.orderbook.OrderBookListenerException: listener failed
    at org.example.orderbook.OrderBook.createLimit(OrderBook.iron:903)
    at org.example.orderbook.OrderBook$Facade.createLimit(OrderBook.java:88)
    at com.acme.trading.Gateway.onNewOrder(Gateway.java:41)
```

Checked exceptions in an Ironwood `throws` clause become checked exceptions in
the facade signature. Unchecked ones map to unchecked mirrors. The exception
object itself is an owned handle inside the Java exception, so exported
getters work on it and `close()` releases its native trace storage.

Java to Ironwood (inside callbacks): an exception escaping an upcall stub
terminates the JVM, so the generated upcall adapter catches every `Throwable`,
stores it in the bridge block, and returns a status. The Ironwood-side proxy
turns that status into `throw new ForeignException(...)`, an ordinary unchecked
Ironwood exception, so source-level listener-failure isolation works unchanged.

### 3.5 Reclamation from Java: `close()`

`close()` is the Java spelling of Ironwood's `free`. The `OrderBook` above is an
Ironwood object allocated by `ironwood_allocate` inside the library, and
Ironwood has no collector. Once a reference has crossed to Java, no Ironwood
code can prove that no alias exists, so the compiler treats the boundary as
publication and never accepts an Ironwood `free` for that object. The only
party that knows when the object is finished is the Java program.

Calling `close()` runs the compiler-emitted destroy entry for the class, which
executes the destructor chain from the dynamic class to the root and then
deallocates, exactly what an accepted source `free` does. The facade then
zeroes its handle. A second `close()` is a no-op, and any other call on a
closed facade throws `IllegalStateException` on the Java side instead of
touching freed memory.

Omitting `close()` is legal, with the same consequence as omitting `free` in
Ironwood: the allocation stays until the process exits. A long-lived order book
is usually never closed. In the example's steady state Java calls no `close()` at
all, because `createLimit` returns a pool-owned order, which the facade exposes
as a view without `close()` (section 4).

The name is `close()` rather than `free()` because Java has no `free`, but it
has one universal idiom for "I own this and I am done with it":
`AutoCloseable.close()`. Try-with-resources then calls it automatically at the
end of a block, giving Java callers structured, hard-to-forget reclamation in
their own syntax:

```java
try (OrderBook book = new OrderBook("AAPL", listener)) {
    book.createLimit(1001L, "1", 1, Side.BUY, 200, 150.44, TimeInForce.DAY);
}
```

Ironwood itself deliberately keeps `close()` (release an external capability)
separate from `free` (reclaim memory) under D050 and the resources example. For
an exported class that already declares an Ironwood `close()`, the facade's
`close()` runs that Ironwood method first and frees in a `finally`, the same
nested shape the resources example recommends. That folds Ironwood's two steps
into the single lifetime a Java developer expects from `AutoCloseable`.

## 4. Analysis-driven binding

This is the part no existing binder can do, because no existing binder has a
whole-program ownership proof on the native side.

| Compiler fact already computed | Java binding consequence |
| --- | --- |
| A callable's result is **fresh and unescaped** (symbolic return summary). | Facade returns an **owned** object: `implements AutoCloseable`, `close()` runs `ironwood.destroy`. |
| A result is **borrowed** (pool-owned order, cached iterator, immortal literal, enum constant). | Facade returns a **view**: same type, no `close()`, no `Cleaner`. Views can be reused flyweights in callbacks. |
| A constructor or method **retains** an argument (D094 constructor-retained borrows, escape summaries). | Facade keeps a strong Java reference to that argument so a Java listener and its upcall stub outlive the native pointer to them. |
| A parameter is **proven not retained** by every closed-world target (D096 borrowing proof, the same one `Instant.parse` and `walkFileTree` use). | The Java argument may be passed **zero-copy** from the Java heap under `Linker.Option.critical(true)`. Otherwise it is copied into reusable per-thread native scratch. |
| No **foreign upcall** is reachable from the callable (closed-world call graph). | The downcall may be marked **critical**: cheaper, and heap access allowed. The JDK requires critical calls to be short and never call back into Java; reachability makes the second rule a compile-time fact. |
| A method has a `throws` clause. | Checked Java exceptions with the same names. |
| A getter body is a single field load (`return this.x;`). | Opt-in **cross-language inlining**: the facade reads the field at its known offset through the global segment. The JIT inlines it; the call costs about one memory load. |
| A class has a destructor chain and proven owned fields. | `close()` reclaims the whole subgraph deterministically, exactly as source `free` would. |
| A generic instantiation is **primitive-specialized** (`Box<int>`). | It appears in Java as a distinct concrete facade class named after the specialization. Reference generics stay generic on both sides. |
| An object is **immortal** (enum constant, `System.out`). | Mirrored as a Java enum constant or singleton; never destroyable. |

Everything in the left column is in the typed program the linker already
builds. The facade generator is a consumer of `IrProgram` plus the analysis
summaries, in the same position as `ClosedWorldPruner`.

### 4.1 Loans on the Java side

Ironwood tracks caller-item loans at compile time (D107): an item inserted into
a container cannot be freed until the container releases it. The Java facade
can keep the same bookkeeping at runtime, on the Java side, where it costs
nothing to Ironwood. When Java passes an owned object to a call the compiler
marked as retaining, the facade flips that Java object's state to "on loan":
`close()` throws `IllegalStateException` until the retaining object is closed or
a compiler-marked releasing call runs. Use-after-close is impossible from Java
because a closed facade has a zero handle and every method checks it. These
checks live in the caller, so they keep the "no runtime overhead in Ironwood
for caller misuse" invariant intact.

### 4.2 Host garbage collector as owner of last resort

Ironwood has no collector by decision. The JVM has one. For Java-owned handles,
an optional mode registers a `Cleaner` action that reclaims the native object
when the Java facade becomes unreachable. This is not Ironwood adopting a GC.
It is the caller's runtime discharging the caller's own `free` obligation. One
constraint: `Cleaner` runs on its own thread, and the library is thread
confined, so the action enqueues the handle into a reclamation queue drained
on the owner thread at the next call (or an explicit reclaim request on the
library object). Explicit `close()` stays the recommended path because it is
deterministic, which is the whole point of Ironwood.

## 5. Callbacks: Java implementing Ironwood interfaces

An exported `OrderBookListener` is the test case. The compiler generates, for each
exported interface or abstract class, a hidden Ironwood class
`OrderBookListener$Foreign` with two hidden fields: the upcall stub address and
a Java-side registry token. Each interface method body lowers to one new typed
IR instruction, `IrForeignCallInstruction`: an indirect C-ABI call through the
stored address. Ironwood source never sees a function pointer. The hidden class
is synthesized like the `@Test` harness, so the "no raw pointers in ordinary
source" invariant holds.

The effect analyzers treat a foreign call as unknown: it may retain every
reference argument, may throw `ForeignException`, and may allocate. That last
property means the existing destructor-effect analysis rejects foreign calls
inside destructors automatically, which is the correct rule.

On the Java side the generated adapter for `onOrderExecuted` receives `long`
handles for `orderBook` and `order`, wraps them in **flyweight views** kept per
callback parameter (no allocation per event), calls the user's Java
implementation, and catches everything. Reentrancy is allowed: a Java callback
may call back into Ironwood on the same thread, and source-level reentrancy
guards keep working.

A Java caller can pass either a native listener (a facade of an Ironwood
`OrderBookLogger`, sent as its handle) or a Java object (sent as a freshly
created foreign proxy). The generated interface has both kinds of implementors
and the facade tells them apart with one `instanceof`.

## 6. Transport options

| Transport | Java baseline | What the compiler emits | Assessment |
| --- | --- | --- | --- |
| **FFM** (primary) | JDK 22+, LTS in 25 | C-ABI trampolines; facade binds `MethodHandle`s once | Fastest supported path, roughly 20 ns per downcall in published microbenchmarks, about 15 percent less with critical. Upcalls cost more. No C code anywhere. |
| **Generated JNI** (compatibility) | JDK 8 to 21 | `JNI_OnLoad` calling `RegisterNatives`, plus per-method adapters that call `JNIEnv` table entries directly from LLVM IR | Same facade API with `native` methods. No user-written C, no mangled-name coupling. Slower, and heap access only through JNI critical regions. Worth it only if Java 21 deployments are a target. |
| **GraalVM native-image** | any | The same exported C symbols plus a generated header | A native-image Java program calls the exports through `@CFunction`. A static-archive output mode would allow one fully static binary. |
| **Runtime interface binding** | JDK 24+ | Manifest embedded in the library; a small runtime jar spins facade classes with the Class-File API against user-declared interfaces | No generated sources in the Java build, one jar plus one library. Subsumes JNA and JNR style binding, with exact compiler metadata instead of guessed signatures. |
| **Co-process over shared memory** | any | A request/response protocol derived from the same export manifest, an Ironwood-side dispatcher loop, and a Java client | Crash isolation, independent lifetimes, any client language, no unwinding or threading constraints. Needs memory-mapped buffers and memory-ordering primitives in Ironwood, which D073 names as the precondition for reconsidering `volatile`: "an explicit shared-memory native interoperability contract." Sub-microsecond hops instead of tens of nanoseconds. |

Rejected on purpose: a JVM bytecode backend for Ironwood (contradicts D002 and
gives up native code), WebAssembly through a JVM wasm runtime (portable but
slow), and Ironwood embedding a JVM (the inverse of the request).

## 7. Zero-copy and allocation-free hot paths

The audience for this feature runs pinned single-threaded event loops and
measures allocations per message. The bridge must not spend any.

- **Handles are `long`.** No `MemorySegment` per object on the hot path. One
  global unbounded segment serves every direct memory read.
- **Strings in.** For a `CharSequence` parameter proven non-retaining with no
  reachable upcall, the facade copies the characters into a per-thread `char[]`
  scratch (reused, zero allocation) and passes it under critical heap access.
  Otherwise it writes into the per-thread native scratch and passes that
  address. Either way the Ironwood side sees a borrowed `CharSequence` view of
  UTF-16 units, which is exactly what `createLimit` already accepts.
- **Strings out.** `String` results are copied into a Java `String` by
  default. An opt-in variant returns an allocation-free `CharSequence` view
  over the Ironwood string's UTF-16 tail. Fresh Ironwood results are destroyed
  after copying; borrowed ones are left alone. The compiler knows which is
  which.
- **Byte payloads.** An Ironwood-owned `byte[]` (FIX message buffers, wire
  frames) is exposed to Java as a `MemorySegment` over its element tail. Java
  writes into it, then calls the Ironwood parser. No copy, no critical call.
- **Callback parameters** are flyweight views reused per event.
- **Trivial getters** compile to memory loads (section 4).
- **Batch entries** (optional): the compiler can generate one entry that
  applies N queued operations in one crossing for callers who batch anyway.

Expected cost per crossing is the JDK's downcall overhead plus the trampoline's
ensure-routine check and one status load. Nothing in the path allocates on
either side.

## 8. Packaging and tooling

- **One jar.** `ironbind` can bundle the facade classes, the native libraries
  for macOS ARM64, Linux ARM64, and Linux x86-64 under
  `META-INF/ironwood/native/<os>-<arch>/`, and the manifest. The loader picks
  the platform library and verifies that its embedded build hash matches the
  facade, failing fast with a clear message on mismatch. This is the packaging
  convention Java developers already know from JNA and Netty.
- **API in source, ABI in manifest.** Field offsets, object sizes, and symbol
  names live in the manifest and are read into `static final` constants at
  facade class initialization, so the JIT constant-folds them. The generated
  Java source changes only when the public API changes; the library can be
  relinked at a different optimization level without touching Java.
- **Optional explicit library object.** Ordinary programs never touch one
  (section 3.1). It exists for pointing at a specific library file such as a
  debug build, for diagnostics such as live allocation counts and the opt-in
  owner-thread assertion, and for draining the deferred reclamation queue of
  section 4.2.
- **World-per-thread isolation.** Ironwood statics are process globals and the
  language is single threaded. A Java program that wants N independent engines
  on N threads loads N copies of the library under N file paths, which gives N
  isolated closed worlds with no shared state. Static facades bind one library
  copy per classloader, so the natural Java form is one classloader per engine;
  a library object with factory methods is the alternative. Either matches
  Feature 70's guidance of isolated instances instead of shared-memory threads.
- **Javadoc from IronDocs.** The compiler already keeps `/** */` comments for
  `irondoc`; the facade generator copies them onto the Java declarations.
- **Build systems.** Generated-sources conventions for Maven and Gradle; the
  IDK launcher is a normal executable a plugin can shell out to. The Eclipse
  builder can run the same link.
- **Testing.** JUnit tests against the native library become possible, and the
  compiler's own harness could call compiled fixtures in-process instead of
  spawning executables for cases that today cross process boundaries.

A speculative extra: because Ironwood source is Java shaped, the same `.iron`
files minus `free` and `destructor` could compile with `javac` against a small
compatibility jar (`ironwood.ds`, `ironwood.pool`, the numeric-field helpers)
to run on the JVM as a fallback or for test authoring. This should be treated
carefully: [`DIFFERENCES_FROM_JAVA.md`](DIFFERENCES_FROM_JAVA.md) records
real semantic differences, and `AGENTS.md` insists Ironwood is its own
language, not a Java dialect. It is mentioned because the pairing makes it
cheap, not because it is recommended now.

## 9. What has to change in Ironwood

Ordered by dependency. Each phase is independently useful.

**Phase 0, spike.** Add `--shared` to link mode: no `main` wrapper, `-shared`
in the Clang driver call, hidden visibility for the runtime object, and one
hand-written export of a public static method with primitive parameters. Call
it from a ten-line FFM program. This proves unwinding containment, lazy type
initialization from a foreign entry, and the threading assumptions before any
generator exists.

**Phase 1, exports and facade.** Trampolines for public static and instance
methods and constructors of `--export` packages; `i64` handles; the per-thread
bridge block; String marshalling through a small runtime helper that builds an
Ironwood `String` from a UTF-16 pointer and length; exception stash and trace
export; destroy entries; the embedded manifest; the facade generator as a new
compiler module emitting Java source; export roots in `ClosedWorldPruner`.

**Phase 2, ownership shaping.** Owned versus view facades from the result
summaries; retention-driven strong references; Java-side loans; enum, nested
type, and exception-hierarchy mirrors; `close()` semantics and the optional
deferred reclamation queue.

**Phase 3, callbacks.** `IrForeignCallInstruction`; synthesized
`$Foreign` proxy classes for exported interfaces; upcall adapters with
exception translation; `ironwood.lang.ForeignException`; effect-analysis rules
for foreign calls.

**Phase 4, zero-copy.** Critical eligibility from reachability plus
non-retention; scratch-backed `CharSequence` views; array segment views;
trivial-getter inlining; allocation-free callback flyweights.

**Phase 5, breadth.** Generated JNI mode; single-jar packaging with platform
libraries; Maven and Gradle conventions; IronDocs to Javadoc; the decision
record.

Documents to touch when this is accepted: a new decision in `DECISIONS.md`
(the shared-library output is already inside D001; the compiler-generated JNI
adapter is an output format, not the JNI compatibility requirement D002 and
`LANGUAGE_SPECS.md` reject), the "native FFI remains open" line in
`LANGUAGE_SPECS.md` and `LANGUAGE.md`, `COMPILER.md` for the new link mode and
IR instruction, and `IDK.md` for packaging.

## 10. Risks and constraints

- **Threads.** Ironwood is single threaded and its statics are process globals.
  The contract is thread confinement per loaded library: one owner thread at a
  time, enforced by an opt-in assertion in the facade. Runtime trace and
  emergency-exception state is already `_Thread_local`, so handing the library
  from one thread to another between calls is safe. `Cleaner` and executor
  threads must never call in directly.
- **Unwinding.** An Ironwood exception must never reach a JVM frame, and a Java
  exception must never reach an upcall stub boundary. Both are handled by the
  mandatory trampolines and adapters; there is no opt-out.
- **Critical calls.** The JDK forbids upcalls and long execution inside a
  critical downcall. The compiler decides eligibility from reachability;
  duration remains the programmer's responsibility and should default to off.
- **Fatal runtime paths.** `fatal_emergency` and `abort()` end the process, so
  an exception escaping a destructor or metadata exhaustion kills the host JVM,
  like any native library. A `--shared` runtime variant could route fatal
  errors through an installable host handler instead.
- **Two libraries in one process.** Each carries its own runtime and closed
  world. Hidden visibility prevents symbol interposition; handles must not
  cross libraries, which the per-library facade classes make a type error.
- **JDK baseline.** FFM is final from JDK 22; JDK 21 needs `--enable-preview`
  or the generated JNI mode.
- **Standard streams.** Ironwood's `System.out` uses stdio; Java's uses its
  own buffers. Interleaved output needs a flush discipline, or the facade can
  redirect Ironwood output through an exported sink.
- **Emergency allocation state** is global per library copy, and
  `IRONWOOD_ALLOCATION_LIMIT` applies to the whole process.
- **Generics.** Reference generics cross naturally (erased on both sides).
  Primitive specializations become named facade classes. Wildcard captures and
  bounded type variables project to their erasure with Java-side generics
  regenerated from the source declaration.
- **Licensing.** The native library contains reachable standard-library code
  and carries the same distribution obligations as an executable
  (`LICENSE_MECHANICS` section 6). The facade contains only signatures.

## 11. Decisions

Accepted so far:

- **Reclamation name.** Java-owned facades reclaim through `close()` and
  implement `AutoCloseable`, so try-with-resources applies. A separate `free()`
  method is not added. Section 3.5 records the semantics.

Still open for the human:

1. **Export selection.** Whole public API of listed packages (recommended, no
   syntax) versus a narrow built-in `@Export` directive later for finer control.
2. **Java baseline.** FFM only (JDK 22+), or also the generated JNI mode.
3. **Facade form.** Generated Java source (recommended: inspectable, IDE
   friendly), prebuilt classes, or runtime interface binding.
4. **Handle representation.** `long` (recommended) versus `MemorySegment`.
5. **Threading contract.** Strict confinement with optional assertion
   (recommended) versus a documented-only rule.
6. **Deferred reclamation through `Cleaner`.** Opt-in, or omitted to keep
   reclamation fully explicit.
7. **Standard-library facades.** Generate facades automatically for library
   types that appear in exported signatures (recommended, minimal closure).
8. **Naming.** `ironbind` for the tool and `--java-facade` for the flag, to sit
   beside `ironjar` and `irondoc`.

## Sources

- JEP 454, Foreign Function and Memory API: https://openjdk.org/jeps/454
- JDK-8318645, passing heap segments to native code under the critical option: https://bugs.openjdk.org/browse/JDK-8318645
- JEP 484, Class-File API: https://openjdk.org/jeps/484
- Reported FFM downcall overhead and critical-option savings: https://www.javacodegeeks.com/2026/03/project-panamas-ffm-api-in-production-replacing-jni-without-writing-c-wrappers.html
- GraalVM native-image native interoperability: https://www.graalvm.org/jdk25/reference-manual/native-image/native-code-interoperability/ffm-api/
- Public Java and C++ bridge comparisons cataloging JNI, JNA, JNR, FFM, jextract, BridJ, JNI invocation, GraalVM, callbacks, and JNI-Bind. They marshal through ordinary parameter passing and use no shared memory or ring buffers.
- Ironwood repository: [`COMPILER.md`](COMPILER.md), [`MEMORY.md`](MEMORY.md),
  [`LANGUAGE_SPECS.md`](LANGUAGE_SPECS.md), [`DECISIONS.md`](DECISIONS.md)
  (D001, D002, D045, D050, D073, D094, D096, D107, D110, D121),
  [`STDLIB_ROADMAP.md`](STDLIB_ROADMAP.md),
  [`projects/OrderBook`](../projects/OrderBook/README.md),
  `runtime/src/ironwood_runtime.c`, and
  `compiler/src/main/java/ironwood/compiler/backend/`.
