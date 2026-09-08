// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

public sealed interface IrInstruction permits IrAddSecondaryExceptionInstruction,
        IrAllocateInstruction, IrAllocationCountInstruction,
        IrLiveAllocationCountInstruction,
        IrArrayAllocateInstruction,
        IrArrayBoundsCheckInstruction, IrArrayLengthCheckInstruction,
        IrArrayLengthInstruction, IrArrayLoadInstruction,
        IrArrayStoreInstruction, IrArrayTypeTestInstruction,
        IrBinaryInstruction, IrCallInstruction, IrFieldLoadInstruction, IrFieldStoreInstruction,
        IrPrintStreamPrintlnInstruction, IrPrintStreamWriteInstruction,
        IrPrintStreamFlushInstruction, IrPrintStreamCheckErrorInstruction,
        IrExceptionCaughtInstruction,
        IrExceptionLandingPadInstruction, IrFreeInstruction, IrDestroyArrayElementsInstruction, IrInstanceOfInstruction,
        IrFileInstruction, IrStreamInstruction,
        IrCharacterInstruction, IrFloatingBitsInstruction, IrFloatingParseInstruction,
        IrRawDeallocateInstruction,
        IrReleaseOwnedToStringResultInstruction,
        IrReleaseOwnedThrowableMessageInstruction,
        IrRollbackInstruction,
        IrIdentityHashCodeInstruction, IrInterfaceCallInstruction, IrNullCheckInstruction,
        IrEnsureTypeInitializedInstruction,
        IrObjectHashCodeInstruction,
        IrObjectToStringInstruction,
        IrThrowableDescriptionInstruction, IrThrowableTraceInstruction,
        IrStringCharAtInstruction, IrStringConcatInstruction, IrStringEqualsInstruction,
        IrStringCaseInstruction, IrStringRepeatInstruction, IrStringReplaceCharInstruction, IrStringReplaceTextInstruction, IrStringEqualsIgnoreCaseInstruction, IrStringJoinInstruction, IrStringCopyInstruction, IrStringFromCharsInstruction, IrStringFromUtf8Instruction,
        IrStringFromCharRangeInstruction, IrStringFromRangeInstruction,
        IrStringFromIntegerInstruction, IrStringFromCharacterInstruction,
        IrStringHashCodeInstruction,
        IrMathUnaryInstruction, IrMathBinaryInstruction,
        IrNumericConversionInstruction, IrPhiInstruction, IrReferenceConversionInstruction, IrUnaryInstruction,
        IrSecondaryExceptionAtInstruction, IrSecondaryExceptionCountInstruction,
        IrStaticFieldLoadInstruction, IrStaticFieldStoreInstruction,
        IrSystemArrayCopyInstruction, IrSystemGetenvInstruction,
        IrSystemClockInstruction, IrSystemExitInstruction, IrSystemPropertyInstruction,
        IrVirtualCallInstruction {
    SourceSpan sourceSpan();
}
