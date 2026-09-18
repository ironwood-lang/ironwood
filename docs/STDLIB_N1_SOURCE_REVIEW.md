<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# N1 networking source and contract review

Design baseline: [networking migration](NETWORKING_MIGRATION_PLAN.md), accepted
2026-09-14. Milestone 1 is complete. The maintainer separately selected Milestone 2
on 2026-09-14 for the remaining blocking socket/address APIs, literal parsing,
scopes and DNS. Milestone 3 was separately selected on 2026-09-15.
Milestones 1 through 6 are implemented. Milestone 4 was separately selected on
2026-09-16 and is complete. Milestone 5 was separately selected on 2026-09-16
and completed on 2026-09-17; Milestone 6 was separately selected on 2026-09-17
and completed on 2026-09-18.
N1 remains pending. See [Milestone 6 verification](NETWORKING_M6_VERIFICATION.md),
[Milestone 5 verification](NETWORKING_M5_VERIFICATION.md)
and [Milestone 4 verification](NETWORKING_M4_VERIFICATION.md). Earlier measurements are recorded in
[Milestone 1 verification](STDLIB_N1_VERIFICATION.md),
[Milestone 2 verification](NETWORKING_M2_VERIFICATION.md) and
[Milestone 3 verification](NETWORKING_M3_VERIFICATION.md).

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
| Typed TCP/host IR, analyses, lowering, isolated C TCP/host support and new fixtures | Original Ironwood implementation, `MIT OR Apache-2.0`. No OpenSSL header/type dependency. |
| `ironwood/net/IpLiteralParser.iron` | Derived in Milestone 2, from `src/java.base/share/classes/sun/net/util/IPAddressUtil.java` at the revision above. Retains the full header and uses `GPL-2.0-only WITH Classpath-exception-2.0`. |
| NetworkInterface, InterfaceAddress, InterfaceSnapshot/InterfaceQuery and their enumeration helpers; util/Enumeration and EnumerationIterator | Independent facades and original flat ownership/cursor mechanisms, `MIT OR Apache-2.0`; no upstream implementation or tests copied. |
| `Proxy.iron`, `ProxySocketImpl.iron`, `ProxyExchange.iron`, `HttpConnectProtocol.iron` | Independent compatible facade and original connection/configuration/HTTP mechanisms, `MIT OR Apache-2.0`. No OpenJDK facade or HTTP implementation translated. |
| Milestone 4 private `SocksProtocol.iron` | Derivation from `src/java.base/share/classes/java/net/SocksSocketImpl.java` at the revision above. Only wire negotiation is translated; full header, source ledger and notices accompany the new file. |
| `src/java.base/share/classes/sun/nio/ch/NioSocketImpl.java` | Dependency-review reference only. Cleaner, locks, virtual-thread parking, descriptor services and temporary direct buffers are excluded. No translation planned. |

Milestone 1 imported no derived networking source. Milestone 2 introduces the
private literal parser with its source-ledger entry and notice. Those milestones
require no external native SDK or networking library. Independent facade calls
preserve the derived helper's source and notice obligations.

## Milestone 5 implementation review

Selected on 2026-09-16; implementation and measurements are recorded in
[M5 verification](NETWORKING_M5_VERIFICATION.md). TlsClient,
its private stream helpers, typed TLS operations, dependency discovery/build
recipe and the isolated C adapter are original `MIT OR Apache-2.0` work.
No JSSE or additional OpenJDK implementation is translated. OpenSSL 3.5.8
and the Mozilla-derived curl CA snapshot dated 2026-08-13 are separately
licensed dependency inputs. Their release checksums were fetched from the
upstream release locations before importing material.

The client is a single-connection, reusable-library abstraction, not a Socket
subclass or a general JSSE provider. It owns a concrete native delegate selected
by an original `NativeSocketImpl.forProxy(Proxy)` factory, which copies explicit
proxy configuration and bypasses global Socket factories. The existing proxy
wire helper remains separately derived. The bridge is a private nested class
inside TlsClient, inaccessible even to application code in the same package.
Its typed TLS attach operation borrows the opaque descriptor and reads only its
primitive handle; no public raw handle or ownership transfer is added. The
adapter borrows that descriptor, owns its SSL/context graph, and releases TLS
state before the delegate closes
the transport. Cached stream views borrow the client and close it; outer
wrappers must be reclaimed before the client. Destructors reclaim managed
storage only, after explicit close. No ownership-analysis exemption was added.

