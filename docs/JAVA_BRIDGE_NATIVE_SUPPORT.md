<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge Linux native support

P1 uses privately delivered shared GCC support for Linux ARM64 and x86-64.
The generated library requires the C++ ABI personality and native unwinder;
the pinned Temurin JVM's own external dependency closure does not supply them.
The D202 minimal-JVM experiment demonstrates a catchable load failure without
this support. Consumers must not install compiler runtime packages or set loader
paths. This mechanism is internal until the public producer phases complete.

## Pinned inputs and provenance

`packaging/java-bridge-support.properties` pins the exact Conda-forge GCC 16.2.0
`libgcc` and `libstdcxx` packages for both architectures, the selected binary
hashes, complete GCC and zlib source archives and build recipe revision
`eb8e8cd301d4fd4392d14fc2357ed1a7265020f3` of
[`conda-forge/ctng-compilers-feedstock`](https://github.com/conda-forge/ctng-compilers-feedstock/tree/eb8e8cd301d4fd4392d14fc2357ed1a7265020f3).
Only `libgcc_s.so.1` and `libstdc++.so.6` become bridge runtime dependencies.
Other libraries in those upstream packages are not copied into the SDK.

The package metadata classifies these binaries as
`GPL-3.0-only WITH GCC-exception-3.1`. The exact GCC archive's
`libgcc/unwind-dw2.c` and `libstdc++-v3/libsupc++/eh_personality.cc` headers
expressly apply the GCC Runtime Library Exception 3.1. No implementation body
or comments from these files are translated into Ironwood source. The compiler,
adapter and preparation code remain original `MIT OR Apache-2.0` work.
The exception permits qualifying combinations with independent modules without
relicensing those modules; it does not remove the runtime libraries' own
obligations. See the [upstream license text](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html).
Ironwood uses LLVM-owned IR and introduces no non-GPL-compatible GCC IR plugin.

The preparation step preserves the unmodified runtime bytes. It includes the
full matching GCC source archive, complete package build recipes and their
patches, zlib build-input source archive, and verbatim GPLv3 and Runtime
Exception texts. The recipe's own license remains in that recipe directory.
Upstream archives retain their internal copyright notices and other applicable
licenses. GCC binary/source package pairing and source availability must remain
intact in every delivered bridge artifact. This is not a source-offer placeholder.

## Preparation and linking

Preparation uses Python 3.14's standard Zstandard support. Only explicit setup
downloads; checks and compiler linking are offline and fail closed. If the host
Python is older, use the IDK's `toolchain/bin/python` executable. Preparation
prints flushed progress messages to stderr for connections, cached downloads,
extraction, source copying and checksum verification. Downloads report received
MiB, average speed and elapsed time about every two seconds as data arrives,
with total size and percentage when the server supplies a usable Content-Length.
The connection/read timeout is 120 seconds, not a total download deadline:

```sh
python3 -B scripts/prepare-java-bridge-support.py --setup --target linux-arm64 \
  --prefix workspace/java-bridge/support/linux-arm64
python3 -B scripts/prepare-java-bridge-support.py --check --target linux-arm64 \
  --prefix workspace/java-bridge/support/linux-arm64
```

Use `linux-x86_64` for the other target. Setup refuses to overwrite an existing
prefix. Source/host producer processes set `IRONWOOD_BRIDGE_SUPPORT_HOME` to the
prepared SDK. Linux IDKs include it at `toolchain/ironwood-bridge-support` and
select it automatically; end-users need no preparation or override. IDK packaging
verifies the complete SDK before copying it and again in the staged archive,
retaining all matching source, recipes, license texts and checksum manifests.
An unset optional override is not a request to use system copies. The compiler
requires matching platform, LLVM 23.1.0, pins, binary/source hashes, complete
source/notices and the prepared glibc 2.17 sysroot.

For Linux shared linking only, the compiler disables the Clang configuration
that injects a development-prefix RPATH. It supplies the same pinned sysroot and
GCC toolchain explicitly, selects the private libraries at link time, and emits
an `$ORIGIN` path to a manifest-identified adjacent support directory. Linux
bridge images retain `-Wl,-z,now`. Linux executable linking is unchanged.
macOS uses the Apple SDK/linker selected through `xcrun` with explicit Clang
flags, retaining pinned LLVM compilation. The actual SDK/linker hashes and
versions enter native build identity; SDK 26.5 and 27.0 are qualified.
All support, source, recipes and notices are copied and verified before the
support directory is published; an existing directory is reused only after
verification. The source/support directory is part of the output, not disposable
compiler scratch space. Jar assembly must preserve it with its paired image.

## Focused qualification

`scripts/java-bridge/check-linux-dependencies.py` builds a scratch image containing
only the pinned JVM and its inventoried nongraphical system-library closure.
It installs no extra compiler runtimes. The runner audits DT_NEEDED, relative
RPATH/RUNPATH, BIND_NOW/NOW, symbol versions and transitive resolution for the
actual delivered libraries. Native requirements above glibc 2.17 fail the audit.
It runs O0/O3 ordinary and checked-JNI library calls, repeated failures,
allocation limits, disjoint images, and a deliberately missing required
relocation symbol. A load failure must remain catchable before native entry.

The required native fixture selectors, exact runner command and evidence scopes
are recorded in `scripts/java-bridge/README.md` and the progress log. Translated
x86-64 checks establish bounded functionality only. Physical x86-64 qualification
and final P6 artifact checks remain separate requirements under D213.
