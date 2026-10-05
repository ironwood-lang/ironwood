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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shared IR variant inventory (D265) against the Java IR model. */
final class IrModelInventoryTests {
    private static final String OPERATIONS = "compiler/src/main/ironwood/ironwood/compiler/port/OperationVariants.iron";
    private static final String MISSING = "switch expression must cover every enum constant or declare default";

    private IrModelInventoryTests() { }

    /** The checked-in source equals a fresh generation, and the pilot root lists agree with it. */
    static void matchesJavaModel() throws Exception {
        String checkedIn = Files.readString(IrModelInventory.TARGET);
        if (!checkedIn.equals(IrModelInventory.generate())) {
            throw new AssertionError("IrModel.iron is stale: regenerate it with IrModelInventory.main");
        }
        String operations = Files.readString(Path.of(OPERATIONS));
        for (String[] root : new String[][]{{"Instruction", "INSTRUCTION"}, {"Terminator", "TERMINATOR"},
                {"Operand", "OPERAND"}}) {
            long shared = transcript().lines().filter(line -> line.startsWith("record ")
                    && line.split(" ")[3].equals(root[1])).count();
            Matcher matcher = Pattern.compile("public enum " + root[0] + " \\{([^}]*)\\}").matcher(operations);
            if (!matcher.find()) throw new AssertionError("no pilot list " + root[0]);
            long pilot = matcher.group(1).split(",").length;
            if (shared != pilot) throw new AssertionError(root[0] + ": shared " + shared + ", pilot " + pilot);
        }
    }

    /** Deleting a treatment or adding an untreated record or enum fails in every mode. */
    static void failsClosed() throws Exception {
        String source = Files.readString(IrModelInventory.TARGET);
        String walkedArm = "            case IR_JUMP -> \"\";\n";
        String rootArm = "            case IR_CALL_INSTRUCTION -> Root.INSTRUCTION;\n";
        String constantsArm = source.lines().filter(line -> line.contains("case IR_BINARY_OPERATOR ->"))
                .findFirst().orElseThrow() + "\n";
        List<String> mutations = List.of(
                source.replace(walkedArm, ""),
                source.replace(rootArm, ""),
                source.replace(constantsArm, ""),
                source.replace("        IR_ADD_SECONDARY_EXCEPTION_INSTRUCTION,",
                        "        IR_PHANTOM_INSTRUCTION, IR_ADD_SECONDARY_EXCEPTION_INSTRUCTION,"),
                source.replace("    public enum Enumeration {\n", "    public enum Enumeration {\n        IR_PHANTOM_KIND,\n"));
        for (String mutation : mutations) {
            if (mutation.equals(source)) throw new AssertionError("inventory mutation did not apply");
            for (UnfreedMode mode : UnfreedMode.values()) {
                CompilationArtifact artifact = new CompilerPipeline(mode)
                        .compile(List.of(SourceFile.of("test/IrModel.iron", mutation)));
                String messages = String.join("; ", artifact.diagnostics().stream()
                        .map(diagnostic -> diagnostic.message()).toList());
                if (artifact.successful() || !messages.contains(MISSING)) {
                    throw new AssertionError("untreated IR variant compiled under " + mode + ": " + messages);
                }
            }
        }
    }

    static void artifacts() throws Exception {
        String expected = transcript();
        Path root = Files.createTempDirectory("ironwood-ir-model-");
        try {
            Path classes = root.resolve("classes");
            run(List.of(IrModelInventory.TARGET.toString(), "integration-tests/cases/compiler_ir_model.iron",
                    "--unfreed=warn", "-d", classes.toString()));
            Path archive = root.resolve("model.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("IR model archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("IR model program timed out");
                }
                if (process.exitValue() != 42 || !output.equals(expected)) {
                    throw new AssertionError("IR model transcript from " + input.getFileName() + " differs");
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** The fixture's expected lines, read back from the generated switches. */
    static String transcript() throws Exception {
        String source = IrModelInventory.generate();
        List<String> records = constants(source, "Record");
        List<String> enums = constants(source, "Enumeration");
        StringBuilder out = new StringBuilder();
        for (String record : records) {
            out.append("record ").append(record).append(' ').append(unquote(arm(source, "javaName", record)))
                    .append(' ').append(arm(source, "root", record).replace("Root.", ""))
                    .append(' ').append(unquote(arm(source, "components", record)))
                    .append(" walked=").append(unquote(arm(source, "walked", record))).append('\n');
        }
        for (String type : enums) {
            out.append("enum ").append(type).append(' ').append(unquote(arm(source, "constants", type))).append('\n');
        }
        if (records.size() != 113 || enums.size() != 20) {
            throw new AssertionError("inventory size " + records.size() + " records, " + enums.size() + " enums");
        }
        return out.toString();
    }

    private static List<String> constants(String source, String name) {
        Matcher matcher = Pattern.compile("public enum " + name + " \\{([^}]*)\\}").matcher(source);
        if (!matcher.find()) throw new AssertionError("no enum " + name);
        List<String> result = new ArrayList<>();
        for (String part : matcher.group(1).split(",")) result.add(part.trim());
        return result;
    }

    private static String arm(String source, String function, String constant) {
        int start = source.indexOf(" " + function + "(");
        Matcher matcher = Pattern.compile("case " + constant + " -> (.*);").matcher(source);
        if (start < 0 || !matcher.find(start)) throw new AssertionError("no arm " + function + " " + constant);
        return matcher.group(1);
    }

    private static String unquote(String text) {
        return text.substring(1, text.length() - 1);
    }

    /** Compiles with no diagnostics at all. */
    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(arguments.toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("IR model compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }
}
