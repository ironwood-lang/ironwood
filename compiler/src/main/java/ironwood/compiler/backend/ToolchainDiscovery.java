// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.util.Optional;

public record ToolchainDiscovery(Optional<LlvmToolchain> toolchain, String error) {
    public ToolchainDiscovery {
        toolchain = toolchain == null ? Optional.empty() : toolchain;
        error = error == null ? "" : error;
    }

    public static ToolchainDiscovery found(LlvmToolchain toolchain) {
        return new ToolchainDiscovery(Optional.of(toolchain), "");
    }

    public static ToolchainDiscovery notFound(String error) {
        return new ToolchainDiscovery(Optional.empty(), error);
    }

    public boolean successful() {
        return toolchain.isPresent();
    }
}
