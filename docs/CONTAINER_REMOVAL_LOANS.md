# Container Removal and Caller-Item Loans

This note records a known ergonomics limitation in Ironwood's compiler-proven
reclamation of caller-owned items stored in `ironwood.ds` containers. It explains
why an individual removal or replacement does not currently permit an immediate
`free`, why the obvious shortcuts are unsound, and what a future
zero-runtime-overhead solution would require.

This is a design note for future work, not a commitment that individual-removal
proofs are implemented. The current normative decision is D107 in
[`DECISIONS.md`](DECISIONS.md#d107---track-releasable-caller-item-loans-from-local-data-structures).

## Current behavior

An audited local container borrows every known caller allocation inserted into
it. The caller still owns the item, but cannot free it while the compiler knows
that a live container may retain it.

```java
HashMap<Item, Item> map = new HashMap<Item, Item>();
Item key = new Item();
Item value = new Item();
map.put(key, value);

map.remove(key);
free key; // Rejected: map still has a compiler-tracked loan on key.
```

A successful whole-container `clear()` or destruction of the container releases
all of that container's caller-item loans:

```java
map.clear();
free key;
free value;
free map;
```

Destruction is also a valid lifetime boundary. It destroys the container's
private storage, not the caller-owned key or value:

```java
map.remove(key);
free map;
free key;
free value;
```

The current rules apply to exact, known local allocations of the audited bundled
lists, maps, and sets. Arbitrary subclasses, escaped containers, unknown calls,
and exposed contents remain conservative.

Rendering a nonempty container currently exposes every stored element, key, and
value for caller-item reclamation. This applies to a direct `toString()` call,
passing the container to `System.out.print(Object)` or `println(Object)`, and
using the container as an object operand in String concatenation:

```java
ArrayList<Item> list = new ArrayList<Item>();
Item item = new Item();
list.add(item);

String text = list.toString();
free text;
list.clear();
free list; // Accepted: rendering did not publish the container itself.
free item; // Rejected: rendering exposed the stored item.
```

The exposure is separate from the container's loan. A successful `clear()` can
release the loan and make the container reclaimable, but it cannot reverse an
item escape that may already have created an external alias. Rendering an empty
container has no current item to expose, so its rendered text and the container
remain reclaimable. The compiler nevertheless records that the container's
contents were exposed. An item inserted into that same container afterward is
therefore treated conservatively too.

This contents rule is currently blanket rather than target-sensitive. An item
whose closed-world type inherits the nonpublishing `Object.toString()` is treated
the same as an item whose override could publish `this`. D111 separately
enumerates closed-world `toString()` targets for the outer argument of
`PrintStream.print(Object)` and `println(Object)`. It can therefore preserve the
container's lifetime without recursively preserving the lifetimes of items that
the container renders. See
[`DECISIONS.md`](DECISIONS.md#d111---rendering-consumers-preserve-source-object-reclamation).

## What a caller-item loan means

A caller-item loan is a compile-time lifetime dependency from a container
allocation to a caller allocation. It is neither an ownership transfer nor an
implicit `free` obligation for the container.

Conceptually, the current analysis retains a relation like this:

```text
container allocation -> set of possibly retained caller allocations
```

Insertion adds an allocation to the set. Successful `clear()` removes the
container's complete set, and container destruction ends the dependency. Branch
and exception joins take the conservative union of possible loans.

The set deliberately does not describe:

- how many times the allocation occurs;
- whether it is a list element, map key, map value, or set member;
- which list index or map entry retains it;
- whether a later operation replaced or removed that particular occurrence; or
- whether a removal found a matching entry.

This compact representation is enough to prove whole-container release. It is
not enough to prove most individual removals.

## Why `clear()` can release every loan

For an audited exact container, successful `clear()` establishes one broad fact:
no logically visible entry can retain any caller item. The compiler does not need
to identify the previous contents individually. It removes the container's loan
set only on the successful continuation of the call. A throwing or uncertain
path retains the loans.

`ArrayLinkedList.clear(boolean)` is an intentional exception. Its package-level
array accessor can read inactive slots, so its loans remain until container
destruction.

This whole-container proof is much simpler than proving which single edge an
individual operation removed.

## Pooled stale fields are not the deciding issue

Pooled map and linked-list entries may retain old key or value references after
they become logically inactive. Their checkout paths overwrite every user field
before the entry can be observed again. Avoiding field scrubbing preserves the
collections' low-allocation, low-write reuse behavior.

Both individual removal and `clear()` return entries through the same pool
helpers. Nevertheless, D107 permits successful `clear()` to discharge loans.
Therefore the stale bits in an inactive pooled entry are not, by themselves, the
reason individual removal remains conservative. They are an internal alias that
the compiler may ignore only while the audited overwrite-before-observation and
encapsulation proofs continue to hold.

A future individual-removal proof could use the same logical-death principle.
It must fail closed if entry fields become observable, checkout stops overwriting
them, or an implementation no longer matches the audited shape.

## Why blindly releasing the loan is unsound

### Duplicate references

One allocation may occur more than once in a list. Removing one position does
not remove the other occurrence:

```java
ArrayList<Item> list = new ArrayList<Item>();
Item item = new Item();
list.add(item);
list.add(item);
list.remove(0);
free item; // Unsafe: list still contains item.
```

A set-shaped loan relation cannot distinguish one occurrence from two.

### The same allocation can have multiple map roles

An allocation used as a removed key may still be a value in another entry:

```java
HashMap<Item, Item> map = new HashMap<Item, Item>();
Item key = new Item();
Item other = new Item();
map.put(key, key);
map.put(other, key);
map.remove(key);
free key; // Unsafe: the entry under other still retains key as its value.
```

The current loan relation records one `map -> key` edge, not separate key-role
and value-role occurrences.

### A lookup argument need not be the stored key

Value-based maps and sets use `equals` and `hashCode`. A probe can remove an
equal but non-identical stored object. Removing the loan associated with the
argument would update the wrong allocation:

```java
Item stored = new Item(7);
Item probe = new Item(7); // Equal by value, but a different allocation.
map.put(stored, value);
map.remove(probe);
```

The analysis would need to know which stored key was matched, not merely which
reference was passed to `remove`.

### Removal can fail normally

Map removal returns `null` and set removal returns `false` when no matching entry
exists. That is a normal continuation, not an exception. Calling `remove` does
not by itself prove that the container changed.

Reference-key lookup is additionally sensitive to user-defined `hashCode` and
`equals`. A mutable key, stateful callback, or callback with uncertain effects
can make a previously inserted key unfindable. Closed-world non-publication
proofs do not automatically prove callback result stability.

### Replacement has the same ambiguity

`put` can insert a new entry or replace a value in an existing entry. Replacing
one value may logically detach the old allocation, but that allocation may also
remain in another entry or role. The current set cannot decrement only the
replaced occurrence.

### Returned values create ordinary aliases

List and map removals can return the removed value. If the caller retains that
result, the alias must participate in the later `free` proof:

```java
Item removed = map.remove(key);
free removed; // Requires the ordinary local-alias and ownership rules.
```

Discarding the result avoids a new local alias, but does not prove which stored
loan was removed.

### Control flow compounds every case

Branches, loops, exceptions, multiple aliases to the same container, and calls
that expose container contents require conservative joins. A removal on one path
cannot discharge a loan that may remain on another path. An insertion is recorded
before its exceptional edge because a call may partially mutate before throwing;
a future removal transfer must be equally precise about normal and exceptional
state.

## Why the simplest proposed fixes are insufficient

### Remove the complete container-to-item edge

This is unsafe in the duplicate, multi-role, failed-removal, and branch cases. It
would turn a conservative rejection into an accepted use-after-free possibility.

### Null every recycled entry field

Scrubbing would add runtime writes to hot removal and clear paths. Ironwood's
rules prohibit adding runtime ownership bookkeeping or work solely to compensate
for a missing compile-time proof. Scrubbing also would not establish that the
same allocation is absent from every other active entry.

### Add runtime reference counts or an ownership registry

Counts, registries, scans, and removal-time ownership checks would add runtime
state and cost to valid programs. They conflict with the requirement that safe
reclamation be proved at compile time without a collector or ownership runtime.

### Make `remove` free the item implicitly

Containers borrow caller items and do not own them. Implicit destruction would
break shared-reference use, surprise Java-shaped API users, and risk double
destruction by the actual owner.

### Add `removeAndFree`

An API name cannot manufacture ownership. Such a method would still need proof
that no duplicate, value role, second container, or ordinary alias retains the
allocation. It merely moves the same analysis problem behind a different call.

### Treat a non-null or `true` result as a complete proof

A success result proves that some entry was removed. For an equality-based lookup
it does not prove that the argument allocation was the stored allocation. It also
does not prove that the removed key or value had no other occurrence. Result
tests can contribute to a future proof, but cannot replace content provenance.

## Requirements for a zero-runtime-overhead improvement

A sound implementation should remain entirely in compiler analysis and lower no
new runtime instructions for ownership tracking. At minimum it would need the
following pieces.

### 1. A richer abstract container state

Replace or supplement the current set with bounded provenance facts. A possible
shape is:

```text
container allocation
  exact-content status
  list positions or summarized occurrence counts
  map bindings: abstract key -> stored key allocation and value allocation
  set memberships
  unknown or exposed remainder
```

The domain must represent multiple occurrences and roles. When precision is
lost or a configured analysis budget is exceeded, it should widen to today's
conservative loan set instead of rejecting compilation for resource reasons or
accepting an unsafe `free`.

### 2. Audited transfer functions for each operation family

Each recognized operation needs an explicit compile-time state transition:

- list insertion, indexed removal, value removal, and end removal;
- map insertion, replacement, keyed removal, clear, and iterator removal;
- set insertion, removal, clear, and iterator removal; and
- special copied-key handling for `CharSequenceMap` and `ByteBufferMap`.

Only exact bundled implementations should receive these semantics. Subclasses
and structurally similar user methods must not inherit them by name.

### 3. A proof of which occurrence was removed

For positional lists, the analysis needs a known position or a sufficiently
precise sequence model. For primitive-key maps, a known key binding may be enough.
For identity maps and sets, allocation identity can identify a membership. For
value-based maps and sets, the compiler also needs a defensible equality and hash
stability proof or must remain conservative.

Normal return alone is not always proof of success. The transfer may depend on a
known pre-call state, a tested return value, or both.

### 4. Separate key, value, and result provenance

Map state must distinguish the stored key from the stored value and count the
same allocation in both roles when necessary. A returned old or removed value
must become an ordinary alias in the caller when retained. Discarded results can
avoid that alias but cannot erase unrelated occurrences.

### 5. Path-sensitive normal and exceptional updates

The refined state must survive branches and loops using safe joins. A mutation
effect should be installed only on the continuations where it is guaranteed.
Failure before mutation, failure after partial mutation, callback exceptions,
and allocation failure must retain a conservative state.

### 6. Encapsulation and pooled-entry logical-death validation

The existing audit that makes inactive pooled fields unobservable must remain a
prerequisite. It should verify private entry storage, pool ownership, overwrite
before observation, and ordered destruction. If the library implementation no
longer satisfies the contract, the optimization must disable itself or produce
a compiler-maintainer diagnostic during standard-library validation.

### 7. Exposure and alias invalidation

Returning elements, retaining iterators, publishing the container, passing it to
unknown code, or invoking callbacks that may retain contents must degrade the
state. A whole-container `clear()` or destruction can discharge that container's
loan, but it cannot reverse a separate item publication. Once an item may have
escaped, no container boundary alone can restore its reclamation permission.

A future refinement should distinguish actual publication from operations that
are merely capable of exposing contents. For audited container rendering, it
could enumerate the closed-world `toString()` targets of every element, key, and
value type and preserve caller-item reclamation only when every target is proven
nonpublishing. Missing summaries, unknown target sets, unaudited containers, and
any publishing target must remain conservative. This would extend D111's
outer-container proof to rendered contents without weakening safe reclamation.

### 8. No new runtime ownership machinery

The generated program must not gain registries, counters, scans, hidden fields,
conditional frees, or slot scrubbing solely for the analysis. Compile-time cost
must also be bounded and measured, particularly in loops and large generated
methods.

## A plausible staged implementation

The feature should be introduced in narrow, independently sound stages rather
than enabling every `remove` method at once.

1. Start with primitive-key maps when the compiler has an exact local container,
   a known key binding, unexposed contents, and a discarded removal result. These
   maps avoid reference-key equality ambiguity, but duplicate value occurrences
   still require counts or binding provenance.
2. Extend to exact identity maps and sets, where membership is based on allocation
   identity. Preserve conservatism for uncertain success and multi-role aliases.
3. Add list end removal and known constant-index removal with a bounded sequence
   model. Value-based removal should wait for equality-target provenance.
4. Add replacement proofs once old-value result aliases and duplicate bindings
   are modeled.
5. Consider value-based maps and sets last. Their proof must incorporate
   closed-world callback effects and result stability, not only non-publication.
6. Add iterator removal only after iterator position and dependent-borrow state
   can identify the removed occurrence across control flow.

Every stage should fall back to D107 behavior outside its proved subset.

## Required regression coverage

A future implementation should add focused compiler and native tests before
changing D107. Positive cases should demonstrate that individual removal can
release the last proved occurrence without changing runtime allocation behavior:

- one value in a primitive-key map removed by a known key;
- one identity-map key and one identity-set member removed by exact identity;
- one list element removed from a known position or end;
- replacement of the only occurrence of an old value;
- successful result-tested forms where the result contributes to the proof; and
- container reuse after removal, proving stale pooled fields are overwritten
  before observation.

Negative cases must continue rejecting `free` for:

- duplicate list elements after only one removal;
- the same allocation in two map values;
- one allocation used as both a key and a value;
- equal but non-identical lookup objects;
- failed removal and mutable or stateful hash/equality behavior;
- a retained removal-result alias;
- a second live container holding the same allocation;
- removal on only one branch or loop iteration;
- callback exceptions and allocation-failure paths;
- iterator or returned-content exposure;
- arbitrary subclasses and unknown calls; and
- any pooled-entry shape that fails overwrite-before-observation validation.

Native tests should verify destructor counts and return live allocations to the
same baseline. Typed-IR or LLVM assertions should confirm that no runtime
ownership bookkeeping, extra scan, or field scrubbing was introduced. Focused
compile-time performance tests should cover widening in loops and large content
states.

## Documentation and decision work for implementation

Implementing any stage changes the accepted safe-`free` surface. The change must
add a new decision that explicitly refines or supersedes D107 for the implemented
subset. Update `MEMORY.md`, `STDLIB.md`, this note, and every relevant example.
Document exactly which container operations, result usages, callback shapes, and
control-flow patterns are proved. Avoid claiming that all individual removal is
supported if only a narrow family is implemented.

## Current recommendation

Keep the existing behavior until a bounded provenance design satisfies the
requirements above. Today, use successful `clear()` or destroy the container
before freeing caller-owned items. Where item lifetimes differ substantially,
separate them into containers whose whole lifetimes match those ownership
boundaries.

The current rejection is safe and intentional. The usability problem is real,
but solving it correctly is an ownership-analysis feature, not a one-line
library or compiler special case.
