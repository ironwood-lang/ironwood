// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Native ArrayList.copy transcripts must equal Java 21 ArrayList copy-constructor transcripts. */
final class ListCopyParityTests {
    private static final String FIXTURE = "integration-tests/cases/compiler_list_copy_parity.iron";
    private static final String[] LABELS = {"a", "b", "c", "d", "e", "f", "g", "h"};

    private ListCopyParityTests() { }

    static void differential() throws Exception {
        String expected = javaTranscript();
        Path root = Files.createTempDirectory("ironwood-list-copy-parity-");
        try {
            Path classes = root.resolve("classes");
            run(List.of(FIXTURE, "--unfreed=warn", "-d", classes.toString()));
            Path archive = root.resolve("parity.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("list copy parity archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("list copy parity program timed out");
                }
                String text = new String(output, StandardCharsets.UTF_8);
                if (process.exitValue() != 42 || !text.equals(expected)) {
                    throw new AssertionError("list copy parity program exit " + process.exitValue()
                            + ", first difference at " + firstDifference(text, expected));
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    // The fixture's generator and operations, applied to java.util.ArrayList copies.
    private static String javaTranscript() {
        int[] state = {20261005};
        ArrayList<String> source = new ArrayList<>(List.of("a", "b"));
        ArrayList<String> first = new ArrayList<>(source);
        StringBuilder out = new StringBuilder();
        for (int step = 0; step < 200; step++) {
            int operation = next(state, 7);
            int target = next(state, 2);
            String label = LABELS[next(state, LABELS.length)];
            apply(state, target == 0 ? source : first, operation, label);
            line(out, step, List.of(source, first));
        }
        ArrayList<String> second = new ArrayList<>(first);
        ArrayList<String> third = new ArrayList<>(source);
        List<ArrayList<String>> lists = List.of(source, first, second, third);
        for (int step = 200; step < 400; step++) {
            int operation = next(state, 7);
            int target = next(state, 4);
            String label = LABELS[next(state, LABELS.length)];
            apply(state, lists.get(target), operation, label);
            line(out, step, lists);
        }
        return out.toString();
    }

    private static void apply(int[] state, ArrayList<String> list, int operation, String label) {
        if (operation == 0) list.add(label);
        if (operation == 1) list.add(next(state, list.size() + 1), label);
        if (operation == 2 && !list.isEmpty()) list.set(next(state, list.size()), label);
        if (operation == 3 && !list.isEmpty()) list.remove(next(state, list.size()));
        if (operation == 4 && !list.isEmpty()) list.removeFirst();
        if (operation == 5 && !list.isEmpty()) list.removeLast();
        if (operation == 6) list.addFirst(label);
    }

    private static int next(int[] state, int bound) {
        state[0] = state[0] * 1103515245 + 12345;
        return ((state[0] >>> 16) & 32767) % bound;
    }

    private static void line(StringBuilder out, int step, List<ArrayList<String>> lists) {
        out.append(step).append(':');
        for (ArrayList<String> list : lists) out.append(" [").append(String.join(",", list)).append(']');
        out.append('\n');
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
                throw new AssertionError("list copy parity compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }
}
