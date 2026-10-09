// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.ir.IrFileInstruction;
import ironwood.compiler.source.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static ironwood.compiler.FilesystemServicesTests.accept;
import static ironwood.compiler.FilesystemServicesTests.delete;
import static ironwood.compiler.FilesystemServicesTests.entries;
import static ironwood.compiler.FilesystemServicesTests.execute;
import static ironwood.compiler.FilesystemServicesTests.links;
import static ironwood.compiler.FilesystemServicesTests.messages;
import static ironwood.compiler.FilesystemServicesTests.reference;
import static ironwood.compiler.FilesystemServicesTests.reject;
import static ironwood.compiler.FilesystemServicesTests.require;
import static ironwood.compiler.FilesystemServicesTests.unlock;

/**
 * M4.2 publication moves (D271): atomic replacement, replacement with a
 * cross-device copy for regular files, and the exclusive no-replace rename,
 * against Java 21 on one file system, across a second real file system,
 * under competing processes and under injected host failures.
 */
final class PublicationTests {
    private static final String REFERENCE = "docs/self-hosting/m4/publication-evidence/PublicationReference.java";
    private static final List<String> CASES = List.of("file-to-absent", "file-over-file", "file-over-empty-dir",
            "dir-over-empty-dir", "dir-over-full-dir", "dir-over-file", "link-source", "over-link", "over-dangling",
            "same-file", "missing-source");

    private PublicationTests() { }

    /** Each move lowers to its typed operation; the returned path is the caller's target, not a fresh one. */
    static void typedOperations() {
        CompilationArtifact artifact = new CompilerPipeline(UnfreedMode.ERROR).compile(SourceFile.of("test/Main.iron", """
                import ironwood.nio.file.Files;
                import ironwood.nio.file.Path;
                class Main {
                    public static int main(String[] args) throws Exception {
                        Path source = Path.of(args[0]);
                        Path target = Path.of(args[1]);
                        Files.moveAtomicReplacing(source, target);
                        Files.moveReplacing(target, source);
                        Files.moveAtomicNoReplace(source, target);
                        free target;
                        free source;
                        return 0;
                    }
                }
                """));
        require(artifact.successful(), messages(artifact));
        Set<IrFileInstruction.Operation> operations = artifact.program().orElseThrow().functions().stream()
                .flatMap(function -> function.blocks().stream()).flatMap(block -> block.instructions().stream())
                .filter(IrFileInstruction.class::isInstance).map(IrFileInstruction.class::cast)
                .map(IrFileInstruction::operation).collect(Collectors.toUnmodifiableSet());
        require(operations.containsAll(List.of(IrFileInstruction.Operation.MOVE_ATOMIC,
                IrFileInstruction.Operation.MOVE_REPLACING, IrFileInstruction.Operation.MOVE_EXCLUSIVE)),
                "publication moves did not lower through typed IR: " + operations);
        String llvm = artifact.llvmIr().orElseThrow();
        for (String boundary : List.of("@ironwood_file_move_atomic", "@ironwood_file_move_replacing",
                "@ironwood_file_move_exclusive")) {
            require(llvm.contains(boundary), "missing runtime boundary " + boundary);
        }
        String prefix = """
                import ironwood.nio.file.Files;
                import ironwood.nio.file.Path;
                class Main { public static int main(String[] args) throws Exception {
                Path source = Path.of(args[0]); Path target = Path.of(args[1]);
                """;
        for (UnfreedMode mode : UnfreedMode.values()) {
            // Files.move is the existing control: every move returns the caller's target.
            for (String move : List.of("move", "moveAtomicReplacing", "moveReplacing", "moveAtomicNoReplace")) {
                // Both inputs are borrowed: each is freed once after the call.
                accept(mode, prefix + "Files." + move + "(source, target); free source; free target; return 0; }}");
                // A kept result aliases the target, never a fresh path: neither name can be freed.
                reject(mode, prefix + "Path moved = Files." + move + "(source, target); free source; free target;"
                        + " return 0; }}", "may still be observed through local 'moved'");
                reject(mode, prefix + "Path moved = Files." + move + "(source, target); free source; free moved;"
                        + " return 0; }}", "may still be observed through local 'target'");
                reject(mode, prefix + "free target; Files." + move + "(source, target); free source; return 0; }}",
                        "after its allocation was freed");
            }
        }
    }

