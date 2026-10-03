 // The address table exists only in this private experiment.
 public static native long address(int id);
 public static native long allocations();
 public static native void failure(long frame, int status);
 public static void main(String[] args) throws Throwable {
     System.load(args[0]);
     if (args[1].equals("verify")) Checks.verify(JniProbe.class);
     else if (args[1].equals("oom")) Checks.oom(JniProbe.class);
     else if (args[1].equals("stack")) Checks.stack(JniProbe.class, Integer.parseInt(args[2]));
     else if (args[1].equals("bench")) {
         for (int i = 0; i < 8; i++) measure(false);
         for (int i = 0; i < 7; i++) measure(true);
     } else throw new IllegalArgumentException(args[1]);
 }
 private static void measure(boolean report) {
     long bytes = Checks.bytes(), allocations = allocations(), sum = 0;
     int count = 5_000_000;
     long start = System.nanoTime();
     for (int i = 0; i < count; i++) sum += add(i, 42);
     long elapsed = System.nanoTime() - start;
     long nativeDelta = allocations() - allocations, javaDelta = Checks.bytes() - bytes;
     Checks.report(report, count, sum, elapsed, nativeDelta, javaDelta);
 }
}
