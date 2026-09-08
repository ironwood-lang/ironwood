// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.source;

public record SourceSpan(SourcePosition start, SourcePosition end) {
    public SourceSpan {
        if (start == null || end == null || end.offset() < start.offset()) {
            throw new IllegalArgumentException("invalid source span");
        }
    }

    public static SourceSpan at(SourcePosition position) {
        return new SourceSpan(position, position);
    }
}
