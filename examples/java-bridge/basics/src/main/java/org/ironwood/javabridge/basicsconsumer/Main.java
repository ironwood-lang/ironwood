// SPDX-License-Identifier: MIT OR Apache-2.0
package org.ironwood.javabridge.basicsconsumer;

import org.ironwood.javabridge.basics.Counter;
import org.ironwood.javabridge.basics.CounterListener;

// This Java object receives callbacks from the native Ironwood counter.
public final class Main implements CounterListener {

    @Override
    public void onChanged(int value) {

        System.out.println("Java listener: " + value);
    }

    public static void main(String[] args) {

        Counter counter = new Counter();
        
        counter.setListener(new Main());
        counter.add(2);
        counter.add(3);
        System.out.println("Counter total: " + counter.getValue());

        // Reclaim the native counter and release its listener registration.
        counter.free();
    }
}
