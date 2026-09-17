# Third-party notices

Ironwood is a mixed-license repository. See the accompanying `LICENSE` and
`LICENSE_MECHANICS` files before interpreting this notice.

## Current source status

The fixed en_US Unicode casing helper, its generated data and the Instant
calendar helper and private IP literal parser are OpenJDK-derived, as recorded
below. Other Ironwood source retains its file-specific licensing.

The pool and data-structure implementations were contributed directly by their
original author and are maintained as first-party Ironwood source under
`MIT OR Apache-2.0`. Their history is recorded in
`docs/SOURCE_PROVENANCE.md`.

The benchmark classes in `ironwood.bench`, their migrated example programs, and
adapted tests were contributed directly by their original author under
`Apache-2.0`, with permission to use neutral names and adapt the implementation.
They retain that file-specific license and the original 2015-2024 copyright
years. The complete license is included in `LICENSE-APACHE`; provenance and
native changes are recorded in `docs/SOURCE_PROVENANCE.md` and `docs/BENCH.md`.

## Packaged toolchains

Self-contained IDKs contain third-party compiler and platform toolchain
packages. Each IDK includes a generated `THIRD-PARTY-PACKAGES.tsv` recording
the package name, version, declared license, and source location. Those
components remain governed by their respective licenses and notices.

Future third-party or OpenJDK-derived source must be listed here and in the
source-provenance ledger in the same change that introduces it.

## Fixed en_US Unicode casing

`runtime/src/ironwood_case.c` and `runtime/src/ironwood_case_data.h` derive from
OpenJDK jdk21u revision `060c4f7589e7f13febd402f4dac3320f4c032b08` and use
`GPL-2.0-only WITH Classpath-exception-2.0`. Complete upstream headers, including Taligent/IBM attribution, the IBM word-rule
notice, and the exception for Ironwood modifications are retained. The underlying Unicode 15.0
notice is preserved in `LICENSES/Unicode-15.0.txt`. Packages carry these sources,
tables, the generator, and notices. See `docs/STDLIB_STRING_REVIEW.md` for scope
and reproduction. Ironwood does not provide a Locale class.

## Instant calendar conversion

`stdlib/src/main/ironwood/ironwood/time/InstantCalendar.iron` adapts the primitive
epoch-day conversion algorithms from OpenJDK
`src/java.base/share/classes/java/time/LocalDate.java` at immutable revision
`060c4f7589e7f13febd402f4dac3320f4c032b08`. It retains the complete Oracle and
original JSR-310 notices and is licensed under
`GPL-2.0-only WITH Classpath-exception-2.0`. Ironwood extends the exception to
its modifications. The helper source ships with the standard-library sources;
the complete license texts are in `LICENSES/GPL-2.0-only.txt` and
`LICENSES/Classpath-exception-2.0.txt`.

## IP literal parsing

`stdlib/src/main/ironwood/ironwood/net/IpLiteralParser.iron` derives from OpenJDK
`src/java.base/share/classes/sun/net/util/IPAddressUtil.java` at immutable revision
`060c4f7589e7f13febd402f4dac3320f4c032b08`. It retains the complete Oracle header
and uses `GPL-2.0-only WITH Classpath-exception-2.0`. Ironwood extends the exception
to its modifications. Only IPv4, IPv6 and BSD ambiguity parsing are adapted;
public networking facades, host-interface queries and reachability remain
independently implemented. Milestone 3 adds no derived source. Packages include the
helper source and `LICENSES/GPL-2.0-only.txt` and
`LICENSES/Classpath-exception-2.0.txt`.

## SOCKS negotiation

`stdlib/src/main/ironwood/ironwood/net/SocksProtocol.iron` derives from OpenJDK
`src/java.base/share/classes/java/net/SocksSocketImpl.java` at immutable revision
`060c4f7589e7f13febd402f4dac3320f4c032b08`. It retains the complete Oracle header
and uses `GPL-2.0-only WITH Classpath-exception-2.0`. Ironwood extends the exception
to its modifications. Only SOCKS4/5 wire negotiation is translated. Public Proxy
and Socket APIs, configuration ownership, native operations and HTTP CONNECT are
independently implemented. Packages include the helper source and the complete
GPL and Classpath Exception texts named above.

## Optional native TLS dependency

The prepared TLS SDK and self-contained IDK include unmodified OpenSSL 3.5.8,
copyright The OpenSSL Project Authors, under Apache License 2.0. The complete
upstream license and notices are retained at `toolchain/ironwood-tls/licenses/OpenSSL.txt`,
and the full pinned source archive at `toolchain/ironwood-tls/sources/`.
The exact source URLs, checksums and configuration are in
`packaging/tls-dependencies.properties` and the SDK's `build.properties`.
This static application dependency is separate from toolchain OpenSSL packages.

The default trust data is the Mozilla CA export published by curl on 2026-08-13,
under Mozilla Public License 2.0. The unmodified export and its notices remain
in the SDK at `share/cacert.pem`. `share/ironwood_ca_data.h` contains the same
certificate PEM blocks formatted as C string data by `scripts/prepare-tls.py`.
The complete MPL text ships in `LICENSES/MPL-2.0.txt` and the SDK's `licenses/`.
TLS applications embed this data and link selected OpenSSL code. Distributors
must retain the applicable notices/licenses and make the covered CA source and
its generated form available under the MPL; the SDK supplies both and the recipe.
Plain applications that prune all TLS operations do not include these inputs.
Source/tool-only packages carry the adapter and recipe, and require an explicit
prepared SDK for TLS links. See `docs/TLS.md` for preparation and redistribution.
