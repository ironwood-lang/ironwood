// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code ironwoodc} diagnostics back out of its console output.
 *
 * <p>The builder runs the compiler as a process rather than linking it into the
 * workbench, so the only channel for errors is the text the compiler prints.
 * That text has a fixed shape:
 *
 * <pre>
 * error: cannot free 'a': allocation may still be observed through local 'b'
 *   --&gt; /path/Main.iron:9:14
 *    |
 *  9 |         free a;
 *    |              ^
 * </pre>
 *
 * <p>Only the message line and the location line carry information; the rest is
 * a rendering of source the workbench already has. Deliberately kept free of
 * Eclipse types so it can be exercised without a workbench, which is what
 * catches a change to the compiler's output format before users do.
 */
public final class CompilerOutputParser {

    private static final String ERROR_PREFIX = "error: ";

    /** Matches the location line, for example {@code   --> /path/Main.iron:9:14}. */
    private static final Pattern LOCATION =
            Pattern.compile("^\\s*-->\\s*(.+):(\\d+):(\\d+)\\s*$");

    /**
     * One parsed diagnostic. A diagnostic about the compilation as a whole has
     * no location, so every location field is optional together.
     */
    public record CompilerDiagnostic(String message, Optional<String> path, int line, int column) {

        public boolean located() {
            return path.isPresent();
        }
    }

    private CompilerOutputParser() {
    }

    /**
     * Extracts every diagnostic from compiler output.
     *
     * <p>A message can wrap onto following lines, so continuation lines are
     * appended until the location line or the next message begins. Text that
     * matches nothing is ignored rather than guessed at.
     */
    public static List<CompilerDiagnostic> parse(String output) {
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        StringBuilder pending = null;

        for (String line : output.split("\\R")) {
            Matcher location = LOCATION.matcher(line);
            if (location.matches()) {
                if (pending != null) {
                    diagnostics.add(new CompilerDiagnostic(pending.toString().strip(),
                            Optional.of(location.group(1).strip()),
                            Integer.parseInt(location.group(2)),
                            Integer.parseInt(location.group(3))));
                    pending = null;
                }
                continue;
            }

            if (line.startsWith(ERROR_PREFIX)) {
                // A previous message that never got a location is about the
                // compilation rather than a place in it.
                if (pending != null) {
                    diagnostics.add(unlocated(pending));
                }
                pending = new StringBuilder(line.substring(ERROR_PREFIX.length()));
                continue;
            }

            // The source echo and caret lines are the only other output, and
            // both are recognizable by their gutter.
            if (pending != null && !isSourceEcho(line)) {
                pending.append(' ').append(line.strip());
            }
        }

        if (pending != null) {
            diagnostics.add(unlocated(pending));
        }
        return diagnostics;
    }

    private static CompilerDiagnostic unlocated(StringBuilder message) {
        return new CompilerDiagnostic(message.toString().strip(), Optional.empty(), 0, 0);
    }

    /**
     * Recognizes the echoed source and caret lines, which both carry a
     * {@code |} gutter after optional line-number digits.
     */
    private static boolean isSourceEcho(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        while (index < line.length() && Character.isDigit(line.charAt(index))) {
            index++;
        }
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index < line.length() && line.charAt(index) == '|';
    }
}
