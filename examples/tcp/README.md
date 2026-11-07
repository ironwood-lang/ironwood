<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Numeric TCP loopback

This Milestone 1 example connects a client to a listener on `127.0.0.1` and an
OS-selected port, then echoes four binary bytes. It uses the implemented numeric
address API, primitive typed options, partial-read handling, read/connect/accept
timeouts, and half-close to signal EOF in each direction.

With `ironwoodc` on PATH, run from this directory:

```console
./compile.sh
./link.sh
./run.sh
```

The native program prints `TCP loopback exchanged 4 bytes in both directions.`
and exits with status `42`. The run script checks that status and exits with
status `0` on success. Compilation and the O3 link use the managed model.

The listener's backlog lets the local connection complete before `accept`, so
this small exchange needs no second process. Its four-byte request and response
are sent sequentially. General blocking clients and servers run in separate
processes; this pattern does not provide concurrent application progress.

The source closes the listener before using the accepted connection.
Stream views and endpoint snapshots follow managed reachability. Nested cleanup
explicitly closes descriptors even when an operation fails; collection is separate
from that resource lifecycle. See [TcpLoopback.iron](src/main/ironwood/org/ironwood/tcp/TcpLoopback.iron)
for the complete program and [the networking member matrix](../../docs/STDLIB_N1_SOURCE_REVIEW.md)
for the supported API. The [Milestone 2 example](../tcpnames/README.md) adds
DNS and destination constructors. The later blocking networking milestones are
complete; their historical evidence retains its original source/toolchain scope.
