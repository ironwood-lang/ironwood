// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.ir.IrTcpInstruction;
import ironwood.compiler.ir.IrType;
import java.nio.file.Files;
import java.nio.file.Path;

final class NetworkAddressTests {
    private NetworkAddressTests() {}

    static void typedResolver() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tcp_addresses/Controlled.iron"));
        CompilationArtifact result = new CompilerPipeline()
                .compile(SourceFile.of("test/Controlled.iron", source));
        if (!result.successful()) throw new AssertionError(result.diagnostics());
        var operations = result.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream()).flatMap(block -> block.instructions().stream())
                .filter(IrTcpInstruction.class::isInstance).map(IrTcpInstruction.class::cast).toList();
        for (var operation : java.util.List.of(IrTcpInstruction.Operation.RESOLVE_START,
                IrTcpInstruction.Operation.RESOLVE_ADDRESS, IrTcpInstruction.Operation.RESOLVE_RELEASE,
                IrTcpInstruction.Operation.REVERSE_NAME, IrTcpInstruction.Operation.LOCAL_NAME,
                IrTcpInstruction.Operation.SCOPE_ID)) {
            if (operations.stream().noneMatch(value -> value.operation() == operation)) {
                throw new AssertionError("Missing typed address operation " + operation);
            }
        }
        var start = operations.stream().filter(value -> value.operation() == IrTcpInstruction.Operation.RESOLVE_START)
                .findFirst().orElseThrow();
        if (!start.outputFields().get(0).type().equals(IrType.I64)
                || !start.outputFields().get(1).type().equals(IrType.I32)) {
            throw new AssertionError("Resolver handle/count lost native widths");
        }
        String llvm = result.llvmIr().orElseThrow();
        if (!llvm.contains("@ironwood_tcp_resolve_start(ptr, ptr, ptr)")
                || !llvm.contains("@ironwood_tcp_resolve_release(i64)")) {
            throw new AssertionError("Resolver native ABI lost output pointers or handle width");
        }
    }

    static void ownership() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tcp_addresses/Ownership.iron"));
        check(source, true, "resolver results and names form shared managed graphs");
        check(source.replace("values[index] = null;", ""), true, "attached resolver results remain legal");
        for (String getter : java.util.List.of("getHostName", "getCanonicalHostName")) {
            check("import ironwood.net.*; class Main { static String use(InetAddress address) throws Exception { String name = address."
                    + getter + "(); ironwood.lang.Memory.collect(); return name; } }", true, "rooted resolver name");
        }
    }

    static void copyingConstructors() {
        String source = "class Copy {\n    private final String text;\n    static Object published;\n    Copy(String input) { this.text = new String(input); }\n    Copy(Object input) { this.text = new String(\"other\"); }\n    \n}\nclass Main {\n    static Copy copy(String text) { return new Copy(text); }\n    public static int main(String[] args) {\n        String text = new String(\"input\");\n        Copy result = copy(text);\n        ;\n        ;\n        return 0;\n    }\n}\n";
        check(source, true, "copying overloaded constructor through factory");
        check(source.replace("this.text = new String(input);", "this.text = new String(input); Copy.published = input;"),
                true, "constructor publishes input");
        check(source.replace("this.text = new String(input);", "this.text = input;"),
                true, "constructor retains input");
        check(source.replace("this.text = new String(\"other\");", "this.text = new String(\"other\"); Copy.published = input;"),
                true, "unselected retaining overload does not poison typed target");
        check(source.replace("new Copy(text)", "new Copy((Object) text)")
                        .replace("this.text = new String(\"other\");", "this.text = new String(\"other\"); Copy.published = input;"),
                true, "selected retaining overload");
    }

    private static void check(String source, boolean expected, String description) {
        CompilationArtifact result = new CompilerPipeline()
                .compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d ->
                d.isError())) {
            throw new AssertionError(description + ": " + result.diagnostics());
        }
    }
}
