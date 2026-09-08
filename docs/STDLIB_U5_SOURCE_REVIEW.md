# U5 directory traversal source and compatibility review

## Classification

The U5 directory and recursive-traversal implementation is an independently
implemented Java-compatible standard-library slice under
`MIT OR Apache-2.0`.

The implementation uses Java 21 public API contracts as the behavioral target.
No OpenJDK implementation body, comment, Javadoc, test, algorithm, or
distinctive internal structure is copied, translated, or adapted.

## Stage 1: directory and attribute foundation

The first stage provides `DirectoryStream<Path>`,
`Files.newDirectoryStream(Path)`, `BasicFileAttributes`, `FileTime`,
`Files.readAttributes(Path)`, and `Files.isSymbolicLink(Path)`. Directory
iteration excludes `.` and `..`, allows one iterator, wraps post-open iteration
failures in `DirectoryIteratorException`, and rejects use after close with
`ClosedDirectoryStreamException`.

Ironwood has no reflection-backed `Class<A>` token, varargs, filesystem-provider
registry, or Java Collections facade. The supported attribute call is therefore
the compile-time-bounded `Files.readAttributes(Path)` overload for basic
attributes. It follows symbolic links. `Files.isSymbolicLink(Path)` performs an
internal no-follow query. Unsupported Java overloads are absent rather than
accepted with reduced runtime behavior.

`FileTime` stores the host timestamp at millisecond resolution and provides
`fromMillis`, `toMillis`, comparison, equality, and hashing. Attribute getters
return fresh caller-owned `FileTime` values. Linux reports epoch zero when a
creation timestamp is unavailable. `fileKey()` returns null, which the Java
contract permits. Nanosecond factories, unit conversion, and ISO rendering are
not part of this stage.

The native directory handle owns the small lookahead state required to make
`hasNext()` non-allocating. Each `nextEntry()` creates one caller-owned `Path`.
The ordinary `Iterator<Path>` view remains available, while `nextEntry()` is the
Ironwood ownership-aware spelling that lets the compiler prove reclamation of
each returned path. Callers close the stream deterministically, normally in
`finally`, and then may free its managed wrapper. A destructor does not hide or
replace native handle closure.

Directory entry names use the existing strict UTF-8 POSIX path convention.
Malformed names fail iteration rather than being replaced or silently skipped.
Open, lookahead, entry conversion, close, and stat failures use the existing
filesystem error categories.

## Stage 2: visitor traversal

The second stage provides `FileVisitResult`, `FileVisitor<T>`,
`SimpleFileVisitor<T>`, `FileSystemLoopException`, and
`Files.walkFileTree`. The two-argument Java-shaped overload does not follow
symbolic links. A fixed-arity overload accepts maximum depth and an explicit
link-following boolean because Ironwood does not provide Java's
`Set<FileVisitOption>` collection facade or varargs surface.

The traversal opens a directory before `preVisitDirectory`, closes its stream
before `postVisitDirectory`, and also closes every open stream when a callback
throws or requests termination. It implements `CONTINUE`, `TERMINATE`,
`SKIP_SUBTREE`, and `SKIP_SIBLINGS` with Java's callback placement. At the
maximum depth, a directory is delivered to `visitFile` and is not opened.
Null visitor results fail immediately.

No-follow traversal reports symbolic links to `visitFile`. Follow-link
traversal compares a candidate directory with each ancestor inside the selected
start tree by filesystem identity. A match is delivered to `visitFileFailed`
as `FileSystemLoopException`. This uses bounded scalar state and ordinary path
objects; it adds no process-global visited registry or runtime identity table.

Callback paths and attributes are traversal-owned and borrowed for one callback.
Closed-world semantic analysis checks every possible visitor target and rejects
a `walkFileTree` call when a callback can retain either value. This permits the
traversal to reclaim temporary paths, attributes, and closed stream wrappers
without runtime ownership tracking. Failure exceptions retain the ordinary
Ironwood exception lifetime when delivered to a visitor.

The focused [`examples/filetree`](../examples/filetree) program demonstrates a
find-style suffix search. It inspects borrowed path text and attributes, prints
matching regular files, and retains only its own suffix copy.
