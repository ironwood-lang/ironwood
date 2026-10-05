<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Constructor field-alias rollback correction

Status: implemented and qualified on the M5/macOS arm64 profile, 2026-10-05.
This closes the safety blocker recorded after aa98ace2. It completes D252's
rollback confinement; it does not complete M1 or establish S1/G1. See D253 and
the [pre-change review](PRE_CHANGE.md#constructor-field-alias-rollback-correction).

## Defect

The qualified D252 compiler (`ee7211eb...67cc`) admitted the root probe, in
which `Parent` stores `this` in a private self field and passes that field to
a helper that publishes it and throws. Automatic rollback then reclaims the
published parent. Investigation showed a wider defect in the mandatory typed
publication check, not only in D252's argument walk:

- constructor stores into the receiver's own fields were exempt, but a later
  load of such a field carried no origin;
- the exemption accepted any store target that only may be the receiver;
- what a helper constructor stored was lost, so publishing the helper or a
  helper method publishing held contents was invisible;
- destructors could resurrect `this` through a self field or a caught
  exception.

Every probe is compile-only for the D252 compiler. Unsafe programs it linked
during reconstruction were deleted unexecuted.

## Correction

[ReceiverPublicationAnalysis](../../../compiler/src/main/java/ironwood/compiler/semantic/ReceiverPublicationAnalysis.java)
runs in the final effect validation. Each value carries three facts: the
parameters it may be, the parameter identities it may reach through fields,
and the parameters whose contents it may reach. Only a constructor store into
exactly its receiver, or into an element of that receiver's compiler-proven
owned array, is retention. Closed-world field marks recover what such fields
return. Constructor summaries expose retained parameters to the code that
builds the object. Values thrown to a local handler reach its landing pads.
The existing effect kernel, its projections and its reclamation facts are
unchanged, so relowering, Bridge proofs and the M0 effect workload keep their
inputs. A constructor or destructor is rejected when either analysis finds
publication of its receiver, producing one diagnostic per callable.

## Evidence

Producing compiler `a7e1477f...c4c6` was built from aa98ace2 plus only this
increment; the paused keyed draft was excluded. The
[ledger](field-alias-evidence/manifest.json) binds sources, tools, commands
and retained logs.

| Check | Result |
| --- | --- |
| [Probe matrix](field-alias-evidence/probes/) | 14 unsafe probes rejected for publication in off/warn/error; 10 were admitted or rejected only for other reasons by D252 |
| Safe counterparts | Encapsulated self field, confined helper reached through it, self-retaining collections, owned child array and dropped caught `this` compile in every mode, link at `-O3` and exit identically with both compilers |
| Reconstruction | An app built against a confined helper links and exits 42; linking it with a publishing helper from classes or an archive is rejected in every mode (D252 admitted all six) |
| Native fixture | `constructor_field_alias_rollback.iron`: 20 normal and failed attempts, three allocations each, live baseline restored, exit 42 in every mode; LLVM byte-identical to D252 (`231a2572...8965`) |
| Focused tests | 18 passed, including the four new tests, the D252 suite and copy/snapshot/pool/Bridge consumers |
| Consumers | Strict library and testing library build under `--unfreed=error`; all 79 example/project trees compile identically with both compilers |
| Cost | Strict library compile 6.60-6.84 s before and 6.67-7.04 s after, three runs each |
| Hygiene | `git diff --check` and `./scripts/check-licenses.sh` pass |

## Limits

The analysis is conservative. An element store into an array that is not an
owned receiver array still publishes the stored value. Two retained `limit-*`
probes remain rejected by the unchanged escape summaries, which keep a parent
unfreeable after a local holder or a helper built in an instance method holds
it. Those are not publication diagnostics and need no change for M1. No runtime
bookkeeping or lowering change was introduced.
