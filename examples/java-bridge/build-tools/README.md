<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->

# Local Maven and Gradle workflows

These examples build the existing value example through the public producer,
package standard Maven coordinates and IDE companions, then consume the ordinary
dependency in a fresh JVM. No Ironwood-specific build-tool plugin is required.
Use the producer's JDK 21 to 25, LLVM 23 and host SDK prerequisites, with `ironwoodc`
on PATH. `IRONWOODC` can select an absolute executable path. The consumer needs
only its build tool, a supported Java JDK and the dependency repository.
IDK distributions include these build files and their native prerequisites:
Linux bridge support is bundled, while macOS selects the installed Apple SDK
and linker. Source/host installations must select LLVM and prepare Linux support
as described in [producer usage](../../../docs/JAVA_BRIDGE_USAGE.md).

Run from the checkout or extracted IDK root, selecting a supported producer JDK
as `JAVA_HOME`:

```sh
export PATH="$JAVA_HOME/bin:$PWD/bin:$PATH"
mvn -f examples/java-bridge/build-tools/maven-producer/pom.xml clean install
mvn -f examples/java-bridge/build-tools/maven-consumer/pom.xml clean compile exec:exec

gradle --no-daemon -p examples/java-bridge/build-tools/gradle-producer clean publishToMavenLocal
gradle --no-daemon -p examples/java-bridge/build-tools/gradle-consumer clean run
```

Use `-Dmaven.repo.local=/absolute/local-repository` on both producer and consumer
to isolate the local repository, including for Gradle's `mavenLocal()`. Maven's
workflow project is a POM that invokes the producer at `package` and installs
the generated jar/POM/companions at `install`. Gradle uses an `Exec` task and a
standard Maven publication. Its generated POM declares the same coordinates;
the paired jar and companion bytes are unchanged. The examples configure no
remote publishing destination. Maven 3.8.6 and Gradle 8.14.3 are the locally
tested build-tool versions, not extra Java consumer runtime requirements.

Both workflows use `org.ironwood.example:ironwood-values:0.1.0-local`. Their
four program output lines must be:

```text
42
copied: bridge
caught: example failure
continued: 42
```

Build outputs stay in each project's ignored `target/`. Run `clean` before
another producer invocation: companion packaging refuses an existing output
directory. The producer does not skip work using incomplete native input caches.
Keep coordinates/version and producing basename consistent across hosts. For a
previously assembled multi-target value jar, set `BRIDGE_PAIRED_JAR` to its
absolute path before the producer command; it packages those exact bytes without
native recompilation. This setting requires a value-example jar with the same
consumer API, not an arbitrary artifact relabeled as this example.

Sources and Javadoc use standard `sources` and `javadoc` classifiers, so ordinary
IDE dependency resolution applies. The main jar retains all native support,
notices and covered source. Do not strip native resources, combine generations,
shade generated classes, or independently replace a companion's generated API.
See [producer usage](../../../docs/JAVA_BRIDGE_USAGE.md) for the complete pairing
and deployment contracts. These local workflows do not qualify a release.

The integration uses Maven's documented
[install-file parameters](https://maven.apache.org/plugins/maven-install-plugin/install-file-mojo.html)
and [external process execution](https://www.mojohaus.org/exec-maven-plugin/exec-mojo.html),
and Gradle's [Maven publication support](https://docs.gradle.org/current/userguide/publishing_maven.html).
