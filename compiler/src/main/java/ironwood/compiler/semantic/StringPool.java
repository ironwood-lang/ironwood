// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrStringConstant;
import ironwood.compiler.source.SourceSpan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StringPool {
    private final Map<String, IrStringConstant> constants = new LinkedHashMap<>();

    IrStringConstant intern(String value, SourceSpan span) {
        return constants.computeIfAbsent(value, ignored -> new IrStringConstant(constants.size(), value,
                value.length(), utf8Length(value), span));
    }

    List<IrStringConstant> constants() {
        return List.copyOf(constants.values());
    }

    private static int utf8Length(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            int codePoint;
            if (Character.isHighSurrogate(unit) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                codePoint = Character.toCodePoint(unit, value.charAt(++index));
            } else if (Character.isSurrogate(unit)) {
                codePoint = 0xFFFD;
            } else {
                codePoint = unit;
            }
            length += codePoint <= 0x7F ? 1 : codePoint <= 0x7FF ? 2
                    : codePoint <= 0xFFFF ? 3 : 4;
        }
        return length;
    }
}
