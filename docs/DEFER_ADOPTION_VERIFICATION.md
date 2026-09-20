<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Broader defer adoption verification

Date: 2026-09-20. The maintainer separately selected this migration after the
completed [defer milestones](DEFER_FINAL_VERIFICATION.md). Baseline:
`651c2d09b6107229fbe9015398095d08ba1ddf48` on local `new-defer-keyword`.
The checkpoint prepared changes for review without an agent commit and excluded
the root `README.md` at the maintainer's request. The maintainer subsequently
committed the migration as `b6ce3a8`, now on `main`; see
[integration status](DEFER_FINAL_VERIFICATION.md#integration-and-follow-up-status).

## Scope and preserved behavior

The migration changes **44 Ironwood source files**: 24 files in 20 example
workflows and 20 files in five projects. It removes 148 `finally` blocks by
using deferred local frees and stable local cleanup calls. Two documentation
snippets in [IRONWOOD_VS_JAVA.md](IRONWOOD_VS_JAVA.md) now use the same idiom.
Affected project guides describe the resulting cleanup.

The changed example workflows are `abstractclasses`, `anonymousclasses`,
`bench`, `boundedgenerics`, `checkedexceptions`, `collections`, `diamond`,
`fieldhiding`, `genericcasts`, `genericinference`, `hostnetworking`,
`initialization`, `interfacedefaults`, `interfacemembers`, `lexicalcapture`,
`nestedgenerics`, `proxy`, `tcp`, `tcpnames`, and `tls`. The five projects are
`OrderBook`, `SimpleTcpEcho`, `minitee`, `streaming`, and `wget`.

Cleanup becomes active at the original protected region's entry. Declarations
are ordered for LIFO execution, preserving close-before-free and borrower-before-
owner cleanup. Smaller explicit blocks preserve early cleanup before later
work, including the echo client's request reclamation before reply reading and
wget's request-buffer reclamation before input-view acquisition. Existing catch
boundaries and acquisition-failure coverage are preserved. Deferred calls
capture stable local receivers; none substitutes early capture for a later
field lookup or a reassigned receiver.

That describes the original migration. The subsequent
[standalone-block audit](STANDALONE_BLOCK_AUDIT.md) removes redundant cleanup
wrappers, including those two temporary-buffer scopes, while retaining scopes
whose early cleanup affects behavior or a lifetime assertion.

The field-hiding and generic-inference examples use scope expiration for local
aliases instead of clearing those aliases immediately before free. The generic
cast example retains its catch and defers reclamation until after it returns.
All three passed their own compile/link/run workflows. A structural review
confirmed unchanged counts of actual `free`, `close()`, and
`closeAfterFailure()` operations in every migrated source file, excluding
comments. Loop bodies, result checks, and I/O algorithms are preserved.

Compiler, runtime, standard-library implementations, Java comparison sources,
integration fixtures, and handwritten performance baselines are unchanged.
Ordinary immediate frees and destructor reclamation remain valid and were not
mechanically replaced.

## Intentionally retained finally

- The `exceptions`, `multicatch`, `statements`, `modernswitch`, `stacktraces`,
  `trycatchfinallyexception`, and `resources` examples retain their focused
  demonstrations of ordinary `finally`, transfers, traces, or failure ordering.
  Compiler regressions and performance comparisons retain their original inputs.
- `TcpNames.inspectAddresses` must reclaim unvisited array elements after partial
  progress and clear their slots. This is a loop over the array's state at exit,
  rather than a deferred free of one local owner.
- `TeeOutputStream` uses `finally` to attempt both borrowed outputs for writes,
  flushes, and closes. These are fan-out operations, including field reads at
  execution time, rather than local allocation cleanup.
- `Config.credential` deliberately catches and ignores a retry-close
  `IOException` before freeing the file. A plain deferred close would change
  that failure policy. Its surrounding scratch-buffer cleanup is migrated.
- `LifecycleProbe` retains its warmup's sequential `close(); free` cleanup.
  Splitting that sequence into two deferred actions would also free after a
  close failure, changing the existing failure behavior. Its per-cycle config
  cleanup is migrated.

These sites need their existing semantics or teaching purpose. This migration
does not add helper APIs solely to eliminate the remaining `finally` syntax.

## Environment and focused commands

Verified on macOS 26.6.2 ARM64 with Oracle Java 21.0.1 and Homebrew LLVM 23.1.0.
No other-platform execution is claimed. From the canonical repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm

./scripts/test.sh \
  --test 'deferred calls retain captures and mandatory ownership proofs' \
  --test 'deferred free enforces local syntax and pending binding writes' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'deferred server cleanup preserves per-client and listener failures at O3' \
  --test 'U3 CLI runs through source class archive and separate link' \
  --test 'minitee copies binary pipelines across class and archive links' \
  --test 'minitee bounds allocations and preserves cleanup failures'

for name in abstractclasses anonymousclasses bench boundedgenerics \
  checkedexceptions collections diamond fieldhiding genericcasts \
  genericinference hostnetworking initialization interfacedefaults \
  interfacemembers lexicalcapture nestedgenerics proxy tcp tcpnames tls; do
  "./examples/$name/compile.sh" &&
    "./examples/$name/link.sh" && "./examples/$name/run.sh" || exit 1
done

./projects/SimpleTcpEcho/test.sh
./projects/OrderBook/compile.sh
./projects/OrderBook/link.sh
./projects/OrderBook/test.sh
./projects/wget/test.sh --group protocol
for group in tls files allocation cleanup; do
  ./projects/wget/test.sh --skip-build --group "$group" || exit 1
done
```

All selected checks passed:

| Selection | Result |
| --- | --- |
| Exact compiler/project tests above | **8/8 passed** |
| Changed example compile/link/run workflows | **20/20 passed**, covering 24 changed source programs |
| SimpleTcpEcho | **10/10 local groups passed**, with no default-port skip |
| OrderBook | **4 native tests and 6 Java tests passed**, CLI checks passed, native/Java latency reports matched byte for byte |
| wget protocol | **89 reported local cases passed**, plus URL tests |
| wget TLS | **30 reported local cases passed**, plus URL tests |
| wget files | **3 reported local cases passed**, plus URL tests |
| wget allocation | **20 reported local cases passed**, plus URL tests |
| wget cleanup | **330 reported local cases passed**, plus URL tests |
| Changed documentation snippets | Strict source/class compilation, O3 link, native exit **42** |

The compiler selection covers mandatory ownership rejection, pending binding
writes, cleanup predecessors, exits, exception ordering, live allocation counts,
and source/class/archive reconstruction. The streaming and minitee selections
exercise binary pipelines and expected output; minitee also checks allocation
bounds, descriptor pressure, managed OOM, and cleanup failures.

SimpleTcpEcho validates binary/UTF-8 data, framing, boundaries, reuse, exit codes,
listener failure, and zero allocations or frees inside every measured reply,
including the first. OrderBook retains its allocation-free sample collection
and pool-reuse assertions. The example benchmark uses its small deterministic
smoke workloads and checksum/report assertions, not throughput acceptance.
Host networking uses its normal interface snapshot, without opt-in reachability.
Networking examples and wget use local scripted peers.

wget rebuilds its six executables with strict `--unfreed=error` and O3 linking.
Its allocation checks cover small, 4 MiB, and chunked responses, repeated HTTP
and TLS lifecycles, redirects, and timeout variants. Reader/transfer steady-state
managed allocation assertions and warmed retained native-heap deltas passed.
The TLS tests retain their existing OpenSSL per-record allocation allowance;
this is not a claim of zero native TLS allocations. Cleanup checks cover injected
socket/output close failures and preservation of the earlier EOF diagnostic.
Managed OOM sweeps reached successful completion at allocation limits **82**
(HTTP), **95** (HTTPS), and **146** (HTTPS through an HTTP proxy), checking
reclamation on preceding failure runs. Linux-only `/dev/full` coverage is not
claimed on this host.

## Documentation snippet check

The exact two changed `java` fences were extracted into an ignored `Main.iron`
wrapper. Minimal `Buffer` and `Resource` stand-ins instrument use, close, and
destruction with a primitive counter. Reproduce the extraction as follows:

```python
from pathlib import Path
import re

text = Path("docs/IRONWOOD_VS_JAVA.md").read_text()
blocks = re.findall(r"```java\n(.*?)```", text, re.S)
selected = [next(block for block in blocks if marker in block) for marker in (
    "Buffer scoped = new Buffer();", "Resource resource = new Resource(1, false);"
)]
wrapper = '''// SPDX-License-Identifier: MIT OR Apache-2.0
class Buffer { destructor { Main.events += 1; } }
class Resource implements AutoCloseable {
    Resource(int id, boolean failing) { }
    @Override public void close() { Main.events += 10; }
    destructor { Main.events += 100; }
}
public class Main {
    static int events;
    static void use(Buffer value) { events += 2; }
    static void use(Resource value) { events += 20; }
    public static int main(String[] args) {
''' + "\n".join(selected) + '''
        return events == 133 ? 42 : 1;
    }
}
'''
path = Path("workspace/defer-migration/doc-src/Main.iron")
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(wrapper)
```

```sh
ironwoodc --unfreed=error workspace/defer-migration/doc-src/Main.iron \
  -d workspace/defer-migration/doc-classes
ironwoodc --link --unfreed=error -cp workspace/defer-migration/doc-classes \
  --main-class Main -O3 -o workspace/defer-migration/doc-snippets
result=0
workspace/defer-migration/doc-snippets || result=$?
test "$result" -eq 42
```

## Evidence and limits

Per-example logs and `examples-results.json`, `compiler-tests.log`,
`SimpleTcpEcho.log`, `OrderBook.log`, `wget-{protocol,tls,files,allocation,cleanup}.log`,
and `docs.log` remain under ignored `workspace/defer-migration/`. wget's generated
native artifacts and final cleanup report remain in
`integration-tests/target/networking-m6/`; other project outputs stay in their
ignored `target/` directories.

`git diff --check`, the license audit, changed documentation link checks, and
added-text policy checks passed. The Git index and baseline commit were unchanged
at that checkpoint. No unfiltered compiler/platform suite was run. This source adoption
does not change hot compiler lowering or replace the existing
[performance acceptance evidence](DEFER_PERFORMANCE_VERIFICATION.md) with a new
whole-program machine-code or timing parity claim.
