<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Pool release helper regression

## What failed

On 2026-09-20, validating the README's pooled `StringBuilder` example exposed
a false-positive escape diagnostic. Extracting a checkout and return into a
helper prevented the caller from reclaiming its pool:

```java
static void use(ArrayObjectPool<StringBuilder> pool) {

    StringBuilder sb = pool.get();
    pool.release(sb);
}
```

After `use(pool)`, `free pool` failed with
`allocation escapes through argument 1 of method 'use'`. The factory's cleanup
then failed because the unreclaimed pool still borrowed it. The same operations
written directly in the caller were accepted. Neither `defer` nor a returned
String was necessary to reproduce the rejection.

Ownership never changes here. The caller owns the pool; the pool owns its
builder-created values; the helper borrows references. Returning a checkout
only makes that value available for reuse. A helper that publishes either
reference must still prevent unsafe reclamation by the original owner.

## Historical evidence

Historical compiler Java sources and their matching standard-library sources
were extracted with `git archive` into temporary directories outside the
checkout. Each compiler was built with Java 21 using the repository's
`javac --release 21 -encoding UTF-8 -Xlint:all -Werror` settings. Tests used
`IRONWOOD_STDLIB_HOME` pointing at the matching snapshot, avoiding discovery of
the current compiler's standard-library archives. No branch or worktree was
created, and no history was rewritten.

| Revision | Date | Inline checkout/return | Same-pool helper | Wrong-pool helper |
| --- | --- | --- | --- | --- |
| `43b2e3d`, initial source | 2026-09-08 | Accepted | Accepted | Not tested |
| `902f127`, parent of networking M1 | 2026-09-14 | Accepted | Accepted | Incorrectly accepted |
| `ddaaaec`, networking M1 | 2026-09-14 | Accepted | Rejected | Rejected |
| `1e249ee`, immediately before defer implementation | 2026-09-19 | Accepted | Rejected | Not tested |

The first defer implementation is `3ea065f`, dated 2026-09-19. It did not
introduce this regression. Both `902f127` and `ddaaaec` rejected a helper that
explicitly published the checked-out item to a static field.

## Why it happened

Networking M1 made escape and symbolic return analysis consume call targets
resolved from provisional typed IR. This correctly preserved effects from
actual overloads, interface implementations, and constructor bindings instead
of relying only on source-level lookup.

`PoolSemantics.symbolic` already conservatively marked both the receiver and
argument of `release` as escaping. Direct call lowering could recognize the
originating pool, but helper summaries lacked the equivalent relational proof.
Using the accurately bound pool target propagated that conservative fallback
to the caller. The change exposed a missing proof at the boundary between
call binding, pool identities, and method summaries.

Older acceptance was not a sufficient correctness oracle: the same old compiler
also accepted returning an item to a different pool through a helper. Reverting
typed call binding or declaring all pool releases non-retaining would restore
that over-permissive behavior.

The README's String-returning variation exposed a related precision issue:
some consumers still used coarse return-inclusive escape flags even when
symbolic analysis proved a fresh independent result. Such calls now use the
separate non-return publication effects. Returning a copy does not publish its
input; publishing an input and also returning a fresh copy still does.

## Fix and proof boundary

`PoolReleaseAnalysis` uses provisional typed SSA operands to prove that a
bundled `release` receives a value checked out from that exact pool. It follows
reference conversions and unanimous phi inputs, and distinguishes stable
parameters and final field paths. Two fields of the same object do not become
the same pool merely because they share a lifetime owner.

Every possible bound checkout/release target must be an audited bundled pool
method. Unknown or retaining implementations, mismatched pools, ambiguous
joins, mutable field reloads, and cyclic identities retain conservative effects.
The proof must hold for every emitted copy of a source cleanup call. Deferred
calls use their captured SSA operands, so later local reassignment cannot
change the checked receiver or argument.

The same proof is consumed by ordinary escape analysis, symbolic return
analysis, owned-field analysis, and final call lowering. Receiver/argument
evaluation and all other publication effects remain checked. A successful
proof discharges only the pool-return effect, never ownership or unrelated
effects. It introduces no runtime registry, bookkeeping, checks, or allocation.

This does not add general ownership transfer, prove arbitrary release wrappers
that separately receive an untracked item, or broaden the existing rules for
ownership of sibling fields such as a pool and its factory.

## Regression coverage and prevention

- The native fixture covers direct and forwarding helpers, concrete and
  interface receivers, both bundled pools, `finally`, `defer`, normal return,
  exceptional cleanup, captured receiver reassignment, and a final pool field.
  It checks independent returned Strings, pool reuse, zero allocations during
  1,000 warmed iterations, and restoration of the live-allocation baseline.
- Semantic checks run in every `--unfreed` mode. A safe alias control is compiled
  alongside negative cases for publication, retaining callbacks/implementations,
  wrong pools, mixed identities, loop and finally reassignment, deferred capture
  mismatches, independent item free, external values, distinct sibling fields,
  and fresh results that also publish an input.
- Source-path, loose-class, and archive reconstruction each compile, link at
  `-O3`, run the pooled-string example, and verify complete caller cleanup.

