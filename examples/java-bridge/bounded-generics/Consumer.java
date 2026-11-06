// SPDX-License-Identifier: MIT OR Apache-2.0
import boundedbench.*;
import org.ironwood.javabridge.support.BridgeLifetime;

/** Ordinary Java calls construct and mutate a native generic holder. */
public final class Consumer {
    public static void main(String[] args) {
        try (var firstRoot = BridgeLifetime.<Value>construct(Value.class, 17);
             var secondRoot = BridgeLifetime.<Value>construct(Value.class, 29);
             var holderRoot = BridgeLifetime.<Holder<Value>>construct(Holder.class, firstRoot.get())) {
            Value first = firstRoot.get(), second = secondRoot.get();
            Holder<Value> holder = holderRoot.get();
            try (var alias = holder.echo(first)) {
                if (alias.value() != first) throw new AssertionError("lost native identity");
            }
            System.out.print("bounded values: " + holder.read());
            Holder<? super Value> view = holder;
            view.set(second);
            System.out.println(" " + holder.read());
        }
    }
}
