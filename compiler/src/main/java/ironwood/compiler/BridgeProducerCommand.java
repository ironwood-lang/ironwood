// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.bridge.BridgeExportSurface;
import ironwood.compiler.bridge.BridgeCallbackCarrierSources;
import ironwood.compiler.bridge.BridgeListenerProxies;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.diagnostic.DiagnosticFormatter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The public bridge path does not alter ordinary compilation or executable linking. */
final class BridgeProducerCommand {
    private BridgeProducerCommand() {}

    static int run(String[] arguments, PrintStream out, PrintStream err) {
        err.println("error: GC6 boundary: native bridge/shared-library outputs are not qualified for managed roots, cleanup and foreign transitions");
        return 1;
    }

    private record Options(List<Path> inputs, List<Path> sources, List<Path> classes, List<String> exports, Path output,
                           Path llvmHome, OptimizationLevel optimization, UnfreedMode unfreed, boolean explain, List<Path> licenses,
                           boolean criticalCalls) {}

    private static Options parse(String[] arguments) {
        var inputs = new ArrayList<Path>(); var exports = new ArrayList<String>(); var licenses = new ArrayList<Path>();
        List<Path> sources = List.of(Path.of(".")), classes = List.of(Path.of("."));
        Path output = null, llvm = null; var optimization = OptimizationLevel.O0; var unfreed = UnfreedMode.WARN; boolean explain = false;
        boolean criticalCalls = false;
        try {
            for (int i = 0; i < arguments.length; i++) {
                String option = arguments[i];
                switch (option) {
                    case "--java-bridge" -> { }
                    case "--export" -> exports.add(value(arguments, ++i, option));
                    case "-o" -> output = Path.of(value(arguments, ++i, option));
                    case "--license" -> licenses.add(Path.of(value(arguments, ++i, option)));
                    case "--source-path", "-sourcepath" -> sources = paths(value(arguments, ++i, option));
                    case "--class-path", "-classpath", "-cp" -> classes = paths(value(arguments, ++i, option));
                    case "--llvm-home" -> llvm = Path.of(value(arguments, ++i, option));
                    case "-O0" -> optimization = OptimizationLevel.O0;
                    case "-O1" -> optimization = OptimizationLevel.O1;
                    case "-O2" -> optimization = OptimizationLevel.O2;
                    case "-O3" -> optimization = OptimizationLevel.O3;
                    case "--explain-rejected-free" -> throw new IllegalArgumentException("manual reclamation options have been removed");
                    case "--help", "-h" -> throw new IllegalArgumentException("Java Bridge host builds support proved roots/views and bounded retention, permanent objects, enums, copied snapshots and primitive/String APIs on macos-arm64, linux-arm64 and linux-x86_64");
                    default -> {
                        if (option.startsWith("--unfreed=")) throw new IllegalArgumentException("--unfreed has been removed");
                        else if (option.equals("--critical-calls=on")) criticalCalls = true;
                        else if (option.equals("--critical-calls=off")) criticalCalls = false;
                        else if (option.startsWith("-")) throw new IllegalArgumentException("unsupported Java Bridge option: " + option);
                        else {
                            if (!option.endsWith(".iron")) throw new IllegalArgumentException("Java Bridge source inputs require .iron; use -cp for compiled classes/archives");
                            inputs.add(Path.of(option));
                        }
                    }
                }
            }
        } catch (InvalidPathException invalid) { throw new IllegalArgumentException("invalid Java Bridge input/output path", invalid); }
        if (exports.isEmpty()) throw new IllegalArgumentException("--java-bridge requires --export <exact-package>");
        if (output == null || output.getFileName() == null || !output.getFileName().toString().endsWith(".jar")) throw new IllegalArgumentException("--java-bridge requires -o <artifact.jar>");
        return new Options(List.copyOf(inputs), sources, classes, List.copyOf(exports), output, llvm, optimization, unfreed, explain, List.copyOf(licenses),
                criticalCalls);
    }

    private static String value(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].isBlank() || arguments[index].startsWith("-")) throw new IllegalArgumentException("missing value after " + option);
        return arguments[index];
    }
    private static List<Path> paths(String text) {
        return java.util.Arrays.stream(text.split(Pattern.quote(File.pathSeparator), -1)).map(path -> Path.of(path.isEmpty() ? "." : path)).toList();
    }
    private static void diagnostics(List<Diagnostic> diagnostics, PrintStream err) {
        var formatter = new DiagnosticFormatter(); diagnostics.forEach(diagnostic -> err.println(formatter.format(diagnostic)));
    }
    static void usage(PrintStream stream) {
        stream.println("       ironwoodc --java-bridge --export <exact-package>... -o <artifact.jar>");
        stream.println("                 [-cp <class-path>] [--source-path <source-path>] [source.iron...]");
        stream.println("                 [-O0|-O1|-O2|-O3] [--llvm-home <directory>]");
        stream.println("                 [--license <notice-file>]...");
        stream.println("                 [--critical-calls=on|off]  (transition-free calls for proved entries; default: off)");
        stream.println("       ironwoodc --java-bridge-assemble -o <artifact.jar> <host.jar>...");
        stream.println("       ironwoodc --java-bridge-distribution --input <paired.jar> --group-id <group>");
        stream.println("                 --artifact-id <name> --version <version> -d <new-directory>");
    }
}
