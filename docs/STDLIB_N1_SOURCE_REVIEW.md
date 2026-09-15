<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# N1 networking source and contract review

Design baseline: [networking migration](NETWORKING_MIGRATION_PLAN.md), accepted
2026-09-14. Milestone 1 is complete. The maintainer separately selected Milestone 2
on 2026-09-14 for the remaining blocking socket/address APIs, literal parsing,
scopes and DNS. Milestones 3 through 6 remain unselected; N1 remains pending.
Milestones 1 and 2 are implemented. Interface-valued APIs, reachability, proxy
and TLS contracts remain separate. Measurements are recorded in
[Milestone 1 verification](STDLIB_N1_VERIFICATION.md) and
[Milestone 2 verification](NETWORKING_M2_VERIFICATION.md).

## Sources and implementation categories

Public behavior is reviewed against the Java 21 API documentation for
[Socket](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/Socket.html),
[ServerSocket](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/ServerSocket.html),
[SocketImpl](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/SocketImpl.html),
[InetAddress](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetAddress.html),
[InetSocketAddress](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/InetSocketAddress.html),
and [StandardSocketOptions](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/StandardSocketOptions.html).
New behavioral probes supplement those contracts. Ironwood implementation
structure follows the existing stream, owned-helper, and typed native boundaries.

The planning review inspected OpenJDK public facade and native implementation
bodies. This is not a clean-room claim. This implementation review additionally
verified the complete Classpath Exception headers of the three references below
at OpenJDK 21 revision `060c4f7589e7f13febd402f4dac3320f4c032b08`. No upstream
implementation bodies, distinctive structures, comments, documentation, or tests
may enter independent source.

Milestone 2 also reviewed the installed Java 21.0.10 `Inet6Address` documentation;
the extraction included adjacent implementation text. That inspection does not
authorize translation into a public facade. Its field layout, helpers and bodies
are not the Ironwood implementation template. New differential probes and the
contracts below determine behavior; only the separately classified literal
helper derives from the pinned implementation.

| File or group | Category and boundary |
| --- | --- |
| `ironwood/net/Socket.iron`, `ServerSocket.iron`, `SocketImpl.iron`, `SocketImplFactory.iron` | Independent compatible facades, `MIT OR Apache-2.0`. Original state and ownership design; no translation of facade bodies. |
| `ironwood/net/NativeSocketImpl.iron`, `NativeSocketFactory.iron`, `SocketDescriptor.iron`, `TcpNative.iron`, `TcpSupport.iron`, `ResolverQuery.iron`; nested stream views | Original Ironwood source, `MIT OR Apache-2.0`. No raw descriptor access in public source. |
| `ironwood/net/SocketAddress.iron`, `InetAddress.iron`, `Inet4Address.iron`, `Inet6Address.iron`, `InetSocketAddress.iron` | Independent compatible address/endpoint values, `MIT OR Apache-2.0`. Call a separately classified parser and original resolver bridge. |
| `SocketOptionDescriptor.iron`, `SocketOption.iron`, `NativeOption.iron`, `StandardSocketOptions.iron`, `SocketImplOptions.iron`, `TcpInventories.iron` | Original primitive-specialized adaptation, `MIT OR Apache-2.0`. No `Object` value protocol. |
| `SocketException.iron`, `BindException.iron`, `ConnectException.iron`, `NoRouteToHostException.iron`, `UnknownHostException.iron`, `SocketTimeoutException.iron`, and `ironwood/io/InterruptedIOException.iron` | Independent compatible hierarchy, `MIT OR Apache-2.0`; copied messages following `FileNotFoundException`. |
| Typed TCP IR, analyses, lowering, isolated C TCP support and new fixtures | Original Ironwood implementation, `MIT OR Apache-2.0`. No OpenSSL header/type dependency. |
| `ironwood/net/IpLiteralParser.iron` | Derived in Milestone 2, from `src/java.base/share/classes/sun/net/util/IPAddressUtil.java` at the revision above. Retains the full header and uses `GPL-2.0-only WITH Classpath-exception-2.0`. |
| Future private SOCKS helper | Derived only when Milestone 4 is selected, from `src/java.base/share/classes/java/net/SocksSocketImpl.java` at the revision above, with the same derived obligations. |
| `src/java.base/share/classes/sun/nio/ch/NioSocketImpl.java` | Dependency-review reference only. Cleaner, locks, virtual-thread parking, descriptor services and temporary direct buffers are excluded. No translation planned. |

