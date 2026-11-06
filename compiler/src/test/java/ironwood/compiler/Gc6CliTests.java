// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.nio.file.*;

/** Real source discovery and production packaging for a scalar-only API. */
final class Gc6CliTests {
    private Gc6CliTests() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(out);
        Path source = out.resolve("Scalar.iron");
        Files.writeString(source, """
                // SPDX-License-Identifier: MIT OR Apache-2.0
                package gc6cli;
                public final class Scalar {

                    private Scalar() {

                    }

                    public static long mix(long value) {

                        return value + 17;
                    }

                    public static long live() {

                        return System.liveAllocationCount();
                    }
                }
                """);
        var command = new java.util.ArrayList<>(java.util.List.of("--java-bridge", "--export", "gc6cli", "--llvm-home", args[1],
                "-O3", "-o", out.resolve("scalar.jar").toString()));
        if (args.length == 2) command.add(source.toString());
        else {
            Path classes = out.resolve("input-classes");
            if (Main.run(new String[]{"-d", classes.toString(), source.toString()}, System.out, System.err) != 0)
                throw new AssertionError("source class compilation refused");
            Path input = classes;
            if (args[2].equals("archive")) {
                input = out.resolve("input.ironjar");
                try (var files = Files.walk(classes)) {
                    IronJar.create(input, files.filter(p -> p.toString().endsWith(".ironclass")).toList());
                }
            } else if (!args[2].equals("class")) throw new IllegalArgumentException("unknown reconstruction transport");
            command.addAll(java.util.List.of("-cp", input.toString(), "--source-path", out.resolve("no-sources").toString()));
        }
        int result = Main.run(command.toArray(String[]::new), System.out, System.err);
        if (result != 0) throw new AssertionError("scalar producer refused: " + result);
        var paired = BridgePairedArchive.read(out.resolve("scalar.jar"));
        if (!paired.generation().managedHeap()) throw new AssertionError("scalar producer selected a legacy memory model");
        System.out.println("GC6 scalar CLI package passed");
    }
}
