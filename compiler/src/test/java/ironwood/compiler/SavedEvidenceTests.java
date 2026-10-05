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

/** Proof snapshots own their saved explanation evidence; no weak keys remain. */
final class SavedEvidenceTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final List<String> HELPERS = List.of("SnapshotList", "SnapshotIdentityMap");
    private static final String FIXTURE = "integration-tests/cases/compiler_saved_evidence.iron";

    private SavedEvidenceTests() { }

    static void controls() throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        for (String helper : HELPERS) {
            sources.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        String fixture = Files.readString(Path.of(FIXTURE));
        String prefix = fixture.substring(fixture.indexOf("import ironwood.ds.*;"), fixture.indexOf("class Main {"))
                + """
                class Main { public static int main(String[] args) {
                    Object node = new Object(); Object source = new Object(); Site site = new Site(source, 1);
                    Evidence evidence = new Evidence(64); evidence.record(node, site);
                    ProofSnapshot proof = new ProofSnapshot(evidence);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(mode, sources, prefix + "boolean restored = evidence.restore(proof.saved()); evidence.release(proof.saved());"
                    + " free proof; evidence.close(); free evidence; return restored ? 42 : 0; }}", null);
            require(mode, sources, prefix + "Saved saved = proof.saved(); free proof; int count = saved.count();"
                    + " evidence.close(); free evidence; return count; }}", "after its allocation was freed");
            require(mode, sources, prefix + "Saved saved = proof.saved(); free saved; free proof; free evidence; return 0; }}",
                    "borrowed helper owned by another object");
            require(mode, sources, prefix + "free evidence; boolean restored = evidence.restore(proof.saved()); free proof;"
                    + " return 0; }}", "after its allocation was freed");
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-saved-evidence-");
        try {
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>();
            for (String helper : HELPERS) arguments.add(PORT + helper + ".iron");
            arguments.addAll(List.of(FIXTURE, "--unfreed=warn", "-d", classes.toString()));
            run(arguments);
            Path archive = root.resolve("evidence.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("saved evidence archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("saved evidence program timed out");
                }
                String text = new String(output, StandardCharsets.UTF_8);
                String expected = "budget 1048576:2:true:true:true:true\nbudget 8:1:true:true:true:true\n"
                        + "budget 5:0:true:true:true:true\n";
                if (process.exitValue() != 42 || !text.equals(expected)) {
                    throw new AssertionError("saved evidence program: exit " + process.exitValue() + ", output " + text);
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("saved evidence compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }

    private static void require(UnfreedMode mode, List<SourceFile> helpers, String source, String rejection) {
        List<SourceFile> sources = new ArrayList<>(helpers);
        sources.add(SourceFile.of("test/Main.iron", source));
        CompilationArtifact artifact = new CompilerPipeline(mode).compile(sources);
        String messages = String.join("; ", artifact.diagnostics().stream()
                .map(diagnostic -> diagnostic.message()).toList());
        boolean accepted = mode == UnfreedMode.ERROR
                ? !messages.contains("cannot free") && !messages.contains("after its allocation was freed")
                : artifact.successful();
        if (rejection == null ? !accepted : artifact.successful() || !messages.contains(rejection)) {
            throw new AssertionError("saved evidence control under " + mode + ": " + messages + "\n" + source);
        }
    }
}
