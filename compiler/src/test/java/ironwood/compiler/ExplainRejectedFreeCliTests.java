// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

final class ExplainRejectedFreeCliTests {
    private ExplainRejectedFreeCliTests() {
    }

    static void runAll() throws Exception {
        Path root = Files.createTempDirectory("ironwood-explain-cli-");
        try {
            usage(root);
            sourceAndLink(root);
            dependencyAndReducedMode(root);
        } finally {
            deleteTree(root);
        }
    }

    private static void usage(Path root) {
        String usage = String.join(System.lineSeparator(),
                "usage: ironwoodc <source.iron>... [-d <class-directory>] [--source-path <path>] [-cp <path>]",
                "       ironwoodc --link --main-class <qualified-name> [-cp <path>] [-o <executable>] [-O0|-O1|-O2|-O3] [-march=native]",
                "                 [--emit-llvm <file.ll>] [--llvm-home <directory>]",
                "                 [--inline-threshold <integer>] [--selective-inlining=on|off]",
                "                 [--partial-inlining=on|off]",
                "                 [--optimization-report <file.yaml>]  (optional LLVM remarks)",
                "       Inlining defaults: threshold 1000 at -O3 (LLVM default otherwise), selective on.",
                "       Partial inlining defaults: on at -O3, LLVM default otherwise.",
                "       Both compilation and linking accept --unfreed=off|warn|error (default: warn)",
                "       and --explain-rejected-free (notes on rejected frees; default: off).",
                "       ironwoodc --version|-v  (compiler version and LLVM selection)", "");
        for (String option : new String[]{"-h", "--help"}) {
            Result result = run(option);
            require(result.status == 2 && result.stdout.isEmpty() && result.stderr.equals(usage),
                    option + " changed help convention: " + result);
        }
        for (String value : new String[]{"true", "false", "on", "", "arbitrary"}) {
            for (boolean link : new boolean[]{false, true}) {
                Result result = link
                        ? run("--link", "--explain-rejected-free=" + value, "--main-class", "Main")
                        : run("--explain-rejected-free=" + value, "Main.iron");
                require(result.status == 2 && result.stdout.isEmpty()
                                && result.stderr.equals("error: --explain-rejected-free does not take a value"
                                        + System.lineSeparator() + usage),
                        "valued flag changed usage: " + result);
            }
        }
        Result misspelled = run("--explain-rejected-frees", "Main.iron");
        require(misspelled.status == 2 && misspelled.stdout.isEmpty()
                        && misspelled.stderr.equals("error: unknown option: --explain-rejected-frees"
                                + System.lineSeparator() + usage),
                "unknown spelling was accepted: " + misspelled);
        Path malformedClasses = root.resolve("malformed-classes");
        Path malformedExecutable = root.resolve("malformed-executable");
        require(run("--explain-rejected-free=", "Main.iron", "-d",
                        malformedClasses.toString()).status == 2
                        && run("--link", "--explain-rejected-free=true", "--main-class", "Main",
                        "-o", malformedExecutable.toString()).status == 2
                        && !Files.exists(malformedClasses) && !Files.exists(malformedExecutable),
                "malformed option produced an artifact");
    }

    private static void sourceAndLink(Path root) throws IOException {
        Path bad = write(root.resolve("Bad.iron"), """
                class Bad {
                    static void check() {
                        Object value = new Object();
                        Object alias = value;
                        free value;
                    }
                }
                """);
        Path rejectedOutput = root.resolve("rejected");
        Result off = run("--unfreed=off", bad.toString(), "-d", rejectedOutput.toString());
        Result on = run("--unfreed=off", "--explain-rejected-free", bad.toString(),
                "-d", rejectedOutput.toString());
        Result repeated = run("--unfreed=off", "--explain-rejected-free",
                "--explain-rejected-free", bad.toString(), "-d", rejectedOutput.toString());
        require(off.status == 1 && on.status == 1 && repeated.status == 1
                        && off.stdout.isEmpty() && on.stdout.isEmpty() && repeated.stdout.isEmpty()
                        && !off.stderr.contains("note:") && on.stderr.contains("note:")
                        && on.stderr.equals(repeated.stderr)
                        && on.stderr.substring(0, on.stderr.indexOf("note:")).equals(
                        off.stderr.substring(0, off.stderr.lastIndexOf(System.lineSeparator()))
                                + System.lineSeparator())
                        && !Files.exists(rejectedOutput),
                "source flag changed rejection, primary, duplication, or output: " + on);

        Path safe = write(root.resolve("Safe.iron"), """
                class Safe {
                    public static int main(String[] args) {
                        Object value = new Object();
                        free value;
                        return 0;
                    }
                }
                """);
        Path classes = root.resolve("classes");
        Result compiled = run("--unfreed=off", "--explain-rejected-free", safe.toString(),
                "-d", classes.toString());
        require(compiled.status == 0 && compiled.stderr.isEmpty()
                        && !compiled.stdout.contains("note:")
                        && Files.isRegularFile(classes.resolve("Safe.ironclass")),
                "successful source compilation gained explanation output: " + compiled);
        Path executable = root.resolve("safe");
        Path llvm = root.resolve("safe.ll");
        Result link = run("--link", "--unfreed=off", "--explain-rejected-free", "-cp",
                classes.toString(), "--main-class", "Safe", "-o", executable.toString(),
                "--emit-llvm", llvm.toString());
        byte[] linkedIr = Files.readAllBytes(llvm);
        Result linkRepeated = run("--link", "--unfreed=off", "--explain-rejected-free",
                "--explain-rejected-free", "-cp", classes.toString(), "--main-class", "Safe",
                "-o", executable.toString(), "--emit-llvm", llvm.toString());
        Result linkOff = run("--link", "--unfreed=off", "-cp", classes.toString(),
                "--main-class", "Safe", "-o", executable.toString(),
                "--emit-llvm", llvm.toString());
        require(link.status == 0 && linkRepeated.status == 0
                        && linkOff.status == 0 && link.stderr.isEmpty()
                        && linkRepeated.stderr.isEmpty() && linkOff.stderr.isEmpty()
                        && Files.isRegularFile(executable) && link.stdout.equals(linkRepeated.stdout)
                        && link.stdout.equals(linkOff.stdout)
                        && Arrays.equals(linkedIr, Files.readAllBytes(llvm)),
                "link flag changed successful output or duplicate handling: " + link + " / " + linkRepeated);
    }

