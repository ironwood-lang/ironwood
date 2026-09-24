// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.semantic.SemanticObserverBridge;
import ironwood.compiler.source.SourceFile;

import java.util.ArrayList;
import java.util.List;

final class ControlFlowStorageTests {
    private ControlFlowStorageTests() {}

    static void boundedFamilies() {
        for (int depth : List.of(2, 5, 10)) {
            String name = "Nested" + depth;
            String rejected = nested(name, depth, true, false);
            SourceFile source = SourceFile.of(name + ".iron", rejected);
            CompilationArtifact off = analyze(source, false, null);
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact on = analyze(source, true, counts);
            samePrimaries(off, on, 1);
            require(counts.collectorsFinished() > 0
                            && counts.collectorHighWater() <= 4096
                            && counts.snapshotHighWater() <= 2048
                            && counts.invocationHighWater() <= 1048576
                            && !counts.invocationStopped(),
                    name + " exceeded an evidence budget");
            Diagnostic error = on.diagnostics().getFirst();
            require(error.notes().size() <= 8
                            && truthfulOmissions(error),
                    name + " emitted an unbounded or contradictory explanation: " + error);
            if (depth == 10) {
                CompilationArtifact repeated = analyze(source, true, null);
                samePrimaries(on, repeated, 1);
                require(noteKeys(on).equals(noteKeys(repeated)),
                        "deep join selected unstable notes");
            }
            SourceFile safe = SourceFile.of(name + "Safe.iron",
                    nested(name + "Safe", depth, false, false));
            CompilationArtifact safeOff = compile(safe, false);
            CompilationArtifact safeOn = compile(safe, true);
            require(safeOff.successful() && safeOn.successful()
                            && safeOff.diagnostics().isEmpty()
                            && safeOn.diagnostics().isEmpty()
                            && safeOff.llvmIr().equals(safeOn.llvmIr()),
                    name + " accepted control changed IR or diagnostics: off="
                            + safeOff.diagnostics() + " on=" + safeOn.diagnostics());
        }

        for (int joins : List.of(16, 64)) {
            String name = "Sequential" + joins;
            SourceFile source = SourceFile.of(name + ".iron", sequential(name, joins));
            CompilationArtifact off = analyze(source, false, null);
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact on = analyze(source, true, counts);
            samePrimaries(off, on, joins);
            require(!counts.invocationStopped()
                            && counts.collectorHighWater() <= 4096
                            && counts.snapshotHighWater() <= 2048
                            && on.diagnostics().stream().allMatch(d -> d.notes().size() <= 8),
                    name + " enumerated unbounded path combinations");
        }

        for (int allocations : List.of(1, 8, 24)) {
            String name = "Present" + allocations;
            SourceFile source = SourceFile.of(name + ".iron", present(name, allocations));
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact on = analyze(source, true, counts);
            CompilationArtifact off = analyze(source, false, null);
            require(on.valid() && off.valid() && on.diagnostics().isEmpty()
                            && off.diagnostics().isEmpty()
                            && counts.collectorHighWater() <= 4096
                            && counts.snapshotHighWater() <= 2048
                            && !counts.invocationStopped(),
                    name + " changed acceptance or exhausted evidence");
        }

        SourceFile cleanup = SourceFile.of("CleanupStress.iron",
                nested("CleanupStress", 5, true, true));
        CompilationArtifact cleanupOff = analyze(cleanup, false, null);
        SemanticObserverBridge.Counts cleanupCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact cleanupOn = analyze(cleanup, true, cleanupCounts);
        samePrimaries(cleanupOff, cleanupOn, 3);
        require(cleanupOn.diagnostics().stream().allMatch(d -> d.notes().size() <= 8
                        && d.notes().getLast().message().startsWith(
                        "this cleanup is checked for "))
                        && cleanupCounts.collectorHighWater() <= 4096
                        && cleanupCounts.snapshotHighWater() <= 2048
                        && !cleanupCounts.invocationStopped(),
                "cleanup copies exceeded their per-error or collector cap: "
                        + cleanupOn.diagnostics());

        SourceFile loop = SourceFile.of("LoopCleanupStress.iron", """
                class LoopCleanupStress {
                    static void check(int count) {
                        byte[] data = new byte[16];
                        for (int i = 0; i < count; i++) {
                            defer free data;
                        }
                    }
                }
                """);
        CompilationArtifact loopOff = analyze(loop, false, null);
        SemanticObserverBridge.Counts loopCounts = new SemanticObserverBridge.Counts();
        CompilationArtifact loopOn = analyze(loop, true, loopCounts);
        samePrimaries(loopOff, loopOn, 2);
        require(loopOn.diagnostics().stream().allMatch(d -> d.notes().size() <= 8)
                        && loopCounts.collectorHighWater() <= 4096
                        && loopCounts.snapshotHighWater() <= 2048
                        && !loopCounts.invocationStopped(),
                "loop cleanup exceeded collector or output limits: "
                        + loopOn.diagnostics());
    }

