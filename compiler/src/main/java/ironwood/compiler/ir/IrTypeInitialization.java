// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.ir;

import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Optional;

/** Closed-world one-time initialization metadata for a retained source type. */
public record IrTypeInitialization(String typeName, List<String> prerequisiteTypes,
                                   Optional<String> initializerLinkageName,
                                   SourceSpan sourceSpan) {
    public IrTypeInitialization {
        prerequisiteTypes = List.copyOf(prerequisiteTypes);
        initializerLinkageName = initializerLinkageName == null
                ? Optional.empty() : initializerLinkageName;
    }
}
