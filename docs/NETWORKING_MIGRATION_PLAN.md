<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood blocking TCP networking migration

Status: Design accepted on 2026-09-14. Milestone 1 complete; Milestone 2 separately
selected by the maintainer on 2026-09-14 and implemented, with evidence in
[NETWORKING_M2_VERIFICATION.md](NETWORKING_M2_VERIFICATION.md). Milestone 3 was
separately selected on 2026-09-15 and implemented, with evidence in
[NETWORKING_M3_VERIFICATION.md](NETWORKING_M3_VERIFICATION.md). Milestones 4 through 6
remain unselected. N1 remains pending.
Saved from the planning review on 2026-09-14. Implementation evidence is tracked
in [STDLIB_N1_SOURCE_REVIEW.md](STDLIB_N1_SOURCE_REVIEW.md) and
[STDLIB_N1_VERIFICATION.md](STDLIB_N1_VERIFICATION.md). Only the implemented
Milestone 1 through 3 APIs are available; proxies, TLS and the downloader remain planned.

## Summary and source findings

Deliver Java-shaped TCP clients and sequential servers, followed by a working
HTTP/HTTPS `wget`. Include the broader socket ecosystem selected during review:
explicit proxies, custom socket implementations, typed options, network
interfaces, and reachability probes. UDP, channels, selectors, and threading
remain outside this migration.

