<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Java Bridge Java 25 product experiment

D209's bounded value experiment works on pinned Temurin 25.0.4.1+1 on macOS
ARM64, with visible native-access warnings. The ordinary producer continues to
admit only Java 21-23. The maintainer authorized retaining that baseline for this
implementation run, keeping the qualification matrix bounded while broader
support remains for later review. No consumer bypass was added.

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
