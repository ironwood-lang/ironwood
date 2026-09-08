// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ir.IrType;

import java.util.List;

/** Compiler-owned allocation contracts for intrinsic callable results. */
final class AllocationResultSemantics {
    private AllocationResultSemantics() {
    }

    static boolean returnsOwnedFresh(CallableSymbol callable) {
        return isObjectToString(callable) || isThrowableDescription(callable) || isStringFromChars(callable)
                || isStringFromCharRange(callable) || isStringFromRange(callable)
                || isStringCase(callable) || isStringRepeat(callable) || isStringReplaceChar(callable)
                || isStringReplaceText(callable) || isStringJoin(callable) || isByteStreamSnapshot(callable)
                || isStringFromInteger(callable) || isStringFromCharacter(callable)
                || isSystemGetenv(callable) || isStringConcat(callable)
                || isSystemProperty(callable) || isThrowableStackTrace(callable)
                || isStringJoinResult(callable)
                || isMutableTextSnapshot(callable)
                || isFreshUtilResult(callable)
                || isFreshTextConversion(callable) || isFreshFileResult(callable)
                || isFreshPathResult(callable);
    }

    private static boolean isMutableTextSnapshot(CallableSymbol callable) {
        if (!callable.sourceName().equals("toString") || callable.isStatic()
                || !callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                || !callable.parameterTypes().isEmpty()) {
            return false;
        }
        return callable.ownerType().equals("ironwood.lang.StringBuffer")
                || callable.ownerType().equals("ironwood.io.StringWriter");
    }

    private static boolean isFreshUtilResult(CallableSymbol callable) {
        IrType string = IrType.reference("ironwood.lang.String");
        if (callable.ownerType().equals("ironwood.util.Arrays")
                && callable.sourceName().equals("toString") && callable.isStatic()
                && callable.returnType().equals(string)
                && callable.parameterTypes().size() == 1
                && callable.parameterTypes().getFirst().isArray()) {
            return true;
        }
        return (callable.ownerType().equals("ironwood.util.Optional")
                || callable.ownerType().equals("ironwood.util.StringJoiner")
                || callable.ownerType().equals("ironwood.util.BitSet"))
                && callable.sourceName().equals("toString") && !callable.isStatic()
                && callable.returnType().equals(string)
                && callable.parameterTypes().isEmpty();
    }

    private static boolean isStringJoinResult(CallableSymbol callable) {
        if (!callable.ownerType().equals("ironwood.lang.String")
                || !callable.sourceName().equals("join") || !callable.isStatic()
                || !callable.returnType().equals(IrType.reference("ironwood.lang.String"))) return false;
        List<IrType> parameters = callable.parameterTypes();
        IrType sequence = IrType.reference("ironwood.lang.CharSequence");
        return !parameters.isEmpty() && parameters.getFirst().equals(sequence)
                && (parameters.size() == 2 && parameters.get(1).isArray()
                    || parameters.size() <= 4 && parameters.stream().allMatch(sequence::equals));
    }

    static boolean isObjectToString(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.Object")
                && callable.sourceName().equals("toString")
                && !callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().isEmpty();
    }

