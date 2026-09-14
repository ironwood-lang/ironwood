<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ironwood blocking TCP networking migration

Status: Proposed, under review. Implementation has not started.
Saved from the planning review on 2026-09-14.

## Summary and source findings

Deliver Java-shaped TCP clients and sequential servers, followed by a working
HTTP/HTTPS `wget`. Include the broader socket ecosystem selected during review:
explicit proxies, custom socket implementations, typed options, network
interfaces, and reachability probes. UDP, channels, selectors, and threading
remain outside this migration.

At the time of the planning review, the canonical checkout and remotes were
verified. No files were modified or tests run during that review.

Use a **hybrid implementation**, based on OpenJDK 21 source at immutable revision
`060c4f7589e7f13febd402f4dac3320f4c032b08`, already referenced by Ironwood's
provenance ledger:

| Component | Approach and rationale |
| --- | --- |
| Socket and address facades | Selectively port useful validation and state behavior from [Socket](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/net/Socket.java) and [ServerSocket](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/java/net/ServerSocket.java), replacing their internal ownership and platform plumbing. Classify adapted files as derived. |
| IP literal parsing | Port the relevant [IPAddressUtil algorithms](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/net/util/IPAddressUtil.java), preserving Java's accepted IPv4/IPv6 forms while removing temporary allocations where possible. |
| Native TCP implementation | Write an original POSIX implementation. [NioSocketImpl](https://github.com/openjdk/jdk21u/blob/060c4f7589e7f13febd402f4dac3320f4c032b08/src/java.base/share/classes/sun/nio/ch/NioSocketImpl.java) depends on cleaners, locks, virtual-thread parking, descriptor services, and temporary direct buffers that do not fit Ironwood. |
| Proxies | Adapt SOCKS4/5 protocol handling from OpenJDK. Write HTTP CONNECT directly: OpenJDK's implementation uses reflection into the much larger HTTP connection stack. |
| TLS and HTTP | Write a narrow OpenSSL adapter and a streaming HTTP downloader. Do not port JSSE or introduce the full URLConnection framework. |

Apply [LICENSE_MECHANICS](LICENSE_MECHANICS) throughout. Derived files retain
complete upstream headers, immutable references, modification notices, and
Classpath Exception licensing. Original runtime mechanisms remain
`MIT OR Apache-2.0`. Upstream tests and documentation will not be copied into
independently written files.

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
necessary.

**Use the agreed native API adaptations.**

- `SocketOption<T>` uses primitive specialization, with typed
  `getOption`/`setOption`. A non-reflective value-kind query replaces `Class<T>`.
- `supportedOptions()` exposes a read-only `ironwood.ds` collection of
  non-generic option descriptors, avoiding heterogeneous primitive wildcards and
  boxing.
- Custom `SocketImpl` implementations receive an opaque managed descriptor and
  a concrete native implementation for delegation. Preserve subclassing,
  factory hooks, and protected acceptance support without exposing raw handles.
- Add the small `Enumeration<T>` interface needed for familiar interface
  enumeration; use `ironwood.ds` for collection returns.
- Supply `InetAddress`, IPv4/IPv6 variants, socket addresses, interface metadata,
  and the appropriate checked exception hierarchy, including timeout and
  interrupted-I/O inheritance.
- Use synchronous OS name services with a fixed documented policy, no Ironwood
  DNS cache or dynamic provider discovery. Connect timeouts do not promise to
  bound OS DNS resolution.

**Keep platform differences explicit.** Target the repository's macOS ARM64,
Linux ARM64, and Linux x86-64 platforms. Handle descriptor inheritance, SIGPIPE
suppression, interrupted calls, IPv6 scopes, dual-stack behavior, and native
error mapping in the platform layer. Internal non-blocking operations and
polling may enforce deadlines while public calls remain blocking. No selectors
are exposed.

**Retain the agreed boundaries.** Explicit SOCKS4/5 and HTTP CONNECT proxies
support configured credentials, including SOCKS5 username/password and HTTP
Basic. Automatic proxy discovery, PAC, Java property configuration, and the
broader authentication stack are excluded. Deprecated socket constructors that
select UDP are absent. Existing exclusions for channels, serialization, dynamic
loading, and thread interruption remain compile-time-visible.

## Milestones and implementation order

| Milestone | Architectural outcome and acceptance gate |
| --- | --- |
| **1. Representative TCP foundation** | Prove socket/stream ownership, native error handling, deadlines, typed options, and failure cleanup through a small complete API slice. Detailed below. |
| **2. Complete blocking socket and address APIs** | Finish constructors, binding, connection, acceptance, state queries, options, urgent data, shutdown, exceptions, IPv4/IPv6 parsing, scoped addresses, and DNS. Interoperate with Java peers and existing Ironwood stream wrappers. |
| **3. Extensions and host networking** | Complete custom implementations/factories, option discovery, `NetworkInterface`, `InterfaceAddress`, and reachability overloads. Reachability includes interface/TTL handling and the OpenJDK-style ICMP attempt with TCP echo fallback, without requiring elevated privileges for ordinary use. |
| **4. Explicit proxy connections** | Deliver SOCKS4/5 and HTTP CONNECT, authentication, proxy-side DNS where applicable, endpoint reporting, and deadlines spanning negotiation. Verify against local scripted proxy peers, including fragmented and malformed replies. |
| **5. TLS client and distribution support** | Add reusable `ironwood.net.tls.TlsClient` with streams, deadlines, deterministic close, and explicit proxy configuration. Use OpenSSL 3.5 LTS, TLS 1.2/1.3, SNI, certificate-chain and hostname/IP verification, and a pinned bundled CA set with custom-CA override. |
| **6. HTTP/HTTPS wget and completion** | Deliver an Ironwood CLI that streams downloads to a file or stdout, follows bounded redirects, handles HTTP body framing, and reports failures reliably. Finish documentation, examples, packaging checks, and focused platform verification. |

TLS remains an optional link dependency selected from reachable typed
operations. Package pinned static libraries and notices with the toolchain;
embed default CA data only in TLS-using executables. Plain TCP programs must not
acquire an OpenSSL dependency. OpenSSL 3.5 is supported through April 2030; its
patch version and the CA snapshot must be maintained through releases. The
Mozilla-derived CA bundle carries MPL 2.0 notices.
[OpenSSL support policy](https://openssl-library.org/policies/releasestrat/),
[CA bundle provenance](https://curl.se/docs/caextract.html).

`wget` remains a focused application: HTTP/1.1 GET, HTTP/HTTPS URLs, DNS names and
IP literals, ports, paths and queries, relative redirects,
content-length/chunked/connection-close bodies, bounded headers, configurable
timeouts, and streamed binary output. It requests identity encoding and reports
unsupported content encodings explicitly. Recursive mirroring, cookies, resume,
HTTP/2, and a general public HTTP framework are outside this application
milestone.

## Milestone 1: detailed implementation and exit criteria

1. **Record the contract and provenance before source changes.** Create the
   networking review and decision entry. Specify the initial supported members,
   error/state transitions, ownership graph, and native operation signatures. Do
   not expose hostname-taking methods until their complete resolution contract
   is implemented.

2. **Build a numeric-address vertical slice.** Implement binary IPv4/IPv6 address
   construction, numeric socket addresses, unconnected sockets, bind/connect,
   listener creation, accept, input/output streams, close, and a representative
   typed boolean option. Use loopback and port zero for tests.

3. **Introduce the typed native boundary.** Add operations for creation, binding,
   listening, connecting, accepting, scalar/bulk I/O, availability, shutdown, and
   close. Define primitive results and captured native errors explicitly. Carry
   allocation, exceptional, and borrowing effects through analysis,
   specialization, pruning, class/archive reconstruction, and LLVM lowering.
   Native calls must not retain caller buffers.

4. **Prove socket-owned stream lifetimes.** Establish owned input/output views
   that share connection state and remain stable across repeated getters. Test
   direct use, helper returns, interface dispatch, and buffered wrappers. Include
   a representative custom implementation that observes arguments and a hostile
   override that retains them; analysis must distinguish their effects.

5. **Make acquisition failures safe.** Allocate managed storage before acquiring
   descriptors where practical. Otherwise, guard acquired descriptors until
   successful ownership transfer. Exercise failure after native acceptance and
   during managed wrapper construction. Cleanup must preserve the primary
   failure, close each descriptor once, and reclaim completed owned storage
   without relying on destructors to close sockets.

6. **Implement representative deadline behavior.** Cover read and accept
   timeouts, plus the timed-connect mechanism. Use monotonic deadlines that
   survive EINTR and readiness retries. Verify that read/accept timeout leaves
   the resource usable. Use controlled native fault injection for timing/error
   cases that cannot be made reliable with loopback alone.

7. **Validate the foundation before broadening it.**

   - Run two-process binary exchange with Ironwood on both sides, then
     Java/Ironwood interoperability in both directions.
   - Cover fragmented reads, EOF, peer reset, repeated close, stream-close
     propagation, and accepted-socket independence.
   - Reject independent stream-view frees, use after owner free, escaped-borrow
     reclamation, and retaining-override cases.
   - Repeat connection/failure cycles under a low descriptor limit and
     allocation budgets.
   - Verify source, class-directory, and archive input paths.
   - Inspect `-O3` machine code and a deterministic fixed-workload loopback
     benchmark. After setup, scalar and bulk TCP I/O must add no managed or
     Ironwood-owned heap allocations, temporary payload copies, or ownership
     bookkeeping.

**Exit gate:** the representative programs work, unsafe reclamation is rejected,
failure loops leak neither descriptors nor owned storage, and the native hot
path meets the allocation requirements. If a proof fails, correct the analysis
or report the architectural blocker before expanding the API.

## Verification and delivery rules

Each later milestone adds focused Java differential tests for supported Java
behavior and independent tests for Ironwood-specific ownership and API
adaptations. Compare portable semantics rather than OS-dependent error text,
exact buffer sizes, or DNS ordering.

TLS tests use local certificates to cover trusted, untrusted, expired,
wrong-host, IP-address, SNI, custom-root, handshake-timeout, and truncated-stream
cases. Downloader tests use local HTTP/HTTPS and proxy fixtures, including
redirects, chunk boundaries, premature EOF, malformed framing, and output
failures.

Run named tests through `./scripts/test.sh --test`, focused local platform
checks, `git diff --check`, and license checks for source/provenance changes.
Exercise package and IDK smoke paths when optional TLS linking changes
distribution behavior. No full suite or hosted development builds are planned.

Keep API documentation, ownership examples, compatibility decisions, roadmap
status, provenance, and notices synchronized with each milestone. Future
implementation follows the repository's canonical-main commit, integration,
push, and synchronization workflow.
