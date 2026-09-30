<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge basics

A Java application calls a native Ironwood counter, and the counter calls back
into a Java listener. There are just three source files:

- [Counter.iron](src/main/ironwood/org/ironwood/javabridge/basics/Counter.iron):
  holds a number and notifies its listener whenever `add()` changes it.
- [CounterListener.iron](src/main/ironwood/org/ironwood/javabridge/basics/CounterListener.iron):
  declares `onChanged(int value)`; the bridge generates the Java interface.
- [Main.java](src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java):
  implements that interface in Java, registers a listener, and calls the counter.

Each `add()` runs in native code and calls the Java listener synchronously on
the same thread, before returning. The two calls add 2 and then 3. The Java
`finally` block calls the generated `free()` method to reclaim the native counter
and release its listener registration. The Java listener itself is JVM-managed.

## Build, run and test

Use a JDK 21, 22 or 23, the pinned LLVM 23 and the
[native platform prerequisites](../../../docs/JAVA_BRIDGE_USAGE.md#build-and-run).
Put the checkout's `bin` directory and the JDK's `bin` directory on `PATH` so
`ironwoodc`, `javac` and `java` are available. Linux also needs the prepared bridge
support SDK described in that guide. From this directory:

```sh
./compile.sh
./link.sh
./run.sh
./test.sh
```

Expected program output:

```text
Java listener: 2
Java listener: 5
Counter total: 5
```

`compile.sh` compiles the Ironwood sources into `target/classes`.
`link.sh` creates `target/ironwood-basics.jar` with its paired native payload and
compiles the Java application against the generated API into
`target/consumer-classes`. `run.sh` launches the Java application.
`test.sh` checks the exact callback sequence and final value in ordinary and
checked-JNI JVM runs. Build commands are printed separately on stderr.

## Use it from another Java application

Add `target/ironwood-basics.jar` to your Java compile and runtime classpaths,
implement `CounterListener`, construct a `Counter` and call `setListener()`.
No handwritten JNI, `native` declarations or manual library loading are needed.
The [Java source](src/main/java/org/ironwood/javabridge/basicsconsumer/Main.java)
shows the complete integration and cleanup.

The generated JAR contains the native image and required support files; keep it
intact when copying it to another application. Consumers need only Java 21, 22
or 23 on the artifact's native target. Java 24+ remains unsupported. Keep counter
use and callbacks on the owning thread.

For more callback behavior, including replacement, reentry and exception
handling, see the [retained listener example](../listeners/README.md).
