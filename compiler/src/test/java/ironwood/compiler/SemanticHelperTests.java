// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.semantic.ByteViewIntrinsic;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * M3.2 semantic helpers (D267): bounded literal decoding and folding, SHA-256
 * with ByteView declaration authority, UTF-8 lengths and text helpers, and
 * the floating text that constant folding uses.
 */
final class SemanticHelperTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final String EVIDENCE = "docs/self-hosting/m3/semantic-evidence/";
    private static final String CLASSES = "compiler/build/classes";
    private static final String BYTE_VIEW = "ironwood.bridge.ByteView";

    private SemanticHelperTests() { }

    static void integralConstants() throws Exception {
        compare("compiler_integral_constants", List.of(PORT + "IntegerLiterals.iron", PORT + "IntegralConstants.iron",
                PORT + "OperationVariants.iron", "compiler/src/main/ironwood/ironwood/compiler/ast/BinaryOperator.iron"),
                EVIDENCE + "IntegralReference.java", true, 42);
    }

    static void sha256() throws Exception {
        compare("compiler_sha256", List.of(PORT + "Sha256.iron"), EVIDENCE + "Sha256Reference.java", false, 42);
    }

    static void textHelpers() throws Exception {
        compare("compiler_text_helpers", List.of(PORT + "Texts.iron", PORT + "SnapshotList.iron"),
                EVIDENCE + "TextHelpersReference.java", true, 42);
    }

    static void floatingText() throws Exception {
        compare("compiler_floating_text", List.of(), EVIDENCE + "FloatingTextReference.java", false, 0);
    }

    /**
     * J0 trusts the bundled declaration from source, class and archive and
     * distrusts changed copies; the native digest gives the same digests and
     * verdicts for every content J0 hashes.
     */
    static void byteViewAuthority() throws Exception {
        SourceFile source = SourceFile.read(Path.of("stdlib/src/main/ironwood/ironwood/bridge/ByteView.iron"));
        SourceFile fromClass = IronClass.read(Path.of("compiler/build/stdlib/ironwood/bridge/ByteView.ironclass"))
                .source(BYTE_VIEW).orElseThrow();
        SourceFile fromArchive = IronJar.read(Path.of("compiler/build/ironwood-stdlib.ironjar"))
                .source(BYTE_VIEW).orElseThrow();
        String content = source.content();
        List<String> contents = new ArrayList<>(List.of(content, fromClass.content(), fromArchive.content()));
        contents.add(content.replaceFirst("\\*/", " */"));
        contents.add(content.replace("readByte", "readBytes"));
        contents.add(content.replace("\n", "\r\n"));
        contents.add(content.substring(0, content.length() - 1));
        Path root = Files.createTempDirectory("ironwood-byte-view-authority-");
        try {
            List<String> arguments = new ArrayList<>();
            StringBuilder expected = new StringBuilder();
            for (int index = 0; index < contents.size(); index++) {
                String text = contents.get(index);
                boolean trusted = ByteViewIntrinsic.trusted(SourceFile.of("ByteView.iron", text));
                if (trusted != index < 3) throw new AssertionError("J0 authority of content " + index + ": " + trusted);
                Path file = root.resolve("content-" + index + ".iron");
                Files.write(file, text.getBytes(StandardCharsets.UTF_8));
                arguments.add(file.toString());
                String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(text.getBytes(StandardCharsets.UTF_8)));
                expected.append(index).append(' ').append(hex).append(' ').append(trusted).append('\n');
            }
            for (Path executable : links(root, "compiler_byteview_digest", List.of(PORT + "Sha256.iron"))) {
                List<String> command = new ArrayList<>(List.of(executable.toString()));
                command.addAll(arguments);
                execute(command, null, 42, expected.toString());
            }
        } finally {
            delete(root);
        }
    }

    static void ownership() throws Exception {
        List<SourceFile> helpers = new ArrayList<>();
        for (String helper : List.of("Sha256", "IntegerLiterals", "Texts", "SnapshotList")) {
            helpers.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        String prefix = """
                import ironwood.ds.ArrayList;
                import ironwood.compiler.port.*;
                class Main { public static int main(String[] args) {
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Inputs are borrowed only during a call; results belong to the caller.
            require(mode, helpers, prefix + "byte[] data = new byte[4]; Sha256 digest = new Sha256();"
                    + " digest.update(data, 0, 4); free data; String hex = digest.hexDigest(); free hex;"
                    + " byte[] raw = digest.digest(); free raw; free digest; return 0; }}", null);
            require(mode, helpers, prefix + "Sha256 digest = new Sha256(); free digest; digest.update(1);"
                    + " return 0; }}", "after its allocation was freed");
            require(mode, helpers, prefix + "Sha256 digest = new Sha256(); byte[] raw = digest.digest(); free raw;"
                    + " free raw; free digest; return 0; }}", "freed");
            require(mode, helpers, prefix + "String error = IntegerLiterals.error(\"0x\", false); int r = error.length();"
                    + " free error; return r; }}", null);
            require(mode, helpers, prefix + "String error = IntegerLiterals.error(\"0x\", false); free error;"
                    + " return error.length(); }}", "after its allocation was freed");
            require(mode, helpers, prefix + "ArrayList<String> parts = new ArrayList<String>(); parts.add(\"a\");"
                    + " String joined = Texts.join(\",\", parts); free parts; int r = joined.length(); free joined;"
                    + " return r; }}", null);
        }
    }

    /** Every allocation failure unwinds to the baseline; a large enough limit succeeds. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-semantic-helpers-failure-");
        try {
            for (Path executable : links(root, "compiler_semantic_helpers_failure", List.of(PORT + "Sha256.iron",
                    PORT + "IntegerLiterals.iron", PORT + "Texts.iron", PORT + "SnapshotList.iron"))) {
                execute(List.of(executable.toString()), null, 43, "");
                int limit = 0;
                while (execute(List.of(executable.toString()), limit, -1, "") == 42) limit++;
                if (limit < 10 || execute(List.of(executable.toString()), limit, 43, "") != 43) {
                    throw new AssertionError("semantic helper OOM sweep ended at limit " + limit);
                }
            }
        } finally {
            delete(root);
        }
    }

    private static void compare(String fixture, List<String> helpers, String reference, boolean bootstrapClasses,
                                int exit) throws Exception {
        String expected = reference(reference, bootstrapClasses);
        Path root = Files.createTempDirectory("ironwood-" + fixture + "-");
        try {
            for (Path executable : links(root, fixture, helpers)) {
                execute(List.of(executable.toString()), null, exit, expected);
            }
        } finally {
            delete(root);
        }
    }

    private static List<Path> links(Path root, String fixture, List<String> helpers) throws Exception {
        Path classes = root.resolve(fixture);
        List<String> arguments = new ArrayList<>(helpers);
        arguments.addAll(List.of("integration-tests/cases/" + fixture + ".iron", "--unfreed=warn",
                "-d", classes.toString()));
        run(arguments);
        Path archive = root.resolve(fixture + ".ironjar");
        ByteArrayOutputStream ignored = new ByteArrayOutputStream();
        if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                new PrintStream(ignored), new PrintStream(ignored)) != 0) {
            throw new AssertionError(fixture + " archive: " + ignored);
        }
        List<Path> executables = new ArrayList<>();
        for (Path input : List.of(classes, archive)) {
            Path executable = root.resolve(input.getFileName() + "-program");
            run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                    "-O3", "-o", executable.toString()));
            executables.add(executable);
        }
        return executables;
    }

    /** Runs a reference in a fresh JVM with inherited options cleared, optionally on J0's classes. */
    private static String reference(String path, boolean bootstrapClasses) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString()));
        if (bootstrapClasses) command.addAll(List.of("-cp", CLASSES));
        command.add(path);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        for (String variable : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(variable);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("reference " + path + " failed: " + output);
        }
        return output;
    }

    /** Compiles with no diagnostics at all. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("semantic helper compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static int execute(List<String> command, Integer limit, int expectedExit, String expectedOutput)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        if (limit == null) environment.remove("IRONWOOD_ALLOCATION_LIMIT");
        else environment.put("IRONWOOD_ALLOCATION_LIMIT", limit.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("semantic helper program timed out");
        }
        if (expectedExit >= 0 && (process.exitValue() != expectedExit || !output.equals(expectedOutput))) {
            throw new AssertionError(Path.of(command.getFirst()).getFileName() + " limit " + limit + ": exit "
                    + process.exitValue() + ", output " + output.length() + " characters");
        }
        return process.exitValue();
    }

    private static void require(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("semantic helper ownership under " + mode + ": " + messages + "\n" + source);
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
