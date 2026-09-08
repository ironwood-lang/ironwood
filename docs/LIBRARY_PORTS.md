# Pools and data structures

Milestone 8 adds reusable object pools and low-allocation data structures to
native Ironwood. Their original author donated the implementations directly to
Ironwood under the project's `MIT OR Apache-2.0` terms; there is no external
source-checkout dependency. D029 records the contribution, namespace, memory,
and adaptation rules, D030 defines archive distribution, and D042 defines the
repository's file-level licensing model.

## Contributed implementation scope

| Component | Production scope | Status |
| --- | ---: | --- |
| Object-pool implementations | 2 public implementations, 2 interfaces, 2 internal helpers | Complete |
| Data-structure implementations | 26 public types | Complete for the contributed core plus application-driven utilities |

The contributed code is now versioned, tested, and maintained with the rest of
Ironwood. Historical external project versions are not part of the Ironwood API
or build contract.

## Port order

1. Java-shaped language and standard-library prerequisites.
2. `ironwood.pool` interfaces `ObjectBuilder` and `ObjectPool`.
3. `ArrayObjectPool`, `MultiArrayObjectPool`, and their private helpers (D103).
4. `ironwood.ds` utilities and primitive array/list/map structures.
5. Generic and linked collections, then their set adapters.
6. Character-sequence maps and heap-buffer maps.
7. Compiler-proven deterministic reclamation for superseded private backing
   arrays and terminal container destructors; detached-node reclamation remains
   separate from active node reuse.
8. `.ironjar`, relocated-package, optimization-level, and allocation-invariant
   verification.

## Intentional differences

- `SoftReference`, retention-policy selection, `discardGarbage()`, and equivalent
  JVM garbage-collector hints are omitted. Superseded private backing arrays are
  explicitly freed after a closed-world ownership summary and local detachment
  proof succeed. Freeing an array never recursively frees its elements. Multi-array
  segments remain reusable high-water capacity.
- Reflection-based `ObjectBuilder.createBuilder(Class)` and pool constructors
  taking `Class` are omitted. Callers supply a statically typed builder.
- Collections borrow inserted user objects. Pools own builder-created
  values. `get()` borrows; `release()` returns a checkout.
  The pool destroys checked-out and available values alike. `takeRetained()` is
  removed. Null returns fail; duplicate returns are unchecked. External objects
  are unsupported and not recorded for destruction.
- Container destructors reclaim proven implementation storage, reusable iterators,
  and owned holders. Pool creation arrays also record holders that reclaim array segments.
  Compiler lifetime and escape proofs reject dangling uses and uncertain owner
  reclamation. Collection element APIs do not recursively destroy user values.
- Direct native `ByteBuffer` storage is deferred until Ironwood has a deliberate
  unsafe/native ownership boundary; the heap-buffer API is the first target.
- `ByteBufferMap` semantically copies every supported key, but the current
  interprocedural safe-`free` summaries may conservatively reject a subsequent
  `free` of a locally allocated key passed through the nested copy routine.
  Removing that `free` is safe and does not change map key ownership; the
  compiler is not permitted to accept it until the copy can be proven.
- The libraries remain deliberately single-threaded and preserve their reused,
  non-reentrant iterator contracts.

## Completion accounting

All 9 applicable pool production targets and all 24 contributed data-structure
production targets are complete. At the completion point recorded by this port
ledger, application-driven additions brought `ironwood.ds` to 26 public types
and the standard library to 154 source types, including internal helpers and
language/runtime prerequisites. The current whole-library inventory is tracked
in [`STDLIB.md`](STDLIB.md). A type
counted as complete only after its supported public behavior ran in a native
test; stubs, compile-only declarations, and untested translations were not
counted.

### Prerequisite ledger

