<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Explicit deferred cleanup

This example demonstrates a temporary buffer, separate close/free operations,
and a pool checkout returned at each iteration's block exit. Its small resource
stand-in makes normal and failing cleanup deterministic, without files or
networking. `AutoCloseable` is an ordinary interface here; only the explicit
`defer resource.close();` schedules closure.

With `ironwoodc` on `PATH`, run from this directory:

```sh
./compile.sh
./link.sh
./run.sh
```

Compilation and the native `-O3` link both use `--unfreed=error`. The native
program prints exactly:

```text
normal: body -> close -> free
body failure: primary preserved, resource reclaimed
close failure: primary preserved, resource reclaimed
body + close failure: body primary, close secondary, resource reclaimed
pool: 3 checkouts, no new allocations, all storage reclaimed
```

The program exits with status `42` after all checks pass; statuses `1` through
`5` identify a failed scenario in that order. The run script checks the complete
output and status, then exits with status `0` on success.

## Cleanup order and exceptions

[DeferredCleanup.iron](src/main/ironwood/org/ironwood/deferredcleanup/DeferredCleanup.iron)
keeps each cleanup declaration beside its acquisition:

```java
byte[] buffer = new byte[2];
defer free buffer;
Resource resource = new Resource(failClose);
defer free resource;
defer resource.close();
```

Reached actions run in reverse order when `useResource` exits: close the
resource, reclaim its wrapper, then reclaim the buffer. The return value is
computed before cleanup. Only reached actions are active, so failed acquisition
does not schedule cleanup for a nonexistent resource. The compiler still proves
each free safe; neither form grants ownership or performs automatic reclamation.

The example records body, close and destructor events as `123`, and checks live
allocation counts after leaving the method. Close and free remain separate even
when close fails. A body failure stays primary; when close also fails, its
exception becomes the body's one secondary exception. With a successful body,
the close failure becomes primary and replaces the pending return. Both frees
finish before the caller's catch handles either failure.

Normal resource use returns live allocations to its baseline. Each failure
case also reclaims the resource and buffer, while accounting separately for
its one or two thrown exception objects. Caught exceptions escape and cannot
be reclaimed by safe `free` under the existing [memory rules](../../docs/MEMORY.md).
The three deliberate failure cases leave four exception objects live until
process termination; `defer` does not change that lifetime.

## Pool reuse and block boundaries

`reusePool` creates a one-item preloaded `ArrayObjectPool`, then uses
`defer pool.release(value);` directly inside its loop body. Each iteration
returns the item, including the iteration that executes `continue`. The next
checkout sees the previous `uses` value. There are exactly three checkouts and
no managed allocations during that loop.

The caller borrows pool items: do not free them individually or use them after
release. Exiting the surrounding block frees the pool and its owned item/arrays,
then its separately owned builder. The example verifies one item destruction
and a return to the pool scenario's live-allocation baseline. It uses an inner
block so those frees finish before the final live-count check.

The existing [resource example](../resources/src/main/ironwood/org/ironwood/resources/DeterministicResources.iron)
shows ordinary `try`/`finally`. This example is Milestone 2 step 2 of the
[defer plan](../../docs/DEFER_PLAN.md); project adoption is a separate step.
Performance parity is recorded in the [performance evidence](../../docs/DEFER_PERFORMANCE_VERIFICATION.md).

## Verification

Verified on 2026-09-19 with Oracle Java 21.0.1, LLVM 23.1.0 and macOS 26.6.2
ARM64, using the compiler from local baseline `564a56d`. From the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm
./examples/deferredcleanup/compile.sh
./examples/deferredcleanup/link.sh
./examples/deferredcleanup/run.sh
bash -n examples/deferredcleanup/compile.sh examples/deferredcleanup/link.sh examples/deferredcleanup/run.sh
./scripts/check-licenses.sh
git diff --check
```

The class-directory compile/link workflow passed with `--unfreed=error` and
`-O3`; the native run passed all five scenarios, exact output and exit `42`.
Shell syntax, license audit (five existing OpenJDK-derived files), documentation
links and diff checks passed. Discovery of immediate `examples/*/compile.sh`
files found 73 workflows, matching all 73 table entries and both sample totals
in the example index. This is a catalog check, not a full-example execution claim.

This checkpoint added only the example and documentation. Compiler, runtime,
standard-library and project sources were unchanged, so the accepted performance
evidence remained applicable. Project adoption, its verification and the final
Milestone 2 audit were pending at this checkpoint. See the
[final audit](../../docs/DEFER_FINAL_VERIFICATION.md) for current completion status.
