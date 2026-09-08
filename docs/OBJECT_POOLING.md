# Object pooling

Instead of repeatedly allocating temporary objects, pool and reuse mutable
objects for maximum performance on hot paths. `StringBuilder` is a good example
because it can be cleared and reused while retaining its capacity.

```java
import ironwood.pool.ArrayObjectPool;
import ironwood.pool.ObjectBuilder;
import ironwood.pool.ObjectPool;

class StringBuilderFactory implements ObjectBuilder<StringBuilder> {

    @Override
    public StringBuilder newInstance() {

        return new StringBuilder(128);
    }
}

public class PoolExample {

    public static void main(String[] args) {

        StringBuilderFactory factory = new StringBuilderFactory();
        ObjectPool<StringBuilder> pool =
                new ArrayObjectPool<StringBuilder>(8, factory);

        for (int index = 0; index < 3; index++) {
            StringBuilder text = pool.get();
            text.setLength(0);
            text.append("Message ").append(index);

            String message = text.toString();
            System.out.println(message);
            free message;

            pool.release(text);
        }

        free pool;
        free factory;
    }
}
```

`get()` checks out an object and `release()` returns it to the same pool. Reset
mutable state before reuse, and do not use the object after release.
Freeing the pool destroys every `StringBuilder` it created. The factory is
borrowed by the pool, so free it separately after the pool.

See the [`ironwood.pool`](api/README.md) documentation (IronDocs) for the complete API and
contracts.
