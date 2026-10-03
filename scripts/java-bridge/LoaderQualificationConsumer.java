// SPDX-License-Identifier: MIT OR Apache-2.0

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Producer-jar loading controls, isolated in fresh child JVMs. */
public final class LoaderQualificationConsumer {
    private static Path directory;
    private static native int reloadHook(String image);
    private static native int faultStat(String image, int slot);
    private LoaderQualificationConsumer() {}

    private static Path jar(String id) { return directory.resolve(id + ".jar"); }
    private static URLClassLoader loader(String... ids) throws Exception {
        var urls = new java.net.URL[ids.length];
        for (int i = 0; i < ids.length; i++) urls[i] = jar(ids[i]).toUri().toURL();
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }
    private static Properties metadata(String id) throws Exception {
        var properties = new Properties();
        try (var zip = new ZipFile(jar(id).toFile()); var input = zip.getInputStream(zip.getEntry("META-INF/ironwood/bridge.properties"))) {
            properties.load(input);
        }
        return properties;
    }
    private static Class<?> support(ClassLoader loader, String id) throws Exception {
        return Class.forName("ironwood.bridge.generated.g" + metadata(id).getProperty("generation") + ".Support", true, loader);
    }
    private static Object call(Class<?> type, String name, Class<?>[] signature, Object... arguments) throws Throwable {
        var method = type.getDeclaredMethod(name, signature); method.setAccessible(true);
        try { return method.invoke(null, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static Object field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void ensure(ClassLoader loader, String id) throws Throwable {
        Class<?> support = support(loader, id);
        var method = Arrays.stream(support.getDeclaredMethods()).filter(m -> m.getName().startsWith("$ironwood$ensure")).findFirst().orElseThrow();
        call(support, method.getName(), new Class<?>[0]);
    }
    private static String only(String id) {
        return switch (id) {
            case "a" -> "loadshared.OnlyA";
            case "b" -> "loadother.OnlyB";
            case "c" -> "loadshared.OnlyC";
            case "d" -> "loaddisjoint.OnlyD";
            default -> throw new AssertionError(id);
        };
    }
    private static void use(ClassLoader loader, String id) throws Throwable {
        int expected = switch (id) { case "a" -> 111; case "b" -> 222; case "c" -> 333; default -> 444; };
        Class<?> facade = Class.forName(only(id), true, loader);
        if (!call(facade, "value", new Class<?>[]{int.class, int.class}, 3, 4).equals(expected + 7)) throw new AssertionError("binding changed: " + id);
    }
    private static void refuse(ClassLoader loader, String id, String text) throws Throwable {
        try { ensure(loader, id); throw new AssertionError("missing loader rejection: " + id); }
        catch (LinkageError failure) {
            if (!failure.getMessage().contains(text)) throw failure;
            System.out.println("refusal:" + failure.getMessage());
        }
    }
    private static Path image(String id) throws Exception {
        String generation = metadata(id).getProperty("generation");
        String filename = Path.of(metadata(id).getProperty("native.resource")).getFileName().toString();
        try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.toString().contains(generation) && p.getFileName().toString().equals(filename)).findFirst().orElseThrow();
        }
    }
    private static void noImage(String id) throws Exception {
        String generation = metadata(id).getProperty("generation");
        try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
            if (files.anyMatch(p -> p.toString().contains(generation))) throw new AssertionError("rejected artifact extracted: " + id);
        }
    }
    private static void collision(String scenario) throws Throwable {
        String winner = scenario.substring(0, 1), loser = scenario.substring(1, 2), first = scenario.substring(3, 4);
        try (var loader = loader(winner, loser)) {
            if (first.equals(loser)) refuse(loader, loser, "expected");
            use(loader, winner);
            if (!first.equals(loser)) refuse(loader, loser, "expected");
            refuse(loader, loser, "expected");
            noImage(loser);
            use(loader, winner);
        }
    }
    private static WeakReference<ClassLoader> bindAndDrop() throws Throwable {
        var loader = loader("a");
        use(loader, "a");
        Class<?> support = support(loader, "a");
        Class<?>[] types = (Class<?>[])call(support, "preflight", new Class<?>[]{ClassLoader.class}, loader);
        Class<?>[] signature = {ClassLoader.class, Class[].class, String.class, String.class, String.class, String.class};
        Object[] original = {loader, types, field(support, "GENERATION"), field(support, "SCHEMA"), field(support, "API"), metadata("a").getProperty("native.build")};
        call(support, "bootstrap", signature, original);
        for (int i = 2; i < 6; i++) {
            Object[] changed = original.clone(); changed[i] = "wrong";
            try { call(support, "bootstrap", signature, changed); throw new AssertionError("wrong pairing accepted"); }
            catch (LinkageError expected) { if (!expected.getMessage().contains("mismatch")) throw expected; }
        }
        try (var other = loader("a")) {
            Object[] changed = original.clone(); changed[0] = other;
            try { call(support, "bootstrap", signature, changed); throw new AssertionError("different loader accepted"); }
            catch (LinkageError expected) { if (!expected.getMessage().contains("already bound")) throw expected; }
        }
        use(loader, "a"); loader.close(); return new WeakReference<>(loader);
    }
    private static void deployment(String scenario) throws Throwable {
        try (var loader = loader(scenario, "d")) {
            use(loader, "d");
            String oldArch = System.getProperty("os.arch"), oldVersion = System.getProperty("os.version");
            byte[] damaged = null;
            if (scenario.equals("host")) System.setProperty("os.arch", "unsupported-architecture");
            if (scenario.equals("floor")) System.setProperty("os.version", "0.0");
            if (scenario.equals("unsafe")) {
                Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
                String owner = Files.getOwner(temporary).getName();
                String user = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                Path root = temporary.resolve("ironwood-java-bridge-" + user);
                Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            }
            if (scenario.equals("existing")) {
                Path image = (Path)call(support(loader, scenario), "extract", new Class<?>[0]);
                damaged = Files.readAllBytes(image); damaged[damaged.length - 1] ^= 1; Files.write(image, damaged);
            }
            String message = switch (scenario) {
                case "host", "floor" -> "has available targets";
                case "missing" -> "missing paired native resource";
                case "corrupt", "existing" -> "native payload digest mismatch";
                case "unsafe" -> "unsafe Ironwood extraction directory";
                case "build" -> "generation/schema/API/build mismatch";
                default -> throw new AssertionError(scenario);
            };
            try { refuse(loader, scenario, message); refuse(loader, scenario, message); }
            finally { System.setProperty("os.arch", oldArch); System.setProperty("os.version", oldVersion); }
            if (scenario.equals("build")) {
                if (reloadHook(image(scenario).toString()) != 0x00010008) throw new AssertionError("mismatched build bound native image");
            } else if (scenario.equals("existing")) {
                if (!Arrays.equals(damaged, Files.readAllBytes(image(scenario))) || reloadHook(image(scenario).toString()) != -2) {
                    throw new AssertionError("corrupt existing image changed or was loaded");
                }
            } else if (scenario.equals("corrupt") || scenario.equals("missing")) {
                String generation = metadata(scenario).getProperty("generation");
                try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")))) {
                    if (files.anyMatch(p -> p.toString().contains(generation)
                            && (p.toString().endsWith(".dylib") || p.toString().endsWith(".partial")))) {
                        throw new AssertionError("failed resource extraction left payload files");
                    }
                }
            } else noImage(scenario);
            use(loader, "d");
        }
    }
    public static void main(String[] arguments) throws Throwable {
        directory = Path.of(arguments[0]);
        String scenario = arguments[1];
        System.load(Path.of(arguments[2]).toAbsolutePath().toString());
        if (scenario.matches("[abc]{2}-[abc]")) collision(scenario);
        else if (scenario.equals("disjoint")) {
            try (var loader = loader("a", "d")) { use(loader, "a"); use(loader, "d"); use(loader, "a"); }
        } else if (scenario.equals("mixed") || scenario.equals("signature")) {
            try (var loader = loader(scenario)) {
                refuse(loader, scenario, scenario.equals("mixed") ? "expected" : "signature mismatch");
                noImage(scenario);
            }
        } else if (scenario.equals("late")) {
            try (var loader = loader("fault", "d")) {
                use(loader, "d");
                refuse(loader, "fault", "injected partial registration");
                refuse(loader, "fault", "injected partial registration");
                Path image = image("fault");
                if (faultStat(image.toString(), 0) != 2 || faultStat(image.toString(), 1) != 2
                        || faultStat(image.toString(), 2) != 1) throw new AssertionError("partial binding cleanup incorrect");
                if (reloadHook(image.toString()) != -1) throw new AssertionError("failed bound image could reload");
                use(loader, "d");
            }
        } else if (scenario.equals("anchor")) {
            var weak = bindAndDrop();
            for (int i = 0; i < 8; i++) { System.gc(); Thread.sleep(25); }
            if (weak.get() == null) throw new AssertionError("permanent loader anchor lost");
            try (var second = loader("a")) { refuse(second, "a", "already loaded in another classloader"); }
            Path image = image("a");
            if (reloadHook(image.toString()) != -1) throw new AssertionError("bound mapped image could reload");
            use(weak.get(), "a");
        } else if (java.util.Set.of("host", "floor", "missing", "corrupt", "build", "unsafe", "existing").contains(scenario)) {
            deployment(scenario);
        } else throw new AssertionError("unknown scenario " + scenario);
        System.out.println("loader-qualified:" + scenario);
    }
}
