# Testing Ironwood code

Ironwood provides a deliberately small `ironwood.testing` standard-library
module for tests written by Ironwood programmers. The module is part of the
standard-library project and ships with every IDK as
`lib/ironwood-testing.ironjar`.

Testing is a separate optional archive, not part of the implicit
`ironwood-stdlib.ironjar` production archive. A test selects it explicitly with
`-cp`; an ordinary application does not. This split keeps test APIs out of
production compilation while making the same supported framework available to
every client. Closed-world linking still removes unreachable code, but clients
should not need to depend on that optimization merely to separate production
and testing dependencies.

## Write and run a test manually

Suppose a project has this layout:

```text
my-counter/
  src/main/ironwood/com/example/Counter.iron
  src/test/ironwood/com/example/CounterTests.iron
```

The production class in `Counter.iron` is ordinary Ironwood source:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0

package com.example;

public final class Counter {

    private int value;

    public Counter() {

        this.value = 0;
    }

    public void increment() {

        this.value++;
    }

    public int value() {

        return this.value;
    }
}
```

The test class in `CounterTests.iron` extends `TestSuite` and marks each test
method with the compiler-owned `@Test` directive. The compiler creates the
dispatch and native entry point automatically:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0

package com.example;

import ironwood.testing.Assertions;
import ironwood.testing.TestSuite;

public final class CounterTests extends TestSuite {

    @Test
    private void startsAtZero() {

        Counter counter = new Counter();
        try {
            Assertions.assertEquals(0, counter.value());
        } finally {
            free counter;
        }
    }

    @Test
    private void increments() {

        Counter counter = new Counter();
        try {
            counter.increment();
            Assertions.assertEquals(1, counter.value());
        } finally {
            free counter;
        }
    }
}
```

From `my-counter`, point `IRONWOOD_HOME` at the extracted IDK. Compile the
test and the referenced production source into one test class directory:

```sh
export IRONWOOD_HOME=/absolute/path/to/extracted-ironwood-idk
mkdir -p target/test-classes
"$IRONWOOD_HOME/bin/ironwoodc" \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar" \
  --source-path "src/main/ironwood:src/test/ironwood" \
  -d target/test-classes \
  src/test/ironwood/com/example/CounterTests.iron
```

Link the test classes with the same testing archive, then run the native test
executable:

```sh
"$IRONWOOD_HOME/bin/ironwoodc" --link \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar:target/test-classes" \
  --main-class com.example.CounterTests \
  -o target/CounterTests -O3
./target/CounterTests
```

The result is deterministic and the process exits with status `0`:

```text
RUN - startsAtZero
ok - startsAtZero
RUN - increments
ok - increments
PASS: 2 passed, 0 skipped, 2 total
```

If any assertion or unexpected throwable fails, later tests still run and the
generated entry point returns status `1`. There is no separate test launcher or
runtime discovery step. The suite class itself is the native main class.

## Run the repository suites

From the repository root:

```sh
./scripts/test-stdlib.sh
```

The script builds the compiler and both standard-library archives, then compiles
the testing-module self-tests, migrated standard-library tests, and Ironwood-native
destruction tests together against `ironwood-testing.ironjar`. A repository-only
`StandardLibraryTests` entry point selects one suite's generated `main`.
The script links one native executable at `-O3` and starts six separate processes,
one per suite check, preserving independent runtime state and failure statuses.
This shares compilation without combining suite execution. `--skip-build` is
available to the compiler harness after it has built the current artifacts.

During execution, the script prints one compact `RUN` line for each suite. It
retains each suite's complete output and reports the labeled suite results and
aggregate case totals together at the end. Complete per-test output is printed
only for a suite that fails unexpectedly.

The focused compiler-harness entry is:

```sh
./scripts/test.sh --test 'standard-library testing module reports deterministic native results'
```

## API and runner model

The sources live under `stdlib/src/testing/ironwood/ironwood/testing` and build
into `compiler/build/ironwood-testing.ironjar`:

