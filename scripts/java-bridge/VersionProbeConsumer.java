// SPDX-License-Identifier: MIT OR Apache-2.0

import versionprobe.VersionProbe;

/** D203/D209 child assertions. A failed control must not enter native source. */
public final class VersionProbeConsumer {
    private VersionProbeConsumer() {}

    public static void main(String[] arguments) throws Exception {
        String mode = arguments.length == 0 ? "normal" : arguments[0];
        if (!mode.equals("normal")) {
            for (int i = 0; i < 2; i++) {
                try {
                    VersionProbe.value();
                    throw new AssertionError("blocked target executed");
                } catch (UnsatisfiedLinkError refusal) {
                    if (!mode.equals("refuse") || !refusal.getMessage().contains("requires Java 21-25; detected " + Runtime.version())) throw refusal;
                    System.out.println("refused:" + refusal.getMessage());
                } catch (IllegalCallerException denial) {
                    if (!mode.equals("deny") || !denial.getMessage().contains("native access")) throw denial;
                    System.out.println("denied:" + denial.getMessage());
                } catch (ExceptionInInitializerError failure) {
                    if (!mode.equals("deny") || i != 0 || !(failure.getCause() instanceof IllegalCallerException denial)
                            || !denial.getMessage().contains("native access")) throw failure;
                    System.out.println("denied:" + denial.getMessage());
                } catch (NoClassDefFoundError failure) {
                    if (i != 1 || !failure.getMessage().contains("Could not initialize class versionprobe.VersionProbe")) throw failure;
                    System.out.println("repeated-facade-init-failure");
                }
            }
            if (mode.equals("deny")) {
                java.nio.file.Path temporary = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
                java.util.List<java.nio.file.Path> images;
                try (var files = java.nio.file.Files.walk(temporary)) {
                    images = files.filter(path -> path.toString().endsWith(".dylib")).toList();
                }
                if (images.size() != 1) throw new AssertionError("expected extracted but unloaded image");
                var process = new ProcessBuilder("/usr/sbin/lsof", "-p", Long.toString(ProcessHandle.current().pid()))
                        .redirectErrorStream(true).start();
                String mappings = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly(); throw new AssertionError("mapping inspection timed out");
                }
                java.nio.file.Files.writeString(temporary.getParent().resolve("denied-image-mappings.txt"), mappings);
                if (process.exitValue() != 0 || !mappings.contains("java") || mappings.contains(images.getFirst().toString())) {
                    throw new AssertionError("denied image mapped or mappings unavailable");
                }
                System.out.println("denied-image-not-mapped");
            }
            return;
        }
        if (VersionProbe.value() != 42 || VersionProbe.add(19, 23) != 42) throw new AssertionError("scalar/init");
        for (String value : new String[]{"", "a\0b", "" + (char)0xd800, "" + (char)0xd83d + (char)0xde00}) {
            if (!value.equals(VersionProbe.copy(value)) || !value.equals(VersionProbe.echo(value))) throw new AssertionError("String copy");
        }
        if (VersionProbe.echo(null) != null) throw new AssertionError("null alias");
        for (int i = 0; i < 2; i++) {
            try { VersionProbe.fail(); throw new AssertionError("missing checked exception"); }
            catch (java.io.IOException expected) {
                if (!expected.getMessage().equals("version probe")
                        || java.util.Arrays.stream(expected.getStackTrace()).noneMatch(frame -> frame.getClassName().contains("VersionProbe"))) throw expected;
            }
            try { VersionProbe.Lazy.read(); throw new AssertionError("missing initializer failure"); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().equals("lazy version probe")) throw expected;
            }
            if (VersionProbe.value() != 42 || VersionProbe.add(20, 22) != 42) throw new AssertionError("continued call");
        }
        System.out.println("version-probe-ok");
    }
}
