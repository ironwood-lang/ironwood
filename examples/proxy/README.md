<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explicit proxy tunnels

This Milestone 4 example connects through authenticated SOCKS5 and HTTP CONNECT
using local scripted peers. It passes the unresolved target `service.invalid:80`
to the proxy, exchanges byte 42, checks the target endpoint, and reclaims the
connection and all configuration inputs. Each native run exits with status 42.

With `ironwoodc` on PATH and Python 3 available:

```sh
./compile.sh
./link.sh
./run.sh
```

The demonstration username/password are `demo`/`pass`. Proxy construction copies
their already-encoded bytes; Socket(Proxy) copies them again, so the original
arrays, endpoint and proxy are freed before connect. The proxy alone receives
the credentials. This plain TCP example does not encrypt proxy credentials or
tunnel data. TLS requires separate selection in Milestone 5.

Ordinary `new Proxy(Proxy.Type.SOCKS, endpoint)` uses credential-free SOCKS5.
`Proxy.socks(endpoint, Proxy.SocksVersion.V4)` explicitly selects SOCKS4 with an
empty user ID; `Proxy.socks4(endpoint, userIdBytes)` supplies one. SOCKS4 needs
an already resolved IPv4 target. There is no automatic protocol fallback,
environment proxy discovery, OS username, or authentication callback.
