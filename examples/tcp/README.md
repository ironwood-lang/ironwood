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
status `0` on success. Compilation and the O3 link use `--unfreed=error`.

The listener's backlog lets the local connection complete before `accept`, so
this small exchange needs no second process. Its four-byte request and response
are sent sequentially. General blocking clients and servers run in separate
processes; this pattern does not provide concurrent application progress.

The source closes and frees the listener before using the accepted connection.
Stream getters return borrowed views, while endpoint snapshots are independently
owned. Nested cleanup closes descriptors and then frees socket graphs even when
an operation fails. See [TcpLoopback.iron](src/main/ironwood/org/ironwood/tcp/TcpLoopback.iron)
for the complete program and [the networking member matrix](../../docs/STDLIB_N1_SOURCE_REVIEW.md)
for the supported API. The [Milestone 2 example](../tcpnames/README.md) adds
DNS and destination constructors. Milestones 3 through 6 remain unselected.
