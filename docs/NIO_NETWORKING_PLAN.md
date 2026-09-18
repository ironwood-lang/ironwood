<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# NIO TCP channels and selectors roadmap

Status: Roadmap staging accepted on 2026-09-18 under
[D167](DECISIONS.md#d167---stage-nio-tcp-channels-and-selectors-as-one-public-capability).
All four milestones below are pending and unselected. This document records
scope and review gates, not a completed API design or implementation selection.

## Relationship to existing work

The six [blocking networking milestones](NETWORKING_MIGRATION_PLAN.md) are
complete. [N1](STDLIB_ROADMAP.md#standard-library-milestone-tracker) remains
pending because it also requires a single-threaded multi-client event-loop
server. This roadmap breaks that existing follow-up into smaller milestones;
it does not reopen the blocking migration or promise all of Java NIO.

Ironwood already has heap `ByteBuffer` operations and `ironwood.nio.file` APIs.
It does not yet have public channels or selectors. The public target here is
Java SE 21 TCP channel and selector behavior in `ironwood.nio.channels`, using
`ByteBuffer` for I/O. Existing `Socket` and `ServerSocket` behavior remains
blocking. Channel-backed socket views and their mode restrictions require the
explicit contract review below.

Non-blocking channels and selectors form one public capability. Private
implementation work may be staged, but there is no standalone public milestone
asking users to poll every socket themselves. Applications still write their
event loop and retain per-connection state; the selector supplies readiness
waiting, not an application framework or scheduler.

## Milestones

Each milestone requires separate maintainer selection. Its exit review records
evidence, remaining risks, and the next proposed scope; passing it does not
authorize the next milestone. NIO1 through NIO4 are distinct from the completed
blocking Milestones 1 through 6.

| Milestone | Bounded outcome | Exit gate |
| --- | --- | --- |
| **NIO1: API, ownership, and source review** | Review the Java hierarchy and contracts, Ironwood adaptations, native backend options, provenance, and reclamation design. | A reviewed member/behavior matrix, explicit decisions for consequential compatibility differences, ownership proof cases, backend rationale, and concrete NIO2/NIO3 boundaries. No public capability is claimed. |
| **NIO2: Private channel and readiness foundation** | Implement the reviewed native non-blocking attempts, multi-descriptor wait, and compiler/library boundary needed by both channels and selectors. | Deterministic native/error/lifetime tests, focused platform checks, allocation evidence, and optimized-code/benchmark evidence. Preserve existing blocking paths. No standalone public non-blocking API is delivered. |
| **NIO3: TCP channels and selectors together** | Deliver the reviewed `SocketChannel`, `ServerSocketChannel`, `Selector`, `SelectionKey`, required hierarchy/exceptions, and buffer I/O as a working unit. | Java differential and ownership tests plus a small selector-driven multi-client example, with partial writes and cleanup. Public APIs have real behavior, IronDocs, and enforced omission boundaries; no placeholder methods. |
| **NIO4: Multi-client acceptance and delivery** | Complete the N1 application, sustained-use, performance, documentation, and distribution gates for this public capability. | A bounded-memory server keeps other clients progressing when one stalls; all selected contracts and ownership paths pass focused checks on the three supported platforms. Publish evidence and review N1 completion. |

NIO3 must already establish correctness and safe cleanup. NIO4 adds sustained
workload and delivery evidence; it is not permission to defer known safety or
Java-contract failures. N1 remains pending until NIO4's exit review verifies
its complete acceptance gate.

## NIO1 review requirements

Use the [behavioral contract review](OPENJDK_PORTING.md#behavioral-contract-review)
for every public addition, including inherited methods, overload resolution,
exception behavior, and all buffer forms admitted by the selected signatures.
Review the required channel interfaces and abstract base classes before freezing
the hierarchy. Do not infer a complete Java API from the four headline classes.

Resolve the following before dependent implementation:

- **Channel behavior:** default blocking mode, mode changes while registered,
  accepted-channel mode, pending `connect`/`finishConnect`, bind and options,
  partial and zero progress, EOF, half-close, and errors. Review single-buffer
  and scatter/gather overloads separately; omission must not accidentally admit
  an incompatible call through an inherited member.
- **Buffers:** position/limit updates, zero remaining space, slices and wrapped
  arrays, and ownership on normal and exceptional exits. Native I/O must borrow
  storage without publishing its backing array, retaining a raw address past
  the call, or allocating a temporary payload copy on each operation.
- **Selectors and keys:** interest versus ready operations, repeated
  registration, registration with multiple selectors, selection return counts,
  selected-key persistence/removal, cancellation versus delayed deregistration,
  and close ordering. Review immediate, timed, and indefinite selection and
  any exposed `wakeup()` behavior. Readiness is advisory; a subsequent operation
  may still make no progress.
- **Collection compatibility:** Java exposes key-set views. Ironwood uses
  `ironwood.ds`, not a parallel Java Collections Framework. Resolve view types,
  mutation restrictions, iterator independence/removal, and live-view semantics
  explicitly. Existing reusable collection iterators are not automatically
  compatible. Record any necessary signature adaptation as a product decision,
  rather than silently changing a Java-shaped method's behavior.
- **Lifetimes:** specify owners and borrowers for selectors, registrations,
  keys, channel-backed socket views, buffers, attachments, sets, and iterators.
  Prove safe reclamation with cancelled keys or views still referenced,
  multiple registrations, attachment replacement, and failure partway through
  construction or registration. Cancellation and native close are not proof
  that a managed object can be freed. Retained keys must never expose a freed
  channel or selector. Cover both accepted cleanup and rejected unsafe `free`.
- **Native model:** evaluate a fixed native provider and the supported OS
  readiness backends, including whether a portable `poll` backend suffices or
  `epoll`/`kqueue` is warranted. Compare scale, idle CPU, latency, allocation,
  and implementation complexity before choosing. The Java-shaped public API
  must not depend on exposing a particular OS primitive.
- **Existing exclusions:** review provider SPI, thread interruption and
  concurrent operations, callback overloads, and socket/channel adapters
  against Ironwood's closed-world, thread-free model. No dynamic discovery,
  fake synchronization, or runtime unsupported stubs. Material API omissions or
  semantic differences require explicit review, not this roadmap's implied
  approval.
- **Provenance:** choose independent versus derived implementation per file
  before inspecting OpenJDK implementation bodies. Use Java 21 public contracts;
  any derived helper needs a verified Classpath-covered file at an immutable
  revision under [LICENSE_MECHANICS](LICENSE_MECHANICS). Preserve independent
  facade/native code boundaries, source records, and required notices.

Small proof fixtures belong to this review when needed to demonstrate that the
proposed ownership graph is representable. Do not implement speculative APIs
to avoid an unresolved contract or reclamation decision.

## Verification and acceptance

Every executable stage has focused tests for its own risks. Select exact tests
or focused groups under [LOCAL_TESTING.md](LOCAL_TESTING.md); do not run an
unfiltered compiler suite. Include source, compiled-class, archive, and final
link reconstruction when introducing ownership or compiler boundaries.

NIO2/NIO3 must cover native error mapping, interrupted waits without timeout
renewal, would-block after readiness, partial I/O, pending connection outcomes,
EOF/reset, cancellation, descriptor reuse, repeated close, and failed
construction/registration cleanup. Use controlled native fixtures for outcomes
that cannot be forced reliably by loopback timing. Java differential tests
cover supported Java behavior; they are not an oracle for Ironwood `free`.

NIO4 uses a selector-driven TCP server with bounded per-connection buffers and
explicit backpressure. Exercise multiple clients, fragmented messages, a peer
that stops sending, a peer that stops reading, connection churn, failure, and
shutdown. Show that one stalled peer does not stop other clients; avoid busy
loops caused by permanently enabled write interests. Keep the existing
`projects/SimpleTcpEcho` blocking quick start intact and provide a separate
selector example and concise guide.

Measure managed and native allocations separately during construction,
registration, steady-state selection and I/O, and cleanup. Necessary retained
registration state is distinct from avoidable per-operation scratch or
safety-only bookkeeping. Preserve D132/D133: no compiler-injected hot-path
lifetime registries or hidden tracing. Inspect optimized machine code and
record deterministic native-call/benchmark evidence, including idle behavior
and the existing untimed blocking TCP path. Any unavoidable safety or
performance conflict goes back to the maintainer before implementation.

The final gate covers macOS ARM64, Linux ARM64, and Linux x86-64 with focused
local tests, the relevant stdlib runner groups, a runnable project workflow,
and affected relocated host-package/IDK paths. Update IronDocs, compatibility
records, provenance, and source/notice packaging as implementation lands.
Missing evidence stays a limitation or blocker, not a completion claim.

## Outside these milestones

These milestones cover plain TCP channel multiplexing, not the whole NIO
ecosystem. `DatagramChannel`/multicast, `FileChannel`/mapping/locks, `Pipe`,
asynchronous channels, direct/mapped buffers, and additional filesystem APIs
require their own concrete use case, contract review, and selection. They are
not prerequisites merely because Java groups them under NIO.

Synchronous DNS, existing proxy negotiation, and `TlsClient` remain blocking.
Resolve endpoints before entering the acceptance loop; do not advertise these
services as non-blocking because their underlying TCP primitives are reusable.
Async DNS, proxy/TLS state machines, and downloader integration need separate
future scope. Threads and a general event-loop framework are not selected.

## Java contract references

The review targets Java SE 21:
[SelectableChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/SelectableChannel.html),
[SocketChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/SocketChannel.html),
[ServerSocketChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/ServerSocketChannel.html),
[Selector](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/Selector.html),
and [SelectionKey](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/SelectionKey.html).
These references establish review inputs, not a completed member-by-member
compatibility or implementation-source review.
