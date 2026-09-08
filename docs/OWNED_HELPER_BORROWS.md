# Owned Helper Borrows

Ironwood collections use a Java-shaped API without allocating a new iterator
for every traversal. For example, `ArrayList<E>` creates one private
`ArrayListIterator<E>`, resets it in `iterator()`, and returns the same object on
each call.

This document specifies who owns that helper, what `free` does, and how the
compiler handles every practical aliasing outcome. The rules require no
lifetime annotations or ownership syntax in application code.

## Core rule

The collection owns its private reusable helper graph. A value returned by
`iterator()` is a borrow of that graph, not an ownership transfer.

For `ArrayList<E>` this means:

- the list owns its backing array and reusable iterator;
- the iterator may retain a private backlink to the list;
- callers may use the iterator but may not `free` it;
- an accepted `free list;` runs the list destructor, which destroys the
  reusable iterator and backing array before destroying the list itself; and
- inserted elements remain borrowed references and are never recursively
  destroyed with the list.

Ironwood never turns double-free into a no-op and never accepts undefined
use-after-free behavior. If the proof is insufficient, compilation fails.

## Outcome matrix

| Situation | Compiler result | Runtime result |
| --- | --- | --- |
| A fresh list is never iterated, then freed | Accept | The iterator, backing array, and list are destroyed. |
| A local iterator is fully used, then the list is freed | Accept | The cached iterator is destroyed with the list. |
| The iterator variable remains in lexical scope but has no use after `free list;` | Accept | The old variable contains a dead value and cannot be read later. |
| The iterator is used after `free list;` | Reject | No executable is produced. |
| The caller writes `free iterator;` | Reject | The caller cannot destroy a borrowed helper. |
| A helper method returns `list.iterator()` to its caller | Preserve the borrow | The returned value still depends on that list; ownership is not transferred. |
| The caller frees the list twice | Reject | Double destruction is never emitted. |
| The iterator is stored in a field, static field, array, closure, return value, or retaining call | Reject a later `free list;` | The list remains allocated unless the program removes the unsupported `free`. |
| A call target may retain the iterator and closed-world analysis cannot disprove it | Reject a later `free list;` | Conservative safety; no undefined behavior. |
| A call only observes the iterator and all closed-world targets prove that | Accept continued analysis | Observation alone does not escape the borrow. |
| Constructor code stores the list backlink only in an encapsulated owned iterator | Accept containment | The backlink dies when the iterator dies. |
| The helper can publish or return that backlink | Reject owner reclamation | The helper is not treated as an encapsulated child. |
| A branch or loop preserves the same owner and borrow identities | Accept continued analysis | Ordinary iteration remains allocation-free. |
| A control-flow merge changes or obscures an identity | Reject the affected `free` | Conservative safety. |
| A primitive iterator returns its reusable holder | Treat the holder as a nested borrow | The holder is destroyed by the iterator destructor. |
| A nested reusable holder escapes | Reject a later owner `free` | The complete owner graph stays alive. |
| A normal independent alias to the list remains observable | Reject `free list;` | This is an owner alias, not a helper borrow. |
| An inserted key or element outlives the collection | Accept | Collection destruction does not destroy user values. |
| The caller frees a previously inserted object after destroying a proved local collection | Accept when no other alias escaped or still retains it | D107 tracks caller-item loans separately from publication; container destruction releases its loans without destroying the items. |
| Construction fails after a helper was installed | Accept compiler-generated rollback | Completed owned fields are destroyed in reverse installation order. |

Every row has an isolated, commented program and runner in the
[`ownedhelperborrows` example](../examples/ownedhelperborrows/README.md). Valid
programs compile, link, and exit with status `42`; rejected programs are checked
for their expected compile-time diagnostic.

## Ordinary completed traversal

This is valid even though `iterator` remains declared after the loop. Its last
use is before the list destruction.

```ironwood
// Expected: compiles, links, and runs. The iterator's last use is in the loop.
ArrayList<Item> list = new ArrayList<Item>();
Iterator<Item> iterator = list.iterator();
while (iterator.hasNext()) {
    use(iterator.next());
}
free list; // Valid: destroys the iterator, backing array, and list.
```

The compiler carries the concrete helper provenance through the
`Iterator<Item>` interface reference. Therefore ordinary `hasNext()`, `next()`,
and `remove()` interface dispatch do not by themselves make the helper escape.

## Use after owner destruction

This is rejected:

```ironwood
ArrayList<Item> list = new ArrayList<Item>();
Iterator<Item> iterator = list.iterator();
free list;          // Valid by itself; this also destroys the cached iterator.
iterator.hasNext(); // Compile-time error: iterator was destroyed with list.
```

The diagnostic is attached to the later iterator use. The accepted meaning of
`free list;` includes destruction of the iterator, so there is no valid live
iterator value afterward.

## Attempting to destroy the borrow

This is also rejected:

```ironwood
ArrayList<Item> list = new ArrayList<Item>();
Iterator<Item> iterator = list.iterator();
free iterator; // Compile-time error: iterator is a borrow, not caller-owned.
```

`iterator()` did not transfer an allocation to the caller. Allowing this free
would invalidate the list's private field and make its later destructor a
double-free. Ironwood prevents the first invalid free instead.

## Escaped and unknown borrows

This is rejected at `free list;`:

```ironwood
savedIterator = list.iterator(); // Publishes the borrowed helper.
free list; // Compile-time error: savedIterator could observe the destroyed helper.
```

The static or instance field may expose the iterator after the list is gone.
The same result applies to returning it from the current ownership region,
storing it in an array, capturing it, or passing it to a call that may retain
it.

