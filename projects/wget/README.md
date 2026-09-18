<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Streaming HTTP/HTTPS downloader

`wget` downloads one HTTP(S) URL through the blocking networking APIs delivered
by the [networking migration](../../docs/NETWORKING_MIGRATION_PLAN.md). It is a
small application with private URL and HTTP helpers, not a public HTTP framework
or a command-line-compatible replacement for GNU Wget.

## Build and run

Select Ironwood's `bin` directory on `PATH`. A native link requires the pinned
[TLS SDK](../../docs/TLS.md), even when a particular invocation uses HTTP,
because this executable retains both protocols. An IDK includes that SDK.

```sh
projects/wget/compile.sh
projects/wget/link.sh
projects/wget/run.sh -O result.bin https://example.com/resource
projects/wget/run.sh http://127.0.0.1:8080/data > result.bin
```

Compilation creates separate `.ironclass` files; linking creates `target/wget`
with `-O3` and `--unfreed=error`. Build diagnostics go to stderr. `run.sh`
preserves the caller's working directory and stdout bytes. Output and credential
file paths are relative to that directory, including paths containing spaces.
`target/` is ignored build output.

| Option | Meaning |
| --- | --- |
| `-O FILE`, `--output FILE` | Stream to this file; `-` or omission means stdout. A final successful response truncates an existing file. |
| `--ca-bundle PEM` | Replace bundled trust roots with this PEM file. |
| `--proxy TYPE://HOST:PORT` | Explicit `socks4`, `socks5`, or `http` CONNECT route; no ambient proxy selection. |
| `--proxy-user-file FILE`, `--proxy-password-file FILE` | Paired SOCKS5 or HTTP Basic credentials as exact file octets. |
| `--socks4-user-file FILE` | Optional SOCKS4 USERID as exact file octets. |
| `--connect-timeout MS` | Default 10000; one budget for resolution, connect, proxy negotiation and TLS handshake. |
| `--head-timeout MS` | Default 30000; one budget across all informational and final response heads. |
| `--read-timeout MS` | Default 30000; timeout for each transport read during body/trailer decoding. |
| `--` | End option parsing. |
| `--help` | Print usage to stderr and exit successfully. |

Timeouts are nonnegative decimal milliseconds through 2147483647; zero disables
the selected timeout. DNS is synchronous: a resolver call can exceed the connect
budget, but the deadline is checked before continuing. Plain TCP request writes
use the existing blocking socket write contract; TLS request writes use the
head timeout. There is no overall download deadline or concurrent event loop.

Credential files are not text-decoded and no newline is stripped. SOCKS5
username/password and SOCKS4 USERID files are limited to 255 octets each; HTTP
Basic inputs to 4096 each. The proxy factories additionally enforce their
protocol-specific nonempty/NUL/colon constraints. Diagnostics do not print
credential contents. Credentials belong only to the configured proxy; origin
requests never contain `Authorization` or `Proxy-Authorization`.

## URL and HTTP behavior

The private parser accepts absolute HTTP/HTTPS URLs using ASCII URI text,
ASCII DNS labels (including supplied A-labels), NP3 IPv4 literals and bracketed
IPv6. Encode non-ASCII path/query bytes with percent escapes. It rejects userinfo,
raw spaces/controls, malformed escapes, unsupported schemes, invalid DNS labels,
invalid ports and scoped IPv6 URL syntax before connecting. It does not perform
IRI/IDNA conversion or browser-style URL repair. Literal parsing delegates to
`InetAddress.parseLiteral`, an Ironwood extension that never performs DNS.

GET requests use HTTP/1.1, the current authority as `Host`, `Connection: close`
and `Accept-Encoding: identity`. Empty paths become `/`. Absent and empty queries
remain distinct, encoded delimiters remain encoded, and fragments never go on
the wire. RFC 3986 reference resolution and HTTP fragment inheritance apply to
redirects. The client follows 301/302/303/307/308 with exactly one Location field
(an empty reference is valid), for at most 20 hops. Every hop uses GET and a new
connection. HTTP-to-HTTPS and host changes are allowed; HTTPS-to-HTTP is rejected,
including after an upgrade. TLS verifies the new identity at each hop. No cookies,
origin credentials, connection pooling or TLS session resumption are retained.

Up to 16 informational responses share the head deadline and 64 KiB aggregate
head budget. Status 101 fails. Status 204 is bodyless and prohibits length/coding
fields; 304 is bodyless metadata, not a successful download. Other non-2xx final
statuses fail without opening the output file. Unsupported content encodings and
transfer codings fail explicitly. For body-bearing responses:

- Transfer-Encoding plus Content-Length is rejected in either header order.
- Exactly one `chunked` coding is supported, with validated delimiters,
  overflow-safe sizes, ignored well-formed extensions and validated/discarded
  trailers. Chunk lines are bounded to 8 KiB and trailers separately to 64 KiB.
- Content-Length must contain identical valid decimal values across any
  repeated fields or comma lists. Exactly that many bytes are read.
- Otherwise the body ends at transport EOF. Reset, timeout and TLS truncation
  are errors. Plain TCP close-delimited HTTP cannot establish that the peer sent
  its entire intended representation.

Header names and coding tokens are case-insensitive; status syntax and CRLF are
validated. Obsolete folded field lines are unfolded as required for an HTTP
user agent. Trailers never replace framing, redirect or authentication decisions.
A 205 response is accepted only with empty content.

Only final-success body bytes are written. Exit status is 0 for success, 8 for
an unsuccessful HTTP status, 64 for invalid CLI/URI grammar, and 74 for I/O,
resolution, literal-address, protocol, TLS or output failure. A later failure can
leave partial output: this is streaming, not an atomic file replacement. Output
and transport close failures are reported; secondary cleanup failures preserve
the primary error. Process stdout is borrowed and never closed by the project.
A closed stdout pipe can terminate the process through the platform's SIGPIPE
behavior; it never reports success.

## Ownership and resource budget

A hop owns its transport and response reader; stream views are borrows. URL
components and redirect results are copied into independent owned values. At
most 21 bounded frames retain URL values; the preceding connection, header and
I/O buffers are closed and reclaimed before the next connection is constructed.
Configuration copies paths and owns its explicit proxy snapshot.

Response storage is fixed: 8 KiB input and 64 Ki UTF-16 line scratch; output adds
one reusable 8 KiB buffer. A redirect alone materializes a Location String.
Body size and chunk count add no managed or HTTP-layer native heap allocations.
OpenSSL's measured per-record framing allocations remain the separately recorded
M5 cost. Large contiguous writes bypass output staging; small chunks are batched.
No runtime ownership registry or compiler exemption is used.

## Local verification

```sh
projects/wget/test.sh
projects/wget/test.sh --skip-build --group protocol
```

The focused driver uses Python 3 and an `openssl` certificate-generation command,
local scripted peers, test-only native interposition and the selected LLVM
`llvm-objdump`. It covers URL resolution, framing, redirects, TLS/proxies,
cleanup, managed allocation failures, native-call counts and fixed workloads.
Groups are `protocol`, `tls`, `files`, `allocation`, and `cleanup`. Full compiler
suite execution is not needed. See [M6 evidence](../../docs/NETWORKING_M6_VERIFICATION.md).

TLS trust exclusions remain those in [TLS.md](../../docs/TLS.md): pinned bundled
roots or an explicit replacement, no ambient trust, revocation checking, client
certificates, general JSSE, session import/export, or early data. This application
completes the blocking migration only; N1's multi-client event-loop gate remains
pending.
