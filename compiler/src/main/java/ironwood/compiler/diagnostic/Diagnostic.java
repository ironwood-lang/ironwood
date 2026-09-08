// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.diagnostic;

import ironwood.compiler.source.SourceFile;
import ironwood.compiler.source.SourceSpan;

public record Diagnostic(String message, SourceFile source, SourceSpan span) {
    public Diagnostic {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
    }

    public static Diagnostic error(SourceFile source, SourceSpan span, String message) {
        return new Diagnostic(message, source, span);
    }

    public static Diagnostic global(String message) {
        return new Diagnostic(message, null, null);
    }
}
