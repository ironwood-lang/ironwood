// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Native text-block normalization must equal Java 21 String.stripIndent. */
final class TextBlocksTests {
    private static final String HELPER = "compiler/src/main/ironwood/ironwood/compiler/port/TextBlocks.iron";

    private TextBlocksTests() { }

    static void ownership() throws Exception {
        SourceFile helper = SourceFile.of("test/TextBlocks.iron", Files.readString(Path.of(HELPER)));
        String prefix = """
                import ironwood.compiler.port.TextBlocks;
                class Main { public static int main(String[] args) {
                    StringBuilder builder = new StringBuilder(); builder.append("  a\\n  b");
                    String input = builder.toString(); free builder;
                    String output = TextBlocks.stripIndent(input);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(mode, helper, prefix + "free input; int length = output.length(); free output; return length; }}", null);
            require(mode, helper, prefix + "free output; free input; return output.length(); }}", "after its allocation was freed");
        }
    }

    static void differential() throws Exception {
        String expected = javaTranscript();
        Path root = Files.createTempDirectory("ironwood-text-blocks-");
        try {
            Path classes = root.resolve("classes");
            run(List.of(HELPER, "integration-tests/cases/compiler_text_blocks.iron", "--unfreed=warn", "-d", classes.toString()));
            Path archive = root.resolve("text.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("text block archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("text block program timed out");
                }
                String text = new String(output, StandardCharsets.UTF_8);
                if (process.exitValue() != 42 || !text.equals(expected)) {
                    throw new AssertionError("text block program exit " + process.exitValue()
                            + ", first difference at " + firstDifference(text, expected));
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // The fixture's generator and hash, applied to Java 21's String.stripIndent.
    private static String javaTranscript() {
        StringBuilder out = new StringBuilder();
        String[] fixed = {"", "\n", "  a\n  b", "  a\n   b\n  ", "  a\n\n  b\n", "\ta\n\t\tb\n\t", " a \r\n  b\t\r  ",
                "    hello\n    ", "  x\n    \n  y\n ", "a\r\rb\r\n", " a\n b", " a\n b"};
        for (int index = 0; index < fixed.length; index++) line(out, -1 - index, fixed[index].stripIndent());
        int[] state = {20261005};
        char[] alphabet = {' ', ' ', ' ', '\t', '\f', '\u000B', ' ', ' ', '\n', '\r', 'a', 'b', '\\'};
        for (int index = 0; index < 20000; index++) {
            int length = next(state, 25);
            StringBuilder builder = new StringBuilder();
            for (int position = 0; position < length; position++) builder.append(alphabet[next(state, 13)]);
            line(out, index, builder.toString().stripIndent());
        }
        return out.toString();
    }

    private static int next(int[] state, int bound) {
        state[0] = state[0] * 1103515245 + 12345;
        return ((state[0] >>> 16) & 32767) % bound;
    }

    private static void line(StringBuilder out, int index, String value) {
        int hash = -2128831035;
        for (int position = 0; position < value.length(); position++) hash = (hash ^ value.charAt(position)) * 16777619;
        out.append(index).append(':').append(value.length()).append(':').append(hash).append('\n');
    }

    private static int firstDifference(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        for (int index = 0; index < limit; index++) if (left.charAt(index) != right.charAt(index)) return index;
        return limit;
    }

    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("text block compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static void require(UnfreedMode mode, SourceFile helper, String source, String rejection) {
        CompilationArtifact artifact = new CompilerPipeline(mode)
                .compile(List.of(helper, SourceFile.of("test/Main.iron", source)));
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        if (rejection == null ? !artifact.successful() : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("text block ownership under " + mode + ": " + messages + "\n" + source);
        }
    }
}