Milestone 1 imported no derived networking source. Milestone 2 introduces the
private literal parser with its source-ledger entry and notice. No external native
SDK or networking library is required. Independent facade calls preserve the
derived helper's source and notice obligations.

## Milestone 2 implementation review

The independent facades retain their existing provenance. `IpLiteralParser.iron`
is the selected
derived file for decimal IPv4, BSD ambiguity recognition and IPv6 parsing from
the pinned `IPAddressUtil.java`. It accepts borrowed String ranges, returns
primitive IPv4 bits, and fills caller-provided IPv6 bytes. It retains no inputs
and performs no allocations. Literal callers reclaim their IPv6 scratch array.
URL validation, property readers, CharBuffer, interface caches and concurrent
maps are excluded. NP3 is fixed false, including ambiguity rejection before DNS.

| Selected additions | Contract and ownership | Required checks |
| --- | --- | --- |
| Socket's four TCP destination constructors; ServerSocket's three bound constructors | Validate ports and copy endpoints; null hostname selects loopback, null InetAddress follows its overload contract. Close acquired descriptors on construction failure and preserve primary failures. | Java 21 probes, custom implementations, local binding, OOM/native failure and peer interoperability |
| Urgent data, OOB-inline, traffic class, reuse-port option discovery and performance preferences | Actual typed hooks and native option behavior; primitive values remain unboxed. Preferences remain advisory. Unsupported capability is omitted from its inventory and correctly typed unsupported tokens fail. | Override dispatch, option bounds, native errors, urgent peer delivery and inventory reuse |
| Address predicates, hostname-bearing binary factories, IPv6 numeric scope factory/query and literals | Fresh owned primitive values and copied retained names/scope text; equality/hash compare address bytes, not scope or labels. Preserve numeric scope through bind/connect and endpoint snapshots. | Mapped addresses, scope rendering, grammar boundaries, widening and copy independence |
| getByName/getAllByName/getLocalHost/getLoopbackAddress | Fresh independent results with IPv4-first family grouping and OS order inside each family. Bulk arrays contain distinct caller-owned elements, with partial-result cleanup. No shared Ironwood success/failure/stale cache. | Controlled resolver families, order, repeated outcomes, native storage cleanup and safe/unsafe array element reclamation |
| Hostname/canonical getters and InetSocketAddress hostname/unresolved APIs | Copy retained input text; cache getter text per address graph as dependent borrows. Host-string and rendering must not initiate reverse lookup. Reverse names require forward confirmation; failed resolution leaves an unresolved endpoint where Java specifies. | First/repeated allocation counts, DNS call counts, copied inputs, unresolved equality/hash, borrow rejection and fresh snapshots |

NetworkInterface-valued overloads, scoped-interface object getters, interface
snapshots and reachability remain with Milestone 3, when that public type is
introduced. Milestone 2 handles numeric scopes and named literal scopes through
private OS queries without introducing a placeholder public NetworkInterface.
Proxy constructors remain with Milestone 4; UDP, channel and boxed-option APIs
remain absent. The mandatory native I/O allocation/call budget from Milestone 1
continues to apply. Resolver and name materialization work is measured separately.

## Selected declaration and contract matrix

The following matrix and the Milestone 2 additions above describe implemented
members. Unlisted members are absent. Factory hooks are retained despite their
Java deprecation; the UDP-selecting overloads are absent. Normal
Java primitive widening remains admitted for integer parameters and must be
covered, as must inherited stream and Object consumers.

