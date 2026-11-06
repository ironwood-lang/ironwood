<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explicit deferred cleanup

This example preserves ordered body/close failures and allocation-free pool
release/reuse. Source resources close through deferred void calls. Managed
buffers and wrappers have ordinary reachability; collection occurs only after
completed phases, never inside the pool loop.

Run compile.sh, link.sh and run.sh with the current SDK. The O3 executable exits
42 and prints:

```text
normal: body -> close
body failure: primary preserved, resource closed
close failure: primary preserved, resource closed
body + close failure: body primary, close secondary, resource closed
pool: 3 checkouts, no new allocations
```

Deferred operands are captured once and run in reverse registration order.
D051 preserves an existing primary and records close failures as secondaries.
Ordinary statements use nested finally when later cleanup must still run.
Pool release remains an explicit protocol operation and never closes an item.
The historical defer milestone reports retain their original free-dependent
measurements; GC5/GC7 supersede those cells. See [MEMORY](../../docs/MEMORY.md).
