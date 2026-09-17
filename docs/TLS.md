<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# TLS client and optional dependency

`ironwood.net.tls.TlsClient` is a single-connection, blocking TLS 1.2/1.3 client.
It verifies the chain, validity dates, server purpose and DNS/IP identity. DNS
identities use ASCII labels (including explicit A-labels), each at most 63
characters, without a trailing dot. Numeric IPv4/IPv6 identities verify IP SANs
and omit SNI. DNS names send SNI. This API is independent of JSSE.

Construct with `new TlsClient()`, `new TlsClient(proxy)`, or
`new TlsClient(proxy, pemPath)`. A null path selects the pinned bundled roots.
A custom PEM file replaces them. It must contain certificate PEM blocks with
optional blank lines or # comments; unreadable, empty or malformed files fail
before connecting. Supply a combined file explicitly when both sets are needed.
The client copies the proxy graph, credentials and path; caller values may be
freed afterward. It bypasses the global SocketImplFactory, using the selected
native transport and the existing explicit proxy protocols.
The native bridge is nested privately inside TlsClient. Applications, including
code in the same package, cannot obtain or supply raw TLS handles.

`connect(host, port, timeoutMillis)` resolves directly or sends the unresolved
hostname to a proxy. SOCKS4 requires a resolved route through
`connect(endpoint, peerIdentity, timeoutMillis)`. The latter verifies the supplied
identity independently of the route. Connect deadlines cover trust loading,
synchronous DNS, proxy negotiation and TLS handshake. DNS cannot be interrupted;
its elapsed time is charged when it returns. Zero means no deadline. A failed
connect closes the client; create another client to retry.

`setReadTimeout` and `setWriteTimeout` set per-operation deadlines in milliseconds,
including retries. A timeout or fatal record error closes the connection.
`getInputStream()` and `getOutputStream()` lazily allocate stable borrowed views.
Do not free them or use them after freeing their client. Caller byte arrays are
borrowed only for the call. Zero-length operations allocate nothing and do no
native I/O. `available()` reports already decrypted bytes. A TLS close notification
produces EOF; transport truncation raises SocketException.

Closing either stream closes the client. `close()` is idempotent, attempts one
nonblocking close notification, releases native TLS state and closes the TCP
descriptor. It does not wait for the peer's notification or promise notification
delivery on a blocked transport. Always close before `free client`; destruction
reclaims the managed graph and does not perform resource I/O. `isConnected()`
retains connection history, while `isClosed()` reports closure.

Trust excludes system/Keychain/JVM/OpenSSL default stores and ambient trust or
configuration variables. There is no revocation checking or fetch, no OCSP
staple validation, no session resumption/cache and no early data. Every connection
performs a full handshake. There are no verification-bypass switches. A valid
but revoked certificate can be accepted. See D158 and the networking plan.

## Prepare a source-tree SDK

Prepare the toolchain prerequisites in `packaging/idk-environment.yml` (LLVM
23.1.0, Perl 5.32.1, GNU Make 4.4.1, Python 3.14). On Linux add the matching
`sysroot_linux-aarch64=2.17` or `sysroot_linux-64=2.17`. On macOS install Command
Line Tools and select an Apple SDK with `SDKROOT` when needed. The deployment
target is macOS 11.0. Use the prerequisite prefix's bin directory on PATH.

```sh
python scripts/prepare-tls.py --llvm-home /path/to/toolchain --prefix toolchain/ironwood-tls
python scripts/prepare-tls.py --verify --llvm-home /path/to/toolchain --prefix toolchain/ironwood-tls
```

The script downloads only checksum-pinned source inputs, builds static archives,
and records tools, architecture, configuration, SDK/sysroot and every installed
file checksum. It refuses to replace an existing prefix. To use another prepared
prefix, set `IRONWOOD_TLS_HOME=/absolute/path/to/sdk`. The compiler never downloads
or searches system OpenSSL. An absent or invalid SDK affects only a final link
with retained typed TLS operations. Class-only builds and plain/pruned programs
require no SDK, even with an invalid override.

IDKs ship the SDK at `toolchain/ironwood-tls`, discovered relative to the
selected distribution. Source/tool-only packages ship the preparation recipe,
pins and adapter, and use an explicitly prepared prefix. Link order is the
adapter followed by libssl.a and libcrypto.a, with platform dead stripping.
The configuration uses the builtin default provider, no dynamic modules, no
configuration autoloading, no engines and no threads. It is for Ironwood's current
single-thread execution model. Applications require the normal system runtime,
with no shared OpenSSL or provider installation. Linux dependency compilation,
adapter compilation and linking all use the matching glibc 2.17 sysroot.

Keep the SDK's OpenSSL source archive, CA export/generated form, recipe and
license texts available when redistributing TLS executables. Preserve the
notices in `THIRD_PARTY_NOTICES.md`; see `LICENSE_MECHANICS` for the repository's
remaining mixed-license components. Patch/CA updates require new pins and
verification. M6 HTTP/HTTPS downloading and the N1 event loop remain unselected.
