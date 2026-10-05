// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The pilot's closed variant lists track the Java model and fail closed. */
final class OperationVariantTests {
    private static final String HELPER = "compiler/src/main/ironwood/ironwood/compiler/port/OperationVariants.iron";
    private static final String EXPECTED = """
            instruction: CALL FOREIGN_CALL
            terminator: RETURN
            operand: VALUE_REFERENCE
            call kind: DIRECT
            callable kind: METHOD
            type kind: REFERENCE
            origin: LOCAL_NEW
            input state: ACTIVE ESCAPED
            joined state: ACTIVE UNCERTAIN ESCAPED
            event: REASON
            analyzer: EFFECT
            phase: INITIAL
            unfreed mode: WARN
            """;
    private static final String MISSING = "switch expression must cover every enum constant or declare default";

    private OperationVariantTests() { }

    /** Every native list names exactly the Java sealed permits or enum constants. */
    static void matchesJavaModel() throws Exception {
        Map<String, List<String>> native_ = enums(Files.readString(Path.of(HELPER)));
        Map<String, List<String>> java = new LinkedHashMap<>();
        java.put("Instruction", permitted("ironwood.compiler.ir.IrInstruction", "Instruction"));
        java.put("Terminator", permitted("ironwood.compiler.ir.IrTerminator", "Terminator"));
        java.put("Operand", permitted("ironwood.compiler.ir.IrOperand", ""));
        java.put("CallKind", constants("ironwood.compiler.ir.IrCallKind"));
        java.put("CallableKind", constants("ironwood.compiler.ir.IrCallableKind"));
        java.put("TypeKind", constants("ironwood.compiler.ir.IrType$Kind"));
        java.put("AllocationOrigin", constants("ironwood.compiler.semantic.FunctionAnalyzer$AllocationOrigin"));
        java.put("AllocationState", constants("ironwood.compiler.semantic.FunctionAnalyzer$AllocationState"));
        java.put("EventKind", constants("ironwood.compiler.semantic.RejectedFreeEvidence$EventKind"));
        java.put("AnalyzerKind", constants("ironwood.compiler.semantic.SemanticAnalysisObserver$AnalyzerKind"));
        java.put("AnalyzerPhase", constants("ironwood.compiler.semantic.SemanticAnalysisObserver$AnalyzerPhase"));
        java.put("UnfreedMode", constants("ironwood.compiler.UnfreedMode"));
        if (!native_.equals(java)) throw new AssertionError("variant lists differ:\nnative " + native_ + "\njava   " + java);
    }

    /** Deleting a treatment, adding an untreated variant or dropping an admitted arm fails. */
    static void failsClosed() throws Exception {
        String source = Files.readString(Path.of(HELPER));
        List<String> mutations = List.of(
                source.replace("case BRANCH, INVOKE, JUMP, SWITCH, THROW, UNREACHABLE -> false;",
                        "case BRANCH, INVOKE, JUMP, SWITCH, THROW -> false;"),
                source.replace("BRANCH, INVOKE, JUMP, RETURN, SWITCH, THROW, UNREACHABLE\n",
                        "BRANCH, INVOKE, JUMP, RETURN, SWITCH, THROW, UNREACHABLE, PHANTOM\n"),
                source.replace("            case REASON -> true;\n", ""));
        for (String mutation : mutations) {
            if (mutation.equals(source)) throw new AssertionError("variant mutation did not apply");
            for (UnfreedMode mode : UnfreedMode.values()) {
                CompilationArtifact artifact = new CompilerPipeline(mode)
                        .compile(SourceFile.of("test/OperationVariants.iron", mutation));
                String messages = String.join("; ", artifact.diagnostics().stream()
                        .map(diagnostic -> diagnostic.message()).toList());
                if (artifact.successful() || !messages.contains(MISSING)) {
                    throw new AssertionError("untreated variant compiled under " + mode + ": " + messages);
                }
            }
        }
    }

    static void artifacts() throws Exception {
        Path root = Files.createTempDirectory("ironwood-operation-variants-");
        try {
            Path classes = root.resolve("classes");
            run(List.of(HELPER, "integration-tests/cases/compiler_operation_variants.iron", "--unfreed=warn",
                    "-d", classes.toString()));
            Path archive = root.resolve("variants.ironjar");
            ByteArrayOutputStream ignored = new ByteArrayOutputStream();
            if (IronJarMain.run(new String[]{"--create", "--file", archive.toString(), classes.toString()},
                    new PrintStream(ignored), new PrintStream(ignored)) != 0) {
                throw new AssertionError("variant archive: " + ignored);
            }
            for (Path input : List.of(classes, archive)) {
                Path executable = root.resolve(input.getFileName() + "-program");
                run(List.of("--link", "-cp", input.toString(), "--main-class", "Main", "--unfreed=warn",
                        "-O3", "-o", executable.toString()));
                Process process = new ProcessBuilder(executable.toString()).redirectErrorStream(true).start();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new AssertionError("variant program timed out");
                }
                String text = new String(output, StandardCharsets.UTF_8);
                if (process.exitValue() != 42 || !text.equals(EXPECTED)) {
                    throw new AssertionError("variant program: exit " + process.exitValue() + ", output " + text);
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Map<String, List<String>> enums(String source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("public enum (\\w+) \\{([^}]*)\\}").matcher(source);
        while (matcher.find()) {
            List<String> names = Arrays.stream(matcher.group(2).split(","))
                    .map(String::trim).filter(name -> !name.isEmpty()).toList();
            boolean sealed = List.of("Instruction", "Terminator", "Operand").contains(matcher.group(1));
            result.put(matcher.group(1), sealed ? names.stream().sorted().toList() : names);
        }
        return result;
    }

    private static List<String> permitted(String root, String suffix) throws Exception {
        return Arrays.stream(Class.forName(root).getPermittedSubclasses()).map(type -> {
            String base = type.getSimpleName().replaceFirst("^Ir", "");
            if (!suffix.isEmpty() && base.endsWith(suffix) && base.length() > suffix.length()) {
                base = base.substring(0, base.length() - suffix.length());
            }
            return base.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
        }).sorted().toList();
    }

    private static List<String> constants(String type) throws Exception {
        return Arrays.stream(Class.forName(type).getEnumConstants()).map(Object::toString).toList();
    }

    private static void run(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            int exit = Main.run(new ArrayList<>(arguments).toArray(String[]::new), stream, stream);
            String text = output.toString(StandardCharsets.UTF_8);
            if (exit != 0 || text.contains("warning") || text.contains("error")) {
                throw new AssertionError("variant compiler run " + arguments + ": exit " + exit + ": " + text);
            }
        }
    }
}