    static void forcedExhaustion() {
        SourceFile source = SourceFile.of("LimitedJoins.iron",
                nested("LimitedJoins", 5, true, false));
        CompilationArtifact off = analyze(source, false, null);
        int[][] limits = {{40, 2048, 1048576}, {4096, 1, 1048576},
                {4096, 2048, 40}};
        for (int index = 0; index < limits.length; index++) {
            int[] limit = limits[index];
            int selected = index;
            SemanticObserverBridge.Counts counts = new SemanticObserverBridge.Counts();
            CompilationArtifact on = limited(source, counts, limit);
            CompilationArtifact repeated = limited(source,
                    new SemanticObserverBridge.Counts(), limit);
            samePrimaries(off, on, 1);
            samePrimaries(off, repeated, 1);
            require(counts.collectorHighWater() <= limit[0]
                            && counts.snapshotHighWater() <= limit[1]
                            && counts.invocationHighWater() <= limit[2]
                            && (index == 2 ? counts.invocationStopped()
                            : counts.localTruncated() && !counts.invocationStopped())
                            && on.diagnostics().getFirst().notes().size() <= 8
                            && explicitOmission(on.diagnostics().getFirst())
                            && on.diagnostics().getFirst().notes().stream().anyMatch(note ->
                            note.message().startsWith(selected == 2
                                    ? "the invocation evidence storage limit was reached"
                                    : "the function evidence storage limit was reached"))
                            && truthfulOmissions(on.diagnostics().getFirst())
                            && noteKeys(on).equals(noteKeys(repeated)),
                    "forced limit changed safety or claimed complete paths: "
                            + on.diagnostics());
        }

        SourceFile cleanup = SourceFile.of("LimitedCleanup.iron",
                nested("LimitedCleanup", 5, true, true));
        CompilationArtifact cleanupOff = analyze(cleanup, false, null);
        CompilationArtifact cleanupOn = limited(cleanup,
                new SemanticObserverBridge.Counts(), limits[0]);
        CompilationArtifact cleanupAgain = limited(cleanup,
                new SemanticObserverBridge.Counts(), limits[0]);
        samePrimaries(cleanupOff, cleanupOn, 3);
        samePrimaries(cleanupOff, cleanupAgain, 3);
        require(noteKeys(cleanupOn).equals(noteKeys(cleanupAgain))
                        && cleanupOn.diagnostics().stream().allMatch(d ->
                        d.notes().size() <= 8 && explicitOmission(d)
                        && truthfulOmissions(d)
                        && d.notes().getLast().message().startsWith(
                        "this cleanup is checked for ")),
                "forced cleanup cap lost a copy or selected unstable notes: "
                        + cleanupOn.diagnostics());
    }

    private static String nested(String name, int depth, boolean publish,
                                 boolean cleanup) {
        int leaves = 1 << depth;
        StringBuilder source = new StringBuilder("class ").append(name).append(" {\n");
        for (int field = 0; field < leaves; field++) {
            source.append("  static byte[] f").append(field).append(";\n");
        }
        if (cleanup) source.append("  static void work() { }\n");
        source.append("  static void check(");
        for (int level = 0; level < depth; level++) {
            if (level > 0) source.append(", ");
            source.append("boolean b").append(level);
        }
        if (cleanup) source.append(", boolean leave");
        source.append(") {\n    byte[] data = new byte[16];\n");
        if (cleanup) source.append("    defer free data;\n");
        appendTree(source, 0, depth, 0, publish);
        if (cleanup) source.append("    work();\n    if (leave) return;\n    work();\n");
        else source.append("    free data;\n");
        source.append("  }\n");
        if (!publish && !cleanup) {
            source.append("  public static int main(String[] args) {\n    check(");
            for (int level = 0; level < depth; level++) {
                if (level > 0) source.append(", ");
                source.append("false");
            }
            source.append(");\n    return 0;\n  }\n");
        }
        return source.append("}\n").toString();
    }