| Type | Purpose |
| --- | --- |
| `Assertions` | Fixed-arity truth, nullness, identity, equality, inequality, floating-delta, and failure checks. |
| `Assumptions` | Skips a case when an environmental precondition is false. |
| `TestSuite` | Base class for compiler-registered tests, with optional before-each and after-each hooks. |
| `TestRunner` | Immediate ordered execution, deterministic reporting, counters, and final process status. |
| `TestFailure`, `TestSkipped` | Internal control-flow failures caught by the runner. |

The API follows JUnit Jupiter's message-last argument order. Optional failure
messages follow the tested values, condition, or floating-point delta:

```java
Assertions.assertTrue(actualSize > 0, "size is positive");
Assertions.assertEquals(expectedSize, actualSize, "sizes match");
Assertions.assertEquals(expectedRate, actualRate, delta, "rates match");
Assumptions.assumeTrue(networkAvailable, "network is available");
```

`Assertions.fail(String)` keeps its single message argument. Message-first
compatibility overloads are not provided because they would make reference
assertions ambiguous. Most old message-first calls fail compilation. A call
whose message, expected value, and actual value are all strings remains
type-correct but is interpreted in message-last order, so migrations must
reorder those calls explicitly.

Mark each case with `@Test`. Tests execute in source declaration order on one
suite instance, and the method identifier is also its displayed name:

```java
public final class ArithmeticTests extends TestSuite {

    @Test
    private void twoPlusTwo() {

        Assertions.assertEquals(4, 2 + 2);
    }
}
```

The compiler synthesizes ordinary `run(int)` dispatch and
`main(String[] args)` methods. The generated `main` constructs one suite and
one runner, sends each method name and generated integer index to the runner,
and returns `finish()`. `finish()` returns `0` when there are no failures and
`1` otherwise. A skipped case does not fail the process. The runner prints no
timestamps, object identities, or discovery-dependent ordering. It catches
unexpected Ironwood throwables, reports the message, and continues with later
tests.

An `@Test` method must be a concrete, parameterless, non-generic instance
method returning `void`. Its class must be a concrete, non-generic, top-level
or static subclass of `TestSuite` with a no-argument constructor. A suite using
`@Test` cannot declare its own `run(int)` or `main(String[] args)` because those
methods belong to the compiler-generated harness. The compiler rejects
`@Test` anywhere else, including ordinary classes and interfaces.

`@Test` is a narrow compiler-owned directive, not a Java annotation. It has no
arguments, metadata, processing, runtime lookup, or reflection. The word
`Test` remains a legal identifier outside this exact directive spelling.

A suite may override `beforeEach(int)` and `afterEach(int)` with `@Override`.
The compiler-generated index is passed to both hooks. `afterEach` runs even
when setup or the test throws. The same suite instance is used for every case,
so hooks should reset any mutable state that must not cross test boundaries.

Ironwood also has no class-literal or lambda mechanism suitable for a
JUnit-shaped `assertThrows(Class, action)`. Expected failures use exact typed
control flow instead:

```java
boolean caught = false;
try {
    operation();
} catch (IllegalArgumentException expected) {
    caught = true;
}
if (!caught) Assertions.fail("expected IllegalArgumentException");
```

This preserves the required exception type. An untyped helper that accepted any
throwable would weaken the test.

`Assertions.assertEquals` has dedicated `char` overloads so character failures
are reported as character comparisons instead of widened integer comparisons.
`assertArrayEquals` remains outside the deliberately small surface; array tests
currently assert lengths and elements explicitly.

## Source-suite audit

The initial framework surface came from an audit of the original author's two
Java test suites at immutable revisions
`1ee68558593402cbbdbd94b8262643f8fcfc7a42` and
`075921aa80923ef6da624da551649a1782f6f286`. Their JUnit 4 argument order was an
input to that migration, but Ironwood's public testing API now uses the JUnit
Jupiter message-last convention.

