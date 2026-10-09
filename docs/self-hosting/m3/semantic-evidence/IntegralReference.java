// SPDX-License-Identifier: MIT OR Apache-2.0

import ironwood.compiler.ast.BinaryOperator;
import ironwood.compiler.ir.IrType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigInteger;

/**
 * J0 reference for integration-tests/cases/compiler_integral_constants.iron.
 * Run with the bootstrap classes on the class path; it calls the real
 * package-private IntegerLiteralDecoder.decode and FunctionAnalyzer's private
 * wrapIntegral, evaluateIntegralBinary and constantNarrowingFits by
 * reflection, so the transcript is J0's own behavior.
 */
public final class IntegralReference {
    private static long seed = 314159L;
    private static Method decode;
    private static Method wrap;
    private static Method binary;
    private static Method narrowing;
    private static Constructor<?> typedValue;

    static final String[] PREFIXES = {"", "0x", "0X", "0b", "0B"};
    static final String[] SUFFIXES = {"", "L", "l"};
    static final String[] FIXED = {"", "_", "L", "l", "0", "00", "0L", "0x", "0b", "0xL", "0bL", "0b2", "12abc",
        "1L2", "0x1g", "08", "0_8", "٣", "1٣", "0x١", "0b١", "１２", "0x_", "_1",
        "1_", "1__0", "0x7fff_ffff", "0X80000000", "0xFFFFFFFFL", "0x1_0000_0000L", "0xFFFFFFFFFFFFFFFFL",
        "0x10000000000000000L", "0xFFFFFFFFFFFFFFFFF", "0b11111111111111111111111111111111",
        "0b111111111111111111111111111111111", "2147483647", "2147483648", "2147483649", "9223372036854775807L",
        "9223372036854775808L", "9223372036854775809L", "18446744073709551615L", "18446744073709551616L",
        "000000000000000000000000000000000000000000000002147483647", "0x00000000000000000000000000000ff"};
    static final String ALPHABET = "0123456789abcdefABCDEFxXbBlL_١g";