    private static void appendTree(StringBuilder source, int level, int depth,
                                   int leaf, boolean publish) {
        if (level == depth) {
            source.append("    ");
            if (publish) source.append("f").append(leaf).append(" = data;");
            else source.append("int marker").append(leaf).append(" = 0;");
            source.append('\n');
            return;
        }
        source.append("    if (b").append(level).append(") {\n");
        appendTree(source, level + 1, depth, leaf, publish);
        source.append("    } else {\n");
        appendTree(source, level + 1, depth,
                leaf + (1 << (depth - level - 1)), publish);
        source.append("    }\n");
    }

    private static String sequential(String name, int joins) {
        StringBuilder source = new StringBuilder("class ").append(name).append(" {\n");
        for (int index = 0; index < joins * 2; index++) {
            source.append("  static byte[] f").append(index).append(";\n");
        }
        source.append("  static void check(boolean choice) {\n");
        for (int index = 0; index < joins; index++) {
            source.append("    byte[] data").append(index).append(" = new byte[16];\n")
                    .append("    if (choice) f").append(index * 2)
                    .append(" = data").append(index).append(";\n")
                    .append("    else f").append(index * 2 + 1)
                    .append(" = data").append(index).append(";\n")
                    .append("    free data").append(index).append(";\n");
        }
        return source.append("  }\n}\n").toString();
    }

    private static String present(String name, int allocations) {
        StringBuilder source = new StringBuilder("class ").append(name)
                .append(" {\n  static void check(boolean choice) {\n");
        for (int index = 0; index < allocations; index++) {
            source.append("    Object item").append(index).append(" = new Object();\n");
        }
        source.append("    if (choice) { int mark = 1; }\n")
                .append("    else { int mark = 2; }\n");
        for (int index = 0; index < allocations; index++) {
            source.append("    free item").append(index).append(";\n");
        }
        return source.append("  }\n}\n").toString();
    }

    private static CompilationArtifact analyze(SourceFile source, boolean explain,
                                               SemanticObserverBridge.Counts counts) {
        return new CompilerPipeline(UnfreedMode.OFF, explain,
                counts == null ? null : (mode, paths, enabled) ->
                        SemanticObserverBridge.create(mode, paths, enabled,
                                counts, source.path())).analyze(List.of(source));
    }

    private static CompilationArtifact compile(SourceFile source, boolean explain) {
        return new CompilerPipeline(UnfreedMode.OFF, explain, null)
                .compile(List.of(source));
    }

    private static CompilationArtifact limited(SourceFile source,
                                               SemanticObserverBridge.Counts counts,
                                               int[] limit) {
        return new CompilerPipeline(UnfreedMode.OFF, true,
                (mode, paths, explain) -> SemanticObserverBridge.createWithLimits(
                        mode, paths, explain, counts, source.path(),
                        limit[0], limit[1], limit[2])).analyze(List.of(source));
    }

    private static boolean truthfulOmissions(Diagnostic diagnostic) {
        boolean omitted = diagnostic.notes().stream().anyMatch(note ->
                note.message().contains("omitted") || note.message().contains("unavailable")
                        || note.message().contains("did not retain")
                        || note.message().contains("limit"));
        return !omitted || diagnostic.notes().stream().noneMatch(note ->
                note.message().contains("on all incoming paths"));
    }

    private static boolean explicitOmission(Diagnostic diagnostic) {
        return diagnostic.notes().stream().anyMatch(note ->
                note.message().contains("omitted")
                        || note.message().contains("storage limit")
                        || note.message().contains("source evidence was unavailable"));
    }

    private static void samePrimaries(CompilationArtifact off,
                                      CompilationArtifact on, int count) {
        require(!off.valid() && !on.valid()
                        && off.diagnostics().size() == count
                        && on.diagnostics().size() == count
                        && off.program().isEmpty() && on.program().isEmpty()
                        && off.llvmIr().isEmpty() && on.llvmIr().isEmpty(),
                "control-flow stress changed outcome or primary count: " + on.diagnostics());
        for (int index = 0; index < count; index++) {
            Diagnostic before = off.diagnostics().get(index);
            Diagnostic after = on.diagnostics().get(index);
            require(before.message().equals(after.message())
                            && before.span().equals(after.span())
                            && before.severity() == after.severity()
                            && before.source().path().equals(after.source().path()),
                    "control-flow stress changed a primary: " + after);
        }
    }

    private static List<String> noteKeys(CompilationArtifact artifact) {
        List<String> keys = new ArrayList<>();
        artifact.diagnostics().forEach(diagnostic -> diagnostic.notes().forEach(note ->
                keys.add(note.message() + "@" + (note.source() == null ? ""
                        : note.source().path()) + ":" + note.span())));
        return keys;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
