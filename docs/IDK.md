# Ironwood Development Kit


GC5 migration-branch status: normal executables use the single exact managed
heap and explicit synchronous Memory.collect(). Source reclamation and managed
destruction are removed. Historical manual-heap descriptions below are superseded
by [the GC5 contract](MEMORY.md). [GC6 managed bridges](GC6_IMPLEMENTATION.md) qualify
macOS arm64 and Linux x86-64 with separate root ABI 3 / owner ABI 2 and isolated
collector images. Full IDK/distribution and other-target qualification remain GC9
work. Ordinary executables retain root ABI 2 / owner ABI 1.
The selected GC SDK is staged at toolchain/ironwood-gc, with verified source,
licenses, root/owner ABIs and exact build identities.

The Ironwood Development Kit (IDK) is a prebuilt, platform-specific compiler
distribution. It includes the Ironwood compiler, `ironjar`, `irondoc`, its
private Java runtime, bundled standard-library source/classes, LLVM 23, Clang,
the platform linker tooling, examples, projects, and reference documentation.
You do not need to install Java or LLVM and you do not need to build Ironwood
from source.

The [Java Bridge producer](JAVA_BRIDGE_USAGE.md) additionally
supports a complete JDK 21 to 25 with compiler/Javadoc tools and JNI headers and
produces managed macOS arm64 and Linux x86-64 jars with the corresponding
pinned native toolchain/support SDK. Linux arm64 and arm64e stay refused pending
qualification. Generated consumers need supported Java and
the paired jar. Byte-view APIs also require the shared
`lib/ironwood-bridge-values.jar`, included by IDK packaging with source and
licenses inside the jar. The producer emits a matching companion automatically;
no native tools are required by Java consumers. See the producer guide for exact
support boundaries and the current byte-view qualification status.

