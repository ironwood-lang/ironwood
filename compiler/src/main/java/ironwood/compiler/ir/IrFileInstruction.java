// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.Optional;

/** Typed whole-file and lexical-path operations owned by the native runtime boundary. */
public record IrFileInstruction(IrValueReference result, Operation operation,
                                Optional<IrOperand> path, Optional<IrOperand> value,
                                SourceSpan sourceSpan) implements IrInstruction {
    public enum Operation {
        READ_ALL_BYTES,
        READ_STRING,
        WRITE_BYTES,
        WRITE_STRING,
        WRITE_CHARS,
        DELETE,
        CREATE_DIRECTORIES,
        COPY,
        MOVE,
        OPEN_DIRECTORY,
        DIRECTORY_HAS_NEXT,
        NEXT_DIRECTORY_ENTRY,
        CLOSE_DIRECTORY,
        READ_ATTRIBUTES,
        FILE_KIND,
        FILE_KIND_NOFOLLOW,
        FILE_SIZE,
        SAME_FILE,
        LAST_ERROR,
        CURRENT_DIRECTORY,
        NORMALIZE_SYNTAX,
        NORMALIZE_PATH,
        FILE_NAME,
        PARENT,
        RESOLVE,
        RESOLVE_SIBLING,
        ABSOLUTE_PATH
    }

    public IrFileInstruction {
        IrType string = IrType.reference("ironwood.lang.String");
        IrType bytes = IrType.array(IrType.I8);
        IrType longs = IrType.array(IrType.I64);
        IrType expectedResult = switch (operation) {
            case READ_ALL_BYTES -> bytes;
            case READ_ATTRIBUTES -> longs;
            case READ_STRING, CURRENT_DIRECTORY, NORMALIZE_SYNTAX, NORMALIZE_PATH,
                    FILE_NAME, PARENT, RESOLVE, RESOLVE_SIBLING, ABSOLUTE_PATH,
                    NEXT_DIRECTORY_ENTRY -> string;
            case WRITE_BYTES, WRITE_STRING, WRITE_CHARS, DELETE, CREATE_DIRECTORIES,
                    COPY, MOVE, DIRECTORY_HAS_NEXT, CLOSE_DIRECTORY, FILE_KIND,
                    FILE_KIND_NOFOLLOW, LAST_ERROR, SAME_FILE -> IrType.I32;
            case FILE_SIZE, OPEN_DIRECTORY -> IrType.I64;
        };
        if (!result.type().equals(expectedResult)) {
            throw new IllegalArgumentException("file operation result type does not match operation");
        }
        boolean needsPath = operation != Operation.LAST_ERROR
                && operation != Operation.CURRENT_DIRECTORY;
        IrType expectedPath = switch (operation) {
            case DIRECTORY_HAS_NEXT, NEXT_DIRECTORY_ENTRY, CLOSE_DIRECTORY -> IrType.I64;
            default -> string;
        };
        if (path.isPresent() != needsPath
                || path.isPresent() && !path.orElseThrow().type().equals(expectedPath)) {
            throw new IllegalArgumentException("file operation path does not match operation");
        }
        IrType expectedValue = switch (operation) {
            case WRITE_BYTES -> bytes;
            case WRITE_STRING, RESOLVE, RESOLVE_SIBLING, SAME_FILE, COPY, MOVE -> string;
            case WRITE_CHARS -> IrType.array(IrType.U16);
            case READ_ATTRIBUTES -> IrType.I1;
            default -> null;
        };
        if (expectedValue == null && value.isPresent()
                || expectedValue != null && (value.isEmpty()
                || !value.orElseThrow().type().equals(expectedValue))) {
            throw new IllegalArgumentException("file operation value does not match operation");
        }
    }
}
