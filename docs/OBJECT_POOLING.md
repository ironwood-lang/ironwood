<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Object pooling

ironwood.pool provides ArrayObjectPool, MultiArrayObjectPool and explicit
ObjectBuilder implementations. Pools reuse ordinary managed objects; collection
never checks an item back in, resets it or closes its resources. The source
[ObjectPool contract](../stdlib/src/main/ironwood/ironwood/pool/ObjectPool.iron)
is authoritative.

A builder must return a fresh unescaped value or null. Pools intentionally retain
every builder-created value, including checked-out items, and their backing
storage. get() builds only where needed; release() returns a checkout to its
originating pool. Return each checkout at most once and stop using it until the
next checkout. Native resources inside an item require explicit lifecycle calls.
These caller obligations add no identity registry, checkout-state scan or
per-operation synchronization. Known wrong-pool transfers retain compile-time
rejection; unknown provenance does not invent an ownership certificate.

ArrayObjectPool grows by its configured factor (default 1.75), rounded down with
at least one added slot. MultiArrayObjectPool retains segments and doubles new
segment length. Creation capacity is reserved before builder invocation, so
allocation failure preserves previously recorded values. Superseded arrays and
failed constructions use normal managed reachability. A live pool can retain
items and stale reference slots deliberately until reuse or named cold cleanup.

Warm checkout/release reuses storage with unchanged allocation expectations.
Observe reclamation at explicit phase boundaries after the entire pool graph
becomes unreachable. Do not collect inside critical loops or library operations
to repair former scope-exit live-count assertions. PoolRetentionTests and the
GC5/GC7 pool fixtures cover rooted survival, reuse, zero warmed allocations,
wrong-pool rejection and completed-phase collection.

A complete reuse example prints `Message 0`, `Message 1`, and `Message 2`:

```java
import ironwood.pool.ArrayObjectPool;
import ironwood.pool.ObjectBuilder;
import ironwood.pool.ObjectPool;

public class PoolExample {

    private static class StringBuilderFactory implements ObjectBuilder<StringBuilder> {
        @Override
        public StringBuilder newInstance() {
            return new StringBuilder(128);
        }
    }

    public static void main(String[] args) {
        StringBuilderFactory factory = new StringBuilderFactory();
        ObjectPool<StringBuilder> pool = new ArrayObjectPool<StringBuilder>(8, factory);
        for (int index = 0; index < 3; index++) {
            StringBuilder text = pool.get();
            try {
                text.setLength(0);
                text.append("Message ").append(index);
                System.out.println(text);
            } finally {
                pool.release(text);
            }
        }
    }
}
```

The earlier owning-pool/destructor proof reports remain frozen historical
evidence. [MEMORY](MEMORY.md) and [GC7](GC7_IMPLEMENTATION.md) supersede their
managed-reclamation premises while preserving pool protocols.
