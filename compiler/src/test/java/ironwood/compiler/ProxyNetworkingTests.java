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

final class ProxyNetworkingTests {
    private ProxyNetworkingTests() {}

    static void ownership() throws Exception {
        String client = Files.readString(Path.of("integration-tests/cases/proxy_connections/Client.iron"));
        check(client, true, "copied credentials, endpoints, proxies and socket graphs");
        String borrow = """
                import ironwood.net.*;
                class Main {
                    public static int main(String[] args) {
                        InetSocketAddress source = InetSocketAddress.createUnresolved("proxy.invalid", 1080);
                        Proxy proxy = new Proxy(Proxy.Type.SOCKS, source);
                        free source;
                        SocketAddress endpoint = proxy.address();
                        free proxy;
                        return ((InetSocketAddress) endpoint).getPort();
                    }
                }
                """;
        check(borrow, false, "proxy endpoint borrow cannot outlive its owner");
        check(borrow.replace("free proxy;", "free endpoint; free proxy;"), false, "proxy endpoint is not caller-owned");
        String proxy = Files.readString(Path.of("stdlib/src/main/ironwood/ironwood/net/Proxy.iron"));
        for (String invalid : List.of(
                proxy.replace("this.username = copyBytes(username);", "this.username = username;"),
                proxy.replace("this.endpoint = copyEndpoint((InetSocketAddress) address);", "this.endpoint = (InetSocketAddress) address;"),
                proxy.replace("public class Proxy {", "public class Proxy { static byte[] published;")
                        .replace("this.username = copyBytes(username);", "this.username = copyBytes(username); published = this.username;"))) {
            CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR).compile(List.of(
                    SourceFile.of("test/Client.iron", client), SourceFile.of("test/Proxy.iron", invalid)));
            if (result.successful() || result.diagnostics().stream().noneMatch(d -> d.isError() && d.message().contains("free")))
                throw new AssertionError("proxy input retention/publication must invalidate reclamation: " + result.diagnostics());
        }
        check("import ironwood.net.*; class Main { void use(Socket socket) throws Exception { socket.setOption(StandardSocketOptions.TCP_NODELAY, 1); } }",
                false, "proxy addition preserves option/value type pairing");
        for (String type : List.of("Authenticator", "PasswordAuthentication", "ProxySelector"))
            check("import ironwood.net." + type + "; class Main { " + type + " unavailable; }", false, "omitted " + type);
        check("import ironwood.net.*; class Main { void use(Proxy proxy) { proxy.getPassword(); } }", false, "no credential getter");
    }

    static void typedPeek() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/proxy_connections/Client.iron"));
        CompilationArtifact result = check(source, true, "proxy typed native boundary");
        var peeks = result.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream()).flatMap(block -> block.instructions().stream())
                .filter(IrTcpInstruction.class::isInstance).map(IrTcpInstruction.class::cast)
                .filter(instruction -> instruction.operation() == IrTcpInstruction.Operation.PEEK_BYTES).toList();
        if (peeks.isEmpty() || !IrTcpInstruction.Operation.PEEK_BYTES.parameterTypes().equals(
                List.of(IrType.I32, IrType.array(IrType.I8), IrType.I32, IrType.I32)))
            throw new AssertionError("peek must preserve descriptor, byte-array and range types");
        if (!result.llvmIr().orElseThrow().contains("@ironwood_tcp_peek_bytes(i32, ptr, i32, i32)"))
            throw new AssertionError("peek native ABI");
    }

    static void contracts() throws Exception {
        var discovery = LlvmToolchain.discover(null);
        if (!discovery.successful()) throw new AssertionError(discovery.error());
        ProcessBuilder builder = new ProcessBuilder("python3", "scripts/test-networking-m4.py").inheritIO();
        builder.environment().put("IRONWOOD_LLVM_HOME", discovery.toolchain().orElseThrow().home().toString());
        Process process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Proxy driver exceeded five minutes");
        }
        if (process.exitValue() != 0) throw new AssertionError("Proxy driver exit " + process.exitValue());
    }

    private static CompilationArtifact check(String source, boolean expected, String label) {
        CompilationArtifact result = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
        if (result.successful() != expected || !expected && result.diagnostics().stream().noneMatch(d -> d.isError()))
            throw new AssertionError(label + ": " + result.diagnostics());
        return result;
    }
}
