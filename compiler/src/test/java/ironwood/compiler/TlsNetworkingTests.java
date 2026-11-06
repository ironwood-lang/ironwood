// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import ironwood.compiler.backend.NativeLinkRequirements;
import ironwood.compiler.ir.IrTlsInstruction;
import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TlsNetworkingTests {
    private TlsNetworkingTests() {}

    static void ownership() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tls_client/Client.iron"));
        check(source, true, "TLS client and buffers are reclaimable");
        String invalid = "import ironwood.net.tls.TlsClient;\nimport ironwood.io.InputStream;\nclass Main {\n    static InputStream saved;\n    public static int main(String[] args) throws Exception {\n        TlsClient client = new TlsClient();\n        client.connect(\"localhost\", 443, 1);\n        InputStream input = client.getInputStream();\n        client.close();\n        ;\n        return input.read();\n    }\n}\n";
        check(invalid, true, "closed source is a live managed object; read reports closed resource");

        check(invalid.replace("client.close();", "saved = input; client.close();")
                .replace("return input.read();", "return 0;"), true, "managed stream can escape independently of explicit resource close");
        for (String method : List.of("setVerifyPeer(false)", "setSession(null)", "setRevocationChecking(true)", "getFileDescriptor()")) {
            check("import ironwood.net.tls.TlsClient; class Main { void use(TlsClient value) { value."
                    + method + "; } }", false, "omitted TLS mechanism " + method);
        }
        check("import ironwood.net.tls.TlsNative; class Main { long value() { return TlsNative.create(null); } }",
                false, "top-level native bridge is absent");
        for (String qualifier : List.of("TlsClient.TlsNative", "TlsClient$TlsNative")) {
            check("package ironwood.net.tls; class Main { long value() { return " + qualifier
                    + ".readByte(1L); } }", false, "same-package caller cannot forge a native handle");
        }
    }

    static void requirements() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tls_client/Client.iron"));
        var result = check(source, true, "typed TLS operations");
        var program = ClosedWorldPruner.prune(result.program().orElseThrow());
        if (!NativeLinkRequirements.from(program).tls()) throw new AssertionError("retained TLS must select dependency");
        var operations = program.functions().stream().flatMap(f -> f.blocks().stream())
                .flatMap(b -> b.instructions().stream()).filter(IrTlsInstruction.class::isInstance)
                .map(IrTlsInstruction.class::cast).map(IrTlsInstruction::operation).toList();
        for (var operation : List.of(IrTlsInstruction.Operation.CREATE, IrTlsInstruction.Operation.ATTACH,
                IrTlsInstruction.Operation.HANDSHAKE, IrTlsInstruction.Operation.READ_BYTES,
                IrTlsInstruction.Operation.WRITE_BYTES, IrTlsInstruction.Operation.CLOSE)) {
            if (!operations.contains(operation)) throw new AssertionError("missing typed operation " + operation);
        }
        String unused = "import ironwood.net.tls.TlsClient;\nclass Main {\n    static void unused() throws Exception {\n        TlsClient client = new TlsClient();\n        try { client.connect(\"localhost\", 443, 1); }\n        finally { client.close(); ; }\n    }\n    public static int main(String[] args) { return 42; }\n}\n";
        var pruned = ClosedWorldPruner.prune(check(unused, true, "unreachable TLS").program().orElseThrow());
        if (NativeLinkRequirements.from(pruned).tls()) throw new AssertionError("pruned TLS selected a dependency");
        for (String body : List.of(
                "static void call() throws Exception { Main value = new Main(); try { value.action(); } finally { ; } } void action() throws Exception { unused(); }",
                "static void call() throws Exception { try { throw new Exception(); } finally { unused(); } }",
                "static final int initialized = initialize(); static int initialize() { try { unused(); } catch (Exception ignored) {} return 42; } static void call() { System.out.println(initialized); }")) {
            String reachable = unused.replace("public static int main", body + " public static int main")
                    .replace("main(String[] args) { return 42; }", "main(String[] args) throws Exception { call(); return 42; }");
            if (!NativeLinkRequirements.from(ClosedWorldPruner.prune(check(reachable, true, "indirect and cleanup TLS")
                    .program().orElseThrow())).tls()) throw new AssertionError("indirect or exceptional TLS omitted");
        }
    }

    static void contracts() throws Exception { driver("scripts/test-networking-m5.py"); }
    static void builds() throws Exception { driver("scripts/test-networking-m5-build.py"); }
    private static void driver(String path) throws Exception {
        var discovery = ironwood.compiler.backend.LlvmToolchain.discover(null);
        if (!discovery.successful()) throw new AssertionError(discovery.error());
        ProcessBuilder builder = new ProcessBuilder("python3", path).inheritIO();
        builder.environment().put("IRONWOOD_LLVM_HOME", discovery.toolchain().orElseThrow().home().toString());
        Process process = builder.start();
        if (!process.waitFor(600, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("TLS driver exceeded ten minutes: " + path);
        }
        if (process.exitValue() != 0) throw new AssertionError("TLS driver exit " + process.exitValue());
    }

    private static CompilationArtifact check(String source, boolean expected, String label) {
        var result = new CompilerPipeline().compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d -> d.isError()))
            throw new AssertionError(label + ": " + result.diagnostics());
        return result;
    }
}
