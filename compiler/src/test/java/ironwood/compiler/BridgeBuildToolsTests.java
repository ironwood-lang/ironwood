// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class BridgeBuildToolsTests {
    static final String NAME = "Java Bridge JDK tools preserve Java 21 APIs and reject unsupported versions";
    private BridgeBuildToolsTests() {}

    static void tools() throws Exception {
        for (int feature : List.of(21, 22, 23)) check(BridgeBuildTools.supportsJdk(feature), "supported producer refused");
        for (int feature : List.of(8, 17, 20, 24, 25, 99)) check(!BridgeBuildTools.supportsJdk(feature), "unsupported producer admitted");
        check(BridgeBuildTools.requireJdk("test").equals(Path.of(System.getProperty("java.home"))), "tools use another JDK");
        Path base = Path.of("workspace/java-bridge/evidence/jdk-tools").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        String originalHome = System.getProperty("java.home");
        boolean macos = System.getProperty("os.name").equals("Mac OS X");
        BridgeBuildTools.requireJniHeaders(macos);
        try {
            System.setProperty("java.home", directory.toString());
            for (String missing : List.of("jni.h", "jni_md.h")) {
                try {
                    BridgeBuildTools.requireJniHeaders(macos);
                    throw new AssertionError("missing JNI header admitted");
                } catch (IOException expected) {
                    check(expected.getMessage().contains("selected JDK's JNI header")
                            && expected.getMessage().endsWith(missing), "unclear JNI header diagnostic");
                }
                Files.createDirectories(directory.resolve("include"));
                Files.writeString(directory.resolve("include/jni.h"), "test header");
            }
        } finally {
            System.setProperty("java.home", originalHome);
        }
        Path source = directory.resolve("Baseline.java"), classes = directory.resolve("classes"); Files.createDirectories(classes);
        Files.writeString(source, "public final class Baseline { private Baseline() {} public static String value() { return \"21\"; } }\n");
        var output = new ByteArrayOutputStream(); var diagnostics = new PrintStream(output);
        BridgeBuildTools.java(List.of(source), classes, diagnostics);
        byte[] bytes = Files.readAllBytes(classes.resolve("Baseline.class"));
        check(bytes[6] == 0 && bytes[7] == 65, "class target exceeds Java 21");
        BridgeBuildTools.javadoc(List.of(source), classes, directory.resolve("docs"), diagnostics);
        check(Files.readString(directory.resolve("docs/Baseline.html")).contains("value()"), "missing API documentation");
        Files.writeString(source, "public final class Baseline { public java.lang.foreign.MemorySegment value; }\n");
        try {
            BridgeBuildTools.java(List.of(source), classes, diagnostics);
            throw new AssertionError("Java 22 API admitted into the Java 21 facade");
        } catch (IOException expected) {
            check(expected.getMessage().contains("compilation failed"), "unexpected API refusal");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
