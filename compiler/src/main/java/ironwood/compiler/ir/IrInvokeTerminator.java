// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public record IrInvokeTerminator(IrInstruction call, String normalTarget,
                                 String unwindTarget,
                                 SourceSpan sourceSpan) implements IrTerminator {
    public IrInvokeTerminator {
        if (!(call instanceof IrCallInstruction)
                && !(call instanceof IrVirtualCallInstruction)
                && !(call instanceof IrInterfaceCallInstruction)
                && !(call instanceof IrEnsureTypeInitializedInstruction)
                && !(call instanceof IrAllocateInstruction)
                && !(call instanceof IrArrayAllocateInstruction)
                && !(call instanceof IrObjectToStringInstruction)
                && !(call instanceof IrThrowableDescriptionInstruction)
                && !(call instanceof IrStringCopyInstruction)
                && !(call instanceof IrStringFromCharsInstruction)
                && !(call instanceof IrStringCaseInstruction)
                && !(call instanceof IrStringRepeatInstruction)
                && !(call instanceof IrStringReplaceCharInstruction)
                && !(call instanceof IrStringReplaceTextInstruction)
                && !(call instanceof IrStringJoinInstruction)
                && !(call instanceof IrStringFromUtf8Instruction)
                && !(call instanceof IrStringFromCharRangeInstruction)
                && !(call instanceof IrStringFromRangeInstruction)
                && !(call instanceof IrStringFromIntegerInstruction)
                && !(call instanceof IrStringFromCharacterInstruction)
                && !(call instanceof IrStringConcatInstruction)
                && !(call instanceof IrSystemGetenvInstruction)
                && !(call instanceof IrFileInstruction)
                && !(call instanceof IrStreamInstruction)) {
            throw new IllegalArgumentException("invoke requires a throwing operation");
        }
    }
}
