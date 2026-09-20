<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Ownership helper-boundary investigation

2026-09-20. Baseline: `ab415661ccb6e6548d958f60ab0fc535562cef6b`, clean
canonical `main`. macOS ARM64, Java 21.0.1, LLVM 23.1.0, ironwoodc
0.5.1-beta. The preceding standalone-block changes were already committed.
The original investigation below made no compiler, runtime, library, or existing
test changes and performed no Git integration. Its results describe that
baseline. The subsequently authorized implementation is recorded in the final
section; baseline failures below are historical unless explicitly retained.

## Findings and classification

All three reconstructed inline forms compile; all three helper forms reproduce
the saved diagnostics. Their objects have safe lifetimes under the documented
contracts. The rejections are **conservative proof limitations and concrete
false positives**, not actual ownership transfers or dangling-reference paths.
Current documentation requires rejection when proof is insufficient; it does
not promise arbitrary helper-refactoring completeness. These are missing
compiler precision, rather than a demonstrated safety-contract violation.

| Case | Classification | Missing proof |
| --- | --- | --- |
| Injected SocketImpl | Safe; longstanding constructor-borrow summary limitation | A temporary wrapper's private-field loan ends before the helper exits. |
| NetworkInterface bindings | Safe; factory-effect and returned-element provenance limitations | A fresh temporary list borrows its elements from the interface; a returned element retains that root after list destruction. |
| wget Response | Safe; independently reproduced instance of the constructor-borrow summary limitation | Both constructor inputs borrow transport's graph; destroying Response ends those loans. |

No recent regression was established. The reduced constructor rejection exists
in the initial compiler. The list helper was already rejected when its inline
single-root proof was introduced. Both predate `defer`. The earlier pool-release
incident is a useful investigation method, not an established cause here.

## Reproduction evidence

The rejected original edited files were not saved. Larger helper fixtures were
**reconstructed** from the handoff transformations and current committed sources.
The original logs remain independent evidence:

- `workspace/standalone-block-revisit/focused-tests.log`, lines 259-283;
- `workspace/standalone-block-revisit/wget-protocol.log`, lines 36-50.

The new evidence is retained under ignored
`workspace/borrow-helper-investigation/`. Every compile log records the command,
complete output and exit status. `reconstruct.py` records the transformations;
`reconstructed/{socket,bindings,wget}/{inline,helper}/src/` holds both versions.
The files under the canonical test/library/project paths were never overwritten.

| Reconstructed case | Inline exit | Helper exit | Helper diagnostics at caller cleanup |
| --- | ---: | ---: | --- |
| Socket | 0 | 1 | Argument 1 escape through `configureBorrowingSocket`; conflicting ownership across exceptional paths. |
| Bindings | 0 | 1 | Argument 1 escape through `checkBindingList`, three cleanup copies. |
| wget | 0 | 1 | Argument 1 escape through `transferBody`, twice; conflicting ownership across exceptional paths. |

These match the saved messages and multiplicities. Socket and wget also match
the reconstructed line numbers; bindings differs by one line of layout.
Compilation uses the actual dependency sources and the normal bundled stdlib.
The broad stdlib and wget protocol drivers were not rerun for these failures.

Four durable, independently compilable reductions are in
[`integration-tests/cases/borrow_helper_boundaries/`](../integration-tests/cases/borrow_helper_boundaries/).
They are investigation fixtures, not registered expectations that unsafe code
should compile. `ConstructorInline` and `ConstructorHelper` differ only by the
method boundary around wrapper acquisition, observation and destruction.
`ListInline` and `ListHelper` isolate a list of an owner's borrowed child,
without networking, generics dispatch ambiguity, `defer`, or exceptions.

From the canonical root:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
export IRONWOOD_LLVM_HOME=/opt/homebrew/opt/llvm

bin/ironwoodc --unfreed=error -d workspace/borrow-check/constructor-inline \
  integration-tests/cases/borrow_helper_boundaries/ConstructorInline.iron
