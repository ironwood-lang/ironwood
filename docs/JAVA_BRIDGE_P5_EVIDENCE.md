<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# P5 callback implementation and evidence

Production revision: `e0643c05e70c1a2f4c70c48edce4dcdf5da3488e` on local
`java-bridge`. Implementation and focused qualification are complete for the
bounded synchronous callback contract on all three targets. Numerical
performance acceptance remains maintainer review. P7 is deferred.

## Delivered behavior

- Generated Java listener interfaces and compiler-visible foreign calls with
  conservative effects, protected native entries and explicit nested contexts.
- Borrowed listeners and retained registrations, including replacement/removal,
  reentrant changes and precise global-reference cleanup after suspended uses.
- Active-owner free refusal, stable final-owner facade arguments and balanced
  conversion references. Copied String inputs survive allocating, reentrant or
  throwing Java callbacks without critical JNI regions.
- Original Java throwable identity for unchanged failures. D227 keeps retained
  or uncertain carriers alive for the process lifetime; strict per-entry proofs
  reclaim only nonescaping carriers. D228 preserves native cause/secondary
  additions in wrappers while leaving the Java original unchanged.
- Public source/class/archive producer parity, ordinary class/module consumers,
  and a separate [listener example](../examples/java-bridge/listeners/README.md).
  Official OrderBook source and benchmark definitions are unchanged.

See the [producer guide](JAVA_BRIDGE_USAGE.md) for the supported boundary and
[progress log](JAVA_BRIDGE_P5_PROGRESS.md) for individual checkpoints and failures
corrected during implementation. Native owners remain exact final classes with
primitive/listener fields. Interface inheritance/default/static methods,
reference callback results, other reference arguments, asynchronous callbacks,
independent-root mutation during callbacks and arbitrary escaping owner graphs
remain producer errors. Native exception storage/graph admission is bounded to
built-in Throwable operations. Calls remain confined to one invoking thread by
caller contract. Java 21-23 remains the baseline; Java 24+ is still refused.

## Final verification

Pinned Eclipse Temurin HotSpot 21.0.12.1+1, 22.0.2+9 and 23.0.2+7; Java 21
bootstrap and LLVM 23.1.0. The final compiler JAR's classes match the tested class
directory. No unfiltered compiler suite was run.

| Target | Execution scope | Focused fixtures | Checked-JNI child replays across 3 JDKs | Owner stack cells | Stateless stack cells |
| --- | --- | ---: | ---: | ---: | ---: |
| macOS ARM64 | Apple M5 hardware | 4 pass | 141 pass | 6 pass | 6 pass |
| Linux ARM64 | Colima on Apple M5 | 4 pass | 141 pass | 6 pass | 6 pass |
| Linux x86-64 | Physical Estonia Xeon E-2288G | 4 pass | 141 pass | 6 pass | 6 pass |

Each stack matrix covers O0/O3 and Java 21/22/23, native callback depths
1/8/32/64 from Java depths 0/64, deepest failure identity and continued use.
Owner cells additionally cover retained listeners, two owner arguments, live
String copies and active-free refusal. Reduced-stack probes run in separate
children. All cells observe successful depth 128 then Java
StackOverflowError at 256 with `-Xss512k`, and successful 256 then failure at 512
with `-Xss1m`, except stateless x86-64 O3, which succeeds at 512 and reports
StackOverflowError at 1024 on all three JDKs. These are diagnostics, not supported
maximum depths or an arbitrary stack-overflow recovery guarantee.

The fixture selection covers native/JNI/Java graph allocation failures, nested
exceptions, catch/replace/retain, modified and cyclic graphs, suppression-disabled
originals, partial String/facade preparation, registration failure, owner aliases
and destruction. Mandatory unsafe-free/publication refusals pass in every
`--unfreed` mode. Cache reconstruction is exercised through deterministic test
cache eviction; that test is not evidence of garbage collection.

Eight focused shared-proof controls also pass, including fresh rollback
attribution, destructor/initialization retention, carrier lifetime and actual
OrderBook unpublished rollback. The new matcher admits protected allocation and
non-publishing argument conversions, while rejecting intervening publication,
unknown calls and alternate incoming edges. Source reclamation is unchanged.