    /**
     * Sixteen cases per move on one file system print Java 21's lines and
     * leave Java's trees, from class and archive links. The exclusive move
     * is compared with Java's default move and differs only where an
     * existing name is the same file.
     */
    static void javaDifferential() throws Exception {
        Path root = Files.createTempDirectory("ironwood-publication-");
        try {
            List<Path> executables = links(root, "stdlib_publication", List.of());
            int run = 0;
            for (String operation : List.of("atomic", "replacing", "exclusive")) {
                Path javaTree = cases(root.resolve("java-" + operation));
                String expected = reference(REFERENCE, null, arguments(operation, javaTree), null, Map.of())
                        .replace(javaTree.toString(), "R");
                unlock(javaTree);
                String javaState = state(javaTree);
                if (operation.equals("exclusive")) {
                    // Java's default move treats both forms of the same file as a no-op.
                    require(expected.contains("R/same-file/s R/same-file/t -> ok\n")
                            && expected.contains("R/same-file/s R/same-file/s -> ok\n"), expected);
                    expected = expected.replace("R/same-file/s R/same-file/t -> ok", "R/same-file/s R/same-file/t"
                            + " -> !FileAlreadyExistsException").replace("R/same-file/s R/same-file/s -> ok",
                            "R/same-file/s R/same-file/s -> !FileAlreadyExistsException");
                }
                for (Path executable : executables) {
                    Path tree = cases(root.resolve("native-" + run++));
                    String output = runNative(executable, arguments(operation, tree)).replace(tree.toString(), "R");
                    require(output.equals(expected), operation + " lines differ:\n" + output + "\nversus\n" + expected);
                    unlock(tree);
                    require(state(tree).equals(javaState), operation + " trees differ:\n" + state(tree)
                            + "\nversus\n" + javaState);
                }
            }
        } finally {
            unlock(root);
            delete(root);
        }
    }

