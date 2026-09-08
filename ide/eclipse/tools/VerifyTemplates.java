// SPDX-License-Identifier: MIT OR Apache-2.0

// Checks that the new-project wizard generates source that actually compiles.
//
// A wizard that produces a project with errors in it is worse than no wizard,
// and the templates are plain text that no compiler checks. This writes them
// into the layout the wizard creates and compiles both with the real Ironwood
// compiler, main sources and the test suite together.
//
//   java -cp ide/eclipse/target/classes \
//       ide/eclipse/tools/VerifyTemplates.java \
//       <ironwoodc> <testing-archive> <work-directory>

import ironwood.ide.eclipse.IronwoodProjectTemplates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class VerifyTemplates {

    /** A project name with punctuation, so the name derivation is exercised too. */
    private static final String PROJECT_NAME = "hello-eclipse sample";

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 3) {
            System.err.println(
                    "usage: VerifyTemplates <ironwoodc> <testing-archive> <work-directory>");
            System.exit(2);
        }

        Path compiler = Path.of(args[0]);
        Path testingArchive = Path.of(args[1]);
        Path work = Path.of(args[2]);

        String typeName = IronwoodProjectTemplates.typeNameFor(PROJECT_NAME);
        String packageName = IronwoodProjectTemplates.packageNameFor(PROJECT_NAME);

        List<String> failures = new ArrayList<>();
        if (!"HelloEclipseSample".equals(typeName)) {
            failures.add("type name derivation produced '" + typeName + "'");
        }
        if (!"helloeclipsesample".equals(packageName)) {
            failures.add("package name derivation produced '" + packageName + "'");
        }
        if (IronwoodProjectTemplates.validatePackageName(packageName) != null) {
            failures.add("the derived package name does not validate");
        }
        if (IronwoodProjectTemplates.validatePackageName("9bad") == null) {
            failures.add("an invalid package name was accepted");
        }

        Path packagePath = Path.of(packageName.replace('.', '/'));
        Path mainDirectory = work.resolve("src/main/ironwood").resolve(packagePath);
        Path testDirectory = work.resolve("src/test/ironwood").resolve(packagePath);
        Files.createDirectories(mainDirectory);
        Files.createDirectories(testDirectory);

        Path mainFile = mainDirectory.resolve(typeName + ".iron");
        Path testFile = testDirectory.resolve(typeName + "Tests.iron");
        Files.writeString(mainFile,
                IronwoodProjectTemplates.mainSource(packageName, typeName),
                StandardCharsets.UTF_8);
        Files.writeString(testFile,
                IronwoodProjectTemplates.testSource(packageName, typeName),
                StandardCharsets.UTF_8);

        // Compile exactly the way the builder does: both roots on the source
        // path, the testing archive on the classpath.
        List<String> command = List.of(
                compiler.toString(),
                "--source-path", work.resolve("src/main/ironwood")
                        + java.io.File.pathSeparator + work.resolve("src/test/ironwood"),
                "-cp", testingArchive.toString(),
                "-d", work.resolve("classes").toString(),
                mainFile.toString(),
                testFile.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();

        if (status != 0) {
            failures.add("the generated sources did not compile");
        }

        if (!failures.isEmpty()) {
            System.err.println("template verification failed with "
                    + failures.size() + " problem(s):");
            failures.forEach(failure -> System.err.println("  " + failure));
            if (!output.isBlank()) {
                System.err.println("compiler output:");
                output.lines().forEach(line -> System.err.println("  " + line));
            }
            System.exit(1);
        }

        System.out.println("template verification passed: " + typeName
                + " and " + typeName + "Tests compile as generated");
    }
}
