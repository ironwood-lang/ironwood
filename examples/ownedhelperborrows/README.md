# Owned helper borrow examples

This project gives each outcome in
[`docs/OWNED_HELPER_BORROWS.md`](../../docs/OWNED_HELPER_BORROWS.md) an isolated
Ironwood program. Every source file contains comments stating whether it must
run successfully or fail compilation.

Valid programs are compiled together, linked as separate native executables,
and expected to exit with status `42`. Invalid programs cannot be linked by
design: compilation must fail with the documented ownership diagnostic.

Run the complete catalog:

```console
$ ./compile.sh
$ ./link.sh
$ ./run-all.sh
```

Or use the single verification entry point:

```console
$ ./test.sh
```

Each `compileNN.sh` script compiles or compile-checks exactly one scenario.
Each `run-NN-*.sh` script then executes or independently compile-checks that
same scenario. The global `compile.sh` invokes all 21 numbered compile scripts.

| # | Scenario | Source result | Compiler | Runner |
| ---: | --- | --- | --- | --- |
| 1 | Fresh owner, never iterated | Native exit 42 | `compile01.sh` | `run-01-fresh-owner-free.sh` |
| 2 | Completed local traversal | Native exit 42 | `compile02.sh` | `run-02-completed-traversal.sh` |
| 3 | Iterator local remains in lexical scope but is dead | Native exit 42 | `compile03.sh` | `run-03-dead-iterator-local.sh` |
| 4 | Iterator use after owner destruction | Compile error | `compile04.sh` | `run-04-iterator-use-after-free.sh` |
| 5 | Caller tries to free borrowed iterator | Compile error | `compile05.sh` | `run-05-free-borrowed-iterator.sh` |
| 6 | Helper method returns the dependent borrow | Native exit 42 | `compile06.sh` | `run-06-helper-return-borrow.sh` |
| 7 | Owner is freed twice | Compile error | `compile07.sh` | `run-07-double-free-owner.sh` |
| 8 | Iterator escapes through a static field | Compile error | `compile08.sh` | `run-08-escaped-iterator.sh` |
| 9 | Polymorphic call may retain the iterator | Compile error | `compile09.sh` | `run-09-unknown-retaining-call.sh` |
| 10 | Closed-world-proven observing call | Native exit 42 | `compile10.sh` | `run-10-observing-call.sh` |
| 11 | Encapsulated constructor backlink | Native exit 42 | `compile11.sh` | `run-11-encapsulated-backlink.sh` |
| 12 | Helper can publish the owner backlink | Compile error | `compile12.sh` | `run-12-published-backlink.sh` |
| 13 | Branches preserve the same borrow identity | Native exit 42 | `compile13.sh` | `run-13-stable-control-flow.sh` |
| 14 | Branch merge obscures the borrow owner | Compile error | `compile14.sh` | `run-14-obscured-control-flow.sh` |
| 15 | Primitive iterator returns a nested holder borrow | Native exit 42 | `compile15.sh` | `run-15-primitive-nested-holder.sh` |
| 16 | Nested holder escapes | Compile error | `compile16.sh` | `run-16-escaped-nested-holder.sh` |
| 17 | Ordinary owner alias remains observable | Compile error | `compile17.sh` | `run-17-live-owner-alias.sh` |
| 18 | Inserted element survives collection destruction | Native exit 42 | `compile18.sh` | `run-18-inserted-element-survives.sh` |
| 19 | Caller frees an inserted element after list destruction | Native exit 42 | `compile19.sh` | `run-19-free-inserted-element.sh` |
| 20 | Constructor rollback destroys an installed helper | Native exit 42 | `compile20.sh` | `run-20-constructor-rollback.sh` |
| 21 | A second iterator call resets the shared iterator | Native exit 42 | `compile21.sh` | `run-21-reusable-iterator-reset.sh` |
