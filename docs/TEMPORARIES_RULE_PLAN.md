<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Unnamed temporary reclamation plan

Status: Proposed on 2026-09-25. Milestone 0, the contract review, was
completed by the maintainer on 2026-09-25 with the decisions recorded in
section 5. All four milestones and the example and project adoption were
implemented on 2026-09-25 with the results recorded in section 5. This document records
the design and the pre-change review required by
[AGENTS.md](../AGENTS.md#verification) and the
[regression lessons](POOL_RELEASE_HELPER_REGRESSION.md#lessons-for-future-changes)
for a change to the shared ownership analysis. The maintainer selects each
later milestone explicitly; work proceeds on the local `temporary-rule` branch
until the maintainer directs integration.

## 1. Problem

An allocation that is created and consumed inside one statement, without ever
being bound to a name, cannot be reclaimed without rewriting the statement:

```java
Sink.use(new Keeper());                      // warning: discarded without being freed
System.out.println("Hello " + name + "!");   // warning: concatenation result discarded
```

The only remedy is to introduce a local for the sole purpose of freeing it:

```java
Keeper keeper = new Keeper();
Sink.use(keeper);
free keeper;
```

Every Java-shaped program does this constantly, so the default `--unfreed=warn`
setting produces noise on ordinary code, and the `README.md` Hello World has to
apologize for warning about its own greeting. The earlier discussion rejected a
`--unfreed=fix` mode because a build flag must not change program behavior,
because a diagnostic is not a proof, and because a mode that inserts frees
creates a dialect whose remaining leaks become invisible. The alternative is a
language rule that holds in every mode and every build.

## 2. Accepted semantics, proposed

### 2.1 Definition

An **unnamed temporary** is a fresh allocation produced during the evaluation
of one full expression that, when the full expression completes, is not
observable through any local, parameter, field, static, array element,
container, pool, pending deferred operation, pending yield, return value, or
thrown exception. The producing expressions are exactly those the missing-free
tracker already registers: `new`, array creation and array initializers, dynamic
String concatenation results, and proven non-null fresh factory results.

A **full expression** is an expression that is not a subexpression of another
expression. The covered contexts are: an expression statement; an assignment
statement; a local variable initializer; the initializer of a field; the
condition of `if`, `while`, `do`,
and classic `for`; the initializer and update expressions of classic `for`; the
iterable of enhanced `for`; a `switch` selector; the operand of `return`,
`yield`, and `throw`; and the arguments of an explicit `this(...)` or
`super(...)` constructor invocation.

### 2.2 Rule

At the end of a full expression, the compiler reclaims each unnamed temporary
for which the ordinary D005 proof succeeds, exactly as if the programmer had
bound it to a hidden local and freed it there. When the proof fails, the
temporary stays allocated and today's diagnostics apply unchanged. A temporary
is either always reclaimed at that site or never; the decision does not depend
on the value of `--unfreed`, and it is identical in a source compile, a class
link, and an archive link.

If evaluation of the full expression is abandoned by an exception after the
temporary was created, the temporary is reclaimed on that exceptional path
only if it is reclaimed on the normal path and the proof also holds at every
point where an exception can leave the expression. The unwind cleanup uses the
same typed cleanup regions as the existing concatenation rendering protocol
and constructor rollback; there is no runtime action stack.

Reclamation runs the object's destructor chain, as any `free` does. A
programmer who needs the object to outlive the statement binds it to a local;
naming an allocation is the opt-out. `@SuppressUnfreed` is unaffected and still
applies only to declarations.

### 2.3 Consequences on the examples

```java
Sink.use(new Keeper());
// Keeper is reclaimed after use returns when use is proven non-retaining.
// If use retains it, nothing is reclaimed and no warning appears, as today.
// If the proof fails for another reason, the existing warning appears.

System.out.println("Hello " + name + "!");
// The concatenation result is reclaimed after println returns.

Shape s = make(new Config());
// Config is reclaimed after make returns; s is named and stays.

list.add(new Item());
// Item is retained by the container; nothing changes.

defer log(new Message());
// Message is a captured deferred operand; the rule does not apply.
```

### 2.4 Out of scope for the first version

- Reclaiming a temporary before the end of its full expression, as Mojo does
  at the last use. Statement-end timing is simpler to specify and to verify;
  earlier timing can be a later refinement with the same proof.
- Temporaries whose value flows through a branch-selected join, for example a
  conditional expression that allocates in both arms and is passed as an
  argument. That depends on
  [BRANCH_SELECTED_FREE_PLAN.md](BRANCH_SELECTED_FREE_PLAN.md).
- Temporaries in `defer` statements, lambda-like captures, and anonymous class
  creation arguments that the class retains. Existing rules apply.
- Any change to named allocations, `free`, `defer free`, containers, pools,
  owned fields, or the fresh factory protocol.

## 3. Pre-change review

### 3.1 Invariants that must survive

- D005 and D027: a synthesized free is accepted only by the same proof as a
  source `free`. The temporary rule never lowers the bar; it only applies the
  proof at a point the programmer could have written a `free`.
- D083: destructors run derived-to-root exactly once per reclaimed object.
  A temporary reclaimed on an unwind path must not also be reclaimed on the
  normal path, which the region structure in section 4.3 guarantees.
- D140 and D145: missing-free findings remain diagnostics. A temporary whose
  proof fails is still reported as today. Every `--unfreed` mode produces the
  same typed IR, so the existing mode-equality checks keep holding.
- D168: deferred operands are captured until cleanup; a temporary passed to a
  deferred call is a pending operand, not a candidate.
- D132 and D133: the emitted code is the free the programmer would have written
  plus a landing pad for the unwind path. No allocation, registry, TLS, or
  helper call is added on any valid path. The parity check in section 6 is
  mandatory before acceptance.
- Refinement monotonicity: provisional lowering may decline a temporary that
  final lowering accepts, never the reverse, because provisional summaries are
  at least as conservative as final ones. The one fact final lowering learns
  later, that a callee may reclaim its argument, comes from the closed-world
  effect analysis over provisional typed IR; the private release intrinsics
  that seed it are therefore also recognized by name in both rounds (section
  4.3). Section 3.5 lists what must be verified about analyses that consume
  provisional typed IR.

Accepted new semantics, distinct from implementation: unnamed temporaries are
reclaimed at the end of their full expression when provably unobserved, on
normal and exceptional completion alike. Regions, probes, and tracker changes
in section 4 are implementation and may change.

### 3.2 Machinery changed and its consumers

Producers changed:

- `FunctionAnalyzer.lowerFreeOperand`: split into a side-effect-free proof
  probe and a diagnostic renderer (section 4.1). Byte-identical diagnostics
  are the gate.
- A new full-expression wrapper used by every context in section 2.1, which
  opens a cleanup region per temporary and closes it at the end of the
  expression (sections 4.2 and 4.3).
- `UnfreedAllocationTracker` bookkeeping: reclaimed temporaries are consumed
  before the statement-boundary observation.

Consumers and the behavior each needs:

| Consumer | Effect | Required behavior |
| --- | --- | --- |
| Source `free` and `defer free` diagnostics, including `--explain-rejected-free` | The proof probe must not record evidence, selected reasons, bindings, or reclamation events | All explanation tests unchanged after the refactor; a probe that rejects leaves no trace |
| `emitCall` exception regions and `beginExceptionHandler` | Each temporary owns a nested region between its creation and the end of the full expression | Landing pads chain outward like constructor rollback; edges from before a later temporary existed never see that temporary |
| Constructor rollback | A failed constructor of the temporary itself rolls back and yields no value | The temporary's region opens only after the constructor call completes normally |
| Concatenation rendering cleanup | The rendered-text protocol already reclaims `toString()` temporaries inside a concatenation | Unchanged; a concatenation result and a fresh String operand are separate temporaries of the enclosing full expression |
| Missing-free tracker | Reclaimed temporaries must not be reported; declined ones must be | Consume on reclamation; optionally attach the probe's rejection as a note to the existing warning |
| `ClosedWorldEffectAnalyzer`, `BorrowDispatchAnalysis`, escape and symbolic-return summaries | See additional `IrFreeInstruction` operations on local temporaries in provisional and final IR | Destructor effects of temporaries join the function's effects; temporaries are never parameters, so argument-reclamation summaries are unchanged |
| Standard library and testing library sources | Reanalyzed with the rule; helpers that pass temporaries to non-retaining callees gain frees | Every existing library regression must keep its output; live-allocation baselines in tests may decrease and must be updated deliberately |
| Examples, projects, and documentation snippets | Programs that print `System.liveAllocationCount()` may print smaller numbers | Audit each check script; update expected output only where a temporary is now reclaimed |
| Class and archive reconstruction | Same source, same rule | Source, loose-class, and archive links behave identically |
| Language server | Fewer warnings | No other change |

### 3.3 Safe and unsafe pairs

Each accepted reclamation is paired with a nearby case that must not reclaim,
must still warn, or must still reject. Negative cases run in `off`, `warn`,
and `error`.

| Reclaimed, must be accepted | Not reclaimed, must keep today's behavior |
| --- | --- |
| `use(new Keeper());` with a non-retaining `use` | Same with `use` storing its argument in a static: no free, no warning |
| `new Keeper();` as an expression statement | `Keeper k = new Keeper();` with no free: named, warning as today |
| `println("Hello " + name);` | `String s = "Hello " + name;` with no free: named, warning as today |
| `use(new Keeper(), compute());` where `compute` throws: reclaimed on unwind | `use(compute(), new Keeper());` where `compute` throws: nothing allocated, nothing freed |
| `new Outer(new Inner());` where `Outer` does not store `Inner` | `new Outer(new Inner());` where `Outer` stores `Inner` in a field: constructor borrow, not reclaimed |
| `(a + b).length();` receiver temporary | `defer log(new Message());` captured deferred operand, not a candidate |
| `Shape s = make(new Config());` reclaims `Config` only | `Shape s = make(new Config());` where `make` retains `Config`: nothing reclaimed |
| `if (check(new Probe())) { ... }` reclaimed before the branch runs | `return wrap(new Payload());` where `wrap` returns its argument: escapes through the result |
| `while (poll(new Request())) { ... }` reclaimed every iteration | `list.add(new Item());` container borrow, unchanged |
| `use(fresh());` for a proven non-null fresh factory | `use(maybeNull());` nullable factory result, unchanged |
| Temporary with a destructor: destructor output observed once, on the right path | Temporary passed twice in one statement through a named alias: not unnamed, unchanged |
| `use(new int[4]);` array temporary | `arr[0] = new Item();` known array slot, unchanged |
| `throw new Failure(describe(new Detail()));` reclaims `Detail` after `describe` | `throw new Failure(new Detail());` where `Failure` stores `Detail`: retained |

Equivalent forms to compare, which must produce the same typed IR after the
change: `use(new Keeper());` against the hand-written hidden-local form
`Keeper k = new Keeper(); try { use(k); } finally { free k; }`, allowing for
label and value numbering.

### 3.4 Focused checks and expected outcomes

New registered tests, proposed names:

- `free proof probe renders identical diagnostics` (Milestone 1 gate: every
  existing rejected-free and explanation test passes unchanged; a dedicated
  test compiles the explanation fixtures with the probe and compares
  diagnostics byte for byte against recorded output).
- `unnamed temporaries reclaim arguments receivers and expression statements`
  (semantic, typed IR contains the free, all three unfreed modes produce the
  same IR).
- `unnamed temporaries keep retained escaped and named allocations` (the right
  column of 3.3, all three modes).
- `unnamed temporaries reclaim on exceptional paths` (native `-O3`: throwing
  later operands and throwing callees, destructor output order, live-allocation
  baseline restored).
- `unnamed temporaries cover every full-expression context` (each context in
  2.1 with a temporary, native run, live counts).
- `unnamed temporaries match handwritten cleanup` (typed IR and LLVM parity
  against the hidden-local form; `-O3` machine code inspection recorded in the
  verification notes).
- `unnamed temporaries survive artifact reconstruction` (source, loose class,
  archive links).
- `unnamed temporaries preserve provisional and final summaries` (a helper
  whose temporary has a destructor with effects; summaries and typed IR agree
  between rounds; refinement converges).

Existing tests expected to change their expectations, each reviewed
individually rather than adjusted mechanically:

- `unfreed diagnostics identify abandoned allocations`: the inline
  concatenation and discarded `new` cases stop warning.
- `unfreed options preserve native output and artifact diagnostics`: the CLI
  checks that expect a concatenation warning need a named allocation instead.
- `Java-shaped Hello World runs through System.out at O3` and the README
  narrative, if they assert the concatenation warning.
- Any example or project check that prints a live-allocation count affected by
  a reclaimed temporary.

Existing tests that must pass unchanged, because they cover the machinery
touched:

```sh
./scripts/test.sh \
  --test 'safe free accepts local allocation and ended aliases' \
  --test 'safe free rejects live aliases and escaped allocations' \
  --test 'safe free rejects unknown identities and uncertain control flow' \
  --test 'safe free rejects double free and post-free use' \
  --test 'safe free tracks ownership independently across duplicated finally paths' \
  --test 'safe free lowers to inspectable typed IR and LLVM' \
  --test 'safe free runs natively' \
  --test 'rejected free preserves escape and uncertainty reason selection' \
  --test 'rejected free keeps selected event sites across updates and restores' \
  --test 'rejected free call sites and missing identities use final local evidence' \
  --test 'rejected free explains conditional and short-circuit expression paths' \
  --test 'rejected free explains try catch and exception predecessors' \
  --test 'rejected frees identify the matched deferred-free binding' \
  --test 'rejected frees identify deferred-call capture roles and original values' \
  --test 'unfreed diagnostics preserve retained and reclaimed allocations' \
  --test 'unfreed diagnostics track receiver-retained allocations' \
  --test '@SuppressUnfreed follows allocations through aliases loops and finally' \
  --test 'deferred calls capture values while deferred free binds locals' \
  --test 'deferred free preserves ownership across cleanup predecessors' \
  --test 'combined defer cleanup preserves exits failures and live counts at O3' \
  --test 'String concatenation lowers through one typed exact-size operation' \
  --test 'Object String concatenation releases implicit rendering results' \
  --test 'Object and collection rendering reclaim temporary text at O3' \
  --test 'Throwable rendering allocation failures reclaim temporary messages' \
  --test 'destructors rollback and live counts lower to typed IR and LLVM' \
  --test 'deterministic destructors and rollback run at O3' \
  --test 'destructor and constructor effects are checked closed-world' \
  --test 'finally preserves primary exceptions and exposes secondary exceptions' \
  --test 'first-failure finally runs at O3' \
  --test 'standard-library caller-owned results teardown and rollback run at O3' \
  --test 'pool release helper proofs preserve mandatory safety' \
  --test 'owned reusable helpers remain dependent borrows across calls' \
  --test 'generic calls preserve conservative safe-free summaries'
git diff --check
./scripts/check-licenses.sh
```

The standard-library regression script in `docs/LOCAL_TESTING.md` runs once
per milestone because the library is reanalyzed under the rule.

### 3.5 Unverified boundaries, stated

- Whether any analysis consumes the provisional typed IR's free instructions in
  a way that a free added only in final lowering could contradict. The
  `ClosedWorldEffectAnalyzer` over bound functions is the known consumer; the
  new summary test in 3.4 is the check, and the review must read that analyzer
  before Milestone 2.
- A callee that reclaims its argument only through a private wrapper of a
  release intrinsic, such as `Files.releaseOwnedLines`, is known to final
  lowering alone. A temporary passed straight to such a wrapper would be freed
  provisionally and withheld finally. No such call exists: every wrapper is
  private to its library class and receives named values. The provisional
  consumers tolerate the extra free because a temporary has no parameter
  origin, the callee already carries the destructor's effects, and nothing can
  observe the value after the call.
- Statement-end timing means a temporary created early in a long expression
  stays allocated until the expression completes. This is a design choice, not
  a defect, and is recorded for the open question on timing.
- The probe refactor touches roughly three hundred lines of diagnostic
  rendering. The byte-identical gate is strong, but the recorded explanation
  fixtures do not cover every branch of that code; unexplained branches are
  noted in the verification record.

## 4. Design

### 4.1 Proof probe

`lowerFreeOperand` currently decides and reports in one pass. Extract a
`FreeProof probe(IrOperand operand, LocalSymbol symbol, Set<LocalSymbol>
expiredAliases)` that returns either `Accepted` or a structured `Rejected`
carrying the rejection kind (missing identity, dependent borrow, pending
deferred free, retaining owner, pending deferred call, pending yield, already
freed, blocked state with reason, attached owned field, known array slot,
local alias) and the witnesses the renderer needs. The probe reads ownership
state and evidence but writes nothing. `lowerFreeOperand` becomes probe, then
either the existing emission or the existing rendering of that rejection.

This refactor is Milestone 1 on its own, with no behavior change, because it
is the piece most likely to perturb diagnostics and because it is useful
independently: the branch-selected join plan and any future compiler-driven
reclamation need the same probe.

### 4.2 Candidate tracking

During a full expression, the analyzer records each registered fresh
allocation that completes successfully as a candidate, together with its
operand and creation span. Candidates are exactly the allocations the
missing-free tracker registers as completed. A candidate is not reclaimed
when something still observes it at the end of the full expression, named or
stored: the existing `name`, array slot, retained borrow, pool, escape,
deferred capture, yield, and return paths all already update tracker or
ownership state, and the end-of-expression check reads that state rather than
duplicating it. A name or slot assigned and cleared again inside the same
expression observes nothing at that point, so `use(saved = new K(), saved =
null)` reclaims the object on the normal path; the exceptional paths, where
the name may still hold it, are simulated separately (section 4.3).

### 4.3 Regions and emission

The full-expression wrapper wraps the lowering of the expression. For each
candidate, immediately after its producing operation completes normally, the
wrapper pushes an `ExceptionRegion` for that temporary, following the pattern
of `emitConstructorCallWithRollback` and the rendered-string cleanup region.
The region stays active until the end of the full expression. Regions nest in
creation order, so unwind cleanup runs in reverse creation order and an edge
from before a later temporary existed never reaches that temporary's landing
pad.

At the end of the full expression, for each candidate in reverse creation
order:

1. Run the probe on the candidate's operand with no symbol.
2. If accepted: emit `IrFreeInstruction` on the normal path through the same
   emission code as a source free, mark the allocation `FREED`, record the
   reclamation, and consume it in the tracker. Then close the region: if it
   has edges, emit a landing pad that frees the temporary and rethrows into
   the enclosing region; otherwise terminate the landing pad as unreachable.
3. If rejected: close the region with a landing pad that only rethrows and
   leave ownership state untouched. An observed allocation, named, stored,
   retained, or escaped, was never a temporary; the statement-boundary
   observation reports it as today, without a note. When nothing observes
   the allocation and the proof was merely uncertain, nothing can ever
   reclaim it, so it is reported at once as discarded with the rejection as
   a note: "temporary could not be reclaimed: ...". The ordinary observation
   would skip it, because it only reports active states.

Candidates are decided in reverse creation order and the pass repeats while a
free releases something: a wrapper created after its argument is freed first
and releases the argument's borrow, while an array container created before
its elements is freed first and releases its slots when nothing else has
observed the elements. Passing the array to a call that can observe
reference-array elements marks the elements escaped, so the container is
reclaimed but its elements are neither reclaimed nor reported, exactly as for
a named array passed to the same call. That limit predates this rule and is
not changed by it.

Two kinds of fresh result are never candidates. A `toString()` result rendered
inside a String concatenation belongs to the rendering protocol, which
releases it conditionally after the copy; registering it would double free.
An argument that a callee may itself reclaim, as reported by the closed-world
reclamation effects, is cancelled in every enclosing full expression. Those
effects exist only in final lowering, so a call to one of the private release
intrinsics that seed them (`Files.releaseOwnedLine` and its siblings,
`releaseRenderedString`, `Throwable.releaseLocalizedMessage`) cancels the
reclaimed argument by name in both rounds; otherwise provisional lowering
would emit a free that final lowering withholds.

An allocation made inside a branching expression, that is inside a conditional
expression, a switch expression, or a short-circuit operator, is not a
candidate either: its definition does not dominate the end of the statement,
so a free there would be invalid IR. Such allocations keep today's behavior.
Reclaiming them belongs with the branch-selected join plan.

Because the region is opened after the constructor call completes, a failed
constructor uses only its existing rollback and never reaches a temporary
landing pad. Each pad reclaims, in the same dependency order as the normal
path, every temporary of the statement that the normal path reclaimed and that
is also provably free at every edge recorded in that region, each edge
simulated in its own environment and ownership state. The normal path alone
is not enough: in `use(saved = new K(), boom(), saved = null)` the local
aliases the object where `boom` throws and no longer does at the end of the
statement. Simulating the whole set rather than the pad's own temporary is
also necessary: in `new Holder().set(new K(), boom())` the child's pad runs
first while the wrapper still retains it, and only the wrapper's release makes
the child reclaimable. Review found both gaps after Milestone 4; the per-edge
simulation and its regression tests close them. A pad's frees update the pad
state that the rethrow edge carries into the enclosing pad, so a temporary is
reclaimed at most once on any path.

### 4.4 Contexts

Every context in section 2.1 is routed through the wrapper. The wrapper is a
no-op when the expression registers no candidate, so statements without fresh
allocations produce identical typed IR. Conditions of loops are full
expressions evaluated per iteration; their regions open and close inside the
condition block. `return`, `yield`, and `throw` close their regions before the
transfer and its cleanup copies run, so a temporary never outlives the frame.

### 4.5 Diagnostics

No new diagnostic kinds. The existing "discarded without being freed" finding
remains for declined temporaries. When the allocation was unobserved and the
proof was uncertain, the finding carries the probe's rejection as one note,
using the same wording the rejected-free renderer would use for its primary
message. The note names the blocking fact only; it does not print
`--explain-rejected-free` witnesses, so that option's budgets and output are
unchanged. A first implementation attached the note to every declined
candidate, which mislabeled named and stored allocations as temporaries; the
full-suite run caught it and the condition was narrowed.

Implementation found that the note currently appears only under
`--unfreed=error`. The `Diagnostic` record discards notes on warnings by
contract, and D184 states that a warning carries no notes. The tracker
already records the reason for every declined temporary, and the finding
shows it when it is an error. Milestone 4 relaxes that contract so the note
also appears under `warn`, as Milestone 0 decided, and records the change in
the decision that accompanies this feature. In practice the note is rare
either way: the probe declines exactly the allocations the tracker already
treats as retained, so a declined temporary is usually reported only later,
when its container or array is freed without releasing it, and that finding
carries no note because the allocation was observed at its statement.

### 4.6 Refinement rounds

The wrapper runs in both provisional and final lowering. A temporary declined
provisionally and accepted finally adds a free only in the final IR. The
summary test in 3.4 exists to show that no consumer of the provisional IR
depends on the absence of that free. If the review in 3.5 finds one, the
fallback is to run the temporary rule only in final lowering and to treat the
destructor effects of candidates conservatively in provisional rounds.

## 5. Milestones and gates

### Milestone 0: contract review

Completed on 2026-09-25. The maintainer decided:

- **Timing:** reclaim at the end of the full expression, in reverse creation
  order, as section 2.2 states. Reclaiming after the consuming operation is
  not selected; it remains a possible later refinement with the same proof.
- **Factory results:** proven non-null fresh factory results are temporaries
  in the first version, alongside `new`, arrays, and dynamic concatenation.
  `use(make())` and `use(new X())` therefore behave alike.
- **Warning note:** a declined temporary keeps today's finding and carries
  the probe's rejection as one note, as section 4.5 specifies. Milestone 2
  found that the diagnostic contract limits the note to error mode;
  Milestone 4 relaxes the contract so the decision holds in `warn` as well.
- **Defer statements:** excluded outright. A temporary in a `defer` call is
  a captured pending operand and is not a candidate; the documentation tells
  programmers to name such objects. Reclaiming after the deferred call runs
  is not selected.

These decisions fix the accepted semantics in section 2. Later milestones
implement them and must not reopen them without a new maintainer decision.

### Milestone 1: proof probe refactor

Extract the probe with no behavior change. Gate: every listed rejected-free,
explanation, and safe-free test passes unchanged, the byte-identical
diagnostic test passes, and typed IR and LLVM for the explanation fixtures are
unchanged.

Implemented on 2026-09-25 on the `temporary-rule` branch. `lowerFreeOperand`
now dispatches on the result of `probeFree`, a query that returns a sealed
`FreeProof` value and writes nothing: no instructions, diagnostics, evidence,
reasons, bindings, or reclamation events. An accepted proof is emitted by
`emitProvenFree`, which carries the previous emission code unchanged. Each
rejection kind is rendered by a helper whose body is the previous diagnostic
code moved verbatim. The registered test `free proof probe renders identical
diagnostics` in `FreeProofProbeTests.java` locks the primary message, its
line, and every explanation note for all thirteen rejection kinds, using
output recorded from the compiler before the refactor.

Verification on macOS ARM64 with Java 21 and the pinned LLVM toolchain:

- Fourteen rejection fixtures compiled with explanations off and on before
  and after the change produced twenty-eight byte-identical stderr outputs.
- The forty-five listed safe-free, rejected-free, explanation, deferred, and
  unfreed tests passed unchanged, plus the new recorded-diagnostics test.
- `scripts/compare-explain-rejected-free.py` against the pre-change commit
  passed both fixtures, so class, archive, LLVM, and native outputs are
  unchanged for accepted programs.
- `git diff --check` is clean.

### Milestone 2: expression statements, initializers, and call arguments

Implement candidate tracking, regions, and emission for expression statements,
local initializers, and call and constructor arguments, on normal and
exceptional paths. Gate: the new semantic, exceptional-path, parity, and
summary tests pass; the standard-library script keeps its output; the examples
and projects check scripts pass with deliberately reviewed live-count updates.

Implemented on 2026-09-25 on the `temporary-rule` branch. Expression
statements, assignment statements, and local variable declarations are lowered
inside a temporary scope. Every completed `new`, array creation, array
initializer, dynamic concatenation result, and proven non-null fresh factory
result registers as a candidate and pushes its own cleanup region; regions
nest like constructor rollback, and a nesting check throws if a wrapper ever
drops one. At the end of the statement each candidate is probed, freed through
the ordinary emission on the normal path, and freed again only in its own
landing pad before rethrowing. Rendering results and callee-reclaimed
arguments are excluded as section 4.3 describes. The tracker consumes
reclaimed candidates and records the reason for declined ones.

Verification on macOS ARM64 with Java 21 and the pinned LLVM toolchain:

- The five new registered tests pass: reclaim, keep, exceptional paths,
  handwritten parity, and provisional/final summaries. The parity test
  compares the operation shape of `use(new Keeper(tag), boom())` with the
  explicit `try`/`finally` form and finds them identical apart from layout
  jumps and unreachable blocks.
- Three existing tests changed their expectations deliberately: the abandoned
  allocation test now expects five findings instead of nine, because the
  inline concatenation, factory, returned-concatenation, and nested cases are
  reclaimed; the CLI options test names its allocation so the warning it
  checks still exists; and the qualified-creation ordering test now reads
  calls from invoke terminators as well as instructions, because a call that
  follows a temporary inside the same statement unwinds to the temporary's
  cleanup pad. Forty-seven other listed tests passed unchanged, including the
  proof, explanation, deferred, concatenation, destructor, finally, pool,
  anonymous-class, and library tests.
- The standard-library suite passed its full run: 172 passed, 1 skipped,
  across 12 suites, with the library reanalyzed under the rule.
- Native probes: a program exercising an argument temporary, a discarded
  `new`, a factory result, a greeting concatenation, a constructor borrow, a
  static escape, a throwing later operand, and a named local reclaimed exactly
  the expected objects, ran four destructors, and warned only about the named
  local.
- Machine code: a loop of one million `Sink.use(new Keeper(index))` calls at
  `-O3` compiles to one `ironwood_allocate`, the inlined callee body, an
  inlined destructor dispatch that skips a null destructor slot, and one
  `ironwood_deallocate` per iteration, with the landing pads out of line after
  the return. The run finishes with zero live allocations.
- The example runner passed all 73 examples after the fix below, and the
  HelloEclipse, wget, SimpleTcpEcho, and OrderBook project suites passed.
  No example or project needed a live-count update.

The example runner exposed a pre-existing unsoundness in the shared escape
analysis, fixed in this milestone with a paired regression test. A synthesized
anonymous class constructor has no body and no recorded super invocation, so
its escape summary treated every parameter as unobserved. For an anonymous
subclass of a generic class such as `outer.new Inner<Token>(token) { ... }`,
the creation site therefore recorded neither an escape nor a borrow for the
argument, and a source `free token;` was accepted while the instance still held
it. The temporary rule turned that latent acceptance into an automatic
use-after-free in `examples/qualifiedanonymous`. The escape analyzer now
forwards each parameter of a synthesized anonymous constructor to the
superclass constructor's effects, exactly as an explicit super invocation
would. The registered test `anonymous class constructor arguments keep
superclass retention` checks that the source free is rejected and the temporary
form is not reclaimed for generic inner, generic top-level, non-generic, and
non-anonymous creations. The fix changes no accepted program's typed IR other
than by rejecting that unsafe free.

Two behaviors surfaced by the tests are worth knowing. A temporary retained by
a container or array is declined at its statement and reported later if the
container is freed without releasing it, exactly as a named element would be.
A temporary passed to a deferred call keeps today's finding once the deferred
call has run, because deferred operands are excluded by the Milestone 0
decision.

### Milestone 3: remaining contexts

Extend to conditions, `for` headers, enhanced `for`, switch selectors,
`return`, `yield`, `throw`, field initializers, and explicit constructor
invocations. Gate: the every-context test and reconstruction test pass.

Implemented on 2026-09-25 on the `temporary-rule` branch. Two lowering shapes
cover the contexts. A transfer operand, used for `return`, `yield`, `throw`,
switch selectors, and the enhanced-for source, reclaims the temporaries
consumed while computing the value and exempts the value's own allocation,
which moves on to the caller, the result phi, the handler, the dispatch, or
the loop. A condition, used for `if`, `while`, `do`, and classic `for`, is a
transfer operand whose boolean carries no allocation; in a condition that
binds pattern variables, the bound values are exempt, because they are
activated only after the condition and must outlive it, while the condition's
other temporaries are reclaimed as usual. Review found that the first
version skipped the scope for such conditions entirely, contrary to the
documents; the exemption is now per bound value. Classic `for` updates,
instance and static field stores, and the explicit `this(...)` or
`super(...)` invocation of a constructor body use the statement scope from
Milestone 2, so a field initializer's temporary is reclaimed after the store
and a delegation argument's temporary after the delegated constructor
returns.

Verification on macOS ARM64 with Java 21 and the pinned LLVM toolchain:

- The every-context test runs a native program with a temporary in an `if`,
  `while`, and `do` condition, in all three parts of a `for` header, in the
  source expression of an enhanced `for`, in a switch selector, in `yield`,
  in `throw`, in an instance and a static field initializer, and in `this`
  and `super` invocations. All twenty temporaries run their destructor and
  only the caught exception stays live, under `--unfreed=error`.
- The transferred-values test checks that a returned allocation, a widened
  returned allocation, a yielded allocation, a thrown allocation, a returned
  concatenation used as a String selector, a fresh array used as an
  enhanced-for source, and a pattern-bound value are never reclaimed, in typed
  IR and natively.
- The reconstruction test links the every-context program from loose classes
  and from an archive and gets the same output from both.
- The guard selection of Milestone 2, extended with the switch, loop, finally
  transfer, multi-catch, and constructor delegation tests, passed unchanged.
- The standard-library suite, all 73 examples, and the four project suites
  passed; see the entry below for the final counts.

### Milestone 4: documentation, decision, and adoption

Let warnings carry notes. The `Diagnostic` record keeps notes for any
diagnostic that has a source and span, instead of only for errors. Nothing
downstream depends on the old rule: the formatter already prints notes for any
diagnostic, the language server does not read notes, and the existing tests
that require empty notes concern explanation-off errors and keep passing. The
declined-temporary note then appears under `warn` and `error`, and the
temporaries test asserts it in both modes. D184's sentence that a warning
carries no notes is superseded by the decision below.

Record decision D185 or the next free number, refining D027's retention rule,
D140's exclusion of implicit destruction, and D168's statement that ordinary
`new` receives no automatic cleanup, each limited to unnamed temporaries, and
superseding D184's note-free warnings.
Update `docs/MEMORY.md` with an "Unnamed temporaries" section, the full
expression definition in `docs/LANGUAGE.md`, `docs/DIFFERENCES_FROM_JAVA.md`
with the destructor-timing note and the naming opt-out, the memory-management
note in `README.md` so the Hello World warns only about `chatter`, and every
occurrence of the relevant numbered feature in `docs/IRONWOOD_VS_JAVA.md`.
Simplify examples and projects that bind a local only to free it, in a
separate reviewed commit, as the defer adoption was done.

Implemented on 2026-09-25 on the `temporary-rule` branch, except for the
example and project adoption above. `Diagnostic` keeps notes for any located
primary; the structured-notes test now asserts that a located warning keeps
and renders its notes, and the temporaries test asserts the
declined-temporary note in `warn` and `error`. D185 records the rule, its exclusions, the diagnostic
contract change, the anonymous-constructor fix, and the performance evidence,
superseding D027, D140, and D168 for unnamed temporaries and D184 for
note-free warnings. `docs/MEMORY.md` gains an "Unnamed temporaries" section
and its opening paragraph names the second way an allocation is reclaimed;
`docs/LANGUAGE.md` states the rule under explicit memory reclamation;
`docs/DIFFERENCES_FROM_JAVA.md` records the destructor-timing consequence and
the naming opt-out; `docs/IRONWOOD_VS_JAVA.md` updates feature 10 and its
matrix row; `docs/MEMORY_MANAGEMENT.md` and `README.md` describe the greeting
as a temporary and warn only about the named `chatter`. The deterministic
benchmark comparison in section 6 was closed by reasoning rather than by a
run; section 6 records why.

Adoption, done as its own change on 2026-09-25: fifteen examples and three
projects drop a local that existed only to be freed, where the value is
consumed by one call, receiver, or `println` in the next statement and the
inlined form reads at least as well. Examples whose subject is reclamation,
deferred cleanup, resources, allocation failure, owned helper borrows, or
String concatenation keep their explicit frees, as do the benchmark baselines.
Candidates inside a short-circuit or conditional expression, a socket address
that its endpoint may retain, and a named argument that a comment presents as
deliberate were left unchanged. Every changed example and project keeps its
output and exit status under its own compile options.

## 6. Performance acceptance

The rule must produce the same code as the hidden-local form. Before
acceptance of Milestone 2, record:

- Typed IR and LLVM text comparison between `use(new Keeper());` and the
  explicit `try`/`finally` form, allowing only label and value numbering.
- `-O3` machine code inspection of one hot loop that passes a temporary to a
  non-retaining callee, confirming one allocation and one free per iteration
  and a cold landing pad.
- A deterministic benchmark from `docs/PERFORMANCE_IMPROVEMENTS.md` or an
  existing example that exercises temporaries, compared before and after,
  reported as unchanged or improved. Memory pressure should fall for
  programs that previously leaked temporaries in loops.

Any regression on a valid path requires maintainer review before proceeding.

Milestone 2 recorded the typed IR parity test and the `-O3` machine code
inspection above. The deterministic benchmark comparison was closed on
2026-09-25 by the maintainer's decision without a measured run, on the
following reasoning, which the machine code inspection supports.

- Code that already reclaimed every allocation is untouched. A named local
  with its own `free` was never a candidate. OrderBook's hot paths are asserted
  allocation-free by its own tests, and the bench programs compile under
  `--unfreed=error`, so the existing deterministic benchmarks contain no
  temporary the rule could reclaim and a comparison would measure only noise.
- Where the rule reclaims a temporary, it emits exactly the free a programmer
  would have written: the parity test shows the typed IR of the hidden-local
  `try`/`finally` form, and the hot-loop probe compiles to one allocation and
  one deallocation per iteration with the destructor dispatch inlined. A loop
  that previously leaked its temporaries now does one deallocation more per
  iteration and stops growing its memory, which is the intended trade and a
  net win for any long-running program.
- A declined temporary costs nothing on the executed path. Its only effect on
  the shape of the code is a call that becomes an invoke into a cold,
  rethrow-only landing pad. The measured `-O3` probe of a declined temporary
  followed by a call produced machine code identical to the named form and an
  executable of the same size.
- No counters, registries, thread-local state, or helper calls were added on
  any valid path, which is what D132 and D133 forbid.

Review findings after Milestone 4, all fixed with regression tests: the pad
free was decided from the normal-path state, so an alias created and cleared
inside the statement dangled on the exceptional path (now the proof must hold
at every unwind edge); a pad emitted a bare free without applying its
ownership consequences, so a child retained by a wrapper freed in the pad
leaked on every caught failure (the pad now uses the ordinary emission); and
an unobserved candidate the proof could not decide produced neither a
reclamation nor a finding (it is now reported at its statement); a child
created after the wrapper that retained it leaked on the exceptional path
because its pad ran before the wrapper's (each pad now reclaims every
candidate free at all of its edges, in dependency order); a use of a value
belonging to a reclaimed temporary, such as an owned field it returned, was
rejected as a use after free with no hint that the program contained no
`free` (the error now names the temporary's creation site and the remedy);
the README still described the greeting as leaking; a condition binding a
pattern variable opened no scope at all, so its other temporaries were not
reclaimed although the documents exempt only the bound value (the exemption
is now per bound value); the assignment-statement context missing from
section 2.1; the classic `for` initializer, covered by the implementation
and the plan but missing from D185, the memory model, and the language
contract; the naming opt-out stated without its timing, so section 4.2 and
the summaries in D185, the memory model, and the language contract read as
if any assignment to a local cancelled the candidate, while the definition,
the implementation, and the unwind example reclaim a name assigned and
cleared again inside the same expression (the documents now say the name
must still hold the object when the full expression completes, pinned by a
test); an allocation published on one path and cleared on the other reported
as a declined temporary, because the join blurs its state to uncertain and the
report tested only the final state (an escape on any path now cancels the
candidate, a fact no join can lose); and section 4.3 overstating that a freed array container always
releases its slots, when a call observing the array leaves the elements
escaped and unreclaimed for temporary and named arrays alike (the sentence
now states the limit); and the cancellation of an argument a callee may
reclaim depending on the effect analysis, which provisional lowering lacks,
so a temporary passed straight to a release intrinsic was freed provisionally
and withheld finally, the reverse of section 3.1 (the intrinsics are now
recognized by name in both rounds, and the transitive remainder is recorded in
section 3.5).

The one program shape that can measure a slowdown is a short-lived
microbenchmark that leaked temporaries in a tight loop on purpose. It now
pays for each free. Leaking was never a supported performance technique;
the idiom for allocation in a hot loop is a hoisted or pooled named object,
which the rule does not touch. Naming the object remains the opt-out.

## 7. Estimated size and risk

Milestone 1 is a refactor of roughly three hundred lines with a strong gate.
Milestones 2 and 3 add roughly two hundred lines in `FunctionAnalyzer` plus
tests. Milestone 4 is documentation and expectation updates across tests,
examples, and the README. Risk is moderate and concentrated in two places: the
probe refactor, guarded by byte-identical diagnostics, and the interaction
with provisional summaries, guarded by the dedicated summary test and the
fallback in section 4.6. The rule is mode-independent and proof-gated, so it
cannot introduce an unsafe free; the failure modes are a missed reclamation or
a disturbed diagnostic, both visible to tests.