| Type and initial members | Behavioral contract and failures | Result and retained-input ownership |
| --- | --- | --- |
| `InetAddress.getByAddress(byte[])`, `getByAddress(String,byte[])`, binary IPv4/IPv6 values | Require 4 or 16 network-order bytes; invalid/null data maps to `UnknownHostException`. Recognize mapped IPv4 values. No resolver call. | Fresh value with copied primitive address bits and optional hostname; inputs remain caller-owned. |
| `InetAddress.getAddress()`, `copy()` | Independent network-order bytes; `copy()` is an explicit Ironwood extension. | Fresh array or fresh address, usable after source reclamation. |
| Address equality, hash, classification, `getHostAddress()`, `toString()` | Java address value behavior and textual representation; scope and hostname do not affect equality/hash. Rendering does not initiate reverse lookup. | Rendered Strings fresh; numeric value access allocation-free. |
| `InetSocketAddress(int)`, `(InetAddress,int)`, `(String,int)`, `createUnresolved`, name/address/port getters, equality/hash/rendering | Port 0..65535; null address means default wildcard; null hostname fails. Lookup failure creates an unresolved endpoint; unresolved names compare ignoring case. Hash memoizes its primitive result. | Constructors copy retained input; getters borrow owned children/text. Endpoint never adopts caller address. |
| `Socket()`, protected `Socket(SocketImpl)` | Default or configured fresh implementation; injected null remains permitted for subclass acceptance initialization under the Java constructor contract. No UDP-selecting overload. | Default/factory implementation owned only if fresh and unpublished; explicit implementation borrowed. |
| `ServerSocket()`, protected `ServerSocket(SocketImpl)` | Unbound listener; injected null throws `NullPointerException`. | Same fresh/borrow distinction. |
| `Socket.bind(SocketAddress)`, `connect(SocketAddress[,int])` | Null bind means wildcard/port zero; null connect, unsupported address type, negative connect timeout fail before native activity. Reject repeat bind/connect and closed use; timeout 0 means infinite. | Copy retained endpoint values and labels; no input retention after successful built-in call. Unresolved native connect throws UnknownHostException; unresolved bind fails. |
| `ServerSocket.bind(SocketAddress[,int])` | Null means wildcard/port zero; nonpositive backlog chooses native default. Bound/closed misuse fails. | Copy retained endpoint values. |
| `ServerSocket.accept()`, protected final `implAccept(Socket)`; matching `SocketImpl` hooks | Accept into a newly created unbound destination. Timeout leaves listener usable. Native ownership transfers once, with cleanup before transfer on failure. | Public accept returns fresh independent socket. Protected hook borrows destination and its implementation. Listener owns neither result nor destination. |
| `getInputStream()`, `getOutputStream()`, `close()` | Stable cached views; unconnected/closed getters fail. Either stream close closes facade and implementation. Close is idempotent; listener closure does not close accepted sockets. | Facade owns views, which borrow implementation streams; delegate owns its own views. Close releases descriptor, free reclaims graph. |
| Scalar/bulk stream I/O and inherited defaults | Positive read returns first progress, EOF is -1; writes complete or fail preserving progress. Zero-length bulk operations validate null/range arguments and succeed without native I/O even after shutdown or close. Reset and positive-length closed behavior follow stream contracts. | No retained payload buffer; observing override remains reclaimable, retaining override blocks affected frees. |
| Client/local/remote address, port, bound/connected/closed queries | Historical binding/connection survives close. Closed/unbound client local address is wildcard; bound listener retains its local value after close. Earlier borrows are not destroyed by transition. | Address getters borrow cached immutable values; endpoint getters return fresh independent endpoint plus address. |
| `SocketImpl.create()`, bind/connect/listen/accept/stream/available/close hooks, String/address convenience connect overloads; concrete public native overrides | TCP-only hook replaces Java's datagram-selecting creation. Managed opaque descriptor can be borrowed, never duplicated or independently closed via a raw public handle. | Fresh private descriptor and helpers owned by native implementation; caller-supplied delegates borrowed; freshly constructed delegates can be owned. |
| Both factory registrations | Null before registration is no-op; any registration after non-null registration throws `SocketException`, including null. Separate globals for client and listener. | Registered factory and captured graph retained for process lifetime; no socket owns it. Cached/published factory results cannot be adopted. |
| `<T> getOption(SocketOption<T>)`, `<T> setOption(SocketOption<T>,T)` | Unbounded T throughout public methods, overrides and delegates. Protected hooks throw `SocketException`; facade generic calls may throw `IOException`. Wrong token/value pair fails compilation. Null token throws NPE; correctly typed unsupported token throws UOE. | Primitive values never box or erase. Fluent facade set returns receiver alias. |
| Dedicated boolean/int options and inventory | TCP_NODELAY, SO_KEEPALIVE, SO_REUSEADDR, SO_SNDBUF, SO_RCVBUF, SO_LINGER, IP_TOS and capability-dependent SO_REUSEPORT; timeout and OOB-inline are implementation-only. All dedicated access routes through generic overrides. Positive buffer sizes; enabled linger nonnegative and clamped to 65535, disabled linger normalized to -1. Timeout nonnegative milliseconds; traffic class 0..255. Server inventory contains only role-supported options. | `supportedOptions()` borrows cached read-only `ironwood.ds` inventory, with separately owned backing list and iterator. Standard tokens and names have process lifetime. Repeated getter allocates nothing, including after close. |
| `SocketException`, `BindException`, `ConnectException`, `NoRouteToHostException`, `UnknownHostException`, `SocketTimeoutException`; `InterruptedIOException` | Checked I/O hierarchy; timeout derives through interrupted I/O, with bytesTransferred. Preserve primary failure if cleanup or allocation fails. | Message copied into exception-owned String. Getters borrow copy; constructor input may be freed independently. Causes/secondary exceptions remain borrowed. |
| `SocketOptions`, integer-ID/Object hooks, UDP constructors, channels, threads | Absent at compile time. | No substitute runtime stubs. |

