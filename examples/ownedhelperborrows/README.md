<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Managed helper aliases and pool reuse

The numbered compile/run scripts remain focused consumer inputs. Maintained
programs use ordinary managed references and explicit pool release/reuse. A live
iterator, view or copied result keeps its referenced graph reachable. Closing
resources and returning pooled items remain separate operations.

The nine files under src/invalid are intentional migration-negative fixtures:
they preserve removed source reclamation syntax for useful diagnostics. Their
old filenames describe historical scenarios, not current managed alias rules.
Equivalent shared/aliased/cyclic graphs succeed in the GC7 consumer families and
integration-tests/cases/gc7_shared_graphs.iron; source/class/archive reconstruction
and deliberate phase reclamation are checked separately.

Run each numbered compile script before its matching run script. Positive
programs expect exit 42; invalid scripts expect a compiler migration error.
The constructor-publication and inserted-element positive programs explicitly
check survival across collection. No self-hosting or performance acceptance is
part of these examples. See [the ledger](../../docs/GC7_DISPOSITION.md).
