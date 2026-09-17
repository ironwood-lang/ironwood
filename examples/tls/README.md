<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Verified TLS echo

This M5 example uses `TlsClient`, a supplied local CA, mandatory hostname
verification, borrowed streams and explicit close/free. It exchanges a byte and
a binary buffer with a loopback TLS peer and returns 42. No Internet access is
used. Disposable certificates and keys stay in ignored `target/`.

Put `ironwoodc` on PATH, prepare/select the pinned TLS SDK as described in
[the TLS guide](../../docs/TLS.md), and install Python 3 with ssl plus an OpenSSL
CLI for the local test peer. The CLI is a fixture dependency, not an application
runtime dependency. Then run:

```sh
./compile.sh
./link.sh
./run.sh
```

Class-only compilation needs no TLS SDK. The native link does. IDKs discover
their bundled SDK automatically; tool-only packages need `IRONWOOD_TLS_HOME`.
