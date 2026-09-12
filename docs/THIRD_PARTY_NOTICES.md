# Third-party notices

Ironwood is a mixed-license repository. See the accompanying `LICENSE` and
`LICENSE_MECHANICS` files before interpreting this notice.

## Current source status

The fixed en_US Unicode casing helper, its generated data and the Instant
calendar helper are OpenJDK-derived, as recorded below. Other Ironwood source retains its file-specific licensing.

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
