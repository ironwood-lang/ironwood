# Memory model direction

For a short practical guide, see [Memory management](MEMORY_MANAGEMENT.md).

Ironwood uses Java-like object creation, identity, nullable references, aliasing,
fields, parameters, and returns, but it deliberately has no garbage collector.
An ordinary allocation is freed before process termination only by a
compiler-proven-safe source `free`, or, for an
[unnamed temporary](#unnamed-temporaries), by the compiler at the end of the
full expression that created it. Becoming unreachable does not free it.
Programs may omit `free`, but named allocations then remain allocated; a
program that continues allocating without freeing enough memory eventually
exhausts the allocator and terminates.

## Ownership and borrowing

Ironwood does not support general ownership transfer for an existing allocation.
Once ownership is established, passing an owned reference as an argument or
retaining it in another object's field does not make the recipient an owner.
When closed-world analysis proves the relationship, the compiler tracks the
recipient as a borrow; otherwise it conservatively treats the reference as
escaped. In neither case may the recipient reclaim the allocation. The original
owning scope or object remains responsible for `free` after every observable
borrow and alias has ended.

A fresh allocation returned without aliases may establish ownership in the
caller, and an object may own children allocated internally in compiler-proven
owned fields. These operations establish ownership for fresh allocations; they
do not transfer an existing caller-owned allocation to a new owner. Ordinary
container insertion and receiver-field retention remain borrowing operations
unless a documented specialized compiler contract states otherwise.

## Missing-free diagnostics

The `--unfreed` option (D140) controls how the compiler reports known local
allocations that are not freed:

| Option | Behavior |
| --- | --- |
| `--unfreed=warn` (default) | Report warnings and allow compilation or linking to succeed if there are no errors. |
| `--unfreed=off` | Suppress missing-free diagnostics for intentional omissions. |
| `--unfreed=error` | Report the same findings as errors and fail the command before writing output. |

Both source compilation and native linking accept the option. The setting
applies to that invocation only; it is not stored in `.ironclass` or `.ironjar`
files. Linking checks reconstructed source again, so compiling with
`--unfreed=off` does not silence a later link: pass it to both commands when
suppression is intended. Warnings are printed to standard error.

Every mode preserves mandatory errors for unsafe reclamation, use after free,
and double free. If the compiler cannot prove a `free` safe, it rejects it.
These options do not change runtime allocation behavior: unnamed temporaries
are reclaimed identically in every mode, and named allocations never are.

The diagnostic reports a known local allocation when its last tracked reference
is discarded, overwritten, or leaves scope without being freed. It points to
the allocation expression and describes abandonment without assuming that the
omission was accidental.

The first version covers source `new`, arrays, dynamic concatenation, and
non-null factory results already proven fresh. It recognizes local aliases,
known array slots, constructor/container borrows, proven receiver-only method
borrows, pool adoption, completed `finally` cleanup, and implicit rendering or
library reclamation. Returning or externally publishing an allocation moves it
outside the local abandonment proof; that is not evidence of eventual
reclamation. Unknown identities/effects, mixed or nullable factory results,
conflicting branch states, and uncertain loop or exception transfers are not
diagnosed solely because cleanup is unproved.
This is a conservative local diagnostic, not a guarantee of program-wide leak
freedom. In particular, conditional cleanup and outward exceptional exits are
not exhaustively checked. Ordinary comments do not suppress findings.

### Per-allocation suppression

The built-in `@SuppressUnfreed` directive (D145) exempts the allocation tracked
for a reference local variable's initializer from missing-free diagnostics,
including errors under `--unfreed=error`:

```java
@SuppressUnfreed
State state = new State();
State alias = state;
state = null;
alias = null; // No missing-free finding for this allocation.
```

The directive takes no arguments and precedes the local's type, on the same
line or a preceding line, optionally interleaved with `final`. It also accepts
classic `for` local initializers. Duplicate directives, primitive locals,
fields, methods, types, parameters, catch parameters, enhanced-for variables,
and expression statements are rejected. `SuppressUnfreed` remains an ordinary
identifier outside this contextual syntax; no import or annotation type is
needed.

Suppression follows that one allocation through existing alias tracking. A
marked alias of an already tracked allocation also exempts that allocation,
including findings from other branches. This is a compile-time exemption,
independent of branch analysis order.
It does not exempt later allocations assigned to the variable, other locals,
objects held inside an array or container, temporary allocations used to
compute the initializer, or unrelated allocations inside called methods. A
null, immortal, or otherwise untracked initializer creates no exemption for
future assignments. Existing diagnostic coverage and analysis limits remain
unchanged; the directive does not add new identity or escape inference.

Every execution of the marked declaration covers its initializer's tracked
allocation, including repeated execution in a loop. This is an explicit
diagnostic exemption and can hide repeated leaks. It is not proof of eventual
cleanup or bounded memory use. Mandatory safe-`free`, use-after-free, and
double-free errors remain unchanged, as do allocation lifetime, destruction,
typed IR, LLVM output, and optimization decisions. No runtime metadata or
automatic cleanup is added.

Unlike the per-invocation `--unfreed` setting, the directive survives in the
source preserved by `.ironclass` and `.ironjar` files. A later native link
respects it even with `--unfreed=error`; other allocations still receive that
invocation's configured diagnostics.

## Unnamed temporaries

An unnamed temporary (D185) is a fresh allocation produced while evaluating
one full expression that nothing can observe once that expression completes:
no local, parameter, field, static, array element, container, pool, pending
deferred operation, pending result, return value, or thrown exception. The
producing expressions are the ones the missing-free tracker registers: `new`,
array creation and array initializers, dynamic String concatenation results,
and proven non-null fresh factory results.

A full expression is an expression that is not part of another expression:
an expression statement, an assignment statement, a local variable
initializer, a field initializer, the condition of `if`, `while`, `do`, or
classic `for`, a classic `for` update, the source of an enhanced `for`, a
switch selector, the operand of `return`, `yield`, or `throw`, or an explicit
`this(...)` or `super(...)` invocation.

At the end of the full expression the compiler reclaims each temporary for
which the ordinary safe-`free` proof succeeds, in reverse creation order,
exactly as a hidden local freed at that point would be. The destructor chain
runs. If the full expression is abandoned by an exception after the temporary
was created, the temporary is reclaimed on that path too, when and only when
it is reclaimed on normal completion. The rule is the same in every `--unfreed`
mode and in source, class, and archive links.

```java
Sink.use(new Keeper());
// Keeper is reclaimed after use returns when use is proven non-retaining.
System.out.println("Hello " + name + "!");
// The concatenation result is reclaimed after println returns.
Shape shape = make(new Config());
// Config is reclaimed after make returns; shape is named and stays.
list.add(new Item());
// Item is retained by the container; nothing changes.
```

A temporary the proof declines stays allocated and keeps the ordinary
missing-free finding, which carries the blocking fact as a note, for example
`temporary could not be reclaimed: allocation is still borrowed by a live
container`. In practice a declined temporary is reported only later, when the
container or array that retained it is freed without releasing it.

Naming an allocation is the opt-out: `Keeper keeper = new Keeper();` keeps the
object until a source `free` or process termination, and reports it as today.
The following are never candidates: a value that moves on through `return`,
`yield`, `throw`, a switch selector, or an enhanced-for source; the value bound
by a pattern condition such as `make() instanceof Keeper k`; a captured
`defer` operand; a `toString()` result rendered inside a concatenation, which
the rendering protocol releases; an argument a callee may itself reclaim; and
an allocation made inside a conditional, switch, or short-circuit expression.
Those keep the behavior described elsewhere in this document.

## Rejected-free explanations

Pass the bare `--explain-rejected-free` flag to a source compilation or native
link to request notes on eligible unsafe reclamation errors. It is off by
default and applies only to that invocation, including when classes or archives
are reanalyzed at link. It neither changes the primary diagnostic nor makes an
unsafe `free` legal. `--unfreed` and `@SuppressUnfreed` do not disable mandatory
safety errors or their requested notes. Successful commands print no explanation
report, though enabled evidence collection has an optional compile-time cost.
The [worked examples](EXPLAIN_REJECTED_FREE_EXAMPLES.md) show a source
compilation and a link that each reject a `free`.

Current detailed notes identify a live local alias at its latest supported
binding site, a unique earlier free on the current path, selected direct
field/static/array stores, a known array element store, or a conditional
reference when their source is retained. Final local call effects identify the
selected argument or receiver at its operand site, including constructors and
multiline operands. A missing allocation identity identifies a parameter or
current expression origin when its span is available; this does not claim an
unknown result is fresh. For a supported final method effect, the compiler
can follow up to four source-backed call-summary hops to a publication or an
explicit evidence boundary. Possible dispatch targets are labeled as such;
without an entry point, compatible retaining targets can remain possible.
Retaining container,
wrapper, and dependent-helper notes use the current proven owner name, or its
type and creation site after reassignment. They point at the insertion,
setter, factory, or helper source expression. Attached private-field notes
name the field and its current load site. Where the selected whole-class field
checker retained a failed predicate, another note locates that non-fresh write,
publication, return, throw, call, constructor condition, or reentrant access.
This is additional proof context, not a replacement for the selected primary.
A supported field-call chain can cross from bundled library source to a user
override and its store. A compatible dispatch target is possible, not proof
that the program called it. Successful owner cleanup ends its borrow. A proved
checkout note names the originating pool and acquisition site, including aliases,
and distinguishes same-pool return from destruction. A rejected independent
free after an external
`release` points to that argument and states the conservative lifetime boundary;
D104 does not promise cleanup of an external object. The compiler also attaches
a boundary note to eligible ordinary/deferred frees, destructor field cleanup,
loop back-edge checks, and owned-array element validation when its selected cause
cannot be located. Skipped ownership refinement instead reports a
limited-analysis note and asks for earlier errors to be fixed first.
Bounded incoming-path alternatives, deferred actions, cleanup exits, and loop
back edges have their own supported notes. The late owned-element validator
locates the selected failed load, store, copy, call, or exit when its source is
available, followed by the recognized destructor element-free site. Repeated
objects also identify their first recording store. Ordinary source that frees
an attached element is rejected earlier by array-alias safety. Other escape
and uncertainty operations, constructor-internal chains, and unsupported
whole-class field predicates remain outside current detailed coverage; a
boundary note does not imply that another blocker is absent.

Parser and type errors, wrong-pool transfers, pending-deferred-local writes,
standalone use-after-free errors, and missing-free warnings do not receive these
notes. Located notes have their own source and excerpt, including for a
dependency; at most eight notes are emitted per primary. Evidence collection is
bounded per function and invocation. If a cap or missing context prevents a
supported detail, the diagnostic says so without relaxing the free check.

| Explanation limit | Current default |
| --- | ---: |
| Function-local live evidence | 65,536 accounting units per callable |
| Saved-snapshot associations | 65,536 units per callable |
| Summary witnesses | 2,048 units per method; 64 units per fact/dependency chain |
| Invocation emergency stop | 1,048,576 simultaneously live units |
| Rendered detail | Eight notes per primary; four supported summary-call hops; at most six retained ordinary join alternatives |

These units count retained evidence associations and references, not heap
bytes. The provisional M0 limits of 4,096 function units, 2,048 snapshot
associations, and 1,024 per-field units are not current defaults. Selected
whole-class field failures share the invocation budget; the late owned-element
validator uses its existing IR facts without a persistent witness map. Join
alternatives beyond the retained representatives are identified as omitted;
the invocation stop reports its own limit rather than silently discarding a
proof. No limit changes mandatory reclamation checks or their primary errors.

Summary and private-field evidence from discarded refinement rounds is retired;
the separate invocation stop counts simultaneously live evidence, including
function-local collection and dispatch fallback. The
[verification record](EXPLAIN_REJECTED_FREE_VERIFICATION.md) reports measured
headroom and compiler cost. The [implementation plan](EXPLAIN_REJECTED_FREE.md)
records detailed evidence contracts and supported boundaries.

## Allocation and reclamation

Object allocation and deallocation remain behind the isolated
`ironwood_allocate` and `ironwood_deallocate` C ABI. The bootstrap allocator uses
zeroing `calloc`; a failed source-evaluated allocation raises the compiler-owned
immortal `OutOfMemoryError`, while accepted source `free` operations lower to
an LLVM destructor-dispatch helper and then the deallocation boundary.
Compiler-owned typed predicates
and exceptional control flow turn null use, invalid array indexing, negative
array lengths, and `throw null` into catchable language exceptions before an
unsafe runtime operation is reached. These are internal runtime mechanics, not
a stable object ABI.
Feature 105 implements that bounded catchable-exhaustion path with one
immortal, non-freeable `OutOfMemoryError` and runtime-private emergency storage.
The failure object and its bounded trace/association storage do not cross the
ordinary allocator or affect allocation counts. A failed allocation produces no
owned source object; another allocation failure while the implicit error is
active terminates deterministically. Pre-entry setup, runtime-private metadata,
and operating-system termination remain outside the catchable boundary.

`System.allocationCount()` exposes a read-only diagnostic snapshot backed by an
atomic process-wide counter. Each successful `ironwood_allocate` or
`ironwood_allocate_array` increments it exactly once; runtime-created Strings use
the ordinary object boundary and therefore count once as well, including one
count for each dynamic String-concatenation result. Compiler-emitted
immortal String literals and runtime-private native exception wrappers do not
cross either language allocation boundary and are excluded. The counter is not
an ownership mechanism and does not change `free` analysis or reclamation.
`System.liveAllocationCount()` is the corresponding current-live diagnostic:
successful ordinary allocations increment it and each non-null runtime
deallocation decrements it. Immortal and runtime-private storage is excluded
from both counters.
The compiler-emitted `System.out` and `System.err` `PrintStream` objects are
likewise immortal and excluded: loading either creates no language allocation,
and its unknown local allocation provenance makes any source `free` fail the
proof.

Arrays are ordinary reclaimable allocations with the same leading type-descriptor
pointer as every object, followed by a native length, compiler-provided element
size/kind metadata, and zero-initialized contiguous elements. Array element types
may themselves be arrays; an outer `new T[n][]` still allocates exactly one
container whose pointer-valued child slots begin as `null`. Per-array-type
compiler metadata carries the exact recursive array identity, `Object`
membership, and inherited Object dispatch entries. Reifiable array casts/tests
compare the leading descriptor pointer exactly and add no runtime registry. The
compiler branches on a nonnegative-length predicate before calling
`ironwood_allocate_array` and on null/bounds predicates before accessing an
array. Failure-only exception construction uses the ordinary allocation
boundary and existing unwind machinery. The array allocator still defends its
internal size calculation. `free` on an array releases only the container,
never objects referenced by its
elements. An array initializer allocates one ordinary exact-length container for
each written brace level before evaluating that brace's elements, then evaluates,
converts, and stores them from left to right. A direct store into a constant slot
of a known local array, including a nested initializer store, records a visible
child alias, and a matching load preserves that allocation identity.
The child cannot be freed while installed; overwriting the slot with `null` or
freeing the outer container proves detachment. Dynamic indices, observable
calls, copies, and escaped containers remain conservative. Primitive-array
elements create no aliases. `System.arraycopy`
copies reference slots as aliases and never transfers ownership. Since the
current proof does not propagate per-element identity through a bulk copy, it
conservatively treats the destination array argument as escaping and rejects a
later `free` of that tracked destination. This restriction also propagates
through forwarding helpers as a non-return call effect; a void result does not
make the call borrowing. Proven primitive-array copies do not publish a helper's
caller-owned buffers (D094), and the private backing-array detachment proof
continues to distinguish container identity from copied element aliases (D041).
Symbolic return analysis preserves primitive element types for local variables,
allocations and casts as well as parameters and fields. A fresh primitive-array
copy returned by a factory remains caller-owned unless it is published elsewhere.
Reference arrays and multidimensional arrays do not qualify for this primitive
element proof.

Primitive generic arguments use the same allocation model without boxing. A
`new Box<int>(value)` operation creates exactly one ordinary object; its
specialized field stores the native `int` directly and no wrapper allocation is
added to `System.allocationCount()`. Used class, method, constructor, interface,
and array shapes are specialized in compiler-owned typed IR before LLVM
lowering. Reference positions retain pointer storage, and static state remains
owned by the unspecialized declaration. Primitive values in fields, parameters,
returns, phis, and array elements create no aliases for safe-`free`; the
containing object or array remains an ordinary tracked reference allocation.
A primitive-specialized object does not carry the raw generic wildcard
membership bit, so it cannot be cast into a pointer-shaped `G<?>` view. No
runtime parameterized-type registry, value tag, wrapper, or hidden lifetime is
introduced.

String literals use a separate ownership category. Each distinct decoded UTF-8
literal is one compiler-emitted immutable `ironwood.lang.String` object with
static UTF-16 tail storage. It is immortal, is not returned by an allocator, and
cannot pass the `free` proof. Equal literal contents in one final program share
this identity. Standard output observes and encodes that static tail
synchronously and never retains the reference. D059 names this finite
compile/link-time canonicalization Feature 75; it is not a mutable runtime pool
and arbitrary runtime Strings cannot be added to it with Java's
`String.intern()`.

Feature 76 implements source String concatenation without hiding an ordinary
builder graph. A constant expression reuses the immortal literal category.
A dynamic concatenation chain instead creates one exact-size ordinary String
result with compiler-visible allocation provenance. The result remains subject
to normal escape and safe-`free` analysis and is never added to the literal
pool. If an object operand produces fresh owned text through `toString()`, that
text is a compiler-managed temporary. It is conditionally reclaimed after its
contents are copied, and cleanup also runs if a later object conversion or the
final result allocation throws. A borrowed or mixed-result override is never
reclaimed by this protocol.
When fixed-point return analysis finds that a method returns a dynamic binary
concatenation, the same allocation provenance follows the result through the
call. A nonescaping result can therefore be freed by its caller. The proof is
not granted to folded constants, borrowed or mixed return paths, published
results, or values with live aliases.

Enum constants use a related compiler-owned immortal category. Each retained
constant has one statically emitted ordinary-layout object whose hidden pooled
name and ordinal are preinitialized and whose source fields are initialized by
its enum constructor. It never crosses `ironwood_allocate`, never changes
`System.allocationCount()`, and cannot pass the source `free` proof. Its
source-visible static-final field is null until construction succeeds, then
retains that permanent identity. Enum lookup and traversal return these same
objects without allocating an array or wrapper. Feature 74 preserves this
ownership category for constant-specific bodies: a bodied constant uses its
compiler-owned final subtype layout but remains one
immortal, allocation-count-neutral object that source cannot `free`.

Each allocation begins with one compiler-owned type-descriptor pointer, followed
by inherited fields in root-first order and then the class's declared fields in
source order. The descriptor points to immutable compiler-emitted type
membership and unified dispatch tables plus the qualified diagnostic type name.
It is installed after the complete object is zeroed and before the root-to-leaf
constructor chain runs, so widening class/interface references, virtual
dispatch, and `instanceof` do not change the object address. The compiler-emitted
type-test helper returns false for `null`.

Default `Object.hashCode()` derives a mixed opaque 32-bit value from allocation
identity; it is stable for the allocation's lifetime but is neither unique nor a
source-visible address. Default `Object.toString()` allocates an ordinary
`ironwood.lang.String` containing the qualified runtime type, `@`, and the
lowercase hexadecimal form of that same hash. No collector reclaims this string
automatically.
The default object-to-string runtime boundary returns a fresh caller-owned
String with compiler-visible allocation identity. The result may be reclaimed
after all aliases are dead; an override retains its normally analyzed return
contract.
`StringBuilder.append(Object)` and `PrintStream.print(Object)`/`println(Object)`
consume that contract internally. Each concrete object/array descriptor records
whether its closed-world `toString()` target always returns fresh, unescaped
text. After consuming the text, the runtime reclaims it only when that bit is
set; an identity, borrowed, or mixed-result override is left untouched. This is
a narrow library ownership protocol, not general automatic reclamation.
An override returning a dynamic binary concatenation can set that bit when the
same return proof shows every non-null result is fresh and unescaped.
For the two PrintStream Object overloads, safe-`free` analysis separately
specializes the outer call from the argument's static type. The call does not
publish the object when every possible closed-world `toString()` target has no
non-return receiver escape. Returning borrowed text does not itself publish the
object because PrintStream consumes that text synchronously. An unknown target
or an override that stores, throws, or otherwise publishes its receiver keeps
the conservative argument-escape rejection.

`Throwable.toString()` likewise returns a fresh caller-owned description. Its
virtual `getLocalizedMessage()` result is copied, then released only when a
second concrete-descriptor bit proves that getter returns fresh, unescaped text.
For the default localized getter, analysis resolves the concrete `getMessage()`
override. A deferred helper call releases a proven owned message even if description
allocation fails. Borrowed, retained, unknown, and mixed-result messages remain
untouched. The description facade receives an audited receiver-borrowing
contract only when all closed-world message getters lack non-return receiver
escapes. A getter that publishes its receiver therefore still blocks `free`.
This extends D089's narrow consumption protocol under D114, with no registry or
general reclamation rule.

`ByteArrayOutputStream.toString()` copies its written byte prefix directly into
one fresh String through a private UTF-8 decoding operation (D115). The result
retains no reference to the stream or its buffer, remains valid after either is
reclaimed, and participates in existing rendering-result cleanup. Empty input
also produces a fresh result. Failed result allocation leaves the stream and
buffer unchanged and creates no helper allocation to reclaim.

`System.identityHashCode(Object)` uses the same allocation-identity mixer,
returns zero for `null`, and bypasses overrides.

Array-backed pools and collections deterministically reclaim a superseded
backing array after migration. The compiler first proves the private field's
container identity is never published, tracks a local loan of the current array,
requires a fresh replacement to detach that loan, and rejects `free` while any
other local alias remains. Migration may copy or relink reference elements, but
freeing the old array releases only the array container. Collections borrow user
elements. D104 gives the two bundled pools a creation-only owning contract:
`get()` lends an object, and `release(object)` returns a checkout for reuse.
Every non-null builder result is recorded once in a growing creation array.
Pool destruction destroys those objects, including checked-out objects, and all
private storage. Multi-array segment holders are recorded too; they shallow-free
their segment arrays. The builder remains borrowed.

The pooled `ironwood.ds` maps and linked lists own both their internal pool and
its builder. The container destroys the pool before the builder; the pool
destroys its entries without freeing external keys or values stored in them.
Private final builder fields precede pool fields so reverse-order constructor
rollback preserves that dependency. Audited bundled-container summaries keep
internal entry loans tied to the container without treating those loans as
ownership transfers. `CharSequenceMap` and `ByteBufferMap` entries additionally
own their copied-key objects and backing arrays. Their key getters return
dependent borrows tied to the root map; independent frees, escaped-key owner
reclamation, and use after map destruction are rejected. Caller-owned input
keys remain valid after map destruction. See D105 and D106.

D107 also tracks caller-item loans for known local bundled lists, maps and sets.
Successful clear or container destruction releases that container's loans; an
explicit item `free` still requires every retaining container and other alias to
be accounted for. Duplicate insertion, individual removal, and indexed
replacement do not discharge an item loan. The rationale, rejected shortcuts,
and requirements for a future
zero-runtime-overhead refinement are documented in
[Container Removal and Caller-Item Loans](CONTAINER_REMOVAL_LOANS.md).
`ArrayLinkedList.clear(boolean)` retains loans until destruction
because its unchecked package accessor can read inactive slots. Observed item
results, iterator access, unknown calls and publication keep escape analysis
conservative. Key callbacks must prove non-retention across every dispatch target.
Copied-key inputs are borrowed only for copying when their reads prove safe.
These proofs add no runtime checks, scans, allocations or automatic item frees.
The fresh `Collections.unmodifiableList` view borrows its backing list until view
destruction; all live views must be destroyed before the list can be reclaimed.

Local pool-dependent aliases cannot be used after owner destruction or freed
independently. Escapes and conflicting ownership prevent reclamation when safety
cannot be proved. Return each checkout at most once, only to its originating
pool, and stop using it until another checkout. Duplicate returns are not checked
at runtime. External objects violate the return contract and are not recorded or
destroyed. The compiler still conservatively bounds such references by the pool;
this does not promise that unsupported external objects can later be freed.

Helpers may return a checked-out value to its originating pool without escaping
that pool. The compiler proves the exact receiver/item relationship across every
cleanup copy and bound target, including captured deferred operands and stable
final pool fields. This never transfers ownership or excuses other publication.
Ambiguous identities and unaudited implementations remain conservative. See the
[pool helper regression analysis](POOL_RELEASE_HELPER_REGRESSION.md) for the proof
boundary, historical cause, and focused safety coverage.

Builders must return fresh unescaped references or null and must not publish
themselves. The compiler validates every concrete `ObjectBuilder` implementation,
rejecting cached results and uncertain factory summaries. A bounded private
creation-array destructor loop is proved separately from ordinary shallow array
free. It destroys distinct recorded elements, also during failed construction.
There is no identity set, registry, or checkout tracking in the reuse path. See
[Standard Library](STDLIB.md#ironwoodpool) and D104.

Enhanced `for` creates no hidden Ironwood allocation. Array traversal borrows
the evaluated array reference for the duration of the loop. Iterable traversal
evaluates the producer once, calls `iterator()` once, and borrows the returned
`Iterator` through ordinary `hasNext()`/`next()` calls. The loop neither owns
nor implicitly frees that object. An `Iterable` implementation must retain
ownership of what it returns; the bundled pools and collections satisfy this by
resetting and returning an iterator stored as reusable container capacity.
Their destructors reclaim that iterator and any owned reusable holder. The
compiler carries the borrow's concrete helper provenance through ordinary
interface calls, rejects an independent `free` of the helper, rejects any use
after accepted owner destruction, and blocks owner destruction when the borrow
escapes or its retention is uncertain. See
[Owned Helper Borrows](OWNED_HELPER_BORROWS.md). A producer that allocates a
fresh iterator without retaining an explicit source-visible ownership path
leaves that allocation without a proven-safe way to free it, just as any other
hidden-by-API allocation would.

Thrown values are the same ordinary Ironwood object references used elsewhere.
The runtime allocates a short-lived native `_Unwind_Exception` wrapper containing
that reference; a landing pad destroys the wrapper without reclaiming the
language object. Propagation may create a new wrapper around the same object.
Consequently exception identity remains language-object identity. Safe-`free`
analysis must treat in-flight wrappers, caught values, rethrows, and pending
cleanup paths as aliases that prevent premature reclamation.

Checked-exception status and `throws` declarations are compile-time contracts;
they do not change ownership, allocation, wrapper lifetime, or native unwinding.
A checked and an unchecked exception object escape through `throw` in exactly
the same way. Declaring a constructor failure therefore does not transfer or
reclaim the partially initialized target.

A multi-catch binding is one implicitly-final SSA alias of the same landed
exception object; testing several alternatives and capturing the shared binding
creates no language allocation or ownership transfer. Precise rethrow changes
only the checked types verified at compile time. It preserves the object,
captured trace, native wrapper rules, secondary associations, and the
existing rule that caught or escaping exceptions cannot be reclaimed by
safe-`free`.

External-resource cleanup uses source-written `try`/`finally` or explicit
block-scoped deferred void calls and does not change allocation ownership.
`AutoCloseable` remains an ordinary interface; there is no implicit `close()`.

A reached deferred call retains its captured receiver and reference arguments
until invocation. Existing `free` rejects reclaiming an observed allocation,
including an owner of a captured dependent stream or view. Destructor field
reclamation also respects direct and getter-based captures. Captures survive
source-local reassignment and are consumed in cleanup order with independent
ownership state for mutually exclusive exit copies. Call effects apply at
invocation; nested operand effects apply during capture. Unknown effects remain
conservative. `--unfreed=off|warn|error` and `@SuppressUnfreed` never weaken these
mandatory checks. Fresh capture results stay live until use and still need
explicit reclamation under the existing ownership rules; intermediate rendering
text retains its narrow copy-and-release protocol. There is no new temporary
reclamation rule, pool ownership transfer, or runtime action storage.

`defer free name;` binds an existing local reference without a synthetic alias.
The local cannot be reassigned or updated until its action executes, including
self-assignment or writes through nested expressions and source finally. Object
and array mutation remain legal while live. Schedule the free before deferred
calls that observe the allocation so those calls finish first. A failing close
still attempts the free, preserving D051 exception order.
The [deferred-cleanup example](../examples/deferredcleanup/README.md) checks this
order, separately accounts for caught exceptions, and demonstrates pool release
without freeing borrowed items.

Every cleanup predecessor uses the ordinary allocation/alias/escape proof.
The target remains available through cleanup, and pending bindings participate
in missing-free analysis without transferring ownership or exempting borrowed,
pooled, immortal, unknown, or mixed-provenance values. An earlier manual free or
duplicate pending free is rejected. Only aliases in the exiting lexical scope
may expire; outer aliases and pending call/return/yield operands remain observers.
After an inner action finishes, a still-in-scope local may be assigned a new
allocation without reading its freed value. Existing back-edge rules still
reject carrying a freed value into another iteration; clear or replace that
binding before the back edge, or use a body-local allocation.

A source finally that frees a still-pending capture is rejected, including when
returning or yielding an observed reference. Constructor block cleanup completes
before failed-constructor rollback; destructor effects remain checked.
Existing allocations retain exact identity across a `try` when SSA and escape
analysis prove it unchanged. The ownership proof snapshots normal, returning,
catch, exceptional, `break`, `continue`, and `yield` predecessors independently
before lowering duplicated `finally` cleanup, so a source owner may place an
exactly-once proven-safe
`free` in that cleanup. If `close()` can throw, a nested `try`/`finally` can
still run `free` while preserving the first-failure rule. `close()` itself never
deallocates the wrapper, its fields, or referenced objects, and an escaped or
aliased wrapper remains ineligible for `free` on every path.

When a `finally` block throws while another exception is already pending, the
runtime retains the later exception in compiler-managed secondary metadata and
continues propagating the original object. These native association nodes are
runtime-private storage, not Ironwood objects or implicit source allocations;
they do not affect `System.allocationCount()`. Thrown primary and secondary
language objects are already escaped for safe-`free` purposes, so the metadata
does not create an untracked reclaimable alias.

A labeled or unlabeled `break`/`continue` that exits one or more protected
regions runs every crossed `finally` inner-to-outer before reaching its target.
Those cleanup paths may observe the same locals as the original source region,
so they remain real aliases for safe-`free` analysis. If cleanup completes
abruptly, it supersedes the pending transfer and no skipped path creates an
implicit reclamation opportunity.

Transfer destinations merge post-cleanup ownership rather than the state from
before cleanup. If any incoming path freed an allocation, subsequent observation
and repeated reclamation are rejected. A reference-valued `yield` is an
observable alias until its cleanup finishes. Previously evaluated receivers and
arguments also cannot be consumed after later expression cleanup frees them.
Loop back edges must not carry newly freed references; an entry allocation
reclaimed inside the loop requires
the same live ownership and identity on every continuing path. Allocations
created inside the body can be reclaimed on each iteration before `continue`.
This is a conservative compile-time proof, not runtime lifetime tracking.

Ordinary Throwable construction invokes virtual `fillInStackTrace`, which
unwinds native frames and decodes immutable compiler-emitted pseudo-probe
metadata into runtime-private C storage (D121 and D132). Ordinary execution
performs no trace bookkeeping. Rethrowing the object retains its trace; explicit refresh
replaces it. Native metadata and secondary association nodes belong to the
Throwable's private slot. Its destructor and failed-constructor rollback release
them; borrowed message, cause and secondary objects remain untouched. The nodes
never contribute to `System.allocationCount()` and cannot be directly targeted
by source `free`. Thrown or published objects retain the existing conservative
escape restrictions. Printing owns and releases traversal helpers, but callbacks
with publication or uncertain effects can prevent proof of later reclamation.
Failure to reserve trace metadata degrades the diagnostic to `<trace unavailable>`
without changing propagation. Emergency allocation failures retain bounded,
reusable metadata with the existing singleton lifetime.

Automatic static initialization uses compiler-emitted private native state and
one failure-reference slot per retained type. Those globals and ensure routines
are not Ironwood allocations and do not contribute to
`System.allocationCount()`. If initialization fails, the slot retains the exact
already-escaped exception object and later active uses rethrow it; this adds no
new source-reclaimable alias. By contrast, objects or arrays created by source
field initializers or static blocks are ordinary Ironwood allocations. Storing
one in a static field publishes it under the existing safe-`free` rules, and no
automatic reclamation occurs when initialization completes or fails.

A static import is only a compile-time alias for an existing field, method, or
member type. Reading or writing an imported static reference observes the same
global slot, initialization state, allocation provenance, escape behavior, and
safe-`free` restrictions as a qualified access through its declaring type.
Calling an imported method likewise uses its ordinary escape summary. Static
imports create no hidden reference, allocation, owner, borrow, or lifetime.

A constructor may not publish in-progress `this`. If construction throws, the
`new` expression's exceptional edge invokes a compiler-generated rollback
callable. It walks compiler-proven owned fields in reverse order, recursively
destroys completed child allocations through their normal destructor entries,
raw-deallocates the incomplete receiver without invoking that receiver's source
destructor, and rethrows the exact original exception. Failed
allocation before storage acquisition needs no rollback. `this(...)`
delegation shares one allocation and therefore rolls back exactly once. The
runtime separately releases transient native exception wrappers because they
are runtime-owned storage rather than Ironwood objects.

`free expression;` reclaims the exact allocation referenced by the expression.
It does not merely clear one variable or infer ownership recursively from
reachability. For an object, it invokes the dynamic class's destructor body and
then each superclass destructor before deallocation; an array has no destructor
and releases only its container. It is legal only when the compiler proves that no live
local, argument, return, field, static, array element, captured nested-object
state, exception state, or call-mediated alias can observe the allocation
afterward. Failure to prove safety is a compile-time diagnostic. Removing a
rejected `free` is always memory-safe, but the allocation is not freed.

If a fresh `new` is tested by a named `instanceof` pattern, the successful
binding is the source-visible owner of that same allocation identity and may be
the target of `free`. The pattern creates no object or ownership transfer. If
the original reference or another alias remains observable, it blocks
reclamation under the ordinary rule; a captured pattern binding is likewise a
real retained alias.

The target variable does not become `null` after a successful `free`; its old
value enters a compiler-tracked dead state. Freeing it again or observing it in
any expression is rejected. A plain assignment may reuse the variable because
it does not read the dead value, and a replacement allocation receives a new,
independent identity and lifetime. Preventing variable reuse would add no memory
safety: the allocation, not the local variable name, is what was reclaimed.

The proof is deliberately conservative. Its positive core includes allocations
created by `new` or array-initializer braces, fresh-or-null results derived by
closed-world return summaries, children detached from known constant local-array
slots, caller allocations released from a proven receiver-only method borrow,
and former values of closed-world-proven exclusive private fields. An owned
field may be freed directly by its declaring destructor. Local aliases are
permitted only after their scopes end. A direct
or closed-world-devirtualized call is permitted for local allocations when its
analyzed body does not retain, return, throw, or otherwise escape the reference.
Inner objects retain their enclosing instance in a compiler-owned field; local
and anonymous objects similarly retain copied final/effectively-final captured
references. Those hidden fields and constructor operands are ordinary aliases:
construction marks a tracked referenced allocation escaped, and freeing the
nested object never recursively frees its enclosing or captured objects.
This also applies to an enclosing object passed through `outer.super(...)`;
freeing the derived object does not restore a reclamation proof for that outer.
Ironwood does not support varargs, so invocation lowering never creates hidden
argument arrays without a source-level owner.

S0/U1/U2 intrinsic summaries mark the default `Object.toString()`,
`String.fromChars(...)`, `String.fromRange(...)`, `String.fromInteger(...)`,
and `String.fromCharacter(...)` results as fresh ordinary
Strings. Ordinary wrappers such as `String.substring(...)` and
`String.concat(...)`, integer text conversion, `System.getenv(...)`, and
`StringBuilder.toString()`/`subSequence(...)` preserve that origin through
fixed-point symbolic return analysis, while `String.toCharArray()` exposes its
source-level fresh array directly. Public String copy and `char[]` constructors
are one-allocation copy operations; their arguments are borrowed during construction rather than
retained by the result. A returned receiver or argument is distinguished from an
outward non-return escape: returning one result does not falsely publish a
different method-local allocation that was already reclaimed. Source, loose
class, and archive inputs reconstruct the same callable bodies at final link,
so these ownership results do not depend on delivery form.

D116 includes floating `String.valueOf` overloads in the compiler-owned fresh
text-result contract. Each uses one dynamic concatenation result with no helper
array. Floating builder append consumes one such temporary and reclaims it in
`defer free`, including when subsequent backing-array growth fails. Within existing
capacity, append retains no new allocation; a valueOf caller owns its result.

Integer/character formatting allocates only its returned String, with bounded
scalar conversion state. Builder subsequences copy only the validated live
range into one immutable String, independent of builder mutation/destruction;
neither operation leaves a helper allocation behind on failure.

Successful collection rendering keeps its returned String caller-owned, while
source-written `defer free` reclaims the method-local StringBuilder and its backing
array on both normal and exceptional exits.
Fresh element renderings consumed through `StringBuilder.append(Object)` use
the concrete-type protocol above instead of becoming unreachable leaks.
U2 extends this contract to `Path.of(...)`, `Paths.get(...)`, path-transforming
methods, `Files.readString(...)`, and `Files.readAllBytes(...)`. Every returned
path owns one internal normalized String; its destructor reclaims that child.
Successful whole-file reads return a fresh caller-owned String or byte array.
File/path calls borrow their Path and content arguments and retain no caller
reference. Native file handles and scratch buffers are runtime-private: all
success, I/O failure, malformed UTF-8, file-too-large, and source-allocation
failure paths close and release them before control returns to Ironwood.
Path transformations construct their one owned String inside the returned path,
so receiver-allocation and String-allocation failures require no temporary
transfer cleanup. File reads resize only an unpublished result allocation and
reclaim it on failure; they allocate no separate full-file staging buffer.
Immutable String writes are borrowed directly. Arbitrary `CharSequence` writes
own one temporary character snapshot, reclaimed by `defer free`, because
content may change or throw during observation and must be validated before
truncation. Native path/cwd buffers use bounded stack storage with an explicitly
reclaimed long-spelling fallback.
Successful `Float.parseFloat` and `Double.parseDouble` calls also borrow their
String and return primitive values. Their grammar validation and bounded native
normalization allocate neither a managed object nor Ironwood-owned native heap
scratch; malformed input may allocate only the ordinary exception on the
failure path.

U3 records constructor-retained borrows when a parameter stays solely in a
private, encapsulated field. The caller must free the wrapper before reclaiming
its borrowed input/storage. Close does not release that managed alias. Publishing
the wrapper publishes its dependencies; returning or exposing the field prevents
this proof. Constructor delegation, exception snapshots, and branch merges keep
these edges explicit. Destructors reclaim only proven-owned children, never
these caller-owned borrows. File stream factories construct their own children
and allocate all managed buffers before acquiring the descriptor last.

A constructor's possible publication of a caller argument is recorded before
its exceptional edge. Throwing after publication cannot make that argument
reclaimable in a catch block. Receiver-only borrows are installed after success;
failed non-publishing construction rolls back the wrapper and leaves its caller's
argument available for reclamation.

D170 preserves that rule across helper calls for a proven temporary constructor
borrower. The wrapper must remain confined, every bound use must preserve
non-publication, cleanup must not publish its retained fields, and typed control
flow must destroy or roll back the wrapper on every exit after acquisition.
Explicit `free`, `finally`, and captured
`defer free` can establish that proof; close alone cannot. A subsequent summary
pass uses the completed proof without erasing argument evaluation, constructor
publication, or other effects. The same fact informs private-field ownership.
Unprotected throwing paths, exposed fields and unknown uses remain conservative.

An ordinary resolved instance method can create the same borrow relationship
when closed-world analysis proves that an argument is retained only in one
private, encapsulated field of a known local receiver. The child cannot be freed
while that receiver remains live. Freeing the receiver removes only the field
alias, never the child allocation; the caller can then free the child, and the
missing-free checker reports it if the caller instead abandons it. Publishing
the receiver publishes every such child. Exposed or ambiguous fields, unknown
receivers, external publication, and unresolved polymorphic calls retain their
conservative escape behavior. This is not ownership transfer and adds no
automatic reclamation.

Non-reference-returning calls may borrow retained buffers when every possible
target of the resolved call proves non-retention (D096). Typed overload selection
and interface-default precedence determine the method; receiver flow through
allocations, locals, arguments, returns, fields, and branch/exception joins bounds
its implementations. An unrelated retaining subclass, unused overload, or
superseded default does not by itself invalidate the proof. Any retaining
implementation that can flow to the receiver still prevents reclamation.

The analysis joins fields across instances and method inputs across callers;
reference-array loads and native reference results remain conservative within
their declared types. A library without a selected entry point has unknown
reference inputs. Empty receiver flow is not a borrowing proof: all compatible
targets are checked. Final linking recomputes the analysis from the complete
source/class/archive inputs, including replacement implementations.
Primitive-array copies introduce no reference aliases; reference-array copies
retain the existing conservative rule. Unknown/retaining calls remain rejected.
Fresh factory results participate in the same cleanup snapshots as source new,
including repeated
finally lowering, real double-free rejection, and loop-back-edge checks.

Short-circuit and conditional expressions use their typed CFG ownership joins:
unchanged allocations remain reclaimable, while conditional publication,
live aliases, incompatible identities, and partial frees remain rejected.
Fresh factory results participate in those joins and generated finally snapshots
on the same basis as source allocations.

An attached field loan cannot cross a retaining or unresolved reentrant call.
The compiler rejects unproven consumed parameters, arbitrary field-loaded
references outside their declaring destructor, live aliases, published
or conditionally replaced fields, escaping constructors or calls, uncertain
polymorphic calls and incompatible control-flow merges, as well as double frees
and later uses. A modern switch expression creates no hidden result allocation;
its reference result is an ordinary alias selected from one branch, and
safe-`free` requires the merged identity to remain source-provable. A source
allocation created and reclaimed wholly inside one proven case
path remains eligible under the ordinary rules. More precise proofs may accept
additional safe programs without weakening these guarantees.

A destructor is a distinct callable kind in typed IR. It cannot allocate,
publish or resurrect `this`, or let an exception escape; direct and indirect
calls contribute effects through a closed-world fixed point. A caught exception
is permitted when every path handles it locally. If an exception nevertheless
crosses the backend destructor boundary, the runtime terminates immediately
instead of deallocating through partially completed cleanup. Reading a field
through the proven non-null `this` receiver or one of its direct SSA aliases does
not add a synthetic null-allocation or exception effect. Nullable receivers
remain checked and retain those effects.

The compiler may later replace a source `new` with stack allocation or scalar
replacement when observable identity and `free` behavior remain unchanged.
Debug builds may poison or quarantine explicitly freed storage to catch compiler
or native-interop bugs, but that cannot replace the static proof.


## Networking result ownership

The [TCP member matrix](STDLIB_N1_SOURCE_REVIEW.md) distinguishes owned default
and fresh factory implementations from borrowed injected delegates. Closing a
socket releases its descriptor; freeing reclaims its managed views, addresses
and owned cached names. Destructors do not close sockets. A borrowed view cannot be freed alone
or used after its owner is reclaimed. Fresh endpoints, address copies and byte
arrays have independent lifetimes. Historical address borrows remain valid
across bind/connect/close transitions until owner reclamation.

The resolver and networking prerequisite fixtures prove distinct fresh elements
in a fresh reference array, including elements created by source-proved fresh
factories. Loading an element and immediately clearing its exact
slot detaches that element for independent reclamation; array free stays
shallow. Copies, duplicates, publication, replacement with borrowed values and
uncertain indices do not gain ownership. Partial construction frees completed
elements. Detached results participate in missing-free diagnostics as well as
mandatory safety checking. See [owned-helper proofs](OWNED_HELPER_BORROWS.md)
for snapshot roots, fresh cursors and list-entry loans. None of these refinements
changes the lifetime rules for thrown exceptions or disables safety in any mode.

Milestone 3 uses these proofs for real interface graphs. A private final
creation array may lend an element through a validated direct indexed getter;
its explicit destructor loop owns the element cleanup. Contained helpers keep
backlinks private, and no view exposes the hidden snapshot owner. A nullable
borrow join retains its single known root when the other alternative is null;
conflicting, fresh or unknown alternatives remain conservative. Scoped-address
construction copies the complete required interface graph. See D163 and
[the M3 contract matrix](STDLIB_N1_SOURCE_REVIEW.md#milestone-3-implementation-review).
