// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ast;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

/** One source-ordered constant in an enum declaration. */
public record EnumConstant(String name, SourceSpan nameSpan, List<Expression> arguments,
                           Optional<AnonymousClassBody> classBody,
                           SourceSpan span) {
    public EnumConstant {
        arguments = List.copyOf(arguments);
        classBody = classBody == null ? Optional.empty() : classBody;
    }
}
