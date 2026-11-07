<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Memory management

Ordinary native programs use one managed heap. Use explicit synchronous
`ironwood.lang.Memory.collect()` at chosen collection points. Allocation never
collects automatically; programs that keep allocating without collection can
exhaust memory. See [the complete contract](MEMORY.md).

```java
import ironwood.lang.Memory;

class ManagedExample {
    public static int main(String[] args) {
        Object value = new Object();
        Memory.collect();
        return value == null ? 1 : 0;
    }
}
```

References keep objects alive across collecting calls and deferred cleanup.
There is no source `free`, destructor, temporary destruction or constructor
rollback. Methods named free are ordinary methods. Remove obsolete `--unfreed`,
`--explain-rejected-free` options and `@SuppressUnfreed` directives.

Close files, sockets and native resources explicitly, usually with
`defer resource.close();`. Return pool checkouts explicitly with
`defer pool.release(value);`. Collection does neither. Captures happen immediately
and execute in reverse order at the end of their block, on every exit path.

Reference collection clear/remove operations drop obsolete references. Where a
reusable ArrayList intentionally retains its backing slots, use resetForReuse(),
then clearUnusedReferences() when that retention is no longer wanted. Primitive
clear stays cheap. Pools intentionally retain reusable entries.

Preparation OOM is catchable and reclaims nothing. Corrupt metadata refuses
before collection. Maintained consumers have migrated under [GC7](GC7_IMPLEMENTATION.md).
[GC8](GC8_IMPLEMENTATION.md) and its Linux follow-up qualify bounded critical
kernels and resident graphs; [GC9](GC9_LINUX_X86_64_DISTRIBUTION.md) qualifies
native/managed distribution on macOS arm64, Linux arm64 and Linux x86-64.
arm64e remains unqualified. See [GC10 acceptance](GC10_ACCEPTANCE.md) for exact
configuration limits and [Java Bridge usage](JAVA_BRIDGE_USAGE.md) for explicit
anchors and registrations. Java reachability alone does not root native objects.
