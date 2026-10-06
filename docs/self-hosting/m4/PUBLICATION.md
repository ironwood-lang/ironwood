<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M4.2 publication guarantees

Status: passes ([D271](../../DECISIONS.md#d271---publish-with-three-distinct-move-guarantees)).
`Files` keeps three publication policies apart: atomic replacement, replacement
with a permitted cross-device copy, and atomic no-replace. All sources are
original and use the default license; no OpenJDK source was consulted.

## Host primitives

Resolved against the packaging baselines before coding (the
[pre-change review](PRE_CHANGE.md#material-choices)):

| Policy | macOS 11.0 baseline | Linux glibc 2.17 baseline | Unsupported or other device |
| --- | --- | --- | --- |
| Atomic replacement | `rename(2)` | `rename(2)` | EXDEV: AtomicMoveNotSupportedException, nothing changed |
| Permitted fallback | `rename(2)`; on EXDEV for a regular file, exclusive temporary beside the target, copy with `fchmod`/`futimens`, `rename(2)`, `unlink` | the same | a directory or link across devices: AtomicMoveNotSupportedException |
| No-replace | `renamex_np(RENAME_EXCL)`, macOS 10.12+ | raw `renameat2(RENAME_NOREPLACE)` syscall, x86-64 316 and arm64 276 when the headers lack them; kernel 3.15+ | ENOTSUP, ENOSYS, a flag EINVAL (classified by comparing real paths) or EXDEV: AtomicMoveNotSupportedException; never check-then-rename |

`link` plus `unlink` was rejected for no-replace: Bridge staging publishes
directories, which it cannot move. `Files.move` is unchanged: it checks with
`lstat` and then renames, like Java's default move, so a creator in that
window is replaced; the native harness demonstrates this deterministically.
On macOS a name renamed to itself succeeds under `RENAME_EXCL`, while Linux
reports EEXIST; a successful exclusive rename whose source still names the
target's file therefore reports an existing target on both hosts.

## Consumer mapping

| Java demand | Native treatment | First consumer |
| --- | --- | --- |
| `Files.move(staged, destination, ATOMIC_MOVE, REPLACE_EXISTING)` with no fallback | `Files.moveAtomicReplacing` | BridgeJarArchive.publish (M6) |
| `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`, then `REPLACE_EXISTING` on AtomicMoveNotSupportedException | `Files.moveReplacing` | IronJar.write (M5) |
| `Files.move(stage, destination)` into a destination that must not exist, FileAlreadyExistsException as a competing build | `Files.moveAtomicNoReplace` | BridgeDistributionCommand.distribute, BridgeNativeSupport.deliver (S7) |

## Behavioral contract review

1. **Overloads and arguments.** The three moves are distinctly named Ironwood
   helpers with one `(Path, Path)` overload each; no Java call binds them, so
   Java's `CopyOption` overloads are untouched (option enums stay absent).
   `AtomicMoveNotSupportedException(String, String, String)` is Java's one
   constructor.
2. **Native-model constraints.** None beyond the host primitives above.
3. **Reduced surface.** The policies are separate names rather than options;
   `Files.move`'s Java semantics are unchanged.
4. **Comparison with Java.** Atomic replacement is compared with Java's
   `ATOMIC_MOVE`, the fallback with IronJar.write's two-step move, and the
   exclusive move with Java's default move, on sixteen cases each and across
   a second real file system.
5. **Provenance.** Independent implementations.

## Results

| Check | Result |
| --- | --- |
| One file system | For each move, sixteen cases (file, directory and link sources; absent, file, empty and non-empty directory, link and dangling-link targets; the same file through a hard link and through the same name; missing source and parent; a read-only parent; a directory into itself; Unicode names) print Java 21's lines and leave Java's trees from class and archive links. The exclusive move differs from Java's default move only for the two same-file cases, which it reports as existing. |
| Second file system | On an HFS+ disk image (macOS): atomic and exclusive moves fail with AtomicMoveNotSupportedException and change nothing; the replacing move copies a regular file over an existing target with mode `rwxr-x--x` and its 2020 modification time, removes the source and leaves no temporary; a directory and a link have no fallback. Java agrees on the atomic move and the file copy, and copies the link, rejects the non-empty directory and copies the exclusive move's file. |
| Competing creators | Eight processes publish into one name over 40 rounds alternating files and directories: exactly one reports success, its content is the target's, and every other process reports FileAlreadyExistsException with its source in place. |
| Native injection | `filesystem_services.c publication` at `-O3`: Files.move overwrites a competitor created between its check and rename; the exclusive rename keeps a competing file, empty directory and dangling link, reports the same name as existing, maps ENOTSUP and EXDEV (and ENOSYS and EINVAL on Linux) without effect, and classifies a directory moved into itself as invalid; the atomic move maps EXDEV and a non-empty-directory EEXIST; the replacing move copies with mode and nanosecond times, and a failed write or final rename keeps both entries with no temporary, while a failed source unlink reports the retained source with the target replaced. Heap and descriptors return to their baselines. |
| Ownership | Every move and `Files.move` as the control borrow both paths in every unfreed mode; a kept result aliases the target, so freeing either name while the other is observed is rejected, as is use after free. |
| Allocation failure | A staged publication (temporary file, write, atomic replacement, second staging with the fallback, exclusive move) unwinds every limit from 0 to 12 to the baseline with an empty scratch directory; limit 13 succeeds. |

No lowering of existing code changed and no hot path was touched, so no
benchmark applies.

## Hosts

| Host | Result |
| --- | --- |
| macOS 27.0.1 arm64 (this machine), APFS with an HFS+ image | every M4.1 and M4.2 test passes; the runtime compiles warning-free (beyond two existing notes) for the 11.0 deployment target with `-Wunguarded-availability` |
| Linux x86-64 (`estonia`: kernel 4.15, glibc 2.27 host, ext4 with tmpfs `/dev/shm`) | every M4.1 and M4.2 test passes, including the `getrandom` and `/dev/urandom` paths, a non-UTF-8 real path, the raw `renameat2` syscall and its EINVAL classification, and the cross-device copy onto tmpfs; built against the toolchain's glibc 2.17 sysroot, the native harness requires at most `GLIBC_2.17`, references `syscall` but neither `renameat2` nor `getrandom`, and runs on the 2.27 host |
| Linux arm64 | unresolved: no host was available; the guarded syscall numbers (276 and 278) compile but have not run |

The first Linux build exposed that glibc hides `realpath` at the runtime's
strict POSIX feature level, as it hides `syscall`; both are declared
explicitly, and the Java fixtures need a UTF-8 locale on that host.

## Boundaries

- Atomic visibility is not crash durability; no fsync policy exists.
- The fallback copies regular files only; Java's `REPLACE_EXISTING` move also
  copies links and empty directories across file systems.
- No real file system without exclusive rename was available: APFS, HFS+ and
  FAT (fskit) volumes on this macOS host all support `RENAME_EXCL`, so the
  unsupported path is qualified by injection.