    /**
     * Moves onto a second real file system: an HFS+ disk image on macOS,
     * /dev/shm on Linux. Atomic and exclusive moves fail without effect; the
     * replacing move copies a regular file with its mode and times and
     * leaves no temporary; directories and links have no fallback.
     */
    static void crossDevice() throws Exception {
        Path root = Files.createTempDirectory("ironwood-publication-devices-");
        Path other = null;
        String image = null;
        try {
            List<Path> executables = links(root, "stdlib_publication", List.of());
            if (System.getProperty("os.name").startsWith("Mac")) {
                Path dmg = root.resolve("other.dmg");
                tool(List.of("/usr/bin/hdiutil", "create", "-size", "16m", "-fs", "HFS+", "-volname", "m4other",
                        dmg.toString()));
                String attached = tool(List.of("/usr/bin/hdiutil", "attach", "-nobrowse", "-noverify",
                        "-mountrandom", root.toString(), dmg.toString()));
                String line = attached.lines().filter(text -> text.contains(root.toString())).findFirst().orElseThrow();
                other = Path.of(line.substring(line.indexOf(root.toString())).strip());
                image = other.toString();
            } else {
                other = Files.createTempDirectory(Path.of("/dev/shm"), "ironwood-publication-");
            }
            require(!Files.getAttribute(root, "unix:dev").equals(Files.getAttribute(other, "unix:dev")),
                    "the second directory is on the same device");
            int run = 0;
            for (Path executable : executables) {
                for (String side : List.of("java", "native")) {
                    Path source = Files.createDirectories(root.resolve(side + run));
                    Path target = Files.createDirectories(other.resolve(side + run));
                    Files.writeString(source.resolve("file"), "S");
                    Files.setPosixFilePermissions(source.resolve("file"), PosixFilePermissions.fromString("rwxr-x--x"));
                    FileTime modified = FileTime.fromMillis(1_577_934_245_000L);
                    Files.setLastModifiedTime(source.resolve("file"), modified);
                    Files.writeString(target.resolve("file-target"), "OLD");
                    Files.createDirectories(source.resolve("dir"));
                    Files.writeString(source.resolve("dir/c"), "C");
                    Files.createSymbolicLink(source.resolve("link"), Path.of("file"));
                    Files.writeString(source.resolve("afile"), "S");
                    Files.writeString(source.resolve("efile"), "S");
                    List<String> arguments = List.of("atomic", source.resolve("afile").toString(),
                            target.resolve("afile-target").toString(), "replacing", source.resolve("file").toString(),
                            target.resolve("file-target").toString(), "replacing", source.resolve("dir").toString(),
                            target.resolve("dir-target").toString(), "replacing", source.resolve("link").toString(),
                            target.resolve("link-target").toString(), "exclusive", source.resolve("efile").toString(),
                            target.resolve("efile-target").toString());
                    String output = side.equals("java") ? reference(REFERENCE, null, arguments, null, Map.of())
                            : runNative(executable, arguments);
                    output = output.replace(source.toString(), "S").replace(target.toString(), "T");
                    Path file = target.resolve("file-target");
                    require(Files.readString(file).equals("S") && !Files.exists(source.resolve("file"))
                            && PosixFilePermissions.toString(Files.getPosixFilePermissions(file)).equals("rwxr-x--x")
                            && Files.getLastModifiedTime(file).equals(modified), side + " fallback copy: "
                            + PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) + " "
                            + Files.getLastModifiedTime(file));
                    String agreed = "atomic S/afile T/afile-target -> !AtomicMoveNotSupportedException\n"
                            + "replacing S/file T/file-target -> ok\n";
                    if (side.equals("java")) {
                        // Java copies a link and the default move a file; a non-empty directory fails.
                        require(output.equals(agreed + "replacing S/dir T/dir-target -> !DirectoryNotEmptyException\n"
                                + "replacing S/link T/link-target -> ok\nexclusive S/efile T/efile-target -> ok\n"), output);
                    } else {
                        require(output.equals(agreed
                                + "replacing S/dir T/dir-target -> !AtomicMoveNotSupportedException\n"
                                + "replacing S/link T/link-target -> !AtomicMoveNotSupportedException\n"
                                + "exclusive S/efile T/efile-target -> !AtomicMoveNotSupportedException\n"), output);
                        require(Files.exists(source.resolve("afile")) && Files.exists(source.resolve("dir/c"))
                                && Files.isSymbolicLink(source.resolve("link")) && Files.exists(source.resolve("efile"))
                                && entries(target).equals("file-target file rwxr-x--x x1"),
                                "native cross-device state: " + entries(target));
                    }
                }
                run++;
            }
        } finally {
            if (image != null) tool(List.of("/usr/bin/hdiutil", "detach", image, "-quiet"));
            else if (other != null) delete(other);
            unlock(root);
            delete(root);
        }
    }

    /** Competing processes publish into one name: exactly one wins and its content survives. */
    static void competingCreators() throws Exception {
        Path root = Files.createTempDirectory("ironwood-publication-race-");
        try {
            Path executable = links(root, "stdlib_publication", List.of()).getFirst();
            int contenders = 8;
            for (int round = 0; round < 40; round++) {
                boolean directories = round % 2 == 1;
                Path arena = Files.createDirectories(root.resolve("round-" + round));
                Path target = arena.resolve("published");
                List<Process> processes = new ArrayList<>();
                for (int contender = 0; contender < contenders; contender++) {
                    Path staged = arena.resolve("staged-" + contender);
                    if (directories) {
                        Files.createDirectories(staged);
                        Files.writeString(staged.resolve("owner"), Integer.toString(contender));
                    } else {
                        Files.writeString(staged, Integer.toString(contender));
                    }
                }
                for (int contender = 0; contender < contenders; contender++) {
                    processes.add(new ProcessBuilder(executable.toString(), "exclusive",
                            arena.resolve("staged-" + contender).toString(), target.toString())
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start());
                }
                int winners = 0;
                int winner = -1;
                for (int contender = 0; contender < contenders; contender++) {
                    Process process = processes.get(contender);
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    require(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 42, output);
                    if (output.endsWith("-> ok\n")) {
                        winners++;
                        winner = contender;
                    } else {
                        require(output.endsWith("-> !FileAlreadyExistsException\n"), output);
                        require(Files.exists(arena.resolve("staged-" + contender), LinkOption.NOFOLLOW_LINKS),
                                "a losing source moved");
                    }
                }
                String owner = Files.readString(directories ? target.resolve("owner") : target);
                require(winners == 1 && owner.equals(Integer.toString(winner)), "round " + round + ": " + winners
                        + " winners, owner " + owner);
            }
        } finally {
            delete(root);
        }
    }

    /** The runtime under an injected competing creator, unsupported and cross-device results, and fallback failures. */
    static void nativeHarness() throws Exception {
        FilesystemServicesTests.runtimeHarness("filesystem_services.c", "publication");
    }

    /** Every allocation failure in a staged publication unwinds to the baseline without leftovers. */
    static void failures() throws Exception {
        Path root = Files.createTempDirectory("ironwood-publication-failure-");
        try {
            Path scratch = Files.createDirectories(root.resolve("scratch"));
            for (Path executable : links(root, "stdlib_publication_failure", List.of())) {
                List<String> command = List.of(executable.toString(), scratch.toString());
                execute(command, root, Map.of(), null, 43, "");
                int limit = 0;
                while (execute(command, root, Map.of(), limit, -1, "") == 42) {
                    require(entries(scratch).isEmpty(), "limit " + limit + " left " + entries(scratch));
                    limit++;
                }
                require(limit >= 10 && execute(command, root, Map.of(), limit, 43, "") == 43
                        && entries(scratch).isEmpty(), "publication OOM sweep ended at limit " + limit);
            }
        } finally {
            delete(root);
        }
    }

    // The sixteen case directories under root.
    private static Path cases(Path root) throws IOException {
        for (String name : CASES) Files.createDirectories(root.resolve(name));
        Files.writeString(root.resolve("file-to-absent/s"), "S");
        Files.writeString(root.resolve("file-over-file/s"), "S");
        Files.writeString(root.resolve("file-over-file/t"), "T");
        Files.writeString(root.resolve("file-over-empty-dir/s"), "S");
        Files.createDirectories(root.resolve("file-over-empty-dir/t"));
        Files.createDirectories(root.resolve("dir-over-empty-dir/s"));
        Files.writeString(root.resolve("dir-over-empty-dir/s/child"), "C");
        Files.createDirectories(root.resolve("dir-over-empty-dir/t"));
        Files.createDirectories(root.resolve("dir-over-full-dir/s"));
        Files.createDirectories(root.resolve("dir-over-full-dir/t"));
        Files.writeString(root.resolve("dir-over-full-dir/t/child"), "C");
        Files.createDirectories(root.resolve("dir-over-file/s"));
        Files.writeString(root.resolve("dir-over-file/t"), "T");
        Files.writeString(root.resolve("link-source/data"), "D");
        Files.createSymbolicLink(root.resolve("link-source/s"), Path.of("data"));
        Files.writeString(root.resolve("over-link/s"), "S");
        Files.writeString(root.resolve("over-link/data"), "D");
        Files.createSymbolicLink(root.resolve("over-link/t"), Path.of("data"));
        Files.writeString(root.resolve("over-dangling/s"), "S");
        Files.createSymbolicLink(root.resolve("over-dangling/t"), Path.of("missing"));
        Files.writeString(root.resolve("same-file/s"), "S");
        Files.createLink(root.resolve("same-file/t"), root.resolve("same-file/s"));
        Files.createDirectories(root.resolve("missing-parent"));
        Files.writeString(root.resolve("missing-parent/s"), "S");
        Files.createDirectories(root.resolve("readonly-parent/ro"));
        Files.writeString(root.resolve("readonly-parent/s"), "S");
        Files.setPosixFilePermissions(root.resolve("readonly-parent/ro"), PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.createDirectories(root.resolve("into-itself/s"));
        Files.createDirectories(root.resolve("unicode"));
        Files.writeString(root.resolve("unicode/🌲 s"), "S");
        Files.writeString(root.resolve("unicode/🌲 t"), "T");
        return root;
    }

    private static List<String> arguments(String operation, Path root) {
        List<String> arguments = new ArrayList<>();
        for (String name : CASES) {
            arguments.addAll(List.of(operation, root.resolve(name + "/s").toString(), root.resolve(name + "/t").toString()));
        }
        arguments.addAll(List.of(operation, root.resolve("same-file/s").toString(), root.resolve("same-file/s").toString()));
        arguments.addAll(List.of(operation, root.resolve("missing-parent/s").toString(),
                root.resolve("missing-parent/missing/t").toString()));
        arguments.addAll(List.of(operation, root.resolve("readonly-parent/s").toString(),
                root.resolve("readonly-parent/ro/t").toString()));
        arguments.addAll(List.of(operation, root.resolve("into-itself/s").toString(),
                root.resolve("into-itself/s/sub").toString()));
        arguments.addAll(List.of(operation, root.resolve("unicode/🌲 s").toString(),
                root.resolve("unicode/🌲 t").toString()));
        return arguments;
    }

    // Every entry with its kind and content or link target, links not followed.
    private static String state(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            List<String> lines = new ArrayList<>();
            for (Path path : paths.sorted().toList()) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) lines.add(name + " -> " + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) lines.add(name + "/");
                else lines.add(name + " = " + Files.readString(path));
            }
            return String.join("\n", lines);
        }
    }

    private static String runNative(Path executable, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable.toString()));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 42, "exit " + process.exitValue());
        return output;
    }

    private static String tool(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0, command + ": " + output);
        return output;
    }
}