| Foundation | Status |
| --- | --- |
| Method/constructor overloads and `this(...)` delegation | Complete; native tests |
| Deterministic `.ironjar` packaging and lazy class-path loading | Complete; package smoke tests |
| `ironwood.lang.Object` root for classes, interfaces, and arrays | Complete; native `-O0` through `-O3` tests |
| Java-shaped standard exception hierarchy | Complete; native `-O0` through `-O3` tests |
| `AutoCloseable`, explicit `finally` cleanup, and ordered secondary failures | Complete; checked-close, archive, safe-free, and native `-O0` through `-O3` tests |
| Invariant reference-sharing generics | Complete; native/archive/safe-free tests |
| Reifiable unbounded wildcard views and casts | Complete; read-only capture, archive/tree-shaking, safe-free, and native `-O0` through `-O3` tests |
| Object-bound unbounded type parameters | Complete; `equals`, `hashCode`, `toString`, reference equality, virtual dispatch, and native collection tests |
| Java expression/control-flow and numeric model required by the library | Complete for the current pool tranche; native and archive tests |
| `ironwood.lang.Iterable<T>` and `ironwood.util.Iterator<E>` | Complete; reusable generic dispatch, source/class/archive, tree-shaking, and native `-O0` through `-O3` tests |
| `CharSequence` and `StringBuilder` | Complete for char/String/whole-or-ranged CharSequence/Object/boolean/int/long/double append and snapshots; compiler-owned source `+`/`+=` is complete as Feature 76 |

### Numeric-helper ledger

These original Ironwood implementations provide only the static Java-shaped
operations currently required by the pool and data-structure ports. They are
not boxed primitive implementations.

| Target API | Status |
| --- | --- |
| `Byte.hashCode(byte)` | Complete; signed widening edge tests at `-O0` through `-O3` |
| `Character.hashCode(char)` | Complete; zero-extension through `\u0000`, `\u8000`, and `\uffff` |
| `Integer.hashCode(int)` | Complete; identity hash across signed boundaries |
| `Long.hashCode(long)` | Complete; Java-compatible high/low folding across signed boundaries |
| `Float.isFinite(float)` | Complete; finite extrema, subnormal, signed zero, infinity, and NaN tests |
| `Math.min/max(int, int)` | Complete; signed-boundary and equal-value tests |
| `Math.min/max(double, double)` | Complete; ordering, infinities, NaN, and exact signed-zero behavior |
| `Integer.MAX_VALUE` | Complete; public static-final compile-time constant under D035 |
| `Math.round(float)` | Complete; exact half behavior, subnormals, signed zero, finite saturation boundaries, NaN, and infinities at `-O0` through `-O3` |

### `ironwood.pool` ledger

| Target type | Visibility | Status |
| --- | --- | --- |
| `ObjectBuilder<E>` | public | Complete; explicit builder, generic interface dispatch, archive/native tests |
| `ObjectPool<E>` | public | Complete; owning `get`/`release` contract, checked-out destruction, generic interface dispatch, transfer and safe-free tests |
| `ArrayObjectPool<E>` | public | Complete except numeric interpolation in validation messages; builder constructors, preload/order, left/right exact growth, deterministic backing-array replacement and destructor cleanup, null/builder errors, safe-free, archive, and native `-O0` through `-O3` tests |
| `MultiArrayObjectPool<E>` | public | Complete; builder constructors, preload/LIFO traversal, flattened package-private holder, geometric/capped segments, null/builder errors, safe-free, archive, and native `-O0` through `-O3` tests |
| `ArraySizing` | package-private | Complete; double-precision exact/minimum/clamped growth, maximum-length error, and native boundary tests |
| `MultiArrayObjectPool.ArrayHolder<E>` | private nested | Owns one linked array-segment container |

### `ironwood.ds` ledger

All source subpackages are intentionally flattened into the required public
standard-library package `ironwood.ds`.

All public list variants provide direct value-containment queries, and every
map provides a direct value-containment query. These operations scan private
storage without mutation or reusable-iterator consumption. The scan
implementation adds no allocation; user-defined equality methods can still
perform arbitrary work.

