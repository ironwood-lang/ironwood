// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

/** Allocation-free Unicode 15.0 Character property or simple-case query. */
public record IrCharacterInstruction(IrValueReference result, Operation operation,
                                     IrOperand codePoint,
                                     SourceSpan sourceSpan) implements IrInstruction {
    public IrCharacterInstruction {
        if (!result.type().equals(IrType.I32) || !codePoint.type().equals(IrType.I32)) {
            throw new IllegalArgumentException("Character property operation requires int values");
        }
    }

    public enum Operation {
        DIGIT("ironwood_character_is_digit"),
        LETTER("ironwood_character_is_letter"),
        UPPER("ironwood_character_is_upper"),
        LOWER("ironwood_character_is_lower"),
        TO_UPPER("ironwood_character_to_upper"),
        TO_LOWER("ironwood_character_to_lower"),
        DIGIT_VALUE("ironwood_character_digit_value"),
        NUMERIC_VALUE("ironwood_character_numeric_value");

        private final String functionName;

        Operation(String functionName) {
            this.functionName = functionName;
        }

        public String functionName() {
            return this.functionName;
        }
    }
}
