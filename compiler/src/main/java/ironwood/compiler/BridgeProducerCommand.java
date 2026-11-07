// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.OptimizationLevel;
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
        Options options;
        try { options = parse(arguments); }
        catch (IllegalArgumentException invalid) { err.println("error: " + invalid.getMessage()); usage(err); return 2; }
        var loaded = new SourceSetLoader(options.sources(), options.classes()).loadBridge(options.inputs(), options.exports());
        if (!loaded.diagnostics().isEmpty()) { diagnostics(loaded.diagnostics(), err); return 1; }
        var pipeline = new CompilerPipeline();
        var artifact = pipeline.analyzeForBridge(loaded.sources());
        diagnostics(artifact.diagnostics(), err);
        if (!artifact.valid()) return 1;
        try {
            var selected = options.exports();
            boolean foreign = artifact.bridgeApiFacts().orElseThrow().types().values().stream()
                    .anyMatch(type -> type.accessible() && selected.contains(type.packageName())
                            && type.kind() == ironwood.compiler.semantic.BridgeApiFacts.Kind.INTERFACE);
            BridgeListenerProxies listeners = null;
            BridgeCallbackCarrierSources carrier = null;
            if (foreign) {
                var sources = new ArrayList<>(loaded.sources());
                carrier = BridgeCallbackCarrierSources.discover(artifact);
                sources.add(carrier.source());
                var withCarrier = pipeline.analyzeForBridge(sources);
                if (!withCarrier.valid()) { diagnostics(withCarrier.diagnostics(), err); return 1; }
                listeners = BridgeListenerProxies.discover(withCarrier, selected);
                artifact = pipeline.analyzeForBridge(sources, listeners);
                if (!artifact.valid()) { diagnostics(artifact.diagnostics(), err); return 1; }
            }
            var proof = BridgeObjectAdmission.proveManaged(artifact, selected, listeners, carrier);
            if (proof.contract().isEmpty()) { err.println("error: managed Java Bridge admission failed: " + proof.reason()); return 1; }
            var tools = LlvmToolchain.discover(options.llvmHome());
            if (!tools.successful()) { err.println("error: " + tools.error()); return 1; }
            BridgeProducer.build(artifact, proof.contract().orElseThrow(), options.output(), tools.toolchain().orElseThrow(), options.optimization(),
                    new BridgeDistributionInputs.Options(options.classes(), options.licenses()), options.criticalCalls(), err);
            out.println("built " + options.output().toAbsolutePath().normalize()); return 0;
        } catch (IOException | IllegalArgumentException failure) {
            err.println("error: Java Bridge build failed: " + failure.getMessage()); return 1;
        }
    }

    private record Options(List<Path> inputs, List<Path> sources, List<Path> classes, List<String> exports, Path output,
                           Path llvmHome, OptimizationLevel optimization, List<Path> licenses,
                           boolean criticalCalls) {}

    private static Options parse(String[] arguments) {
        var inputs = new ArrayList<Path>(); var exports = new ArrayList<String>(); var licenses = new ArrayList<Path>();
        List<Path> sources = List.of(Path.of(".")), classes = List.of(Path.of("."));
        Path output = null, llvm = null; var optimization = OptimizationLevel.O0;
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
                    case "--unfreed", "--explain-rejected-free" -> throw new IllegalArgumentException("manual reclamation options have been removed");
                    case "--help", "-h" -> throw new IllegalArgumentException("managed Java Bridge host builds support exact handles, listener registrations, copied values and borrowed views on qualified macos-arm64, linux-arm64 and linux-x86_64");
                    default -> {
                        if (option.startsWith("--unfreed=") || option.startsWith("--explain-rejected-free=")) {
                            throw new IllegalArgumentException("manual reclamation options have been removed");
                        }
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
        return new Options(List.copyOf(inputs), sources, classes, List.copyOf(exports), output, llvm, optimization, List.copyOf(licenses),
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