bin/ironwoodc --unfreed=error -d workspace/borrow-check/constructor-helper \
  integration-tests/cases/borrow_helper_boundaries/ConstructorHelper.iron
bin/ironwoodc --unfreed=error -d workspace/borrow-check/list-inline \
  integration-tests/cases/borrow_helper_boundaries/ListInline.iron
bin/ironwoodc --unfreed=error -d workspace/borrow-check/list-helper \
  integration-tests/cases/borrow_helper_boundaries/ListHelper.iron
```

Expected compile exits are respectively **0, 1, 0, 1**. Helper diagnostics name
`use` as the escaping call. Repeat either helper with `--unfreed=off` or `warn`:
the mandatory rejection remains. The inline constructor's expected native exit
is 1, the list's is 0; the constructor's exit 1 is its deliberate result, not a
failed runtime assertion. Each source declares its own `Main`; compile them
separately. For example:

```sh
bin/ironwoodc --link --unfreed=error -O3 \
  -cp workspace/borrow-check/list-inline --main-class Main \
  -o workspace/borrow-check/list-program
workspace/borrow-check/list-program
```

Additional scratch reductions and their commands are retained by `reduce.py`,
`extra.py`, `unknown-and-exception.py`, and `artifacts.py`. The larger compile
commands are in `socket-{inline,helper}.log`, `bindings-{inline,helper}.log`, and
`wget-{inline,helper}.log`. This evidence includes:

| Controlled change | Observation |
| --- | --- |
| Constructor wrapper: straight-line `free`, ordinary `finally`, or `defer` | Inline accepted, helper rejected in every form. |
| Constructor wrapper: normal execution or a static exception through cleanup | Inline accepted and runs; helper rejected with either `finally` or `defer`. |
| Constructor only observes the input without retaining it | Helper accepted. |
| Direct fresh borrowing-wrapper factory; caller destroys wrapper first | Accepted; reversing destruction order rejected. |
| Direct helper returning an owner's child, with no temporary list | Accepted; ordinary dependent returns already work. |
| NetworkInterface helper only creates and frees the list, without `get` | Rejected. Returned aliases are not necessary for this effect failure. |
| NetworkInterface helper reads an element but returns void | Rejected with plain, `finally`, and deferred cleanup. |
| NetworkInterface helper returns an element or null | Rejected with the same three cleanup forms. |
| wget reduced to `new Response(transport.input(), transport)` and destruction | Inline accepted, helper rejected. No body transfer, Sink, or Config is needed. |
| Constructor and list helpers delivered through source, loose classes, archive | All three delivery forms reject at the caller; the standalone helper libraries themselves compile. |

The exception comparisons use unchanged local bindings, a static exception, and
exactly-once cleanup. No operand capture, null-check timing, or exception-object
reclamation change is used to obtain acceptance. The reduced wget acquisition
is compile-only: its unconnected transport would throw during input acquisition.
It is not claimed as a successful network execution.

## Lifetime graphs and actual cleanup

### 1. Injected implementation

The caller owns `TestingSocketImpl`, which owns its `NativeSocketImpl` transport.
`TestingSocket` borrows that caller-owned implementation through
[`Socket.supplied`](../stdlib/src/main/ironwood/ironwood/net/Socket.iron).
The protected constructor and the inherited `TestingSocket` constructor retain
only that encapsulated field. `close()` cascades to the implementation; the
Socket destructor frees views, wildcard metadata and `owned`, never `supplied`.
[`TestingSocketImpl`](../stdlib/test/ironwood/net/TestingSocketImpl.iron) is freed
only by the caller, once, after the wrapper is gone.

The helper's deferred close runs before its deferred wrapper free. A failed
close still attempts free. Failed construction rolls back the wrapper. Normal
return and exceptional exit therefore end the wrapper borrow before caller
cleanup. The post-helper counters observe a still-live implementation. No
returned facade, published implementation, or retaining callback was found in
this concrete use. A live or published wrapper is a genuinely unsafe nearby
case and remains rejected.

### 2. NetworkInterface bindings

[`NetworkInterface.getByName`](../stdlib/src/main/ironwood/ironwood/net/NetworkInterface.iron)
returns the independently owned lookup root. Its owned snapshot owns the
interface/address graph. `getInterfaceAddresses()` returns a fresh
[`ArrayList`](../stdlib/src/main/ironwood/ironwood/ds/ArrayList.iron) containing
borrowed `InterfaceAddress` references. The list owns its array and reusable
iterator, not those entries. `clear`, re-insertion and shallow destruction do
not destroy an entry or change its owner.

The helper returns an entry whose dependency is the original interface root.
The caller uses it before its deferred `free netif`. The null/empty case also
cleans up the list before the owner. Loop failures reclaim temporary rendered
Strings, the list, and then the owner. No path frees or publishes the returned
binding. See [owned-helper contracts](OWNED_HELPER_BORROWS.md#networking-foundation-proofs)
and [`InterfaceAddress`](../stdlib/src/main/ironwood/ironwood/net/InterfaceAddress.iron).
The pending return operand is not a loan from the list's storage ownership.

### 3. wget transport

[`Transport`](../projects/wget/src/main/ironwood/org/ironwood/wget/Transport.iron)
owns either Socket or TlsClient; `input()` lends a view of the same graph.
[`Response`](../projects/wget/src/main/ironwood/org/ironwood/wget/Response.iron)
borrows that input and the transport itself. Its destructor frees only location,
line and byte storage. It cannot reclaim the input or transport.

Response is destroyed on normal exit, status/allocation early returns, and
exceptions. Sink, once acquired, is destroyed first. A failed Response
construction rolls back completed owned buffers. The caller's transport close
and cleanup run after the helper's cleanup; the resulting ownership order is
the same as the inline block. The source's Sink close placement on early/error
paths is unchanged by extraction and is not a new transport lifetime issue.
Config is only observed; the failure diagnostic concerns transport.

## Compiler trace

Line numbers below refer to the baseline. `instrument.py` builds a separate
scratch copy of the Java compiler with diagnostic printing only. It records
final summaries, calls and exceptional joins; no analysis decision is changed.
`trace.py` retains the commands and output in `trace-*.log`. The ordinary compiler
independently produced all reported acceptance/rejection results.

1. [`SemanticAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SemanticAnalyzer.java),
   lines 250-309, builds provisional typed functions, resolves closed-world
   receiver/call targets with `BorrowDispatchAnalysis`, and refines escape,
   symbolic-return and owned-field facts before final lowering. The failing
   helpers are unambiguous static calls. Inherited constructor binding is correct.
   The tiny reductions remove sockets, overloads and dispatch from the failure.
