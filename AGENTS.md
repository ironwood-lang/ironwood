# AGENTS.md

Public guidance for AI coding agents working on Ironwood. See
[CONTRIBUTING.md](CONTRIBUTING.md) for contribution requirements and the project's
AI-assisted development policy. Keep durable rules here; keep feature catalogs,
history, examples, and specifications in the linked documents.

## Checkout, edits, and Git

For maintainer-directed agent tasks, follow this workflow unless the human
explicitly instructs otherwise. External contributors follow the pull request
process in `CONTRIBUTING.md`.

- Use the task's designated canonical checkout for `ironwood-lang/ironwood`.
  Before reading or changing repository files, verify that the working directory
  is that checkout's root and that `origin` uses
  `https://github.com/ironwood-lang/ironwood.git` for both fetch and push. Stop on
  a mismatch; do not change files, remotes, branches, or history to repair it.
- Work directly on local `main`. Do not create or switch branches or create
  worktrees unless the human explicitly requests it.
- Before editing, fetch `origin` and fast-forward `main` to `origin/main`.
  Report a blocker if this cannot be done safely.
- Treat current file contents as authoritative, including committed and
  uncommitted human edits. Immediately before each edit, re-read the affected
  file or region. Apply the smallest patch; never restore an earlier agent
  version or rewrite unrelated content. Inspect the diff for unintended
  deletions, reversions, or rewrites. If human edits conflict with the task and
  cannot be preserved safely, stop and ask.
- Verify the change, commit only the task's changes to `main`, fetch again,
  safely integrate new `origin/main` commits, and push only `main`. Fetch
  afterward and confirm zero divergence and a clean, synchronized `main`.
  Never commit unrelated human changes.
- Report specific verification, commit, integration, push, or synchronization
  blockers instead of claiming completion.

## Language and compiler invariants

Ironwood is its own statically compiled systems language, not a Java
implementation or transpiler:

> Java designed to replace C++ instead of to run on a virtual machine.

- Use the behavior a Java programmer expects unless it conflicts with
  closed-world native compilation, explicit safe reclamation, or an accepted
  Ironwood decision. Prioritize high-performance Java-shaped applications
  needing native AOT, not the full Java platform. Before adding a broad subsystem
  for a familiar API,
  evaluate a fixed convention or smaller dependency, resolve material scope
  choices with the human, and document accepted conventions.
- Compile ahead of time to native executables or libraries with a closed-world
  final link. Preserve whole-program reachability, specialization,
  devirtualization, and ownership analysis. Do not introduce JVM bytecode, a JVM,
  JIT, runtime class loading, or JVM machinery to mimic an API; use native or
  compile-time mechanisms, a reduced API, or an explicit omission.
- Keep ordinary source Java-shaped: classes, interfaces, references, packages,
  exceptions, generics, arrays, and control flow. Do not expose raw pointers,
  pointer arithmetic, manual vtables, or Rust-style lifetime syntax.
- There is no GC. Ordinary `new` allocations remain until a compiler-proven
  `free` or process termination. Reject `free` when safety cannot be proved;
  never weaken the guarantee or silently reclaim unreachable objects.
- Keep the mandatory runtime small; unreachable standard-library code is removed
  from the closed-world program, allowing a broad library.
- Use UTF-8 `.iron` source, a Java 21 bootstrap compiler, and the pinned LLVM 23
  backend. Preserve the pipeline: compiler-owned typed IR -> LLVM IR ->
  `llvm-as` -> `opt` -> `llc`, plus Clang compilation of the isolated C runtime
  and native linking. Generated programs do not require Java.
- Keep frontend semantics in typed analysis and IR. Do not replace the repository
  skeleton or pipeline, translate Java to C, or implement semantics in ad hoc
  LLVM text generation.

### Safety and performance

- Memory-safety enforcement is mandatory in every compiler mode. Preserve
  compile-time protection against dangling references, use after free, and
  double free. If aliasing, escape, or lifetime facts cannot prove a `free`
  safe, reject it with a compilation error. Never assume unknown effects are
  non-retaining or grant borrowing/ownership exemptions merely to silence
  diagnostics or make code compile.
- Keep missing-free diagnostics (`--unfreed=off|warn|error`) separate from
  memory-safety enforcement; no setting may disable or downgrade mandatory
  safety errors. Fix false positives by correcting analysis or code without
  weakening reclamation proofs. Changes to ownership or escape analysis need
  focused regressions for both accepted safe cases and rejected unsafe cases.
