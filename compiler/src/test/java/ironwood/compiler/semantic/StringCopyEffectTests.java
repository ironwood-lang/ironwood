// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.*;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

/** Isolates allocation effects from the independently allocating String initializer. */
public final class StringCopyEffectTests {
    private StringCopyEffectTests() {}

    public static void allocationEffects() {
        var span = SourceSpan.at(new SourcePosition(0, 1, 1));
        var type = IrType.reference("ironwood.lang.String");
        var input = new IrValueReference(0, type, span);
        var output = new IrValueReference(1, type, span);
        var parameters = List.of(new IrParameter("input", input, span));
        var done = new IrReturnTerminator(Optional.empty(), span);
        var copy = new IrStringCopyInstruction(output, input, span);
        var direct = new IrFunction("Effects", "copy", "copy", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(copy), done, span)), span);
        var caught = new IrFunction("Effects", "caught", "caught", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(),
                        new IrInvokeTerminator(copy, "done", "failure", span), span),
                        new IrBasicBlock("done", List.of(), done, span),
                        new IrBasicBlock("failure", List.of(new IrExceptionLandingPadInstruction(
                                new IrValueReference(2, IrType.EXCEPTION, span),
                                new IrValueReference(3, IrType.EXCEPTION, span), span),
                                new IrExceptionCaughtInstruction(new IrValueReference(3, IrType.EXCEPTION, span), span)),
                                done, span)), span);
        var helper = new IrFunction("Effects", "helper", "helper", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrCallInstruction(Optional.empty(),
                        "caught", IrType.VOID, List.of(input), span)), done, span)), span);
        var move = new IrFunction("Effects", "move", "move", IrType.VOID, parameters,
                List.of(new IrBasicBlock("entry", List.of(new IrReferenceConversionInstruction(output, input, span)), done, span)), span);
        var analyzer = new ClosedWorldEffectAnalyzer(List.of(helper, caught, direct, move), List.of());
        analyzer.analyze();
        for (String linkage : List.of("copy", "caught", "helper")) {
            if (analyzer.nonThrowingAndAllocationFree(linkage)) {
                throw new AssertionError("String copy incorrectly proved allocation-free: " + linkage);
            }
        }
        if (!analyzer.nonThrowingAndAllocationFree("move")) {
            throw new AssertionError("nonallocating String reference move lost its cleanup proof");
        }
    }
}
