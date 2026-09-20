<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Standalone-block audit

Date: 2026-09-20. The maintainer requested removal of unnecessary standalone
blocks throughout Ironwood sources and documentation examples, accepting longer
temporary lifetimes where appropriate. Baseline: local `main` at
`0e78ef9762a9d643855998dfd3ec0b0374666e81`. The latest result is recorded first;
the original inventory and verification remain below as historical evidence.

## Follow-up: explicit cleanup and method boundaries

The second pass, requested after the Minitee explicit-close change, starts from
`d70ca50aac9ab4ac70b29cc0c4a99c729ec47945`. All **638 tracked Ironwood files**
were parsed again and every remaining block was reviewed. This pass removes
**36 of the 62 blocks in 12 source files**, leaving **26 in seven test/probe
files**. No standalone statement blocks remain in ordinary examples,
application implementations, or the main and testing libraries. Initializers
and required control-flow bodies are excluded. Compiler/runtime implementation
is unchanged; this pass is left uncommitted.

- `Files.readAllLines` closes its reader explicitly before marking success;
  deferred close/free still cover failure. `BufferedReader.close` is idempotent.
- `Files.preDirectory` completes the callback and attribute release before
  traversal continues. `TestRunner.runCase` completes setup, the test, and
  teardown before counting a pass. Teardown still runs exactly once, including
  on setup/body failure; it is not treated as idempotent.
- The pool example checks destruction after its existing method returns.
  Collection checks use one named method per collection, preserving all 21
  renderers, three renderings each, exact text, result/container live counts,
  failure cleanup, and the original failure exit codes.
- URL/address helpers reclaim temporary inputs before returning independent
  results. Socket/TLS helpers finish warmup or destruction before assertions.
  The transfer probe prints its end marker after its method's cleanup.
  Distinct local names remove three StringBuilder switch-case scopes.

Unlike idempotent close, explicit `free` cannot coexist with a pending deferred
free of the same allocation. Removing the deferred action instead can lose
failure cleanup. Ordinary method boundaries preserve both requirements without
adding helper objects, callbacks, runtime registration, or counters.

### Remaining cases

| Location | Blocks | Reason retained |
| --- | ---: | --- |
| [defer_calls](../integration-tests/cases/defer_calls.iron) | 13 | Deliberately test block exit, captures, initialization timing, switch fallthrough, failure ordering, and release before reuse. |
| [defer_free](../integration-tests/cases/defer_free.iron) | 7 | Deliberately test deferred destruction, binding reuse, skipped cases, and pool cleanup. |
| [defer_free_socket](../integration-tests/cases/defer_free_socket.iron), line 36 | 1 | Prove deferred writes and close happen at block exit before peer reads. Explicit cleanup would stop testing that behavior. |
| [HostNetworkingTests.checkBindings](../stdlib/test/ironwood/net/HostNetworkingTests.iron), line 129 | 1 | Prove a binding outlives its temporary list. Returning it through the attempted helper caused an escape diagnostic on the interface owner. |
| [TcpFoundationTests.checkBorrowedImplementation](../stdlib/test/ironwood/net/TcpFoundationTests.iron), line 210 | 1 | End the wrapper's borrow before observing/reclaiming its implementation. Helper extraction caused escape and conflicting-ownership diagnostics. |
| [TransferProbe.transfer](../projects/wget/src/test/ironwood/org/ironwood/wget/TransferProbe.iron), line 19 | 1 | End the response's borrow before closing/reclaiming transport. Inner-body extraction caused escape and conflicting-ownership diagnostics; the outer block was removed. |
| [ResponseProbe.main](../projects/wget/src/test/ironwood/org/ironwood/wget/ResponseProbe.iron), lines 20 and 23 | 2 | Preserve response-before-input teardown, the end marker after reclamation, and both primitive measurement results afterward. A helper requires different result plumbing; explicit frees must also preserve exceptional cleanup. Extra measurement storage and changed timing/marker order were avoided. |

The three rejected borrow-related helper layouts were withdrawn. No ownership
exemptions or compiler changes were introduced. Further removal would need a
separate ownership-proof investigation or a less direct test refactor.

### Follow-up verification

macOS ARM64, Java 21.0.1, LLVM 23.1.0. **Ten distinct selected compiler checks
passed.** Nine passed initially; after withdrawing rejected borrow-helper
layouts, only the failing stdlib-runner check was repeated and passed. That
runner verified **172 passes and one expected skip**, intentional failure
reporting, and the negative assertion-signature fixture.

