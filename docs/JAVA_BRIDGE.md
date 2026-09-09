<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Ironwood for Java

> **Coming soon.** The Ironwood Java bridge is planned, but it is not yet
> implemented or available in an Ironwood release.

Write performance-sensitive code in Ironwood, compile it to a native library,
and call it from a regular Java application as if it were an ordinary Java
dependency. No handwritten bridge code, native declarations, or manual library
loading will be required.

## Quick start

### 1. Write an ordinary Ironwood API

```ironwood
package com.acme.pricing;

public final class PriceEngine {

    public long notional(long quantity, long price) {

        return quantity * price;
    }
}
```

There is no bridge-specific syntax. Export a package, and its public classes,
constructors, methods, enums, interfaces, and exceptions will become the
Java-facing API.

### 2. Build the Java bridge

```sh
ironwoodc --source-path src/main/ironwood -d target/classes \
    src/main/ironwood/com/acme/pricing/*.iron

ironwoodc --link --shared --export com.acme.pricing -cp target/classes \
    -o target/libpricing --java-facade target/generated-java -O3
```

The planned tooling will package the Java API and native library together for
use from Maven, Gradle, or a direct class path.

### 3. Use it like Java

```java
import com.acme.pricing.PriceEngine;

try (PriceEngine engine = new PriceEngine()) {
    long value = engine.notional(250L, 1995L);
    System.out.println(value);
}
```

Imports, constructors, method calls, enums, callbacks, checked exceptions, and
stack traces will feel familiar to Java developers. Owned native objects will
implement `AutoCloseable`, so try-with-resources will handle cleanup naturally.

The library will load automatically when first used. Application code will not
need `System.loadLibrary`, JNI wrappers, C headers, or platform-specific call
sites.

## The promise

- Write Java-shaped Ironwood instead of glue code.
- Keep the Java API aligned with the Ironwood API.
- Move selected application hot paths to ahead-of-time native code.
- Distribute the result as a familiar Java dependency.
- Keep Java callbacks and exceptions working across the boundary.

For the design proposal and current open questions, see
[`IRONWOOD_JAVA_BRIDGE.md`](IRONWOOD_JAVA_BRIDGE.md).
