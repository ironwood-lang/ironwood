# AGENTS.md

This file contains the durable rules an agent needs while working in the
Ironwood repository. Keep it concise. Do not copy feature catalogs, milestone
history, examples, or detailed specifications into this file; use the linked
documents as the source of truth.

## Mission

Ironwood is a statically compiled systems language that deliberately feels like
Java, but is designed from the beginning for closed-world, ahead-of-time native
compilation:

> Java designed to replace C++ instead of to run on a virtual machine.

Use the behavior a Java programmer would expect unless it conflicts with
closed-world native compilation, explicit safe reclamation, or an accepted
Ironwood decision.

Prioritize high-performance Java-shaped applications that need native AOT.
Ironwood is not intended to replace the full Java platform. Before adding a
broad subsystem only to enable a familiar API, evaluate a fixed convention or
smaller dependency and resolve material scope choices with the human. Record
accepted conventions in the decisions and compatibility documents.

## Architectural invariants

- Ironwood is its own language, not a Java implementation or transpiler.
- Programs compile ahead of time to native executables or libraries. They do
  not require JVM bytecode, a JVM, a JIT, or runtime class loading.
- The final native link is closed-world. Whole-program reachability,
  specialization, devirtualization, and ownership analysis are design assets.
- Ordinary source uses Java-shaped classes, interfaces, references, packages,
  exceptions, generics, arrays, and control flow. Do not expose raw pointers,
  pointer arithmetic, manual vtables, or Rust-style lifetime syntax.
- Ironwood has no garbage collector. Ordinary `new` allocations remain
  allocated until an accepted compiler-proven `free` reclaims them or the
  process ends. If safety cannot be proved, reject the `free`; never make it
  unsafe or silently reclaim unreachable objects.
- Keep the mandatory runtime small. A broad standard library is welcome because
  unreachable library code is removed from the closed-world program.
- Safety must not introduce runtime overhead on valid code paths. Prefer
  compile-time proofs and checks eliminated from generated code. Do not add
  runtime scans, hash lookups, registries, state tracking, or allocations solely
  to detect caller misuse of library contracts; document the caller's obligations
  instead. Constant-time and allocation-free checks still have a cost. If a
  safety requirement would add runtime overhead, explain the conflict and revisit
  the design with the human before implementing it. Do not silently add overhead
  or weaken the compiler-proven reclamation guarantee.
- Treat the normal-path guarantees in `docs/DECISIONS.md` decisions D132 and
  D133 as performance invariants.
  New compiler or runtime features must not add compiler-injected per-call or
  per-operation bookkeeping to valid steady-state paths. This includes continuous
  trace maintenance, TLS access, allocations, registry lookups, synchronization,
  and avoidable runtime helper calls. Prefer compile-time metadata, inlined
  minimal checks, and outlined uncommon paths. Before accepting a change to hot
  lowering, inspect `-O3` machine code and run the relevant deterministic
  benchmark. Discuss any unavoidable regression with the human before
  implementation.
- Preserve the established compiler-owned typed IR and native pipeline. Do not
  recreate the repository skeleton, replace the pipeline with Java-to-C source
  translation, or move language semantics into ad hoc LLVM text generation.
- Do not reintroduce JVM machinery to mimic a Java API. Prefer a native
  implementation, a deliberately reduced API, a compile-time mechanism, or an
  explicit omission.

## Repository baseline

- The project and language are named **Ironwood**.
- Ironwood source files use `.iron` and UTF-8.
- The bootstrap compiler is Java 21. Generated programs are native and do not
  require Java at runtime.
- LLVM 23 is the pinned backend. The pipeline is typed IR -> LLVM IR ->
  `llvm-as` -> `opt` -> `llc`, followed by Clang compilation of the isolated C
  runtime and native linking.
- Compiler, runtime, standard library, examples, projects, integration
  fixtures, and documentation are separate concerns. Preserve those
  boundaries.
- Keep focused feature demonstrations in `examples/` and larger end-to-end
  applications in `projects/`; both use compile/link/run scripts and ignored
  `target/` output.
- `./scripts/test.sh` is the primary executable-behavior verification command.
- `workspace/` is ignored local scratch space, not production source.

