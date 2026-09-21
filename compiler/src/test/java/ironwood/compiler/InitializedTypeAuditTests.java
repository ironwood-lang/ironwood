// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class InitializedTypeAuditTests {
    private static final String SUFFIX = ".$initialized.";

    private InitializedTypeAuditTests() {}

    static void controlFlow() throws Exception {
        IrProgram raw = analyze(fixture("control"));
        IrProgram optimized = InitializedTypeSpecializer.specialize(raw);
        for (String name : List.of("control", "dispatch")) {
            IrFunction original = named(raw, name);
            IrFunction guarded = named(optimized, name);
            require(!guards(guarded).isEmpty(), name + " was skipped");
            require(guarded.blocks().containsAll(original.blocks()), name + " fallback changed");
            checkCfg(guarded);
            Set<Integer> originalValues = values(original.blocks());
            Set<Integer> copiedValues = values(guarded.blocks().stream().filter(b -> !original.blocks().contains(b)).toList());
            original.parameters().forEach(p -> originalValues.remove(p.value().id()));
            require(originalValues.stream().noneMatch(copiedValues::contains), name + " reused fallback SSA locals");
        }
        IrFunction control = named(optimized, "control");
        List<IrBasicBlock> fast = control.blocks().stream()
                .filter(b -> b.label().startsWith("$initialized.body.")).toList();
        require(fast.stream().anyMatch(b -> b.terminator() instanceof IrSwitchTerminator), "missing fast switch");
        require(fast.stream().anyMatch(b -> b.terminator() instanceof IrInvokeTerminator), "missing fast invoke");
        require(fast.stream().flatMap(b -> b.instructions().stream()).anyMatch(IrPhiInstruction.class::isInstance),
                "missing fast phi joins");
        require(fast.stream().flatMap(b -> b.instructions().stream()).anyMatch(IrFreeInstruction.class::isInstance),
                "missing fast deferred free");
        require(fast.stream().flatMap(InitializedTypeAuditTests::operations)
                .anyMatch(i -> i instanceof IrStaticFieldLoadInstruction l && l.field().ownerClass().equals("Mutable")),
                "mutable alias reload was substituted");
        IrFunction dispatch = named(optimized, "dispatch");
        require(guards(dispatch).equals(Set.of("Mode", "Main")), "indirect target leaked state facts: " + guards(dispatch));
        require(operations(dispatch).anyMatch(IrInterfaceCallInstruction.class::isInstance), "dispatch was not indirect");
        IrFunction late = optimized.functions().stream().filter(f -> f.ownerClass().equals("Late")
                && f.sourceName().equals("get")).findFirst().orElseThrow();
        require(operations(late).anyMatch(i -> i instanceof IrEnsureTypeInitializedInstruction e
                && e.typeName().equals("Hidden")), "indirect target lost its active-use barrier");
        IrProgram pruned = ClosedWorldPruner.prune(optimized);
        require(pruned.functions().contains(late), "indirect target was pruned");
        require(pruned.functions().stream().filter(f -> f.sourceName().equals("value")
                && f.ownerClass().startsWith("Mode$")).count() == 2, "constant bodies were pruned");

        // Exercise the copier on real cleanup/phi operands with deliberately
        // colliding source-label prefixes, then verify exact reversible renaming.
        IrFunction original = named(raw, "control");
        var forward = new IrCfgRenamer(v -> new IrValueReference(v.id() + 100000, v.type(), v.sourceSpan()),
                l -> "$initialized.body." + l);
        var reverse = new IrCfgRenamer(v -> new IrValueReference(v.id() - 100000, v.type(), v.sourceSpan()),
                l -> l.substring("$initialized.body.".length()));
        require(original.blocks().stream().map(forward::block).map(reverse::block).toList().equals(original.blocks()),
                "CFG renaming changed non-name data");
        var labelsOnly = new IrCfgRenamer(v -> v, l -> "$initialized.body." + l);
        IrFunction collision = copy(original, original.blocks().stream().map(labelsOnly::block).toList());
        IrProgram renamed = InitializedTypeSpecializer.specialize(replace(raw, original, collision));
        IrFunction renamedRoot = named(renamed, "control");
        require(!guards(renamedRoot).isEmpty(), "collision case was skipped");
        checkCfg(renamedRoot);

        String safe = fixture("control");
        String unsafe = safe.replace("defer free value;", "defer free value; free value;");
        require(!unsafe.equals(safe), "unsafe mutation missed its cleanup");
        for (UnfreedMode mode : UnfreedMode.values()) {
            var positive = new CompilerPipeline(mode).compile(SourceFile.of("control.iron", safe));
            require(positive.successful(), mode + " safe cleanup: " + positive.diagnostics());
            var negative = new CompilerPipeline(mode).compile(SourceFile.of("control.iron", unsafe));
            require(!negative.valid() && negative.program().isEmpty(), mode + " accepted double cleanup");
        }
    }

    static void groups() throws Exception {
        IrProgram raw = analyze(fixture("graph"));
        IrProgram optimized = InitializedTypeSpecializer.specialize(raw);
        for (String root : List.of("first", "second", "recursive")) {
            require(!guards(named(optimized, root)).isEmpty(), root + " was skipped");
            checkCfg(named(optimized, root));
        }
        require(guards(named(optimized, "first")).equals(Set.of("Flag")), "first guard set");
        require(guards(named(optimized, "second")).equals(Set.of("Flag", "Extra")), "second guard set");
        List<IrFunction> shared = clones(optimized, "shared");
        require(shared.size() == 2, "shared leaf needs one version per independent group");
        require(clones(optimized, "recursive").size() == 1 && clones(optimized, "mutual").size() == 1,
                "recursive group was not bounded");
        for (IrFunction clone : optimized.functions().stream().filter(f -> f.linkageName().contains(SUFFIX)).toList()) {
            require(guards(clone).isEmpty(), "guardless clone has a guard");
            require(operations(clone).noneMatch(IrEnsureTypeInitializedInstruction.class::isInstance),
                    "recursive/shared clone retained an ensure");
            operations(clone).filter(IrCallInstruction.class::isInstance).map(IrCallInstruction.class::cast)
                    .forEach(c -> require(c.targetLinkageName().contains(SUFFIX), "recursive edge repeated a root guard"));
            checkCfg(clone);
        }
        require(ClosedWorldPruner.prune(optimized).functions().containsAll(shared), "shared versions were pruned");

        // Isolate each policy boundary using source graphs, and keep a nearby
        // accepted graph so a globally disabled transform cannot satisfy bounds.
        assertBounded(boundSource(1, 31, 1), true, "32-function group");
        assertBounded(boundSource(1, 32, 1), false, "33-function group");
        assertBounded(boundSource(1, 5, 80), false, "large group of small functions");
        assertBounded(boundSource(20, 0, 50), true, "total copied operations");
        String four = typeSource(4);
        assertBounded(four, true, "four guard types");
        assertBounded(typeSource(5), false, "five guard types");
    }

    private static void assertBounded(String source, boolean expected, String description) {
        IrProgram raw = analyze(source);
        IrProgram optimized = InitializedTypeSpecializer.specialize(raw);
        require((optimized != raw) == expected, description + " unexpected specialization decision");
        int extra = cost(optimized) - cost(raw);
        int guards = optimized.functions().stream().mapToInt(f -> guards(f).size()).sum();
        require(extra - guards * 2 <= Math.min(8192, cost(raw) / 2), description + " exceeded total body budget");
        optimized.functions().forEach(f -> require(guards(f).size() <= 4, description + " exceeded guard budget"));
        if (description.equals("large group of small functions")) {
            require(raw.functions().stream().filter(f -> f.ownerClass().equals("Main"))
                    .allMatch(f -> cost(f) <= 1200), "body bound masked the group bound");
            int groupCost = raw.functions().stream().filter(f -> f.ownerClass().equals("Main")
                    && f.sourceName().startsWith("r0c")).mapToInt(InitializedTypeAuditTests::cost).sum();
            require(groupCost > 4096 && groupCost <= Math.min(8192, cost(raw) / 2),
                    "test did not isolate the group operation bound");
        }
        if (description.equals("total copied operations")) {
            long roots = optimized.functions().stream().filter(f -> !guards(f).isEmpty()).count();
            require(roots > 1 && roots < 20, "total budget did not select a proper subset");
        }
    }

    private static String boundSource(int roots, int callees, int repetitions) {
        StringBuilder text = new StringBuilder("enum Flag { FIRST, SECOND; } class Main {");
        for (int r = 0; r < roots; r++) {
            for (int c = 0; c <= callees; c++) {
                text.append("static int r").append(r).append("c").append(c).append("(int n) { int sum = 0;");
                if (c == 0) text.append("for (int i = 0; i < n; i++) {");
                for (int k = 0; k < repetitions; k++) text.append("sum += Flag.FIRST != Flag.SECOND ? 1 : 0;");
                if (c < callees) text.append("sum += r").append(r).append("c").append(c + 1).append("(n);");
                if (c == 0) text.append("}");
                text.append("return sum; }");
            }
        }
        text.append("public static int main(String[] args) { return 0");
        for (int r = 0; r < roots; r++) text.append(" + r").append(r).append("c0(args.length)");
        return text.append("; } }").toString();
    }

    private static String typeSource(int count) {
        StringBuilder text = new StringBuilder();
        for (int t = 0; t < count; t++) text.append("class T").append(t).append(" { static int value = 1; }");
        text.append("class Main { static int loop(int n) { int sum = 0; for (int i = 0; i < n; i++) {");
        for (int t = 0; t < count; t++) text.append("sum += T").append(t).append(".value + T").append(t).append(".value;");
        return text.append("} return sum; } public static int main(String[] args) { return loop(args.length); } }").toString();
    }

    static void nativeAdversarial() throws Exception {
        runLevels("initialized_specialization_control", List.of("control", "dispatch"), List.of(0, 2, 3), 42, "");
        runLevels("initialized_specialization_graph", List.of("first", "second", "recursive"), List.of(0, 2, 3), 42, "");
    }

    static void nativeLowerLevels() throws Exception {
        runLevels("initialized_specialization", List.of("lazy", "enumValue", "observe", "broken", "ordered", "warmed"),
                List.of(0, 2), 42, "");
        runLevels("initialized_specialization_trace", List.of("loop"), List.of(0, 2), 1,
                "uncaught Ironwood exception: ironwood.lang.IllegalArgumentException: specialized trace\n"
                        + "\tat Main.leaf(initialized_specialization_trace.iron:18)\n"
                        + "\tat Main.loop(initialized_specialization_trace.iron:26)\n"
                        + "\tat Main.main(initialized_specialization_trace.iron:37)\n");
    }

    private static void runLevels(String fixture, List<String> roots, List<Integer> levels, int exit, String error) throws Exception {
        Path source = Path.of("integration-tests/cases/" + fixture + ".iron");
        IrProgram optimized = InitializedTypeSpecializer.specialize(analyze(Files.readString(source)));
        for (String name : roots) require(optimized.functions().stream().anyMatch(f -> f.sourceName().equals(name)
                && !guards(f).isEmpty()), fixture + " skipped " + name);
        Path root = Path.of("integration-tests/target/initialized-audit", fixture).toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli("--unfreed=error", source.toString(), "-d", classes.toString());
        for (int level : levels) {
            Path binary = root.resolve("program-O" + level);
            Path llvm = root.resolve("program-O" + level + ".ll");
            cli("--link", "--unfreed=error", "-cp", classes.toString(), "--main-class", "Main", "-O" + level,
                    "--emit-llvm", llvm.toString(), "-o", binary.toString());
            require(Files.readString(llvm).contains("$initialized"), fixture + " lost specialization on reconstruction");
            Path stdout = root.resolve("O" + level + ".stdout");
            Path stderr = root.resolve("O" + level + ".stderr");
            Process process = new ProcessBuilder(binary.toString()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            int actual = process.waitFor();
            require(actual == exit && Files.readString(stdout).isEmpty() && Files.readString(stderr).equals(error),
                    fixture + " O" + level + " exit " + actual + ": " + Files.readString(stdout) + Files.readString(stderr));
        }
    }

    private static Set<Integer> values(List<IrBasicBlock> blocks) {
        Set<Integer> ids = new HashSet<>();
        var scanner = new IrCfgRenamer(v -> { ids.add(v.id()); return v; }, l -> l);
        blocks.forEach(scanner::block);
        return ids;
    }

    private static void checkCfg(IrFunction function) {
        Set<String> labels = new HashSet<>();
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (IrBasicBlock block : function.blocks()) {
            require(labels.add(block.label()), "duplicate label " + block.label());
            for (String target : successors(block)) predecessors.computeIfAbsent(target, ignored -> new HashSet<>()).add(block.label());
        }
        require(labels.containsAll(predecessors.keySet()), "dangling CFG target");
        for (IrBasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction instanceof IrPhiInstruction phi) {
                    Set<String> incoming = phi.incoming().stream().map(IrPhiIncoming::predecessor).collect(Collectors.toSet());
                    require(incoming.equals(predecessors.getOrDefault(block.label(), Set.of())), "phi edge mismatch in " + block.label());
                }
            }
        }
    }

    private static List<String> successors(IrBasicBlock block) {
        return switch (block.terminator()) {
            case IrJump t -> List.of(t.target());
            case IrBranch t -> List.of(t.trueTarget(), t.falseTarget());
            case IrInvokeTerminator t -> List.of(t.normalTarget(), t.unwindTarget());
            case IrThrowTerminator t -> t.unwindTarget().map(u -> List.of(t.normalTarget(), u)).orElse(List.of());
            case IrSwitchTerminator t -> Stream.concat(Stream.of(t.defaultTarget()), t.cases().stream().map(IrSwitchCase::target)).toList();
            default -> List.of();
        };
    }

    private static String fixture(String suffix) throws Exception {
        return Files.readString(Path.of("integration-tests/cases/initialized_specialization_" + suffix + ".iron"));
    }

    private static IrProgram analyze(String source) {
        var result = new CompilerPipeline(UnfreedMode.OFF).analyze(List.of(SourceFile.of("audit.iron", source)));
        require(result.valid(), "analysis failed: " + result.diagnostics());
        return result.program().orElseThrow();
    }

    private static IrFunction named(IrProgram program, String name) {
        return program.functions().stream().filter(f -> f.ownerClass().equals("Main") && f.sourceName().equals(name)
                && !f.linkageName().contains(SUFFIX)).findFirst().orElseThrow();
    }

    private static List<IrFunction> clones(IrProgram program, String name) {
        return program.functions().stream().filter(f -> f.sourceName().equals(name) && f.linkageName().contains(SUFFIX)).toList();
    }

    private static Set<String> guards(IrFunction function) {
        return operations(function).filter(IrTypeInitializedInstruction.class::isInstance).map(IrTypeInitializedInstruction.class::cast)
                .map(IrTypeInitializedInstruction::typeName).collect(Collectors.toSet());
    }

    private static Stream<IrInstruction> operations(IrFunction function) {
        return function.blocks().stream().flatMap(InitializedTypeAuditTests::operations);
    }

    private static Stream<IrInstruction> operations(IrBasicBlock block) {
        return Stream.concat(block.instructions().stream(), block.terminator() instanceof IrInvokeTerminator i
                ? Stream.of(i.call()) : Stream.empty());
    }

    private static int cost(IrFunction function) {
        return function.blocks().stream().mapToInt(b -> b.instructions().size() + 1).sum();
    }

    private static int cost(IrProgram program) {
        return program.functions().stream().mapToInt(InitializedTypeAuditTests::cost).sum();
    }

    private static IrFunction copy(IrFunction function, List<IrBasicBlock> blocks) {
        return new IrFunction(function.ownerClass(), function.sourceName(), function.linkageName(), function.returnType(),
                function.parameters(), blocks, function.sourceSpan(), function.sourceFileName(), function.kind());
    }

    private static IrProgram replace(IrProgram program, IrFunction original, IrFunction replacement) {
        return new IrProgram(program.moduleName(), program.classes(), program.staticFields(), program.typeInitializations(),
                program.arrayTypes(), program.stringConstants(), program.dispatchSlots(), program.functions().stream()
                .map(f -> f == original ? replacement : f).toList(), program.entryPoint(), program.allocationFailure());
    }

    private static void cli(String... arguments) {
        var errors = new ByteArrayOutputStream();
        int result = Main.run(arguments, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));
        require(result == 0, String.join(" ", arguments) + ": " + errors.toString(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
