// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Verified SDK inputs for direct C fault probes; no compiler root-map claim. */
public final class RuntimeHarnessBuild {
    private RuntimeHarnessBuild() {}

    public static void build(LlvmToolchain tools, String fixture, Path directory, Path executable) throws Exception {
        var runtime = RuntimeLibrary.discover();
        if (!runtime.successful()) throw new AssertionError(runtime.error());
        Path distribution = runtime.source().orElseThrow().getParent().getParent().getParent();
        var gc = GcDependency.discover(distribution, tools);
        var flags = new ArrayList<>(gc.compileFlags());
        flags.add("-I" + gc.home().resolve("include"));
        flags.addAll(List.of("-ffunction-sections", "-fdata-sections", "-fvisibility=hidden"));
        var objects = new ArrayList<String>();
        for (String source : List.of("integration-tests/runtime/" + fixture, "runtime/src/ironwood_case.c")) {
            Path object = directory.resolve(objects.isEmpty() ? "harness.o" : "case.o");
            var command = new ArrayList<>(List.of(tools.clang().toString(), "-std=c11", "-O3"));
            command.addAll(flags);
            command.addAll(List.of("-c", distribution.resolve(source).toString(), "-o", object.toString()));
            run(directory, command, "compile-" + objects.size());
            objects.add(object.toString());
        }
        var link = new ArrayList<>(List.of(tools.clang().toString(), "--driver-mode=g++"));
        link.addAll(objects);
        link.add(System.getProperty("os.name").startsWith("Mac") ? "-Wl,-dead_strip" : "-Wl,--gc-sections");
        link.addAll(gc.linkFlags());
        link.addAll(List.of("-o", executable.toString()));
        run(directory, link, "link");
    }

    private static void run(Path directory, List<String> command, String name) throws Exception {
        Path log = directory.resolve(name + ".log");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("native harness timeout: " + name);
        }
        if (process.exitValue() != 0) throw new AssertionError("native harness " + name + ": " + Files.readString(log));
    }
}
