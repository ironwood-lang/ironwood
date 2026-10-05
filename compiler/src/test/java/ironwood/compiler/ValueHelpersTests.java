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

/** M1.3 escape, int-sequence, singleton-list and record value helpers. */
final class ValueHelpersTests {
    private static final String PORT = "compiler/src/main/ironwood/ironwood/compiler/port/";
    private static final List<String> HELPERS = List.of("SnapshotList", "SnapshotBits", "CharEscapes", "SnapshotInts", "Lists");
    // Matches docs/self-hosting/m1/values-evidence/ValueHelpersReference.java.
    private static final String TRANSCRIPT = """
            escape 34:0:34
            escape 34:1:34
            escape 39:1:39
            escape 92:0:92
            escape 92:1:92
            escape 98:0:8
            escape 98:1:8
            escape 102:0:12
            escape 102:1:12
            escape 110:0:10
            escape 110:1:10
            escape 114:0:13
            escape 114:1:13
            escape 116:0:9
            escape 116:1:9
            escapes present:15 hash:928770999
            ints 0:3:2:false:29853:true:true
            ints 1:1:1000000000:false:1000000031:true:true
            ints 2:2:999999999:true:1000000930:true:true
            ints 3:3:-2147483644:true:-2147451873:true:true
            ints 4:1:7:false:38:true:true
            ints 5:5:1000000006:true:1028635847:true:true
            ints 6:6:852516352:false:-262884769:true:true
            ints 7:4:-4:true:892737:true:true
            ints 8:4:6:true:1131097:true:true
            ints 9:5:1000000000:true:1135465985:true:true
            ints 10:1:1:false:32:true:true
            ints 11:3:2:true:28863:true:true
            ints 12:1:2147483647:false:-2147483618:true:true
            ints 13:2:3:false:994:true:true
            ints 14:3:-2147483646:false:-2147451965:true:true
            ints 15:1:0:false:31:true:true
            ints 16:3:-2147483641:true:-2147453640:true:true
            ints 17:0:0:false:1:true:true
            ints 18:6:-2147483633:true:-1258841152:true:true
            ints 19:1:2147483647:false:-2147483618:true:true
            ints 20:2:1000000000:false:1000000961:true:true
            ints 21:6:-147483648:true:875483167:true:true
            ints 22:1:7:false:38:true:true
            ints 23:3:-2147483641:true:-2147453640:true:true
            ints 24:3:-2147483642:true:-2147454811:true:true
            ints 25:1:-2147483648:true:-2147483617:true:true
            ints 26:2:9:false:1030:true:true
            ints 27:2:8:false:999:true:true
            ints 28:1:2147483647:false:-2147483618:true:true
            ints 29:6:-1147483643:false:1550617090:true:true
            ints 30:1:0:false:31:true:true
            ints 31:1:0:false:31:true:true
            ints 32:1:0:false:31:true:true
            ints 33:1:2147483647:false:-2147483618:true:true
            ints 34:1:7:false:38:true:true
            ints 35:0:0:false:1:true:true
            ints 36:1:1:false:32:true:true
            ints 37:5:-2147483642:true:-2118885211:true:true
            ints 38:3:11:false:31932:true:true
            ints 39:2:14:false:1185:true:true
            ints 40:3:-2147483640:true:-2147453639:true:true
            ints 41:3:13:true:36494:true:true
            ints 42:6:-2147483642:true:-1288639651:true:true
            ints 43:0:0:false:1:true:true
            ints 44:1:2147483647:false:-2147483618:true:true
            ints 45:2:9:false:1030:true:true
            ints 46:2:7:false:1178:true:true
            ints 47:1:0:false:31:true:true
            ints 48:5:5:false:27682566:true:true
            ints 49:0:0:false:1:true:true
            ints 50:3:2147483646:true:-2147453889:true:true
            ints 51:6:-2147483630:true:-1196048419:true:true
            ints 52:2:8:false:1179:true:true
            ints 53:2:8:false:999:true:true
            ints 54:5:-1147483644:true:-1011094079:true:true
            ints 55:0:0:false:1:true:true
            ints 56:5:2147483646:true:-2119807839:true:true
            ints 57:6:1000000001:false:1945684512:true:true
            ints 58:3:-1147483649:true:1074839134:true:true
            ints 59:0:0:false:1:true:true
            ints 60:4:8:true:900519:true:true
            ints 61:1:1:false:32:true:true
            ints 62:0:0:false:1:true:true
            ints 63:2:1000000002:false:1000001023:true:true
            ints empty:0:0:1
            ints bounds:true
            single:1:true
            single null:true
            position:true:true:false:false
            position invalid:true:true:true:false
            span:true:true:false
            span invalid:true:true:true:false
            site:true:true:false
            retention:true:true:false
            summary:false:true:false:false
            """;

    private ValueHelpersTests() { }

    static void ownership() throws Exception {
        List<SourceFile> sources = new ArrayList<>();
        for (String helper : HELPERS) {
            sources.add(SourceFile.of("test/" + helper + ".iron", Files.readString(Path.of(PORT + helper + ".iron"))));
        }
        String prefix = """
                import ironwood.ds.IntArrayList; import ironwood.compiler.port.*;
                class Item { }
                class Main { public static int main(String[] args) {
                    IntArrayList builder = new IntArrayList(2); builder.add(3); builder.add(4);
                    SnapshotInts counts = new SnapshotInts(builder);
                    Item item = new Item(); SnapshotList<Item> one = Lists.single(item);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            require(mode, sources, prefix + "free builder; int total = counts.sum() + one.size()"
                    + " + CharEscapes.decodeSimple('n', false); free counts; free one; return total; }}", null);
            require(mode, sources, prefix + "free counts; free builder; free one; return counts.sum(); }}",
                    "after its allocation was freed");
            require(mode, sources, prefix + "free one; free counts; free builder; return one.size(); }}",
                    "after its allocation was freed");
            // The generic singleton factory conservatively exposes its item.
            require(mode, sources, prefix + "free one; free counts; free builder; free item; return 0; }}",
                    "cannot free 'item'");
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-value-helpers-");
        try {
            Path classes = root.resolve("classes");
            List<String> arguments = new ArrayList<>();
            for (String helper : HELPERS) arguments.add(PORT + helper + ".iron");
            arguments.addAll(List.of("integration-tests/cases/compiler_value_helpers.iron", "--unfreed=warn",
                    "-d", classes.toString()));
            run(arguments);
            Path archive = root.resolve("values.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("value helper archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("value helper program timed out");
                }
                String text = new String(output, StandardCharsets.UTF_8);
                if (process.exitValue() != 42 || !text.equals(TRANSCRIPT)) {
                    throw new AssertionError("value helper program: exit " + process.exitValue() + ", output " + text);
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
                throw new AssertionError("value helper compiler run " + arguments + ": exit " + exit + ": " + text);
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
            throw new AssertionError("value helper control under " + mode + ": " + messages + "\n" + source);
        }
    }
}
