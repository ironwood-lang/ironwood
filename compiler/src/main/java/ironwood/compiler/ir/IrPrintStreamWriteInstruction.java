// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

public record IrPrintStreamWriteInstruction(IrOperand stream,
                                            Optional<IrStringConcatPart> value,
                                            boolean newline,
                                            SourceSpan sourceSpan) implements IrInstruction {
    public IrPrintStreamWriteInstruction {
        if (!stream.type().equals(IrType.reference("ironwood.io.PrintStream"))) {
            throw new IllegalArgumentException("PrintStream write requires a PrintStream receiver");
        }
        value = value == null ? Optional.empty() : value;
        if (value.isEmpty() && !newline) {
            throw new IllegalArgumentException("an empty PrintStream write must emit a newline");
        }
    }
}
