// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrFunction;
import ironwood.compiler.ir.IrProgram;
import ironwood.compiler.source.SourceSpan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit internal roots for P0 analysis and subsequent library linking.
 * Resolution establishes identity only, never a lifetime or export permission.
 */
public record BridgeRootSet(List<Root> roots, List<Problem> problems) {
    public BridgeRootSet {
        roots = List.copyOf(roots);
        problems = List.copyOf(problems);
        if (!problems.isEmpty() && !roots.isEmpty()) {
            throw new IllegalArgumentException("failed bridge root resolution cannot expose partial roots");
        }
    }

    public record Root(BridgeCallableId callable, String sourceFile, SourceSpan span,
                       BridgeAbi abi) {
        public Root {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(sourceFile);
            Objects.requireNonNull(span);
            Objects.requireNonNull(abi);
            if (!BridgeAbi.describe(callable).equals(Optional.of(abi))) {
                throw new IllegalArgumentException("bridge root ABI does not match its callable");
            }
        }
    }

    public record Problem(BridgeCallableId callable, String message,
                          Optional<SourceSpan> span) {
        public Problem {
            Objects.requireNonNull(callable);
            Objects.requireNonNull(message);
            Objects.requireNonNull(span);
        }
    }

    public boolean resolved() {
        return problems.isEmpty() && !roots.isEmpty();
    }

    public static BridgeRootSet resolve(IrProgram program, List<BridgeCallableId> requested) {
        Map<String, IrFunction> functions = new LinkedHashMap<>();
        for (IrFunction function : program.functions()) {
            if (functions.putIfAbsent(function.linkageName(), function) != null) {
                throw new IllegalArgumentException("duplicate resolved IR linkage: " + function.linkageName());
            }
        }
        List<Root> roots = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        for (BridgeCallableId callable : requested.stream().distinct()
                .sorted(Comparator.comparing(BridgeCallableId::linkage)).toList()) {
            IrFunction function = functions.get(callable.linkage());
            if (function == null || !BridgeCallableId.of(function).equals(callable)) {
                problems.add(new Problem(callable,
                        "bridge root is missing or its resolved signature changed", Optional.empty()));
                continue;
            }
            Optional<BridgeAbi> abi = BridgeAbi.describe(callable);
            if (abi.isEmpty()) {
                problems.add(new Problem(callable,
                        "bridge root has an unsupported native value ABI", Optional.of(function.sourceSpan())));
                continue;
            }
            roots.add(new Root(callable, function.sourceFileName(), function.sourceSpan(), abi.orElseThrow()));
        }
        return new BridgeRootSet(problems.isEmpty() ? roots : List.of(), problems);
    }

    /** Re-resolve after reconstruction/specialization; never carry a stale identity forward. */
    public BridgeRootSet revalidate(IrProgram program) {
        if (!resolved()) {
            return this;
        }
        return resolve(program, roots.stream().map(Root::callable).toList());
    }
}
