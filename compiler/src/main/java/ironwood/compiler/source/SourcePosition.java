// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.source;

public record SourcePosition(int offset, int line, int column) {
    public SourcePosition {
        if (offset < 0 || line < 1 || column < 1) {
            throw new IllegalArgumentException("invalid source position");
        }
    }
}
