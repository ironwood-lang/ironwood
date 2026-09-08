// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

/**
 * Converts compiler source locations into protocol ranges.
 *
 * <p>Ironwood counts lines and columns from one, the protocol counts both from
 * zero, and getting that wrong shifts every marker by a character. Keeping the
 * conversion in one place means it is wrong nowhere or everywhere.
 */
public final class Ranges {

    private static final Range START_OF_FILE =
            new Range(new Position(0, 0), new Position(0, 0));

    private Ranges() {
    }

    /**
     * Converts a span, widening a zero-width one by a character so that it
     * renders as something the reader can see and click.
     */
    public static Range of(SourceSpan span) {
        if (span == null) {
            return START_OF_FILE;
        }
        Position start = of(span.start());
        Position end = of(span.end());
        if (start.equals(end)) {
            end = new Position(end.getLine(), end.getCharacter() + 1);
        }
        return new Range(start, end);
    }

    public static Position of(SourcePosition position) {
        return new Position(Math.max(0, position.line() - 1), Math.max(0, position.column() - 1));
    }

    /**
     * Reports whether a position falls inside a span, used to find the
     * declaration or name the cursor is on.
     */
    public static boolean contains(SourceSpan span, Position position) {
        if (span == null) {
            return false;
        }
        Range range = of(span);
        return isAtOrAfter(position, range.getStart()) && isAtOrAfter(range.getEnd(), position);
    }

    private static boolean isAtOrAfter(Position later, Position earlier) {
        if (later.getLine() != earlier.getLine()) {
            return later.getLine() > earlier.getLine();
        }
        return later.getCharacter() >= earlier.getCharacter();
    }
}
