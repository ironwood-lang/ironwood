<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# TCP names and constructors

This Milestone 2 example resolves `localhost`, reclaims the returned address
array and its elements, then exchanges one byte using the listener and client
constructors. Name resolution uses the OS configuration and can block independently
of socket timeouts. No external server is needed.

With `ironwoodc` on PATH, run in this directory:

```console
./compile.sh
./link.sh
./run.sh
```

The program prints `Resolved localhost and exchanged byte 42.` and exits with
status `42`. The run script verifies that status. Compilation and the O3 link
use `--unfreed=error`. The numeric [TCP example](../tcp/README.md) additionally
demonstrates partial reads, binary payloads, deadlines and half-close.

The source distinguishes copied hostname inputs, borrowed cached name getters,
and caller-owned resolver results. It detaches each array element before freeing
it and closes sockets before reclaiming their graphs. This small sequential
loopback exchange does not provide concurrent application progress.
