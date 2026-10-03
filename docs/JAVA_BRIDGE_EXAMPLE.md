<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge, step by step

This walks through [examples/java-bridge/basics](../examples/java-bridge/basics):
a native Ironwood counter that a Java program calls, and then it calls a Java
listener back. Every command runs from that directory. The scripts there do the
same steps; this page shows what they run.

```sh
cd examples/java-bridge/basics
```

## 1. The Ironwood sources

Two files. Nothing in them is bridge-specific, in other words, total **transparency**!

`src/main/ironwood/org/ironwood/javabridge/basics/CounterListener.iron`:

```java
package org.ironwood.javabridge.basics;

public interface CounterListener {

    public void onChanged(int value);
}
```

`src/main/ironwood/org/ironwood/javabridge/basics/Counter.iron`:

```java
package org.ironwood.javabridge.basics;

public final class Counter {

    private int value;

    private CounterListener listener;

    public Counter() {

    }

    public void setListener(CounterListener listener) {

        this.listener = listener;
    }

    public void add(int amount) {

        this.value += amount;

        if (this.listener != null) this.listener.onChanged(this.value);
    }

    public int getValue() {

        return this.value;
    }
}
```

## 2. Compile the Ironwood code

```sh
ironwoodc --source-path src/main/ironwood -d target/classes --unfreed=error \
    src/main/ironwood/org/ironwood/javabridge/basics/*.iron
```

This is an ordinary Ironwood compilation. It writes `.ironclass` files under
`target/classes`. (`compile.sh`)

## 3. Generate the library

```sh
ironwoodc --java-bridge --export org.ironwood.javabridge.basics -cp target/classes \
    --license ../../../LICENSE-MIT --license ../../../LICENSE-APACHE \
    -O3 -o target/ironwood-basics.jar
```

`--export` names the package whose public API Java gets. Repeat it for each
package to export. The result, `target/ironwood-basics.jar`, is the library:
the generated Java class `Counter` and interface `CounterListener` in package
`org.ironwood.javabridge.basics`, the native library for this machine, the
loader, sources, Javadoc and license texts. It is a normal jar: nothing has to
be installed next to it. (first half of `link.sh`)

## 4. The Java program

`src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java`:

```java
package org.ironwood.javabridge.basicsconsumer;

import org.ironwood.javabridge.basics.Counter;
import org.ironwood.javabridge.basics.CounterListener;

public final class Main implements CounterListener {

    private Main() {

    }

    @Override
    public void onChanged(int value) {

        System.out.println("Java listener: " + value);
    }

    public static void main(String[] args) {

        Counter counter = new Counter();
        try {
            counter.setListener(new Main());
            counter.add(2);
            counter.add(3);
            System.out.println("Counter total: " + counter.getValue());
        } finally {
            counter.free();
        }
    }
}
```

Plain Java: it imports the two generated types from the jar, implements the
interface, constructs the class, and calls the generated `free()` when done.
There is no `native` keyword, no JNI and no `System.load`.

## 5. Compile it against the library

```sh
javac --release 21 -cp target/ironwood-basics.jar -d target/consumer-classes \
    src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java
```

The jar is the only thing on the compile classpath. (second half of `link.sh`)

## 6. Run

```sh
java --enable-native-access=ALL-UNNAMED \
    -cp target/ironwood-basics.jar:target/consumer-classes \
    org.ironwood.javabridge.basicsconsumer.Main
```

Output:

```text
Java listener: 2
Java listener: 5
Counter total: 5
```

The native library is extracted and loaded on the first use of `Counter`.
`--enable-native-access=ALL-UNNAMED` keeps Java 24 and 25 from printing their
restricted-method warning; Java 21 to 23 accept and ignore it. (`run.sh`)

`./test.sh` runs the program normally and under `-Xcheck:jni` and compares the
output with the lines above.

## 7. Use the library in your own program

1. Copy `target/ironwood-basics.jar` as it is. Do not unpack, shade or strip it.
2. Put it on the compile classpath and the runtime classpath, exactly as in
   steps 5 and 6. With Maven or Gradle, add it as a normal dependency.
3. Call the generated classes like Java classes. Call `free()` on every
   reclaimable native object when you are done with it; Java garbage
   collection never reclaims native memory.
4. Keep the same `--enable-native-access=ALL-UNNAMED` launch option. On the
   module path use `--enable-native-access=<Automatic-Module-Name>` from the
   jar manifest, and for an executable jar started with `java -jar` add
   `Enable-Native-Access: ALL-UNNAMED` to that jar's manifest.

The jar built on a Mac runs on macOS ARM64 only, and the one built on Linux on
that Linux architecture only. To ship one jar for several platforms, build on
each and combine the builds as described under
[assemble host builds](JAVA_BRIDGE_USAGE.md#assemble-host-builds).

## What to read next

- [examples/java-bridge](../examples/java-bridge/README.md): values and
  exceptions, listeners, arrays, byte views and generics, each with the same
  compile, link and run scripts.
- [JAVA_BRIDGE.md](JAVA_BRIDGE.md): what the bridge is and what it generates.
- [JAVA_BRIDGE_USAGE.md](JAVA_BRIDGE_USAGE.md): the supported API surface,
  ownership rules, platform prerequisites and the complete runtime contracts.
