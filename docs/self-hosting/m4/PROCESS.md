<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# M4.3 synchronous launch and discovery adapters

Status: the process facility passes
([D272](../../DECISIONS.md#d272---run-external-programs-synchronously-by-absolute-path)).
`ironwood.process.ProcessRunner.runToFile` runs a program by absolute path
with inherited environment, an optional child directory and merged file
output, and returns a primitive-only `ProcessResult`. All sources are
original and use the default license.

## Launch design

The pre-change review compared `posix_spawn` with `fork`/`execv` against the
baselines: macOS 11.0 has spawn-time `chdir` (10.15+) and
close-on-exec-by-default, but glibc 2.17 has no spawn-time `chdir`, so one
`fork`/`execv` path serves both hosts. The runtime encodes the command,
directory and output spellings into one block before forking (an allocation
failure is raised before any descriptor exists); the parent opens the output
(so a relative output is the caller's) and `/dev/null`, keeps both and the
report pipe close-on-exec and above descriptor 2; the child only calls `dup2`,
`chdir`, `execv`, `write` and `_exit`; a pre-exec failure reaches the parent
as a stage and errno through the pipe, so a child's own exit status 127 is a
result; the parent retries EINTR on the pipe and in `waitpid` and always
reaps. Stage and reason map to NoSuchFileException, AccessDeniedException or
a FileSystemException naming the executable, directory or output.

## Behavioral contract review

1. **Overloads and arguments.** One distinctly named static method; Java's
   ProcessBuilder and Process stay absent, so no Java call binds it. A null
   directory selects the caller's directory; null command, element or output
   throws NullPointerException.
2. **Native-model constraints.** Closed-world native launch with no JVM
   machinery; executable discovery belongs to callers (B4), so no PATH search.
3. **Reduced surface.** No environment map, pipes, asynchronous wait,
   timeout or kill; the reduced ProcessBuilder/Process remains roadmap item 4.
4. **Comparison with Java.** The exit value follows Java's POSIX convention
   (128 plus the signal); arguments use U+FFFD for an unpaired surrogate where
   Java uses `?`; inherited descriptors without close-on-exec reach the
   program where Java's launcher closes them. These are recorded in
   DIFFERENCES_FROM_JAVA.md.
5. **Provenance.** Independent implementation.

## Caller adaptations

The launch audit's two bare launches are gone from the Java seed: TlsDependency
runs `/usr/bin/xcrun` (MacNativeTools' fixed choice, now shared as
`MacNativeTools.XCRUN`) and LlvmToolchain launches the `brew` that the new
compiler-owned `ExecutableSearch` resolves. The search tries PATH entries in
order, skips empty entries rather than searching the working directory,
resolves a relative entry against the working directory without normalizing
it (so `link/../bin` follows the link, as exec would) and takes the first
executable regular file. PATH-selected `xcrun` substitutes are no longer
selected; Homebrew remains optional. LLVM/JDK selection and the fixed Apple
tool paths stay caller policy.

## Compiler integration

`IrProcessInstruction` is bound only to the exact `runProcessValue` intrinsic
and joins every IR consumer: closed-world effects (an unused result still
launches), allocation-failure reachability, borrow dispatch (a primitive
result), primitive specialization, CFG renaming, invoke eligibility and LLVM
emission (`invoke i64 @ironwood_process_run`). `runToFile` has an audited
borrowing contract for its parameters and, by exact signature, for the
command's elements: an analyzed method receiving a `String[]` otherwise
exposes the elements, which would make every String placed in a command
unreclaimable. String is final and the launch retains nothing, so the
contract is sound; an ordinary method taking `String[]` keeps the
conservative rule.

## Results

| Check | Result |
| --- | --- |
| Controlled programs | Twenty-eight cases from class and archive links against `process_helper.c` with a PATH of empty entries and decoys: exit statuses 0, 3 and a child's 127; signals 15 and 9 (exit values 143 and 137); spaces, quotes, `$`, `*`, `;|&`, a backslash, an empty argument, an emoji and an unpaired surrogate passed literally; NUL rejected; an inherited variable; child and inherited directories; output relative to the caller; empty stdin; 2 MiB of interleaved output; only descriptors 0-2 in the child while the caller holds a file stream and a directory stream; missing, non-executable, directory and unknown-format executables; a shell script; missing and non-directory directories; missing and directory outputs; empty and relative commands; 300 repeated launches with a stable descriptor count. No decoy ran. |
| Native injection | `process_services.c`: failures opening the output and stdin, creating the pipe and forking close what was opened and start nothing; an encoding allocation failure is raised before any descriptor; exec and directory failures arrive through the pipe and the child is reaped; a real SIGALRM every 20 ms interrupts the wait, which resumes; 200 launches leave descriptors and children unchanged; a descriptor inherited without close-on-exec reaches the program (the recorded boundary). |
| Signals | With the program leading a job's process group, SIGINT to the group ends the program (exit value 130) and its tool; SIGTERM to the program alone ends it (143) and leaves the tool running, the recorded boundary. |
| Ownership | In every unfreed mode the command array, a Path spelling and a fresh String in the command and both Paths are freed after the call and the result once; double free, use after free, freeing an element while the array is live, freeing an element of a published array and an ordinary `String[]` method stay rejected. |
| Allocation failure | Every limit from 0 to 6 around a launch and its read-back unwinds to the baseline and leaves no output file; limit 7 succeeds. |
| Typed IR | One process instruction remains for an unused result; a destructor may not launch (allocation effect). |
| Caller adaptations | ExecutableSearch takes the first executable regular file over a non-executable file and a directory, skips empty entries even when the working directory holds the tool, resolves relative entries and keeps `link/../bin` unnormalized. A child JVM with PATH `::decoy:fake:/usr/bin:/bin` launches `fake/brew` by absolute path and returns its prefix, and finds none without brew; TLS and Apple discovery under a PATH offering a decoy `xcrun` return `/usr/bin/xcrun`'s SDK and never run the decoy. |
