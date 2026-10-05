// SPDX-License-Identifier: MIT OR Apache-2.0

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Java 21 reference for integration-tests/cases/compiler_callbacks.iron: the
 * same callback shapes with the java.util.function types they replace.
 */
public final class CallbacksReference {
    private static int depth;
    private static final RuntimeException FAILURE = new RuntimeException("source failure");
    private static String variable = "v0";

    private static void log(String line) {
        System.out.println(line);
    }

    private static boolean nested(BooleanSupplier source) {
        depth++;
        try {
            return source.getAsBoolean();
        } finally {
            depth--;
        }
    }

    private record Renamer(Function<String, String> values, Function<String, String> labels) { }

    private record LValue(Supplier<String> read, Consumer<String> write) { }

    public static void main(String[] args) {
        BooleanSupplier atDepth = () -> {
            log("source depth " + depth);
            return depth == 1;
        };
        log("nested " + nested(atDepth) + " after " + depth);
        int threshold = 0;
        log("capturing " + nested(() -> depth > threshold) + " after " + depth);
        try {
            nested(() -> { throw FAILURE; });
        } catch (RuntimeException expected) {
            log("failed " + (expected == FAILURE) + " after " + depth);
        }

        int[] fresh = {0};
        Function<String, String> values = value -> {
            if (value.startsWith("%p")) return value;
            fresh[0]++;
            return "%r" + fresh[0];
        };
        Renamer renamer = new Renamer(values, Function.identity());
        for (String operand : new String[]{"%p0", "%t1", "%t2", "%p1", "%t3"}) {
            log("operand " + operand + " -> " + renamer.values().apply(operand));
        }
        log("label entry -> " + renamer.labels().apply("entry") + " same " + (renamer.labels().apply("loop") == "loop"));
        log("renamed " + fresh[0]);

        int[] calls = {0};
        Predicate<String> isCall = value -> {
            calls[0]++;
            return value.startsWith("call");
        };
        boolean match = false;
        for (String instruction : new String[]{"load", "call f", "store", "call g"}) {
            if (isCall.test(instruction)) {
                match = true;
                break;
            }
        }
        log("match " + match + " calls " + calls[0]);

        Consumer<String> reclaimer = value -> log("reclaim " + value);
        String[] candidates = {"t0", "keep", "t1", "t2"};
        for (int index = candidates.length - 1; index >= 0; index--) {
            if (!candidates[index].equals("keep")) reclaimer.accept(candidates[index]);
        }

        LValue target = new LValue(() -> {
            log("read " + variable);
            return variable;
        }, value -> {
            log("write " + value);
            variable = value;
        });
        String before = target.read().get();
        log("rhs");
        variable = "v1";
        target.write().accept(before + "+rhs");
        log("variable " + variable);

        int[] seen = {0};
        BiConsumer<String, String> observer = (original, clone) -> {
            seen[0]++;
            log("clone " + original + " -> " + clone);
        };
        for (String function : new String[]{"f1", "g", "f2"}) {
            if (function.startsWith("f")) observer.accept(function, function + "$clone");
        }
        log("clones " + seen[0]);
    }
}
