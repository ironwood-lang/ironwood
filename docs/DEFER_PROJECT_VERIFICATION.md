<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Defer project adoption verification

Date: 2026-09-20 (checks started on September 19). Scope: SimpleTcpEcho cleanup
adoption and its required verification, steps 3 and 4 of
[Milestone 2](DEFER_PLAN.md). Baseline: local commit
`9f8f07660508401d9fbcb24b45e14946a4a0bab3` on `new-defer-keyword`.
The compiler implementation, runtime and standard library are unchanged. The final Milestone 2
audit and integration remain separate, unselected work.

## Behavior and source boundary

Only `Server.serve` changes in production code. A byte-for-byte comparison
confirmed that `reply`, `main`, and everything else outside `serve` are unchanged.
`Client.iron` and `ReplyProbe.iron` are unchanged. The project retains its 1 KiB
limit, reused 1,028-byte buffer, blocking EOF framing, stdout choices,
diagnostics, argument handling and exit codes.

Listener cleanup is declared after acquisition, then buffer cleanup after its
allocation. On leaving `serve`, buffer free precedes listener close/free.
`Socket client = server.accept();` remains outside the per-client `try`.
At the start of that try, `defer free client;` followed by `defer client.close();`
ensures close then free finish before catch dispatch, including failure during
stream acquisition or close. The same catch prints the client error and permits
another accept. An accept failure unwinds the listener scope and reaches `main`'s
status-74 handler. No cleanup moved to the surrounding infinite loop's scope.

## Focused verification

Environment: Oracle Java 21.0.1, LLVM 23.1.0, macOS 26.6.2 ARM64. No other-host
execution is claimed. With the built compiler, reproduce from the repository root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm
./projects/SimpleTcpEcho/test.sh
./scripts/test.sh \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'combined defer cleanup closes and reclaims loopback sockets at O3' \
  --test 'deferred server cleanup preserves per-client and listener failures at O3'
```

The project workflow passed strict source compilation, class-directory linking
at `-O3`, and all ten local test groups, with no default-port skip. These cover
default/custom arguments, repeated clients, UTF-8 and binary data, fragmented
input, waiting for EOF, Ctrl+C, exact stdout, 1,023/1,024-byte boundaries,
oversized rejection followed by reuse, usage errors, parse failures, connection
refusal, and a real listener bind failure with exit `74` and a stderr trace.
Logs remain in ignored `projects/SimpleTcpEcho/target/test/` and
`target/defer-adoption/project-tests.log`.

The unchanged native `ReplyProbe` checked six successful requests of lengths
**1023, 1024, 0, 5, 1024, 2** through real TCP streams. Allocation and live-object
counters were unchanged across **every** `reply`, including the first request:
zero managed allocations and zero frees. Exact response bytes and GOT/REPLIED
output also passed. Socket/stream acquisition stays outside the measured call.

All three selected compiler tests passed. The new fixture was adjusted to keep
destructor instrumentation within the existing allocation-free contract, then
only that selection was rerun using the already built test classes:

```sh
java -ea -cp compiler/build/classes:compiler/build/test-classes \
  ironwood.compiler.CompilerTests \
  --test 'deferred server cleanup preserves per-client and listener failures at O3'
```

[The server-shaped fixture](../integration-tests/cases/defer_server_failure.iron)
uses deterministic stand-ins, not unreliable OS close failures. It covers four
clients: stream setup plus close failure, body plus close failure, close-only
failure, and success. The fifth accept throws the designated listener exception.
It checks exact primary and secondary identities, four closes and four frees,
three client catches, subsequent accepts, and reclamation before each catch.
The buffer is gone before listener close, and the listener is gone before the
outer catch returns `74`. Static test exceptions are initialized before the
live-count baseline; all per-run allocations return to it. Destructors update
only primitive counters. No safety exemption was added.

The fixture also passed a separate strict compile/link and native run:

```sh
ironwoodc --unfreed=error integration-tests/cases/defer_server_failure.iron \
  -d projects/SimpleTcpEcho/target/defer-adoption/failure-classes
ironwoodc --link --unfreed=error \
  -cp projects/SimpleTcpEcho/target/defer-adoption/failure-classes \
  --main-class Main -O3 -o projects/SimpleTcpEcho/target/defer-adoption/failure
```

Running that executable produced exit **74**, empty stdout and empty stderr.

## Generated-code and allocation evidence

The accepted [Section 8 performance matrix](DEFER_PERFORMANCE_VERIFICATION.md)
remains the repeated timing evidence. This stage adds a comparison of the actual
server before/after adoption and its native allocation probe. Network/stdout
latency is not used as evidence of compiler performance.

Reproduce the matched server artifacts with the same compiler and link settings:

```sh
IRONWOOD_ADOPTION=projects/SimpleTcpEcho/target/defer-adoption
IRONWOOD_SERVER=projects/SimpleTcpEcho/src/main/ironwood/org/ironwood/simpletcpecho/Server.iron
for variant in before after; do
  mkdir -p "$IRONWOOD_ADOPTION/$variant/src/org/ironwood/simpletcpecho"
