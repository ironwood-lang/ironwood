<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/branding/ironwood-header-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="assets/branding/ironwood-header-light.png">
    <img alt="The Ironwood Programming Language. The Java experience, built for native performance. A rounded I with six leaves and circuit branches." src="assets/branding/ironwood-header-light.png" width="780">
  </picture>
</p>

Ironwood is an ahead-of-time compiled, object-oriented language for
high-performance native applications. It preserves the familiar Java syntax,
object model, and everyday APIs that Java developers already know, while
producing optimized native executables with no JVM, JIT, or garbage collector.
Ironwood is designed as a simpler, safer alternative to C++ without raw
pointers, borrow syntax, or an unfamiliar ownership-driven programming model.

> “Java gained its simplicity from the systematic removal of features from its predecessors.”
>
> **James Gosling and Henry McGilton**, *The Java Language Environment, 1995* (Source: [Oracle](https://www.oracle.com/java/technologies/simple-familiar.html))

> “My original goal was to build a C++ compiler that didn't have these problems.”
>
> **James Gosling**, *Interview with Bill Venners, 1999* (Source: [Artima](https://www.artima.com/articles/james-gosling-on-java-may-1999))

## Why Ironwood?

- **Performance:** Compile directly to native executables with LLVM. Closed-world
  compilation lets the compiler optimize across the whole program, with no JVM,
  JIT, or garbage collector in the generated application. Profile-guided
  optimization and a compiler-owned `@Inline` directive are on the roadmap so
  Ironwood keeps pushing the envelope for maximum performance.

- **Safety:** Reclaim memory explicitly with compiler-proven `free` when you need
  to, or reuse objects through the native `ironwood.pool` standard library. The
  compiler rejects reclamation it cannot prove safe, preventing dangling
  references, double frees, and use after free. Allocations remain valid until
  an accepted `free` reclaims them or the process ends.

- **Familiarity:** Use the Java programming model you already know: classes and
  interfaces, inheritance and polymorphism, Java-width primitives, generics,
  exceptions, packages, and ordinary nullable references. Ironwood is designed
  to make Java developers feel at home from the first line of code.

## Ironwood vs GraalVM Native Image

Both Ironwood and GraalVM Native Image produce closed-world, ahead-of-time compiled native executables, but they start from different places. GraalVM Native Image compiles existing Java bytecode and carries the JVM machinery needed to preserve Java semantics into the executable. Ironwood is a separate language designed for native compilation from the ground up, with Java-familiar syntax, objects, and APIs, but no JVM, JIT, Java bytecode, or garbage collector in the generated application.

> GraalVM makes Java applications native. Ironwood makes native development feel like Java.

## Quick Start

The step-by-step instructions to download, install and run are <a href="docs/QUICK_START.md">here</a>.

## Hello World

If this looks familiar, that is intentional. Ironwood keeps Java's package and
class structure while compiling your application into a native executable.
Each top-level class lives in its own `.iron` file beneath the standard
`src/main/ironwood` source root:

```text
hello/
└── src/
    └── main/
        └── ironwood/
            └── org/
                └── ironwood/
                    └── hello/
                        ├── Chatter.iron
                        └── Hello.iron
```

### Hello.iron

```java
package org.ironwood.hello;

public class Hello {

    public static void main(String[] args) {

        Chatter chatter = new Chatter();

        System.out.println("Hello " + chatter.getWord() + "!");
    }
}
```

### Chatter.iron

```java
package org.ironwood.hello;

import ironwood.util.Random;

class Chatter {

    private static final String[] WORDS = { "World", "Ironwood", "Developers" };

    private final Random rand = new Random();

    String getWord() {
        int index = this.rand.nextInt(WORDS.length);
        return WORDS[index];
    }
}
```

With `ironwoodc` on your `PATH`, run these commands from the `hello` directory:

```sh
# Compile Hello.iron; Chatter.iron is found and compiled automatically.
ironwoodc -sourcepath src/main/ironwood -d target/classes \
  src/main/ironwood/org/ironwood/hello/Hello.iron

# Link the closed-world application into an optimized native executable.
ironwoodc --link -cp target/classes \
  --main-class org.ironwood.hello.Hello -o target/hello -O3

# Run the native executable.
./target/hello
```

Each run prints one of the three greetings:

```text
Hello Ironwood!
```

The resulting `target/hello` is a native executable. It does not need the IDK (Ironwood Development Kit), Java, a JVM, a JIT, or LLVM when deployed.

## Memory Management

Ironwood does not have a garbage collector so memory is never reclaimed automatically. For our short `Hello World` program that wouldn't be a problem but let's change it to show how Ironwood handles memory explicitly.

> The Hello World example above compiles with warnings because `chatter` and the concatenated String are not freed. The default compiler option is `--unfreed=warn`. You can use `--unfreed=off` to silence these warnings or the stricter `--unfreed=error` to fail compilation with an error. Click [here](docs/MEMORY_MANAGEMENT.md) for more details.

### Hello.iron

```java
package org.ironwood.hello;

public class Hello {

    public static void main(String[] args) {

        Chatter chatter = new Chatter();

        String text = "Hello " + chatter.getWord() + "!";
        System.out.println(text);
        free text; // destroy object and reclaim the memory

        free chatter; // destroy object and reclaim the memory

        // System.out.println(chatter); // use after free NEVER compiles
    }
}
```

### Chatter.iron

```java
package org.ironwood.hello;

import ironwood.util.Random;

class Chatter {

    private static final String[] WORDS = { "World", "Ironwood", "Developers" };

    private final Random rand = new Random();

    String getWord() {
        int index = this.rand.nextInt(WORDS.length);
        return WORDS[index];
    }

    destructor {
      free rand; // destroy object and reclaim the memory
    }
}
```

The cleanup above demonstrates how the same code can manage memory deterministically in a long-running native application. When execution reaches `free chatter;`, the compiler first proves that the object cannot be observed through another live reference. The `Chatter` destructor then runs and reclaims its privately owned `Random` instance before
the `Chatter` object itself is reclaimed. A destructor does not run merely because an object becomes unreachable. It runs only as part of a `free` operation that the compiler has accepted and proven safe.

If the compiler cannot prove that either reclamation is safe, compilation
fails. There is no unsafe fallback, and a successfully freed reference cannot
be used again.


> Yes: no C++ dangling pointers or unpredictable references. The compiler won't allow it.

## Object Pooling

Ironwood ships with a native object pool (<a href="docs/api/README.md">`ironwood.pool`</a>), which is paramount for hot paths without allocation. You can <a href="docs/OBJECT_POOLING.md">click here</a> for more info.


## Ironwood Standard Library

Ironwood strives to provide a standard library as close as possible to the JDK, if not identical. The exception is the absence of _collections_ in favor of the highly optimized single-threaded data-structures from `ironwood.ds`. For example, you can use an `ArrayList` with the code below.

```java
import ironwood.ds.ArrayList;
import ironwood.util.Iterator;

public class ListExample {
	
	public static void main(String[] args) {
		
		ArrayList<String> list = new ArrayList<>();
		list.add("Hi1");
		list.add("Hi2");
		
		Iterator<String> iter = list.iterator();
		while(iter.hasNext()) {
			System.out.println(iter.next());
		}
	}
}
```

> For the IronDocs of the latest Ironwood Standard Library, you can <a href="docs/api/README.md">click here</a>.

## Performance Benchmark

For 80 million measured operations, Ironwood completed the workload in
1.011 seconds. It delivered 1.32 times the throughput of Oracle JDK 25 and
1.51 times the throughput of GraalVM 25 in these recorded runs. Both Ironwood and Java first completed the same unmeasured warmup pass of
8 million operations before the 80-million-operation measurement began.

| Implementation | Elapsed time | Throughput |
|---|---:|---:|
| Ironwood `-O3` | 1,011,361,526 ns (1.011 s) | 79.10 million ops/s |
| Oracle JDK 25 | 1,334,067,437 ns (1.334 s) | 59.97 million ops/s |
| GraalVM 25 | 1,525,100,964 ns (1.525 s) | 52.46 million ops/s |

Click [here](docs/BENCHMARK.md) for the full details.

## Writing Automated Tests

Inspired by JUnit 5, Ironwood ships with a test framework for automated tests. You can see an example <a href="docs/TESTING.md">here</a>.

## Generating API Docs

Like JavaDocs, Ironwood has IronDocs, which generates documentation in the Markdown format. You can <a href="docs/IRONDOCS_USER_GUIDE.md">click here</a> for more info.

## Packaging Libraries

You can package compiled Ironwood classes into one .ironjar file. This is useful for distributing a library or reusing it in another Ironwood project. You can <a href="docs/IRONJAR.md">click here</a> for more info.

## Transparent Java Bridge

Compile Ironwood code to a native library, and call it from a regular Java application as if it were an ordinary Java dependency. Total transparency with no handwritten bridge code, native declarations, or manual library loading will be required. **It is like Java calling Java.** For more details <a href="docs/JAVA_BRIDGE.md">click here</a>.

## Differences from Java

For the full list of Java features that Ironwood preserves, the mechanisms it adapts for native development, and the complexity it deliberately leaves behind, check <a href="docs/JAVA_EXCLUSIONS.md">this document</a>.

## Community and Feedback

Questions, use cases, design feedback, and contributions are welcome. Early feedback will help guide Ironwood’s direction.

- Use [GitHub Discussions](https://github.com/ironwood-lang/ironwood/discussions)
  for questions, ideas, and use cases.
- Open a [GitHub issue](https://github.com/ironwood-lang/ironwood/issues) for
  bugs and concrete proposals.
- See [CONTRIBUTING.md](CONTRIBUTING.md) before submitting a pull request.
- For private feedback or other inquiries, email
  [contact@ironwood-lang.org](mailto:contact@ironwood-lang.org).

## License

Ironwood is primarily distributed under the terms of both the MIT License and
the Apache License (Version 2.0), with portions covered by other licenses.

See [LICENSE](LICENSE), [LICENSE-APACHE](LICENSE-APACHE),
[LICENSE-MIT](LICENSE-MIT), and
[THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) for details.
