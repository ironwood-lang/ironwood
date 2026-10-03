// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.Objects;
import java.util.Optional;

/** Immutable outcome carrying a compiler-owned immutable contract only when proved. */
public record BridgeProof<T>(Status status, Optional<T> contract, String reason) {
    public BridgeProof {
        Objects.requireNonNull(status);
        Objects.requireNonNull(contract);
        if ((status == Status.PROVED) != contract.isPresent()) {
            throw new IllegalArgumentException("only a proved bridge outcome can carry a contract");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("bridge proof outcome requires a reason");
        }
    }

    public static <T> BridgeProof<T> proved(T contract, String reason) {
        return new BridgeProof<>(Status.PROVED, Optional.of(contract), reason);
    }

    public static <T> BridgeProof<T> unknown(String reason) {
        return new BridgeProof<>(Status.UNKNOWN, Optional.empty(), reason);
    }

    public static <T> BridgeProof<T> rejected(String reason) {
        return new BridgeProof<>(Status.REJECTED, Optional.empty(), reason);
    }

    public enum Status { PROVED, UNKNOWN, REJECTED }
}