Set `JAVA_HOME` to a complete external JDK 21, 22, 23, 24 or 25 to override the
bundled Java for bridge production. Leave it unset to use the bundled JDK. The native
toolchain remains bundled. Generated classes target Java 21 on every producer.
Java 24 and 25 consumers follow the JDK's
[native-access policy](JAVA_BRIDGE_USAGE.md#runtime-and-distribution-contracts);
Java 26 or later remains outside the bridge range. Do not combine independently rebuilt
ByteView companion jars: retain the exact companion paired with the artifact.

## Compile your first program

Extract the archive and add its `bin` directory to your shell path:

```sh
tar -xzf ironwood-idk-<version>-<platform>.tar.gz
cd ironwood-idk-<version>-<platform>
export PATH="$PWD/bin:$PATH"
```

Compile, link, and run the included basic example:

```sh
cd examples/basic
./compile.sh
./link.sh
./run.sh
```

Each script prints the command it runs and links with `-O3`. Compilation writes
`target/classes/org/ironwood/basic/Main.ironclass`, linking writes
`target/Main`, and the run script reports exit status `42`. The source is
`src/main/ironwood/org/ironwood/basic/Main.iron` and declares package
`org.ironwood.basic`.

Check the packaged compiler version, selected LLVM and Clang versions, and paths
without compiling a source file. Both commands print the same information:

```sh
ironwoodc --version
ironwoodc -v
```

The IDK selects its bundled LLVM. If no valid toolchain is available, the output
includes `LLVM not found:` and the discovery diagnostic; the version command
still exits successfully.

The IDK includes additional examples with the same scripts and layout:

```sh
cd ../controlflow
./compile.sh
./link.sh
./run.sh

cd ../objects
./compile.sh
./link.sh
./run.sh

cd ../inheritance
./compile.sh
./link.sh
./run.sh

cd ../exceptions
./compile.sh
./link.sh
./run.sh

cd ../checkedexceptions
./compile.sh
./link.sh
./run.sh

cd ../resources
./compile.sh
./link.sh
./run.sh

cd ../reclamation
./compile.sh
./link.sh
./run.sh

cd ../textreclamation
./compile.sh
./link.sh
./run.sh

cd ../echo
./compile.sh
./link.sh
./run.sh

cd ../foundations
./compile.sh
./link.sh
./run.sh

cd ../collections
./compile.sh
./link.sh
./run.sh

cd ../arguments
./compile.sh
./link.sh
./run.sh

cd ../stacktraces
./compile.sh
./link.sh
./run.sh

cd ../staticinitialization
./compile.sh
./link.sh
./run.sh

cd ../classicswitch
./compile.sh
./link.sh
./run.sh

cd ../enums
./compile.sh
./link.sh
./run.sh

cd ../multidimensionalarrays
./compile.sh
./link.sh
./run.sh

cd ../..
```

The control-flow and object examples report exit status `30`; the inheritance,
exception, checked-exception, resource, reclamation, text-reclamation,
foundations, and collections examples report `42`.
The arguments example prints its three test arguments and reports exit status
`3`. The stack-trace example intentionally produces an uncaught exception; its
run script expects status `1` and verifies the primary and secondary source
traces instead of treating the process failure as accidental. The static-
initialization example verifies one-time active-use ordering across a
superclass, a default-method interface, runtime field initializers, a static
block, and the entry method. The inheritance example combines superclass
construction, overridden calls through base and interface references, and
`instanceof`. The exception example combines cross-frame native unwinding,
superclass catch matching, and `finally` on normal and return paths. The
classic-switch example prints and verifies evaluated-once integral dispatch,
consecutive labels, and explicit fallthrough. The
enum example prints and verifies constructor-backed immortal constants,
constant-specific initialization and interface behavior, allocation-free
lookup, and ordinal enum-switch dispatch. The
multidimensional-array example verifies exact invariant array tests and casts,
then preserves shared child arrays across explicit collection. The reclamation
example allocates an object with child storage in a bounded phase, verifies that
allocation never collects, and explicitly collects after the phase returns.
Exact type layouts, membership/dispatch and exception metadata are native image
data and require no JVM, class loader or reflection runtime.


The text-reclamation example receives one UTF-16 argument, returns fresh
managed substring, character-array and builder-snapshot results, preserving
value and allocation behavior. Reclamation controls use deliberate phase boundaries.
The foundations example uses primitive/reference arrays, pooled UTF-16-tail
string literals, `String.length()`/`byteLength()`, native UTF-8 standard output, and managed
array reachability. The compiler locates the packaged standard library
relative to the IDK root; applications do not add it to `-cp` manually.
The collections example uses `ironwood.pool` and `ironwood.ds` directly through
the bundled deterministic standard-library archive.
The arguments example demonstrates the status-returning
`public static int main(String[] args)` entry point. The classic Java
`public static void main(String[] args)` form is also accepted and maps normal
completion to native status 0. Process arguments exclude
the executable name and are exposed as immutable UTF-16
`ironwood.lang.String` objects.

The packaged `projects/streaming` scripts build cat/wc/cp/prompt with the bundled
compiler and stream library. The IDK smoke test exercises piped stdin and a binary
copy containing NUL and invalid UTF-8 using the packaged executable. Standard
streams are immortal; resource close releases native handles. Managed wrappers
and buffers become reclaimable by later explicit collection. API/provenance choices ship in STDLIB_U3_SOURCE_REVIEW.md and the
standard-library archive's license metadata. The accepted networking design and
socket/address contract review ship as NETWORKING_MIGRATION_PLAN.md and
STDLIB_N1_SOURCE_REVIEW.md, with STDLIB_N1_VERIFICATION.md,
NETWORKING_M2_VERIFICATION.md, NETWORKING_M3_VERIFICATION.md,
NETWORKING_M4_VERIFICATION.md, NETWORKING_M5_VERIFICATION.md and
NETWORKING_M6_VERIFICATION.md as the evidence
records. These records and TLS.md ship loose and
in the archive's license metadata.
The archive also carries the private literal
parser and SOCKS helper sources with their Classpath-covered provenance/notices.
These records distinguish selected work from implemented APIs; networking
Milestones 1 through 6 are complete; N1's event-loop phase remains pending.
The relocated smoke test also captures and reclaims an interface enumeration,
without running live reachability.

The packaged examples use one public top-level type per file and Ironwood-specific
`src/main/ironwood` source roots. Their compile scripts pass that package root
explicitly. For your own conventional source tree, do the same:

```sh
ironwoodc --source-path src/main/ironwood src/main/ironwood/com/test/Blah.iron
```

Multiple source-path or classpath entries use the platform path separator.
`-cp` is the documented spelling; `-classpath` and `--class-path` remain
compatible aliases. Each entry may be a class directory, an individual
`.ironclass`, or a deterministic `.ironjar` archive. Compile a reusable class
directory and consume it with:

```sh
ironwoodc --source-path src/main/ironwood -d target/classes \
  src/main/ironwood/com/example/Counter.iron
ironwoodc --source-path app/src/main/ironwood -cp target/classes \
  -d app/target/classes app/src/main/ironwood/com/example/App.iron
ironwoodc --link -cp target/classes:app/target/classes \
  --main-class com.example.App -o app/target/App
```

Create and inspect a Java-style Ironwood archive with:

```sh
ironjar --create --file target/example.ironjar target/classes
ironjar --list --file target/example.ironjar
ironwoodc --link -cp target/example.ironjar:app/target/classes \
  --main-class com.example.App -o app/target/App
```

Artifacts retain format-1 embedded sources and full semantic revalidation.
Removed reclamation syntax/options diagnose migration, including embedded paths.
There is no container-format migration or manual/GC mode selector.

## JVM options

Edit `conf/jvm.options` inside the extracted IDK to configure the private JVM
used by `ironwoodc`, `ironjar`, and `irondoc`. All three launchers read the same
file relative to their own installation, regardless of the working directory or
an `IRONWOOD_HOME` pointing elsewhere. Changes apply on the next invocation. No
launcher script needs editing.

The shipped file contains only comments, preserving normal JVM defaults. For
example, uncomment `-Xmx2g` to limit the JVM heap to 2 GiB. On an affected Linux
ARM system reporting `Unable to get SVE vector length`, uncomment
`-XX:UseSVE=0` to disable SVE for these tools. Leave that workaround commented on
unaffected systems and other platforms.

Use one JVM argument per line, starting with `-`. Blank lines and lines starting
with `#` are ignored after surrounding whitespace is trimmed. LF and CRLF line
endings are accepted. Spaces within an argument are preserved, so write
`-Dname=value with spaces` without shell quotes. Use `--option=value` for options
that take a value. Inline comments, shell expansion, and `@argument-file` entries
are not supported. Options retain their file order and precede the tool's main
class or JAR and its command-line arguments. Existing Java environment options
are still handled by Java itself; this file does not clear or replace them.

A missing file uses normal defaults. An unreadable file or an entry not starting
with `-` stops the launcher with a diagnostic; other invalid JVM options are
reported by Java. The configuration affects only these development tools, not
generated native executables, LLVM, or unrelated Java installations. Review and
carry over your local settings when installing a newer IDK.

## Platforms

Official IDK archives are produced for:

- macOS ARM64;
- Linux ARM64;
- Linux x86-64.

Linux archives include their compiler/linker dependencies and the complete pinned
Java Bridge support SDK, including its source, recipes, notices and checksums.
Bridge production needs no separate support preparation or environment override.
Linux IDKs support glibc 2.17 and newer. Native programs produced by an official
Linux IDK keep the same glibc baseline. Musl-based systems are not supported.

macOS users do not need Homebrew or a separate LLVM installation, but must have
Apple's Command Line Tools installed because Apple distributes the macOS SDK
through that package:

```sh
xcode-select --install
```

Native builds use the macOS SDK and Apple linker selected by `xcrun`, while the
bundled LLVM 23 toolchain performs compilation and optimization. This avoids the
older bundled linker's inability to read SDK 27 text stubs. SDK 26.5 and 27.0 are
qualified; an older SDK selection is no longer required. Inspect the current
selection with:

```sh
xcode-select -p
xcrun --sdk macosx --show-sdk-version
xcrun --sdk macosx --show-sdk-path
xcrun --sdk macosx --find ld
```

Optional `DEVELOPER_DIR` selects another installed Apple developer environment;
`SDKROOT` selects an explicit SDK. A missing or invalid explicit selection fails
with a diagnostic rather than falling back. These are build-time tools; Java
consumers need neither the SDK nor LLVM. See the
[Java Bridge workflow](JAVA_BRIDGE_USAGE.md) for JDK 21-25 production and use.

See `docs/LANGUAGE.md` for the implemented language subset and
`docs/COMPILER.md` for compiler architecture.

## Optional TLS dependency packaging

Linux release packagers also prepare the Java Bridge support SDK once before
calling `package-idk.sh` (use `linux-x86_64` for that architecture):

```sh
"$IRONWOOD_IDK_TOOLCHAIN_HOME/bin/python" -B scripts/prepare-java-bridge-support.py \
  --setup --target linux-arm64 \
  --prefix "$IRONWOOD_IDK_TOOLCHAIN_HOME/ironwood-bridge-support"
```

The packaging command only checks/copies existing dependencies and fails if
support is missing or altered. End-users of the resulting Linux IDK do not run
this setup. The release-only workflow prepares it automatically. Its full GCC
source/runtime-exception notices and checksum manifest remain in the IDK;
`THIRD-PARTY-PACKAGES.tsv` includes the pinned bridge GCC runtime record.

M5 provides a separate native TLS adapter, selected only from retained typed
operations after closed-world pruning. IDKs include pinned OpenSSL 3.5.8 static
archives and the Mozilla-derived CA snapshot dated 2026-08-13 under
`toolchain/ironwood-tls`. Source/tool-only packages carry the same adapter,
preparation recipe and pins, using an explicit `IRONWOOD_TLS_HOME` prefix.
Plain native programs and class-only builds need no SDK or OpenSSL headers.
Invalid explicit overrides fail TLS links without falling back to another SDK.

Prepare the application SDK before packaging, separately from Conda OpenSSL:

```sh
export PATH="$IRONWOOD_IDK_TOOLCHAIN_HOME/bin:$PATH"
python scripts/prepare-tls.py --llvm-home "$IRONWOOD_IDK_TOOLCHAIN_HOME" --prefix "$IRONWOOD_IDK_TOOLCHAIN_HOME/ironwood-tls"
./scripts/package-idk.sh 0.4.2-beta
```

The toolchain environment supplies pinned LLVM, Perl, Make and Python build
prerequisites. Linux archives, adapter compilation and final linking use the
matching glibc 2.17 sysroot. macOS records its Apple SDK and deployment target
11.0. Packaging validates the SDK manifest and checksums and merges distinct
`openssl-static` and `mozilla-ca-bundle` records with Conda inventory in
`THIRD-PARTY-PACKAGES.tsv`. The SDK includes complete OpenSSL source, CA source
and generated data, upstream license texts and the build manifest.

The prepared SDK is excluded from Conda packing and copied unchanged into the
IDK. Conda relocation must not rewrite its checksummed files, including the
original build paths recorded in OpenSSL's `share/configdata.pm`. The IDK smoke
check verifies the SDK before and after the packaged launchers run relocation.

`IRONWOOD_TLS_HOME` is a build-time SDK location, not a runtime trust store.
`TlsClient` uses bundled roots or an explicit custom PEM file replacing them;
no system trust discovery, revocation checking, session resumption or early data
is exposed. Updating embedded roots requires relinking. The native application
needs no shared OpenSSL, configuration file or provider modules. See packaged
`docs/TLS.md`, D153/D158 in `docs/DECISIONS.md`, and
`docs/NETWORKING_M5_VERIFICATION.md` for contracts and evidence.

The smoke check uses a relocated path containing spaces and a local verified
TLS peer, validates SDK/TSV/provenance identity, and audits shared dependencies
and Linux GLIBC requirements. It also builds the shipped wget project through
its scripts and class/archive inputs, runs local HTTP/HTTPS peers, and audits
both downloader executables. The N1 event-loop phase remains pending.

## GC native inputs

Normal native linking requires a GC5 qualified SDK. Set IRONWOOD_GC_HOME in a
source checkout, or stage the complete verified SDK at toolchain/ironwood-gc in
a distribution. This selects inputs, not a memory mode. Missing or stale inputs
refuse before execution. The normal link verifies exact target/configuration,
LLVM 23.1.0, engine-3, root ABI 2, owner ABI 1, the required nonmoving pass and
all copied source/payload checksums. package-gc-inputs.py copies source, notices,
headers, libraries and manifests together and records production.enabled=true.
No dependency is downloaded by normal linking or offline verification.

Prepare pinned BDWGC 8.2.12 with scripts/prepare-gc.py. GC5's exact profile keeps
USE_MARK_BYTES=1. See [the current implementation report](GC5_IMPLEMENTATION.md).
Full IDK/native distribution qualification remains GC9. GC6 paired bridges
bind their separate root/owner/segment protocol and actual GC SDK payload hashes;
paired artifacts include complete BDWGC source/notices. Linux arm64 and arm64e remain unqualified.