2. [`FunctionAnalyzer.recordConstructorBorrows`](../compiler/src/main/java/ironwood/compiler/semantic/FunctionAnalyzer.java),
   line 7096, recognizes receiver-only retention in an encapsulated field and
   installs a wrapper-to-input loan. Successful free removes the wrapper's loans
   at line 1934. Constructor publication is separately recorded before the
   exceptional edge; successful receiver-only loans are installed after the
   constructor. This explains accepted inline cleanup and failed-constructor safety.
3. [`EscapeSummaryAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/EscapeSummaryAnalyzer.java),
   lines 770-809, treats `new` arguments as escaping whenever the constructor's
   `parameterEscapes` is true. That predicate includes `receiverRetainedParameters`
   at lines 1461-1463. There is no local wrapper/loan lifetime in this scan.
   [`SymbolicReturnOriginAnalyzer`](../compiler/src/main/java/ironwood/compiler/semantic/SymbolicReturnOriginAnalyzer.java),
   lines 433-465, independently calls `publish(argumentValue)` for the same
   receiver-retained input. Reclaiming the fresh wrapper later does not retract
   that publication. This is the decisive shared gap for Socket and Response.
4. The final Socket constructor summaries have no outside-receiver escaping
   parameters and retain parameter 0 in encapsulated `Socket.supplied`.
   Response retains parameters 0 and 1 in its private input/transport fields,
   without outside-receiver publication. Nevertheless both extracted helpers
   summarize caller parameter 0 in `parametersEscapingWithoutReturn`.
   Transport's final input summary correctly returns Socket/TLS view borrows
   rooted in `this`; the two view types do not imply two different owners.
5. The bindings case takes a different route. The final
   `getInterfaceAddresses` summary is a fresh result but has
   `thisEscapesWithoutReturn=true`. Its validated
   [`FreshBorrowingFactoryAnalysis`](../compiler/src/main/java/ironwood/compiler/semantic/FreshBorrowingFactoryAnalysis.java)
   proof simultaneously records an ArrayList whose elements borrow `THIS`.
   `FunctionAnalyzer.recordFreshBorrowingFactory`, line 8085, consumes that proof
   and installs a temporary list loan. The symbolic call-summary path at
   `SymbolicReturnOriginAnalyzer.callResult`, lines 581-632, instead propagates
   the coarse non-return effect. It has no equivalent factory-loan lifetime proof.
   This already rejects the create/free-only helper.
6. `FunctionAnalyzer.recordSingleRootListGet`, line 8128, maps a validated local
   unexposed exact ArrayList read to its sole borrowed root. Symbolic return
   analysis does not carry that list-content relationship: the observed `get`
   summary is `mayReturnNonOrigin=true`, with no borrowed return origin.
   `checkBindingList` consequently has **no returned borrow**, allows null and
   unknown return provenance, and marks parameter 0 as non-return escaping.
   Removing only the escape bit would still fail to preserve the result's lifetime.
7. `FunctionAnalyzer.recordResolvedCall`, lines 7891-7914, consumes those helper
   summaries and marks the caller allocation escaped. The generic free proof
   then reports the diagnostic. Exceptional merging at lines 2451 and 10811
   joins `ACTIVE` and `ESCAPED` states into `UNCERTAIN`, producing the additional
   conflict reason in Socket/wget. The traced joins for the caller owners contain
   no `FREED` input state. This is a consequence of the escape effect, not evidence
   of a changed owner or a separately established double-free bug. Other temporary
   allocations can have their own cleanup joins; they are not the reported owner.

Summary facts are reconstructed from source inside `.ironclass` and `.ironjar`
([`IronClass.source`](../compiler/src/main/java/ironwood/compiler/IronClass.java),
line 52). The artifact experiments preserve the same rejection; no serialization
or stale-artifact defect was identified. The normal launcher artifacts were also
rebuilt during the selected checks.

## Historical observations

`history.py`, `history-retry.py` and `history-more.py` extract matching compiler
and stdlib sources with read-only `git archive`, into temporary directories
outside this checkout. Java sources are built using `--release 21 -encoding UTF-8
-Xlint:all -Werror`. Each run sets `IRONWOOD_STDLIB_HOME` to its own snapshot,
preventing accidental discovery of the current stdlib artifacts.

| Revision | Constructor inline/helper, plain and finally | Single-root list inline/helper | Network list create/free helper |
| --- | --- | --- | --- |
| `43b2e3d`, initial source, Sep 8 | Accept / reject | Reject / reject | Not available/tested |
| `ddaaaec`, networking M1, Sep 14 | Accept / reject | Accept / reject | Not tested |
| `59b7b1b`, networking M3, Sep 15 | Accept / reject | Accept / reject | Reject |
| `1e249ee`, immediately before defer, Sep 19 | Accept / reject | Accept / reject | Reject |

The initial compiler lacks `--unfreed`; initial option-error attempts are saved
but excluded from the table. Successful reruns used its actual CLI without that
flag. Its list-inline failure was an unsupported element exposure proof, not a
successful historical baseline. The observations establish age, not a claim
that every larger modern library fixture was compiled unchanged at old commits.
The shared constructor reduction establishes the age of wget's missing proof;
no historical wget protocol execution was needed or claimed.

## Verification and proposed fix boundary

Seven selected existing checks passed after a normal bootstrap rebuild:

```sh
./scripts/test.sh \
  --test 'TCP extensions preserve factory and constructor ownership effects' \
  --test 'flat interface snapshots preserve owned element borrows' \
  --test 'host networking preserves snapshot and scoped address ownership' \
  --test 'wget URL and response ownership contracts' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'borrow dispatch rejects retaining and unknown receiver flows' \
  --test 'deferred free preserves ownership across cleanup predecessors'
```

Six accepted reductions additionally compile/link at `-O3` and run with the
expected results. Both exception fixtures run on normal and throwing paths.
Unsafe mutations remain rejected in each of `off`, `warn`, and `error`: published
wrapper, live wrapper, live returned wrapper, publication before a constructor
throws, unknown receiver with a possible retaining target, borrowed-element
use after owner destruction, independent element free, published element, and
published list. The nine variants give 27 negative case/mode checks.
No unfiltered suite, remote build or performance claim is involved. Source
license checks and `git diff --check` pass.

The investigation recommended two separately reviewable proof components:

- **Temporary wrapper confinement.** Model a proved fresh local borrower and its
  exact constructor inputs as loans, rather than immediately publishing inputs.
  Discharge a loan only when borrower destruction/rollback is proved on every
  relevant normal, returning and exceptional exit. Propagate publication of the
  borrower, its fields, callbacks or exceptions to its dependencies. Preserve
  real publication before constructor failure. Require all bound targets to
  satisfy the proof; never treat missing effects as non-retaining.
- **Temporary list provenance.** Reuse the validated fresh-list factory and
  single-root element proof in summary inference. Preserve list identity,
  content-root loans, exposure/mutation facts and returned element provenance
  separately. List destruction releases its loans; it cannot turn an entry into
  a fresh result or erase the entry's dependency on the original owner. Null can
  join the known root. Exposed, mixed or unknown roots remain conservative.

Prefer a shared compile-time proof over independent exemptions in the escape,
symbolic-return, owned-field and final-lowering consumers. Keep pool identity
proofs separate. Do not make constructors or collection methods universally
non-retaining, delete the exceptional-join diagnostic, infer ownership transfer,
or add runtime bookkeeping. Source/class/archive reconstruction and final
closed-world linking must consume the same facts.

The proposed regression coverage pairs each accepted extraction with
its inline equivalent, then reverse owner/borrower destruction, publish the
borrower or dependent result, return it beyond the owner's lifetime, free the
borrow directly, mix list roots, and introduce unknown/retaining dispatch.
Cover constructor failure after publication, helper exceptions, close failure,
normal/early returns, plain/finally/defer cleanup, unchanged captured operands,
and every unfreed mode. Keep direct borrowed-return and fresh borrowing-factory
controls. Use the existing tests above plus the pool helper/dispatch checks when
shared analysis changes; no full suite is warranted by this investigation.

The maintainer subsequently authorized both improvements and committing the
result. Existing scoped tests remain intact.

## Authorized implementation follow-up

D170 adds two compile-time proofs, with shared cleanup traversal:

- `TemporaryBorrowAnalysis` proves local constructor confinement, non-retaining
  bound uses, and destruction or rollback on all exits. Escape, symbolic-return,
  and owned-field analyses consume the same immutable site facts. A separate
  cleanup check rejects publication of retained fields by declaring/nestmate
  destructors, including unqualified field reads.
- `TemporaryListBorrowAnalysis` separates fresh list identity from the receiver
  or parameter root of all its borrowed elements. A validated `get` retains that
  root across helper returns, including loop aliases and clear/reinsert. It does
  not infer ownership transfer or waive independent publication.

Both derive facts from completed summaries before a subsequent refinement, and
all duplicated lowering sites must agree. The convergence check avoids another
summary rebuild when field and site proofs have stopped changing. No runtime or
backend changes are made. Only functions containing candidate constructions or
list results build the additional proof indexes; completed cleanup checks are
cached within the immutable field-analysis pass.

The reconstructed Socket, bindings and wget helper sources now compile. The
four original plain reductions remain historical boundary probes: constructor
and list helpers without protected cleanup still lack proof on throwing exits.
`Lifecycle.iron` and `ListLifecycle.iron` are registered, deterministic O3 tests
for the implemented cleanup lane. The latter checks 6,000 allocations for 2,000
list calls, exactly the list, backing array and existing reusable iterator per
call. Both verify stable live-allocation counts and exceptional cleanup.

Remaining bounds are deliberate: temporary lists require one known input root
and the audited operation set; mixed, exposed or unknown contents stay
conservative. Owned-field analysis retains its separate reentrancy restrictions.
General helper-refactoring completeness and absence of all future regressions
are not claimed.

### Implementation verification

Seventeen distinct focused compiler checks passed across the bounded runs:
the seven investigation checks above, the exact-dispatch and artifact-dispatch
checks, receiver-retained allocation diagnostics, pool-helper safety/artifact
checks, and these five new regressions:

```sh
./scripts/test.sh \
  --test 'temporary constructor helpers preserve mandatory ownership proofs' \
  --test 'temporary list helpers preserve element owners' \
  --test 'temporary constructor helper cleanup runs at O3' \
  --test 'temporary list helper cleanup runs at O3' \
  --test 'temporary borrower proofs survive artifact reconstruction'
```

The new checks cover protected and straight-line cleanup, early/throwing exits,
constructor/close failure, publication during destruction, private-field loans,
loop identities, list clear/reinsert, mixed roots, returned-entry lifetime and
independent-free rejection. Safety cases run in `off`, `warn`, and `error`.
Source, loose-class and archive reconstruction all link and run the two native
lifecycle fixtures at O3. The original nine unsafe mutations still reject in
all three modes (27 checks). No unfiltered suite or hosted build was run.
License and whitespace checks pass.

### Compiler cost and generated code

Measurements use the same host/toolchain and reconstructed inline sources as
the baseline, with an unchanged baseline compiler retained before editing.
Each row is the median of three interleaved baseline/current process runs;
RSS is the median of the per-process maximum resident set size from
`/usr/bin/time -l`. Default JVM heap settings were used. Timings include compiler
startup and class emission, not native linking.

| Workload and mode | Baseline seconds | Current seconds | Time change | Baseline RSS MiB | Current RSS MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Socket inline, error | 3.016 | 3.061 | +1.5% | 863.2 | 811.2 |
| Bindings inline, error | 3.089 | 2.969 | -3.9% | 867.4 | 899.8 |
| wget inline, error | 3.414 | 3.453 | +1.1% | 910.4 | 890.2 |
| wget inline, off | 3.225 | 3.330 | +3.3% | 875.5 | 943.9 |

The off-mode comparison includes the now-mandatory effect pass needed for
temporary cleanup proofs. The largest median RSS increase is 7.8%. Individual
RSS measurements and host timings vary considerably; this small sample is not
a universal overhead bound or evidence of a speedup in the negative-delta row.
The first implementation rebuilt an unchanged summary unnecessarily; the final
convergence check removes that work and candidate filtering avoids unrelated
proof indexes.

For both original inline reductions, baseline and final emitted LLVM IR and
O3 machine assembly are byte-identical. The native lifecycle tests additionally
check the new helper paths' exact allocation counts and stable live counts.
There are no runtime, backend, or per-operation instrumentation changes.
Detailed commands, complete logs, baseline artifacts and measurement scripts
remain in ignored `workspace/borrow-helper-fix/` (`final-measurements.json`,
`final-off-measurements.json`, `codegen-results.json`, and the focused-test logs).
