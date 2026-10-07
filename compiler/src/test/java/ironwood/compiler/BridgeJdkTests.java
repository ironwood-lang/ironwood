// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * M6.2's JDK selection for the native Bridge producer (D295): the port's
 * JdkSelection against scripts/jdk.sh's selection, each JDK's own
 * properties and BridgeBuildTools' gates; javac and javadoc through it
 * against the in-process tools; its ownership controls; and an allocation
 * sweep that leaves no log behind.
 */
final class BridgeJdkTests {
    private static final String PORT = PortFixtures.PORT;
    private static final List<String> SOURCES = List.of(PORT + "JdkSelection.iron", PORT + "Command.iron",
            PORT + "ExecutableSearch.iron", PORT + "TextList.iron");
    private static final boolean MACOS = System.getProperty("os.name").startsWith("Mac");
    // A JVM startup warning, which a Linux arm64 guest without SVE prints at every start (00f5554f).
    private static final Pattern JVM_WARNING = Pattern.compile("(?m)^[^\n]* VM warning: [^\n]*\n");

    private BridgeJdkTests() { }

    record Outcome(int exit, String output, String error) { }

    // Runs a command with JAVA_HOME and PATH as given (null removes JAVA_HOME).
    static Outcome run(List<String> command, Path directory, String javaHome, String path) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        Map<String, String> variables = builder.environment();
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "IRONWOOD_ALLOCATION_LIMIT")) {
            variables.remove(variable);
        }
        if (javaHome == null) variables.remove("JAVA_HOME");
        else variables.put("JAVA_HOME", javaHome);
        variables.put("PATH", path);
        Process process = builder.start();
        var error = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try {
                process.getErrorStream().transferTo(error);
            } catch (IOException ignored) {
                // The output is incomplete; the comparison reports it.
            }
        });
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(600, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(command.getFirst() + " timed out");
        }
        drain.join();
        return new Outcome(process.exitValue(), output, error.toString(StandardCharsets.UTF_8));
    }

    static String hex(String text) {
        return BridgeJarTests.hex(text);
    }

    static Path script(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    /**
     * A JDK double: bin/java prints a property listing after a JVM warning;
     * bin/javac, bin/javadoc and the JNI headers exist unless omitted.
     */
    static Path fakeJdk(Path root, String name, String specification, String vendor, int exit, List<String> omit)
            throws IOException {
        Path home = root.resolve(name);
        StringBuilder listing = new StringBuilder("OpenJDK 64-Bit Server VM warning: a startup warning\nProperty settings:\n");
        listing.append("    java.home = ").append(home).append('\n');
        listing.append("    java.runtime.version = ").append(specification).append(".0.1+1-fake\n");
        listing.append("    java.specification.version = ").append(specification).append('\n');
        if (vendor != null) listing.append("    java.vendor = ").append(vendor).append('\n');
        listing.append("    java.vendor.url = https://example.invalid/\n    java.library.path = /a\n        /b\n");
        script(home.resolve("bin/java"), "#!/bin/sh\ncat >&2 <<'END'\n" + listing + "END\necho 'fake version' >&2\nexit "
                + exit + "\n");
        for (String tool : List.of("bin/javac", "bin/javadoc")) {
            if (!omit.contains(tool)) script(home.resolve(tool), "#!/bin/sh\nexit 0\n");
        }
        for (String header : List.of("include/jni.h", "include/darwin/jni_md.h", "include/linux/jni_md.h")) {
            if (!omit.contains(header)) {
                Files.createDirectories(home.resolve(header).getParent());
                Files.writeString(home.resolve(header), "/* " + header + " */\n");
            }
        }
        return home;
    }

    // The selected JDK's own values, read by a program it runs, or by its
    // property listing for a JDK that cannot launch source files.
    static Map<String, String> ownValues(Path home, Path root) throws Exception {
        var values = new LinkedHashMap<String, String>();
        Path probe = root.resolve("PropertyProbe.java");
        if (!Files.exists(probe)) {
            Files.writeString(probe, "public class PropertyProbe { public static void main(String[] a) {"
                    + " for (String k : new String[]{\"java.home\", \"java.specification.version\", \"java.runtime.version\","
                    + " \"java.vendor\"}) System.out.println(k + \"=\" + System.getProperty(k)); } }");
        }
        Outcome outcome = run(List.of(home.resolve("bin/java").toString(), probe.toString()), null, null, "/usr/bin:/bin");
        if (outcome.exit() != 0) return null;
        for (String line : outcome.output().split("\n")) {
            int equals = line.indexOf('=');
            values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return values;
    }

    // scripts/jdk.sh's selection with the same environment and directory: its JAVA_HOME, or its error.
    static Outcome script(Path root, String javaHome, String path, Path directory) throws Exception {
        String jdkScript = Path.of("scripts/jdk.sh").toAbsolutePath().toString();
        return run(List.of("/bin/bash", "-c", "source \"$2\"; ironwood_select_java \"$1\" && printf %s \"$JAVA_HOME\"",
                "select", root == null ? "/nonexistent-root" : root.toString(), jdkScript), directory, javaHome, path);
    }

    static int feature(String specification) {
        return specification.matches("[0-9]{1,9}") ? Integer.parseInt(specification) : -1;
    }

    // What the native fixture prints for a selected JDK with these values.
    static String expected(Map<String, String> values) {
        String home = values.get("java.home");
        String version = values.get("java.runtime.version");
        int feature = feature(values.get("java.specification.version"));
        StringBuilder text = new StringBuilder();
        text.append("home ").append(home).append('\n').append("feature ").append(feature).append('\n');
        text.append("version ").append(version).append('\n').append("vendor ").append(values.get("java.vendor")).append('\n');
        String producer = gate("producer", feature, version, home);
        text.append(producer == null ? "producer ok\n" : "producer error " + hex(producer) + "\n");
        String headers = producer;
        if (headers == null) {
            for (String header : List.of("include/jni.h", MACOS ? "include/darwin/jni_md.h" : "include/linux/jni_md.h")) {
                Path file = Path.of(home).resolve(header);
                if (headers == null && (!Files.isRegularFile(file) || !Files.isReadable(file))) {
                    headers = "Java Bridge producer requires the selected JDK's JNI header: " + file;
                }
            }
        }
        if (headers == null) headers = gate("Javadoc generation", feature, version, home);
        if (headers == null && !executable(Path.of(home, "bin", "javadoc"))) {
            headers = "Java Bridge producer requires the selected JDK's Javadoc component: " + home;
        }
        text.append(headers == null ? "headers ok\n" : "headers error " + hex(headers) + "\n");
        return text.toString();
    }

    static boolean executable(Path file) {
        return Files.isRegularFile(file) && Files.isExecutable(file);
    }

    // BridgeBuildTools.requireJdk's messages.
    static String gate(String operation, int feature, String version, String home) {
        if (feature < 21 || feature > 25) {
            return "Java Bridge " + operation + " requires JDK 21, 22, 23, 24 or 25; detected " + version;
        }
        if (!executable(Path.of(home, "bin", "javac"))) {
            return "Java Bridge " + operation + " requires the selected JDK's javac component: " + home;
        }
        return null;
    }

    /**
     * JAVA_HOME, then the installation's JDK, then PATH: each case selects
     * the JDK scripts/jdk.sh selects (or fails with its message), records the
     * selected JDK's own java.home, java.runtime.version and java.vendor, and
     * gives BridgeBuildTools' gate messages. Real JDKs listed in
     * IRONWOOD_TEST_JDKS join the test JVM's.
     */
    static void selection() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jdk-").toRealPath();
        try {
            Path program = PortFixtures.links(root.resolve("build"), "compiler_bridge_jdk_selection", SOURCES).getFirst();
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            Path fakes = Files.createDirectories(root.resolve("fake"));
            String jdk = Path.of(System.getProperty("java.home")).toRealPath().toString();
            Path fake21 = fakeJdk(fakes, "fake21", "21", "Fake Vendor", 0, List.of());
            Path installation = Files.createDirectories(root.resolve("installation/toolchain/lib"));
            Files.createSymbolicLink(installation.resolve("jvm"), Path.of(jdk));
            installation = root.resolve("installation");
            // The shell selection needs sed and the JDK doubles need cat; neither directory holds a java.
            Path tools = Files.createDirectories(root.resolve("tools"));
            for (String tool : List.of("sed", "cat")) {
                Path found = Path.of("/usr/bin", tool);
                Files.createSymbolicLink(tools.resolve(tool), Files.exists(found) ? found : Path.of("/bin", tool));
            }
            String emptyPath = Files.createDirectories(root.resolve("no-java")) + ":" + tools;
            Path javaOnPath = Files.createDirectories(root.resolve("path-java"));
            Files.createSymbolicLink(javaOnPath.resolve("java"), fake21.resolve("bin/java"));
            String withJava = javaOnPath + ":" + tools;
            record Case(String label, Path root, String javaHome, String path, Path home) { }
            List<Case> cases = new ArrayList<>(List.of(
                    new Case("JAVA_HOME", null, jdk, emptyPath, Path.of(jdk)),
                    new Case("installation", installation, null, emptyPath, Path.of(jdk)),
                    new Case("installation before PATH", installation, null, withJava, Path.of(jdk)),
                    new Case("JAVA_HOME before installation", installation, fake21.toString(), emptyPath, fake21),
                    new Case("PATH", null, null, emptyPath + ":" + withJava, fake21),
                    new Case("empty JAVA_HOME", null, "", withJava, fake21),
                    new Case("relative JAVA_HOME", null, "fake/fake21", emptyPath, fake21),
                    new Case("missing JAVA_HOME", installation, root.resolve("missing").toString(), withJava, null),
                    new Case("nothing on PATH", null, null, emptyPath, null)));
            for (var variant : List.of(
                    List.<Object>of("exit1", "21", "Fake Vendor", 1, List.of()),
                    List.<Object>of("novendor", "21", "", 0, List.of()),
                    List.<Object>of("fake26", "26", "Fake Vendor", 0, List.of()),
                    List.<Object>of("fake20", "20", "Fake Vendor", 0, List.of()),
                    List.<Object>of("fake18", "1.8", "Fake Vendor", 0, List.of()),
                    List.<Object>of("nojavac", "21", "Fake Vendor", 0, List.of("bin/javac")),
                    List.<Object>of("nojni", "21", "Fake Vendor", 0, List.of("include/jni.h")),
                    List.<Object>of("nomd", "25", "Fake Vendor", 0, List.of("include/darwin/jni_md.h", "include/linux/jni_md.h")),
                    List.<Object>of("nojavadoc", "23", "Fake Vendor", 0, List.of("bin/javadoc")))) {
                @SuppressWarnings("unchecked")
                List<String> omit = (List<String>) variant.get(4);
                String vendor = (String) variant.get(2);
                Path home = fakeJdk(fakes, (String) variant.get(0), (String) variant.get(1), vendor.isEmpty() ? null : vendor,
                        (Integer) variant.get(3), omit);
                cases.add(new Case("fake " + variant.get(0), null, home.toString(), emptyPath, home));
            }
            String extra = System.getenv("IRONWOOD_TEST_JDKS");
            if (extra != null) {
                for (String home : extra.split(":")) {
                    if (!home.isEmpty()) cases.add(new Case("JDK " + home, null, home, emptyPath, Path.of(home)));
                }
            }
            for (Case current : cases) {
                String expected;
                if (current.home() == null) {
                    Outcome reference = script(current.root(), current.javaHome(), current.path(), root);
                    String prefix = "error: ";
                    String message = reference.error().strip();
                    if (reference.exit() == 0 || !message.startsWith(prefix)) {
                        throw new AssertionError(current.label() + ": scripts/jdk.sh did not fail: " + reference);
                    }
                    expected = "error " + hex(message.substring(prefix.length())) + "\n";
                } else if (current.label().startsWith("fake ") && current.label().matches("fake (exit1|novendor)")) {
                    expected = "error " + hex("could not inspect selected Java: " + current.home().resolve("bin/java")) + "\n";
                } else {
                    Map<String, String> values = current.home().startsWith(fakes) ? fakeValues(current.home()) : ownValues(current.home(), root);
                    if (values == null) throw new AssertionError(current.label() + ": the JDK did not report its values");
                    expected = expected(values);
                    Outcome reference = script(current.root(), current.javaHome(), current.path(), root);
                    if (feature(values.get("java.specification.version")) >= 21 && (reference.exit() != 0
                            || !Path.of(reference.output()).toRealPath().equals(Path.of(values.get("java.home")).toRealPath()))) {
                        throw new AssertionError(current.label() + ": scripts/jdk.sh selected " + reference);
                    }
                }
                Outcome outcome = run(List.of(program.toString(), current.root() == null ? "-" : current.root().toString(),
                        scratch.toString(), MACOS ? "1" : "0"), root, current.javaHome(), current.path());
                if (outcome.exit() != 43 || !outcome.output().equals(expected)) {
                    throw new AssertionError(current.label() + ": exit " + outcome.exit() + "\nexpected " + expected + "actual   "
                            + outcome.output());
                }
                try (var left = Files.list(scratch)) {
                    if (left.findAny().isPresent()) throw new AssertionError(current.label() + " left an inspection log");
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    static Map<String, String> fakeValues(Path home) throws IOException {
        String text = Files.readString(home.resolve("bin/java"));
        var values = new TreeMap<String, String>();
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            int equals = trimmed.indexOf(" = ");
            if (equals > 0 && trimmed.startsWith("java.")) values.putIfAbsent(trimmed.substring(0, equals), trimmed.substring(equals + 3));
        }
        return values;
    }

    /**
     * javac and javadoc through the selected JDK write the in-process tools'
     * class files and pages byte for byte, and a -Werror failure exits 1 with
     * the same diagnostics; no tool log is left.
     */
    static void tools() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jdk-tools-").toRealPath();
        try {
            Path program = PortFixtures.links(root.resolve("build"), "compiler_bridge_jdk_tools", SOURCES).getFirst();
            String home = Path.of(System.getProperty("java.home")).toRealPath().toString();
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            Path sources = Files.createDirectories(root.resolve("src/bridge/generated/g0"));
            Files.writeString(sources.resolve("Api.java"), """
                    package bridge.generated.g0;

                    /** A generated facade. */
                    public final class Api {
                        private Api() { }

                        /**
                         * Adds two values.
                         *
                         * @param left the first
                         * @param right the second
                         * @return their sum
                         */
                        public static long add(long left, long right) { return left + right; }

                        /** @return the names, in order */
                        public static java.util.List<String> names() { return java.util.List.of("é", "中"); }
                    }
                    """);
            Files.writeString(sources.resolve("Support.java"), """
                    package bridge.generated.g0;

                    final class Support {
                        private Support() { }

                        static native int registered(String name);
                    }
                    """);
            List<Path> files = List.of(sources.resolve("Api.java"), sources.resolve("Support.java"));
            Path javaClasses = Files.createDirectories(root.resolve("java-classes"));
            var diagnostics = new ByteArrayOutputStream();
            BridgeBuildTools.java(files, javaClasses, new PrintStream(diagnostics, true, StandardCharsets.UTF_8));
            Path work = Files.createDirectories(root.resolve("work"));
            Path nativeClasses = Files.createDirectories(root.resolve("native-classes"));
            List<String> javac = new ArrayList<>(List.of(program.toString(), "javac", scratch.toString(), work.toString(),
                    nativeClasses.toString()));
            files.forEach(path -> javac.add(path.toString()));
            requireTool(run(javac, null, home, "/usr/bin:/bin"), 0, diagnostics.toString(StandardCharsets.UTF_8));
            if (!BridgeJarTests.sameTree(BridgeJarTests.tree(javaClasses), BridgeJarTests.tree(nativeClasses))) {
                throw new AssertionError("javac class files differ");
            }
            Path javaDocs = root.resolve("java-docs");
            BridgeBuildTools.javadoc(files, javaClasses, javaDocs, new PrintStream(new ByteArrayOutputStream()));
            Path nativeDocs = root.resolve("native-docs");
            List<String> javadoc = new ArrayList<>(List.of(program.toString(), "javadoc", scratch.toString(), work.toString(),
                    nativeClasses.toString(), nativeDocs.toString()));
            files.forEach(path -> javadoc.add(path.toString()));
            requireTool(run(javadoc, null, home, "/usr/bin:/bin"), 0, "");
            if (!BridgeJarTests.sameTree(BridgeJarTests.tree(javaDocs), BridgeJarTests.tree(nativeDocs))) {
                throw new AssertionError("javadoc pages differ");
            }
            Path failing = Files.writeString(sources.resolve("Raw.java"),
                    "package bridge.generated.g0;\nfinal class Raw { java.util.List items; }\n");
            var failure = new ByteArrayOutputStream();
            try {
                BridgeBuildTools.java(List.of(failing), Files.createDirectories(root.resolve("java-raw")),
                        new PrintStream(failure, true, StandardCharsets.UTF_8));
                throw new AssertionError("raw type compiled under -Werror");
            } catch (IOException expected) {
                if (!expected.getMessage().equals("generated Java Bridge facade compilation failed")) throw expected;
            }
            requireTool(run(List.of(program.toString(), "javac", scratch.toString(), work.toString(),
                    Files.createDirectories(root.resolve("native-raw")).toString(), failing.toString()), null, home, "/usr/bin:/bin"),
                    1, failure.toString(StandardCharsets.UTF_8));
            for (Path directory : List.of(scratch, work)) {
                try (var left = Files.list(directory)) {
                    if (left.anyMatch(path -> path.getFileName().toString().endsWith(".log"))) {
                        throw new AssertionError("a tool log was left in " + directory);
                    }
                }
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

    /**
     * The fixture's tool exit and diagnostics. A separate tool process can
     * print JVM startup warnings, which the in-process tools never do; they
     * are not diagnostics and are left out of the comparison.
     */
    static void requireTool(Outcome outcome, int exit, String diagnostics) {
        String prefix = "exit " + exit + " ";
        String output = outcome.output();
        if (outcome.exit() == 43 && output.startsWith(prefix) && output.endsWith("\n")) {
            String text = unhex(output.substring(prefix.length(), output.length() - 1));
            if (JVM_WARNING.matcher(text).replaceAll("").equals(diagnostics)) return;
        }
        throw new AssertionError("exit " + outcome.exit() + ": " + output.substring(0, Math.min(2000, output.length()))
                + outcome.error());
    }

    static String unhex(String text) {
        StringBuilder out = new StringBuilder();
        if (text.equals("-")) return "";
        for (int index = 0; index + 4 <= text.length(); index += 4) {
            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
        }
        return out.toString();
    }

    /** A selection owns its values; the copies it hands out belong to the caller. */
    static void ownership() throws Exception {
        List<SourceFile> helpers = PortFixtures.portSources(List.of("JdkSelection", "Command", "ExecutableSearch", "TextList"));
        String prefix = "import ironwood.compiler.port.*;\nclass Main { public static int main(String[] args)"
                + " throws ironwood.io.IOException { ironwood.nio.file.Path scratch = ironwood.nio.file.Path.of(args[0]);"
                + " JdkSelection jdk = new JdkSelection(null, scratch, scratch); ";
        for (UnfreedMode mode : UnfreedMode.values()) {
            PortFixtures.require(mode, helpers, prefix + "free scratch; String home = jdk.home(); String vendor ="
                    + " jdk.vendor(); jdk.requireJdk(\"producer\"); free jdk; int r = home.length() + vendor.length();"
                    + " free home; free vendor; return r; }}", null);
            PortFixtures.require(mode, helpers, prefix + "free scratch; String home = jdk.home(); free jdk; free home;"
                    + " return home.length(); }}", "after its allocation was freed");
            PortFixtures.require(mode, helpers, prefix + "free scratch; free jdk; free jdk; return 0; }}",
                    "allocation was already freed");
        }
    }

    /**
     * Raises the allocation limit from zero until a selection, its gates and
     * one javac and javadoc run complete, with a JDK double: every stopped run
     * unwinds its allocations and leaves no inspection or tool log.
     */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-bridge-jdk-sweep-").toRealPath();
        try {
            Path fake = fakeJdk(root.resolve("fake"), "fake21", "21", "Fake Vendor", 0, List.of());
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            Path work = Files.createDirectories(root.resolve("work"));
            for (Path program : PortFixtures.links(root.resolve("build"), "compiler_bridge_jdk_failure", SOURCES)) {
                int limit = 0;
                while (true) {
                    String limitText = Integer.toString(limit);
                    ProcessBuilder builder = new ProcessBuilder(program.toString(), scratch.toString(), work.toString(),
                            MACOS ? "1" : "0").redirectErrorStream(true);
                    builder.environment().put("JAVA_HOME", fake.toString());
                    builder.environment().put("IRONWOOD_ALLOCATION_LIMIT", limitText);
                    Process process = builder.start();
                    process.getInputStream().readAllBytes();
                    int exit = process.waitFor();
                    for (Path directory : List.of(scratch, work)) {
                        try (var left = Files.list(directory)) {
                            List<Path> logs = left.toList();
                            if (!logs.isEmpty()) throw new AssertionError("limit " + limit + " left " + logs);
                        }
                    }
                    if (exit != 42) {
                        if (exit != 43) throw new AssertionError("limit " + limit + " exit " + exit);
                        break;
                    }
                    limit++;
                }
                if (limit < 20) throw new AssertionError("sweep ended at limit " + limit);
            }
        } finally {
            PortFixtures.delete(root);
        }
    }

}
