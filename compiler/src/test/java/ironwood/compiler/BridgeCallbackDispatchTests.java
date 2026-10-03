// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Public callback dispatch must preserve every admitted primitive carrier. */
final class BridgeCallbackDispatchTests {
    static final String NAME = "Java Bridge callback dispatch preserves primitive values and checked failure identity";
    private BridgeCallbackDispatchTests() {}

    static void dispatch() throws Exception {
        Path base = Path.of("workspace/java-bridge/evidence/p5/dispatch").toAbsolutePath();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-");
        Path listener = directory.resolve("Values.iron"), driver = directory.resolve("Driver.iron");
        Files.writeString(listener, """
                package dispatch;
                public interface Values {
                    boolean bool(boolean value);
                    byte octet(byte value);
                    short small(short value);
                    char character(char value);
                    int integer(int value);
                    long wide(long value);
                    float single(float value);
                    double real(double value);
                    void empty() throws Exception;
                }
                """);
        Files.writeString(driver, """
                package dispatch;
                public final class Driver {
                    private Driver() {}
                    public static long run(Values values) throws Exception {
                        if (values.bool(false) || !values.bool(true)) return 1L;
                        if (values.octet((byte)-128) != (byte)-128) return 2L;
                        if (values.small((short)-32768) != (short)-32768) return 3L;
                        if (values.character((char)65535) != (char)65535) return 4L;
                        if (values.integer(-2147483648) != -2147483648) return 5L;
                        if (values.wide(-9223372036854775808L) != -9223372036854775808L) return 6L;
                        if (1.0f / values.single(-0.0f) != -1.0f / 0.0f) return 7L;
                        if (values.single(1.0f / 0.0f) != 1.0f / 0.0f) return 8L;
                        float singleNaN = values.single(0.0f / 0.0f);
                        if (singleNaN == singleNaN) return 9L;
                        if (1.0 / values.real(-0.0) != -1.0 / 0.0) return 10L;
                        double realNaN = values.real(0.0 / 0.0);
                        if (realNaN == realNaN) return 11L;
                        values.empty();
                        return 42L;
                    }
                }
                """);
        Path consumer = directory.resolve("Consumer.java");
        Files.writeString(consumer, """
                import dispatch.Driver;
                import dispatch.Values;
                public final class Consumer implements Values {
                    private boolean nested;
                    private Exception failure;
                    public boolean bool(boolean v) { return v; }
                    public byte octet(byte v) { return v; }
                    public short small(short v) { return v; }
                    public char character(char v) { return v; }
                    public int integer(int v) { return v; }
                    public long wide(long v) { return v; }
                    public float single(float v) { return v; }
                    public double real(double v) { return v; }
                    public void empty() throws Exception {
                        if (failure != null) throw failure;
                        if (!nested) {
                            nested = true;
                            try { if (Driver.run(this) != 42L) throw new AssertionError("nested values"); }
                            finally { nested = false; }
                        }
                    }
                    public static void main(String[] args) throws Exception {
                        Consumer value = new Consumer();
                        for (int i = 0; i < 200; i++) {
                            if (Driver.run(value) != 42L) throw new AssertionError("values");
                        }
                        value.failure = new Exception("checked callback");
                        try { Driver.run(value); throw new AssertionError("lost failure"); }
                        catch (Exception actual) { if (actual != value.failure) throw actual; }
                        value.failure = null;
                        if (Driver.run(value) != 42L) throw new AssertionError("continued use");
                        System.out.println("callback-dispatch-ok");
                    }
                }
                """);
        Path jdk = Path.of(System.getProperty("java.home"));
        for (String level : List.of("-O0", "-O3")) {
            Path output = directory.resolve(level.substring(1));
            Files.createDirectories(output);
            Path jar = output.resolve("dispatch.jar");
            BridgeProducerTests.command(output, "producer", 0, new String[]{"--java-bridge", "--export", "dispatch",
                    "--unfreed=error", level, "-o", jar.toString(), listener.toString(), driver.toString()});
            BridgeEntryTests.run(output, List.of(jdk.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all",
                    "-Werror", "-cp", jar.toString(), "-d", output.toString(), consumer.toString()), "javac");
            String result = BridgeEntryTests.run(output, List.of(jdk.resolve("bin/java").toString(), "-Xcheck:jni", "-cp",
                    jar + java.io.File.pathSeparator + output, "Consumer"), "consumer");
            if (!result.equals("callback-dispatch-ok\n")) throw new AssertionError(result);
        }
        System.out.println("callback dispatch evidence: " + directory);
    }
}
