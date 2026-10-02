<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge Java 25 product experiment

> **Status, 2026-10-02:** D245 admits Java 24 and 25 to the ordinary producer
> and loader under the JDK's native-access policy. The experiment below is the
> record that informed that decision; its "ordinary" artifacts refuse Java 24/25
> because they predate D245. The [D245 qualification](#d245-admission-qualification)
> section at the end records what was run after admission.

D209's bounded value experiment works on pinned Temurin 25.0.4.1+1 on macOS
ARM64, with visible native-access warnings. At that revision the ordinary
producer admitted only Java 21-23. The maintainer authorized retaining that
baseline for that implementation run, keeping the qualification matrix bounded
while broader support remained for later review. No consumer bypass was added.

## Matched inputs and outcome

The experiment used Apple M5 hardware, macOS 26.6.2, LLVM 23.1.0 and the Java 21
producer at checkpoint `8e9b0373`. The ordinary compiler content identity was
`66fc51e91cfd022f472c61afe02e6e021495c3cc1087c545f10d20cd03f7d5bf`.
The runner copied that complete compiler into its private evidence directory,
changed only the version predicate/diagnostic and supported-version metadata,
then compiled those changes with Java 21. Its actual inventory became
`eaf8ac1c11296fb93912ab26b802e0099d52a0d6da3a0076cfe1789a315daeeb`.
The resulting generations and native build identities differ while source
program, logical API and runtime identities agree. Both paths use the same
production native implementation and all ordinary pairing checks.

Evidence is in `workspace/java-bridge/evidence/p2/version-policy-3/`.
Each jar contains its complete pairing/content inventory. `result.json` records
all 36 child commands' results, and `identities.json` records compiler classes,
consumer/source, jars and final/extracted image hashes. Full commands, stdout,
stderr, exit statuses, JDK preflight, host, signature, dependency, attributes and
deny-child file mappings remain alongside those artifacts.

| Cases at both O0 and O3 | Child runs | Result |
| --- | ---: | --- |
| Ordinary Java 21, three launch forms | 6 | Values, initialization, checked exceptions and repeated calls pass without warnings |
| Ordinary Java 24/25, three launch forms | 12 | Refused before extraction or native loading; no native-access warnings |
| Experimental Java 25, default policy, three launch forms | 6 | Functional cases pass with native-access warnings |
| Experimental Java 25, separate checked-JNI launches | 6 | Same functional cases pass; native-access warnings only, no observed JNI misuse |
| Experimental Java 25, explicit deny, three launch forms | 6 | JVM rejects native access; extracted image is absent from the child's mapped files |

Functional assertions cover scalar carriers, stored initialization, UTF-16
copied/fresh/alias/null Strings, declared IOException catches/messages/native
frames, stored initializer failures and repeated successful calls afterward.
An initial Java class-initialization failure naturally produces
NoClassDefFoundError on a second facade use; refusal controls assert that JVM
behavior without expecting the first error type to repeat.

Final ad-hoc native signatures remain valid after automatic extraction, with
identical payload bytes. Pinned launchers are unchanged and their signatures and
entitlements are recorded. No native-access flag, manifest grant or injected JVM
option is present in the default launches. The experimental predicate admits
exactly Java 25; it does not admit Java 24 or arbitrary future versions.

| Payload | Final SHA-256 |
| --- | --- |
| O0 ordinary | `d15b10534d38f56c588058c53a863568251d33815b4181c7bd7c9f45de1dd37d` |
| O0 experimental | `39f6342db52a053f0778507d5d6e3905ab839fa8c57095cc4fae9e1cc25aa0b6` |
| O3 ordinary | `1ce6d48c97562073ba3f5cad1699f52273f224389a7a15e5ae4a4d44158a8d7d` |
| O3 experimental | `5e2101eb32fe93785e12511d532191040081e80b2f6c926346b0229c7047a075` |

## Reproduction and interpretation

Build the compiler with the pinned Java 21 JDK. Follow the preparation and run
commands in [the validation README](../scripts/java-bridge/README.md#java-version-policy-experiment).
Use a new evidence directory. The runner never downloads tools, never patches
the working compiler and refuses injected JVM options. Provisioning is an
explicit preceding action with checked-in version/checksum pins.

The first fixture attempted console output from its native initializer and was
correctly rejected for unclassified stream effects in the String-result proof.
The final fixture uses stored initialization and an independent deny-child
mapping inspection. No compiler proof was weakened to run the experiment.

Java 25's warning identifies System.load and the caller's unnamed or automatic
module, suggests a native-access grant, and warns of future denial. Operation
therefore requires no consumer wiring under this tested default policy, but it
does not meet a warning-free interpretation of transparency. Explicit denial is
a deployment-policy failure, not an exception-translation or JNI correctness
failure.

For later product review, warning-based Java 25 admission is a viable candidate
if that visible warning is acceptable. It still requires an explicit support
decision and the full object/lifetime, OrderBook, platform and performance
qualification. This value probe does not establish those properties, every JVM
vendor, Java 24, or future JVM policies. The current run retains Java 21-23 and
the existing nine P6 matrix cells; release readiness remains unqualified.

## D245 admission qualification

On 2026-10-02 the ordinary producer, loader and generation metadata admitted
Java 24 and 25 (D245). The version predicates admit exactly 21-25; `java.supported`
is `21,22,23,24,25`, and every generation identity changed with it. The runs below
used the modified compiler at that revision; they are the review basis for the
maintainer's Java 25 measurements, not a release qualification.

### Launch policy on Java 24 and 25

With no option, both releases print the JDK's warning once and then load. The
exact text seen on pinned Temurin 25.0.4.1+1 (identical on 24.0.2+12) for a
class-path launch of the object producer fixture was:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by ironwood.bridge.generated.g<generation>.Support in an unnamed module (file:<path>/objects.jar)
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

`--enable-native-access=ALL-UNNAMED` (class path), `--enable-native-access=<Automatic-Module-Name>`
(module path) and the `Enable-Native-Access: ALL-UNNAMED` manifest attribute of
an executable jar each produced exactly the expected output with no warning.
`--illegal-native-access=deny` failed in the facade's class initializer with
`ExceptionInInitializerError` caused by `IllegalCallerException: Illegal native
access from an unnamed module (file:...)`, repeated as `NoClassDefFoundError`,
with the extracted image present but never mapped. `-Dironwood.bridge.calls=jni`
kept JNI and `-Xcheck:jni` reported nothing. Native access enabled only for other
modules (`--enable-native-access=java.base`) linked the critical handles with the
same warning on 24/25, where Java 21-23 keep the silent JNI fallback.

### Runs

| Host and JDK | Role | Checks | Result |
| --- | --- | --- | --- |
| macOS ARM64, pinned Temurin 21.0.12.1+1 | producer and runner | JDK tools, loader, identities, critical calls (3), object/root/retaining producers with pinned 22-25 consumer replays, enum facades, permanent facades, private enum entries (2), OrderBook producer, OrderBook allocation, assembly, colliding generations | 21 focused tests passed |
| macOS ARM64, pinned Temurin 24.0.2+12 | producer and runner | JDK tools, loader, identities, critical calls (3), object producer, enum facades, permanent facades, private enum entries (2) | 11 passed |
| macOS ARM64, pinned Temurin 25.0.4.1+1 | producer and runner | the 11 above plus root and retaining producers | 13 passed |
| macOS ARM64, `check-java-version-policy.py` | Java 21 producer, Java 21/24/25 launchers | O0/O3 version probe: default, checked JNI, granted and denied launches on class path, module path and executable jar | 54 child launches passed; evidence in `workspace/java-bridge/evidence/d245/version-policy-1/` |
| Linux x86-64, Oracle JDK 25.0.4.1 | producer and runner | JDK tools, loader, identities, critical calls (3), enum facades, permanent facades, private enum entries (2), object/root/retaining producers, OrderBook producer, OrderBook allocation, assembly | 16 passed |
| Linux x86-64, Oracle GraalVM 25.0.4 | producer and runner | JDK tools, critical calls (3), object producer, OrderBook producer | 6 passed |

### OrderBook workflow on Linux x86-64 with Oracle JDK 25

Single observations from `projects/OrderBook/java-bridge` on the Estonia host
with `JAVA_HOME=/usr/java/jdk-25.0.4.1`, recorded for the maintainer's later
measurement. They are not benchmark results and `BENCHMARK.md` is unchanged.

| Step | Observation |
| --- | --- |
| `compile.sh`, `link.sh` | Built; critical calls selected for 25 of 35 native bindings |
| `test.sh` (Oracle JDK 25 and Oracle GraalVM 25) | `PASS: Java Bridge demo, throughput, latency and invalid-argument checks`, after the override launch gained the grant that silences the Java 24/25 load warning |
| `run.sh` (both JVMs) | The documented snapshots, ending `true`, `true`, `4`, `170`, `2` |
| `throughput.sh 8 80` | 1092431436 ns for 80M measured operations after 8M warm-up (one run) |
| `latency.sh 10000 50000 1000` | Average batch 108.960 us (1000 cycles, 8000 operations), minimum 107.596 us, 99% maximum 138.685 us, 99.999% maximum 191.589 us (one run) |
| Default-policy launch without a grant | The four-line JEP 472 warning quoted above, then the normal output |
| `--illegal-native-access=deny` launch | `ExceptionInInitializerError` at `Main.main` caused by `IllegalCallerException: Illegal native access from an unnamed module (file:.../orderbook.jar)`; exit status 1, no native code ran |

### Not verified

Linux ARM64 on Java 24/25, Temurin 24/25 on Linux (no pins are provisioned),
JVM vendors other than Temurin, Oracle JDK and Oracle GraalVM, and the IDK,
Maven/Gradle and full nine-cell qualification workflows on the new releases.
