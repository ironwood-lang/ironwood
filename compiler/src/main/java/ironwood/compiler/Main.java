// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.LinkResult;
import ironwood.compiler.backend.LlvmEmitter;
import ironwood.compiler.backend.LlvmToolchain;
import ironwood.compiler.backend.NativeBackend;
import ironwood.compiler.backend.OptimizationLevel;
import ironwood.compiler.backend.ToolchainDiscovery;
import ironwood.compiler.diagnostic.DiagnosticFormatter;
import ironwood.compiler.source.SourceFile;
import ironwood.compiler.ast.DeclaredTypes;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("--version") || args[0].equals("-v"))) {
            out.println("ironwoodc " + CompilerVersion.current());
            return 0;
        }

        CommandLine commandLine = CommandLine.parse(args, err);
        if (commandLine == null) {
            return 2;
        }

        List<String> explicitClassTypes = commandLine.mainClass() == null
                ? List.of() : List.of(commandLine.mainClass());
        List<Path> effectiveSourcePath = commandLine.link() ? List.of() : commandLine.sourcePath();
        SourceLoadResult loaded = new SourceSetLoader(effectiveSourcePath, commandLine.classPath())
                .load(commandLine.inputs(), explicitClassTypes);
        if (!loaded.diagnostics().isEmpty()) {
            printDiagnostics(loaded.diagnostics(), err);
            return 1;
        }

        CompilerPipeline pipeline = new CompilerPipeline();
        CompilationArtifact artifact;
        if (commandLine.link()) {
            artifact = commandLine.mainClass() == null
                    ? pipeline.compile(loaded.sources())
                    : pipeline.compile(loaded.sources(), commandLine.mainClass());
        } else {
            artifact = pipeline.analyze(loaded.sources());
        }
        if (!artifact.valid()) {
            printDiagnostics(artifact.diagnostics(), err);
            return 1;
        }

        if (!commandLine.link()) {
            try {
                for (Path classFile : writeClassOutputs(loaded.sources(), commandLine.classOutput(),
                        commandLine.inputs())) {
                    out.println("built " + classFile);
                }
            } catch (IOException exception) {
                err.println("error: cannot write Ironwood class: " + exception.getMessage());
                return 1;
            }
            return 0;
        }

        var linkedProgram = ClosedWorldPruner.prune(artifact.program().orElseThrow());

        ToolchainDiscovery discovery = LlvmToolchain.discover(commandLine.llvmHome());
        if (!discovery.successful()) {
            err.println("error: " + discovery.error());
            return 1;
        }

        Path selectedOutput = commandLine.output() == null
                ? defaultOutput(linkedProgram, loaded.sources(), commandLine.inputs())
                : commandLine.output();
        Path output = selectedOutput.toAbsolutePath().normalize();
        Path llvmPath = commandLine.emitLlvm() == null
                ? createTemporaryLlvm(output, err)
                : commandLine.emitLlvm().toAbsolutePath().normalize();
        if (llvmPath == null) {
            return 1;
        }
        boolean temporaryLlvm = commandLine.emitLlvm() == null;

        try {
            Path llvmParent = llvmPath.getParent();
            if (llvmParent != null) {
                Files.createDirectories(llvmParent);
            }
            Files.writeString(llvmPath, new LlvmEmitter().emit(linkedProgram),
                    StandardCharsets.UTF_8);
            LinkResult linkResult = new NativeBackend().link(discovery.toolchain().orElseThrow(), llvmPath,
                    output, commandLine.optimizationLevel());
            if (!linkResult.success()) {
                err.println("error: native link failed");
                if (!linkResult.output().isBlank()) {
                    err.println(linkResult.output());
                }
                return 1;
            }
            if (!linkResult.output().isBlank()) {
                err.println(linkResult.output());
            }
            out.println("built " + output);
            return 0;
        } catch (IOException exception) {
            err.println("error: cannot write LLVM IR: " + exception.getMessage());
            return 1;
        } finally {
            if (temporaryLlvm) {
                try {
                    Files.deleteIfExists(llvmPath);
                } catch (IOException ignored) {
                    // A stale temporary file is preferable to hiding the compiler result.
                }
            }
        }
    }

    private static void printDiagnostics(List<ironwood.compiler.diagnostic.Diagnostic> diagnostics,
                                         PrintStream err) {
        DiagnosticFormatter formatter = new DiagnosticFormatter();
        diagnostics.forEach(diagnostic -> err.println(formatter.format(diagnostic)));
    }

    private static List<Path> writeClassOutputs(List<SourceFile> sources, Path classOutput,
                                                List<Path> explicitInputs)
            throws IOException {
        List<Path> outputs = new ArrayList<>();
        Path outputRoot = classOutput == null ? null : classOutput.toAbsolutePath().normalize();
        var explicitPaths = explicitInputs.stream().map(path -> path.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        StandardLibrary standardLibrary = StandardLibrary.discover();
        for (SourceFile source : sources) {
            Path sourcePath = source.path().toAbsolutePath().normalize();
            if (source.path().toString().contains("!/")
                    || standardLibrary.isBundledSource(source) && !explicitPaths.contains(sourcePath)) {
                continue;
            }
            ParsedSource parsed = SourceParser.parse(source);
            if (parsed.unit().isEmpty()) {
                continue;
            }
            var unit = parsed.unit().orElseThrow();
            for (var declaredType : DeclaredTypes.in(unit)) {
                var declaration = declaredType.declaration();
                String artifactSimpleName = declaredType.binaryName();
                if (!unit.packageName().isEmpty()) {
                    artifactSimpleName = artifactSimpleName.substring(unit.packageName().length() + 1);
                }
                Path output;
                if (outputRoot == null) {
                    output = source.path().resolveSibling(artifactSimpleName + IronClass.EXTENSION)
                            .toAbsolutePath().normalize();
                } else {
                    Path packagePath = unit.packageName().isEmpty() ? Path.of("")
                            : Path.of(unit.packageName().replace('.', '/'));
                    output = outputRoot.resolve(packagePath)
                            .resolve(artifactSimpleName + IronClass.EXTENSION);
                }
                String canonical = declaredType.binaryName();
                IronClass.write(output, unit, canonical);
                outputs.add(output);
            }
        }
        return outputs;
    }

    private static Path defaultOutput(ironwood.compiler.ir.IrProgram program, List<SourceFile> sources,
                                      List<Path> inputs) {
        String owner = program.entryPoint().orElseThrow().ownerClass();
        String simpleName = owner.substring(owner.lastIndexOf('.') + 1);
        for (SourceFile source : sources) {
            if (source.path().toString().contains("!/")) {
                continue;
            }
            ParsedSource parsed = SourceParser.parse(source);
            if (parsed.unit().isEmpty()) {
                continue;
            }
            var unit = parsed.unit().orElseThrow();
            boolean declaresEntry = DeclaredTypes.in(unit).stream()
                    .anyMatch(declaration -> declaration.binaryName().equals(owner));
            if (declaresEntry) {
                return source.path().resolveSibling(simpleName);
            }
        }
        return inputs.isEmpty() ? Path.of(simpleName) : inputs.getFirst().resolveSibling(simpleName);
    }

    private static Path createTemporaryLlvm(Path output, PrintStream err) {
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                return Files.createTempFile(parent, ".ironwoodc-", ".ll");
            }
            return Files.createTempFile("ironwoodc-", ".ll");
        } catch (IOException exception) {
            err.println("error: cannot create temporary LLVM file: " + exception.getMessage());
            return null;
        }
    }

    private record CommandLine(List<Path> inputs, Path output, Path classOutput,
                               Path emitLlvm, Path llvmHome,
                               List<Path> sourcePath, List<Path> classPath,
                               OptimizationLevel optimizationLevel, String mainClass,
                               boolean link) {
        private CommandLine {
            inputs = List.copyOf(inputs);
            sourcePath = List.copyOf(sourcePath);
            classPath = List.copyOf(classPath);
        }

        private static CommandLine parse(String[] args, PrintStream err) {
            List<String> positional = new ArrayList<>();
            Path output = null;
            Path classOutput = null;
            Path emitLlvm = null;
            Path llvmHome = null;
            String mainClass = null;
            boolean link = false;
            List<Path> sourcePath = List.of(Path.of("."));
            boolean sourcePathSpecified = false;
            List<Path> classPath = List.of(Path.of("."));
            OptimizationLevel optimizationLevel = OptimizationLevel.O0;
            boolean optimizationSpecified = false;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "-o" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing path after -o");
                        }
                        output = Path.of(args[index]);
                    }
                    case "-d" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing directory after -d");
                        }
                        classOutput = Path.of(args[index]);
                    }
                    case "--emit-llvm" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing path after --emit-llvm");
                        }
                        emitLlvm = Path.of(args[index]);
                    }
                    case "--llvm-home" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing path after --llvm-home");
                        }
                        llvmHome = Path.of(args[index]);
                    }
                    case "--main-class" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing qualified class name after --main-class");
                        }
                        mainClass = args[index];
                    }
                    case "--link" -> link = true;
                    case "-sourcepath", "--source-path" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing path list after " + args[index - 1]);
                        }
                        sourcePath = parsePathList(args[index]);
                        sourcePathSpecified = true;
                    }
                    case "-cp", "-classpath", "--class-path" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing path list after " + args[index - 1]);
                        }
                        classPath = parsePathList(args[index]);
                    }
                    case "--library" -> {
                        return usage(err, "--library has been removed; use -d <class-directory>");
                    }
                    case "-O0" -> {
                        optimizationLevel = OptimizationLevel.O0;
                        optimizationSpecified = true;
                    }
                    case "-O1" -> {
                        optimizationLevel = OptimizationLevel.O1;
                        optimizationSpecified = true;
                    }
                    case "-O2" -> {
                        optimizationLevel = OptimizationLevel.O2;
                        optimizationSpecified = true;
                    }
                    case "-O3" -> {
                        optimizationLevel = OptimizationLevel.O3;
                        optimizationSpecified = true;
                    }
                    case "-h", "--help" -> {
                        printUsage(err);
                        return null;
                    }
                    default -> {
                        if (args[index].startsWith("-")) {
                            return usage(err, "unknown option: " + args[index]);
                        }
                        positional.add(args[index]);
                    }
                }
            }

            if (!link && output != null) {
                return usage(err, "-o requires --link");
            }
            if (!link && emitLlvm != null) {
                return usage(err, "--emit-llvm requires --link");
            }
            if (!link && mainClass != null) {
                return usage(err, "--main-class requires --link");
            }
            if (!link && llvmHome != null) {
                return usage(err, "--llvm-home requires --link");
            }
            if (!link && optimizationSpecified) {
                return usage(err, "optimization levels require --link");
            }
            if (link && classOutput != null) {
                return usage(err, "-d cannot be combined with --link; linking does not emit .ironclass files");
            }
            if (link && sourcePathSpecified) {
                return usage(err, "--source-path cannot be combined with --link; link compiled classes instead");
            }
            if (!link && positional.isEmpty()) {
                return usage(err, "expected at least one input file");
            }
            if (link && !positional.isEmpty()) {
                return usage(err, "--link does not accept source files; compile them first");
            }
            if (link && mainClass == null) {
                return usage(err, "--link requires --main-class <qualified-name>");
            }
            return new CommandLine(positional.stream().map(Path::of).toList(), output,
                    classOutput, emitLlvm, llvmHome, sourcePath, classPath,
                    optimizationLevel, mainClass, link);
        }

        private static List<Path> parsePathList(String value) {
            String[] entries = value.split(Pattern.quote(File.pathSeparator), -1);
            List<Path> paths = new ArrayList<>();
            for (String entry : entries) {
                paths.add(Path.of(entry.isEmpty() ? "." : entry));
            }
            return List.copyOf(paths);
        }

        private static CommandLine usage(PrintStream err, String message) {
            err.println("error: " + message);
            printUsage(err);
            return null;
        }

        private static void printUsage(PrintStream stream) {
            stream.println("usage: ironwoodc <source.iron>... [-d <class-directory>]"
                    + " [--source-path <path>] [-cp <path>]");
            stream.println("       ironwoodc --link --main-class <qualified-name>"
                    + " [-cp <path>] [-o <executable>]"
                    + " [-O0|-O1|-O2|-O3]");
            stream.println("                 [--emit-llvm <file.ll>] [--llvm-home <directory>]");
            stream.println("       ironwoodc --version|-v");
        }
    }
}
