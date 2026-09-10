<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Testing Ironwood Code

Ironwood compiles test suites into native executables. The deliberately small
`ironwood.testing` module ships in every IDK as
`lib/ironwood-testing.ironjar`.

The testing archive is separate from the production standard library. Tests
select it explicitly with `-cp`; ordinary applications do not include it.

## Write a test suite

Put production code under `src/main/ironwood` and test code under
`src/test/ironwood`. A test class extends `TestSuite`, and each test method uses
the compiler-owned `@Test` directive:

```java
// src/test/ironwood/com/example/ArithmeticTests.iron
package com.example;

import ironwood.testing.Assertions;
import ironwood.testing.TestSuite;

public final class ArithmeticTests extends TestSuite {

    @Test
    private void twoPlusTwo() {

        Assertions.assertEquals(4, 2 + 2);
    }

    @Test
    private void integerDivision() {

        Assertions.assertEquals(3, 7 / 2);
    }
}
```

An `@Test` method must be a concrete, parameterless, non-generic instance
method returning `void`. Its suite must be a concrete, non-generic, top-level
or static subclass of `TestSuite` with a no-argument constructor.

`@Test` is an Ironwood directive, not a Java annotation. It has no arguments,
reflection, or runtime discovery. The compiler generates the suite's
`run(int)` dispatcher and `main(String[] args)` entry point, so a suite using
`@Test` cannot declare either method itself.

Tests run in source order on one suite instance. The runner continues after a
failure or skip, exits with status `0` when no test failed, and exits with
status `1` otherwise. After printing the summary, the generated entry point
frees its `TestRunner` before returning that status. Reporting strings are
freed after printing. The suite instance may escape through user callbacks
and remains process-lived; tests manage their own allocations explicitly.

## Assertions and assumptions

`Assertions` provides these checks:

| Category | Methods |
| --- | --- |
| Truth | `assertTrue`, `assertFalse` |
| Nullness | `assertNull`, `assertNotNull` |
| Identity | `assertSame`, `assertNotSame` |
| Equality | `assertEquals` for booleans, integers, characters, floating-point values with a delta, and objects |
| Inequality | `assertNotEquals` for integers and objects |
| Explicit failure | `fail()` and `fail(String)` |

Optional messages are always the last argument:

```java
Assertions.assertTrue(actualSize > 0, "size is positive");
Assertions.assertEquals(expectedSize, actualSize, "sizes match");
Assertions.assertEquals(expectedRate, actualRate, delta, "rates match");
```

`Assumptions.assumeTrue(condition)` skips the current test when the condition
is false. It also accepts an optional message as its last argument. A skipped
test does not fail the process.

Expected exceptions use typed `try`/`catch` control flow:

```java
boolean caught = false;
try {
    operation();
} catch (IllegalArgumentException expected) {
    caught = true;
}
if (!caught) Assertions.fail("expected IllegalArgumentException");
```

There is currently no `assertThrows` or `assertArrayEquals`. Assert array
lengths and elements explicitly.

## Lifecycle hooks

A suite may override `beforeEach(int)` and `afterEach(int)`. The generated test
index is passed to both hooks. `afterEach` runs even when setup or the test
throws. Because every test uses the same suite instance, reset mutable suite
state in these hooks when tests must be isolated.

## Compile and run

From the project root, point `IRONWOOD_HOME` at the extracted IDK, compile the
test source, link its generated entry point, and run it:

```sh
export IRONWOOD_HOME=/absolute/path/to/ironwood
mkdir -p target/test-classes

"$IRONWOOD_HOME/bin/ironwoodc" \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar" \
  --source-path "src/main/ironwood:src/test/ironwood" \
  -d target/test-classes \
  src/test/ironwood/com/example/ArithmeticTests.iron

"$IRONWOOD_HOME/bin/ironwoodc" --link \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar:target/test-classes" \
  --main-class com.example.ArithmeticTests \
  -o target/ArithmeticTests -O3

./target/ArithmeticTests
```

## Run the repository suites

From the Ironwood repository root, run the standard-library tests with:

```sh
./scripts/test-stdlib.sh
```

For the focused compiler-harness check of the testing module, run:

```sh
./scripts/test.sh --test 'standard-library testing module reports deterministic native results'
```

Compiler diagnostics remain visible on standard error. The compiler-harness
check compares the script's standard output with the expected suite summary,
so warnings do not change the test result. Compilation errors and failing
native checks still fail the script.