Reference-only collection, snapshot and cursor parameters use `extends Object`
or narrower reference bounds. `SocketOption<T>` and every option method leave
T unbounded. Inventory element types are non-generic descriptors, independent
of option value representation. Existing `Iterator`/`Iterable` bounds do not
change. Class-directory/archive reconstruction must preserve these declarations.

## Later result contracts tested synthetically in Milestone 1

These Milestone 1 fixtures did not implement DNS, interface discovery or proxies.
Real DNS results now exercise the same proofs in Milestone 2; the other APIs remain unselected.
The synthetic proofs must use ordinary alias rules and exercise both owning
query roots and owning enumeration roots.

| Later result | Required proof |
| --- | --- |
| Multi-address result | Fresh array and distinct fresh caller-owned elements; detach/free each element, then shallow-free array. Partial failure reclaims completed elements. Early termination, retained elements, replaced borrowed slots and duplicate aliases retain their actual ownership. |
| Interface snapshot | Flat structural storage owns private views; parent and child navigation borrows the same root. Lookup and enumeration roots both reclaim safely after views finish. Never expose hidden owner or create mutually owning interface objects. |
| Nested enumeration/list | Independent fresh cursor borrows snapshot; next element borrows view. Fresh mutable list contains borrowed entries and must be freed before snapshot. Caller insertion does not transfer ownership. |
| Non-stream borrows | Reject independent frees, escaped-borrow owner reclamation, and later use after root free, through helpers and interfaces. |
| `Proxy.socks5(endpoint,user,password)`, `Proxy.httpConnectBasic(endpoint,user,password)` | Reserved Ironwood extensions for Milestone 4. Fresh immutable proxy copies endpoint/octet arrays; connection copies configuration. No credential getter, ambient authentication or implicit protocol fallback. |

## Fixed policy and dependency audit

NP1/NP2 select dual-stack capability, IPv4 fallback only when IPv6 is unavailable,
IPv4-first result preference and default wildcard reporting. Explicit IPv6
bindings report IPv6. Capability probes belong in setup; no per-I/O query.
NP3 fixes the pinned parser's ambiguity setting false, including decimal short
forms, decimal leading zeros and rejection of BSD-only forms before resolution.
NP4 selects OS name services; NP5/NP6/NP7 omit positive, negative and stale
Ironwood caches. Milestone 2 implements and tests these DNS/parser behaviors.
NP8 selects explicit proxies without properties, environment/PAC or bypass.
NP9 uses explicit V4 or default V5, with no V5-to-V4 retry. NP10 uses only named
explicit credential factories and separate V4 user ID. NP11 excludes optional
automatic endpoint enrichment, while preserving ordinary contextual messages.

The pinned parser's `jdk.net.allowAmbiguousIPAddressLiterals` read maps to NP3;
its `jdk.net.url.delayParsing`, URL checks and scoped-interface cache are outside
binary construction and the implemented literal helper. The pinned
SOCKS helper's `SocksProxy` version input maps to NP9, proxy selection to NP8,
Authenticator and `StaticProperty.userName()` to NP10, and automatic V4 retry
is explicitly excluded. No property reader or startup property cache is imported.
The plan inventories indirect InetAddress/cache/DefaultProxySelector settings;
the remaining proxy dependency bodies must be re-audited before Milestone 4.
Networking adds no supported `System.getProperty` keys or general configuration
map. OpenSSL dependency discovery, TLS operations, and link flags are Milestone 5.

## Implemented typed boundary and allocation ledger

The TCP boundary must retain its own typed operation identity after primitive
specialization and pruning. Native-link requirement collection will consume the
post-pruning typed program in Milestone 5, including exceptional paths. Do not
infer dependencies from LLVM text or package names.

