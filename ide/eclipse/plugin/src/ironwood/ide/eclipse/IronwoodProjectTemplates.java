// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import java.util.Locale;

/**
 * Source the new-project wizard writes into a fresh project.
 *
 * <p>The sample is a working program and a working test suite rather than a
 * stub, because the point of a new project is to have something that builds and
 * runs before anything is edited. Managed values may be shared; reclamation
 * occurs only at an explicit collection boundary.
 */
public final class IronwoodProjectTemplates {

    private IronwoodProjectTemplates() {
    }

    /** Turns a project name into a usable type name. */
    public static String typeNameFor(String projectName) {
        StringBuilder name = new StringBuilder();
        boolean capitalize = true;
        for (int index = 0; index < projectName.length(); index++) {
            char character = projectName.charAt(index);
            if (!Character.isLetterOrDigit(character)) {
                capitalize = true;
                continue;
            }
            if (name.isEmpty() && !Character.isLetter(character)) {
                continue;
            }
            name.append(capitalize ? Character.toUpperCase(character) : character);
            capitalize = false;
        }
        return name.isEmpty() ? "Main" : name.toString();
    }

    /** Suggests a package name for a project name. */
    public static String packageNameFor(String projectName) {
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < projectName.length(); index++) {
            char character = projectName.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                name.append(Character.toLowerCase(character));
            }
        }
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name.insert(0, "app");
        }
        return name.toString().toLowerCase(Locale.ROOT);
    }

    /** Rejects a package name the compiler would not accept. */
    public static String validatePackageName(String packageName) {
        if (packageName.isBlank()) {
            return "Enter a package name.";
        }
        for (String segment : packageName.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return "Package segments cannot be empty.";
            }
            if (!Character.isJavaIdentifierStart(segment.charAt(0))) {
                return "'" + segment + "' is not a valid package segment.";
            }
            for (int index = 1; index < segment.length(); index++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(index))) {
                    return "'" + segment + "' is not a valid package segment.";
                }
            }
        }
        return null;
    }

    public static String mainSource(String packageName, String typeName) {
        return """
                // SPDX-License-Identifier: MIT OR Apache-2.0

                package %1$s;

                // Greets the name given as the first argument, or "world" when there is
                // none. Managed results remain valid while reachable.
                public class %2$s {

                    public static int main(String[] args) {

                        String name = args.length > 0 ? args[0] : "world";
                        StringBuilder builder = new StringBuilder();
                        builder.append("Hello, ");
                        builder.append(name);
                        builder.append('!');

                        String message = builder.toString();
                        System.out.println(message);

                        return 0;
                    }
                }
                """.formatted(packageName, typeName);
    }

    public static String testSource(String packageName, String typeName) {
        return """
                // SPDX-License-Identifier: MIT OR Apache-2.0

                package %1$s;

                import static ironwood.testing.Assertions.*;
                import ironwood.testing.TestSuite;

                // Each case marked with @Test becomes part of a native test executable
                // whose main class is this suite: there is no separate runner.
                //
                public final class %2$sTests extends TestSuite {

                    @Test
                    private void buildsAGreeting() {

                        StringBuilder builder = new StringBuilder();
                        builder.append("Hello, ");
                        builder.append("Ironwood");
                        builder.append('!');
                        String message = builder.toString();
                        boolean matched = message.equals("Hello, Ironwood!");

                        assertTrue(matched, "greeting text");
                    }

                    @Test
                    private void preservesSharedResultsAcrossCollection() {

                        StringBuilder builder = new StringBuilder();
                        builder.append("Hello, Ironwood!");
                        String message = builder.toString();
                        String alias = message;
                        ironwood.lang.Memory.collect();
                        assertEquals("Hello, Ironwood!", alias);
                    }
                }
                """.formatted(packageName, typeName);
    }
}