The public surface is constructors for default trust, explicit Proxy,
and Proxy plus custom PEM path; connect by hostname/port or explicit endpoint
plus peer identity; cached input/output streams; read/write timeout configuration;
connected/closed queries and idempotent close. Configuration snapshots caller
inputs. Custom roots are loaded before network activity and replace bundled
roots. Connection failure closes the entire client, preserving its primary
failure. One connect deadline spans local resolution, proxy setup and handshake;
OS DNS remains synchronous. Positive stream timeouts bound each operation;
TLS timeouts close the client because a partially processed TLS record cannot
be abandoned safely. Close attempts close_notify once without waiting for the
peer, then always releases native state and the descriptor.

TLS 1.2/1.3, chain/time/server-purpose and DNS/IP verification are mandatory.
DNS identities use ASCII labels (including caller-provided A-labels); numeric
identities use address bytes and no SNI. No verification bypass, session reuse,
early data, revocation or ambient trust/configuration is exposed. Each client
uses an isolated native library context with built-in providers, no dynamic
modules and no default trust loading. Bulk I/O borrows caller storage; scalar
I/O uses bounded native stack storage. Managed costs are the client,
native delegate/descriptor, copied proxy graph, optional copied CA path and
two lazy stream views, plus explicit temporary identity/endpoint values.
Native TLS allocations are measured separately, including first use, tickets,
failure and repeated cleanup. The M5 record explains the eight-object direct
connection ledger, 24/26/31-object proxy fixtures, native allocation-failure
sweeps, record-framing costs and zero per-connection retention after warmup.

## Milestone 4 implementation review

The maintainer selected explicit SOCKS4/5 and HTTP CONNECT on 2026-09-16.
NP8-NP10 and D159 remain the contract. Work continues locally on the requested
`socket-tcp-support` branch without pushing; completion does not select TLS or
the downloader. Public Proxy and Socket additions are independently implemented
from contracts and new Java probes. Proxy route values, configuration copying,
descriptor/deadline management and HTTP CONNECT parsing are original Ironwood
mechanisms. No upstream facade, authentication callback, proxy selector or HTTP
implementation body is a translation template.

The exact pinned SocksSocketImpl file was re-fetched and matched SHA-256
`60441b81bed622ad6a62c99ea00b904276c5757d3da6ae88b70173bbe0b2f2fc`.
SocksConsts, SocksProxy and DefaultProxySelector were inspected as dependency
references. Their property selection, OS username and ambient authentication
paths are excluded. The derived helper implements negotiation only, borrowing
socket-owned configuration during the call. It neither owns descriptors nor
retains credentials globally. The independent public facade calls this helper
through original private connection logic.

Configuration constructors copy endpoint and credential storage, and
Socket(Proxy) copies it again. Endpoint getters borrow immutable owned values;
remote socket-address results are fresh copies of the target, including an
unresolved target. Actual-API tests cover input mutation/reclamation, independent proxy reclamation,
borrow escape rejection and partial-construction rollback.
There is no credential getter, callback, process cache or shared ownership.

Negotiation uses one monotonic connect deadline, including elapsed synchronous
proxy DNS, and bounded reusable scratch. HTTP head processing consumes at most
64 KiB and 16 interim responses, rejects 101, and stops exactly at the final 2xx
header terminator. A typed PEEK_BYTES operation uses recv(MSG_PEEK), then
consumes exactly the parsed prefix. It leaves tunnel bytes in the kernel and
releases negotiation scratch before returning. Established streams keep the
existing direct TCP implementation, with no prefix wrapper or per-read proxy
bookkeeping. The private exchange stores a primitive descriptor handle only
inside the enclosing connect call; the Socket remains its sole resource owner.
Keeping a borrowed managed descriptor in a temporary helper caused conservative
retention in the existing source proof. Restricting that helper to call-scoped
native operations resolves the proof without any ownership-analysis exemption,
new ownership rule or runtime registry. Public source still has no raw handle.
The new typed operation receives mandatory null/overflow-safe range checks.

