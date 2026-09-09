# Ironwood Development Kit

The Ironwood Development Kit (IDK) is a prebuilt, platform-specific compiler
distribution. It includes the Ironwood compiler, `ironjar`, `irondoc`, its
private Java runtime, bundled standard-library source/classes, LLVM 23, Clang,
the platform linker tooling, examples, projects, and reference documentation.
You do not need to install Java or LLVM and you do not need to build Ironwood
from source.

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

Check the packaged compiler version without configuring LLVM or compiling a
source file:

```sh
ironwoodc --version
ironwoodc -v
```

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
then explicitly detaches and reclaims separately allocated child arrays. The
reclamation example repeatedly allocates an object with owned child storage,
observes it, explicitly frees it, verifies its destructor count, and proves with
`System.liveAllocationCount()` that each iteration returns to the same live
baseline. Type descriptors, membership/dispatch/destructor/rollback tables, and
exception landing tables are emitted into the native program; they
do not require a JVM, class loader, or reflection runtime.
The text-reclamation example receives one UTF-16 argument, returns fresh
caller-owned substring, character-array, and builder-snapshot results, frees
them and the builder explicitly, and verifies the live-allocation baseline.
The foundations example uses primitive/reference arrays, pooled UTF-16-tail
string literals, `String.length()`/`byteLength()`, native UTF-8 standard output, and explicit
array-container reclamation. The compiler locates the packaged standard library
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
streams are immortal; managed wrappers and buffers are reclaimed after explicit
resource close. API/provenance choices ship in STDLIB_U3_SOURCE_REVIEW.md and the
standard-library archive's license metadata.

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

`ironjar --license <file>` may be repeated during creation to place license or
notice metadata below `META-INF/LICENSES`. Archive entries, indexes, and
timestamps are deterministic; malformed, duplicate, nested, or unsafe archive
content is rejected. Only referenced archived classes enter the final closed
world. The IDK itself ships its bundled standard library as
`lib/ironwood-stdlib.ironjar` while retaining loose sources/classes for
inspection and bootstrap compatibility; applications do not add that archive to
`-cp` manually.

The optional standard-library testing framework ships separately as
`lib/ironwood-testing.ironjar`. Add that archive to `-cp` when compiling and
linking tests. It is not implicit in production builds. See `docs/TESTING.md`
inside the IDK for a complete class, test suite, compile, link, and run example.

The IDK also carries `LICENSE`, the MIT and Apache-2.0 texts,
`LICENSE_MECHANICS`, the GPLv2 and Classpath Exception texts, third-party
notices, the source-provenance ledger, the S0, U1, and U2 source/provenance
reviews, and the floating-point parsing review. The complete
`projects/minigrep` application is also included with its compile, link, run,
and sample-data files.
Including the latter texts prepares
the distribution for tracked derived library files and does not relicense
default-licensed source. See `LICENSE_MECHANICS` for downstream distribution
rules.

`-d` uses Java's class-output convention and performs class-only compilation,
even when a source declares `main`. Every type uses `.ironclass`; a main class is
distinguished only by entry-point metadata inside the same format. Format 1
embeds validated source so the compiler can rebuild the final closed-world
program, including destructor bodies, effect proofs, and constructor rollback.
It is a compile-time artifact, not a runtime class-loading mechanism.

Compile a main class now and link it later from the class path with:

```sh
ironwoodc --source-path examples/basic/src/main/ironwood -d examples/basic/target/classes \
  examples/basic/src/main/ironwood/org/ironwood/basic/Main.iron
ironwoodc --link -cp examples/basic/target/classes \
  --main-class org.ironwood.basic.Main -o examples/basic/target/Main
```

`--main-class` always names both the first class to load and the native entry
class, so it is required and must be present on `-cp` (default `.`).
Link mode rejects source files and `--source-path`. It creates a native launcher
at link time; it does not create a different kind of class file.

The IDK relocates its private toolchain automatically the first time it runs, so
extract it to a location writable by your user. The compiled executable is
native and does not require the IDK, Java, or LLVM when deployed. Exception
support uses the target platform's standard C++ ABI personality/unwind library
as a native system dependency; Ironwood objects and catch typing do not use C++
RTTI or C++ object semantics.

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

Linux archives include their compiler/linker dependencies.
Linux IDKs support glibc 2.17 and newer. Native programs produced by an official
Linux IDK keep the same glibc baseline. Musl-based systems are not supported.

macOS users do not need Homebrew or a separate LLVM installation, but must have
Apple's Command Line Tools installed because Apple distributes the macOS SDK
through that package:

```sh
xcode-select --install
```

See `docs/LANGUAGE.md` for the implemented language subset and
`docs/COMPILER.md` for compiler architecture.
