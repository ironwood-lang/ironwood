<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Expanded canonical reference corpus

The [exact archive](canonical-corpus.tar.gz) and
[manifest](canonical-corpus-manifest.json) preserve 248 fresh captures: 124 for
original J0 and 124 for qualified J0-D247. Each of 31 sources has two fresh
explanation-off captures and two explanation-on captures per seed. Every source
byte strictly validates as UTF-8; 24 sources compile successfully and seven
are deliberately rejected. These are selected entry points, not a compiler suite.
No native compiler pilot is implemented or evaluated.

| Family | Exact sources | Intended comparison |
| --- | --- | --- |
| Original ownership pair | BranchJoin, SlotOrder | Accepted cleanup and rejected branch-retained array aliases; D247 first-store note delta |
| Volume | Volume16, Volume64, Volume256 | Called-method counts independent of depth |
| Control depth | Control16, Control64, Control128 | Nested if scopes, ordered branches and deep completion |
| Type depth | Types8, Types16, Types32 | Nested generic types with null and no owned allocation |
| Real snapshots | Ownership8, Ownership32, Ownership128 | Branch stores, joins, detachment and final safe free |
| Original source capture/inference | GenericInference, CapturedAliases, CapturedAliasesUnsafe | Target inference, retained capture and activated documented unsafe free |
| Original frontend/AST examples | TextBlocks, ClassicSwitch, ModernSwitch, InstanceOfPatterns, MultidimensionalArrays, DeterministicResources | Cooked/raw text, switches, patterns, arrays, constructor/resource control flow |
| Focused payload/spans | UnicodeSource, NumericForms, ShiftGenerics | Supplementary text/CRLF/Unicode identifier digit; radix/long/negative-zero payload; generic closing punctuation versus shifts |
| Malformed recovery | BadEscape, BadRadix, UnterminatedComment, UnterminatedBlock, TruncatedParse | Ordered lexer/parser diagnostics, EOF spans and partial AST without a crash |

Original examples retain their source bytes and original executable return
contracts; this capture procedure does not execute them. Focused accepted sources
state expected executable exit zero. The paired unsafe capture changes exactly
the documented commented free. No compiler or language semantics are changed by
these fixtures. Numeric AST literals retain lexical text; full typed IR/LLVM
captures additionally preserve the existing payload lowering.

## Capture and storage contract

`freeze-corpus.py --all --output NEW_PATH` freezes this exact named corpus using
the qualified ordered identity. Add the original identity and explicit
`--baseline-label original-J0` for the original seed. Exact --workload selections
are also supported. The runner pins seed/standard-library archive hashes, freezes
ReferenceCapture and helper source bytes, compiles with Java 21 --release 21,
-Xlint:all and -Werror, and uses the qualified JVM profile with option variables
cleared. Every producer still verifies the actual consumed standard-library
sources come from the frozen installation/archive. No seed rebuild is measured.

Each capture preserves tokens/spans, lexical diagnostics, full AST, parser
diagnostics, analysis diagnostics and typed IR, compilation diagnostics and final
typed IR, status pair, and LLVM where successful. Source identity is the existing
protocol's display filename plus UTF-8 hash; lists, semantic IDs, branches,
diagnostics and LLVM order remain visible. Producer stdout/stderr, argv, exact
source bytes and frozen tooling are retained. The qualifier compares raw artifact
hashes within identical settings; only primary diagnostics are compared across
different explanation flags, while both full note streams remain stored.

Every raw output byte is stored under its SHA-256, with per-capture filename,
byte count and hash mappings in captures.json. This removes duplicate storage,
not semantic content. It neither sorts nor rewrites an artifact. Reconstruct a
normal directory with `--output CAPTURE_ROOT --materialize LABEL NEW_DIRECTORY`;
the resulting files work with the existing compiler-neutral compare.py. Names
and hashes are checked before qualification. Raw captures are separate from
resource measurements; reflection/serialization costs are not native pilot costs.

The measurement runner's frozen source precedes a qualifier refinement that
retains exact mismatch groups/hashes in qualification.json before failing. The
archive's qualifier-controls/qualifier-source.py and each report's qualifier
source hash identify that refinement; no captured compiler output was replaced.

## Results and limitations

J0-D247 passes all 124 captures: exact repeat bytes, expected status/LLVM
presence and primary explanation parity. Original J0 reproduces its historical
SlotOrder explanation instability: explanation-on r1 differs from r0 in
diagnostics.json and compile-diagnostics.json. Its report therefore has
qualification_passed false and preserves both hashes. All other original repeat
groups and all primary/off-on checks agree. This does not supersede or relabel
the earlier original-J0 failure archive.

All 124 paired cross-seed comparisons differ only in those two SlotOrder note
artifacts for one explanation-on repeat. Tokens, AST, statuses, primary safety
verdicts, typed IR and LLVM remain unchanged. D247's incoming-path/current-store
order remains the scoped repair; other semantic order proofs remain separate.

Materialized positive/negative captures pass the existing neutral harness checks:
a changed diagnostic message, removed real IR branch edge and changed LLVM
byte are detected individually. Separate storage controls reject a changed raw
blob and a missing repeat. The original raw bytes remain intact. The retained
qualifier observes 57 structural record kinds, including SourcePosition/SourceSpan,
and 68 token kinds. Observed kinds are fixture coverage, not the independent
complete model/dispatch coverage required before a native rewrite.

The full M0.2 inventory, exact selected native helper/workload closure and
numerical resource budgets remain open. Corpus success alone does not close
M0/S0 or authorize the next milestone.
