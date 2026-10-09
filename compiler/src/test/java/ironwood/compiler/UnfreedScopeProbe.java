// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.source.SourceFile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Child-JVM probe for UnfreedAllocationTests: compiles one program through the
 * command line and through the in-process pipeline, under the standard library
 * that {@code IRONWOOD_STDLIB_HOME} selects, and prints each diagnostic as
 * "path kind: message file:line:column".
 */
public final class UnfreedScopeProbe {
    private UnfreedScopeProbe() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path program = Path.of(args[1]);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Main.run(new String[]{"--unfreed=" + mode, program.toString(), "-d", args[2]},
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        List<String> lines = errors.toString(StandardCharsets.UTF_8).lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("error: ") || line.startsWith("warning: ")) {
                String location = index + 1 < lines.size() ? lines.get(index + 1).trim() : "";
                System.out.println("command " + line + " "
                        + location.substring(location.lastIndexOf('/') + 1));
            }
        }
        CompilationArtifact artifact = new CompilerPipeline(
                UnfreedMode.valueOf(mode.toUpperCase(Locale.ROOT)))
                .analyze(List.of(SourceFile.read(program)));
        for (Diagnostic diagnostic : artifact.diagnostics()) {
            System.out.println("pipeline " + (diagnostic.isError() ? "error: " : "warning: ")
                    + diagnostic.message() + " " + diagnostic.source().path().getFileName() + ":"
                    + diagnostic.span().start().line() + ":" + diagnostic.span().start().column());
        }
    }
}
