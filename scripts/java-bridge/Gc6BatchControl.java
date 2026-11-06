// SPDX-License-Identifier: MIT OR Apache-2.0
import gc6callbacks.Listener;
import gc6callbacks.Node;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.Arrays;

/** Exact event checksum and allocation control for batching and its JNI fallback. */
public final class Gc6BatchControl implements Listener {
    private long sum, events;
    @Override public int call(int value, Node node) { return value + 1; }
    @Override public void event(long value) { sum += value; events++; }
    public static void main(String[] args) throws Exception {
        String support = Files.readString(Path.of(args[0]));
        Class<?> factories = Class.forName(support + ".Listeners"), registrationType = Class.forName(support + ".ListenerRegistration");
        Object registration = factories.getMethod("open").invoke(null);
        Gc6BatchControl listener = new Gc6BatchControl();
        Method construct = Arrays.stream(factories.getDeclaredMethods()).filter(method -> method.getName().startsWith("construct_")
                && Arrays.equals(method.getParameterTypes(), new Class<?>[]{registrationType, int.class, Listener.class, boolean.class})).findFirst().orElseThrow();
        Object handle = construct.invoke(null, registration, 17, listener, false);
        Node node = (Node)handle.getClass().getMethod("value").invoke(handle);
        long allocated = Node.allocations();
        long[] samples = new long[5];
        for (int trial = -2; trial < samples.length; trial++) {
            listener.sum = 0; listener.events = 0;
            long start = System.nanoTime();
            for (int i = 0; i < 16; i++) node.burst(4096);
            long elapsed = System.nanoTime() - start;
            if (listener.sum != 16L * 4096 * 4095 / 2 || listener.events != 16L * 4096) throw new AssertionError("batch schedule checksum changed");
            if (trial >= 0) samples[trial] = elapsed;
        }
        if (Node.allocations() != allocated) throw new AssertionError("callback schedule allocated managed objects");
        registrationType.getMethod("close").invoke(registration);
        handle.getClass().getMethod("close").invoke(handle); Node.clearSaved();
        Arrays.sort(samples);
        System.out.println("{\"batch_disabled\":" + Boolean.getBoolean("ironwood.bridge.batch.disable")
                + ",\"events\":" + listener.events + ",\"checksum\":" + listener.sum + ",\"managed_allocations\":0,\"median_ns\":" + samples[2] + "}");
    }
}
