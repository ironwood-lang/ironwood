// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Child processes share the enclosing RSS guard and have an independent deadline. */
final class GcTestProcesses {
    private GcTestProcesses() {}

    static String run(Path directory, List<String> command, String name) throws Exception {
        return run(directory, command, name, 0);
    }

    private static String run(Path directory, List<String> command, String name, int expected) throws Exception {
        Files.createDirectories(directory);
        Path log = directory.resolve(name + ".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("timed out: " + command);
        }
        String output = Files.readString(log);
        if (process.exitValue() != expected) throw new AssertionError(command + ": " + output);
        return output;
    }

    static void command(Path directory, String name, int expected, String[] arguments) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", Path.of("compiler/build/classes").toAbsolutePath().toString(), "ironwood.compiler.Main"));
        command.addAll(List.of(arguments));
        run(directory, command, name, expected);
    }
}
