<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Memory management

Ironwood has no garbage collector. Ordinary objects and arrays remain allocated
until explicitly freed or the process exits. Merely leaving scope, assigning
`null`, or losing the last reference does not free an object.

## Ownership and borrowing

Ironwood does not support general ownership transfer for an existing allocation.
Once ownership is established, passing or storing a reference elsewhere does not
give ownership to the recipient. When the compiler can prove the relationship,
it tracks the receiving reference as a borrow; the original owner remains
responsible for reclaiming the allocation with `free` after all observable
borrows and aliases have ended.

An object can own children that it creates internally, and a method can return a
proven fresh result for its caller to own. Neither case transfers an existing
caller-owned allocation to another object. Collections and ordinary fields
therefore borrow inserted caller objects unless a documented specialized
contract says otherwise.

## Using `free`

Free a named allocation after its last use. This complete program binds the
String created by concatenation to a local, prints it, and frees it:

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

An allocation that nothing names, stores, or moves on by the end of its
statement is an unnamed temporary, and the compiler reclaims it for you at the
end of that statement, once it proves nothing else can observe it. `System.out.println("Argument count: " +
args.length);` therefore needs no local and no `free`; the same holds for
`use(new Keeper())`. Naming the allocation, as above, keeps it until you free
it. See [unnamed temporaries](MEMORY.md#unnamed-temporaries) for the rule and
its limits.

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

Use ordinary `finally` or explicit `defer` when cleanup must run on both normal
and exceptional paths. `defer free name;` schedules the same compiler-proven
reclamation of an owned local at the end of its explicit block. That binding
cannot be reassigned until cleanup; the object can still be used and mutated
while live. A deferred void call such as `defer resource.close();` captures its
receiver and arguments once when reached, then invokes it at block exit.

Only reached actions run, in reverse declaration order, including on `return`,
exceptions and transfers out of the block. To close before freeing, declare
`defer free resource;` before `defer resource.close();`. Separate actions still
run if an earlier cleanup fails: the first exception stays primary and later
failures become secondary. Closing and freeing remain separate operations;
`close()` does not imply `free`. Neither form grants ownership or weakens safety.
See the [full defer contract](LANGUAGE.md#explicit-deferred-cleanup-milestone-1)
and [runnable example](../examples/deferredcleanup/README.md), including pool
release and cleanup failures.

## Allocations that are not freed

The compiler reports known named allocations that are discarded, overwritten,
or left behind at scope exit without being freed, and unnamed temporaries it
could not reclaim:

| Option | Behavior |
| --- | --- |
| `--unfreed=warn` (default) | Report a warning; compilation may still succeed. |
| `--unfreed=off` | Disable these diagnostics. |
| `--unfreed=error` | Report the same findings as errors and fail compilation. |

These options apply to both source compilation and native linking. Each command
uses its own setting, so pass a non-default option to both when needed.

If the compiler rejects a `free`, retry that compile or link with the bare
`--explain-rejected-free` flag to request source-related notes. The flag is off
by default and applies only to that command. It never makes an unsafe `free`
legal, and `--unfreed` settings do not suppress these safety errors. Current
evidence and limits are in [rejected-free explanations](MEMORY.md#rejected-free-explanations).
See the [compile and link examples](EXPLAIN_REJECTED_FREE_EXAMPLES.md) for
source files, commands, and the resulting diagnostics.

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
