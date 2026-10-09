// SPDX-License-Identifier: MIT OR Apache-2.0

import ironwood.compiler.source.SourcePosition;
import ironwood.compiler.source.SourceSpan;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Java 21 reference transcript for compiler_value_helpers.iron. Escapes call
 * the compiler's own Lexer.decodeSimpleEscape; positions and spans are the
 * compiler's own records; sites, retentions and summaries are records with
 * the same component types. Run with the compiler classes on the class path.
 */
public final class ValueHelpersReference {

    record Site(Object source, SourceSpan span) { }
    record Retention(Object owner, Object child) { }
    record Summary(boolean allocates, boolean throwsOutward, BitSet published, BitSet returned, BitSet reclaimed) {
        Summary {
            published = (BitSet) published.clone();
            returned = (BitSet) returned.clone();
            reclaimed = (BitSet) reclaimed.clone();
        }
    }

    static int state = 20261005;

    static int next(int bound) {
        state = state * 1103515245 + 12345;
        return ((state >>> 16) & 32767) % bound;
    }

    static boolean rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder();
        Method decode = Class.forName("ironwood.compiler.lexer.Lexer")
                .getDeclaredMethod("decodeSimpleEscape", char.class, boolean.class);
        decode.setAccessible(true);
        int hash = -2128831035;
        int present = 0;
        for (int code = 0; code < 65536; code++) {
            for (int context = 0; context < 2; context++) {
                Character value = (Character) decode.invoke(null, (char) code, context == 1);
                int decoded = value == null ? -1 : value;
                hash = (hash ^ decoded) * 16777619;
                if (value != null) {
                    present++;
                    out.append("escape ").append(code).append(':').append(context).append(':').append(decoded).append('\n');
                }
            }
        }
        out.append("escapes present:").append(present).append(" hash:").append(hash).append('\n');

        int[] pool = {0, 1, 2, -1, 7, 2147483647, -2147483648, 1000000000};
        for (int round = 0; round < 64; round++) {
            int length = next(7);
            List<Integer> builder = new ArrayList<>();
            for (int index = 0; index < length; index++) builder.add(pool[next(pool.length)]);
            List<Integer> first = List.copyOf(builder);
            List<Integer> second = List.copyOf(builder);
            builder.add(5);
            out.append("ints ").append(round).append(':').append(first.size()).append(':')
                    .append(first.stream().mapToInt(Integer::intValue).sum()).append(':')
                    .append(first.stream().anyMatch(count -> count < 0)).append(':').append(first.hashCode()).append(':')
                    .append(first.equals(second)).append(':').append(first.size() == length).append('\n');
        }
        List<Integer> empty = List.of();
        out.append("ints empty:").append(empty.size()).append(':')
                .append(empty.stream().mapToInt(Integer::intValue).sum()).append(':').append(empty.hashCode()).append('\n');
        boolean bounded = false;
        try {
            empty.get(0);
        } catch (IndexOutOfBoundsException expected) {
            bounded = true;
        }
        out.append("ints bounds:").append(bounded).append('\n');

        Object item = new Object();
        List<Object> one = List.of(item);
        out.append("single:").append(one.size()).append(':').append(one.get(0) == item).append('\n');
        boolean rejected = false;
        try {
            List.of((Object) null);
        } catch (NullPointerException expected) {
            rejected = true;
        }
        out.append("single null:").append(rejected).append('\n');

        SourcePosition a = new SourcePosition(4, 1, 5);
        SourcePosition equalA = new SourcePosition(4, 1, 5);
        SourcePosition b = new SourcePosition(9, 2, 1);
        out.append("position:").append(a.equals(equalA)).append(':').append(a.hashCode() == equalA.hashCode())
                .append(':').append(a.equals(b)).append(':').append(a.equals(null)).append('\n');
        out.append("position invalid:").append(rejects(() -> new SourcePosition(-1, 1, 1))).append(':')
                .append(rejects(() -> new SourcePosition(0, 0, 1))).append(':')
                .append(rejects(() -> new SourcePosition(0, 1, 0))).append(':')
                .append(rejects(() -> new SourcePosition(0, 1, 1))).append('\n');
        SourceSpan span = new SourceSpan(a, b);
        SourceSpan equalSpan = new SourceSpan(equalA, b);
        SourceSpan point = new SourceSpan(a, a);
        out.append("span:").append(span.equals(equalSpan)).append(':').append(span.hashCode() == equalSpan.hashCode())
                .append(':').append(span.equals(point)).append('\n');
        out.append("span invalid:").append(rejects(() -> new SourceSpan(null, a))).append(':')
                .append(rejects(() -> new SourceSpan(a, null))).append(':')
                .append(rejects(() -> new SourceSpan(b, a))).append(':')
                .append(rejects(() -> new SourceSpan(a, a))).append('\n');
        Object file = new Object();
        Object otherFile = new Object();
        Site site = new Site(file, span);
        Site equalSite = new Site(file, equalSpan);
        Site otherSite = new Site(otherFile, span);
        out.append("site:").append(site.equals(equalSite)).append(':').append(site.hashCode() == equalSite.hashCode())
                .append(':').append(site.equals(otherSite)).append('\n');
        Retention retention = new Retention(file, otherFile);
        Retention equalRetention = new Retention(file, otherFile);
        Retention swapped = new Retention(otherFile, file);
        out.append("retention:").append(retention.equals(equalRetention)).append(':')
                .append(retention.hashCode() == equalRetention.hashCode()).append(':')
                .append(retention.equals(swapped)).append('\n');
        BitSet bits = new BitSet();
        bits.set(1);
        bits.set(200);
        BitSet trimmed = new BitSet();
        trimmed.set(1);
        BitSet none = new BitSet();
        Summary first = new Summary(true, false, bits, none, none);
        bits.clear(200);
        Summary second = new Summary(true, false, bits, none, none);
        Summary third = new Summary(true, false, trimmed, none, none);
        Summary fourth = new Summary(false, false, trimmed, none, none);
        out.append("summary:").append(first.equals(second)).append(':').append(second.equals(third)).append(':')
                .append(third.equals(fourth)).append(':').append(third.equals(null)).append('\n');
        System.out.print(out);
    }
}
