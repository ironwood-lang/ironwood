// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.GcLayoutEmitter;
import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourceFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Exercises final typed layouts without enabling a production GC mode. */
public final class GcLayoutTests {
    static final String NAME = "GC2 exact layouts follow resolved storage and refuse legacy hidden links";
    private GcLayoutTests() {}

    private static IrProgram program() {
        String source = """
                class Parent { byte tag; Object inherited; }
                class Shape extends Parent { boolean flag; double number; Object link; long bits; }
                class Box<T> { T value; Box(T value) { this.value = value; } T get() { return value; } }
                class Outer {
                    class Inner { Object extra; }
                    Object captured(Object value) {
                        class Local { Object read() { return value; } }
                        return new Local();
                    }
                }
                enum Choice { FIRST { Object extra; }, SECOND; Object shared; }
                class _Holder { Object listener; long javaReference; }
                class Main {
                    static int drive() {
                        Box<long> number = new Box<long>(42L);
                        long result = number.get();
                        free number;
                        return (int) result;
                    }
                }
                """;
        var large = new StringBuilder("class Large {");
        for (int index = 0; index < 160; index++) large.append(index % 2 == 0 ? " Object " : " long ")
                .append("slot").append(index).append(';');
        source += large.append('}');
        var result = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("gc2.iron", source)));
        check(result.valid(), result.diagnostics().toString());
        return result.program().orElseThrow();
    }

    static List<IrAllocationLayout> layouts() {
        IrProgram input = program();
        var layouts = new ArrayList<IrAllocationLayout>();
        for (String name : List.of("Parent", "Shape", "Outer$Inner")) {
            var type = input.classes().stream().filter(candidate -> candidate.name().equals(name)).findFirst();
            if (name.equals("Outer$Inner") && type.isEmpty()) {
                type = input.classes().stream().filter(candidate -> candidate.fields().stream()
                        .anyMatch(field -> field.name().equals("<enclosing>"))).findFirst();
            }
            layouts.add(IrAllocationLayout.of(type.orElseThrow()));
        }
        var captures = input.classes().stream().filter(candidate -> candidate.fields().stream()
                .anyMatch(field -> field.name().startsWith("<capture:"))).findFirst().orElseThrow();
        layouts.add(IrAllocationLayout.of(captures));
        var specialized = input.classes().stream().filter(candidate -> candidate.name().startsWith("Box")
                && candidate.fields().stream().anyMatch(field -> field.type().equals(IrType.I64))).findFirst().orElseThrow();
        layouts.add(IrAllocationLayout.of(specialized));
        var generic = input.classes().stream().filter(candidate -> candidate.name().equals("Box")).findFirst().orElseThrow();
        layouts.add(IrAllocationLayout.of(generic));
        layouts.add(IrAllocationLayout.of(input.classes().stream().filter(type -> type.name().equals("Large")).findFirst().orElseThrow()));
        for (var element : List.of(IrType.I1, IrType.I8, IrType.I16, IrType.U16, IrType.I32,
                IrType.I64, IrType.F32, IrType.F64, IrType.reference("Shape"), IrType.array(IrType.I32))) {
            layouts.add(IrAllocationLayout.of(new IrArrayType(IrType.array(element), 0, List.of(), List.of(), false)));
        }
        var string = input.classes().stream().filter(candidate -> candidate.name().equals("ironwood.lang.String")).findFirst().orElseThrow();
        layouts.add(IrAllocationLayout.of(string));
        layouts.add(IrAllocationLayout.of(input.classes().stream().filter(type -> type.name().equals("Choice")).findFirst().orElseThrow()));
        layouts.add(IrAllocationLayout.of(input.classes().stream().filter(type -> type.superclass().orElse("").equals("Choice")).findFirst().orElseThrow()));
        layouts.add(IrAllocationLayout.of(input.classes().stream().filter(type -> type.name().equals("_Holder")).findFirst().orElseThrow()));
        return List.copyOf(layouts);
    }

    static void proofs() throws Exception {
        var layouts = layouts();
        layouts.forEach(IrAllocationLayout::requireSupported);
        check(layouts.get(1).references().size() == 2, "inherited reference missing or primitive traced");
        check(layouts.get(2).references().stream().anyMatch(field -> field.name().equals("<enclosing>")), "hidden enclosing missing");
        check(!layouts.get(3).references().isEmpty(), "hidden capture missing");
        check(layouts.get(4).references().isEmpty() && layouts.get(5).references().size() == 1,
                "primitive specialization reused generic reference map");
        check(layouts.get(6).references().size() == 80, "large map truncated");
        var input = program();
        String drive = input.functions().stream().filter(function -> function.ownerClass().equals("Main")
                && function.sourceName().equals("drive")).findFirst().orElseThrow().linkageName();
        var rooted = new IrProgram(input.moduleName(), input.classes(), input.staticFields(), input.typeInitializations(),
                input.arrayTypes(), input.stringConstants(), input.dispatchSlots(), input.functions(), Optional.empty(),
                input.allocationFailure(), java.util.Set.of(drive));
        var transformed = NativeLinkTransformation.apply(rooted).program();
        check(transformed.classes().size() < input.classes().size(), "fixture did not exercise class pruning");
        for (var type : transformed.classes()) {
            var before = input.classes().stream().filter(candidate -> candidate.name().equals(type.name())).findFirst().orElseThrow();
            check(IrAllocationLayout.of(type).equals(IrAllocationLayout.of(before)), "clone/prune changed physical map");
        }
        var cloneSource = Files.readString(Path.of("integration-tests/cases/enum_argument_specialization.iron"));
        var cloneArtifact = new CompilerPipeline(UnfreedMode.OFF).analyzeForBridge(List.of(SourceFile.of("clones.iron", cloneSource)));
        check(cloneArtifact.valid(), cloneArtifact.diagnostics().toString());
        var cloneInput = cloneArtifact.program().orElseThrow();
        var clones = NativeLinkTransformation.apply(cloneInput).program();
        check(clones.functions().stream().anyMatch(function -> function.linkageName().contains(".$enumarg.")),
                "fixture did not exercise enum argument cloning");
        for (var type : clones.classes()) {
            var before = cloneInput.classes().stream().filter(candidate -> candidate.name().equals(type.name())).findFirst().orElseThrow();
            check(IrAllocationLayout.of(type).equals(IrAllocationLayout.of(before)), "specialized clone changed a map");
        }
        var throwable = input.classes().stream().filter(type -> type.name().equals("ironwood.lang.Throwable")).findFirst().orElseThrow();
        var refusal = IrAllocationLayout.of(throwable);
        check(refusal.shape() == IrAllocationLayout.Shape.REFUSED && refusal.refusal().contains("traceState"), "hidden native links accepted");
        check(IrAllocationLayout.of(input).size() == input.classes().stream().filter(IrClass::isClass).count() + input.arrayTypes().size(),
                "full inventory silently omitted layouts");
        denied(() -> GcLayoutEmitter.c(input));
        denied(() -> GcLayoutEmitter.llvm(input));
        var inheritedFailure = new IrClass("DerivedFailure", IrTypeKind.CLASS, Optional.of(throwable.name()), List.of(),
                throwable.fields(), 0, List.of(), List.of(), throwable.sourceSpan());
        denied(() -> IrAllocationLayout.of(inheritedFailure).requireSupported());
        var view = new IrArrayType(IrType.array(IrType.reference("ironwood.bridge.ByteView")), 0, List.of(), List.of(), false);
        denied(() -> IrAllocationLayout.of(view).requireSupported());
        denied(() -> GcLayoutEmitter.c(List.of(refusal)));
        denied(() -> GcLayoutEmitter.llvm(List.of(refusal)));
        var badField = new IrField("Bad", "hole", IrType.I64, 3, throwable.sourceSpan());
        var bad = new IrClass("Bad", IrTypeKind.CLASS, Optional.empty(), List.of(), List.of(badField), 0, List.of(), List.of(), throwable.sourceSpan());
        denied(() -> IrAllocationLayout.of(bad).requireSupported());
        String ordinary = new LlvmEmitter().emit(input);
        check(ordinary.contains("%\"ironwood.class.Shape\" = type { ptr, i8, ptr, i1, double, ptr, i64 }"),
                "experimental fixture no longer matches production physical storage");
        check(!ordinary.contains("iw_gc_") && !ordinary.contains("iw.layout"), "dormant metadata entered production output");
        check(GcLayoutEmitter.c(layouts).contains("offsetof(struct iw_shape_1, f1)"), "C offsets guessed");
        check(GcLayoutEmitter.llvm(layouts).contains("getelementptr (%iw.shape.1, ptr null, i32 0, i32 2)"), "LLVM offsets guessed");
    }

    public static void main(String[] arguments) throws Exception {
        proofs();
        Path output = Path.of(arguments[0]);
        Files.createDirectories(output);
        var layouts = layouts();
        Files.writeString(output.resolve("layouts.c"), GcLayoutEmitter.c(layouts));
        Files.writeString(output.resolve("layouts.ll"), GcLayoutEmitter.llvm(layouts));
        StringBuilder header = new StringBuilder("// SPDX-License-Identifier: MIT OR Apache-2.0\n");
        for (int index = 0; index < layouts.size(); index++) {
            header.append("extern const struct iw_gc_layout iw_c_layout_").append(index).append(", iw_llvm_layout_").append(index).append(";\n");
        }
        header.append("static const struct iw_gc_layout *const c_layouts[] = {");
        for (int index = 0; index < layouts.size(); index++) header.append("&iw_c_layout_").append(index).append(',');
        header.append("};\nstatic const struct iw_gc_layout *const llvm_layouts[] = {");
        for (int index = 0; index < layouts.size(); index++) header.append("&iw_llvm_layout_").append(index).append(',');
        header.append("};\n");
        Files.writeString(output.resolve("layouts.h"), header);
        Files.writeString(output.resolve("inventory.txt"), layouts.stream().map(layout -> layout.name() + ": " + layout.shape()
                + " refs=" + layout.references().stream().map(field -> field.ownerClass() + "." + field.name()).toList() + "\n")
                .collect(java.util.stream.Collectors.joining()));
    }

    private static void denied(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsupported experimental layout was admitted");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
