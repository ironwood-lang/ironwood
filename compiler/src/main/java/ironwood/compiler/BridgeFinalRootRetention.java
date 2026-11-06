// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.bridge.*;
import ironwood.compiler.ir.*;

/** Final proof that the emitted root protocol still covers every lifetime effect. */
public final class BridgeFinalRootRetention {
    private final BridgeEntryModule module;
    private final BridgeManagedImage lifetime;

    private BridgeFinalRootRetention(BridgeEntryModule module, BridgeManagedImage lifetime) {
        this.module = module;
        this.lifetime = lifetime;
    }

    public IrProgram program() { return lifetime.program(); }
    public BridgeManagedImage lifetime() { return lifetime; }
    public BridgeRootRetentionContract protocol() { return module.rootRetention().orElseThrow(); }

    public boolean matches(BridgeEntryModule entries, IrProgram candidate) { return module == entries && lifetime.matches(entries, candidate); }

    static BridgeFinalRootRetention managed(BridgeEntryModule module, BridgeManagedImage lifetime) {
        if (!lifetime.matches(module, lifetime.program()))
            throw new IllegalArgumentException("managed bridge lifetime does not match the emitted module");
        return new BridgeFinalRootRetention(module, lifetime);
    }

}
