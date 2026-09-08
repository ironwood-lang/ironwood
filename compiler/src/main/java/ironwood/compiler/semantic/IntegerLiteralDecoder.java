// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.semantic;

import ironwood.compiler.ir.IrType;

import java.math.BigInteger;

final class IntegerLiteralDecoder {
    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);

    private IntegerLiteralDecoder() {
    }

    static Result decode(String spelling, boolean negated) {
        String text = spelling.replace("_", "");
        boolean longLiteral = text.endsWith("l") || text.endsWith("L");
        if (longLiteral) {
            text = text.substring(0, text.length() - 1);
        }
        Kind kind = text.startsWith("0x") || text.startsWith("0X")
                ? Kind.HEXADECIMAL
                : text.startsWith("0b") || text.startsWith("0B")
                ? Kind.BINARY : Kind.DECIMAL;
        int radix = switch (kind) {
            case DECIMAL -> 10;
            case HEXADECIMAL -> 16;
            case BINARY -> 2;
        };
        String digits = kind == Kind.DECIMAL ? text : text.substring(Math.min(2, text.length()));
        IrType type = longLiteral ? IrType.I64 : IrType.I32;
        int width = longLiteral ? 64 : 32;
        try {
            BigInteger magnitude = new BigInteger(digits, radix);
            if (kind != Kind.DECIMAL) {
                if (magnitude.bitLength() > width) {
                    return Result.failure(type, kind.displayName
                            + " integer literal exceeds " + width + " bits");
                }
                if (magnitude.testBit(width - 1)) {
                    magnitude = magnitude.subtract(BigInteger.ONE.shiftLeft(width));
                }
                if (negated) {
                    magnitude = wrap(magnitude.negate(), width);
                }
            } else {
                BigInteger maximum = longLiteral ? MAX_LONG : MAX_INT;
                BigInteger allowed = negated ? maximum.add(BigInteger.ONE) : maximum;
                if (magnitude.compareTo(allowed) > 0) {
                    return Result.failure(type, "integer literal is outside the signed "
                            + width + "-bit range");
                }
                if (negated) {
                    magnitude = magnitude.negate();
                }
            }
            return Result.success(type, magnitude);
        } catch (NumberFormatException failure) {
            return Result.failure(type, "malformed integer literal '" + spelling + "'");
        }
    }

    private static BigInteger wrap(BigInteger value, int width) {
        BigInteger modulus = BigInteger.ONE.shiftLeft(width);
        BigInteger wrapped = value.mod(modulus);
        return wrapped.testBit(width - 1) ? wrapped.subtract(modulus) : wrapped;
    }

    private enum Kind {
        DECIMAL("decimal"),
        HEXADECIMAL("hexadecimal"),
        BINARY("binary");

        private final String displayName;

        Kind(String displayName) {
            this.displayName = displayName;
        }
    }

    record Result(IrType type, BigInteger value, String error) {
        private static Result success(IrType type, BigInteger value) {
            return new Result(type, value, null);
        }

        private static Result failure(IrType type, String error) {
            return new Result(type, null, error);
        }

        boolean successful() {
            return error == null;
        }
    }
}
