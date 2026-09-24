// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.diagnostic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

/** A related message whose location, when known, belongs to its own source. */
public record DiagnosticNote(String message, SourceFile source, SourceSpan span) {
    public DiagnosticNote {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic note message must not be blank");
        }
        if ((source == null) != (span == null)) {
            throw new IllegalArgumentException("diagnostic note source and span must be present together");
        }
    }

    public DiagnosticNote(String message) {
        this(message, null, null);
    }
}