- Safety must not add runtime overhead on valid paths. Prefer compile-time
  proofs and eliminated checks. Do not add scans, hash lookups, registries,
  state tracking, or allocations solely to detect library-contract misuse;
  document caller obligations instead. Constant-time, allocation-free checks
  still cost something. Discuss any safety requirement needing runtime overhead
  with the human before implementation; do not silently add overhead or weaken
  compiler-proven reclamation.
- Preserve D132 and D133 in `docs/DECISIONS.md`: no compiler-injected per-call or
  per-operation bookkeeping on valid steady-state paths, including continuous
  trace maintenance, TLS access, allocations, registry lookups, synchronization,
  or avoidable runtime helper calls. Prefer compile-time metadata, inlined
  minimal checks, and outlined uncommon paths. Before accepting changes to hot
  lowering, inspect `-O3` machine code and run the relevant deterministic
  benchmark. Discuss unavoidable regressions with the human before implementation.

## Implementation standards

- Inspect existing code before editing. Preserve boundaries between compiler,
  runtime, standard library, examples, projects, integration fixtures, and docs.
  Put focused demonstrations in `examples/` and larger applications in
  `projects/`, using compile/link/run scripts and ignored `target/` output.
  `workspace/` is ignored scratch space, not production source.
- Follow `docs/IRONWOOD_FORMATTING.md` for Ironwood source. Prefer focused code,
  explicit compiler phases, immutable semantic structures where practical, and
  structured results for ordinary compiler control flow. Avoid god objects,
  speculative frameworks, metaprogramming, and clever shortcuts.
- Preserve source spans and useful diagnostics; bad source must not crash the
  compiler. Do not make unsupported compatibility or performance claims.
- Add regression coverage for executable fixes. New language behavior needs
  parser, semantic, typed-IR, native integration, and negative tests where
  applicable. Use small Java differential tests only for Java-compatible
  behavior, never as an oracle for `free` or excluded dynamic APIs.
- For new or materially changed examples/projects, add concise `.iron` comments
  explaining the demonstration, expected output or exit status, and non-obvious
  control flow, ownership, reclamation, or runtime checks. Keep comments current;
  do not narrate obvious syntax.

### Standard library

