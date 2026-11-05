// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.semantic;

import ironwood.compiler.ast.AccessModifier;
import ironwood.compiler.ast.ClassDeclaration;
import ironwood.compiler.diagnostic.Diagnostic;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.source.SourceFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Source authority is preserved by format-1 source/class/archive reconstruction. */
final class MemoryIntrinsic {
    static final String TYPE = "ironwood.lang.Memory";
    private static final String SOURCE_SHA256 = "3921b82123de14ac6ec5de214884783cd2d8ef80cb033e32e5fba5c5a0bd8890";

    private MemoryIntrinsic() {}

    static void validate(TypeSymbol type, List<Diagnostic> diagnostics) {
        if (!type.name().equals(TYPE)) return;
        if (!(type.declaration() instanceof ClassDeclaration declaration)
                || declaration.accessModifier() != AccessModifier.PUBLIC
                || !declaration.isFinal() || declaration.isAbstract()
                || !declaration.typeParameters().isEmpty()
                || declaration.methods().size() != 1
                || type.declaredMethods().size() != 1
                || type.declaredMethods().values().stream().noneMatch(method -> signature(method)
                        && method.body().isPresent() && method.body().orElseThrow().statements().isEmpty())) {
            diagnostics.add(Diagnostic.error(type.source(), type.declaration().nameSpan(),
                    "invalid collection intrinsic: ironwood.lang.Memory must declare public static void collect()"));
        } else if (!trusted(type.source())) {
            diagnostics.add(Diagnostic.error(type.source(), type.declaration().nameSpan(),
                    "unrecognized collection intrinsic declaration; rebuild ironwood.lang.Memory from the bundled standard library"));
        }
    }

    static boolean matches(CallableSymbol method, SourceFile source) {
        return signature(method) && trusted(source);
    }

    private static boolean signature(CallableSymbol method) {
        return method.ownerType().equals(TYPE) && method.sourceName().equals("collect")
                && method.kind() == IrCallableKind.METHOD && method.accessModifier() == AccessModifier.PUBLIC
                && method.isStatic() && !method.isAbstract() && method.returnType().equals(IrType.VOID)
                && method.parameterTypes().isEmpty() && method.typeVariables().isEmpty()
                && method.thrownTypes().isEmpty();
    }

    private static boolean trusted(SourceFile source) {
        try {
            return SOURCE_SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.content().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
