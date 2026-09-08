// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;
import java.util.List;
import java.util.Optional;

/** Private Throwable storage and rendering operations, without public native handles. */
public record IrThrowableTraceInstruction(Optional<IrValueReference> result, Operation operation,
                                          List<IrOperand> arguments, SourceSpan sourceSpan)
        implements IrInstruction {
    public enum Operation {
        CAPTURE("captureTrace", "capture", IrType.VOID, List.of(throwable())),
        RELEASE("releaseTrace", "release", IrType.VOID, List.of(throwable())),
        COMMON("commonTrace", "common", IrType.I32, List.of(throwable(), throwable())),
        PRINT("printTraceFrames", "print", IrType.VOID, List.of(throwable(), stream(), IrType.I32, IrType.I32)),
        EMERGENCY("printEmergencyTrace", "emergency", IrType.VOID, List.of(throwable(), stream())),
        ARRAY("getStackTrace", "array", IrType.array(stackTraceElement()), List.of(throwable()));

        private final String sourceName;
        private final String suffix;
        private final IrType returnType;
        private final List<IrType> parameterTypes;
        Operation(String sourceName, String suffix, IrType returnType, List<IrType> parameterTypes) {
            this.sourceName = sourceName;
            this.suffix = suffix;
            this.returnType = returnType;
            this.parameterTypes = parameterTypes;
        }
        public String sourceName() { return sourceName; }
        public String runtimeName() { return "ironwood_throwable_trace_" + suffix; }
        public IrType returnType() { return returnType; }
        public List<IrType> parameterTypes() { return parameterTypes; }
        private static IrType throwable() { return IrType.reference("ironwood.lang.Throwable"); }
        private static IrType stream() { return IrType.reference("ironwood.io.PrintStream"); }
        private static IrType stackTraceElement() {
            return IrType.reference("ironwood.lang.StackTraceElement");
        }
    }

    public IrThrowableTraceInstruction {
        arguments = List.copyOf(arguments);
        if (!result.map(IrValueReference::type).orElse(IrType.VOID).equals(operation.returnType())
                || arguments.size() != operation.parameterTypes().size()) {
            throw new IllegalArgumentException("Throwable trace operation signature mismatch");
        }
        for (int index = 0; index < arguments.size(); index++) {
            IrType expected = operation.parameterTypes().get(index);
            IrType actual = arguments.get(index).type();
            if (expected.isReference() ? !actual.isReference() : !actual.equals(expected)) {
                throw new IllegalArgumentException("Throwable trace operand type mismatch");
            }
        }
    }
}