The final primitive owner `twice` adapter has identical O3 instructions and
relocations to the preceding checkpoint on all three targets. Its ARM64 frame
remains 368 bytes; the x86-64 adapter reserves 312 bytes plus six saved registers.
Primitive dispatch gains no identity lookup or runtime lifetime-policy flag.
License audit and `git diff --check` pass. Public producer tests verify package
content/native digests and source/Javadoc presence.

## Linux listener measurements

This is the separate result-processor/listener workload, not an OrderBook rerun.
Each event applies the same state transition and checksum in all three scenarios.
Native/Java means a Java application calls the generated native processor, which
invokes its Java listener once per event. Native/native and Java/Java keep both
parts in their respective execution environment.

Three fresh process forks, five warmup batches and seven measured batches per
fork; one million events per batch. Values below are pooled median amortized
nanoseconds per event, the complete observed range, and reciprocal throughput
in millions of events/second. They are not individual-event latency percentiles.
Every batch's count, checksum and last value is independently checked. Timing
runs omit checked JNI. Registration, initialization and measurement setup are
outside timing.

Estonia uses Docker CPU 1 from the host's isolated set; its existing `powersave`
governor remains unchanged. Linux ARM64 uses Docker virtual CPU 1 in the six-vCPU,
8 GiB Colima VM. Hosts, commands, versions and raw samples accompany the results.
Runs use existing pinned images and SDKs, with read-only roots and no network.
No packages or infrastructure were installed. The new host work stays under the
authorized validation directory; RAM-backed container scratch and streamed
archives avoid Estonia's nearly full disk.

### Linux x86-64 hardware

| Scenario | JVM | ns/event | Observed range | Million events/s |
| --- | --- | ---: | ---: | ---: |
| Native processor / native listener | None | 1.247 | 1.226-1.273 | 801.730 |
| Java processor / Java listener | 21 | 1.255 | 1.235-1.338 | 796.865 |
| Native processor / Java listener | 21 | 104.824 | 103.080-105.699 | 9.540 |
| Java processor / Java listener | 22 | 1.255 | 1.235-1.287 | 796.842 |
| Native processor / Java listener | 22 | 104.745 | 104.123-106.322 | 9.547 |
| Java processor / Java listener | 23 | 1.255 | 1.235-1.317 | 796.923 |
| Native processor / Java listener | 23 | 110.161 | 108.789-111.383 | 9.078 |

### Linux ARM64 virtualization

| Scenario | JVM | ns/event | Observed range | Million events/s |
| --- | --- | ---: | ---: | ---: |
| Native processor / native listener | None | 1.330 | 1.316-1.404 | 752.115 |
| Java processor / Java listener | 21 | 1.333 | 1.318-1.389 | 750.000 |
| Native processor / Java listener | 21 | 47.933 | 47.574-48.436 | 20.862 |
| Java processor / Java listener | 22 | 1.402 | 1.321-1.963 | 713.204 |
| Native processor / Java listener | 22 | 47.920 | 47.793-48.141 | 20.868 |
| Java processor / Java listener | 23 | 1.359 | 1.317-1.481 | 735.858 |
| Native processor / Java listener | 23 | 47.950 | 47.790-48.323 | 20.855 |

Standalone native measured loops allocate zero native objects. Both JVM scenarios
report zero measured Java allocated bytes. The benchmark does not expose a native
allocation counter through the production bridge; its native allocation behavior
is checked separately by the instrumented fixtures. Do not interpret unavailable
counters as zero. These measurements do not establish an application speedup.

## Reproduction and artifact identities

Use the commands and prerequisites in the [listener example](../examples/java-bridge/listeners/README.md)
and [runner guide](../scripts/java-bridge/README.md). `replay-listeners.py` preserves
successful fixture commands, allocation-failure settings and input hashes;
`check-stack.py --callbacks` and `--owned-callbacks` run the two stack matrices.
`measure-listeners.py` records three-way raw samples, allocation fields and
native disassembly. Use fresh evidence directories; preserve previous results.

