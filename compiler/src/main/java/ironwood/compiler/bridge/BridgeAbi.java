// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.ir.IrType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport-neutral native value descriptors. Describing a reference does not
 * authorize its export, conversion, retention or destruction. These require proofs.
 */
public record BridgeAbi(List<Value> parameters, Value result) {
    public BridgeAbi {
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(result);
        if (parameters.stream().anyMatch(value -> value.carrier() == Carrier.VOID)) {
            throw new IllegalArgumentException("void bridge parameter");
        }
    }

    public record Value(IrType sourceType, Carrier carrier) {
        public Value {
            Objects.requireNonNull(sourceType);
            Objects.requireNonNull(carrier);
            if (!carrierFor(sourceType).equals(Optional.of(carrier))) {
                throw new IllegalArgumentException("bridge carrier does not match source type");
            }
        }
    }

    public enum Carrier {
        VOID, BOOLEAN_U8, SIGNED_I8, SIGNED_I16, UTF16_U16, SIGNED_I32, SIGNED_I64,
        IEEE_F32, IEEE_F64, OPAQUE_REFERENCE
    }

    public static Optional<Carrier> carrierFor(IrType type) {
        return switch (type.kind()) {
            case VOID -> Optional.of(Carrier.VOID);
            case I1 -> Optional.of(Carrier.BOOLEAN_U8);
            case I8 -> Optional.of(Carrier.SIGNED_I8);
            case I16 -> Optional.of(Carrier.SIGNED_I16);
            case U16 -> Optional.of(Carrier.UTF16_U16);
            case I32 -> Optional.of(Carrier.SIGNED_I32);
            case I64 -> Optional.of(Carrier.SIGNED_I64);
            case F32 -> Optional.of(Carrier.IEEE_F32);
            case F64 -> Optional.of(Carrier.IEEE_F64);
            case REFERENCE -> type.typeArguments().isEmpty()
                    ? Optional.of(Carrier.OPAQUE_REFERENCE) : Optional.empty();
            case ARRAY, TYPE_PARAMETER, WILDCARD, NULL, EXCEPTION -> Optional.empty();
        };
    }

    public static Optional<BridgeAbi> describe(BridgeCallableId callable) {
        if (carrierFor(callable.result()).isEmpty()
                || callable.parameters().stream().anyMatch(type -> type.equals(IrType.VOID)
                || carrierFor(type).isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(new BridgeAbi(callable.parameters().stream()
                .map(type -> new Value(type, carrierFor(type).orElseThrow())).toList(),
                new Value(callable.result(), carrierFor(callable.result()).orElseThrow())));
    }
}