    static boolean isThrowableDescription(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.Throwable")
                && callable.sourceName().equals("renderDescription") && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(
                        IrType.reference("ironwood.lang.Throwable"),
                        IrType.reference("ironwood.lang.String")));
    }

    static boolean isStringFromChars(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("fromChars")
                && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.array(IrType.U16), IrType.I32));
    }

    static boolean isStringCase(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("caseValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"), IrType.I1));
    }

    static boolean isStringRepeat(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("repeatValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"), IrType.I32));
    }

    static boolean isStringReplaceChar(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("replaceCharValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"), IrType.U16, IrType.U16));
    }

    static boolean isStringReplaceText(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("replaceTextValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"),
                        IrType.reference("ironwood.lang.String"), IrType.reference("ironwood.lang.String")));
    }

    static boolean isStringEqualsIgnoreCase(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("equalsIgnoreCaseValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.I1)
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"), IrType.reference("ironwood.lang.String")));
    }

    static boolean isStringJoin(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("joinValue") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.reference("ironwood.lang.String"), IrType.array(IrType.reference("ironwood.lang.String"))));
    }

    static boolean isByteStreamSnapshot(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.io.ByteArrayOutputStream")
                && callable.sourceName().equals("decodeSnapshot") && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.array(IrType.I8), IrType.I32));
    }

    static boolean isStringFromRange(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("fromRange")
                && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.String"), IrType.I32, IrType.I32));
    }

    static boolean isStringFromCharRange(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("fromChars")
                && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(
                IrType.array(IrType.U16), IrType.I32, IrType.I32));
    }

    static boolean isStringFromInteger(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("fromInteger") && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.I64, IrType.I32));
    }

    static boolean isStringFromCharacter(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("fromCharacter") && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(IrType.U16));
    }

    static boolean isSystemGetenv(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.System")
                && callable.sourceName().equals("environmentValue")
                && callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.String")));
    }

    private static boolean isSystemProperty(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.System")
                && callable.sourceName().equals("propertyValue")
                && callable.isStatic()
                && callable.accessModifier() == AccessModifier.PRIVATE
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(List.of(
                IrType.reference("ironwood.lang.String")));
    }

    private static boolean isThrowableStackTrace(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.Throwable")
                && callable.sourceName().equals("getStackTrace")
                && !callable.isStatic()
                && callable.returnType().equals(IrType.array(
                IrType.reference("ironwood.lang.StackTraceElement")))
                && callable.parameterTypes().isEmpty();
    }

    private static boolean isStringConcat(CallableSymbol callable) {
        return callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("concat") && !callable.isStatic()
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                && callable.parameterTypes().equals(
                List.of(IrType.reference("ironwood.lang.String")));
    }

    private static boolean isFreshTextConversion(CallableSymbol callable) {
        if (!callable.returnType().equals(IrType.reference("ironwood.lang.String"))) {
            return false;
        }
        if (callable.ownerType().equals("ironwood.time.Instant")
                && callable.sourceName().equals("toString") && !callable.isStatic()
                && callable.parameterTypes().isEmpty()) {
            return true;
        }
        if (callable.ownerType().equals("ironwood.lang.StackTraceElement")
                && callable.sourceName().equals("toString") && !callable.isStatic()
                && callable.parameterTypes().isEmpty()) {
            return true;
        }
        if (callable.ownerType().equals("ironwood.lang.String")
                && callable.sourceName().equals("valueOf") && callable.isStatic()) {
            return callable.parameterTypes().equals(List.of(IrType.U16))
                    || callable.parameterTypes().equals(List.of(IrType.I32))
                    || callable.parameterTypes().equals(List.of(IrType.I64))
                    || callable.parameterTypes().equals(List.of(IrType.F32))
                    || callable.parameterTypes().equals(List.of(IrType.F64))
                    || callable.parameterTypes().equals(List.of(IrType.array(IrType.U16)))
                    || callable.parameterTypes().equals(List.of(IrType.array(IrType.U16), IrType.I32, IrType.I32));
        }
        if (callable.sourceName().equals("toString") && callable.isStatic()) {
            return callable.ownerType().equals("ironwood.lang.Character")
                    && callable.parameterTypes().equals(List.of(IrType.U16))
                    || callable.ownerType().equals("ironwood.lang.Byte")
                    && callable.parameterTypes().equals(List.of(IrType.I8))
                    || callable.ownerType().equals("ironwood.lang.Short")
                    && callable.parameterTypes().equals(List.of(IrType.I16))
                    || callable.ownerType().equals("ironwood.lang.Integer")
                    && (callable.parameterTypes().equals(List.of(IrType.I32))
                    || callable.parameterTypes().equals(List.of(IrType.I32, IrType.I32)))
                    || callable.ownerType().equals("ironwood.lang.Long")
                    && (callable.parameterTypes().equals(List.of(IrType.I64))
                    || callable.parameterTypes().equals(List.of(IrType.I64, IrType.I32)))
                    || callable.ownerType().equals("ironwood.lang.Float")
                    && callable.parameterTypes().equals(List.of(IrType.F32))
                    || callable.ownerType().equals("ironwood.lang.Double")
                    && callable.parameterTypes().equals(List.of(IrType.F64));
        }
        if (callable.isStatic()
                && (callable.sourceName().equals("toHexString")
                || callable.sourceName().equals("toOctalString")
                || callable.sourceName().equals("toBinaryString"))) {
            return callable.ownerType().equals("ironwood.lang.Integer")
                    && callable.parameterTypes().equals(List.of(IrType.I32))
                    || callable.ownerType().equals("ironwood.lang.Long")
                    && callable.parameterTypes().equals(List.of(IrType.I64));
        }
        if (callable.sourceName().equals("toUnsignedString") && callable.isStatic()) {
            return callable.ownerType().equals("ironwood.lang.Integer")
                    && callable.parameterTypes().equals(List.of(IrType.I32, IrType.I32))
                    || callable.ownerType().equals("ironwood.lang.Long")
                    && callable.parameterTypes().equals(List.of(IrType.I64, IrType.I32));
        }
        return false;
    }

    private static boolean isFreshFileResult(CallableSymbol callable) {
        if (!callable.ownerType().equals("ironwood.nio.file.Files") || !callable.isStatic()) {
            return false;
        }
        return callable.sourceName().equals("readAllBytes")
                && callable.returnType().equals(IrType.array(IrType.I8))
                || callable.sourceName().equals("readAllBytesValue")
                && callable.returnType().equals(IrType.array(IrType.I8))
                || callable.sourceName().equals("readString")
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                || callable.sourceName().equals("readStringValue")
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                || callable.sourceName().equals("nextDirectoryEntryValue")
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                || callable.sourceName().equals("nextDirectoryEntry")
                && callable.returnType().equals(IrType.reference("ironwood.lang.String"))
                || callable.sourceName().equals("readAttributesValue")
                && callable.returnType().equals(IrType.array(IrType.I64))
                || callable.sourceName().equals("newDirectoryStream")
                && callable.returnType().equals(IrType.reference(
                "ironwood.nio.file.DirectoryStream",
                List.of(IrType.reference("ironwood.nio.file.Path"))))
                || callable.sourceName().equals("readAttributes")
                && callable.returnType().equals(IrType.reference(
                "ironwood.nio.file.attribute.BasicFileAttributes"))
                || (callable.ownerType().equals("ironwood.nio.file.DirectoryStream")
                || callable.ownerType().equals("ironwood.nio.file.UnixDirectoryStream"))
                && callable.sourceName().equals("nextEntry")
                && callable.returnType().equals(IrType.reference("ironwood.nio.file.Path"));
    }

    private static boolean isFreshPathResult(CallableSymbol callable) {
        IrType path = IrType.reference("ironwood.nio.file.Path");
        IrType string = IrType.reference("ironwood.lang.String");
        if (callable.ownerType().equals("ironwood.nio.file.Paths") && callable.isStatic()) {
            if ((callable.sourceName().equals("get") && callable.returnType().equals(path))
                    || (callable.sourceName().equals("currentDirectory")
                    || callable.sourceName().equals("currentDirectoryValue")
                    || callable.sourceName().equals("normalizeSyntax")
                    || callable.sourceName().equals("normalizePath")
                    || callable.sourceName().equals("fileName")
                    || callable.sourceName().equals("parent")
                    || callable.sourceName().equals("resolveSibling")
                    || callable.sourceName().equals("absolutePath")
                    || callable.sourceName().equals("absolutePathValue")
                    || callable.sourceName().equals("resolve"))
                    && callable.returnType().equals(string)) {
                return true;
            }
        }
        if (callable.ownerType().equals("ironwood.nio.file.Path")) {
            return callable.sourceName().equals("of") && callable.isStatic()
                    && callable.returnType().equals(path)
                    || !callable.isStatic() && callable.returnType().equals(path)
                    && switch (callable.sourceName()) {
                        case "getFileName", "getParent", "getRoot", "resolve",
                                "resolveSibling", "normalize", "toAbsolutePath" -> true;
                        default -> false;
                    };
        }
        return callable.ownerType().equals("ironwood.nio.file.UnixPath")
                && !callable.isStatic() && callable.returnType().equals(path)
                && switch (callable.sourceName()) {
                    case "getFileName", "getParent", "getRoot", "resolve",
                            "resolveSibling", "normalize", "toAbsolutePath" -> true;
                    default -> false;
                };
    }
}
