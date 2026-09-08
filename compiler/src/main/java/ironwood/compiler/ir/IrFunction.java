// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;

public record IrFunction(String ownerClass, String sourceName, String linkageName, IrType returnType,
                         List<IrParameter> parameters, List<IrBasicBlock> blocks,
                         SourceSpan sourceSpan, String sourceFileName, IrCallableKind kind) {
    public IrFunction {
        parameters = List.copyOf(parameters);
        blocks = List.copyOf(blocks);
        if (sourceFileName == null || sourceFileName.isBlank()) {
            throw new IllegalArgumentException("source filename is required");
        }
        kind = kind == null ? IrCallableKind.METHOD : kind;
    }

    public IrFunction(String ownerClass, String sourceName, String linkageName, IrType returnType,
                      List<IrParameter> parameters, List<IrBasicBlock> blocks,
                      SourceSpan sourceSpan) {
        this(ownerClass, sourceName, linkageName, returnType, parameters, blocks, sourceSpan,
                "<unknown>.iron", IrCallableKind.METHOD);
    }

    public IrFunction withSourceIdentity(String fileName, IrCallableKind callableKind) {
        return new IrFunction(ownerClass, sourceName, linkageName, returnType, parameters, blocks,
                sourceSpan, fileName, callableKind);
    }

    public boolean constructor() {
        return kind == IrCallableKind.CONSTRUCTOR;
    }

    public String traceCallableName() {
        return ownerClass + "." + switch (kind) {
            case CONSTRUCTOR -> "<init>";
            case DESTRUCTOR -> "<destructor>";
            case CLASS_INITIALIZER -> "<clinit>";
            case CONSTRUCTOR_ROLLBACK -> "<constructor-rollback>";
            case METHOD -> sourceName;
        };
    }
}
