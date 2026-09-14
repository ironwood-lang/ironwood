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
        accept(source, "default TCP lifecycle and independent results");
        for (String operation : List.of("free input;", "free accepted; input.read();",
                "free accepted.getInetAddress();", "free accepted.supportedOptions();",
                "InetAddress view = accepted.getInetAddress(); free accepted; view.hashCode();")) {
            reject(source.replace("if (input.read() != 42)", operation + " if (input.read() != 42)"),
                    "borrow lifetime: " + operation);
        }
        reject(source.replace("StandardSocketOptions.TCP_NODELAY, true",
                "StandardSocketOptions.TCP_NODELAY, 1"), "wrong boolean value");
        reject(source.replace("StandardSocketOptions.SO_SNDBUF, 32768",
                "StandardSocketOptions.SO_SNDBUF, true"), "wrong integer value");
        reject(source.replace("new Socket()", "new Socket(false)"), "absent UDP constructor");
        reject(source.replace("client.getTcpNoDelay()", "client.getOption(1)"), "absent boxed option protocol");
        reject(source.replace("free accepted;", "free accepted; accepted.getLocalAddress();"),
                "accepted result use after reclamation");
    }

    static void failedFreshAcquisition() {
        String source = """
                class Value { int value; }
                class Main {
                    static Value create(boolean fail) {
                        Value result = new Value();
                        try {
                            if (fail) throw new IllegalArgumentException();
                        } catch (RuntimeException failure) {
                            free result;
                            throw failure;
                        }
                        return result;
                    }
                    public static int main(String[] args) {
                        Value result = create(false);
                        free result;
                        return 0;
                    }
                }
                """;
        accept(source, "failed acquisition cleanup preserves fresh success");
        reject(source.replace("return result;", "free result; return result;"),
                "returning a freed fresh allocation");
        reject(source.replace("class Main {", "class Main { static Value published;")
                .replace("return result;", "Main.published = result; return result;"),
                "published fresh result");
        reject(source.replace("Value result = create(false);", "Value result = create(false); release(result);")
                .replace("static Value create", "static void release(Value value) { free value; } static Value create"),
                "callee reclamation remains an input effect");
    }

    static void extensionEffects() throws Exception {
        String source = Files.readString(Path.of("integration-tests/cases/tcp_delegation/Main.iron"));
        accept(source, "injected, factory, buffered and protected-accept delegation");
        reject(source.replace("class ObservingInput extends InputStream {",
                        "class ObservingInput extends InputStream { static byte[] saved;")
                .replace("return this.delegate.read(bytes, offset, length);",
                        "ObservingInput.saved = bytes; return this.delegate.read(bytes, offset, length);"),
                "retaining stream override");
        reject(source.replace("class ObservingImpl extends SocketImpl {",
                        "class ObservingImpl extends SocketImpl { static InputStream published;")
                .replace("return this.input;", "ObservingImpl.published = this.input; return this.input;"),
                "published implementation view");
        reject(source.replace("class TestFactory implements SocketImplFactory {",
                        "class TestFactory implements SocketImplFactory { private SocketImpl cached = new ObservingImpl(false);")
                .replace("return new ObservingImpl(this.listener);", "return this.cached;"),
                "cached factory result cannot be adopted");
        reject(source.replace("class TestFactory implements SocketImplFactory {",
                        "class TestFactory implements SocketImplFactory { static SocketImpl published;")
                .replace("return new ObservingImpl(this.listener);",
                        "SocketImpl result = new ObservingImpl(this.listener); TestFactory.published = result; return result;"),
                "published factory result cannot be adopted");
        reject(source.replace("Socket.setSocketImplFactory(new TestFactory(false));",
                        "TestFactory retained = new TestFactory(false); Socket.setSocketImplFactory(retained); free retained;"),
                "registered factory cannot be reclaimed");
        reject(source.replace("free server;", "free listening; free server;"),
                "inherited constructor borrow remains live until facade reclamation");
        reject(source.replace("class TestFactory implements SocketImplFactory {",
                        "class TestFactory implements SocketImplFactory { private byte[] captured; "
                        + "TestFactory(boolean listener, byte[] captured) { this(listener); this.captured = captured; }")
                .replace("Socket.setSocketImplFactory(new TestFactory(false));",
                        "byte[] captured = new byte[8]; TestFactory retained = new TestFactory(false, captured); "
                        + "Socket.setSocketImplFactory(retained); free captured;"),
                "registered factory retains its caller-owned capture");
        String borrowed = Files.readString(Path.of("integration-tests/cases/tcp_delegation/Borrowed.iron"));
        accept(borrowed, "custom implementation borrows a caller-owned native delegate");
        reject(borrowed.replace("free clientImpl;", "free connecting; free clientImpl;"),
                "borrowed native delegate cannot be freed before custom implementation");
        reject(borrowed.replace("free acceptedImpl;", "free receiving.getFileDescriptor(); free acceptedImpl;"),
                "opaque descriptor remains borrowed");
        String inventory = "import ironwood.ds.*; import ironwood.util.Iterator;\n"
                + source.replace("class Main {", "class DelegationDemonstration {")
                        .replace("public static int main(", "public static int demonstration(")
                + Files.readString(Path.of("integration-tests/cases/tcp_delegation/Inventory.iron"))
                        .replaceAll("(?m)^import .*;\\R", "");
        accept(inventory, "custom inventory, backing-list and token lifetimes");
        for (String operation : List.of("free inventory;", "free iterator;",
                "free implementation; inventory.size();")) {
            reject(inventory.replace("socket.close();", operation + " socket.close();"), operation);
        }
        reject(inventory.replace("free view;", "free backing; free view;"), "view outlives backing list");
        reject(inventory.replace("free view;", "free token; free view;"), "token outlives retaining list");
        reject(inventory.replace("free implementation;", "free implementation; inventory.size();"),
                "inventory remains dependent after both facade and implementation reclamation");
        reject(inventory.replace("free this.inventory;\n        free this.backing;",
                "free this.backing;\n        free this.inventory;"), "view destructor order");
        reject(inventory.replace("private final ArrayList<SocketOptionDescriptor> backing;\n    private final UnmodifiableList<SocketOptionDescriptor> inventory;",
                "private final UnmodifiableList<SocketOptionDescriptor> inventory;\n    private final ArrayList<SocketOptionDescriptor> backing;"),
                "view constructor rollback order");
        reject(inventory.replace("class InventoryImpl extends ObservingImpl {",
                        "class InventoryImpl extends ObservingImpl { static ArrayList<SocketOptionDescriptor> published;")
                .replace("this.backing.add(StandardSocketOptions.TCP_NODELAY);",
                        "InventoryImpl.published = this.backing; this.backing.add(StandardSocketOptions.TCP_NODELAY);"),
                "published inventory backing list");
    }

    private static CompilationArtifact compile(String source) {
        return new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", source));
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
