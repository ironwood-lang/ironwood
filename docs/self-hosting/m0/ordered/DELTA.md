<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# D247 ordered Java baseline delta

Original J0 remains revision 6bde84df320e9dbc32977a2b665d214ac09bc2d4 with
its original identity, attributed discovery, captures and failed resource
references. This baseline is explicitly named J0-D247, source commit
28664736270f19a73c0a79a2f845bac916953471. Only FunctionAnalyzer.java differs among
the 460 production Java inputs. The later overwrite test does not change
production inputs. It verifies the same source contract separately.

Two independent source reconstructions produce seed SHA-256
cf010a1c38b4931555d855bff035704d71acc5e7a989df6179745e3b6a5c1405 and the
unchanged strict standard-library archive SHA-256
6e5d8448b5db78122fb5b3750a602b960e13d9021ccb146fd0b3be25fb445dcf.
[qualified-identity.json](qualified-identity.json) records the full immutable
commit and all source/tool/profile inputs. The earlier identity preserves its
original abbreviated revision argument; the freezer now resolves arguments to
a full commit before exporting source. Neither report overwrites original J0.

## Reached dependency delta

Original origin H0613 is Map.copyOf(knownArraySlots) at FunctionAnalyzer:14261.
The original catalog has 25 propagated candidates, all in FunctionAnalyzer.
It stays intact as evidence of the old source; the new copy no longer introduces
unordered slot membership. The private helper copies entries once in current
builder order, rejects null keys/values and freezes independent membership.
The outer snapshot method supplies its builder directly instead of allocating
a redundant intermediate linked copy. It does not retain that mutable builder.

New reached Java members are Collections.unmodifiableMap(Map) and two immediate
Objects.requireNonNull(T) checks inside copyArraySlots. The latter already exist
elsewhere in the original dependency inventory. The map wrapper here admits only
the newly owned private linked copy. Read/accessor iteration forwards its order;
mutation, including view mutation, must fail; no mutable alias to the owned copy
escapes. It retains keys/values shallowly. B1/M1.1 supplies a private immutable
ordered slot-membership representation with those copy/null/value contracts;
B7/M1.3 rewrites the immediate copy callback to local traversal if needed. No
general Collections facade, new public map API or GC ownership assumption is
selected. Retire owned membership only after snapshot/flow/evidence readers end,
without freeing borrowed allocation nodes. The null checks are Java compiler
contract checks, not injected application runtime bookkeeping.

| Original candidate lines | Actual consumer / effect of this delta |
| --- | --- |
| 12518, 12519, 14261 | Snapshot construction and its old immutable copy. Replace that exact copy boundary with ordered immutable ownership; preserve independent membership and null rejection. |
| 12545, 12605, 12646 | Restore putAll, common-slot insertion and first incoming blocking-store selection. Preserve path precedence, then current-store insertion order before selecting a witness. This is the repaired observation. |
| 2072, 13176 | Explicit minimum slot index, then container allocation order. Those comparisons already define deterministic priority and remain unchanged. Store order does not override them. |
| 2121, 12600 | Remove all matching membership, without selecting one removed item. Predicates use container identity or incoming value identity. Retain order of surviving entries. |
| 11877, 11879, 11880 | Locally retained membership union; actual callers only contains-test that set. addAll candidates overapproximate merged sources. This contribution assigns no IDs/witnesses and is extensional. Other inputs are still separately reviewed. |
| 12875 | anyMatch alias predicate in a back-edge test. Slot order returns only a Boolean; back-edge priority comes from its explicit path list. |
| 13048 | Recursive known-element collection inserts into an identity set. Slot traversal is ordered, but that resulting set and one-of consumers have separate first-event/budget obligations, still open. |
| 9383, 13209, 13227, 13275 | Exposure/cancellation/escape walkers consume current slot membership in explicit order, including a list snapshot before recursive cancellation. Their other retained-child and one-of inputs may be unordered; the complete recursive first-event/optional-budget proof remains open. |

This traces the slot-origin contribution without claiming that repairing one
producer proves every other hash origin or recursive consumer. The preserved
original inventory, global source proofs, syntax/capture ledger and M0.3 kernel
selection remain required.

## Qualified references and measured follow-up

[references.tar.gz](references.tar.gz) and its [manifest](references-manifest.json)
retain four fresh canonical captures each of BranchJoin and SlotOrder, exact
producer commands/source hashes, freeze logs and 36 fresh resource follow-ups.
Every repeated structural output and LLVM byte sequence matches within each
fixture. Against original frozen references, BranchJoin matches all ten artifacts;
SlotOrder differs only in diagnostics.json and compile-diagnostics.json, where
the selected incoming store note is line 18 instead of line 19. Primary messages,
primary spans, safety status, tokens and AST are unchanged. No output is sorted
or normalized.

Resource follow-ups select BranchJoin, SlotOrder and Ownership128, twice per
sampling/observer configuration. All 36 have identical expected verdicts and
same-configuration semantic artifact bytes. Observer-plus-explanation settings
preserve primary diagnostic sequence and final LLVM. Accounting ends with zero
live budget units and retired evidence. These paired observations qualify this
repaired subset, not an upper bound for the entire compiler or a performance
guarantee. Direct snapshot/effect kernels, separate native-tool costs and the
global corpus/budget gate remain open; no native pilot has been evaluated.
