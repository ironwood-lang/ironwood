// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;
import ironwood.compiler.semantic.BridgeRetentionAnalyzer;
import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Isolates runtime text effects from producer dispatch, array publication and cleanup graphs. */
final class BridgeTextRetentionTests {
    private BridgeTextRetentionTests() {}

    static void effects() {
        var span = SourceSpan.at(new SourcePosition(0, 1, 1));
        var string = IrType.reference("ironwood.lang.String");
        var text = new IrValueReference(0, string, span);
        var object = new IrValueReference(1, IrType.reference("ironwood.lang.Throwable"), span);
        var chars = new IrValueReference(2, IrType.array(IrType.U16), span);
        var bytes = new IrValueReference(3, IrType.array(IrType.I8), span);
        var result = new IrValueReference(4, string, span);
        var zero = new IrConstant(IrType.I32, 0, span);
        var parameters = List.of(new IrParameter("text", text, span), new IrParameter("object", object, span));
        List<IrInstruction> copies = List.of(new IrStringCopyInstruction(result, text, span),
                new IrStringConcatInstruction(result, List.of(new IrStringConcatPart(IrStringConcatPartKind.STRING, text),
                        new IrStringConcatPart(IrStringConcatPartKind.INTEGER, zero)), span),
                new IrStringFromCharsInstruction(result, chars, zero, span),
                new IrStringFromCharRangeInstruction(result, chars, zero, zero, span),
                new IrStringFromUtf8Instruction(result, bytes, zero, span),
                new IrObjectToStringInstruction(result, object, span),
                new IrThrowableDescriptionInstruction(result, object, text, span));
        var functions = new ArrayList<IrFunction>();
        var field = new IrStaticField("Text", "saved", string, false, false, false, new IrNull(string, span), span);
        var published = new IrStaticFieldStoreInstruction(field, text, span);
        for (int index = 0; index < copies.size(); index++) {
            for (boolean publish : List.of(false, true)) {
                String name = (publish ? "published" : "copy") + index;
                var body = new ArrayList<IrInstruction>(List.of(new IrArrayAllocateInstruction(chars, IrType.U16, zero, span),
                        new IrArrayAllocateInstruction(bytes, IrType.I8, zero, span), copies.get(index)));
                if (publish) body.add(published);
                functions.add(new IrFunction("Text", name, name, string, parameters,
                        List.of(new IrBasicBlock("entry", body, new IrReturnTerminator(Optional.of(result), span), span)), span));
            }
        }
        for (var cleanup : List.of(new IrReleaseOwnedToStringResultInstruction(object, text, span),
                new IrReleaseOwnedThrowableMessageInstruction(object, text, span))) {
            String name = cleanup.getClass().getSimpleName();
            functions.add(new IrFunction("Text", name, name, IrType.VOID, parameters,
                    List.of(new IrBasicBlock("entry", List.of(cleanup), new IrReturnTerminator(Optional.empty(), span), span)), span));
        }
        var unknowns = List.of(new IrSystemArrayCopyInstruction(object, zero, object, zero, zero, span),
                new IrAddSecondaryExceptionInstruction(object, object, span));
        for (var unknown : unknowns) {
            String name = unknown.getClass().getSimpleName();
            functions.add(new IrFunction("Text", name, name, IrType.VOID, parameters,
                    List.of(new IrBasicBlock("entry", List.of(unknown), new IrReturnTerminator(Optional.empty(), span), span)), span));
        }
        var program = new IrProgram("text", List.of(), List.of(field),
                List.of(new IrTypeInitialization("Text", List.of(), Optional.empty(), span)), List.of(), List.of(), List.of(),
                functions, Optional.empty(), Optional.empty());
        var roots = BridgeRootSet.resolve(program, functions.stream().map(BridgeCallableId::of).toList());
        var proofs = BridgeRetentionAnalyzer.analyze(program, roots);
        var borrowed = BridgeRetentionAnalyzer.borrowedStringResults(program, roots);
        var immortal = BridgeRetentionAnalyzer.immortalStringResults(program, roots);
        for (var entry : proofs.entrySet()) {
            var name = entry.getKey().name();
            var expected = name.startsWith("published") ? BridgeProof.Status.REJECTED
                    : unknowns.stream().anyMatch(instruction -> instruction.getClass().getSimpleName().equals(name))
                    ? BridgeProof.Status.UNKNOWN : BridgeProof.Status.PROVED;
            if (entry.getValue().status() != expected) throw new AssertionError(entry.toString());
            if (name.startsWith("copy") && (borrowed.contains(entry.getKey()) || immortal.contains(entry.getKey()))) {
                throw new AssertionError("fresh text result acquired borrowed/immortal cleanup authority");
            }
        }
    }
}