    private static void dependencyAndReducedMode(Path root) throws IOException {
        Path library = write(root.resolve("lib/Sink.iron"), """
                public class Sink {
                    public void accept(byte[] value) { }
                    public static void use(Sink sink) {
                        byte[] data = new byte[16];
                        sink.accept(data);
                        free data;
                    }
                }
                """);
        Path libraryClasses = root.resolve("lib-classes");
        Result libraryBuilt = run("--unfreed=off", "--explain-rejected-free",
                library.toString(), "-d", libraryClasses.toString());
        require(libraryBuilt.status == 0 && libraryBuilt.stderr.isEmpty(),
                "library control did not compile: " + libraryBuilt);
        Path keeper = write(root.resolve("app/Keeper.iron"), """
                public class Keeper extends Sink {
                    static byte[] kept;
                    @Override public void accept(byte[] value) { kept = value; }
                    public static void main(String[] args) { Sink.use(new Keeper()); }
                }
                """);
        Path appClasses = root.resolve("app-classes");
        Result dependency = run("--unfreed=off", "--explain-rejected-free", "-cp",
                libraryClasses.toString(), keeper.toString(), "-d", appClasses.toString());
        require(dependency.status == 1 && dependency.stdout.isEmpty()
                        && dependency.stderr.contains("cannot free 'data'")
                        && dependency.stderr.contains("note:") && !Files.exists(appClasses),
                "dependency rejected-free explanation was absent: " + dependency);

        Path earlier = write(root.resolve("earlier/Sink.iron"), """
                public class Sink {
                    public void accept(byte[] value) { }
                    public static void use(Sink sink) {
                        byte[] data = new byte[16];
                        free data;
                    }
                }
                """);
        Path earlierClasses = root.resolve("earlier-classes");
        Result earlierBuilt = run("--unfreed=off", earlier.toString(),
                "-d", earlierClasses.toString());
        Path keeperClasses = root.resolve("keeper-classes");
        Result keeperBuilt = run("--unfreed=off", "-cp", earlierClasses.toString(),
                keeper.toString(), "-d", keeperClasses.toString());
        require(earlierBuilt.status == 0 && keeperBuilt.status == 0,
                "legitimate separate class builds failed: " + earlierBuilt + " / " + keeperBuilt);
        String finalClasses = keeperClasses + File.pathSeparator + libraryClasses;
        Path rejectedLink = root.resolve("rejected-link");
        Path rejectedIr = root.resolve("rejected.ll");
        Result linkOff = run("--link", "--unfreed=off", "-cp", finalClasses,
                "--main-class", "Keeper", "-o", rejectedLink.toString(),
                "--emit-llvm", rejectedIr.toString());
        Result linkOn = run("--link", "--unfreed=off", "--explain-rejected-free",
                "-cp", finalClasses, "--main-class", "Keeper", "-o", rejectedLink.toString(),
                "--emit-llvm", rejectedIr.toString());
        require(linkOff.status == 1 && linkOn.status == 1 && linkOff.stdout.isEmpty()
                        && linkOn.stdout.isEmpty() && !linkOff.stderr.contains("note:")
                        && linkOn.stderr.contains("cannot free 'data'")
                        && linkOn.stderr.contains("note:")
                        && linkOn.stderr.substring(0, linkOn.stderr.indexOf("note:")).equals(linkOff.stderr)
                        && !Files.exists(rejectedLink) && !Files.exists(rejectedIr),
                "rejected link lost flag transport or primary parity: " + linkOff + " / " + linkOn);

        Path skipped = write(root.resolve("Skipped.iron"), """
                class Base { void keep(Object value) { } }
                class Skipped extends Base {
                    void keep(Object value) { }
                    static Object saved;
                    static void check() {
                        Object value = new Object();
                        saved = value;
                        free value;
                    }
                }
                """);
        Result limited = run("--unfreed=off", "--explain-rejected-free", skipped.toString(),
                "-d", root.resolve("skipped-classes").toString());
        require(limited.status == 1 && limited.stdout.isEmpty()
                        && limited.stderr.contains("ownership analysis was limited because of earlier errors")
                        && limited.stderr.contains("@Override")
                        && !Files.exists(root.resolve("skipped-classes")),
                "skipped refinement lost its limited-analysis note: " + limited);
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static Result run(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int status = Main.run(arguments,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Result(status, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private record Result(int status, String stdout, String stderr) {
    }
}
