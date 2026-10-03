// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrField;
import ironwood.compiler.source.SourceSpan;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete reference-store attribution for one entry, including exceptional paths.
 * Input indices include the receiver. Admission must separately prove that every
 * holder is an ownership root, that dependencies are acyclic and that commit is bounded.
 */
public record BridgeRetentionContract(List<Slot> slots) {
    public BridgeRetentionContract {
        slots = List.copyOf(slots);
    }

    public record Slot(int holderInput, IrField field, Set<Integer> valueInputs,
                       boolean mayClear, List<Site> sites) {
        public Slot {
            if (holderInput < 0 || valueInputs.stream().anyMatch(index -> index < 0)) {
                throw new IllegalArgumentException("slot inputs must be resolved parameter indices");
            }
            Objects.requireNonNull(field);
            valueInputs = Set.copyOf(valueInputs);
            sites = List.copyOf(sites);
        }
    }

    public record Site(String callable, String sourceFile, SourceSpan span) {
        public Site {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(sourceFile);
            Objects.requireNonNull(span);
        }
    }
}