    private static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char character : text.toCharArray()) {
            if (character >= 0x20 && character < 0x7f) out.append(character);
            else out.append(String.format("\\u%04x", (int) character));
        }
        return out.toString();
    }

    private static void literal(String spelling) throws Exception {
        for (boolean negated : new boolean[]{false, true}) {
            Object result = decode.invoke(null, spelling, negated);
            Method type = result.getClass().getDeclaredMethod("type");
            Method value = result.getClass().getDeclaredMethod("value");
            Method error = result.getClass().getDeclaredMethod("error");
            type.setAccessible(true);
            value.setAccessible(true);
            error.setAccessible(true);
            String typeName = ((IrType) type.invoke(result)).kind().name();
            Object failure = error.invoke(result);
            System.out.println("lit " + escape(spelling) + " " + negated + " " + typeName + " "
                    + (failure != null ? "error " + failure : "value " + value.invoke(result)));
        }
    }

    private static IrType type(String name) {
        return switch (name) {
            case "I8" -> IrType.I8;
            case "I16" -> IrType.I16;
            case "U16" -> IrType.U16;
            case "I32" -> IrType.I32;
            default -> IrType.I64;
        };
    }

    public static void main(String[] args) throws Exception {
        Class<?> decoder = Class.forName("ironwood.compiler.semantic.IntegerLiteralDecoder");
        decode = decoder.getDeclaredMethod("decode", String.class, boolean.class);
        decode.setAccessible(true);
        Class<?> analyzer = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer");
        Class<?> typed = Class.forName("ironwood.compiler.semantic.FunctionAnalyzer$TypedValue");
        for (Constructor<?> constructor : typed.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) typedValue = constructor;
        }
        typedValue.setAccessible(true);
        wrap = analyzer.getDeclaredMethod("wrapIntegral", BigInteger.class, IrType.class);
        wrap.setAccessible(true);
        binary = analyzer.getDeclaredMethod("evaluateIntegralBinary", BinaryOperator.class, typed, typed, IrType.class);
        binary.setAccessible(true);
        narrowing = analyzer.getDeclaredMethod("constantNarrowingFits", IrType.class, typed);
        narrowing.setAccessible(true);

        for (String spelling : FIXED) literal(spelling);
        String[] magnitudes = {"0", "1", "2147483647", "2147483648", "4294967295", "4294967296",
            "9223372036854775807", "9223372036854775808", "18446744073709551615", "18446744073709551616"};
        for (String prefix : PREFIXES) {
            int radix = prefix.isEmpty() ? 10 : prefix.endsWith("x") || prefix.endsWith("X") ? 16 : 2;
            for (String magnitude : magnitudes) {
                String digits = new BigInteger(magnitude).toString(radix);
                for (String zeros : new String[]{"", "0", "00000000000000000000"}) {
                    for (String suffix : SUFFIXES) {
                        literal(prefix + zeros + digits + suffix);
                        literal(prefix + String.join("_", (zeros + digits).split("")) + suffix);
                    }
                }
            }
        }
        for (int round = 0; round < 20000; round++) {
            StringBuilder spelling = new StringBuilder(PREFIXES[next(PREFIXES.length)]);
            int length = next(24);
            for (int index = 0; index < length; index++) spelling.append(ALPHABET.charAt(next(ALPHABET.length())));
            literal(spelling.toString());
        }

        long[] values = {0, 1, -1, 2, -2, 7, -7, 31, 32, 33, 63, 64, Integer.MAX_VALUE, Integer.MIN_VALUE,
            Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, 0x55555555L, (int) 0xAAAAAAAAL, 1000000007, -1000000007,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1, 1L << 32, -(1L << 32),
            0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL, 65535, 65536, -32768, 32767, 127, -128, 255, 256};
        String[] wraps = {"I8", "I16", "U16", "I32", "I64"};
        for (long value : values) {
            StringBuilder line = new StringBuilder("wrap " + value);
            for (String name : wraps) line.append(' ').append(wrap.invoke(null, BigInteger.valueOf(value), type(name)));
            System.out.println(line);
        }
        BinaryOperator[] operators = {BinaryOperator.ADD, BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY,
            BinaryOperator.DIVIDE, BinaryOperator.REMAINDER, BinaryOperator.SHIFT_LEFT, BinaryOperator.SHIFT_RIGHT,
            BinaryOperator.UNSIGNED_SHIFT_RIGHT, BinaryOperator.BITWISE_AND, BinaryOperator.BITWISE_XOR,
            BinaryOperator.BITWISE_OR, BinaryOperator.LESS};
        for (String name : new String[]{"I32", "I64"}) {
            IrType resultType = type(name);
            for (BinaryOperator operator : operators) {
                StringBuilder line = new StringBuilder("binary " + name + " " + operator.name());
                long hash = 17;
                int defined = 0;
                for (long left : values) {
                    long l = ((BigInteger) wrap.invoke(null, BigInteger.valueOf(left), resultType)).longValue();
                    for (long right : values) {
                        long r = ((BigInteger) wrap.invoke(null, BigInteger.valueOf(right), resultType)).longValue();
                        Object result = binary.invoke(null, operator,
                                typedValue.newInstance(resultType, null, BigInteger.valueOf(l)),
                                typedValue.newInstance(resultType, null, BigInteger.valueOf(r)), resultType);
                        long encoded = result == null ? 0x7357L : ((BigInteger) result).longValue();
                        if (result != null) defined++;
                        hash = hash * 31 + encoded;
                    }
                }
                System.out.println(line.append(" defined ").append(defined).append(" hash ").append(hash));
            }
            for (long value : values) {
                long v = ((BigInteger) wrap.invoke(null, BigInteger.valueOf(value), resultType)).longValue();
                System.out.println("unary " + name + " " + v + " "
                        + wrap.invoke(null, BigInteger.valueOf(v).negate(), resultType) + " "
                        + wrap.invoke(null, BigInteger.valueOf(v).not(), resultType));
            }
        }
        String[] actuals = {"I8", "I16", "U16", "I32", "I64"};
        for (long value : values) {
            StringBuilder line = new StringBuilder("narrow " + value);
            for (String actual : actuals) {
                for (String expected : actuals) {
                    long v = ((BigInteger) wrap.invoke(null, BigInteger.valueOf(value), type(actual))).longValue();
                    boolean fits = (Boolean) narrowing.invoke(null, type(expected),
                            typedValue.newInstance(type(actual), null, BigInteger.valueOf(v)));
                    line.append(fits ? '1' : '0');
                }
            }
            System.out.println(line);
        }
    }
}
