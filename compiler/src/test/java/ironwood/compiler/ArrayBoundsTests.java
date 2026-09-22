// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ArrayBoundsTests {
    private ArrayBoundsTests() {}

    static void nativeBehavior() throws Exception {
        Path root = Path.of("integration-tests/target/unsigned-array-bounds").toAbsolutePath();
        Files.createDirectories(root);
        Path classes = root.resolve("classes");
        cli("integration-tests/cases/unsigned_array_bounds.iron", "--unfreed=error", "-d", classes.toString());
        Path archive = root.resolve("bounds.ironjar");
        IronJar.create(archive, List.of(classes));
        for (int level : List.of(0, 3)) {
            Path binary = root.resolve("bounds-O" + level);
            Path llvm = root.resolve("bounds-O" + level + ".ll");
            cli("--link", "-cp", (level == 0 ? classes : archive).toString(), "--main-class", "Main",
                    "--unfreed=error", "-O" + level, "--emit-llvm", llvm.toString(), "-o", binary.toString());
            String ir = Files.readString(llvm);
            require(ir.contains("icmp ult i32") && !ir.contains("array.index.nonnegative"),
                    "bounds lowering still has separate signed checks");
            for (List<String> command : List.of(List.of(binary.toString()), List.of(binary.toString(), "nonempty"))) {
                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                require(exit == 42 && text.isEmpty(), command + " exited " + exit + ": " + text);
            }
        }
    }

    private static void cli(String... args) {
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            require(Main.run(args, stream, stream) == 0, output.toString());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
