// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/** Build-time tools only; generated consumers require neither a JDK nor native tools. */
final class BridgeBuildTools {
    private BridgeBuildTools() {}

    static boolean supportsJdk(int feature) { return feature >= 21 && feature <= 25; }

    static Path requireJdk(String operation) throws IOException {
        if (!supportsJdk(Runtime.version().feature())) {
            throw new IOException("Java Bridge " + operation + " requires JDK 21, 22, 23, 24 or 25; detected " + Runtime.version());
        }
        if (ToolProvider.getSystemJavaCompiler() == null) {
            throw new IOException("Java Bridge " + operation + " requires the selected JDK's javac component: " + System.getProperty("java.home"));
        }
        return Path.of(System.getProperty("java.home"));
    }

    static Path requireJniHeaders(boolean macos) throws IOException {
        Path home = requireJdk("producer");
        for (String name : List.of("include/jni.h", macos ? "include/darwin/jni_md.h" : "include/linux/jni_md.h")) {
            if (!Files.isRegularFile(home.resolve(name)) || !Files.isReadable(home.resolve(name))) {
                throw new IOException("Java Bridge producer requires the selected JDK's JNI header: " + home.resolve(name));
            }
        }
        requireJavadoc();
        return home;
    }

    private static void requireJavadoc() throws IOException {
        requireJdk("Javadoc generation");
        if (ToolProvider.getSystemDocumentationTool() == null) {
            throw new IOException("Java Bridge producer requires the selected JDK's Javadoc component: " + System.getProperty("java.home"));
        }
    }

    static String run(Path directory, String name, List<String> command) throws IOException {
        Path log = directory.resolve(name + ".log");
        var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            int status = process.waitFor();
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (status != 0) throw new IOException(name + " failed (exit " + status + "): " + output);
            return output;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly(); Thread.currentThread().interrupt();
            throw new IOException(name + " interrupted", interrupted);
        }
    }

    static void java(List<Path> sources, Path classes, PrintStream diagnostics) throws IOException {
        requireJdk("Java compilation");
        var compiler = ToolProvider.getSystemJavaCompiler();
        var arguments = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-proc:none", "-Xlint:all", "-Werror",
                "--class-path", "", "--source-path", "", "-d", classes.toString()));
        sources.forEach(path -> arguments.add(path.toString()));
        if (compiler.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)) != 0) {
            throw new IOException("generated Java Bridge facade compilation failed");
        }
    }

    static void javadoc(List<Path> sources, Path classes, Path output, PrintStream diagnostics) throws IOException {
        requireJavadoc();
        var tool = ToolProvider.getSystemDocumentationTool();
        var arguments = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-quiet", "-Xdoclint:none", "-notimestamp",
                "--class-path", classes.toString(), "--source-path", "", "-d", output.toString()));
        sources.forEach(path -> arguments.add(path.toString()));
        if (tool.run(null, diagnostics, diagnostics, arguments.toArray(String[]::new)) != 0) {
            throw new IOException("generated Java Bridge API documentation failed");
        }
    }
}