- Keep APIs familiar to Java programmers within Ironwood's native, closed-world
  model. Apply the
  [behavioral contract review](docs/OPENJDK_PORTING.md#behavioral-contract-review)
  to every API addition or semantic change, regardless of provenance. Check all
  Java-valid calls admitted by overload resolution, including widening and
  inherited defaults. Incomplete support requires an enforced compile-time
  boundary, an omitted member, or a distinctly named Ironwood helper.
  Documentation cannot excuse runtime traps or silent Java-incompatible behavior.
- Minimize managed-object and native-heap allocation on hot paths. Allocate for
  required results or meaningful retained or escaping state; prefer primitives,
  bounded stack state, caller-provided storage, or reuse over short-lived helper
  objects. Test allocation behavior.
- Choose provenance per implementation: prefer verified Classpath-covered OpenJDK
  helpers for complex, mature, portable algorithms when translation reduces
  risk and effort; prefer original code for small APIs and Ironwood-specific
  compiler, runtime, ownership, or native mechanisms. Preserve the same public
  API compatibility goal either way.
- Extend `ironwood.ds` for collections; do not duplicate the Java Collections
  Framework. Audit every allocation and ownership transfer without assuming GC.
  Respect pool ownership and reuse contracts; do not add unnecessary `free`
  operations for pooled storage.

## Licensing and provenance

Read and follow `docs/LICENSE_MECHANICS` before creating, copying, translating, or
substantially adapting source. It is authoritative.

- Original Ironwood and independently implemented Java-compatible code use
  `SPDX-License-Identifier: MIT OR Apache-2.0`.
- Substantial OpenJDK translations are derived works, permitted only when the
  exact upstream file expressly carries the Classpath Exception. Retain the
  complete upstream header, use
  `SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0`, identify
  the exact upstream path and immutable commit, describe Ironwood changes, and
  update `docs/SOURCE_PROVENANCE.md` and applicable notices.
- Never put OpenJDK-derived implementation in a default-licensed file without
  reclassifying the whole file. Do not copy OpenJDK implementation comments,
  Javadocs, or tests into independently implemented work. Stop for licensing
  review if any imported source has uncertain or missing license/provenance.
- Every library port must follow `docs/OPENJDK_PORTING.md`, ship required source
  and notices, and pass `./scripts/check-licenses.sh`.
- `ironwood.pool` and `ironwood.ds` were contributed by their original author and
  are first-party standard-library source, not compatibility layers. Never use
  former project or organization names in source, docs, tests, metadata,
  packages, or generated artifacts. The prohibited case-insensitive token is
  `cor` immediately followed by `al`. Keep historical and licensing descriptions
  brand-neutral; unmodified third-party license texts may remain unchanged.

## Verification

- Run `git diff --check` for every change. Choose the smallest meaningful checks
  for its behavior and risk, not merely its file location. Once they pass, stop;
  broaden or repeat only for new changes, failures, or unresolved risks.
- Pure documentation, comment, formatting, policy, or version-label edits need
  focused consistency checks, not compiler suites or packaging smoke tests.
  For version bumps, rebuild only as needed and verify tool version output and
  matching docs. Changed runnable examples, including code in documentation
  comments, need a focused compile/run check.
- `./scripts/test.sh` is the primary behavior check. During development, use
  `./scripts/test.sh --test 'EXACT NAME'` or focused groups from
  `docs/LOCAL_TESTING.md`. Never run unfiltered `./scripts/test.sh`,
  `./scripts/test-platforms.sh --full`, or equivalent unfiltered `CompilerTests`.
  Shared phases, runtime changes, multiple features, merges, and pushes do not
  justify a full suite.
- Run the full suite only for a human-requested release's final readiness check
  or an explicit request for the full suite. After failures, fix and rerun only
  failing tests; another complete run requires an explicit human request.
- Keep hosted three-platform builds release-only. Do not add full-suite checks
  to ordinary pushes or pull requests, or paid cross-platform validation during
  development, unless explicitly requested. Compiler/native suites run locally
  before release; hosted releases build and verify packages without those suites.
- For release/packaging behavior changes, exercise affected packaging and smoke
  paths in `README.md` and `docs/IDK.md`. A version-label bump alone is not a
  packaging behavior change.
- Run `./scripts/check-licenses.sh` for source, license headers, provenance,
  notices, or distribution contents. Skip it for metadata, documentation,
  or policy edits without licensing impact.

## Documentation

Read relevant source before changing behavior. Use `rg` to find needed sections;
do not preload documents. Read only needed files or ranges, expanding scope for
broader audits when required. Search decisions by topic; read the tail only for
numbering or append format.

- `README.md`: overview, build, examples, distribution.
- `docs/IRONWOOD_FORMATTING.md`: source style.
- `docs/LANGUAGE_SPECS.md`, `docs/LANGUAGE.md`: feature status, grammar, semantics.
- `docs/OBJECT_MODEL.md`, `docs/GENERICS.md`, `docs/MEMORY.md`: object, generic,
  and reclamation rules.
- `docs/DIFFERENCES_FROM_JAVA.md`, `docs/IRONWOOD_VS_JAVA.md`: semantic differences
  and numbered feature comparisons.
- `docs/COMPILER.md`, `docs/IMPORTANT_OPTIMIZATIONS.md`: compiler, IR, LLVM,
  runtime, CLI, and native performance design.
- `docs/STDLIB.md`, `docs/STDLIB_ROADMAP.md`: implemented and planned library work.
- `docs/DECISIONS.md`, `docs/ROADMAP.md`: accepted decisions, supersessions,
  milestones, and design checkpoints.
- `docs/LICENSE_MECHANICS`, `docs/OPENJDK_PORTING.md`, `docs/SOURCE_PROVENANCE.md`:
  licensing and provenance.
- `docs/LOCAL_TESTING.md`: focused test selection.
- `examples/README.md`, `projects/README.md`, `runtime/README.md`, `docs/IDK.md`:
  subsystem usage and packaging.

Keep affected authoritative docs synchronized with behavior. Tests must not be
the only specification. Record meaningful semantic or architectural changes in
`docs/DECISIONS.md`; new decisions must explicitly supersede earlier ones when
necessary. Record accepted conventions in decisions and compatibility docs.

When a numbered feature is implemented or changes status, search every occurrence
of its number and name in `docs/IRONWOOD_VS_JAVA.md`; do not read it wholesale.
Align the matrix, headings and prose, Java and Ironwood snippet labels, pending
tables and ranks, roadmap summaries, runnable links, and verification counts.
Remove stale mixed-status wording when complete.
Updating status does not authorize choosing the next implementation target.

## Communication and tools

- Never use Unicode U+2014 in any written text, including prose, comments,
  documentation, commit messages, and responses. Use other punctuation and
  verify generated text before finalizing it.
- Use the `java` Markdown fence label for every Ironwood snippet, never `iron`,
  `ironwood`, or an unlabeled fence.
- End responses with task-relevant outcomes, without token, credit, allowance,
  account-usage, or similar per-prompt reporting.
- Use Opera for browser interactions, never Chrome. If Opera cannot complete an
  interaction, report the limitation rather than switching browsers.
