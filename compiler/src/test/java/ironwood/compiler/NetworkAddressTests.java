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
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR)
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
        check(source, true, "copied DNS inputs, detached elements and endpoint names");
        check(source.replace("values[index] = null;", ""), false, "attached resolver result");
        check(source.replace("free value;", "free value; value.hashCode();"), false, "reclaimed address");
        check(source.replace("free unresolved;", "free borrowed; free unresolved;"), false, "borrowed name");
        check(source.replace("free unresolved;", "free unresolved; borrowed.length();"), false, "name after owner free");
        check(source.replace("free unresolved;", "free unresolved; unresolved.getHostName();"), false, "endpoint after free");
        for (String getter : java.util.List.of("getHostName", "getCanonicalHostName")) {
            String borrowed = """
                    import ironwood.net.*;
                    class Main {
                        public static int main(String[] args) throws UnknownHostException {
                            InetAddress address = InetAddress.getByName("127.0.0.42");
                            String name = address.GETTER();
                            name.length();
                            free address;
                            return 0;
                        }
                    }
                    """.replace("GETTER", getter);
            check(borrowed, true, getter + " borrowed until owner cleanup");
            check(borrowed.replace("free address;", "free name; free address;"), false, getter + " independent free");
            check(borrowed.replace("free address;", "free address; name.length();"), false, getter + " use after owner free");
        }
    }

    static void copyingConstructors() {
        String source = """
                class Copy {
                    private final String text;
                    static Object published;
                    Copy(String input) { this.text = new String(input); }
                    Copy(Object input) { this.text = new String("other"); }
                    destructor { free this.text; }
                }
                class Main {
                    static Copy copy(String text) { return new Copy(text); }
                    public static int main(String[] args) {
                        String text = new String("input");
                        Copy result = copy(text);
                        free text;
                        free result;
                        return 0;
                    }
                }
                """;
        check(source, true, "copying overloaded constructor through factory");
        check(source.replace("this.text = new String(input);", "this.text = new String(input); Copy.published = input;"),
                false, "constructor publishes input");
        check(source.replace("this.text = new String(input);", "this.text = input;"),
                false, "constructor retains input");
        check(source.replace("this.text = new String(\"other\");", "this.text = new String(\"other\"); Copy.published = input;"),
                true, "unselected retaining overload does not poison typed target");
        check(source.replace("new Copy(text)", "new Copy((Object) text)")
                        .replace("this.text = new String(\"other\");", "this.text = new String(\"other\"); Copy.published = input;"),
                false, "selected retaining overload");
    }

    private static void check(String source, boolean expected, String description) {
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR)
                .compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d ->
                d.isError() && d.message().contains("free"))) {
            throw new AssertionError(description + ": " + result.diagnostics());
        }
    }
}