In this plan, TLS means **Transport Layer Security**, used for HTTPS. In the
runtime performance rules in [AGENTS.md](../AGENTS.md) and
[D132](DECISIONS.md#d132---stack-traces-use-on-demand-native-decoding-without-runtime-bookkeeping),
TLS denotes thread-local storage.

This is the accepted first phase of the broader N1 networking gate. N1 still
requires a later non-blocking surface and event-loop integration; completing
the six milestones below would not complete N1. The sequencing change is accepted
in [D151](DECISIONS.md#d151---stage-blocking-tcp-before-event-loop-integration),
with the corresponding [roadmap](STDLIB_ROADMAP.md#standard-library-milestone-tracker)
and [concurrency guidance](JAVA_EXCLUSIONS.md) updated to distinguish the phases.
The [acceptance checklist](#acceptance-and-documentation-updates) identifies the
remaining status, compatibility, and packaged-review updates when this design
is adopted.

Use a **hybrid implementation**: independent public facades and original native
support, with OpenJDK derivation limited to private IP literal parsing and SOCKS
protocol helpers. The source review used OpenJDK 21 at immutable revision
`060c4f7589e7f13febd402f4dac3320f4c032b08`, already referenced by Ironwood's
provenance ledger. Pin derived helpers to that revision; the public facades use
Java 21 API contracts as their behavioral target.

| Component | Implementation and rationale | Planned source license |
| --- | --- | --- |
| Public networking facades | Independently implement `Socket`, `ServerSocket`, address and interface types, exceptions, options, proxy configuration, and socket extension APIs. Use public Java contracts, including [Socket](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html) and [ServerSocket](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/ServerSocket.html), and existing Ironwood stream/file patterns. Validation and state behavior do not require translating upstream facade bodies. | `MIT OR Apache-2.0` |
| Private IP literal parser | Port the relevant [IPAddressUtil algorithms](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/net/util/IPAddressUtil.java), preserving the pinned Java default IPv4/IPv6 grammar under policy NP3 below while reducing temporary allocation. Keep the algorithm in dedicated helper files. | `GPL-2.0-only WITH Classpath-exception-2.0` |
| Private SOCKS protocol helper | Adapt SOCKS4/5 handshake and reply processing from [SocksSocketImpl](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/net/SocksSocketImpl.java). Separate it from public proxy configuration, socket lifecycle, and native resource ownership; do not port the entire upstream implementation class. | `GPL-2.0-only WITH Classpath-exception-2.0` |
| Compiler and native networking support | Write original typed IR and POSIX operations, including resolution, interface queries, and reachability, from the selected public behavior and documented platform APIs. [NioSocketImpl](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/nio/ch/NioSocketImpl.java) is a dependency-review reference, not a translation template. Its cleaners, locks, virtual-thread parking, descriptor services, and temporary direct buffers do not fit Ironwood. | `MIT OR Apache-2.0` |
| HTTP CONNECT | Independently implement the scoped tunnel protocol over Ironwood streams. OpenJDK's reflection into the larger HTTP connection stack is not reused. | `MIT OR Apache-2.0` |
| TLS adapter and HTTP downloader | Write original Ironwood/native adapter code, streaming HTTP logic, and private downloader URL/reference helpers from protocol specifications. OpenSSL and the CA bundle retain their own licenses and notices; do not port `java.net.URI`, JSSE, or the full URLConnection framework. | `MIT OR Apache-2.0` for Ironwood code; external dependencies separately licensed |

### Provenance boundary

This applies the preferred separation in
[LICENSE_MECHANICS section 5](LICENSE_MECHANICS#5-porting-architecture) and the
[porting guide](OPENJDK_PORTING.md#source-structure). No exception for derived
public facades is proposed. The existing stream/file implementations provide
the native ownership patterns; Java API contracts and new differential probes
provide the validation and state-transition requirements. This changes the
planned implementation categories, not the license of existing source. At the
planning checkpoint, no networking implementation had been written; the status
above and milestone evidence records identify the subsequently implemented work.

The planning review did inspect OpenJDK facade and native implementation bodies,
as well as the two algorithm candidates. Preserve that inspection history in
the networking source review; do not claim that those sources were never read
or that a clean-room process occurred. Write independent facade bodies from
the contract matrix and Ironwood mechanisms, without translating upstream
bodies, skeletons, distinctive internal structures, comments, Javadoc text, or
tests. Changing syntax or moving copied code behind a new class name does not
establish independence.

Keep derived algorithms in separate files behind narrow internal interfaces
with explicit buffer and ownership contracts. Do not copy their implementation
into a permissively licensed facade. Each derived file must retain the complete
verified upstream Classpath Exception header, immutable source reference,
modification notice, and derived SPDX expression. Add actual derived files to
the provenance ledger and notices when introduced, with corresponding source
in distributions. Calling them from an independent facade does not remove the
derived portions' distribution obligations.

Any later proposal to derive additional networking code must revisit this
classification and explain why an independent implementation or a separate
helper is insufficient. If a facade substantially adapts upstream code, the
whole file must receive the derived classification and required notices; never
retain a permissive header by labeling an adaptation independent. Resolve
uncertain provenance before writing the affected implementation.

## Acceptance and documentation updates

The plan is linked from `STDLIB_ROADMAP.md` and the networking decisions.
Initial acceptance on 2026-09-14 authorized only Milestone 1 implementation and
the following coordinated updates. The maintainer separately selected Milestone 2
at the exit checkpoint on the same date. Keep unimplemented APIs visibly planned. Design acceptance,
individual milestone completion, and completion of the full N1 gate are distinct.
Record implementation selection separately as well: selecting Milestone 1 does
not select Milestones 2 through 6. Each later milestone needs an explicit
maintainer selection under the [Milestone 1 checkpoint](#milestone-1-selection-checkpoint).

| Location | Required update on acceptance and subsequent delivery |
| --- | --- |
| This plan and [DECISIONS.md](DECISIONS.md#d151---stage-blocking-tcp-before-event-loop-integration), D151-D160 | Record the accepted scope/date and decision statuses, retaining a separate implementation status. Resolve any changed proposals explicitly; do not mark their implementations complete merely because the design is accepted. |
| [STDLIB_ROADMAP.md N1 row](STDLIB_ROADMAP.md#standard-library-milestone-tracker) | Record the accepted blocking TCP/DNS/proxy/TLS/downloader phase and the later non-blocking event-loop phase. Keep N1 pending until the latter's multi-client acceptance gate passes. Record progress through this plan's six milestones separately. |
| [STDLIB_ROADMAP.md item 6](STDLIB_ROADMAP.md#later-useful-library-tranches) | Replace the proposed-sequencing qualification with the accepted D151 order: blocking clients/sequential servers and the scoped HTTP/HTTPS downloader first, then a separately designed non-blocking surface. Retain the streaming/ownership prerequisites and N1's event-loop completion requirement. |
| [JAVA_EXCLUSIONS.md threads row](JAVA_EXCLUSIONS.md) | Describe the accepted first phase as planned blocking clients/sequential servers, with process-wide application stalls during blocking calls. Keep language threads excluded and multi-client event-loop support planned; claim availability only after the corresponding implementation passes its gate. |
| [LANGUAGE_SPECS.md networking line](LANGUAGE_SPECS.md#runtime-libraries-platforms-and-tooling) | Separate the accepted N1 scope from the broad future-library list and link this plan. Distinguish planned TCP/DNS, explicit proxies, scoped TLS and HTTP/HTTPS from deferred UDP and event-loop APIs. Keep unsupported entries until delivered; do not imply general cryptography, JSSE, or full NIO support. |
| [STDLIB.md current omissions](STDLIB.md#current-omissions) | Identify networking and scoped TLS as accepted planned work, still unavailable. As milestones pass, add the actual `ironwood.net`/TLS and utility API inventory and remove only delivered members from the omissions; retain the remaining exclusions. |
| [DIFFERENCES_FROM_JAVA.md](DIFFERENCES_FROM_JAVA.md) and the networking notes in [JAVA_EXCLUSIONS.md](JAVA_EXCLUSIONS.md) | Add an explicitly planned networking compatibility section: omitted boxed `SocketOptions`, unbounded primitive-specialized option methods, value-kind metadata instead of `Class<T>`, the `ironwood.ds` inventory of non-generic descriptors, and `SocketImpl` hook/checked-exception differences. Include the named proxy credential extensions, explicit reference bounds, fixed policies, and ownership conventions already decided here. Update implementation labels per milestone. |
| [scripts/build.sh](../scripts/build.sh) | Add `docs/NETWORKING_MIGRATION_PLAN.md` to the explicit standard-library `IronJarMain --license` arguments in the acceptance follow-up. Add `docs/STDLIB_N1_SOURCE_REVIEW.md` when Milestone 1 creates it. Both must become `META-INF/LICENSES/` entries in `ironwood-stdlib.ironjar`; copying loose documentation alone does not populate the archive. |
| [scripts/check-licenses.sh](../scripts/check-licenses.sh) | Register the same documents in `IRONWOOD_REQUIRED_LICENSE_FILES`, with the source review added in the same change that creates it. This list currently checks file presence/non-emptiness, not archive membership; keep the packaging checks below as a separate requirement. |

Milestone 1's initial review is named `docs/STDLIB_N1_SOURCE_REVIEW.md`. Create
it before networking source changes, with the contract/declaration matrix,
ownership and allocation budgets, per-file implementation categories, pinned
upstream references, and planned versus verified coverage. Do not register a
nonexistent review or substitute this architectural plan for the detailed source
review. Keep both documents current as implementation proceeds.

The loose-document copies and smoke-test inventories are also explicit in
[package.sh](../scripts/package.sh), [package-idk.sh](../scripts/package-idk.sh),
[test-package.sh](../scripts/test-package.sh), and
[test-idk.sh](../scripts/test-idk.sh). Add the plan during the acceptance
follow-up and the source review when created, including both loose-document and
`META-INF/LICENSES/` expectations. Update the packaged-document description in
[IDK.md](IDK.md) at the same time. These review-document updates belong to
acceptance/Milestone 1; they must not wait for Milestone 5's OpenSSL packaging.
Imported-source provenance, dependency notices, and additional license texts
still enter the ledgers only when the corresponding material is introduced.

For acceptance's documentation edits, check links, status consistency, and
`git diff --check`. When the script lists change, run the license audit and
focused archive/package checks: rebuild the standard-library archive, verify
the exact review entries and their contents, and exercise the affected package
and IDK smoke paths. No compiler full suite is implied. Verification of these
updates is recorded in the networking source review.

## Architecture and compatibility

**Keep source semantics in Ironwood.** Public classes, validation, socket state,
stream composition, option handling, and protocol parsing belong in
`ironwood.net`. A private compiler-recognized bridge lowers through typed
networking IR into isolated C runtime operations. This extends the existing
stream architecture without adding a public FFI.

**Preserve blocking socket contracts.** Support IPv4 and IPv6, hostname
resolution, explicit local binding, ephemeral ports, backlog,
connect/read/accept timeouts, partial reads, complete-or-failing writes, EOF,
urgent data, half-close, and dedicated socket-option methods. Preserve Java's
distinction between historical connection/binding state and current closure
state. Closing either socket stream closes the socket; closing a listener leaves
accepted sockets usable. Contract review covers every exposed overload and
inherited stream method against the
[Java 21 APIs](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html).

**Separate resource closure from memory reclamation.** Socket-owned stream views
are dependent borrows. `close()` releases native resources; a later proven-safe
`free` reclaims the managed graph. Outer buffering/encoding wrappers must be
reclaimed before their borrowed socket graph. Constructors, acceptance,
resolution, and proxy/TLS failures require explicit cleanup of acquired
resources. No cleaners, reference counting, descriptor registries, or permissive
ownership exemptions will be introduced. Reuse the existing
[owned-helper model](OWNED_HELPER_BORROWS.md), extending proofs only where
necessary. Non-stream results follow the per-method ownership matrix below,
recorded in accepted
[D154](DECISIONS.md#d154---define-network-result-ownership-and-connection-allocation-budgets).

**Use the agreed native API adaptations.**

- `SocketOption<T>` deliberately leaves `T` unbounded for primitive
  specialization, with typed `getOption`/`setOption`. A non-reflective value-kind
  query replaces `Class<T>`.
- `supportedOptions()` exposes a read-only `ironwood.ds` collection of
  non-generic option descriptors, avoiding heterogeneous primitive wildcards and
  boxing.
- Custom `SocketImpl` implementations receive an opaque managed descriptor and
  a concrete native implementation for delegation. Preserve subclassing,
  factory hooks, and protected acceptance support without exposing raw handles.
  Their option and ownership protocols are part of Milestone 1, as specified
  below and recorded in accepted
  [D152](DECISIONS.md#d152---establish-socket-extension-contracts-in-the-first-tcp-milestone).
- Declare the reference-only interface `ironwood.util.Enumeration<E extends Object>`
  for familiar interface enumeration; use `ironwood.ds` for collection returns.
  Preserve the declaration-level distinction in the generic-bound rules below.
- Supply `InetAddress`, IPv4/IPv6 variants, socket addresses, interface metadata,
  and the appropriate checked exception hierarchy, including timeout and
  interrupted-I/O inheritance.
- Use synchronous OS name services under the [fixed networking policies](#fixed-networking-policies)
  below, with no Ironwood DNS cache or dynamic provider discovery. Connect
  timeouts do not promise to bound OS DNS resolution.

**Keep platform differences explicit.** Target the repository's macOS ARM64,
Linux ARM64, and Linux x86-64 platforms. Handle descriptor inheritance, SIGPIPE
suppression, interrupted calls, IPv6 scopes, dual-stack behavior, and native
error mapping in the platform layer. Internal non-blocking operations and
polling may enforce deadlines while public calls remain blocking. No selectors
are exposed.

**Retain the agreed boundaries.** Explicit SOCKS4/5 and HTTP CONNECT proxies
support configured credentials, including SOCKS5 username/password and HTTP
Basic, through the named [Ironwood proxy credential extensions](#ironwood-proxy-credential-extensions)
below. Automatic proxy discovery, PAC, Java property configuration,
`Authenticator`/`PasswordAuthentication`, and the broader authentication stack
are excluded. Socket constructors that select UDP
are absent under the deprecation and capability policy below. Channels and
selectors are deferred to separate work.
Existing exclusions for serialization, dynamic loading, and thread interruption
remain compile-time-visible.

### Generic parameter bounds

Apply the existing [generic-bound distinction](DIFFERENCES_FROM_JAVA.md#generic-type-parameter-bounds)
and [native specialization rules](GENERICS.md#native-primitive-specialization),
as recorded for networking in proposed
[D160](DECISIONS.md#d160---declare-networking-reference-bounds-without-disabling-primitive-options).
An omitted bound permits primitive arguments; an explicit `extends Object`
admits references only. The bound is part of the API contract, not formatting.

- Declare `Enumeration<E extends Object>` and matching bounds on its generic
  snapshot/cursor implementations and helper methods. Reference uses such as
  `Enumeration<NetworkInterface>` and `Enumeration<InetAddress>` remain ordinary
  use-site types; `Enumeration<int>` must fail at the caller's type use.
- Audit every public and private type, method, and constructor parameter that
  represents retained objects, borrowed views, or `ironwood.ds`/pool elements.
  Use `extends Object` when only references are supported, or preserve an
  existing narrower reference bound. Carry the bound through helpers and
  class/archive reconstruction; do not wait for a primitive instantiation to
  fail inside a reference-only implementation body.
- Keep `SocketOption<T>` and every public, protected, override, and delegation
  `getOption`/`setOption` type parameter unbounded. Values such as `boolean` and
  `int` retain their native representation. An option-token object is itself a
  reference; the inventory's reference element bound does not require boxing
  the option value or adding `extends Object` to the option's `T`.
- Do not add reference bounds indiscriminately to existing `Iterator<E>` or
  `Iterable<T>`; both support primitive traversal. This migration's enumeration
  and reference-only adapters have the explicit bound, independently of those
  general-purpose interfaces. Bounds do not grant ownership or weaken D154's
  borrowed-result lifetime checks.

### Ironwood proxy credential extensions

Accepted [D159](DECISIONS.md#d159---name-explicit-proxy-credential-factories-as-ironwood-extensions)
adds two original static factories to `ironwood.net.Proxy`. They are **Ironwood
extensions**, not migrated Java members. Java's
[Proxy](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Proxy.html)
describes a route; its constructor has no credential arguments. Java obtains
credentials through the
[Authenticator callback API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Authenticator.html).
Choosing explicit configuration avoids that broader framework; it is a product
decision, not a claim that callbacks are impossible in Ironwood.

| Planned extension signature | Contract |
| --- | --- |
| `Proxy.socks5(InetSocketAddress address, byte[] username, byte[] password)` returning `Proxy` | Configure SOCKS5 with required RFC 1929 username/password negotiation. Each field contains 1-255 already-encoded octets. Do not truncate, transcode, or offer no-authentication when credentials are configured. |
| `Proxy.httpConnectBasic(InetSocketAddress address, byte[] username, byte[] password)` returning `Proxy` | Configure HTTP CONNECT with explicit Basic credentials. Inputs are ASCII-compatible encoded credential text, not Base64 or a complete header. Empty fields are allowed; reject control octets (`0x00`-`0x1f` and `0x7f`) in either field and a colon in the username, while permitting colons in the password. Encode `username:password` as unwrapped Base64 for `Proxy-Authorization` on the CONNECT request to this proxy only. |

Both factories require non-null endpoint and array arguments; null is not an
authentication-mode selector. Invalid credential fields fail with
`IllegalArgumentException` before any network activity; null arguments fail
with `NullPointerException`. Byte arrays make wire encoding an explicit caller
choice, including for non-ASCII credentials, rather than silently choosing or
replacing characters. There is no implicit charset negotiation. These contracts
follow [RFC 1929](https://www.rfc-editor.org/rfc/rfc1929.html#section-2) and
[RFC 7617](https://www.rfc-editor.org/rfc/rfc7617.html#section-2).

The result is an immutable, fresh caller-owned `Proxy` that snapshots the
endpoint and credential bytes. Callers may mutate or free their inputs after
construction. No public credential getter, setter, separate credential-holder
allocation, or global cache is needed. Credential contents must not appear in
`toString()` or diagnostics. Preserve Java's route-based `type()`, `address()`,
`equals()`, and `hashCode()` behavior; proxy equality does not mean credential
equivalence and must not be used to share authenticated connection state.

Pass the result through the familiar `Socket(Proxy)` constructor; it copies
the retained configuration into its owned graph, so the caller may reclaim the
`Proxy` independently. The private SOCKS helper borrows those bytes only during
negotiation. `TlsClient` and `projects/wget` use the same proxy representation
and snapshot rule. Count retained copies in the connection allocation ledger;
none of this adds per-read/write allocation or credential lookup.

Ordinary `Proxy` construction remains credential-free under NP10. SOCKS4's
explicit user ID stays separate from the SOCKS5 password protocol. Basic is
sent on the initial CONNECT request when explicitly configured; a 407 response
is a connection failure, with no prompting, realm cache, credential retry, or
switch to a different authentication scheme. Never send proxy credentials in
an origin `Authorization` header or inside the established tunnel. General
`Authenticator` and `PasswordAuthentication` types remain omitted, with negative
compilation coverage rather than compatibility stubs.

### Fixed networking policies

These are accepted product conventions, recorded in
[D155](DECISIONS.md#d155---fix-networking-property-conventions-explicitly), not
unspecified consequences of removing property reads. Preserve selected Java
defaults where practical; use explicit per-instance configuration for proxy
choices. A property map is possible in a native program; choosing fixed
conventions is a product decision, not a native-compilation requirement.
Omitting that map alone would not justify changing grammar or protocol support.

The inventory uses the [Java 21 networking properties](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/doc-files/net-properties.html)
and the pinned source revision above, including
[InetAddress lookup policy](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/net/InetAddress.java),
[cache policy](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/net/InetAddressCachePolicy.java),
and [proxy selection](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/net/spi/DefaultProxySelector.java).
The parser and SOCKS sources linked in the provenance table supply their
helper-specific switches and fallback behavior.

| Policy | Java setting or dependency | Accepted Ironwood convention and compatibility consequence |
| --- | --- | --- |
| NP1: address families | `java.net.preferIPv4Stack` | Fix the policy to Java's `false` default: support both families, using dual-stack transport when available and IPv4 transport when IPv6 is unavailable. Do not introduce a process-wide IPv4-only switch. Explicit addresses still select the destination or local binding; IPv6 unavailability follows the native error contract rather than silently substituting an IPv4 destination. |
| NP2: address preference | `java.net.preferIPv6Addresses` | Fix the policy to Java's `false` default: when both families are available, return IPv4 results before IPv6, preserving OS order within each family. `getByName` selects the first result. Prefer loopback `127.0.0.1` before `::1`; report the default unspecified wildcard as `0.0.0.0` when IPv4 is available, otherwise `::`. Explicit IPv6 bindings retain IPv6 reporting. Do not silently use the OS's cross-family order or IPv6-first mode. Callers can use an explicit IPv6 address or select an IPv6 result from `getAllByName`. This adds no automatic connection racing. |
| NP3: ambiguous IPv4 literals | `jdk.net.allowAmbiguousIPAddressLiterals` | Fix to the pinned `false` default. Preserve the helper's accepted decimal forms, including one to four components and decimal leading zeros. When decimal parsing fails but the input is BSD-parsable, reject it before OS resolution; do not remove the ambiguity check with the property lookup. Preserve the public facade's exception contract, including `UnknownHostException` from `InetAddress.getByName`/`getAllByName`, rather than leaking the helper's `IllegalArgumentException`. No compatibility switch enables the permissive fallback. |
| NP4: name service | `jdk.net.hosts.file`; resolver-provider discovery | Use the synchronous OS resolver, including its configured hosts database and name services. Do not read an alternate Java hosts file or load a Java resolver provider. Deployment-specific mappings belong to OS configuration or explicit address construction. OS resolution can block independently of connect timeout. |
| NP5: successful lookup cache | Security property `networkaddress.cache.ttl`; fallback `sun.net.inetaddr.ttl` | No Ironwood process-wide positive DNS cache, equivalent to zero retention at this layer. Fresh lookup calls consult the OS resolver; its caches remain outside Ironwood's control. This deliberately differs from Java's default positive caching and avoids a global retained address graph. |
| NP6: failed lookup cache | Security property `networkaddress.cache.negative.ttl`; fallback `sun.net.inetaddr.negative.ttl` | No Ironwood negative DNS cache, equivalent to zero retention at this layer. Repeated failures may repeat OS lookup work; do not promise Java's default failure-cache interval. |
| NP7: stale lookup cache | Security property `networkaddress.cache.stale.ttl`; fallback `sun.net.inetaddr.stale.ttl` | No Ironwood stale-result fallback or refresh scheduler. A failed fresh OS lookup fails even if an earlier call succeeded. OS caching is still possible. Per-address cached name getters under D154 remain object state, not a shared DNS cache or a reason to retain query results globally. |
| NP8: proxy selection | `socksProxyHost`, `socksProxyPort`, `socksNonProxyHosts`, `http.proxyHost`, `http.proxyPort`, `http.nonProxyHosts`, `https.proxyHost`, `https.proxyPort`, legacy `proxyHost`/`proxyPort`, `java.net.useSystemProxies` | Direct connections by default; use only an explicitly supplied proxy endpoint and port. Do not read Java proxy properties, desktop/PAC settings, or proxy environment variables. An explicit proxy applies even to loopback; there is no implicit non-proxy-host bypass. Applications choose direct or proxied connections explicitly. This preserves the selected explicit-proxy scope without a hidden process-wide selector. |
| NP9: SOCKS version | `socksProxyVersion`, default `5`; `SocksSocketImpl`'s legacy V4 retry | Default to SOCKS5 and provide typed per-proxy V4/V5 selection. SOCKS4 remains supported explicitly. Deliberately omit the upstream retry of a V4 handshake after a failed/non-V5 greeting: a configured V5 connection fails instead of changing protocols. This makes protocol and authentication selection predictable. SOCKS4 requires an already resolved IPv4 target; callers resolve locally, and an unresolved connect target keeps Java's `UnknownHostException` behavior. Proxy-side DNS and IPv6 use SOCKS5. No valid SOCKS4 path is replaced by a stub. |
| NP10: proxy credentials | Documented `java.net.socks.username`/`java.net.socks.password`, `Authenticator`, and the helper's `user.name` fallback | Use only explicit credentials through the Ironwood extensions `Proxy.socks5(address, username, password)` and `Proxy.httpConnectBasic(address, username, password)` defined above. Without SOCKS5 credentials, offer no-authentication only; with credentials, require username/password authentication and fail if it is not negotiated. Keep SOCKS4 user-ID configuration distinct, defaulting to an empty ID; do not infer an OS username. HTTP CONNECT Basic is sent explicitly to the configured proxy; 407 fails without callback or retry. These are deliberate differences from ambient Java authentication fallback, not removal of authenticated proxies. |
| NP11: exception enrichment | `jdk.includeInExceptions=hostInfo` | Keep optional automatic endpoint enrichment disabled. Preserve useful ordinary errors and contract-required input context, such as failed-name messages; do not claim all exception text is redacted. Follow D154's copied-message ownership independently of enrichment, and do not copy the reflective Java enrichment helper. |

NP3 is not a four-component-only parser policy. For example, `127.1` and
`2130706433` denote `127.0.0.1`, while `010.0.0.1` uses decimal 10. A form such
as `0x7f.0.0.1` must not reach a permissive native resolver after the default
Java parser rejects it. Carry the full pinned grammar, IPv6 forms/scopes, and
facade error mapping into the contract review, including consumer differences
such as `InetSocketAddress` retaining an unresolved hostname on lookup failure.

The property keys above are not additions to `System.getProperty`'s supported
native subset. Unknown/JVM-only keys still return null, and this migration adds
no `System.setProperty`, Java `conf/net.properties` reader, or runtime `-D`
configuration. `user.name` remains an ordinary supported host property, but
network authentication does not consume it implicitly. Freeze choices in
source constants or explicit proxy configuration, with no per-I/O property
lookup or retained global configuration map.

Keep the dependency boundary explicit too. `IPAddressUtil`'s
`jdk.net.url.delayParsing` switch belongs to its URL-validation machinery,
which is not part of the selected literal parser. Do not port that machinery
or accidentally use its masks as a downloader URL grammar. Java's
`jdk.net.useFastTcpLoopback` and `sun.net.useExclusiveBind` concern native paths
outside the selected POSIX platform behavior; retain native TCP option semantics
on the three target platforms. Full URLConnection/JSSE settings are not silently
inherited by the original downloader or OpenSSL adapter. Their scoped protocol,
trust, and CLI configuration must be documented in Milestones 5 and 6.

Before translating either helper or adding another dependency, audit direct
and indirect system/security property reads, `NetProperties`, startup-cached
values, and native switches. Map every applicable read to this inventory, or
record a new product decision before exposing the behavior. Record excluded
helper regions and why they are not dependencies; simply deleting an unfamiliar
property read is not a policy decision. These conventions were accepted with
D155 on 2026-09-14. Their Milestone 1 portion is complete; Milestone 2 separately
implements the remaining address/parser/resolver policies. Later portions remain unselected.

### Deprecation and capability policy

Deprecation is a reason to review an API, not an automatic inclusion or exclusion
rule. Retain a deprecated member when it serves the selected TCP compatibility
scope and its complete contract fits Ironwood. Otherwise omit the member or
provide an explicitly documented native adaptation. Apply the same behavioral
contract review to non-deprecated members.

| Java surface | Accepted treatment and reason |
| --- | --- |
| `Socket.setSocketImplFactory` and `ServerSocket.setSocketFactory` | Retain both hooks and `SocketImplFactory` for the requested process-wide implementation customization. Both hooks are deprecated since Java 17. Preserve their one-time registration and null/error behavior, without Java synchronization or security-manager machinery. Recommend explicit implementation injection for new application code. |
| Protected `Socket(SocketImpl)`, `ServerSocket(SocketImpl)`, and `implAccept(Socket)` | Retain per-instance customization and protected acceptance. They avoid global factory state and form part of the first ownership proof. |
| Boolean `stream` socket constructors | Omit the entire overloads because `false` selects UDP. A `true`-only implementation would admit valid unsupported calls. The ordinary TCP constructors cover the TCP use case; deprecation alone is not the reason for omission. |
| `SocketOptions` and its integer-ID/`Object` accessors | Omit this boxed-value protocol, irrespective of deprecation status. Replace it with the typed protocol below; do not leave boxed accessors as runtime stubs. |

The Java 21 contracts for the
[client factory hook](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html#setSocketImplFactory(java.net.SocketImplFactory)),
[listener factory hook](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/ServerSocket.html#setSocketFactory(java.net.SocketImplFactory)),
and [SocketImpl](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/SocketImpl.html)
are the compatibility references. Retaining these hooks does not add
`javax.net` factories, provider discovery, or runtime loading. No general
Ironwood policy of preserving every deprecated Java API is implied.

### SocketImpl protocol and ownership

`ironwood.net.SocketImpl` is an independently implemented native adaptation,
not a drop-in copy of Java's extension class. It does not implement
`SocketOptions`. Its sole option override protocol is
`protected <T> T getOption(SocketOption<T> option)` and
`protected <T> void setOption(SocketOption<T> option, T value)`, with unbounded
`T` so primitive specialization remains available. Do not add `extends Object`
to these hooks, their overrides/delegates, or the public facade methods when
applying the reference-only bounds elsewhere. Both hooks declare
`SocketException`, narrower than Java's `IOException`; this lets dedicated
socket-option methods preserve their checked exception contracts while using
the same hooks. Public generic facade methods may retain `throws IOException`.

All dedicated option methods route through this typed protocol, including
overrides in a custom implementation. Standard TCP option tokens carry
`boolean` or `int`; implementation-facing tokens also cover timeout and
out-of-band-inline settings absent from Java's `StandardSocketOptions`.
Preserve each facade method's Java validation and normalize disabled linger to
integer `-1` at the implementation boundary. Bound-address lookup uses an
address query, not an `Object`-valued `SO_BINDADDR` option. `supportedOptions()`
lists the public generic options actually supported for that socket role;
implementation-only tokens must not silently enlarge that public inventory.

The native bridge specializes boolean and integer option values into their
respective typed native operations. There is no intermediate `Object`, wrapper,
raw generic cast, or tagged value container. The value-kind query is descriptive
metadata, not permission to cast an arbitrary `T`. Custom implementations use
the same generic hooks and can delegate them without erasing `T`; their option
inventory describes their own supported tokens. A correctly typed but
unsupported option follows Java's `UnsupportedOperationException` contract;
a mismatched option/value type fails compilation. Preserve Java's null, range,
closed-socket, and native-failure contracts rather than using that exception to
hide missing behavior for an advertised option.

Use an ordinary concrete `NativeSocketImpl` for composition. It exposes the
operations needed by a delegating implementation as public overrides, avoiding
Java's protected-access restriction on calls through an unrelated subclass
receiver. Both the abstraction and native delegate use the TCP creation hook
`create()`, replacing `create(boolean)` and its datagram branch. Descriptor
operations remain managed and opaque.
Milestone 1 must establish these edges using ordinary source ownership proofs:

- A default socket owns its fresh native implementation. A factory-created
  implementation is adopted only when analysis proves a fresh, unpublished
  result; a cached or published result cannot receive an ownership exemption.
  Failure to prove this adoption contract requires a compile-time diagnostic,
  not a runtime ownership fallback.
  Explicitly supplied implementations are borrowed, as with existing stream
  wrappers. Closing a facade cascades to its implementation; freeing a facade
  never destroys a caller-supplied implementation.
- An implementation owns its fresh descriptor storage and stream helpers, or
  borrows a delegate that owns them. A custom wrapper may instead own a delegate
  it constructs freshly. These are distinct source-proven cases, not an
  automatic ownership transfer through a constructor parameter. Descriptor
  access returns a borrow and never exposes or duplicates raw-handle ownership.
- Socket-owned cached stream views borrow the implementation's returned
  streams. Those returned streams remain dependent on their implementation or
  delegate, and closing a facade view closes the facade. Reclaim outer wrappers
  and the facade before an externally owned implementation and its delegates.
  Custom stream getters and all reachable overrides participate in the proof.
- A registered factory is retained by static configuration for the process
  lifetime. It is not owned by any socket and cannot be freed while registered;
  captured references have the same retention consequences. This deliberate
  cost is another reason to prefer per-instance injection in examples.
- Protected acceptance borrows its destination socket and implementation.
  Transfer an accepted native resource into destination-owned descriptor
  storage exactly once. The listener never owns the accepted socket; failure
  before successful transfer must close the acquired resource.

These contracts are a Milestone 1 prerequisite and exit gate, not a claim that
the existing compiler already proves every case. If ordinary analysis cannot
prove the graph, resolve that blocker before broadening the API. Do not add
blanket non-retention assumptions, runtime ownership registries, or placeholder
extension hooks that throw until Milestone 3.

### Non-stream results and input ownership

Java's reference-returning APIs mix retained values and snapshots; Java object
identity alone does not specify an Ironwood reclamation contract. The choices
below apply to the built-in implementations. A **fresh** result is caller-owned;
a **borrow** must not be independently freed and remains dependent on the
identified owner. Null results stay null where the Java contract permits them.
`close()` does not end a borrow or reclaim metadata. Every added overload,
inherited reference-returning method, and custom override must appear in the
implementation's contract matrix with its actual return and retention effects.

| Method or result family | Accepted ownership and allocation contract |
| --- | --- |
| `ServerSocket.accept()` | Return a fresh caller-owned `Socket` with its own adopted implementation/descriptor graph under D152. The listener owns none of the result. Close and free the accepted socket independently; closing or freeing the listener leaves it usable. |
| Protected `implAccept(Socket)` | Fill a borrowed caller-supplied destination. Transfer the acquired native resource, not ownership of the destination object or its externally supplied implementation. |
| `Socket.getInetAddress()`, `Socket.getLocalAddress()`, `ServerSocket.getInetAddress()` | Return immutable address borrows cached by the receiver, with at most one materialization per required lifecycle value. Shared wildcard values may instead have process lifetime. Repeated calls in the same state allocate nothing. Preserve the distinct client and listener post-close values described below. |
| `Socket.getRemoteSocketAddress()`, `Socket.getLocalSocketAddress()`, `ServerSocket.getLocalSocketAddress()` | Return a fresh independent `InetSocketAddress` snapshot when non-null, including its own address value and retained text. It remains usable after the socket is freed. Charge each call to the result-allocation budget; do not secretly borrow the socket through the new wrapper. |
| `InetSocketAddress` constructors and `createUnresolved`; `InetAddress.getByAddress` and `Inet6Address.getByAddress` | Produce a fresh owned value graph. Copy retained address bytes, hostname text, and scope-interface metadata; caller arguments remain caller-owned and can be reclaimed after the call. No constructor silently adopts an argument. |
| `InetAddress.getByName()`, `getLocalHost()`, `getLoopbackAddress()` | Return a fresh independent address, including owned retained name/scope data. Even loopback results have this fresh-result contract. No process-wide resolver cache or caller-hostname loan is introduced. |
| `InetAddress.getAllByName()` | Return a fresh caller-owned array and distinct fresh caller-owned address elements, including for null/empty-host loopback resolution. The array does not own its elements: callers detach and free each owned element, then shallow-free the array. Partial construction must reclaim all completed elements and the array. |
| `InetSocketAddress.getAddress()`; `Inet6Address.getScopedInterface()` | Return a borrow of the receiver's owned address or captured interface snapshot, respectively. A fresh enclosing result does not make its children independently freeable. |
| `InetAddress.getAddress()`; `NetworkInterface.getHardwareAddress()` | Return fresh caller-owned byte-array snapshots, or the permitted null hardware-address result. They never expose private arrays and remain usable after their source is freed. |
| `InetAddress.getHostName()`, `getCanonicalHostName()`; `InetSocketAddress.getHostName()`, `getHostString()` | Return borrowed retained text owned by the address graph. Copy constructor-supplied names; materialize/cache a missing name on first request according to Java's lookup behavior. `getHostString()` must not perform reverse DNS. Per-object name retention is separate from the excluded global DNS cache. |
| `InetAddress.getHostAddress()` and networking `toString()` methods | Return fresh caller-owned rendered Strings, including empty/unchanged cases where applicable. Use the established rendering-result cleanup rules for ordinary Object consumers. Rendering is outside allocation-free numeric metadata access. |
| `Socket.supportedOptions()`, `ServerSocket.supportedOptions()`, `SocketImpl.supportedOptions()` | Return a borrowed read-only `ironwood.ds` inventory retained by the implementation, or a shared process-lifetime inventory for a built-in role/family/capability profile. Cache construction occurs once, not per getter call. Its view, backing list, iterator, and option tokens retain their distinct owners; read-only access implies no ownership transfer. |
| Option descriptors and their names | Standard tokens have process lifetime; custom tokens retain their actual implementation or caller owner. Inventory element access borrows them. Name getters borrow token-owned text. Primitive option values create no object result; any custom reference-valued option must declare its own result/retention contract. Fluent facade `setOption` returns a receiver alias. |
| `NetworkInterface.getByName()`, `getByIndex()`, `getByInetAddress()` | Return a fresh owning query result, or the specified null result. Copy query inputs and capture the structural interface snapshot needed by its views; it must not retain the caller's name or address object. |
| `NetworkInterface.getNetworkInterfaces()` | Return a fresh enumeration that owns the captured interface snapshot. `nextElement()` returns borrowed interface views tied to that snapshot. Consuming the enumeration does not transfer its elements; free it only after all views and dependent cursors are finished. |
| `NetworkInterface.getParent()` | Return a borrowed interface view from the same captured snapshot, or null. Parent/child relationships do not create recursive ownership cycles. |
| `NetworkInterface.getInetAddresses()`, `getSubInterfaces()` | Return fresh independent cursors borrowing the interface snapshot; `nextElement()` borrows an address or interface view. Free each cursor before its snapshot owner. Two simultaneous enumerations have independent positions. |
| `NetworkInterface.getInterfaceAddresses()` | Return a fresh mutable `ironwood.ds.ArrayList<InterfaceAddress>` containing borrowed snapshot entries. Free the list before its snapshot owner; list destruction does not destroy the entries. Caller-added elements keep ordinary `ironwood.ds` borrowing rules. |
| `NetworkInterface.getName()`, `getDisplayName()`; `InterfaceAddress.getAddress()`, `getBroadcast()` | Return borrowed text/address metadata from the owning snapshot, with the specified null cases. The result never transfers ownership of a child. |
| `Proxy.address()` | Return a borrow of the proxy's owned endpoint snapshot; proxy construction copies the accepted endpoint value instead of adopting or retaining the caller's endpoint graph. |
| `Proxy.socks5(...)`, `Proxy.httpConnectBasic(...)` | Ironwood extensions returning a fresh caller-owned proxy with copied endpoint and credential storage. Do not retain input arrays or expose credential borrows; reclaim owned copies with the proxy graph, including partial-construction cleanup. |
| `Socket(Proxy)` and explicit proxy configuration in `TlsClient` | Copy retained endpoint, protocol, and credential values into the connection's owned graph. The original proxy remains caller-owned and may be reclaimed after the call. The private negotiation helper borrows connection-owned storage without retaining it globally. |

Keep primitive endpoint address/port/scope state in the transport so accepting
a numeric connection need not materialize address objects, names, or formatted
Strings. Lazily materialized address borrows remain valid until socket
reclamation. A client returns its connected peer after close, but its local
address getter returns a wildcard when closed or unbound. Its closed local
endpoint snapshot contains the wildcard and the previous local port. A bound
listener retains its bound address/endpoint after close. Do not mutate or free
an earlier borrowed value to implement these transitions; keep the bound
snapshot alive and select the appropriate value for each getter.

Built-in `bind`/`connect` copy retained endpoint values and labels, so a caller
can release its input graph after the call. Add the explicitly named Ironwood
helper `InetAddress.copy()` to obtain a fresh independent copy of a borrowed
address without an intermediate byte array or loss of hostname/scope data.
Endpoint construction likewise copies its input address. Identity across copies
is not preserved; Java address value equality, hashing, and observable address
data remain the compatibility target.

Interface snapshots own structural metadata and private view storage; represent
parent/subinterface relations using flat snapshot records and borrowed views,
not mutually owning `NetworkInterface` objects. Never expose the hidden storage
owner through a view. Lookup roots and enumeration roots must both support safe
cleanup after traversal. Preserve Java's live native queries where required;
captured structure is not permission to freeze every interface property. Release
OS enumeration/resolver storage after copying results, including on failure.
Do not apply a recursive-free rule to ordinary arrays or collections, immortalize
query results, or add reference counting to make this graph work. The bulk
array cleanup and cyclic navigation proofs are explicit Milestone 1 risks,
tested with synthetic records before DNS and host networking are implemented.
Those probes must include independently retained elements, early termination,
and mutation of a returned array/list. Replacing a slot with a borrowed or
duplicate value cannot give that value permission to be freed; analyze the
actual aliases rather than assigning permanent ownership to array positions.

### Exception message ownership

Apply U3's
[FileNotFoundException pattern](../stdlib/src/main/ironwood/ironwood/io/FileNotFoundException.iron)
to networking exceptions: each supplied or generated diagnostic message is
copied into exception-owned text. A base `Throwable(String)` call would borrow
the argument and is insufficient. Preserve the Java exception hierarchy,
including `SocketTimeoutException` through `InterruptedIOException`; reuse the
copying behavior through inheritance or a private helper without changing that
hierarchy. `getMessage()` and the default localized getter borrow the owned
message; `toString()` follows the existing fresh-description contract.
Generic `IOException` failures emitted by networking need the same owned-text
behavior, through an internal copying subtype or equivalent owned construction.

Failed resolution, bind, connect, accept, option access, proxy negotiation, and
TLS operations must not keep caller hostnames, endpoints, temporary formatting
buffers, or native error-string storage alive through their messages. Release
temporary messages after copying, and audit failure during message/exception
construction for managed rollback and native-resource cleanup. Explicit causes
and secondary exceptions keep their existing borrowing rules; copying message
text does not adopt those exception objects. Count message copies, exception
objects, and native trace storage separately from successful-connection costs.

### Reachability contract and verification

Retain both `InetAddress.isReachable` overloads in Milestone 3, with live probes
classified as opt-in host smoke checks under accepted
[D156](DECISIONS.md#d156---separate-reachability-contract-tests-from-host-smoke-checks).
Preserve the [Java best-effort contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetAddress.html#isReachable(java.net.NetworkInterface,int,int)):
interface selection, IPv4/IPv6 scope, TTL and timeout validation, and ordinary
failure behavior remain supported. ICMP may be unavailable without privileges;
the TCP port-7 fallback allows an unprivileged attempt, not guaranteed success.

The pinned OpenJDK [IPv4](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/unix/native/libnet/Inet4AddressImpl.c)
and [IPv6](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/unix/native/libnet/Inet6AddressImpl.c)
implementations count both a successful port-7 connection and `ECONNREFUSED`
as reachable, including refusal reported through connect completion. Preserve
that behavior in the independent native implementation. A positive result does
not establish that the echo service or an application service is listening;
a negative result does not establish that the host or its TCP services are down.
Clients and `wget` must connect to their actual destination without using this
probe as a prerequisite.

**Deterministic Milestone 3 gate:** Use controlled native fixtures at the syscall
boundary to cover ICMP permission/unavailability fallback, matching and rejected
echo replies, immediate and asynchronous TCP refusal as `true`, successful
connect, timeout and error mapping, interface/family/TTL handling, EINTR, and
descriptor/buffer cleanup on every exit. Check public argument contracts,
including null interface and zero/default values. Reuse the first milestone's
test-only native fault-injection approach, with controlled clock/wait results;
do not add production dispatch hooks or per-operation instrumentation for tests.
These tests can run in the normal focused macOS and Linux VM/Rosetta checks
without raw-socket privileges, external hosts, or a real echo daemon.

**Live host smoke only:** Run separately and explicitly against loopback or an
operator-selected controlled host. Record OS/architecture, VM/container or
translation context, relevant privilege/network configuration, target family,
interface/TTL, timeout, and observed boolean/error. Record which mechanism was
actually exercised when evidence is available; `true` alone does not prove
ICMP worked. Do not assert that a public address must respond, a closed port
must mean unreachable, or a chosen address must time out. Do not change host
firewalls, sysctls, container capabilities, or user privileges to make the check
pass, or require a port-7 listener. Keep these probes out of default compiler,
platform, package/IDK smoke, and hosted release pass/fail gates.

Record unavailable or inconclusive live coverage separately from deterministic
passes, including the reason; do not label an unexercised ICMP path verified.
Crashes, hangs beyond a generous harness limit, leaks, and demonstrated contract
violations remain failures to investigate, not environmental skips. Milestone 3
still requires the complete implementation and deterministic gate; a successful
live probe cannot replace them, and unavailable live ICMP does not block the
TCP-client/server or downloader milestones. This changes verification, not API
scope or provenance.

### Relationship to N1 and future event-loop networking

The roadmap previously made an event-loop design a prerequisite for sockets,
DNS, and HTTP. This plan proposes changing that order while retaining
event-loop integration as an N1 completion requirement. Blocking sockets first
provide useful clients, sequential server examples, and a native transport
foundation. A blocked accept, read, write, or connection attempt stops other
application work in the same process. A process can hold multiple connections,
but this API does not provide readiness-based multiplexing between them.

An internal wait for the current operation's timeout is not an application
event loop. Keep the native boundary reusable by separating individual I/O
attempts from readiness waits. On non-blocking descriptors, attempts report
partial progress, would-block, pending connection, EOF, or a native error as
distinct results. The blocking facade handles retries and complete writes,
waiting only when necessary and respecting its deadline. An untimed operation
must retain the direct blocking syscall path under the budget below; it cannot
inherit polling overhead from an earlier timed operation.

A later non-blocking surface can use those same I/O operations without the
blocking retry loop, and share readiness-event and error mapping with a
multi-descriptor wait backend. Readiness remains advisory: a retry may still
report would-block. This migration adds no selector registrations, scheduler,
callbacks, or public non-blocking methods, and does not freeze a future
selector API or backend. Reusing the TCP primitives will not make this
migration's synchronous DNS resolver, proxy negotiation, or TLS client non-blocking;
event-loop integration must address those boundaries separately.

N1's later acceptance program must show progress on other connections while
one peer stalls, partial-write backpressure, and safe connection cleanup.
Timeout-driven sequential examples and the internal wait mechanism cannot
substitute for that application gate.

### Untimed TCP I/O budget

Apply D132 and D133's prohibition on avoidable steady-state helper work to the
built-in TCP path. On an established socket with read timeout zero, an ordinary
positive-length scalar or bulk read performs **one receive call** when not
interrupted. Return a positive short read immediately; do not loop to fill the
requested length. EINTR permits a necessary receive retry. A valid zero-length
bulk read performs no receive call; locally known EOF or input shutdown needs
no receive either. These counts concern the socket stream itself; composed
operations such as `readFully` may require several reads.

The untimed path performs **no readiness wait, clock read, availability probe,
descriptor-flag query/toggle, or socket-option query/update** around that
receive. This includes clock calls served without a syscall, such as Linux
vDSO clocks. Select the timed or untimed path before constructing a deadline or
entering a generic wait helper. Keep required local state/argument validation
and the thin native receive boundary; outline deadline and error work.

Connected descriptors must support direct blocking I/O even after a timed
connect, timed read, or timeout reset. Choose the deadline mechanism accordingly:
per-call non-blocking attempts must not permanently change the descriptor's
blocking mode. Any necessary persistent mode changes belong to connection
setup/completion or explicit configuration transitions, not each read/write.
A positive read timeout must not force the untimed write path to poll.
Accepted-descriptor setup is counted separately in the connection ledger.

Untimed writes likewise issue only the sends needed to complete the bytes,
without readiness, clock, or descriptor-mode work. One full successful send is
the ordinary case; short writes and EINTR require correct progress-preserving
retries. This adds no public write timeout and makes no one-syscall promise for
TLS record processing or user overrides that perform additional work. Future
non-blocking support must preserve the blocking facade's budget.

## Milestones and implementation order

The table describes the overall migration architecture and dependency order.
It is not a single implementation assignment. **Milestones 2 through 6 each
require separate maintainer selection**, starting with the review at the
[Milestone 1 exit gate](#milestone-1-selection-checkpoint). Accepting this plan
or completing an earlier milestone does not select the next one. Unselected
milestones retain their planned scope and compatibility requirements.

| Milestone | Architectural outcome and acceptance gate |
| --- | --- |
| **1. Representative TCP foundation** | Establish the real `SocketImpl` delegation, factory, option, and ownership protocols, including non-stream result lifetimes and a complete per-connection allocation ledger. Prove them alongside native errors, deadlines, and failure cleanup through a small complete API slice. Detailed below. |
| **2. Complete blocking socket and address APIs** | Extend the established facade and implementation protocols with remaining constructors, binding, connection, acceptance, state queries, options and discovery, urgent data, shutdown, exceptions, IPv4/IPv6 parsing, scoped addresses, and DNS. Deliver resolver-result ownership and cleanup under the matrix above. Interoperate with Java peers and existing Ironwood stream wrappers. |
| **3. Host networking** | Add `NetworkInterface`, `InterfaceAddress`, and both reachability overloads, with owned query snapshots and borrowed traversal results. Independently implement best-effort IPv4/IPv6 ICMP with TCP port-7 fallback, including interface/TTL handling. Gate reachability on deterministic contract/native fixtures; live probes are separate opt-in [host smoke checks](#reachability-contract-and-verification), with no privilege-dependent boolean acceptance gate. No earlier milestone depends on extension machinery first delivered here. |
| **4. Explicit proxy connections** | Deliver SOCKS4/5 and HTTP CONNECT, the named `Proxy.socks5`/`Proxy.httpConnectBasic` credential extensions, proxy-side DNS where applicable, endpoint reporting, and deadlines spanning negotiation. Verify factory validation, owned configuration copies, authentication scope, and local scripted proxy peers, including fragmented and malformed replies, informational response heads, and the successful CONNECT tunnel boundary described below. |
| **5. TLS client and distribution support** | Add reusable `ironwood.net.tls.TlsClient` with streams, deadlines, deterministic close, and explicit proxy configuration. Use OpenSSL 3.5 LTS, TLS 1.2/1.3, SNI, certificate-chain and hostname/IP verification, and a pinned bundled CA set with custom-CA replacement. Exclude revocation checking, system trust discovery, and session resumption under the [TLS scope below](#tls-client-scope-and-exclusions). Deliver the separately compiled adapter, selection from post-pruning typed operations, pinned static dependency builds, source-tree discovery, and package provenance described below. Acceptance includes both working TLS and plain links without a TLS SDK. |
| **6. HTTP/HTTPS wget and completion** | Deliver `projects/wget` with private URL parsing/reference resolution, the redirect and HTTP-response policies below, streamed file/stdout output, and reliable failure reporting. Finish project documentation/scripts and focused socket examples, then validate TLS and plain TCP from relocated packages on all three platforms, including Linux glibc 2.17 audits of TLS and downloader executables. |

Milestone 6 completes this accepted blocking migration only. Record its result
separately from the still-pending event-loop portion of N1.

TLS remains an optional native build dependency, with the mechanism below
accepted in [D153](DECISIONS.md#d153---select-the-native-tls-dependency-after-closed-world-pruning).
Embed default CA data only in TLS-using executables. Plain TCP programs must
need neither OpenSSL headers for runtime compilation nor OpenSSL libraries at
native link time. OpenSSL 3.5 is supported through April 2030; its patch version
and the CA snapshot must be maintained through releases. The Mozilla-derived
CA bundle carries MPL 2.0 notices.
[OpenSSL support policy](https://openssl-library.org/policies/releasestrat/),
[CA bundle provenance](https://curl.se/docs/caextract.html).

### TLS client scope and exclusions

Accepted [D158](DECISIONS.md#d158---bound-tls-trust-revocation-and-session-behavior)
applies to both `TlsClient` and HTTPS in `projects/wget`. Certificate-chain,
validity-time, server-purpose, and hostname/IP verification remain mandatory.
The first migration deliberately excludes the following capabilities; neither
the reusable client nor the downloader exposes switches that claim to enable
them or bypass certificate verification.

- **Revocation checking:** No CRL checking, online OCSP, or validation of stapled
  OCSP responses. Do not fetch revocation data or introduce background refresh
  or a revocation cache. Custom CA input supplies trust anchors, not revocation
  policy. A successful handshake does not establish revocation status: an
  otherwise valid certificate can be accepted after it has been revoked.
  Bundled-root updates do not substitute for leaf/intermediate revocation checks.
- **System trust stores:** Trust only the pinned bundled CA set, or an explicitly
  supplied custom CA bundle that replaces it for that client. Do not merge
  custom and bundled anchors implicitly. An unreadable, invalid, or empty custom
  bundle fails configuration without falling back to bundled/system roots.
  Omit macOS Keychain, Linux trust-directory, JVM trust-store, and OpenSSL default
  trust discovery. Do not let `SSL_CERT_FILE`, `SSL_CERT_DIR`, or ambient OpenSSL
  configuration change trust. Private/system-installed roots require an explicit
  custom bundle; callers needing both root sets supply their own combined bundle.
  No automatic trust-store refresh is provided.
- **Session resumption:** No session-ID or ticket reuse across connections, no
  session cache/import/export API, and no TLS 1.3 early data (0-RTT). Each new
  connection performs a full authenticated handshake, including redirect hops
  to the same host. This deliberately retains the handshake cost. OpenSSL may
  process tickets sent by a TLS 1.3 peer during a live connection; do not retain
  them beyond connection cleanup or offer them on later connections.

Enforce these conventions in adapter configuration and ownership. Load only
the selected explicit anchors, without calling OpenSSL default-path loaders;
its defaults can consult environment-selected locations.
[OpenSSL trust loading](https://docs.openssl.org/3.5/man3/SSL_CTX_load_verify_locations/).
Leave CRL checking disabled and add no OCSP validation path; ordinary certificate
validation still applies.
[OpenSSL verification options](https://docs.openssl.org/3.5/man1/openssl-verification-options/).
Use a fresh native `SSL` object per connection, `SSL_SESS_CACHE_OFF`, no external
session-cache callbacks, and no saved-session installation or early-data writes.
Do not treat `SSL_OP_NO_TICKET` alone as proof that TLS 1.3 cannot resume.
[OpenSSL session caching](https://docs.openssl.org/3.5/man3/SSL_CTX_set_session_cache_mode/),
[ticket option semantics](https://docs.openssl.org/3.5/man3/SSL_CTX_set_options/).
These are explicit Milestone 5/6 boundaries, not claims of complete JSSE support.
Any later addition needs its own API, ownership, trust policy, and verification
decision rather than a silent change of defaults.

### Downloader application and protocol contract

Under accepted [D157](DECISIONS.md#d157---scope-the-downloader-as-a-project-with-private-url-and-http-policies),
Milestone 6 creates the application at `projects/wget/`, with source under
`src/main/ironwood/org/ironwood/wget/`, a README, compile/link/run scripts, a
focused test script, and ignored `target/` output. Follow the existing project
workflow: separate class compilation and native linking, preserve the caller's
directory, reserve stdout for downloaded bytes, and send diagnostics to stderr.
Small TCP demonstrations belong in `examples/`; the downloader is a complete
application. Project sources and scripts await separate Milestone 6 selection.
The application uses HTTP/1.1 GET, configurable connect/response-head/body-read
timeouts, and streamed binary output to a caller-selected file or stdout.

**Private URL boundary.** Implement URL parsing and relative-reference
resolution as private project helpers with an owned URL value. Do not add a
public `ironwood.net.URI`, `URL`, or URLConnection framework solely for this
application, or translate the OpenJDK URI implementation. The helper targets
[RFC 3986 reference resolution](https://www.rfc-editor.org/rfc/rfc3986.html#section-5.2)
and HTTP(S) authorities, not Java URI API compatibility or browser URL recovery.
Accept an absolute HTTP/HTTPS initial URL and resolve redirect URI-references
against the current effective URL, including absolute and network-path
references, absolute/relative paths, dot segments, query-only, fragment-only,
and empty references. Preserve absent versus empty queries and percent-encoded
delimiters; do not percent-decode path segments before reference resolution.

Use ASCII URI text, with percent-encoded path/query bytes and ASCII DNS labels
(including supplied IDNA A-labels), IPv4, or bracketed IPv6 hosts. Automatic
IRI/IDNA conversion is outside this private CLI grammar. Reject userinfo,
malformed escapes, raw spaces/control characters, invalid authorities/ports,
and unsupported schemes before name lookup or connection. Keep literal-address
interpretation under NP3 rather than inventing another IPv4 grammar. Preserve
fragments for reference resolution and
[HTTP redirect inheritance](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2),
but never send them in the request target. Send `/` for an empty request path,
append the query only when present, and construct `Host` from the current
authority with correct IPv6 brackets and port handling.

**Redirect policy.** Follow 301, 302, 303, 307, and 308 with a maximum of 20
redirect hops; all requests remain GET. Require exactly one valid `Location`
field, distinguishing an empty reference from a missing field. Resolve and
validate every next target before connecting. Allow HTTP-to-HTTPS and redirects
within either scheme, including a change of host; reject HTTPS-to-HTTP even
after an earlier upgrade. The first CLI has no downgrade override. Recompute
the authority and TLS hostname/IP verification for each hop. Proxy credentials
remain scoped to the configured proxy and never become origin credentials.
Do not follow other status codes as redirects or treat 304 as a successful
download without a cached representation.

Each hop uses a fresh connection and requests `Connection: close`; there is no
connection pool or cookie/authentication cache. Close the preceding connection
without copying its redirect body to output. Resolve/copy the next URL into
independent owned storage before releasing the prior URL and header buffers;
no URL result may borrow from a reused response buffer. Only the final
successful response contributes output bytes. A later transfer/output/cleanup
failure produces nonzero status; streamed partial output is not transactional.

**Response sequence and framing.** Apply the ordered rules in
[RFC 9112 section 6.3](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.3)
and the [informational-response contract](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.2).
Parse header names and coding tokens case-insensitively; keep response-head
processing separate from body decoding. The following are explicit application
policies, not inferred Java HTTP property defaults:

| Response case | Required behavior |
| --- | --- |
| Informational 100-199, except 101 | Consume the complete header block and continue to the next response head, including unexpected 100, 102, 103, and unknown informational codes. These responses have no body or trailers and do not trigger output, redirects, or success. Allow at most 16 interim blocks per request and retain one response-head deadline across them. EOF before a final response is an incomplete response. |
| 101 Switching Protocols | Fail explicitly and close; the client neither requests nor supports protocol upgrades. Do not wait for another HTTP response or interpret upgraded bytes as a download. |
| Final no-body status | Apply status/method semantics before length framing. A 204 has no message body; a 304 has no body and is not download success. Length/coding fields cannot create a body for these statuses. Validate prohibited fields separately; informational responses and 204 cannot carry `Content-Length` or `Transfer-Encoding`, while valid 304 metadata does not cause body reads. |
| Body-bearing response with both `Transfer-Encoding` and `Content-Length` | Reject as a protocol error and close before output. Transfer-Encoding has framing precedence; choosing rejection does not permit a fallback to Content-Length, header-order-dependent behavior, or trying both interpretations. |
| Transfer-Encoding without Content-Length | Support exactly a single `chunked` coding. Stream decoded chunk data, validate chunk sizes and delimiters, consume the terminating zero chunk and bounded trailers, and ignore well-formed unknown chunk extensions. Reject unsupported coding chains, repeated/non-final chunked, or invalid coding syntax explicitly; never treat an unsupported coding as identity or use Content-Length instead. Trailers do not override framing, redirect, or authentication decisions. |
| No Transfer-Encoding, valid Content-Length | Read exactly that many octets, including zero. Accept repeated or comma-list lengths only when every valid decimal value is identical; reject conflicting, malformed, or overflowing values. Premature EOF or timeout is failure. |
| Neither length field | Read a body-bearing response until transport EOF. A timeout, reset, or TLS truncation error is not successful EOF. Plain TCP close-delimited bodies cannot prove that the sender supplied its complete intended representation; document this protocol limitation. |

Use overflow-safe counters and bounded metadata: 64 KiB of aggregate
status/header bytes across interim and final heads per request, a separate
64 KiB trailer budget, and 8 KiB per chunk-size/extension line. Reject exceeding
these fixed limits explicitly. Stream payload through reusable buffers without
allocations proportional to body size or chunk count. Request
`Accept-Encoding: identity` and reject unsupported content encodings explicitly;
Content-Encoding is separate from transfer framing.

Milestone 4's HTTP CONNECT implementation also consumes bounded informational
heads and rejects 101. Its final 2xx ends HTTP parsing at the header terminator:
ignore Content-Length/Transfer-Encoding there, and preserve any already-buffered
following bytes for the tunnel. Do not run the downloader's body decoder over
a successful CONNECT response or require a Milestone 6 URL helper for a proxy
endpoint already represented by socket-address types.

Recursive mirroring, cookies, resume, HTTP/2, and a general public HTTP/URI
framework remain outside this application milestone. The chosen URL and HTTP
boundaries must be visible in the project's CLI documentation and diagnostics.

### Optional TLS build and packaging mechanism

**Current integration points.**
[Main](../compiler/src/main/java/ironwood/compiler/Main.java) prunes the typed
program before LLVM emission, but passes only paths and optimization level to
[NativeBackend](../compiler/src/main/java/ironwood/compiler/backend/NativeBackend.java).
The backend now prepares `ironwood_runtime.c`, `ironwood_case.c` and the isolated
Milestone 1 `ironwood_tcp.c` component, and
uses a fixed native link command. Linker dead stripping alone cannot prevent
OpenSSL header requirements if TLS code is added to those translation units.
The [IDK environment](../packaging/idk-environment.yml) has no explicit
application TLS dependency, and
[package-idk.sh](../scripts/package-idk.sh) generates its dependency TSV solely
from Conda metadata. A transitive toolchain OpenSSL package is not evidence of
suitable static libraries or the required Linux baseline.

**Selection from typed operations.** In Milestone 5, derive a small immutable
native-link requirements value from the specialized program returned by
`ClosedWorldPruner.prune`, and pass it explicitly to native preparation and
linking. Inspect retained typed operations, including invoke terminators,
reachable initializers, cleanup paths, and dispatch targets. A retained TLS
operation selects the TLS adapter and its dependency inputs. Importing a TLS
type or including an archive with pruned TLS methods does not select them.
Do not infer dependencies from package names, LLVM text, or flags saved during
class-only compilation. Recompute requirements at every final source, class,
or archive link. This is compile-time metadata, with no runtime feature lookup.

**Translation units and linker inputs.** Introduce an original
`runtime/src/ironwood_tls.c` adapter, with an opaque internal C interface.
OpenSSL includes and types stay inside that component; shared runtime headers
and TCP translation units remain usable without them. Compile the adapter only
when the pruned program requires TLS. Compile or include the pinned CA data
only in that selected component. Preserve the existing LLVM and Clang pipeline.
The native link adds the adapter object, explicit paths to `libssl.a` followed
by `libcrypto.a`, and the pinned build's required platform libraries/flags after
the consuming objects. Retain platform dead stripping; do not force-load entire
archives or silently fall back to shared OpenSSL. Static OpenSSL does not imply
a fully static executable or removal of the existing system runtime linkage.

Use a verified static configuration whose required providers are available
without external OpenSSL modules or an installed OpenSSL configuration. Record
the actual configuration and system-library closure per platform rather than
assuming two archive names suffice. Scope every OpenSSL include path, archive,
and additional link flag to TLS links. Extend runtime-object caching to key the
selected component, its header inputs, compiler/target/sysroot arguments, and
dependency build identity; the current casing-specific cache key cannot safely
stand in for TLS header and configuration dependencies.

**Pinned dependency builds.** Add a checked-in TLS dependency manifest and
reproducible build recipe beside `packaging/idk-environment.yml`. Pin the exact
OpenSSL 3.5 patch source and checksum, CA snapshot and checksum, configuration,
tool versions, and platform metadata. The environment manifest supplies the
recipe's build prerequisites; it must not substitute an unverified solver
result for the application archives. Build into a dedicated
`toolchain/ironwood-tls` prefix when preparing an IDK, keeping this application
dependency separate from OpenSSL used by the toolchain itself.

| Platform | Dependency-build and compatibility requirement |
| --- | --- |
| Linux ARM64 | Build `libssl.a` and `libcrypto.a` with the matching LLVM toolchain and `sysroot_linux-aarch64=2.17`. Use that sysroot for dependency compilation, adapter compilation, and final linking. |
| Linux x86-64 | Apply the same rule with `sysroot_linux-64=2.17`. A final link against an older sysroot cannot repair archives built against newer glibc headers or symbols. |
| macOS ARM64 | Build matching ARM64 archives with the selected Apple SDK and deployment target recorded in the manifest and consistent with the IDK's platform contract. Preserve the Command Line Tools prerequisite. |

This extends [D139](DECISIONS.md#d139---keep-linux-release-output-compatible-with-glibc-217)
to the optional dependency without changing the baseline or adding
cross-compilation. Record and validate archive architecture, source/build
identity, sysroot or SDK, and resulting checksums. Extend the existing
`llvm-readelf` audit in [test-idk.sh](../scripts/test-idk.sh) to every new TLS
and `wget` smoke executable; reject Linux GLIBC requirements above 2.17.

**Discovery for source trees and packages.** The planned
`IRONWOOD_TLS_HOME` override selects a prepared dependency prefix containing
OpenSSL headers, both static archives, CA data, and the pinned build manifest.
Without an override, discover `toolchain/ironwood-tls` relative to the selected
Ironwood distribution, independent of the working directory. Provide a
source-tree preparation script using the same pinned recipe and prefix layout;
developers can prepare a local prefix or select the matching IDK prefix.
Validate explicit overrides rather than silently selecting another installation.
Only a TLS native link performs this discovery. Missing, wrong-platform, or
mismatched inputs produce an actionable TLS dependency diagnostic before native
compilation; ordinary links and class-only builds do not probe or require the
prefix, even if an unusable override is present. Do not download dependencies
during compilation or search ambient Homebrew, `pkg-config`, or system OpenSSL
as an implicit fallback. These paths and the override are accepted planned interfaces,
not currently implemented settings.

**Packaging and provenance.** Milestone 5 packages the adapter source, headers,
static archives, CA data, build manifest, recipe, and applicable license texts
and notices; Milestone 6 validates the relocated result. Update
`scripts/package-idk.sh`, `scripts/package.sh`, their smoke checks, and release
preparation for their respective distribution contents. Source/tool-only
packages carry the adapter, preparation recipe, and dependency documentation;
they use the explicit prefix when no bundled SDK is present. Include the
networking plan and source review in the packaged docs, accounting for
`docs/IDK.md` becoming the IDK's root `README.md`. Add actual
dependencies to `docs/THIRD_PARTY_NOTICES.md` and
`docs/SOURCE_PROVENANCE.md` when introduced. Extend the packaged
`THIRD-PARTY-PACKAGES.tsv` generation to merge the checked-in dependency
manifest with Conda records, including distinct OpenSSL-static and CA-bundle
entries with version, license, and immutable source location. Ship the richer
build/checksum manifest alongside it; do not pretend separately built archives
are covered by an unrelated Conda entry. Preserve downstream notice and source
availability requirements for the portions included in generated executables.
Do not add ledger entries claiming these dependencies are already shipped.

## Milestone 1: detailed implementation and exit criteria

1. **Record the contract and provenance before source changes.** Create the
   networking review at `docs/STDLIB_N1_SOURCE_REVIEW.md` and record acceptance of the
   decision entries. Register the review in the archive, license, package, and
   smoke-test lists under the [acceptance checklist](#acceptance-and-documentation-updates).
   Record per-file implementation categories and prior source inspection, with
   derivation limited to the two private helpers above. Build the facade contract matrix from Java API
   documentation and new behavioral probes, then design its state and
   ownership using existing Ironwood I/O mechanisms. Specify the initial
   supported members, error/state transitions, ownership graph, and native
   operation signatures. Settle the `SocketImpl` hooks, concrete delegation,
   factory registration and result adoption, injected-implementation borrows,
   stream returns, acceptance transfer, and typed option protocol before the
   facade depends on them. Record retained deprecated members and compile-time
   omissions in the same member matrix. Record the owner of every non-stream
   result and retained input, including each bulk-result element. Record the
   generic declaration matrix with explicit bounds for reference-only parameters
   and unbounded option-value parameters under D160. Draw the
   default accepted-socket graph and assign an allocation budget to each
   component and lazy result. Do not expose hostname-taking methods
   until their complete resolution contract is implemented.
   Resolve NP1-NP11 and record the property/dependency audit in that matrix.
   Freeze family selection and default-address rules in the native boundary,
   and reserve explicit version/credential inputs for the later SOCKS helper.
   Include D159's named proxy factories and snapshot ownership in the member
   matrix, without implementing authentication before Milestone 4.
   Do not import Java property infrastructure into either derived helper.

2. **Build a numeric-address vertical slice.** Implement binary IPv4/IPv6 address
   construction, numeric socket addresses, unconnected sockets, bind/connect,
   listener creation, accept, input/output streams, and close. Implement the
   corresponding `SocketImpl` slice, native delegate, explicit implementation
   constructors, both factory hooks, and protected acceptance. Include boolean
   and integer options, dedicated setters/getters, and a minimal accurate
   `supportedOptions()` inventory. Include borrowed numeric address getters,
   fresh endpoint/byte-array snapshots, `InetAddress.copy()`, and copied-message
   networking exceptions. Use loopback and port zero for tests. Omit
   later members from the initial surface instead of installing runtime stubs.
   Verify default wildcard binding accepts both IPv4 and IPv6 peers on a
   dual-stack host, explicit family bindings behave correctly, and reported
   addresses follow NP1/NP2. Exercise the IPv4-only capability fallback through
   a controlled native fixture. Keep capability checks in setup, outside the
   untimed I/O path. This tests the family-policy assumptions before DNS work.

3. **Introduce the typed native boundary.** Add operations for creation, binding,
   listening, connecting, accepting, scalar/bulk I/O, availability, shutdown, and
   close. Define primitive results and captured native errors explicitly. Carry
   allocation, exceptional, and borrowing effects through analysis,
   specialization, pruning, class/archive reconstruction, and LLVM lowering.
   Native calls must not retain caller buffers. Keep I/O attempts separate from
   readiness waits, preserving partial progress, would-block, pending
   connection, EOF, and error results for future non-blocking callers.
   Anticipate optional native components by keeping TCP and future TLS typed
   operations distinguishable after specialization and pruning. Specify where
   final-link requirements will be collected and keep the TCP ABI free of
   OpenSSL types and headers. This is an architectural constraint on the first
   boundary; TLS operations, dependency discovery, conditional adapter builds,
   and link-flag selection are implemented in Milestone 5.

4. **Prove ownership through the real extension path.** Establish owned
   input/output views that share connection state and remain stable across
   repeated getters. Test
   direct use, helper returns, interface dispatch, and buffered wrappers. Include
   a custom implementation that delegates to `NativeSocketImpl`, with both
   injected and factory-created instances and custom accepted sockets. Exercise
   an observing stream override and a hostile override that retains caller
   buffers or publishes a borrowed helper. Prove safe reclamation for the
   observing case and reject the affected frees in retaining cases; do not
   reject all custom implementations or silently assume their effects. Check
   cached/published factory results, factory capture retention, and cleanup of
   caller-owned delegates separately from facade-owned storage.

   Round-trip both option types through public generic calls, dedicated
   methods, generic override dispatch, and native delegation. Check linger
   disable/enable normalization, timeout mapping, unsupported-token behavior,
   and option discovery. Reject wrong option/value types and calls to the
   absent boxed protocol or UDP-selecting constructors during compilation.
   Test factory registration, null, and repeated-registration behavior in
   separate processes because registration is global and irreversible.

   Prove that an accepted socket survives listener reclamation, independent
   endpoint/copy results survive socket reclamation, and address/inventory
   borrows reject independent frees or later use after owner reclamation. Cover
   local/remote getters before and after close, including the listener's
   different local-address behavior. Test read-only inventory/backing-list/token
   lifetimes using the existing `ironwood.ds` rules. Use synthetic multi-address
   results and an interface snapshot with parent/subinterface navigation to
   prove element-by-element array cleanup, borrowed views, independent cursors,
   and both query-root shapes without implementing DNS or interface discovery.
   Insufficient provenance is an architectural blocker, not permission to
   change the result to an undocumented borrow or omit the later API.

5. **Make acquisition failures safe.** Allocate managed storage before acquiring
   descriptors where practical. Otherwise, guard acquired descriptors until
   successful ownership transfer. Exercise failure after native acceptance and
   during managed wrapper construction. Cleanup must preserve the primary
   failure, close each descriptor once, and reclaim completed owned storage
   without relying on destructors to close sockets. Cover partial bulk results
   and snapshot construction. Keep a caught exception alive while reclaiming
   its caller-supplied message/input text and verify its copied message remains
   readable. Inject failure during message copying and exception construction.

6. **Implement representative deadline behavior.** Cover read and accept
   timeouts, plus the timed-connect mechanism. Use monotonic deadlines that
   survive EINTR and readiness retries. Verify that read/accept timeout leaves
   the resource usable. Use controlled native fault injection for timing/error
   cases that cannot be made reliable with loopback alone. Exercise the
   attempt/wait boundary privately: would-block must not become EOF or a
   successful zero-length result for a positive blocking read, and partial
   writes must resume from the remaining bytes. Retries must not restart an
   operation's deadline when one is configured; this adds no write timeout to
   the public `Socket` contract.
   Verify the untimed syscall budget before and after timed connect, a timed
   read, and `setSoTimeout(positive)` followed by `setSoTimeout(0)`. Verify that
   writes stay on their untimed path even with a positive read timeout. Charge
   setup and explicit option changes separately; a mode change cannot be
   deferred into every subsequent read. Keep timeout, EINTR, partial-transfer,
   and EOF correctness tests alongside these performance checks.

7. **Validate the foundation before broadening it.**

   - Run two-process binary exchange with Ironwood on both sides, then
     Java/Ironwood interoperability in both directions.
   - Cover fragmented reads, EOF, peer reset, repeated close, stream-close
     propagation, and accepted-socket independence.
   - Reject independent stream-view frees, use after owner free, escaped-borrow
     reclamation, and retaining-override cases.
   - Repeat connection/failure cycles under a low descriptor limit and
     allocation budgets.
   - Measure complete sequential-server connection cycles with
     `System.allocationCount()` and `System.liveAllocationCount()`. Warm only
     process-lifetime metadata before the loop, reuse caller payload buffers,
     and record counters before accept, after accept, after first stream and
     metadata getters, after repeated getters/I/O, and after close/free.
     Print results outside the measured regions. Fix separate exact numeric
     baselines for IPv4/IPv6 and default/custom implementations from the graph
     recorded in step 1; explain every allocated object/array/String rather
     than merely accepting a stable total. Include both facade and delegate
     stream views, descriptors, address caches, inventories, and their storage.
   - Require zero additional allocations for repeated borrowed getters, option
     inventory access, and established scalar/bulk I/O. Measure fresh endpoint,
     address-copy, byte-array, and rendered-String results as separate explicit
     costs. Each completed successful cycle must restore the live managed
     allocation baseline; attribute failures and partial-result cleanup
     separately. Audit native heap allocations and file descriptors as well,
     since managed counters do not include OS resolver/interface or trace
     storage. Do not add production per-connection ownership bookkeeping.
   - Verify source, class-directory, and archive input paths.
   - Inspect `-O3` machine code and a deterministic fixed-workload loopback
     benchmark. After setup, scalar and bulk TCP I/O must add no managed or
     Ironwood-owned heap allocations, temporary payload copies, or ownership
     bookkeeping. Follow the untimed path from generated code through the
     native bridge to receive/send; require the syscall budget above and no
     avoidable initialization, deadline, wait, or trace helper calls. Inspect
     clock calls explicitly because syscall tracing alone can miss vDSO work.
   - Measure timed and untimed cases separately. In a focused diagnostic run,
     attribute receive/send, readiness, clock, and descriptor-control calls to
     the measured socket operations. Check scalar and bulk reads, short reads,
     zero-length reads, writes, and timed-to-untimed transitions; distinguish
     necessary EINTR/short-write retries from extra work. Run the deterministic
     benchmark without tracing, with timing and reporting outside the transfer
     loop. Report operation/byte counts and native-call counts alongside timing
     and allocations; aggregate throughput alone cannot establish this gate.
     Keep counting/interposition in test tooling, not production I/O.

**Exit gate:** the representative programs work through default, injected, and
factory-created implementations; both primitive option shapes survive generic
dispatch without boxing; and safe custom delegation remains reclaimable while
unsafe reclamation is rejected. Failure loops leak neither descriptors nor
owned storage, and the native hot path meets the allocation requirements.
The exit report must include the exact per-connection allocation ledger and
regressions for first-use versus repeated getters, explicit fresh results,
non-stream borrow safety, bulk-result cleanup, and copied exception messages.
It must also include untimed-path disassembly and native-call evidence showing
one receive for an ordinary uninterrupted positive-length read, with no poll,
clock read, or descriptor-flag toggle, including after timed-to-untimed transitions.
Do not claim zero-allocation connections based only on steady-state I/O tests;
an unexplained allocation or unproved result lifetime blocks this milestone.
When separately selected, Milestone 2 may extend this proved protocol;
Milestone 3 must not supply a missing prerequisite. If a proof fails, correct
the analysis or report the architectural blocker before expanding the API.

### Milestone 1 selection checkpoint

Milestone 1 is complete on `socket-tcp-support`. The
[evidence record](STDLIB_N1_VERIFICATION.md) reports its focused gates and host
limits; subsequent compiler and example regressions were corrected locally.
On 2026-09-14 the maintainer separately selected Milestone 2: remaining blocking
socket constructors and operations, options and discovery, urgent data, address
classification, IPv4/IPv6 literals and scopes, and synchronous OS DNS with the
reviewed result ownership and cleanup. This selection includes its required
source review, provenance, documentation and focused verification. Work remains
local on the same branch, without a push. Milestone 3 was subsequently selected
at the Milestone 2 checkpoint below; Milestones 4 through 6 and the later
event-loop phase remain unselected. Milestone 2 implementation and its
[evidence](NETWORKING_M2_VERIFICATION.md) are now available.

After completing Milestone 1's implementation, verification, and documentation,
stop for the maintainer's exit review. Present the evidence above and remaining
risks, and review the planned scopes and dependencies of Milestones 2 through 6.
Record the next explicitly selected milestone and its bounded scope before
starting that work; leave the others unselected. Each of Milestones 2 through 6
requires its own selection, even after a preceding gate passes. This checkpoint
does not remove the planned socket, host-networking, proxy, TLS, or downloader
outcomes, and does not relax their acceptance criteria.

### Milestone 2 selection checkpoint

Milestone 2 implements blocking socket/address APIs, literal/scoped addresses
and synchronous OS DNS. Its [verification record](NETWORKING_M2_VERIFICATION.md)
contains the focused platform, ownership, allocation, native-error and performance
evidence and distribution checks. At that checkpoint, public interface-valued
APIs and reachability remained Milestone 3 work; their subsequent selection is
recorded below. Proxies, TLS and the downloader remain Milestones 4 through 6.
Completing Milestone 2 did not select any later milestone or the event-loop phase.

## Milestone 3 selection checkpoint

On 2026-09-15 the maintainer separately selected Milestone 3: host interface
queries, owned structural snapshots and borrowed traversal results, the
interface-valued IPv6 scope APIs, reference-bounded Enumeration, and both
reachability overloads with original IPv4/IPv6 ICMP and TCP port-7 fallback.
This includes contract review, focused deterministic native and ownership
verification, documentation and distribution metadata. Live reachability is
opt-in smoke coverage under D156. Work stays on `socket-tcp-support`, locally
without pushing. Milestones 4 through 6 and event-loop work remain unselected.
The host APIs and deterministic gates are implemented; see
[Milestone 3 evidence](NETWORKING_M3_VERIFICATION.md). Stop at this checkpoint.
Completion does not select Milestone 4 or any other later work.

## Verification and delivery rules

When each networking type is introduced, verify its declaration bounds at the
caller boundary, including through class/archive inputs. Milestone 3 must accept
reference enumerations and reject primitive arguments to `Enumeration` and its
reference-only generic helpers without specialization failures in bundled
library bodies. Milestone 1's option tests must continue accepting boolean/int
tokens through public methods, custom overrides, and delegation, with no boxing
or value erasure. Keep wrong option/value pairings as compile-time errors.
Audit the generic declaration matrix before widening any bound or adding a new
helper; these checks supplement the existing ownership and allocation gates.

Each later milestone adds focused Java differential tests for supported Java
behavior and independent tests for Ironwood-specific ownership and API
adaptations. Compare portable semantics rather than OS-dependent error text,
exact buffer sizes, or resolver order within an address family. NP2's
cross-family ordering is a contract and must be tested.

Milestone 2's policy gate uses controlled resolver results to verify IPv4-first
ordering, single-family operation, loopback/wildcard defaults, and repeated
success/failure lookups without an Ironwood shared cache. Differential literal
tests run Java with the three address properties explicitly set to the selected
`false` values; include shortened decimal forms, leading zeros, BSD-only forms,
malformed literals, IPv6/scopes, and facade-specific failure mapping. Assert
that ambiguity rejection performs no OS name lookup. Test intentional cache
differences against the policy, not Java's default TTL behavior.

Milestone 3 follows the [reachability verification split](#reachability-contract-and-verification).
Do not compare live Java and Ironwood reachability booleans as a deterministic
oracle in the VM/Rosetta environment or on native hosts. Network-interface
ownership and metadata tests remain part of the normal focused gate; the smoke
classification applies only to live reachability probes.

Milestone 4's policy gate checks default V5, explicit V4, authenticated V5,
no-authentication V5, and absence of ambient credentials and proxy bypasses.
Scripted peers must verify that a malformed/non-V5 greeting or rejected
authentication never triggers a V4 retry or a direct connection. Test locally
resolved V4 targets, rejection of unresolved V4 connect targets, and V5's
unresolved-target handling separately. Exercise the documented configuration
differences directly; do not use Java's fallback
behavior as the oracle for those differences. Keep negative compilation tests
for omitted configuration APIs and verify networking property keys remain
absent from the native `System.getProperty` subset.

Exercise D159's actual factories through `Socket(Proxy)`, then reuse them through
`TlsClient` and `projects/wget` in Milestones 5 and 6. Cover null/invalid inputs,
SOCKS5's octet-length boundaries, Basic's empty fields, colon/control rules,
non-ASCII byte preservation, exact Base64 output, and 407 failure without retry.
Verify credentials reach only the configured proxy and never the tunneled origin,
including after redirects. Mutation/free of input arrays and reclamation of the
original proxy must leave each connection's snapshot intact. Check failure
cleanup, repeated-connection allocations, credential-free rendering/diagnostics,
and rejection of `Authenticator`/`PasswordAuthentication` source dependencies.

Review source provenance as well as behavior: independent facades must follow
the recorded contract and Ironwood design, while derived algorithms remain in
their classified helper files. License-header checks supplement this review;
they cannot establish implementation independence. Update the existing source
ledger and notices for files actually introduced, not planned imports.

TLS tests use local certificates to cover trusted, untrusted, expired,
wrong-host, IP-address, SNI, custom-root, handshake-timeout, and truncated-stream
cases. Milestone 5 also verifies the [TLS exclusions](#tls-client-scope-and-exclusions):

- Use controlled roots to prove custom bundles replace rather than extend the
  default set, and invalid/empty/unreadable overrides fail without fallback.
  Isolated fixtures must show that system/default trust locations and
  `SSL_CERT_FILE`, `SSL_CERT_DIR`, and ambient OpenSSL configuration cannot add
  anchors. Do not modify the host's installed trust stores to run these tests.
- Use an otherwise valid revoked leaf and controlled CRL/OCSP endpoints to
  document acceptance without revocation checking and verify no revocation
  fetches. This does not relax expired, wrong-host, or untrusted-chain rejection.
- Reconnect to local TLS 1.2 and 1.3 peers offering sessions/tickets, including
  TLS 1.3 post-handshake tickets. Require full handshakes and false session-reuse
  reports, with no resumption PSK or early data offered. Repeat through same-host
  HTTPS redirects in Milestone 6 and check session/ticket cleanup and allocation
  retention over many connections. Checking a cache/ticket flag alone is
  insufficient. Keep omitted configuration/session APIs compile-time exclusions.

Downloader tests use local HTTP/HTTPS and proxy fixtures, including
redirects, chunk boundaries, premature EOF, malformed framing, and output
failures.

Milestone 6's private parser gate uses RFC 3986 resolution cases plus HTTP(S)
authority validation, query/fragment inheritance, encoded delimiters, and
rejection before connection. Scripted peers verify redirect limits, host/port
changes, HTTP-to-HTTPS acceptance, HTTPS-to-HTTP rejection without a follow-up
connection, and new TLS identity checks after redirection. Exercise split and
coalesced 100/103/unknown-1xx/final heads, the interim count/byte/deadline limits,
101 rejection, and EOF before final status. Framing cases include TE+CL in both
header orders, supported/unsupported transfer codings, duplicate lengths,
overflow, chunk extensions/trailers, no-body statuses, and all truncation paths.
Milestone 4 separately verifies informational CONNECT heads and a 2xx tunnel
handoff with misleading length fields and buffered tunnel bytes. Large binary
downloads and many small chunks must preserve streaming allocation budgets and
cleanup; run the project through its compile/link/run workflow and relocated
packages. These are protocol-fixture checks, not live-internet acceptance tests.

TLS build verification in Milestones 5 and 6 must cover the dependency boundary
as well as successful HTTPS:

- Link and run plain TCP with the dependency prefix absent. Repeat with TLS
  methods present but pruned, through source, class-directory, and archive
  inputs. Check that no TLS adapter is compiled, no OpenSSL paths or flags are
  added, and no TLS symbols or CA payload remain in the output. Class-only
  compilation of TLS callers must also work without the SDK.
- Link a reachable TLS caller and verify the selected adapter, static archives,
  platform flags, and CA inputs. Exercise indirect calls and initializer/cleanup
  reachability, missing or mismatched SDK diagnostics, and cache invalidation
  after a dependency build or header changes.
- From relocated packages, including paths with spaces, compile and run local
  TLS and HTTP/HTTPS fixtures without system OpenSSL development files. Verify
  that generated applications need no shared OpenSSL, external provider
  modules, or ambient configuration; the toolchain may have its own separate
  dependencies. Verify CA override behavior and bundled CA identity. Audit
  dynamic dependencies and Linux GLIBC versions of the produced TLS and
  downloader binaries, and verify the dependency manifest, TSV, source
  provenance, and packaged notices agree.

Run named tests through `./scripts/test.sh --test`, focused local platform
checks, `git diff --check`, and license checks for source/provenance changes.
Exercise package and IDK smoke paths when optional TLS linking changes
distribution behavior. No full suite or hosted development builds are planned.

Keep API documentation, ownership examples, compatibility decisions, roadmap
status, provenance, and notices synchronized with each milestone. Future
implementation follows the repository's canonical-main commit, integration,
push, and synchronization workflow.
