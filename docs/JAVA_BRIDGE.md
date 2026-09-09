<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Native Ironwood for Java

> **Coming soon.** The Ironwood Java bridge is planned, but it is not yet
> implemented or available in an Ironwood release.

Write performance-sensitive code in Ironwood, compile it to native code, and
call it from a regular Java application as if it were an ordinary Java
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

ironwoodc --java-bridge \
    --export com.acme.pricing \
    -cp target/classes \
    -o target/pricing-bridge.jar \
    -O3
```

Repeat `--export` for every package that should be available to Java:

```sh
ironwoodc --java-bridge \
    --export com.acme.pricing \
    --export com.acme.risk \
    --export com.acme.orders \
    -cp target/classes \
    -o target/trading-bridge.jar \
    -O3
```

The public API from every exported package will appear in the same Java jar.
Types needed by those APIs will be included automatically.

The completed build will look like this:

```text
target/
├── classes/com/acme/pricing/PriceEngine.ironclass
└── pricing-bridge.jar
```

`pricing-bridge.jar` is a regular Java jar containing the generated Java API and
the native library built for the current platform. On macOS ARM64 it contains:

```text
pricing-bridge.jar
├── com/acme/pricing/PriceEngine.class
└── META-INF/ironwood/native/
    └── macos-arm64/libpricing.dylib
```

The Java application uses this jar directly. An `.ironjar` is not required.

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
memory just as an accepted `free` would. If `close()` is not called, the memory
will remain allocated until the process exits. The generated class will
implement `AutoCloseable`, so you can call `close()` directly or use
try-with-resources.

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
