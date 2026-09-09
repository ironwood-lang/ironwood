<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Testing Ironwood Code

Ironwood tests compile into native executables. The optional testing library is
included in every IDK as `lib/ironwood-testing.ironjar`.

## 1. Create the project layout

Keep production and test code in their conventional source roots:

```text
calculator/
├── src/
│   ├── main/
│   │   └── ironwood/
│   │       └── com/example/Calculator.iron
│   └── test/
│       └── ironwood/
│           └── com/example/CalculatorTests.iron
└── target/
```

## 2. Write the production code

Create `src/main/ironwood/com/example/Calculator.iron`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0

package com.example;

public class Calculator {

    private Calculator() {

    }

    public static int add(int left, int right) {

        return left + right;
    }

    public static boolean isEven(int value) {

        return value % 2 == 0;
    }
}
```

## 3. Write the tests

Create `src/test/ironwood/com/example/CalculatorTests.iron`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0

package com.example;

import ironwood.testing.Assertions;
import ironwood.testing.TestSuite;

public class CalculatorTests extends TestSuite {

    @Test
    private void addsNumbers() {

        Assertions.assertEquals(7, Calculator.add(3, 4));
    }

    @Test
    private void detectsEvenNumbers() {

        Assertions.assertTrue(Calculator.isEven(8));
        Assertions.assertFalse(Calculator.isEven(7));
    }
}
```

`@Test` is an Ironwood compiler directive, not a runtime annotation. A test
method must be a concrete, parameterless instance method that returns `void`.
The containing class must extend `TestSuite` and have a no-argument constructor.

## 4. Compile the tests

Install Ironwood as described in [Quick start](docs/QUICK_START.md), set
`IRONWOOD_HOME`, and run this command from the `calculator` directory:

```sh
mkdir -p target/test-classes

ironwoodc \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar" \
  --source-path "src/main/ironwood:src/test/ironwood" \
  -d target/test-classes \
  src/test/ironwood/com/example/CalculatorTests.iron
```

The combined source path lets the compiler find both the production class and
the test suite.

## 5. Link the native test executable

```sh
ironwoodc --link \
  -cp "$IRONWOOD_HOME/lib/ironwood-testing.ironjar:target/test-classes" \
  --main-class com.example.CalculatorTests \
  -o target/CalculatorTests -O3
```

The compiler generates the suite entry point automatically. No separate test
launcher or runtime discovery step is needed.

## 6. Run the tests

```sh
./target/CalculatorTests
```

A successful run prints:

```text
RUN - addsNumbers
ok - addsNumbers
RUN - detectsEvenNumbers
ok - detectsEvenNumbers
PASS: 2 passed, 0 skipped, 2 total
```

Tests run in source declaration order. The executable returns status `0` when
all tests pass and status `1` when any test fails. See
[Testing Ironwood code](docs/TESTING.md) for assertions, assumptions, lifecycle
hooks, failure testing, and the complete runner contract.
