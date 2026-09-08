# Everyday StringBuilder compatibility review

## Scope and implementation choice

D118 adds the requested editing and query methods. `append(float)` was already
implemented under D116 and retains its exact float conversion. This addition
does not claim completion of the entire Java StringBuilder surface.

D119 subsequently changes `setLength(int)` to return `this` by explicit product
choice. It preserves all mutation/failure behavior and permits reset-and-append
chaining. Java's method returns void; existing Ironwood overrides using void
must change their return type. No existing repository override required migration.
The return value carries an ordinary receiver alias, with no new allocation or
ownership. Existing statement-style Java differential fixtures still apply;
chaining and alias checks use separate Ironwood-specific tests.

| Method group | Added forms |
| --- | --- |
| Character-array append | `append(char[])`, `append(char[], int offset, int length)` |
| Insertion | `insert(int, Object/String/CharSequence/char[]/boolean/char/int/long/float/double)`, array offset/length, CharSequence start/end |
| Deletion | `delete(int, int)`, `deleteCharAt(int)` |
| Other edits | `reverse()`, `setCharAt(int, char)`, `replace(int, int, String)` |
| Search and snapshots | `indexOf(String)`, `indexOf(String, int)`, `substring(int)`, `substring(int, int)`, `isEmpty()` |

These are small operations over Ironwood's existing owned UTF-16 array. An
independent implementation fits that representation and avoids importing Java's
GC and compact-string storage assumptions. Existing integer/float conversion,
array copying, immutable String allocation, and rendering cleanup are reused.
No new native ABI, Unicode table, dependency, or license is introduced.

The behavioral reference is the
[Java 21 StringBuilder API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/StringBuilder.html),
supplemented by independently written Java comparisons. No OpenJDK implementation
source, comments, Javadocs or tests were copied or adapted. Source remains
`MIT OR Apache-2.0`, as recorded in the provenance ledger.

## Behavioral review

All indices count UTF-16 code units. Deleting one char can split a surrogate
pair; reversing must preserve original pairs and can create new pairs from
previously unpaired units. Search returns UTF-16 positions, handles an empty
needle and extreme starting indices, and rejects null. Substrings validate
against live content rather than spare capacity.

Deletion and replacement clamp an oversized end to the current length.
Substring and source slices reject invalid ends. Array slices use offset/length;
CharSequence slices use start/end. Validation uses subtraction to avoid signed
overflow. Tests distinguish null handling and exception classes across overloads,
including calls with more than one invalid argument.

Insertion covers the complete overload group for supported types. Byte and
short arguments widen to int; float has its own conversion. Null String,
CharSequence and Object arguments insert the four characters `null`. A null
char array throws. The receiver is returned for chainable edits; `setCharAt`
returns void.

Self-insertion has a surprising Java behavior. For a builder containing `abcd`,
`builder.insert(1, builder)` produces `aaaaabcd`, while casting the source to
Object produces `aabcdbcd`. CharSequence insertion moves the suffix, expands
the live length and then reads each source character; Object insertion obtains
a String snapshot first. A custom CharSequence callback may observe those edits
or throw after a partial write. Ironwood matches those observable operations
rather than imposing snapshot or transactional semantics on every overload.

## Allocation and ownership

Array/text edits, integer insertion, reversal, deletion and search use the owned
buffer directly. They allocate no managed or native helper storage within
capacity. Growth allocates one replacement array and reclaims the old one;
overflow throws before corrupting lengths. The integer append and insert paths
share digit emission, including zero and both signed-long extremes.

Each substring/subSequence creates just one fresh String, including empty and
full ranges. This follows Ironwood's existing explicit-ownership convention;
the result survives mutation or reclamation of its builder. Input arrays and
sequences are copied and can be reclaimed when ordinary alias proofs permit it.

Floating insertion obtains and frees one existing float/double conversion
result. Object insertion uses the existing concrete-type freshness descriptor
to release only a fresh, unescaped rendering, including when growth fails.
Borrowed, cached and published renderings remain untouched. A compiler call-site
refinement permits reclaiming a rendering source only when all possible
`toString` overrides avoid publishing it. It does not suppress callback effects.

The review exposed a pre-existing proof gap: symbolic analysis forgot the type
of a literal receiver, so a `toString` implementation returning
`"text".substring(0)` could not be recognized as a fresh-result factory. Retaining
the literal's String type fixes that proof. The literal remains borrowed;
publication of the allocated result still disqualifies temporary reclamation.
No runtime ownership registry, scan or new misuse check is added.

## Verification and lessons

Focused native comparisons exercise all added overloads, chaining, snapshots,
growth, self-insertion through CharSequence and Object, callback failures, nulls,
invalid ranges, integer extremes and floating type distinctions. Reversal is
compared over all 55,987 strings of lengths zero through six from a six-character
alphabet containing ASCII, NUL, and the high/low surrogate boundaries. The
comparison passed with Java 21.0.1 and the development host's Java 23.0.1; native
checks ran at `-O3` on macOS ARM64. This is focused development verification,
not a full-suite or multi-platform release check.

Separate tests count allocations and live storage, reclaim copied inputs before
the builder, and retain snapshots after freeing the builder. Negative checks
reject freeing live builder aliases or callback sources that publish themselves.
A published String result must stay live. Allocation-limit sweeps fail every
allocation in thirteen editing/snapshot cases, including floating conversion,
Object rendering, self-snapshot rendering and subsequent buffer growth. Failed
allocation preserves prior content/capacity and frees completed temporaries.
Callback-thrown exceptions are a separate case and may leave partial edits.

The earlier mistake was auditing isolated members against the motivating
application. That missed calls admitted through widening and inherited Object
behavior. Adding `append(char[])` is another example: without the overload, an
array can select Object rendering instead of text. This work reviews complete
neighboring overload groups and their actual Java behavior before implementation.

Independent implementation is appropriate for these buffer operations. Their
small size does not make their contracts trivial: Java comparisons caught
callback-length and exception-subtype differences during this work. Translating
OpenJDK wholesale would not replace the ownership audit or overload tests.
`AGENTS.md` already requires these checks through the behavioral-contract review;
no additional agent rule is needed.
