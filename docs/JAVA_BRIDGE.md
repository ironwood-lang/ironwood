<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Ironwood for Java

> **Coming soon.** The Ironwood Java bridge is planned, but it is not yet
> implemented or available in an Ironwood release.

Write performance-sensitive code in Ironwood, compile it to a native library,
and call it from a regular Java application as if it were an ordinary Java
dependency. No handwritten bridge code, native declarations, or manual library
loading will be required.

## Quick start

### 1. Set up the project

Keep Ironwood and Java source in their familiar source roots:

```text
pricing/
├── src/main/ironwood/com/acme/pricing/PriceEngine.iron
└── src/main/java/com/acme/app/Main.java
```

### 2. Write an ordinary Ironwood API

```java
package com.acme.pricing;

public final class PriceEngine {

    public long notional(long quantity, long price) {

        return quantity * price;
    }
}
```

There is no bridge-specific syntax. When you export a package, its public classes,
constructors, methods, enums, interfaces, and exceptions will become the
Java-facing API.

### 3. Build the bridge

```sh
ironwoodc --source-path src/main/ironwood -d target/classes \
    src/main/ironwood/com/acme/pricing/*.iron

ironwoodc --link --shared --export com.acme.pricing -cp target/classes \
    -o target/libpricing.dylib --java-facade target/generated-java -O3
```

The example uses the macOS library name. Linux builds will produce
`libpricing.so` instead.

The planned packaging step will compile the generated Java facade and bundle it
with the platform library as an ordinary Java jar. Its final command spelling
has not been decided yet. The completed build will look like this:

```text
target/
├── classes/com/acme/pricing/PriceEngine.ironclass
├── generated-java/com/acme/pricing/PriceEngine.java
├── libpricing.dylib
└── pricing-bridge.jar
```

The `.ironclass` file is an intermediate Ironwood compiler artifact. The
`.dylib` or `.so` contains the native code. `pricing-bridge.jar` is the file a
Java application adds as a dependency, and it contains the generated Java API
and native library.

### 4. Use it like Java

```java
package com.acme.app;

import com.acme.pricing.PriceEngine;

public class Main {

    public static void main(String[] args) {

        PriceEngine engine = new PriceEngine();
        long value = engine.notional(250L, 1995L);
        System.out.println(value);
        engine.close();
    }
}
```

For objects owned by Java, `close()` will be the Java spelling of Ironwood
`free`. It will run the Ironwood destructor, if present, and reclaim the native
memory just as an accepted `free` would. The generated class will implement
`AutoCloseable`, so try-with-resources will remain available when preferred.

Compile and run the Java application against the generated jar:

```sh
mkdir -p target/app-classes

javac -cp target/pricing-bridge.jar -d target/app-classes \
    src/main/java/com/acme/app/Main.java

java -cp target/pricing-bridge.jar:target/app-classes com.acme.app.Main
```

The program will print `498750`. Maven and Gradle projects will use the same jar
as a normal dependency.

The library will load automatically when first used. Application code will not
need `System.loadLibrary`, JNI wrappers, C headers, or platform-specific call
sites.

## The promise

- Write Java-shaped Ironwood instead of glue code.
- Keep the Java API aligned with the Ironwood API.
- Move selected application hot paths to ahead-of-time native code.
- Distribute the result as a familiar Java dependency.
- Keep Java callbacks and exceptions working across the boundary.

For the full design proposal, see
[`IRONWOOD_JAVA_BRIDGE.md`](IRONWOOD_JAVA_BRIDGE.md).
