// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;
import java.util.Set;

/** Audited ownership boundaries of the bundled object pools, not user method-name conventions. */
final class PoolSemantics {
    static final String DYNAMIC_BORROW = "*";
    static final String POOL_VALUE_BORROW = "*pool-value";
    private static final Set<String> POOLS = Set.of(
            "ironwood.pool.ArrayObjectPool", "ironwood.pool.MultiArrayObjectPool");

    private PoolSemantics() {}

    static boolean isPool(String name) { return POOLS.contains(name); }

    static boolean isCheckout(CallableSymbol method) {
        return isPool(method.ownerType()) && !method.isStatic()
                && method.sourceName().equals("get") && method.parameterTypes().isEmpty();
    }

    static boolean isRelease(CallableSymbol method) {
        return isPool(method.ownerType()) && !method.isStatic()
                && method.sourceName().equals("release") && method.parameterTypes().size() == 1
                && method.returnType().equals(IrType.VOID);
    }

    static boolean borrowsMember(CallableSymbol method) {
        return isCheckout(method) || isPool(method.ownerType()) && !method.isStatic()
                && method.sourceName().equals("getArrayElement");
    }

    static SymbolicReturnOriginAnalyzer.ReturnSummary symbolic(CallableSymbol method) {
        if (method.ownerType().equals("ironwood.pool.ObjectBuilder")
                && method.sourceName().equals("newInstance") && !method.isStatic()
                && method.parameterTypes().isEmpty()) {
            return new SymbolicReturnOriginAnalyzer.ReturnSummary(Set.of(), Set.of(), Set.of(),
                    false, true, true, false);
        }
        if (borrowsMember(method)) {
            return new SymbolicReturnOriginAnalyzer.ReturnSummary(Set.of(),
                    Set.of(new SymbolicReturnOriginAnalyzer.BorrowedReturnOrigin(
                            ReturnOrigin.thisOrigin(), isCheckout(method) ? POOL_VALUE_BORROW : DYNAMIC_BORROW)), Set.of(),
                    false, false, !isCheckout(method), false);
        }
        if (isRelease(method)) {
            // A wrapper without a proved receiver/argument identity cannot promise
            // an ownership transfer. Direct calls discharge this obligation locally.
            return new SymbolicReturnOriginAnalyzer.ReturnSummary(Set.of(), Set.of(),
                    Set.of(ReturnOrigin.thisOrigin(), ReturnOrigin.parameter(0)),
                    false, false, false, false);
        }
        return null;
    }
}
