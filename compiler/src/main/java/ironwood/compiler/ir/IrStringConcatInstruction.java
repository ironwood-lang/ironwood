// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

/** Produces one exactly sized immutable String from an ordered set of converted values. */
public record IrStringConcatInstruction(IrValueReference result,
                                        List<IrStringConcatPart> parts,
                                        SourceSpan sourceSpan) implements IrInstruction {
    public IrStringConcatInstruction {
        parts = List.copyOf(parts);
        if (!result.type().equals(IrType.reference("ironwood.lang.String"))
                || parts.size() < 2) {
            throw new IllegalArgumentException(
                    "String concatenation requires a String result and at least two parts");
        }
    }
}