Exact current feature support belongs in the documentation, not here.

## Documentation map

Read the relevant source before changing behavior:

Do not preload documents from this map. Use `rg` to locate relevant sections
and read only the files or ranges needed for the current task. Expand the scope
only when the task requires a broader audit.

- `README.md`: project overview, build, examples, and distribution entry point.
- `docs/IRONWOOD_FORMATTING.md`: canonical formatting and source-style rules for
  Ironwood source.
- `docs/DIFFERENCES_FROM_JAVA.md`: concise explanations of Ironwood semantics that
  differ from similar-looking Java constructs.
- `docs/LANGUAGE_SPECS.md`: consolidated supported, deferred, open, and excluded
  language surface.
- `docs/LANGUAGE.md`: detailed grammar and language semantics.
- `docs/OBJECT_MODEL.md`, `docs/GENERICS.md`, and `docs/MEMORY.md`: object,
  generic, and reclamation rules.
- `docs/COMPILER.md`: compiler, IR, LLVM, runtime, and CLI architecture.
- `docs/IMPORTANT_OPTIMIZATIONS.md`: detailed account of the native
  performance work: cold-path outlining, guarded dispatch, `-O3` options, and
  trace decoding through LLVM-outlined code.
- `docs/STDLIB.md` and `docs/STDLIB_ROADMAP.md`: implemented library and planned
  usefulness work.
- `docs/DECISIONS.md`: accepted architectural decisions and supersessions.
  Search by topic for relevant accepted decisions and read only matching
  sections; read the file tail only when numbering or append format is needed.
- `docs/ROADMAP.md`: milestone history and active design checkpoints.
- `docs/IRONWOOD_VS_JAVA.md`: approachable, numbered Java comparison.
- `docs/OPENJDK_PORTING.md`, `docs/SOURCE_PROVENANCE.md`, and
  `docs/LICENSE_MECHANICS`: licensing and provenance rules.
- `examples/README.md`, `projects/README.md`, `runtime/README.md`, and
  `docs/IDK.md`: subsystem and application usage and packaging details.

Do not let tests become the only specification. Record meaningful semantic or
architectural changes in `docs/DECISIONS.md`, using a new decision that
explicitly supersedes an accepted decision when necessary.

## Licensing and source provenance

Read and follow `docs/LICENSE_MECHANICS` before creating, copying, translating, or
substantially adapting source. It is authoritative.

- Original Ironwood code and independently implemented Java-compatible code use
  `SPDX-License-Identifier: MIT OR Apache-2.0`.
- A substantial OpenJDK translation is a derived work. It may be added only
  when the exact upstream file expressly carries the Classpath Exception.
- OpenJDK-derived files must retain their complete upstream header, use
  `SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0`, identify
  the exact upstream path and immutable commit, describe Ironwood changes, and
  update `docs/SOURCE_PROVENANCE.md` and applicable notices.
- Never place OpenJDK-derived implementation in a default-licensed file without
  reclassifying the whole file. Do not copy OpenJDK implementation comments,
  Javadocs, or tests into work described as independently implemented.
- Do not import source with a missing or uncertain license or provenance. Stop
  for a licensing review instead.
- Every library port follows `docs/OPENJDK_PORTING.md`, ships required source and
  notices, and passes `./scripts/check-licenses.sh`.

The pool and data-structure implementations were contributed directly by their
original author. Treat `ironwood.pool` and `ironwood.ds` as first-party Ironwood
standard-library source, not as a compatibility layer. Never use the former
project or organization names in Ironwood source, documentation, tests,
metadata, packages, or generated artifacts. The prohibited case-insensitive
brand token is formed by writing `cor` immediately followed by `al`; keep even
historical and licensing descriptions brand-neutral. Unmodified third-party
license texts may remain unchanged.

## Standard-library work

- Keep APIs familiar to Java programmers while adapting implementation and
  failure behavior to Ironwood's native, closed-world model.