The pool example compiled/linked at `-O3` and produced its five expected lines
with exit **42**. Wget's protocol group passed **89 network cases** and URL
checks; its allocation group passed **20 network cases** plus local reader
probes, checking managed/native allocations and repeated lifecycle cleanup.
It also emitted optimized disassembly; no timing-parity claim is made.
All 638 sources parsed without diagnostics, confirming the 26 blocks above.
License checks and `git diff --check` passed. No unfiltered compiler/platform
suite ran.

With the environment exports from the original verification section:

```sh
./scripts/test.sh \
  --test 'U2 paths and whole-file I/O run at O3' \
  --test 'U2 file and path allocation failures roll back at O3' \
  --test 'U5 file tree traversal enforces borrowed visitor callbacks' \
  --test 'U5 file tree traversal controls depth links and cleanup' \
  --test 'native filesystem scratch and resource cleanup survive injected failures' \
  --test 'standard-library testing module reports deterministic native results' \
  --test 'standard-library test reporting reclaims temporary allocations' \
  --test 'deferred standard-library collection rendering preserves text and live counts' \
  --test 'everyday StringBuilder operations match Java' \
  --test 'everyday StringBuilder allocation failures reclaim temporary storage'
bash examples/deferredcleanup/compile.sh
bash examples/deferredcleanup/link.sh
bash examples/deferredcleanup/run.sh
python3 scripts/test-networking-m6.py --group protocol
python3 scripts/test-networking-m6.py --skip-build --group allocation
git diff --check
./scripts/check-licenses.sh
```

The focused stdlib rerun reused fresh compiler/test classes:

```sh
java -ea -cp compiler/build/classes:compiler/build/test-classes \
  ironwood.compiler.CompilerTests \
  --test 'standard-library testing module reports deterministic native results'
```

Ignored `workspace/standalone-block-revisit/` contains the plan, inventories,
rejected-helper diagnostics, and final verification logs/reports.

## Original audit scope and result

The audit parsed all **638 tracked `.iron` files** with the compiler's source
parser, then inspected Java/Ironwood code fences in **1,369 tracked Markdown
files**, including API documentation, and code examples in Ironwood Javadocs.
Generated build output and ignored scratch files are excluded.

The source inventory counts blocks used as statements, including explicit
classic-switch case scopes. It excludes method and control-flow bodies,
initializer blocks, array initializers, and labeled control-flow bodies.

| Sources | Before | Removed | Retained |
| --- | ---: | ---: | ---: |
| Examples | 16 | 15 | 1 |
| Projects | 20 | 13 | 7 |
| Standard and testing libraries, including tests | 48 | 38 | 10 |
| Integration fixtures | 46 | 1 | 45 |
| Total | 130 | 67 | 63 |

The **67 removed blocks affect 31 source files**. Two documentation snippets
now use named method bodies instead of standalone blocks. The final source
scan has no parse errors. The four remaining documentation candidates are
instance initializers or nested array initializers, not standalone statements.

Deferred operations remain installed immediately after acquisition. Existing
method, loop, conditional, and try bodies provide cleanup boundaries. Some
temporary allocations now remain live until those bodies exit, including the
echo client's request bytes while it reads the reply. Cleanup still runs on
exceptional exits; close-before-free and borrower-before-owner ordering remain
intact. No compiler or runtime implementation changed.

The TCP examples use a private `exchange()` helper so socket cleanup still
finishes before printing success. `Files.createsTraversalLoop` relies on the
existing deferred-call rule: the argument value is captured at declaration,
so releasing `parent` at iteration exit frees that iteration's original path
even after the local is assigned its successor. Deferred-free targets are not
reassigned while pending. Duplicate test locals were renamed or reused where
removing their scopes made declarations conflict.