| Added public surface | Contract and ownership |
| --- | --- |
| `Proxy(Type, SocketAddress)`, Type DIRECT/HTTP/SOCKS, NO_PROXY | Independently implemented route value. Endpoint must be InetSocketAddress; DIRECT construction is invalid. Java's null-type constructor quirk is preserved, but Socket rejects the resulting invalid route. NO_PROXY has process lifetime. |
| `type`, `address`, final `equals`/`hashCode`, `toString` | type/address remain overridable, including in equality/hash. Endpoint getter borrows; rendering returns owned credential-free text. Route identity does not include version/credentials. |
| `Socket(Proxy)` | Copies configuration, rejects null/invalid route with IAE; NO_PROXY honors registered factory, explicit HTTP/SOCKS bypass it. Proxied remote snapshots report the original target. |
| `Proxy.socks(endpoint, SocksVersion.V4/V5)`, `socks4(endpoint,userId)` | Original typed version/user-ID extensions. V4 accepts only resolved IPv4; empty user ID is valid, NUL is rejected. |
| `socks5`, `httpConnectBasic` | D159 factories with validated copied octets; required V5 authentication or one explicit initial Basic header. Nulls NPE, invalid values IAE. No credentials escape through getters or rendering. |

New independent Java probes verify route equality, subclasses, nulls and factory
selection. They are not an oracle for explicit credential extensions, no-fallback
policy, ownership or strict HTTP head budgets. Scripted peers cover those rules.
A resolved proxy endpoint is borrowed during setup without a redundant temporary
copy; an unresolved endpoint produces a temporary resolved graph, reclaimed on
success and failure. No DNS, selector or authentication cache is introduced.

## Milestone 3 implementation review

Public NetworkInterface, InterfaceAddress, Enumeration and the IPv6 scope and
reachability additions are independent compatible implementations under
`MIT OR Apache-2.0`. Private snapshot/cursor helpers, typed native operations,
POSIX interface queries and ICMP/TCP probing are original Ironwood mechanisms
under the same license. No additional OpenJDK derivation is selected. Review
Java 21 declarations and contracts and use new probes; upstream implementations
and tests are not translation templates. The existing parser remains separately
derived.

The selected result matrix in the migration plan is mandatory: fresh lookup
owners and owning enumerations capture flat structural snapshots; navigation,
metadata and traversal entries borrow that snapshot; fresh independent cursors
and mutable ArrayList results must be freed before their owner. Hardware bytes
are fresh independent results. IPv6 scope construction copies interface state.
Live flags, MTU and hardware queries preserve Java behavior. No ownership
registry, reference counting, implicit recursive array cleanup or cached global
snapshot is permitted. Native query storage and partial managed construction
must be reclaimed on failure.

Reachability uses deterministic syscall/clock fixtures for ICMP matching,
permission fallback, TCP success and immediate/asynchronous refusal, deadlines,
EINTR, interface/family/TTL selection, native errors and cleanup. Live booleans
are never acceptance assertions. Reference bounds must survive source, class
and archive inputs; primitive socket option specialization remains supported.
The implemented member matrix follows; measurements are in the M3 record.

