# HelloEclipse

A Hello World sized to show what is different about Ironwood, and the project
used to exercise the Eclipse plugin in `ide/`.

It prints a greeting, reclaims everything it allocated, and checks that claim
against the live allocation count before returning. A leak fails the run with
status 2 rather than passing quietly, which is the part a Hello World for a
language without a collector has to demonstrate.

```console
$ ./compile.sh
$ ./link.sh
$ ./run.sh
Hello, world!
$ ./run.sh Eclipse
Hello, Eclipse!
$ ./test.sh
...
PASS: 6 passed, 0 skipped, 6 total
```

The program returns `0` on success and `2` if any allocation was still live at
the end.

## The two classes

`Greeting` holds a name and appends greetings built from it. `HelloEclipse`
is the entry point that drives it.

`Greeting` appends into a `StringBuilder` the caller provides rather than
returning a `String`. That is not a style choice. The compiler proves a `free`
only for an allocation made by `new` in the same method or returned by a factory
it knows, so a `String` returned from `Greeting` could never be reclaimed by its
caller. Taking caller storage leaves ownership where it started and keeps the
whole program reclaimable.

Two details in the code are worth knowing before writing Ironwood of your own,
because both are easy to hit and the error messages only make sense once seen:

- **Chaining `append` defeats the escape analysis.** `append` returns `this`, so
  `out.append(a).append(b)` makes the builder flow to a return value and the
  caller can no longer free it. The same appends written as separate statements
  keep the builder provably owned.
- **Passing a value to `Assertions.assertEquals` escapes it.** That overload
  takes `Object` and compares with a virtual `equals` the compiler cannot
  devirtualize. `GreetingTests` therefore compares through `String.equals`, with
  the built value as the receiver, frees it, and only then asserts.

## Tests

`src/test/ironwood` holds `GreetingTests`, a `TestSuite` whose cases are marked
with the built-in `@Test` directive. The compiler generates the dispatch and the
native entry point, so the suite class is its own main class and there is no
separate runner. `./test.sh` compiles, links, and runs it.

## In Eclipse

The project carries the Ironwood nature, so importing it through
File > Open Projects from File System is enough to have it build, report errors
in the Problems view, and run through Run As > Ironwood Application.

Set Preferences > Ironwood > Ironwood home first, or the language server and
builder cannot find the compiler. See [`ide/README.md`](../../ide/README.md).

To run the tests from Eclipse, create an Ironwood Application launch whose main
class is `org.ironwood.helloeclipse.GreetingTests`. A suite is an ordinary main
class, so nothing test-specific is needed to launch it.