- Apply the [behavioral contract review](docs/OPENJDK_PORTING.md#behavioral-contract-review)
  to every standard-library API addition or semantic change, regardless of
  implementation provenance. Check the Java behavior of calls admitted by
  overload resolution, including widening and inherited defaults, beyond the
  motivating application's examples. Incomplete support needs an enforced
  compile-time boundary, an omitted member, or a distinctly named Ironwood
  helper. Documentation alone must not excuse runtime traps or silent behavior
  differences for Java-valid calls.
- Minimize managed-object and native-heap allocation in standard-library hot
  paths. Allocate when an object is a required result or represents state meant
  to escape, be retained, or have a meaningful lifetime; do not create
  short-lived helper objects merely for immediate work when primitives, bounded
  stack state, caller-provided storage, or reusable storage provide a practical
  non-allocating design. Test allocation behavior for such paths.
- Do not default either to wholesale OpenJDK translation or to blanket
  reimplementation. Prefer verified Classpath-covered OpenJDK-derived helpers
  for complex, mature, portable algorithms when translation reduces risk and
  effort; prefer original or independently implemented code for small APIs and
  Ironwood-specific compiler, runtime, ownership, or native mechanisms. Keep
  the public API compatibility goal the same whichever provenance is selected.
- Use and extend `ironwood.ds` for maps, lists, sets, and related collections;
  do not build a duplicate Java Collections Framework.
- Audit every allocation and ownership transfer. Java and OpenJDK assume a GC;
  Ironwood code must account for explicit reclamation and must not import that
  assumption accidentally.
- Respect pool ownership and reuse contracts. Do not add unnecessary `free`
  operations merely because an implementation uses pooled storage.

## Engineering standards

- Inspect existing code and repository state before editing. Preserve unrelated
  human changes and keep task commits narrowly scoped.
- Follow `docs/IRONWOOD_FORMATTING.md` when authoring or revising Ironwood source.
- Favor clear, focused code and explicit compiler phases over metaprogramming,
  god objects, speculative frameworks, or clever shortcuts.
- Preserve source spans through the compiler. Bad source must produce useful
  diagnostics, not compiler crashes.
- Prefer immutable semantic structures where practical. Use structured results
  rather than exceptions for ordinary compiler control flow.
- Keep frontend semantics in typed analysis and IR. LLVM is a backend, not the
  language specification.
- Add regression coverage when fixing executable behavior. New language behavior
  should have parser, semantic, typed-IR, native integration, and negative tests
  wherever applicable.
- Where behavior intentionally matches Java, small differential tests are
  useful. Do not use Java as the oracle for Ironwood-specific behavior such as
  `free` or excluded dynamic APIs.
- When creating or materially changing an Ironwood example or project, add
  concise comments to its `.iron` source explaining what it demonstrates,
  what output or exit status to expect, and any non-obvious control flow,
  ownership, reclamation, or runtime checks. Keep the comments synchronized
  with behavior; do not narrate obvious syntax line by line.
- Do not make unsupported compatibility or performance claims.

## Documentation obligations for feature changes

Whenever a numbered language feature is implemented or changes status, search
for every occurrence of its feature number and name throughout
`docs/IRONWOOD_VS_JAVA.md` and update every affected section. Do not read the
document wholesale. At minimum, align:

- the feature matrix status and summary;
- the detailed heading and prose;
- Java and Ironwood snippet labels;
- pending-feature tables and ranks;
- roadmap summaries and runnable example links; and
- displayed verification counts.

Remove stale mixed-status wording such as “intended roadmap syntax” or “not
implemented today” once a feature is complete. Do not choose or authorize the
next implementation target merely while updating status documentation.

Keep every other affected authoritative document in the documentation map
synchronized with executable behavior.

## Checkout and Git policy

All work must happen directly in the sole canonical checkout for the
`ironwood-lang/ironwood` GitHub repository:

`${HOME}/workspace-mba-m2/Ironwood`

Before reading or changing repository files, verify that this exact directory
is active and that `origin` points to `https://github.com/ironwood-lang/ironwood.git`
for both fetch and push. If either check fails, stop instead of changing files,
remotes, branches, or history.

For every task that changes repository content, complete this workflow unless
the human explicitly instructs otherwise:

1. Work directly on local `main`. Do not create or switch to a task branch, and
   do not create a Git worktree, unless the human explicitly requests one.
2. Before editing, fetch `origin` and fast-forward local `main` to `origin/main`.
   If this cannot be completed safely, report the blocker instead of creating a
   branch or changing history.
3. Verify the in-scope change according to the verification policy below.
4. Commit only the task's changes directly to `main`.
5. Fetch `origin` again and safely integrate any new `origin/main` commits before
   pushing.
6. Push only `main` unless the human explicitly requests a different target.
7. Fetch and confirm that local and remote `main` have zero divergence, and
   leave the canonical checkout on a clean, synchronized `main` branch.

Never include unrelated human changes in the task commit. If verification,
commit, merge, push, or synchronization cannot be completed safely, report the
specific blocker instead of claiming completion.

## Verification policy

- Choose the smallest meaningful checks for the behavior and risk of the change.
  A file's location alone does not justify running tests. Run `git diff --check`
  for every change.
- Pure version-label, documentation, comment, formatting, and agent-policy edits
  need focused consistency checks, not compiler suites or packaging smoke tests.
  For a version bump, rebuild only as needed and verify the affected tools'
  version output and matching documentation.
  Changes to runnable examples, including code inside documentation comments,
  require a focused compile/run check of the changed example.
- Never run the full compiler/native test suite during development. This includes
  unfiltered `./scripts/test.sh`, `./scripts/test-platforms.sh --full`, and
  equivalent unfiltered invocations of `CompilerTests`.
  Shared compiler phases, runtime changes, multiple affected features, merges,
  and pushes do not justify an exception. Run only the relevant individual tests
  or focused regression groups. Use `./scripts/test.sh --test 'EXACT NAME'` or
  the selected-test modes in `docs/LOCAL_TESTING.md`.
- Reserve the full suite for the final readiness check of a release the human
  has requested, or an explicit human request to run the full suite. After a
  failure, fix the cause and rerun only the failing tests. Do not repeat the full
  suite unless the human explicitly requests another complete run.
- Keep hosted three-platform builds release-only. Do not add full-suite checks
  on ordinary pushes or pull requests, or trigger paid cross-platform validation
  during development, unless the human explicitly requests that change.
  Compiler/native suites run locally before release. Hosted releases build and
  verify the packages without running the compiler/native suite.
- When release or packaging behavior changes, exercise the affected packaging
  and smoke-test paths described in `README.md` and `docs/IDK.md`. A version-label
  bump alone is not a packaging behavior change.
- Run `./scripts/check-licenses.sh` when changes affect source files, license
  headers, provenance, notices, or distribution contents. Skip it for metadata,
  documentation, or policy edits with no licensing impact.
- Once the relevant checks pass, stop testing. Broaden or repeat checks only
  when new changes, failures, or unresolved risks justify it.

## Response policy

Do not append token, credit, allowance, account-usage, or similar per-prompt
reporting to responses. End with the task outcome or information directly
relevant to the work.

## Browser policy

Always use Opera for browser interactions. Never use Chrome. If Opera cannot
complete an interaction, report the limitation instead of switching browsers.

## Mandatory Writing Style Rule: No Em Dashes

Never use the em dash character (`—`, Unicode U+2014) when writing or editing prose, documentation, comments, commit messages, user-facing text, or any other textual content.
This is a hard requirement, not a stylistic preference.
Whenever an em dash would normally be used, rewrite the sentence using other punctuation such as a period, comma, colon, semicolon, or parentheses.

BAD:
> "That worked—I can now see the private repository and its rendered IronDocs pages in Ego Browser."

GOOD:
> "That worked. I can now see the private repository and its rendered IronDocs pages in Ego Browser."

Before finalizing any generated text, verify that it contains no em dash characters (`—`).

## Mandatory Markdown Rule: Ironwood Code Fences

Never attach the language labels `iron` or `ironwood` to a fenced Markdown code
block. Markdown renderers do not recognize them consistently. Always use the
`java` fence label for every Ironwood source snippet, never an unlabeled fence.
Before finalizing any Markdown change, search all changed Markdown files for
opening fences labeled `iron` or `ironwood` and replace every such label with
`java`.
