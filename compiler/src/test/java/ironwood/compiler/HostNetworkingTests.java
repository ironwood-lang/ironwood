// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.ir.IrTcpInstruction;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class HostNetworkingTests {
    private HostNetworkingTests() {}

    static void ownership() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/host_interfaces/Controlled.iron"));
        check(source, true, "query resources close explicitly; snapshots retain shared names and children");
        check(source.replace("class HostControlled {", "class HostControlled { static InetAddress saved;")
                .replace("address = cursor.nextElement();", "address = cursor.nextElement(); saved = address;"),
                true, "managed cursor result can be published");
    }

    static void typedOperations() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/host_interfaces/Probe.iron"));
        CompilationArtifact result = check(source, true, "typed interface and reachability operations");
        var operations = result.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream()).flatMap(block -> block.instructions().stream())
                .filter(IrTcpInstruction.class::isInstance).map(IrTcpInstruction.class::cast).toList();
        for (var operation : List.of(IrTcpInstruction.Operation.INTERFACES_START,
                IrTcpInstruction.Operation.INTERFACE_NEXT, IrTcpInstruction.Operation.INTERFACE_ADDRESS,
                IrTcpInstruction.Operation.INTERFACES_RELEASE, IrTcpInstruction.Operation.INTERFACE_FLAGS,
                IrTcpInstruction.Operation.INTERFACE_MTU, IrTcpInstruction.Operation.INTERFACE_HARDWARE,
                IrTcpInstruction.Operation.REACHABLE)) {
            if (operations.stream().noneMatch(value -> value.operation() == operation))
                throw new AssertionError("Missing host operation " + operation);
        }
        var start = operations.stream().filter(value -> value.operation() == IrTcpInstruction.Operation.INTERFACES_START)
                .findFirst().orElseThrow();
        if (!start.outputFields().getFirst().type().equals(IrType.I64)
                || !start.outputFields().get(1).type().equals(IrType.I32))
            throw new AssertionError("Interface handle/count widths");
        if (!result.llvmIr().orElseThrow().contains("@ironwood_tcp_interfaces_start(ptr, ptr)"))
            throw new AssertionError("Host native output ABI");
    }

    static void referenceBounds() {
        String source = "import ironwood.util.Enumeration;\nimport ironwood.util.Iterator;\nclass Values<E extends Object> implements Enumeration<E> {\n    @Override public boolean hasMoreElements() { return false; }\n    @Override public E nextElement() { return null; }\n}\nclass Main {\n    public static int main(String[] args) {\n        Values<String> values = new Values<String>();\n        Iterator<String> cursor = values.asIterator();\n        boolean empty = !cursor.hasNext();\n        ;\n        ;\n        return empty ? 0 : 1;\n    }\n}\n";
        check(source, true, "reference enumeration and inherited default");
        check(source.replace("Values<String>", "Values<int>").replace("Iterator<String>", "Iterator<int>"),
                false, "primitive implementation argument");
        check("import ironwood.util.Enumeration; class Main { Enumeration<int> values; }",
                false, "primitive enumeration use");
        check("package ironwood.util; class Main { EnumerationIterator<int> values; }",
                false, "primitive private adapter use");
    }

    static void deterministicContracts() throws Exception {
        var discovery = LlvmToolchain.discover(null);
        if (!discovery.successful()) throw new AssertionError(discovery.error());
        ProcessBuilder builder = new ProcessBuilder("python3", "scripts/test-networking-m3.py").inheritIO();
        builder.environment().put("IRONWOOD_LLVM_HOME", discovery.toolchain().orElseThrow().home().toString());
        Process process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Host networking driver exceeded five minutes");
        }
        if (process.exitValue() != 0) throw new AssertionError("Host networking driver exit " + process.exitValue());
    }

    private static CompilationArtifact check(String source, boolean expected, String description) {
        CompilationArtifact result = new CompilerPipeline().compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d -> d.isError()))
            throw new AssertionError(description + ": " + result.diagnostics());
        return result;
    }
}