When changing shared call binding or ownership summaries, pair a safe helper
extraction with its inline equivalent and nearby unsafe mutations. Include
reference-returning and void helpers, pooled/container-dependent references,
custom retaining dispatch targets, cleanup copies, and artifact reconstruction.
Checking only the newly added subsystem misses effects on existing audited
library contracts. Keep this selection focused rather than running the full
compiler suite.

```sh
./scripts/test.sh \
  --test 'pool release helpers preserve borrows and allocation-free reuse' \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'pool release helper proofs survive artifact reconstruction'
git diff --check
./scripts/check-licenses.sh
```

The registered native fixture is
[`pool_release_helpers.iron`](../integration-tests/cases/pool_release_helpers.iron);
the semantic and artifact checks are in
[`PoolReleaseTests.java`](../compiler/src/test/java/ironwood/compiler/PoolReleaseTests.java).
Focused existing dispatch, pool, deferred cleanup, fresh-result, receiver-borrow,
StringBuilder, and networking ownership checks accompany this selection.

## Verification results, 2026-09-20

Verified locally on macOS ARM64 with Java 21 and LLVM 23:

- The new native regression failed against the unchanged compiler with the pool
  escape diagnostic before implementing the fix.
- All three new registered tests passed. The native program exits `42`, observes
  zero allocations over 1,000 reuse iterations, returns independent Strings,
  returns the item after an exception, and restores its live-allocation baseline.
- The semantic test accepts the native fixture and safe alias control in
  `off`, `warn`, and `error`. All 16 unsafe variants retain safety errors in
  each mode, giving 48 negative case/mode assertions.
- Source, loose-class, and archive native reconstruction each exits `42` at
  `-O3` with complete caller cleanup.
- All 14 affected existing tests below passed, for 17 registered tests total.
  No unfiltered compiler or platform suite was run.
- `git diff --check` and `./scripts/check-licenses.sh` passed.

```sh
./scripts/test.sh \
  --test 'pool release transfers ownership for safe-free analysis' \
  --test 'pool ownership rejects dangling and conflicting aliases' \
  --test 'owning pools reclaim all values and retain allocation-free reuse' \
  --test 'data structures destroy internal pools and preserve caller items' \
  --test 'borrow dispatch uses exact overloads defaults and receiver flow' \
  --test 'borrow dispatch rejects retaining and unknown receiver flows' \
  --test 'owned buffer fields require a fresh unescaped factory result' \
  --test 'unfreed diagnostics track receiver-retained allocations' \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'TCP extensions preserve factory and constructor ownership effects' \
  --test 'everyday StringBuilder ownership and allocations are preserved' \
  --test 'fresh bulk results preserve detached element ownership under mutation'
```

The historical test name mentioning a release "transfer" is retained above to
identify the existing check exactly. It checks rejection of independent free;
it does not authorize changing a pooled value's owner.

### Optimized code check

A minimal helper and its inline equivalent both compile under strict unfreed
checking and run successfully. Save this as `Main.iron` in two separate scratch
directories, replacing only `message(pool);` in one copy with
`StringBuilder sb = pool.get(); pool.release(sb);`:

```java
// SPDX-License-Identifier: MIT OR Apache-2.0
import ironwood.pool.ArrayObjectPool;
import ironwood.pool.ObjectBuilder;

class Builder implements ObjectBuilder<StringBuilder> {

    @Override
    public StringBuilder newInstance() {

        return new StringBuilder();
    }
}

public class Main {

    static void message(ArrayObjectPool<StringBuilder> pool) {

        StringBuilder sb = pool.get();
        pool.release(sb);
    }

    public static int main(String[] args) {

        Builder builder = new Builder();
        ArrayObjectPool<StringBuilder> pool = new ArrayObjectPool<StringBuilder>(1, builder);
        message(pool);
        int result = 0;
        free pool;
        free builder;
        return result;
    }
}
```

For each directory, run the following with Java 21 and LLVM 23 selected. The
explicit optimizer flags match the compiler's `-O3` pipeline. The `.opt.ll` and
assembly inspect optimized program code; the linked executable additionally
contains finalized stack-trace metadata and the native runtime.

```sh
ironwoodc --unfreed=error -d classes Main.iron
ironwoodc --link --unfreed=error -O3 -cp classes --main-class Main \
  --emit-llvm program.ll -o program
opt -S '-passes=default<O3>' -inline-threshold=1000 \
  -enable-partial-inlining program.ll -o program.opt.ll
llc -O=3 --relocation-model=pic -filetype=asm program.opt.ll -o program.s
llvm-objdump --disassemble --no-show-raw-insn program > disassembly.txt
llvm-objdump --section-headers program
./program
```

Both linked `__text` sections are **13,320 bytes**. Both optimized program
assemblies have **965 instructions**, including **528 in `main`**. After excluding
assembly comments and metadata directives, the instruction streams differ only
in the immediate count passed to the existing one-time trace registration:
411 helper source sites versus 409 inline source sites. The helper is inlined;
checkout, release, and cleanup have identical instructions. Source/trace metadata
differs as expected, so the binaries are not claimed to be byte-identical.
Combined with the deterministic zero-allocation reuse test, this checks the
changed lowering without adding runtime safety bookkeeping. It is not a new
general performance or timing acceptance claim.