done
git show "9f8f076:$IRONWOOD_SERVER" > "$IRONWOOD_ADOPTION/before/src/org/ironwood/simpletcpecho/Server.iron"
cp "$IRONWOOD_SERVER" "$IRONWOOD_ADOPTION/after/src/org/ironwood/simpletcpecho/Server.iron"
for variant in before after; do
  ironwoodc --unfreed=error --source-path "$IRONWOOD_ADOPTION/$variant/src" \
    -d "$IRONWOOD_ADOPTION/$variant/classes" \
    "$IRONWOOD_ADOPTION/$variant/src/org/ironwood/simpletcpecho/Server.iron"
  ironwoodc --link --unfreed=error -cp "$IRONWOOD_ADOPTION/$variant/classes" \
    --main-class org.ironwood.simpletcpecho.Server -O3 \
    --emit-llvm "$IRONWOOD_ADOPTION/$variant/program.ll" \
    -o "$IRONWOOD_ADOPTION/$variant/Server"
  "$IRONWOOD_LLVM_HOME/bin/llvm-as" "$IRONWOOD_ADOPTION/$variant/program.ll" \
    -o "$IRONWOOD_ADOPTION/$variant/program.bc"
  "$IRONWOOD_LLVM_HOME/bin/opt" '-passes=default<O3>' -inline-threshold=1000 \
    -enable-partial-inlining -S "$IRONWOOD_ADOPTION/$variant/program.bc" \
    -o "$IRONWOOD_ADOPTION/$variant/optimized.ll"
  "$IRONWOOD_LLVM_HOME/bin/llc" -O=3 --relocation-model=pic -filetype=asm \
    "$IRONWOOD_ADOPTION/$variant/optimized.ll" -o "$IRONWOOD_ADOPTION/$variant/program.s"
  "$IRONWOOD_LLVM_HOME/bin/llvm-objdump" --disassemble --no-show-raw-insn \
    "$IRONWOOD_ADOPTION/$variant/Server" > "$IRONWOOD_ADOPTION/$variant/disassembly.txt"
  "$IRONWOOD_LLVM_HOME/bin/llvm-size" -A "$IRONWOOD_ADOPTION/$variant/Server"
done
```

Both emitted/optimized LLVM and native assembly were inspected. Server methods
are inlined into `main`; there is no separately counted server cleanup function.
Referenced shared/library helpers are counted once in linked text.

| Code measurement | Before | Deferred | Delta | Ratio |
| --- | ---: | ---: | ---: | ---: |
| Linked `__TEXT,__text` bytes | 63752 | 63752 | 0 | 1.000000 |
| `main` bytes, including inlined server/cleanup | 6732 | 6752 | +20 | 1.002971 |
| `main` stack frame bytes | 112 | 112 | 0 | 1.000000 |

Register allocation and exceptional cleanup layout differ; this is not an
identical-machine-code claim. The successful client path retains the same
operations, checks, calls and spills. Saved registers and stack space match.
The net five extra instructions in `main` are in the restructured cold cleanup
code, including different inlining of listener close and secondary association.
All other disassembled function sizes are unchanged. The alignment gap from
`ironwood_trace_register_current` to `ironwood_throwable_trace_release` shrinks
from **36 to 16 bytes**, exactly absorbing those 20 bytes. There is no linked
text growth, extra successful-path allocation, action stack, registration,
callback, TLS operation or synchronization introduced by this adoption.

Non-code sections are separate from that text measurement:

| Section | Before bytes | Deferred bytes | Delta |
| --- | ---: | ---: | ---: |
| `__gcc_except_tab` | 4480 | 4436 | -44 |
| Both `__const` sections summed | 300296 | 300248 | -48 |
| `__probes` | 30308 | 30499 | +191 |
| `__probe_descs` | 29429 | 29429 | 0 |
| `__unwind_info` | 2216 | 2216 | 0 |
| `__eh_frame` | 6224 | 6224 | 0 |

Other section sizes are unchanged. The trace-site count changes from 3755 to
3754 in the existing one-time process registration; source-site metadata changes
with the source cleanup structure. There is no per-operation registration.

## Stage boundary

`./scripts/check-licenses.sh` passed (five existing OpenJDK-derived source files),
as did `git diff --check`, Python syntax and new documentation-link checks.

This completes the selected project adoption and verification for review. The
final Milestone 2 documentation/status audit remains pending. No other project
was adopted, and no compiler/runtime implementation change, full suite, rebase,
merge or push was performed.
