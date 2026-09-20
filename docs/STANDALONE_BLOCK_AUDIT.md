<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Standalone-block audit

Date: 2026-09-20. The maintainer requested removal of unnecessary standalone
blocks throughout Ironwood sources and documentation examples, accepting longer
temporary lifetimes where appropriate. Baseline: local `main` at
`0e78ef9762a9d643855998dfd3ec0b0374666e81`. Changes are prepared without a commit
or push.

## Scope and result

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

## Boundaries retained for review

Four blocks in ordinary application/library code cannot simply be flattened
without changing cleanup timing that affects subsequent work:

| Location | Blocks | Why cleanup finishes here |
| --- | ---: | --- |
| [Files.readAllLines](../stdlib/src/main/ironwood/ironwood/nio/file/Files.iron), line 153 | 1 | Reader close must succeed before `successful = true`. Otherwise a close failure could bypass rollback of the result list and pending line. |
| [Files.walkEntry](../stdlib/src/main/ironwood/ironwood/nio/file/Files.iron), line 313 | 1 | Release the directory's borrowed attributes after `preVisitDirectory`, before descendant callbacks. Flattening retains each ancestor's attributes during recursion and changes the callback's reclamation boundary. |
| [TestRunner.run](../stdlib/src/testing/ironwood/ironwood/testing/TestRunner.iron), line 30 | 1 | `afterEach` can fail or skip. It must complete before incrementing the pass count or printing success. |
| [Minitee.main](../projects/minitee/src/main/ironwood/org/ironwood/minitee/Minitee.iron), line 33 | 1 | Finish tee close/flush before inspecting stdout's recorded error state and selecting the exit status. |

Comments explain these four boundaries. Meaningful helper extraction could
replace their syntax in a separate change; this audit leaves them visible for
maintainer review rather than altering their behavior to remove braces.

The other **59 retained blocks** preserve focused demonstrations and regression
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

## Focused verification

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
