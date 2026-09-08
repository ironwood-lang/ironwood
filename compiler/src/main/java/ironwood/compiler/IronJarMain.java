// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class IronJarMain {
    private IronJarMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        CommandLine commandLine = CommandLine.parse(args, err);
        if (commandLine == null) {
            return args.length == 1 && (args[0].equals("-h") || args[0].equals("--help")) ? 0 : 2;
        }
        try {
            if (commandLine.operation() == Operation.CREATE) {
                IronJar.create(commandLine.file(), commandLine.inputs(), commandLine.licenses());
                out.println("built " + commandLine.file().toAbsolutePath().normalize());
            } else {
                IronJar.read(commandLine.file()).entries().forEach(out::println);
            }
            return 0;
        } catch (IOException exception) {
            err.println("error: " + exception.getMessage());
            return 1;
        }
    }

    private enum Operation {
        CREATE,
        LIST
    }

    private record CommandLine(Operation operation, Path file, List<Path> inputs,
                               List<Path> licenses) {
        private CommandLine {
            inputs = List.copyOf(inputs);
            licenses = List.copyOf(licenses);
        }

        private static CommandLine parse(String[] args, PrintStream err) {
            Operation operation = null;
            Path file = null;
            List<Path> inputs = new ArrayList<>();
            List<Path> licenses = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--create" -> {
                        if (operation != null) {
                            return usage(err, "specify exactly one of --create or --list");
                        }
                        operation = Operation.CREATE;
                    }
                    case "--list" -> {
                        if (operation != null) {
                            return usage(err, "specify exactly one of --create or --list");
                        }
                        operation = Operation.LIST;
                    }
                    case "--file" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing archive path after --file");
                        }
                        if (file != null) {
                            return usage(err, "--file may be specified only once");
                        }
                        file = Path.of(args[index]);
                    }
                    case "--license" -> {
                        if (++index >= args.length) {
                            return usage(err, "missing metadata file after --license");
                        }
                        licenses.add(Path.of(args[index]));
                    }
                    case "-h", "--help" -> {
                        printUsage(err);
                        return null;
                    }
                    default -> {
                        if (args[index].startsWith("-")) {
                            return usage(err, "unknown option: " + args[index]);
                        }
                        inputs.add(Path.of(args[index]));
                    }
                }
            }
            if (operation == null) {
                return usage(err, "expected one of --create or --list");
            }
            if (file == null) {
                return usage(err, "expected --file <archive.ironjar>");
            }
            if (file.getFileName() == null
                    || !file.getFileName().toString().endsWith(IronJar.EXTENSION)) {
                return usage(err, "archive path must end with " + IronJar.EXTENSION);
            }
            if (operation == Operation.CREATE && inputs.isEmpty()) {
                return usage(err, "--create requires at least one .ironclass file or class directory");
            }
            if (operation == Operation.LIST && !inputs.isEmpty()) {
                return usage(err, "--list does not accept input files");
            }
            if (operation == Operation.LIST && !licenses.isEmpty()) {
                return usage(err, "--license is accepted only with --create");
            }
            return new CommandLine(operation, file, inputs, licenses);
        }

        private static CommandLine usage(PrintStream err, String message) {
            err.println("error: " + message);
            printUsage(err);
            return null;
        }

        private static void printUsage(PrintStream stream) {
            stream.println("usage: ironjar --create --file <archive.ironjar>"
                    + " [--license <metadata-file>]..."
                    + " <class-directory|file.ironclass>...");
            stream.println("       ironjar --list --file <archive.ironjar>");
        }
    }
}
