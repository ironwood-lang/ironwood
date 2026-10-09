// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.doc;

import java.util.List;

/** Independent public-API and original source-helper facts, not native delivery. */
public final class BuilderContractProbe {
    private static int checks;
    private BuilderContractProbe() {}
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("builder check " + checks);
    }
    private static void fails(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("unexpected failure", failure);
        }
        throw new AssertionError("missing " + type.getName());
    }
    private static String anchor(String reference) {
        return new DocModel.Member("field", "n", reference, "sig", List.of(),
                List.of(), false, null, null).anchor();
    }
    private static final class Rendered {
        int calls;
        @Override public String toString() { calls++; return "rendered"; }
    }

    public static void main(String[] args) {
        StringBuilder value = new StringBuilder();
        check(value.isEmpty());
        check(value.append('x') == value);
        check(value.append(Integer.MIN_VALUE).append(Long.MIN_VALUE).toString()
                .equals("x-2147483648-9223372036854775808"));
        value.setLength(0);
        check(value.isEmpty());
        value.append((String) null).append((Object) null).append((CharSequence) null);
        check(value.toString().equals("nullnullnull"));
        value.setLength(0);
        value.append((Object) Integer.valueOf(97)).append((Object) Character.valueOf('a'));
        check(value.toString().equals("97a"));
        Rendered rendered = new Rendered();
        check(value.append(rendered) == value);
        check(rendered.calls == 1);
        check(value.toString().equals("97arendered"));
        Object failure = new Object() {
            @Override public String toString() { throw new IllegalStateException("render failure"); }
        };
        String before = value.toString();
        fails(IllegalStateException.class, () -> value.append(failure));
        check(value.toString().equals(before));
        value.setLength(0);
        value.append("ab");
        String snapshot = value.toString();
        value.append(value);
        check(value.toString().equals("abab"));
        check(snapshot.equals("ab"));
        value.setLength(0);
        value.append("abcd").append(value, 1, 3);
        check(value.toString().equals("abcdbc"));
        value.setLength(0);
        value.append((CharSequence) null, 1, 3);
        check(value.toString().equals("ul"));
        fails(IndexOutOfBoundsException.class, () -> value.append("ab", -1, 1));
        fails(IndexOutOfBoundsException.class, () -> value.append("ab", 2, 1));
        fails(IndexOutOfBoundsException.class, () -> value.append("ab", 0, 3));
        check(value.toString().equals("ul"));
        value.insert(0, (String) null);
        check(value.toString().equals("nullul"));
        value.insert(value.length(), "!");
        check(value.toString().equals("nullul!"));
        fails(StringIndexOutOfBoundsException.class, () -> value.insert(-1, "x"));
        fails(StringIndexOutOfBoundsException.class, () -> value.insert(value.length() + 1, "x"));
        value.setLength(2);
        check(value.toString().equals("nu"));
        value.setLength(4);
        check(value.charAt(2) == 0 && value.charAt(3) == 0);
        fails(StringIndexOutOfBoundsException.class, () -> value.setLength(-1));
        fails(NullPointerException.class, () -> new StringBuilder((String) null));
        fails(NullPointerException.class, () -> ((CharSequence) null).isEmpty());
        CharSequence empty = new StringBuilder();
        check(empty.isEmpty());
        value.setLength(0);
        value.appendCodePoint(0x1f600).appendCodePoint(0xd800);
        check(value.length() == 3 && value.charAt(0) == 0xd83d && value.charAt(1) == 0xde00 && value.charAt(2) == 0xd800);
        fails(IllegalArgumentException.class, () -> value.appendCodePoint(-1));
        fails(IllegalArgumentException.class, () -> value.appendCodePoint(0x110000));
        check(anchor("a_Z09").equals("member-a_Z09"));
        check(anchor(".").equals("member--2e-"));
        check(anchor("m\ud83d\ude00\ud800").equals("member-m-1f600--d800-"));
        fails(NullPointerException.class, () -> anchor(null));
        StringBuilder bound = null;
        fails(NullPointerException.class, () -> { java.util.function.Consumer<String> append = bound::append; append.accept("x"); });
        System.out.println("checks=" + checks);
    }
}
