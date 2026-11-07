<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Managed Java Bridge producer

The producer accepts only the GC6 managed model, schema 2 / objects-gc-v1.
Use the matching rebuilt BDWGC SDK and, on Linux, native support SDK. Pinned
BDWGC is 8.2.12 with USE_MARK_BYTES=1. Allocation never collects; only canonical
source Memory.collect() synchronously reclaims unreachable managed objects.
See [the memory contract](MEMORY.md).

## Build and run

Build source/class inputs normally, then export selected public packages:

```sh
./bin/ironwoodc examples/java-bridge/generics/*.iron -d examples/java-bridge/generics/target/classes
./bin/ironwoodc --java-bridge --export genericbench -O3 \
  -cp examples/java-bridge/generics/target/classes \
  --license LICENSE-MIT --license LICENSE-APACHE \
  -o examples/java-bridge/generics/target/generics.jar
```

The example's link/run scripts compile the ordinary Java consumer with its
BridgeLifetime setup utility. That utility resolves the generated image-specific
support package and delegates to the real Roots, RootHandle and
ListenerRegistration APIs. It is example setup code, not a runtime registry.
Use direct generated support types when their artifact identity is fixed.

A producer JDK supplies javac, Javadoc and JNI headers. The existing launcher
policy accepts Java 21-25 and rejects newer unknown versions. Current GC6
qualification is narrower: Java 21 JNI and recorded Java 23 critical/JNI controls
on macOS arm64 and Linux x86-64. Java 24/25 native-access policy still applies.
The [completed GC9 qualification](GC9_LINUX_X86_64_DISTRIBUTION.md) adds Linux
arm64 JNI/recorded Java 23 controls and closes the physical x86-64 distribution
gate, including a three-target assembled jar. arm64e, Windows, musl and 32-bit
targets remain unsupported. This does not expand the complete P6 JDK matrix.
The maintained classpath launch scripts pass --enable-native-access=ALL-UNNAMED
for the existing native-access policy, including Java 24/25. That JVM grant
does not anchor native objects or relax managed admission.

## Anchors, resources and registrations

Generated Roots constructors return RootHandle<T>. Reference-returning facade
methods return independent handles where the projection requires an anchor.
RootHandle.value() requires a live handle; close() is idempotent and releases
only that anchor. Aliases share native identity, while anchors have independent
lifetimes. A closed handle cannot authorize later use. A reachable native graph
may keep an object alive after one anchor closes; reacquire through a live root
rather than relying on a stale Java facade.

Use try-with-resources in Java to scope anchors. It is separate from Ironwood's
source language, which uses ordinary finally/deferred void calls for resources.
Before ending a source resource's lifecycle, call its source close operation
explicitly. Closing the Java anchor does not close its file, socket or TLS state.
Collection never calls source cleanup.

Listeners.open() acquires a ListenerRegistration before retaining callback
operations. Generated Listeners.install_* or retaining constructor operations
bind that registration to exact final native slots. Reconciliation runs after
success and failure; nested newer registrations keep their matching final
bindings, and closing an old token cannot detach a replacement. Active
retirement holds JNI globals until the outermost invocation completes.
Registration closure releases its bindings, independently of RootHandle closure
and source close. Java reachability alone does not supply native roots.
JVM reentry uses suspended exact root segments and explicit carrier/JNI completion. The image remains single-caller or externally
serialized, including transitions and collection. General concurrent or
asynchronous callback lifetimes are not admitted.

<a id="read-only-generic-facades"></a>
<a id="bounded-generic-inputs"></a>

## Values, generics and foreign views

Primitive and String values retain their implemented copy/exception behavior.
Finite read-only generics and final-bounded input/mutation facades retain their
existing Java generic signatures and runtime identity. Generic methods and
unsupported export shapes receive compile-time refusals. Ordinary managed graphs
may contain shared children, cycles and partial construction; no destructor or
exclusive-ownership proof is required to admit them.

Copied array inputs preserve staging/copy-back and failure ordering. Borrowed
ByteViews use the image-specific verified descriptor and synchronous lifetime;
they cannot be stored, returned or passed through unknown retaining effects.
Collecting calls remain legal only with the exact roots and suspension protocol.
A copied managed input is not a borrowed native view.

Exception translation roots managed String results and snapshots. Getter
receiver confinement remains required where the exception graph may contain an
invocation-owned JVM carrier. JNI references and carriers are explicitly released
on success and every failure/reentry path. Source exceptions retain D051 primary
and ordered secondary behavior.

<a id="runtime-and-distribution-contracts"></a>

## Artifacts and failure boundaries

Ordinary executables use root ABI 2 / owner ABI 1. Managed bridge images use root
ABI 3 / owner ABI 2 and suspended segment version 1. Exact metadata, native build
identities, compiler/runtime inputs and private image exports are verified before
user execution. Stale schema, old projections, altered SDK payloads and mixed
images refuse; no legacy memory model or native deallocation fallback is selected.
Source/class/archive reconstruction preserves embedded source paths and current
migration diagnostics. Companion source, provenance and notices remain required.

--java-bridge-library, --java-bridge-assemble and --java-bridge-distribution retain
their current packaging roles. Host artifacts must match the verified schema,
projection, API/compiler identities and declared target inputs. Assembly does not
qualify an unqualified platform. See [IDK packaging](IDK.md) and the focused GC6
image/metadata controls.

## Critical calls

Producer option --critical-calls=on selects additional transition-free adapters
only for proved eligible entries; the default is off. Every selected call retains
its registered JNI route. The generated loader uses JNI if a critical handle is
unavailable, and -Dironwood.bridge.calls=jni explicitly selects that fallback.
Collecting, callback-capable and other ineligible closures retain JNI and its
exact segment/owner protocol. Selection is never an exemption from GC admission,
image compatibility or the single-caller contract. See the separate GC8
[critical/JNI and callback measurements](GC8_IMPLEMENTATION.md#foreign-paths-and-actual-o3-execution)
and [GC9 target/JVM limits](GC9_LINUX_X86_64_DISTRIBUTION.md).

## Maintained checks and measurements

Use each example's compile/link/run/test scripts. The generics benchmark drivers
accept --quick. scripts/java-bridge/measure-managed.py accepts one explicit
micro, retention, orderbook or listeners workload and --quick for a bounded
migration smoke. It preserves pool reuse and allocation expectations, and scopes
explicit handles outside setup or where returned results require them. Numerical
observations are not GC8 performance/capacity acceptance.

The [GC7 ledger](GC7_DISPOSITION.md) records retired pre-GC6 projection runners,
exact provenance hashes and their replacement object/callback/image/metadata
coverage. Historical P0-P7 reports are frozen evidence; their old source syntax,
platform tables and ownership APIs are not current usage instructions.
