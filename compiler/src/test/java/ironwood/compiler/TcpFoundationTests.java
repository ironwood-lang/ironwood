// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.source.SourceFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class TcpFoundationTests {
    private TcpFoundationTests() {}

    static void ownershipAndOptions() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tcp_foundation/Main.iron"));
        accept(source, "explicit TCP lifecycle and shared managed results");
        reject(source.replace("StandardSocketOptions.TCP_NODELAY, true", "StandardSocketOptions.TCP_NODELAY, 1"), "wrong boolean value");
        reject(source.replace("StandardSocketOptions.SO_SNDBUF, 32768", "StandardSocketOptions.SO_SNDBUF, true"), "wrong integer value");
        reject(source.replace("new Socket()", "new Socket(false)"), "absent UDP constructor");
        reject(source.replace("client.getTcpNoDelay()", "client.getOption(1)"), "absent boxed option protocol");
    }

    static void failedFreshAcquisition() {
        accept("class Value {} class Main { static Value saved; static Value create(boolean fail) { Value result = new Value(); saved = result; if (fail) throw new IllegalArgumentException(); return result; } }", "managed partial publication and successful result");
    }

    static void extensionEffects() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tcp_delegation/Main.iron"));
        accept(source, "injected, factory, buffered and protected-accept delegation with explicit closure");
        accept(Files.readString(Path.of("integration-tests/cases/tcp_delegation/Borrowed.iron")), "custom implementation retains managed native delegate");
        String inventory = "import ironwood.ds.*; import ironwood.util.Iterator;\n"
                + source.replace("class Main {", "class DelegationDemonstration {").replace("public static int main(", "public static int demonstration(")
                + Files.readString(Path.of("integration-tests/cases/tcp_delegation/Inventory.iron")).replaceAll("(?m)^import .*;\\R", "");
        accept(inventory, "managed option inventory shares its backing list");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline().compile(SourceFile.of("test/Main.iron", source));
    }

    private static void accept(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (!artifact.successful()) throw new AssertionError(description + ": " + artifact.diagnostics());
    }

    private static void reject(String source, String description) {
        CompilationArtifact artifact = compile(source);
        if (artifact.successful()) throw new AssertionError("Accepted " + description);
    }
}