Borrow provenance also crosses ordinary helper methods:

```ironwood
// Expected: compiles. The return preserves the borrow from the supplied list.
static Iterator<Item> iteratorOf(ArrayList<Item> list) {
    return list.iterator(); // Not a fresh allocation or ownership transfer.
}
```

The caller receives a borrow dependent on the supplied `list`. It may consume
that iterator and then free the list, but it may not free the iterator, use it
after the list is freed, or publish it and then free the list. A method with
mixed outcomes, such as returning either the iterator or an unrelated value,
is not treated as an exact borrow; the affected owner cannot be freed unless a
safe result can still be proved.

There are two conservative cases:

1. If the compiler proves the borrow escaped, it records the concrete escape
   reason and rejects owner reclamation.
2. If the compiler cannot determine whether a polymorphic or indirect use
   retains the borrow, it also rejects owner reclamation. Unknown never means
   "probably safe."

## Reuse and non-reentrancy

Two calls return the same iterator object:

```ironwood
Iterator<Item> first = list.iterator();
Iterator<Item> second = list.iterator(); // Valid, but resets the shared iterator.
// first == second, so using first now observes the reset traversal state.
```

The second call resets the shared state. `first` and `second` are aliases of the
same borrowed helper, so simultaneous or nested traversals of one collection
are unsupported. This is an iterator behavior constraint, not an ownership
transfer. Either alias used after list destruction is rejected.

## Primitive reusable holders

Primitive collections avoid boxing by returning a reusable `IntHolder` or
`LongHolder` from their cached iterator. The holder is owned by the iterator,
which is owned by the collection:

```text
# Expected ownership chain; callers borrow both helper objects.
IntArrayList -> IntArrayListIterator -> IntArrayListHolder
```

Each returned holder reference is therefore another borrow tied to the root
collection. The caller cannot free the holder. A holder escape blocks
collection destruction, and a holder use after collection destruction is a
compile-time error.

## Constructor containment

An iterator constructor commonly stores its owner:

```ironwood
// Expected: accepted only while the backlink remains private and encapsulated.
ArrayListIterator(ArrayList<E> owner) {
    this.owner = owner; // Contained backlink, not external publication.
}
```

That store is not treated as global publication when all of the following are
proven closed-world:

- the iterator is freshly installed in a compiler-proven-owned private field;
- the backlink is retained only in a private helper field;
- calls using the backlink do not publish it; and
- no helper method returns, stores, throws, or otherwise exposes it.

If any condition fails, constructing the owner is considered an escape and a
later `free` is rejected.

## Standard-library coverage

Every `ironwood.ds` iterable destroys its cached reusable iterator. Primitive
array lists, linked lists, and sets also destroy the reusable holder owned by
that iterator. Composite containers destroy owned helpers in dependency order:
set iterator before backing map, and hybrid-list iterator before overflow list
and backing array.

D105 extends this chain to the private entry pools and builders of the bundled
maps and linked lists. The pool is destroyed before its borrowed builder and
reclaims all recorded entries. Constructor rollback follows the same dependency
order. Audited internal entry-accessor summaries tie node aliases to the
container, rejecting independent node destruction, escaped-node owner frees,
and observations after container destruction. This is a bounded bundled-library
contract, not general inference for arbitrary pool-backed object graphs.

D106 includes the copied keys inside `CharSequenceMap` and `ByteBufferMap`
entries in this ownership chain. Entries keep their keys in private final
fields and expose them as dependent borrows. The public map key getters tie
those borrows to the map, including through wrapper calls, local aliases,
reference conversions, and fluent buffer methods. Pool destruction reaches
entry, key, and backing-array destruction. Input keys remain caller-owned;
an independently allocated String snapshot may also outlive the map.

Collection keys and values remain borrowed. D104 gives object pools a creation-only owning
contract: their values, linked nodes, and retained high-water capacity are
reclaimed with the pool. Checkout produces a dependent borrow. Return accepts pool checkouts by contract;
external objects are not recorded or destroyed. The compiler preserves that lifetime
through local aliases and rejects subsequent use after destruction. It also
tracks checkout provenance separately from a borrow of an object's owned child,
so returning a child cannot give two destructors authority over it. The full
contract and current conservative limits are in `STDLIB.md`.

Inserted user objects remain valid after their collection is destroyed. D107
tracks loans from known local bundled containers to their caller-owned items.
Successful whole-container clear or destruction releases those loans, allowing
explicit caller reclamation when every retaining container has released the
item. `ArrayLinkedList` requires destruction because its package accessor can
read inactive slots. Individual removal remains conservative about duplicates.
Observed element results, iterator access, unknown calls, uncertain callbacks,
and publication retain conservative escape behavior, including later insertions
into an exposed container. No runtime ownership bookkeeping is added.

`Collections.unmodifiableList(list)` preserves a constructor borrow from its
fresh view to the backing list. Destroy every view before freeing that list;
view destruction frees only its own iterator. The factory contract requires the
direct constructor-forwarding body, a proved fresh result, and an encapsulated
backing-list field. Failure does not leave a live view loan.

Private backing arrays also keep their existing detachment rules. Returning an
array field does not turn it into an owned-helper borrow; a published backing
array prevents the compiler from proving private array reclamation.

## Summary for Java developers

Use a returned iterator normally. Do not free it. Free the collection when the
program is finished with the collection and every value borrowed from its
reusable helper graph. Ironwood either proves that destruction safe or reports
the remaining/unknown observation; it does not require Rust lifetime syntax and
does not silently accept dangling references.

To compile, link, and verify the complete scenario catalog:

```console
$ cd examples/ownedhelperborrows
$ ./test.sh
```