| Target type | Status |
| --- | --- |
| `IntHolder` | Complete; primitive `int get()` interface dispatch, loose/archive and native `-O0` through `-O3` tests |
| `LongHolder` | Complete; primitive `long get()` interface dispatch and signed boundary tests at `-O0` through `-O3` |
| `MathUtils` | Complete except numeric interpolation in the validation message; long power-of-two boundaries, exception behavior, archive, tree-shaking, and native `-O0` through `-O3` tests |
| `ArrayList<E>` | Complete; growth, indexed insertion/access/replacement/removal, forward/reverse value lookup, deterministic backing-array free, reusable iterator removal, value semantics, and native `-O0` through `-O3` tests |
| `ArrayLinkedList<E>` | Complete; fixed array plus pooled linked overflow, reusable composite iterator/removal, value semantics, and native `-O0` through `-O3` tests |
| `LinkedList<E>` | Complete; pooled links, head/tail/middle removal, reusable iterator, value semantics, and native `-O0` through `-O3` tests |
| `IntArrayList` | Complete; primitive growth, indexed replacement/removal, forward/reverse value lookup, deterministic backing-array free, reusable holder iterator, value semantics, and native `-O0` through `-O3` tests |
| `IntLinkedList` | Complete; pooled primitive links, reusable holder iterator/removal, value semantics, and native `-O0` through `-O3` tests |
| `LongArrayList` | Complete; signed-extreme primitive growth, indexed replacement/removal, forward/reverse value lookup, deterministic backing-array free, reusable holder iterator, value semantics, and native `-O0` through `-O3` tests |
| `LongLinkedList` | Complete; pooled primitive links, signed-extreme reusable holder iterator/removal, value semantics, and native `-O0` through `-O3` tests |
| `HashMap<K, V>` | Complete; value keys, collisions/rehash, pooled entries, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests |
| `IdentityHashMap<K, V>` | Complete; identity keys/hash, collisions/rehash, pooled entries, reusable iterator/key/removal, identity mapping semantics, and native `-O0` through `-O3` tests |
| `LinkedHashMap<K, V>` | Complete; insertion-order links and iteration, collisions/rehash, pooled entries, iterator removal, value semantics, and native `-O0` through `-O3` tests |
| `IntMap<V>` | Complete; signed/extreme keys, collisions/rehash, pooled entries, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests |
| `LongMap<V>` | Complete; signed/extreme keys and Java long hashing, collisions/rehash, pooled entries, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests |
| `ByteMap<V>` | Complete; full signed-byte key domain, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests |
| `CharMap<V>` | Complete; checked ASCII key domain, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests |
| `CharSequenceMap<V>` | Complete; copied mutable UTF-16 keys, maximum-length validation, content lookup/equality/hash, collisions/rehash, reusable iterator/removal, and native `-O0` through `-O3` tests |
| `ByteBufferMap<V>` | Complete for the heap-backed surface; copied byte-array/ranged/remaining-buffer keys, caller-position preservation, collisions/rehash, pooled entries, reusable iterator/key/removal, value semantics, and native `-O0` through `-O3` tests. Direct-buffer constructors/state and GC cleanup are intentionally omitted |
| `HashSet<E>` | Complete; map-backed value uniqueness, reusable iterator/removal, value semantics, and native `-O0` through `-O3` tests |
| `IdentityHashSet<E>` | Complete; identity uniqueness/hash, reusable iterator/removal, identity semantics, and native `-O0` through `-O3` tests |
| `LinkedHashSet<E>` | Complete; insertion-order iteration/rendering, order-independent equality/hash, reusable iterator/removal, and native `-O0` through `-O3` tests |
| `IntSet` | Complete; primitive uniqueness, reusable holder iterator/removal, signed extremes, value semantics, and native `-O0` through `-O3` tests |
| `LongSet` | Complete; primitive uniqueness, reusable holder iterator/removal, signed extremes, value semantics, and native `-O0` through `-O3` tests |

The prerequisite object graph follows Java's reference model: every class and
array is an `ironwood.lang.Object`, with identity `equals`, `hashCode`, and
`toString` defaults. Primitive values are not objects, and `Serializable` is not
part of this milestone.
