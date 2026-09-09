# Object pooling

Instead of repeatedly allocating temporary objects, pool and reuse mutable
objects for maximum performance on hot paths. `StringBuilder` is a good example
because it can be cleared and reused while retaining its capacity.

> Allocating objects on the hot path is never a good idea for performance.

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
            text.setLength(0); // this actually return the StringBuilder
            text.append("Message ").append(index);

            System.out.println(text); // does not allocate anything

            pool.release(text);
        }

        // The order here is important. Invert and it won't compile
        free pool;
        free factory;
    }
}
```

`get()` checks out an object and `release()` returns it to the pool.
You must reset the mutable state before reuse, and do not use the object after release.
Freeing the pool destroys every `StringBuilder` it created. The factory is
borrowed by the pool, so free it separately after the pool, never before or it won't compile.

See the [`ironwood.pool`](api/README.md) documentation (IronDocs) for the complete API and
contracts.