The preference is recorded in [IRONWOOD_FORMATTING.md](IRONWOOD_FORMATTING.md#standalone-blocks).

## Original retained boundaries

The original audit retained four blocks in ordinary application/library code
because flattening alone would change cleanup timing that affects subsequent
work. After the Minitee follow-up below, three operational blocks and 59
test/demonstration scopes remained. The second pass supersedes this inventory.

| Location | Blocks | Why cleanup finishes here |
| --- | ---: | --- |
| [Files.readAllLines](../stdlib/src/main/ironwood/ironwood/nio/file/Files.iron), line 153 | 1 | Reader close must succeed before `successful = true`. Otherwise a close failure could bypass rollback of the result list and pending line. |
| [Files.walkEntry](../stdlib/src/main/ironwood/ironwood/nio/file/Files.iron), line 313 | 1 | Release the directory's borrowed attributes after `preVisitDirectory`, before descendant callbacks. Flattening retains each ancestor's attributes during recursion and changes the callback's reclamation boundary. |
| [TestRunner.run](../stdlib/src/testing/ironwood/ironwood/testing/TestRunner.iron), line 30 | 1 | `afterEach` can fail or skip. It must complete before incrementing the pass count or printing success. |
| [Minitee.main](../projects/minitee/src/main/ironwood/org/ironwood/minitee/Minitee.iron), line 33 | 1 | Finish tee close/flush before inspecting stdout's recorded error state and selecting the exit status. |

Comments explained these boundaries; the second pass uses explicit close or
helpers for all three remaining operational sites.

Minitee follow-up: `tee.close()` now runs explicitly before the stdout error
check, so no standalone block is needed. `defer tee.close()` remains a failure
guard if copying throws before the explicit close; the tee's idempotent close
makes the later deferred call a no-op. Deferred frees still reclaim the tee
before its borrowed file. This removed one more source block, leaving **62**
before the second pass.

The other **59 retained blocks** preserved focused demonstrations and regression
coverage. Eight are classic-switch case scopes. Moving cleanup past the later
assertions would invalidate these tests or stop testing the intended exit.

| Location | Blocks | Purpose |
| --- | ---: | --- |
| [DeferredCleanup.reusePool](../examples/deferredcleanup/src/main/ironwood/org/ironwood/deferredcleanup/DeferredCleanup.iron), line 93 | 1 | Demonstrate pool destruction and restoration of the live-allocation baseline after cleanup. |
| [defer_calls](../integration-tests/cases/defer_calls.iron) | 13 | Block exit, capture, target initialization, switch scopes, exception ordering, and pool reuse after release. |
| [defer_free](../integration-tests/cases/defer_free.iron) | 7 | Destruction counts, binding reuse after cleanup, switch fallthrough, and pool destruction. |
| [defer_free_socket](../integration-tests/cases/defer_free_socket.iron), line 36 | 1 | Deferred buffered writes and socket close must run before the peer reads and EOF assertions. |
| [defer_stdlib_rendering](../integration-tests/cases/defer_stdlib_rendering.iron) | 21 | Destroy each collection before checking its live-allocation baseline. |
| [stdlib_stringbuilder_everyday](../integration-tests/cases/stdlib_stringbuilder_everyday.iron), lines 82 and 88 | 2 | Classic-switch case-local declarations, including repeated local names. |
| [stdlib_stringbuilder_failure](../integration-tests/cases/stdlib_stringbuilder_failure.iron), line 40 | 1 | Classic-switch case scope in the failure fixture. |
| [ResponseProbe](../projects/wget/src/test/ironwood/org/ironwood/wget/ResponseProbe.iron), lines 20 and 23 | 2 | Observe response destruction, reader cleanup, and the live-allocation baseline. |
| [TransferProbe](../projects/wget/src/test/ironwood/org/ironwood/wget/TransferProbe.iron), lines 12 and 20 | 2 | Response-before-transport teardown and a marker after destruction. |
| [UrlTests](../projects/wget/src/test/ironwood/org/ironwood/wget/UrlTests.iron), lines 29 and 60 | 2 | Prove results remain usable after their base or input is freed. |
| [AddressSocketTests](../stdlib/test/ironwood/net/AddressSocketTests.iron), line 38 | 1 | Use a parsed address after freeing its input text. |
| [HostNetworkingTests](../stdlib/test/ironwood/net/HostNetworkingTests.iron), line 129 | 1 | Use an interface binding after freeing its mutable list container. |
| [ProxyTests](../stdlib/test/ironwood/net/ProxyTests.iron), line 65 | 1 | Complete warmup reclamation before measuring allocations. |
| [TcpFoundationTests](../stdlib/test/ironwood/net/TcpFoundationTests.iron), lines 210, 213, and 234 | 3 | Observe borrowed implementation ownership and exact destruction timing. |
| [TlsClientTests](../stdlib/test/ironwood/net/TlsClientTests.iron), line 86 | 1 | Finish warmup and reclamation before establishing the baseline. |

## Original focused verification

Host: macOS ARM64, Java 21.0.1, LLVM 23.1.0. All checks below passed. This is
focused local validation, not an exhaustive suite or cross-platform claim.

| Check | Result |
| --- | --- |
| Compiler selection below | 13/13 passed; includes 96 proxy scenarios, 139 TLS scenarios, ownership rejections, allocation failures, file traversal, and combined socket cleanup. |
| Streaming source/class/archive CLI check | 1/1 passed, including binary copying, same-file rejection, and prompt behavior. |
| Selected native stdlib networking suites | 41/41 passed: M1 9, M2 10, M3 8, M4 10, M5 4. |
| Changed examples | All six groups compiled and linked. Five functional example workflows and five benchmark executables passed their small native runs. |
| SimpleTcpEcho | Project test passed, including EOF framing, repeated clients, capacity handling, failures, and zero allocation/free inside reply. |
| OrderBook | Four native tests, six Java tests, CLI checks, and exact native/Java report comparison passed. |
| Wget protocol / allocation / cleanup groups | 89 / 20 / 330 reported network cases passed respectively, plus URL checks and the allocation group's local reader probes. |
| Two edited documentation snippets | Extracted verbatim into a small harness; strict compilation and `-O3` linking passed. Native exit 42 with empty stdout/stderr verified use, close, and destruction order. |
| Source inventory and hygiene | All 638 sources parsed; 63 reviewed blocks remain. `git diff --check` and license audit passed. |

From the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm

./scripts/test.sh \
  --test 'everyday StringBuilder operations match Java' \
  --test 'everyday StringBuilder ownership and allocations are preserved' \
  --test 'everyday StringBuilder allocation failures reclaim temporary storage' \
  --test 'floating text overloads match Java and reclaim temporaries' \
  --test 'floating text failures preserve builder state and cleanup' \
  --test 'U5 file tree traversal enforces borrowed visitor callbacks' \
  --test 'U5 file tree traversal controls depth links and cleanup' \
  --test 'native filesystem scratch and resource cleanup survive injected failures' \
  --test 'network address results preserve copied inputs and dependent names' \
  --test 'TCP constructors options and urgent data run at O3' \
  --test 'explicit proxy negotiation and cleanup contracts' \
  --test 'TLS local protocol policy and native cleanup contracts' \
  --test 'combined defer cleanup closes and reclaims loopback sockets at O3'

# Reuse the freshly built compiler for this additional focused selection.
java -ea -cp compiler/build/classes:compiler/build/test-classes \
  ironwood.compiler.CompilerTests \
  --test 'U3 CLI runs through source class archive and separate link'

bash projects/SimpleTcpEcho/test.sh
bash projects/OrderBook/compile.sh
bash projects/OrderBook/link.sh
bash projects/OrderBook/test.sh
python3 scripts/test-networking-m6.py --group protocol
python3 scripts/test-networking-m6.py --skip-build --group allocation
python3 scripts/test-networking-m6.py --skip-build --group cleanup
```

The native stdlib selection compiles the registered suites once but executes
only the affected networking groups, each in a fresh process:

```sh
mkdir -p workspace/standalone-block-audit
ironwoodc --unfreed=error --source-path stdlib/test \
  -cp compiler/build/ironwood-testing.ironjar \
  -d workspace/standalone-block-audit/stdlib-classes \
  stdlib/test/ironwood/testing/StandardLibraryTests.iron
ironwoodc --link --unfreed=error -O3 \
  -cp workspace/standalone-block-audit/stdlib-classes:compiler/build/ironwood-testing.ironjar \
  --main-class ironwood.testing.StandardLibraryTests \
  -o workspace/standalone-block-audit/stdlib-tests
for suite in networking-m1 networking-m2 networking-m3 networking-m4 networking-m5; do
  workspace/standalone-block-audit/stdlib-tests "$suite"
done

for name in bench hostnetworking proxy statements tcp tcpnames; do
  bash "examples/$name/compile.sh"
  bash "examples/$name/link.sh"
done
for name in hostnetworking proxy statements tcp tcpnames; do
  bash "examples/$name/run.sh"
done
for name in SleepBenchmark MathBenchmark BubbleSortBenchmark; do
  "examples/bench/target/$name" 1 3
done
examples/bench/target/IntMapBenchmark 1 3 8
examples/bench/target/NanoBenchExample 3

git diff --check
./scripts/check-licenses.sh
```

Ignored `workspace/standalone-block-audit/` contains the source inventory,
documentation scan, extracted snippet harness, per-group reports, and logs for
this checkout. The wget allocation group also checks managed/native allocation
behavior and emits optimized disassembly. Compiler hot lowering did not change; no new
performance-parity claim is made for the intentionally extended lifetimes.