Creation, bind, listen, connect attempt/completion, accept attempt, scalar/bulk
receive/send, availability, shutdown, option access, endpoint query, close and
readiness wait are distinct operations. Results carry primitive progress/status
and captured native error together, without a later errno query. Caller buffers
are borrowed for a call only. Native failures return status; source constructs
copied checked exceptions. No native call acquires an unguarded descriptor across
an allocating managed operation. Deadlines use one monotonic absolute limit per
operation, preserving EINTR and readiness retries. Timed receive uses per-call
nonblocking flags; timed connect restores blocking mode during completion.
Timed accept mode changes belong to explicit timeout configuration. Untimed
receive/send never call clock, readiness or descriptor control helpers.

The original `TcpNative` bridge uses the following signatures. Every status
result is a `long` containing a signed 32-bit result and the captured native
error in its upper 32 bits. Error classification is a separate pure operation
used only after failure. EOF is result -1 with error zero; a would-block error
is never EOF. No call throws through the C boundary or retains an input buffer.

| Operations | Source parameters |
| --- | --- |
| create, close, available, connection completion | family or descriptor `int` |
| bind and connect attempt | descriptor, socket family, address family, four address words, port and scope, all `int` |
| listen, restore flags, shutdown | descriptor and integer argument |
| accept attempt | listener descriptor |
| scalar receive and per-call nonblocking receive | descriptor |
| bulk receive and per-call nonblocking receive | descriptor, borrowed `byte[]`, offset, length |
| scalar/bulk send | descriptor and byte value, or borrowed `byte[]`, offset, length |
| readiness attempt | descriptor, write/read `boolean`, remaining `long` nanoseconds |
| blocking-mode configuration | descriptor and blocking `boolean`; success returns prior flags |
| boolean/integer native options | descriptor, option code, and matching primitive value for writes |
| endpoint query | descriptor, peer/local `boolean`, borrowed descriptor state; seven typed integer output fields |
| resolver acquire/iterate/release | borrowed UTF-8 input; typed 64-bit list handle/cursor, 32-bit count/family/address/scope fields; explicit native-list release |
| reverse/local name, literal scope query, default family | primitive address/scope fields, borrowed byte-array output or interface-name input; bounded native scratch state |
| urgent send and reuse-port discovery | descriptor and low-byte value; setup-only capability probe |

Endpoint query lowering passes pointers to compiler-selected primitive fields,
so C does not depend on the managed descriptor's layout. The native operation
uses one stack socket-address structure and one endpoint syscall. Public source
never receives these pointers. The private generic option bridge selects typed
boolean/int source helpers during existing specialization. Other shapes retain
the source unsupported-option failure. Token identity controls native option
selection; descriptive value-kind metadata never authorizes a generic cast.

Default numeric accepted graph, confirmed again by the Milestone 2 regression probe:

```text
Socket (1)
  owns NativeSocketImpl (1)
    owns opaque descriptor and primitive endpoint state (1)
    owns lazy input/output delegate views (2)
    owns lazy local/remote numeric address values (2)
  owns lazy input/output facade views (2)
    borrows implementation views and confined facade backlink
```

Measured: 3 allocations at accept, 4 more for first stream getters, 2 more for
first local/remote address getters, 9 total for either numeric family. Numeric
address values store primitive bits rather than a private byte array. A custom
owning delegator adds its implementation object; an observing input view adds
one separately explained helper. Factory and built-in inventories are warmed
process-lifetime metadata, never hidden per-connection allocations. Repeated
borrowed getters, inventory access and established I/O must allocate zero.
Fresh endpoint costs 2 live objects; address copy and byte-array snapshot each
cost 1. Rendering costs 3 allocations for host text, 4 for address/endpoint toString,
and 5 for a connected socket or bound listener description.
Every completed close/free cycle must return to the initial live count. Native
heap/trace storage and descriptor counts require independent evidence.

## Verification record

The [Milestone 1 record](STDLIB_N1_VERIFICATION.md) records the actual default,
injected/factory delegation, primitive options, numeric TCP, ownership and
failure probes, allocation/native-call counts, O3 inspection and archive/package
checks. [Milestone 2 evidence](NETWORKING_M2_VERIFICATION.md) adds Java 21 literal
and API differentials, controlled DNS policy/failure probes, real urgent data,
allocation-failure cleanup and local three-platform results. The private IP
parser carries its separate derived license; no SOCKS implementation is present.
Milestones 3 through 6 each require a separate selection.
