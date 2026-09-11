<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Memory management

Ironwood has no garbage collector. Ordinary objects and arrays remain allocated
until explicitly freed or the process exits. Leaving scope, assigning `null`,
or losing the last reference does not free an object.

## Using `free`

Free an allocation after its last use. This complete program prints the argument
count and frees the String created by concatenation:

```java
public class Hello {

    public static void main(String[] args) {

        String message = "Argument count: " + args.length;
        System.out.println(message);
        free message;
    }
}
```

The compiler must prove that no other reference can observe an allocation after
it is freed. If it cannot prove safety, compilation fails. Using a freed object
or freeing it twice is also a compilation error. Assigning one reference to
another variable creates an alias, not a copy of the object.

Dynamic String concatenation creates a result that needs cleanup, as above.
String literals and constant concatenations are immortal and must not be freed.
When using a library result, follow its ownership contract: a borrowed reference
does not become yours to free merely because a method returned it.

## Objects, arrays, and cleanup

An accepted `free` runs an object's destructor before releasing its storage.
A destructor can free privately owned fields when the compiler proves it safe.
It does not run just because a variable leaves scope. Fields are not
automatically treated as owned objects and recursively freed.

Freeing an array releases the array itself, not objects or child arrays stored
in it. Collections and pools have their own ownership contracts; follow them
when deciding whether to free an element or return it to a pool.

Use `finally` when cleanup must run on both normal and exceptional paths.
Closing a resource and freeing its wrapper are separate operations;
`close()` does not imply `free`.

## Allocations that are not freed

The compiler reports known local allocations that are discarded, overwritten,
or left behind at scope exit without being freed:

| Option | Behavior |
| --- | --- |
| `--unfreed=warn` (default) | Report a warning; compilation may still succeed. |
| `--unfreed=off` | Disable these diagnostics. |
| `--unfreed=error` | Report the same findings as errors and fail compilation. |

These options apply to both source compilation and native linking. Each command
uses its own setting, so pass a non-default option to both when needed.

For one intentionally retained allocation, put `@SuppressUnfreed` before its
reference local declaration. The exemption follows the initializer's tracked
allocation through aliases and repeated declaration executions, including
under `--unfreed=error`. Later different allocations assigned to the variable
remain reportable. The directive survives class/archive files and native
linking, without changing cleanup or memory-safety checks. See the
[full suppression contract](MEMORY.md#per-allocation-suppression).

A short program may intentionally leave allocations until process exit. A
long-running program that repeatedly does so can exhaust memory. The check does
not prove that every allocation is eventually freed: returned or stored
objects and uncertain control flow can fall outside its coverage.

Every mode keeps mandatory memory-safety errors enabled. `--unfreed=off` neither
makes an unsafe `free` legal nor introduces automatic cleanup.

See [the detailed memory model](MEMORY.md) for ownership rules, diagnostic
limits, and compiler implementation details.
