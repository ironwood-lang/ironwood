// SPDX-License-Identifier: MIT OR Apache-2.0
import boundedbench.*;

/** Ordinary Java calls construct and mutate a native generic holder. */
public final class Consumer {
    public static void main(String[] args) {
        Value first = new Value(17), second = new Value(29);
        Holder<Value> holder = new Holder<>(first);
        try {
            if (holder.echo(first) != first) throw new AssertionError("lost native identity");
            System.out.print("bounded values: " + holder.read());
            Holder<? super Value> view = holder;
            view.set(second);
            System.out.println(" " + holder.read());
        } finally {
            // Destroy the retaining root before the values it may retain.
            holder.free();
            second.free();
            first.free();
        }
    }
}
