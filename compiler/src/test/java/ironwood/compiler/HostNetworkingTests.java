// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

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
        check(source, true, "query roots, mutable lists, cursors and copied IPv6 graph");
        check(source.replace("free combined;", "free second; free combined;"), false,
                "caller-added entry retains its separate query owner");
        check(source.replaceFirst("free entries;", "free retained; free entries;"), false, "list entry cannot be freed");
        check(source.replace("free cursor;", "free owner; free cursor;"), false, "cursor retains owner");
        check(source.replace("free children;", "free child; free children;"), false, "child remains a borrow");
        check(source.replace("if (retained.getNetworkPrefixLength() != 64)",
                "free owner; if (retained.getNetworkPrefixLength() != 64)"), false, "entry outlives list, not query");
        check(source.replace("if (scope.getIndex() != 7", "free scope; if (scope.getIndex() != 7"),
                false, "scoped interface belongs to copied address");
        check(source.replace("if (scope.getIndex() != 7", "free copy; if (scope.getIndex() != 7"),
                false, "scope cannot outlive address owner");
        check(source.replace("if (owner.getIndex() != 7", "free owner.getName(); if (owner.getIndex() != 7"),
                false, "name remains borrowed");
        check(source.replace("class HostControlled {", "class HostControlled { static InetAddress saved;")
                .replace("address = cursor.nextElement();", "address = cursor.nextElement(); saved = address;"),
                false, "published cursor result prevents query reclamation");
        String binding = Files.readString(Path.of("stdlib/src/main/ironwood/ironwood/net/InterfaceAddress.iron"));
        for (String invalid : List.of(
                binding.replace("public class InterfaceAddress {",
                        "public class InterfaceAddress { static InterfaceSnapshot published;")
                    .replace("this.snapshot = snapshot;", "this.snapshot = snapshot; published = snapshot;"),
                binding.replace("    NetworkInterface scopeInterface() {",
                        "    InterfaceSnapshot revealStorage() { return this.snapshot; }\n    NetworkInterface scopeInterface() {"),
                binding.replace("public class InterfaceAddress {",
                        "public class InterfaceAddress { static InterfaceAddress published;")
                    .replace("this.record = record;", "this.record = record; published = this;"))) {
            CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR).compile(List.of(
                    SourceFile.of("test/Controlled.iron", source), SourceFile.of("test/InterfaceAddress.iron", invalid)));
            if (result.successful() || result.diagnostics().stream().noneMatch(d -> d.isError()
                    && (d.message().contains("free") || d.message().contains("owned elements"))))
                throw new AssertionError("contained array element published its graph: " + result.diagnostics());
        }
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
        String source = """
                import ironwood.util.Enumeration;
                import ironwood.util.Iterator;
                class Values<E extends Object> implements Enumeration<E> {
                    @Override public boolean hasMoreElements() { return false; }
                    @Override public E nextElement() { return null; }
                }
                class Main {
                    public static int main(String[] args) {
                        Values<String> values = new Values<String>();
                        Iterator<String> cursor = values.asIterator();
                        boolean empty = !cursor.hasNext();
                        free cursor;
                        free values;
                        return empty ? 0 : 1;
                    }
                }
                """;
        check(source, true, "reference enumeration and inherited default");
        check(source.replace("Values<String>", "Values<int>").replace("Iterator<String>", "Iterator<int>"),
                false, "primitive implementation argument");
        check("import ironwood.util.Enumeration; class Main { Enumeration<int> values; }",
                false, "primitive enumeration use");
        check("package ironwood.util; class Main { EnumerationIterator<int> values; }",
                false, "primitive private adapter use");
    }

    static void deterministicContracts() throws Exception {
        Process process = new ProcessBuilder("python3", "scripts/test-networking-m3.py").inheritIO().start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Host networking driver exceeded five minutes");
        }
        if (process.exitValue() != 0) throw new AssertionError("Host networking driver exit " + process.exitValue());
    }

    private static CompilationArtifact check(String source, boolean expected, String description) {
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d -> d.isError()))
            throw new AssertionError(description + ": " + result.diagnostics());
        return result;
    }
}