| Area | Classes | Source methods | Expanded cases | Lifecycle and runner use |
| --- | ---: | ---: | ---: | --- |
| Object pools | 13 | 106 | 126 | 106 `@Test`, one `@Before`, two parameterized runners, 12 annotation-based expected exceptions |
| Data structures | 24 | 343 | 343 | 343 `@Test`, 11 `@Before`, 67 annotation-based expected exceptions, seven inline exception assertions |
| Total | 37 | 449 | 469 | Default JUnit 4 discovery and fresh instances per method |

Across both suites, the observed JUnit assertion calls were: 821 equality, 269
truth, 123 falsehood, 87 null, 80 non-null, 50 same-reference, 12
different-reference, 25 inequality, 16 explicit failure, seven inline expected
exception, and two assumption checks. There were no after-class or before-class
hooks, rules, ignored cases, categories, timeouts, custom ordering, or matcher
libraries.

The initial native migration contains 65 compiler-registered library cases:

- 24 cases for array and multi-array pools, including growth, preload, reuse,
  validation, null builder results, exact exception types, sizing, and warmed
  allocation behavior.
- 41 cases covering the complete portable behavior of the original primitive
  integer array-list suite except its removed garbage-collector hint, plus all
  18 fixed-domain byte-map tests and one Ironwood allocation-counter case.

The test sources are maintained as first-party Ironwood work under
`MIT OR Apache-2.0`, following the original author's direct contribution and
relicensing recorded by D029. They use neutral Ironwood package and type names.

## Ironwood-native destruction coverage

The original Java suites could not exercise Ironwood's `free` operation or
deterministic destructors. The repository therefore adds 34 compiler-registered
Ironwood-native cases alongside the 65 migrated cases:

- 10 pool cases cover empty pools, preloaded values, checked-out values,
  released and repeatedly reused values, backing-array growth, multi-array
  segment growth, exact-once payload destruction, nested payload storage, and
  the caller-owned builder lifetime.
- 24 data-structure cases cover array, linked, and hybrid lists; object,
  identity, linked, primitive-key, and copied-key maps; object, identity,
  linked, and primitive sets; unmodifiable list views; and reclamation after
  `ArrayList.removeFirst()` and set or hybrid-list rendering.

Every destruction case records `System.liveAllocationCount()` before the
operation and verifies that the expected allocations return to that baseline.
Static destructor counters separately prove exact destructor calls. The
data-structure cases also prove the ownership boundary: internal arrays,
iterators, primitive iterator holders, entry pools, and copied keys are
reclaimed, while caller-supplied elements, values, builders, and input keys
remain alive until the caller explicitly frees them.

## Remaining migration boundaries

Most data-structure cases can be migrated through ordinary source adaptation.
Primitive overloads replace boxing, typed catches replace class-token exception
assertions, and explicit arrays or helper methods replace varargs, lambdas, and
Java collection helpers.

The following tests cannot be copied literally without changing accepted
Ironwood decisions or weakening their meaning:

- The original pool suite covers three pool implementations and one private
  linked helper removed by D103. Tests specific to those types have no current
  target.
- Reflection-based pool construction and one data-structure declaration-origin
  test require runtime class objects and reflective member lookup, which are
  excluded.
- Pool tests that return independently created external values conflict with
  D104. Current pools accept returns only for values checked out from the same
  pool.
- Five data-structure methods call a garbage-collector hint deliberately
  omitted by D029. Ironwood allocation and live-allocation counters cover the
  relevant deterministic native behavior instead.
- Two constructor-configuration methods inspect direct byte buffers. The
  current byte buffer is heap-only.
- A few pool tests assert older exception-message text that differs from the
  current accepted API. They remain reported rather than weakened to type-only
  checks.

Current native compiler fixtures continue to cover ownership diagnostics,
allocation failure, destruction, and cross-artifact behavior. The framework
suites complement those fixtures with named public-API and destruction
behavior; they do not replace compiler-level proof tests.
