// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.basicsconsumer;

import org.ironwood.javabridge.basics.Counter;
import org.ironwood.javabridge.basics.CounterListener;
import org.ironwood.javabridge.support.BridgeLifetime;

// This Java object receives callbacks from the native Ironwood counter.
public final class Main implements CounterListener {

    @Override
    public void onChanged(int value) {

        System.out.println("Java listener: " + value);
    }

    public static void main(String[] args) {

        try (var anchor = BridgeLifetime.<Counter>construct(Counter.class);
             var registration = BridgeLifetime.registration(Counter.class)) {
        Counter counter = anchor.get();
        registration.setter(Counter.class, "setListener", CounterListener.class).set(counter, new Main());
        counter.add(2);
        counter.add(3);
        System.out.println("Counter total: " + counter.getValue());

        // Closing registration releases JNI ownership; closing the anchor releases
        // only the foreign root. Source resource close would be a separate call.
        }
    }
}
