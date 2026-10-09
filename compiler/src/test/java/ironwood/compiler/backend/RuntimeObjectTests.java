// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import ironwood.compiler.Main;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Evidence for omitting the runtime-object cache from the one-link native
 * driver (D269). The Java backend keeps the cache; this test reads it.
 */
public final class RuntimeObjectTests {
    private RuntimeObjectTests() { }

    /**
     * A link prepares each runtime object once under a distinct key, so the
     * first link of a process inserts one entry per object and reuses none;
     * only a second link in the same process hits. Each cached object equals
     * a direct compilation with its key's own command, and two direct
     * compilations are byte-identical, so compiling directly loses nothing.
     */
    @SuppressWarnings("unchecked")
    public static void directCompilation() throws Exception {
        Field field = NativeBackend.class.getDeclaredField("RUNTIME_OBJECTS");
        field.setAccessible(true);
        Map<Object, byte[]> cache = (Map<Object, byte[]>) field.get(null);
        Path root = Files.createTempDirectory("ironwood-runtime-objects-");
        try {
            Path source = root.resolve("Main.iron");
            Files.writeString(source, "class Main { public static int main(String[] args) { return 42; } }\n");
            Path classes = root.resolve("classes");
            compiler(List.of(source.toString(), "-d", classes.toString()));
            synchronized (NativeBackend.class) {
                cache.clear();
            }
            Path first = root.resolve("first");
            compiler(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "-O3", "-o", first.toString()));
            Map<Object, byte[]> entries;
            synchronized (NativeBackend.class) {
                entries = new LinkedHashMap<>(cache);
            }
            Set<Path> sources = new HashSet<>();
            for (Object key : entries.keySet()) sources.add((Path) component(key, "runtimeSource"));
            if (entries.size() != 4 || sources.size() != 4) {
                throw new AssertionError("one link prepared " + entries.size() + " objects from " + sources);
            }
            Path second = root.resolve("second");
            compiler(List.of("--link", "-cp", classes.toString(), "--main-class", "Main", "-O3", "-o", second.toString()));
            synchronized (NativeBackend.class) {
                if (cache.size() != 4 || !cache.keySet().equals(entries.keySet())) {
                    throw new AssertionError("a second link did not reuse the four objects");
                }
            }
            for (Path program : List.of(first, second)) {
                Process process = new ProcessBuilder(program.toString()).start();
                if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 42) {
                    throw new AssertionError(program + " did not exit 42");
                }
            }
            int index = 0;
            for (Map.Entry<Object, byte[]> entry : entries.entrySet()) {
                Object key = entry.getKey();
                List<String> command = new ArrayList<>(List.of(component(key, "clang").toString(), "-std=c11",
                        "-fPIC", ((OptimizationLevel) component(key, "optimizationLevel")).clangArgument(),
                        "-ffunction-sections", "-fdata-sections"));
                command.addAll((List<String>) component(key, "arguments"));
                byte[] previous = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    Path object = root.resolve("direct-" + index + "-" + attempt + ".o");
                    List<String> direct = new ArrayList<>(command);
                    direct.addAll(List.of("-c", component(key, "runtimeSource").toString(), "-o", object.toString()));
                    clang(direct);
                    byte[] bytes = Files.readAllBytes(object);
                    if (!Arrays.equals(bytes, entry.getValue()) || previous != null && !Arrays.equals(bytes, previous)) {
                        throw new AssertionError("direct compilation of " + component(key, "runtimeSource")
                                + " differs from the cached object");
                    }
                    previous = bytes;
                }
                index++;
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Object component(Object key, String name) throws Exception {
        Method accessor = key.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        return accessor.invoke(key);
    }

    private static void compiler(List<String> arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            if (Main.run(arguments.toArray(String[]::new), stream, stream) != 0) {
                throw new AssertionError("compiler run " + arguments + ": " + output.toString(StandardCharsets.UTF_8));
            }
        }
    }

    private static void clang(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(300, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("direct runtime compilation failed: " + output);
        }
    }
}