The installed Java 21.0.10 source archive supplied the public contract review.
An initial documentation extraction also included adjacent implementation text
for NetworkInterface and InetAddress/Inet6Address; a corrected extraction
retains only attached public contracts. As with the planning review, this is
not a clean-room claim and does not change the independent classification.
No extracted implementation structure, prose or tests enter Ironwood source.
The pinned OpenJDK unix native NetworkInterface.c header, hardware-query and
numeric interface-scope behavior were inspected during the final native review.
Its Classpath Exception is explicit; no translation was made. The supported
glibc getifaddrs address layout was checked against [glibc 2.17 ifaddrs.c](https://github.com/bminor/glibc/blob/c758a6861537815c759cba2018a3b1abb1943842/sysdeps/unix/sysv/linux/ifaddrs.c)
and the 2.39 tag. The OS-provided
hardware length addresses storage beyond the eight-byte public sockaddr_ll
member, up to 24 bytes in that ABI. Only this ABI fact is used; no glibc source,
algorithm, comments or implementation structure is copied or distributed.

| M3 member family | Behavior and failures | Ownership/retention |
| --- | --- | --- |
| NetworkInterface.getByName(String), getByIndex(int), getByInetAddress(InetAddress) | Native structural query; absent result is null, null name/address is NPE, negative index is IAE, index zero is null; native query failures are SocketException | Fresh owning lookup result; caller inputs are not retained |
| NetworkInterface.getNetworkInterfaces() | Captures top-level interfaces; subinterfaces are traversed from their parent; native query failure is SocketException | Fresh owning enumeration; elements borrow its flat snapshot |
| getName(), getDisplayName(), getIndex(), isVirtual(), getParent() | Captured names/index and parent relationship; missing native index is -1; no parent is null | Names and parent are borrows, never independent owned results |
| getInetAddresses(), getSubInterfaces() | Independent fresh cursor positions over captured entries; exhausted nextElement throws NoSuchElementException | Free cursors before query owner; returned entries can outlive cursor cleanup under that owner |
| getInterfaceAddresses() | Fresh mutable ironwood.ds.ArrayList, including empty interfaces; exact initial capacity avoids population growth | List owns only its ordinary backing/helper storage; entries borrow the query; caller insertion keeps D107 loans |
| isUp(), isLoopback(), isPointToPoint(), supportsMulticast(), getMTU() | Live native query on each call, SocketException on failure; no cached-status substitution | Primitive results, no managed allocation on success |
| getHardwareAddress() | Live native hardware bytes or null; no Ethernet-only length restriction | Fresh independent byte array; temporary native/managed storage reclaimed |
| NetworkInterface.equals(Object), hashCode(), toString() | Name/address value comparison, name hash, Java-shaped description | Comparison borrows inputs; rendering returns fresh String |
| InterfaceAddress.getAddress(), getBroadcast(), getNetworkPrefixLength() | Captured address, nullable IPv4 broadcast and short prefix | Address/broadcast metadata borrow the query snapshot |
| InterfaceAddress.equals(Object), hashCode(), toString() | Address/broadcast/prefix value semantics and Java-shaped rendering | Inputs borrowed; rendered String fresh |
| Inet6Address.getByAddress(String, byte[], NetworkInterface), getScopedInterface() | Sixteen-byte IPv6 input; derive matching interface scope or UnknownHostException; null interface uses numeric unscoped factory | Factory copies retained bytes/text/interface graph; getter borrows its address-owned snapshot |
| InetAddress named-scope literals and copy() | Preserve named scope and scoped-interface metadata; unknown/incompatible scope is UnknownHostException | Fresh independent graphs; numeric-scope-only values keep null scoped-interface objects |
| InetAddress.isReachable(int), isReachable(NetworkInterface, int, int) | Negative timeout/TTL is IAE; null interface and zero/default values allowed; family mismatch false; best-effort ICMP then port-7 fallback with refusal true; ordinary native failures are IOExceptions | Receiver/interface borrowed only for the call; primitive result, no steady-path managed allocation |
| `Enumeration<E extends Object>`.hasMoreElements(), nextElement(), asIterator() | Reference-only declaration; inherited asIterator advances the same enumeration; exhaustion and remove follow Iterator contracts | Adapter is fresh and borrows the enumeration; custom enumeration effects remain source-proved |

Integer parameters retain ordinary Java widening, including byte/short/char.
No public constructors are added to NetworkInterface or InterfaceAddress;
streams, Java Collections duplication, serialization machinery, UDP, channels,
selectors and dynamic provider/reflection APIs remain omitted. The private
EnumerationIterator keeps the same explicit reference bound. General-purpose
Iterator and Iterable remain unbounded for primitive specialization.


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
snapshots and reachability were subsequently implemented in Milestone 3 above.
At the Milestone 2 checkpoint, numeric scopes and named literal scopes used
private OS queries without a placeholder public NetworkInterface.
Proxy constructors were subsequently implemented in Milestone 4; UDP, channel and boxed-option APIs
remain absent. The mandatory native I/O allocation/call budget from Milestone 1
continues to apply. Resolver and name materialization work is measured separately.

## Selected declaration and contract matrix

The following matrix and the Milestone 2 through 4 additions above describe implemented
members. Unlisted members are absent. Factory hooks are retained despite their
Java deprecation; the UDP-selecting overloads are absent. Normal
Java primitive widening remains admitted for integer parameters and must be
covered, as must inherited stream and Object consumers.

| Type and initial members | Behavioral contract and failures | Result and retained-input ownership |
| --- | --- | --- |
| `InetAddress.getByAddress(byte[])`, `getByAddress(String,byte[])`, binary IPv4/IPv6 values | Require 4 or 16 network-order bytes; invalid/null data maps to `UnknownHostException`. Recognize mapped IPv4 values. No resolver call. | Fresh value with copied primitive address bits and optional hostname; inputs remain caller-owned. |
| `InetAddress.getAddress()`, `copy()` | Independent network-order bytes; `copy()` is an explicit Ironwood extension. | Fresh array or fresh address, usable after source reclamation. |
| `InetAddress.parseLiteral(String)` (M6 prerequisite) | Ironwood extension selecting the existing NP3 grammar without DNS; null throws NPE, empty/malformed/ambiguous literals throw UnknownHostException, nonliteral names return null. Named scopes can query local interface metadata. | Fresh independent address on success; input is not retained. |
| Address equality, hash, classification, `getHostAddress()`, `toString()` | Java address value behavior and textual representation; scope and hostname do not affect equality/hash. Rendering does not initiate reverse lookup. | Rendered Strings fresh; numeric value access allocation-free. |
| `InetSocketAddress(int)`, `(InetAddress,int)`, `(String,int)`, `createUnresolved`, name/address/port getters, equality/hash/rendering | Port 0..65535; null address means default wildcard; null hostname fails. Lookup failure creates an unresolved endpoint; unresolved names compare ignoring case. Hash memoizes its primitive result. | Constructors copy retained input; getters borrow owned children/text. Endpoint never adopts caller address. |
| `Socket()`, protected `Socket(SocketImpl)` | Default or configured fresh implementation; injected null remains permitted for subclass acceptance initialization under the Java constructor contract. No UDP-selecting overload. | Default/factory implementation owned only if fresh and unpublished; explicit implementation borrowed. |
| `ServerSocket()`, protected `ServerSocket(SocketImpl)` | Unbound listener; injected null throws `NullPointerException`. | Same fresh/borrow distinction. |
| `Socket.bind(SocketAddress)`, `connect(SocketAddress[,int])` | Null bind means wildcard/port zero; null connect, unsupported address type, negative connect timeout fail before native activity. Reject repeat bind/connect and closed use; timeout 0 means infinite. | Copy retained endpoint values and labels; no input retention after successful built-in call. Unresolved native connect throws UnknownHostException; unresolved bind fails. |
| `ServerSocket.bind(SocketAddress[,int])` | Null means wildcard/port zero; nonpositive backlog chooses native default. Bound/closed misuse fails. | Copy retained endpoint values. |
| `ServerSocket.accept()`, protected final `implAccept(Socket)`; matching `SocketImpl` hooks | Accept into a newly created unbound destination. Timeout leaves listener usable. Native ownership transfers once, with cleanup before transfer on failure. | Public accept returns fresh independent socket. Protected hook borrows destination and its implementation. Listener owns neither result nor destination. |
| `getInputStream()`, `getOutputStream()`, `close()` | Stable cached views; unconnected/closed getters fail. Either stream close closes facade and implementation. Close is idempotent; listener closure does not close accepted sockets. | Facade owns views, which borrow implementation streams; delegate owns its own views. Close releases descriptor, free reclaims graph. |
| `shutdownInput()`, `shutdownOutput()` | Require an open connected socket and reject repeated shutdown of the same direction. Native ENOTCONN after peer/local EOF completes shutdown successfully, matching observed Java 21 behavior; other native errors remain failures. | No allocation or ownership change. |
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
Real DNS results exercise the proofs in Milestone 2; Milestone 3 adds actual
interface snapshots. Proxy APIs remain unselected.
The synthetic proofs must use ordinary alias rules and exercise both owning
query roots and owning enumeration roots.

| Later result | Required proof |
| --- | --- |
| Multi-address result | Fresh array and distinct fresh caller-owned elements; detach/free each element, then shallow-free array. Partial failure reclaims completed elements. Early termination, retained elements, replaced borrowed slots and duplicate aliases retain their actual ownership. |
| Interface snapshot | Flat structural storage owns private views; parent and child navigation borrows the same root. Lookup and enumeration roots both reclaim safely after views finish. Never expose hidden owner or create mutually owning interface objects. |
| Nested enumeration/list | Independent fresh cursor borrows snapshot; next element borrows view. Fresh mutable list contains borrowed entries and must be freed before snapshot. Caller insertion does not transfer ownership. |
| Non-stream borrows | Reject independent frees, escaped-borrow owner reclamation, and later use after root free, through helpers and interfaces. |
| `Proxy.socks5(endpoint,user,password)`, `Proxy.httpConnectBasic(endpoint,user,password)` | Implemented Ironwood extensions in Milestone 4. Fresh immutable proxy copies endpoint/octet arrays; connection copies configuration. No credential getter, ambient authentication or implicit protocol fallback. |

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
the proxy dependencies were re-audited for Milestone 4 above.
Networking adds no supported `System.getProperty` keys or general configuration
map. Milestone 5 adds OpenSSL dependency discovery, typed TLS operations and
conditional link flags under D153/D165.

## Implemented typed boundary and allocation ledger

The TCP boundary must retain its own typed operation identity after primitive
specialization and pruning. Native-link requirement collection consumes the
post-pruning typed program from Milestone 5, including exceptional paths. Do not
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
| bulk receive, per-call nonblocking receive and setup-only peek | descriptor, borrowed `byte[]`, offset, length |
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
parser carries its separate derived license. Milestone 4 introduces the separately
classified SOCKS helper, preserving the independent public-facade boundary.
[Milestone 3 evidence](NETWORKING_M3_VERIFICATION.md) adds interface/scoped
graphs, deterministic reachability, native cleanup and allocation measurements.
[Milestone 4 evidence](NETWORKING_M4_VERIFICATION.md) adds proxy contracts,
copied credentials, tunnel boundaries and deadlines. Separately selected
[Milestone 5 evidence](NETWORKING_M5_VERIFICATION.md) adds verified TLS,
trust/session policy, native cleanup and optional dependency packaging.
Milestone 6 was separately selected on 2026-09-17 and completed on 2026-09-18;
see [Milestone 6 evidence](NETWORKING_M6_VERIFICATION.md).

## Milestone 6 implementation review

The downloader is original application code under `projects/wget`, using RFC
3986 resolution and RFC 9110/9112 HTTP contracts, not an OpenJDK URI or HTTP
translation. Existing derived IP/SOCKS helpers keep their classification and
remain behind the original networking APIs. No new derived source was added.
The RFC documents were inspected directly for resolution, Location inheritance,
informational heads, field syntax, lengths, chunks and trailers.

Private components are an owned URL value, CLI configuration, one-hop
transport, bounded response reader, output sink and application driver. URL values
own copied text and numeric metadata; redirect results never borrow earlier
URLs or response buffers. Configuration copies explicit proxy credentials and
CA paths. Connections own Socket/TlsClient graphs; readers borrow stream views
and own their fixed input/line buffers. Sinks own output staging and optional
file streams. Close precedes free on every exit. File
output is opened only for a final successful response; stdout remains borrowed.
Cleanup failure must not replace an active primary error. Streaming partial
output is explicitly nontransactional.

One small library prerequisite is `InetAddress.parseLiteral(String)`, an
Ironwood-only, non-resolving entry point to the existing reviewed parser. It
returns a fresh address for a literal, null for a nonliteral name, and preserves
NP3 ambiguity and malformed IPv6 failures. Null is rejected; empty text is not
loopback. The downloader validates URI syntax first, then uses this helper to
avoid copying the IP algorithm or resolving DNS before target validation.

The reader uses fixed reusable byte/character storage, primitive framing state
and overflow-safe counters. Headers have a 64 KiB aggregate limit across up to
16 interim heads; trailers have 64 KiB; chunk lines have 8 KiB. Body/chunk loops
must add no managed allocations, with required transport-native allocations
measured separately. Configuration/URL/connection costs are outside that loop.
Focused parser, ownership, malformed-wire, redirect, proxy, TLS, allocation,
native-code/benchmark and relocated-package results are recorded in
[M6 verification](NETWORKING_M6_VERIFICATION.md). The pinned OpenSSL 3.5.8
`ssl/record/methods/tls13_meth.c` was inspected to attribute the existing M5
WPACKET per-record allocation cost; no implementation or comments were copied.