Full source/class/JAR payload and exact Docker/SSH commands:
`workspace/java-bridge/p5-linux-e0643c05/`. Input archive SHA-256:
`052f06ad2f606f78fa0e3477c2338642be7b7f6f459a1ceae5308b2b6eff93c0`.
The separate stateless stack bundle is under `p5-stateless-e0643c05/`, SHA-256
`4bcf19af63a5678297d22a2f9c02bd1b08a10c0a5eb535c815f09188dbddd553`.
Its input manifest differs only in `validation/run.sh`; production inputs match.
All collected files are verified against their complete archive manifests.

Mac evidence is under `workspace/java-bridge/evidence/p5/`:
`replay-mac-e0643c05/`, `owner-stack-mac-e0643c05/` and
`stateless-stack-mac-e0643c05/`. Linux archive directories contain complete
artifacts plus `collected/` indexes with commands, logs, identities and results.
The preceding `ddf48b13` run is historical evidence; its incorrect M2 host note
is corrected in the progress log. The physical Mac is Apple M5, model Mac17,2.

Common measured generation and source identities:

| Identity | SHA-256 |
| --- | --- |
| generation | `10ae22f52f9d32174439849d7cc0db896192ab754b737ad37a5603a08af7a6c1` |
| program | `77d9afe55ebf01481c3c8474d82fba520bec9cd01bd6d267556361bb5bd09c4c` |
| api | `f1af7d82e68b1b14321dacd18bc63bea8752f593b4c8641ce8436db6ace5dbeb` |
| compiler.sha256 | `7db78de499f7d76d3c41f513f7740874596c0cebc213563d6fc67f5d96a1bd8c` |
| runtime.sha256 | `eff7bd01439b5f44bf5b762b4a545ad92d7b145385c0710e433b4afd24d68ef4` |

Measured production artifacts:

| Target | Artifact | SHA-256 |
| --- | --- | --- |
| Linux x86_64 | Paired JAR | `1b22fc06219c17b80695c652422733d129c7b71f2232d50a2dc2517b64fb6517` |
| Linux x86_64 | Native payload | `5e41b317e695397bd87b7d3ec9bcbabcca805f295e619fe6e26fa5429b56cbf6` |
| Linux x86_64 | Standalone executable | `d20424d89a74838ba1c96ec7479a4ada719f14c4f309edeff667fd532622a768` |
| Linux arm64 | Paired JAR | `739c743aa9e7d9f5100e2f5085564421422b67342eff33afeb9528ffe8a7098d` |
| Linux arm64 | Native payload | `93eba014589bfc3be8d773301dbc4e3488f6564306b03834501903e8cec0e21d` |
| Linux arm64 | Standalone executable | `1cf727fff7cc5da339de22e2b7b62e8f6cc664c0a206d42d9941076eba517cb9` |

Verified complete evidence archives:

| Archive | Files | SHA-256 |
| --- | ---: | --- |
| p5-linux-e0643c05/arm64-fixtures | 1994 | `1438d762f14f043d09328a27923272d16f786004aa3c3162db43dff9d6189a37` |
| p5-linux-e0643c05/arm64-qualification | 330 | `f5359e24c7f33c65bbf54c895bb85fe034844413560fd99a6f00353b0d4e3f73` |
| p5-linux-e0643c05/x86_64-fixtures | 1994 | `d17415d6a3cd3f4563bc9faf0a250961231d682caccf850a92c4bac0f8164052` |
| p5-linux-e0643c05/x86_64-qualification | 330 | `f9566241bc2609a75e1dc15a33f7aa658528f09b0e32c573e16a4e64c4a24bfb` |
| p5-stateless-e0643c05/arm64-qualification | 197 | `40feac4c5861a258c464bfcad38a27dd5db43523c74be972668dd22e4abf4ede` |
| p5-stateless-e0643c05/x86_64-qualification | 206 | `dfcbc7699100538f1a0f3efee1bfb4c2194bb4e6e4df0904768995e86b783afc` |
