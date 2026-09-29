// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Paired target selection and private extraction; native bootstrap is generated separately. */
public final class BridgeLoaderSources {
    private BridgeLoaderSources() {}

    public record Payload(BridgeGeneration.NativeBuild build, String minimumOs, String imageSha256,
                          Map<String, String> dependencies) {
        public Payload(BridgeGeneration.NativeBuild build, String minimumOs, String imageSha256) {
            this(build, minimumOs, imageSha256, Map.of());
        }

        public Payload {
            if (!Set.of("macos-arm64", "linux-arm64", "linux-x86_64").contains(build.target()) || !minimumOs.matches("[0-9]+(?:\\.[0-9]+){0,2}")
                    || !imageSha256.matches("[0-9a-f]{64}") || !build.identity().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid Java Bridge target payload");
            }
            dependencies = java.util.Collections.unmodifiableMap(new TreeMap<>(dependencies));
            for (var file : dependencies.entrySet()) {
                if (!file.getKey().matches("[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*")
                        || List.of(file.getKey().split("/")).stream().anyMatch(part -> part.equals(".") || part.equals(".."))
                        || file.getKey().equals("libbridge.so") || file.getKey().equals("libbridge.dylib")
                        || !file.getValue().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid native dependency path/digest");
            }
            if (build.target().equals("macos-arm64") && !dependencies.isEmpty()) {
                throw new IllegalArgumentException("macOS bridge payload may only use supported system dependencies");
            }
        }

        public String filename() { return build.target().equals("macos-arm64") ? "libbridge.dylib" : "libbridge.so"; }
    }

    private static String row(String... values) {
        return "{" + java.util.Arrays.stream(values).map(BridgeJavaSources::quote).collect(java.util.stream.Collectors.joining(", ")) + "}";
    }

    public static String generate(BridgeGeneration generation, BridgeJavaSources declarations, Payload payload) {
        return generate(generation, declarations, List.of(payload));
    }

    public static String generate(BridgeGeneration generation, BridgeJavaSources declarations, List<Payload> payloads) {
        return generate(generation, declarations.generatedTypes(), declarations.nativeDeclarations(), declarations.ensureMethod(), payloads);
    }

    /** Assembly rebuilds only this loader from validated common Java declaration metadata. */
    public static String generate(BridgeGeneration generation, List<String> generatedTypes,
            List<BridgeJavaSources.NativeDeclaration> nativeDeclarations, String ensureMethod, List<Payload> payloads) {
        if (ensureMethod == null || !ensureMethod.matches("\\$ironwood\\$ensure\\$*")) {
            throw new IllegalArgumentException("invalid generated loader entry name");
        }
        var targets = new TreeMap<String, Payload>();
        for (var payload : payloads) {
            if (!payload.build().generation().equals(generation.identity()) || !payload.build().api().equals(generation.apiIdentity())) {
                throw new IllegalArgumentException("loader payload generation mismatch");
            }
            if (targets.putIfAbsent(payload.build().target(), payload) != null) throw new IllegalArgumentException("duplicate loader target");
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("loader requires a payload");
        if (!generatedTypes.contains(generation.supportPackage() + ".Support")) {
            throw new IllegalArgumentException("loader declarations belong to another generation");
        }
        String classes = generatedTypes.stream().map(BridgeJavaSources::quote).collect(java.util.stream.Collectors.joining(", "));
        String bindings = nativeDeclarations.stream().map(binding -> "{" + BridgeJavaSources.quote(binding.binaryName())
                + ", " + BridgeJavaSources.quote(binding.nativeName()) + ", " + BridgeJavaSources.quote(binding.descriptor()) + "}")
                .collect(java.util.stream.Collectors.joining(",\n            "));
        var files = new java.util.ArrayList<String>();
        for (var payload : targets.values()) {
            payload.dependencies().forEach((path, sha) -> files.add(row(payload.build().target(), path, sha)));
            files.add(row(payload.build().target(), payload.filename(), payload.imageSha256()));
        }
        boolean views = nativeDeclarations.stream().anyMatch(binding -> binding.descriptor().contains("Lironwood/bridge/ByteView;"));
        String inventory = targets.values().stream().map(payload -> row(payload.build().target(), payload.build().identity(),
                payload.minimumOs(), payload.filename())).collect(java.util.stream.Collectors.joining(",\n            "));
        return TEMPLATE.replace("@PACKAGE@", generation.supportPackage())
                .replace("@GENERATION@", generation.identity()).replace("@API@", generation.apiIdentity())
                .replace("@SCHEMA@", BridgeGeneration.SCHEMA).replace("@PAYLOADS@", inventory).replace("@FILES@", String.join(",\n            ", files))
                .replace("@VIEW_HELPERS@", views ? VIEW_HELPERS : "").replace("@VIEW_VERIFY@", views ? "verifyByteView(loader);" : "")
                .replace("@TYPES@", classes).replace("@BINDINGS@", bindings).replace("@ENSURE@", ensureMethod);
    }

    private static final String VIEW_HELPERS = """
            private static Class<?> byteViewClass;
            private static void verifyByteView(ClassLoader loader) throws IOException {
                Class<?> type;
                try { type = Class.forName("ironwood.bridge.ByteView", false, loader); }
                catch (ClassNotFoundException missing) {
                    throw new IOException("add the paired ironwood-bridge-values.jar to the classpath or module path", missing);
                }
                byte[] expected;
                try (InputStream resource = Support.class.getResourceAsStream("/META-INF/ironwood/java-dependencies/ironwood-bridge-values.jar")) {
                    if (resource == null) throw new IOException("missing paired byte-view dependency");
                    expected = resource.readAllBytes();
                }
                var origin = type.getProtectionDomain().getCodeSource();
                if (origin == null) throw new IOException("byte-view dependency has no verifiable jar origin");
                try (InputStream actual = origin.getLocation().openStream()) {
                    if (!Arrays.equals(expected, actual.readAllBytes())) {
                        throw new IOException("incompatible ironwood-bridge-values.jar; use the paired dependency");
                    }
                }
                byteViewClass = type;
            }
            """;

    private static final String TEMPLATE = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            import java.io.IOException;
            import java.io.InputStream;
            import java.lang.invoke.MethodType;
            import java.lang.reflect.Method;
            import java.lang.reflect.Modifier;
            import java.nio.ByteOrder;
            import java.nio.file.*;
            import java.nio.file.attribute.*;
            import java.security.MessageDigest;
            import java.security.NoSuchAlgorithmException;
            import java.util.*;

            @Identity("@GENERATION@")
            public final class Support {
                @VIEW_HELPERS@
                private static final String GENERATION = "@GENERATION@";
                private static final String API = "@API@";
                private static final String SCHEMA = "@SCHEMA@";
                // target, native build, minimum macOS/glibc version, image filename
                private static final String[][] PAYLOADS = {@PAYLOADS@};
                // target, relative file, SHA-256; dependencies precede their image
                private static final String[][] FILES = {@FILES@};
                private static final String[] TYPES = {@TYPES@};
                private static final String[][] BINDINGS = {@BINDINGS@};
                private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
                private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
                private static boolean ready;
                private static Throwable failed;

                private Support() {}
                private static native void bootstrap(ClassLoader loader, Class<?>[] types,
                        String generation, String schema, String api, String build);

                public static synchronized void @ENSURE@() {
                    if (ready) return;
                    if (failed != null) fail(failed);
                    try {
                        Runtime.Version version = Runtime.version();
                        if (!supportedVersion(version.feature())) {
                            throw new UnsatisfiedLinkError("Ironwood artifact " + GENERATION + " requires Java 21-23; detected " + version);
                        }
                        ClassLoader loader = Support.class.getClassLoader();
                        if (loader == null) throw new LinkageError("Ironwood artifact requires a defining application loader: " + GENERATION);
                        @VIEW_VERIFY@
                        Class<?>[] types = preflight(loader);
                        String[] payload = requireHost();
                        Path image = extract(payload);
                        System.load(image.toString());
                        bootstrap(loader, types, GENERATION, SCHEMA, API, payload[1]);
                        ready = true;
                    } catch (Throwable problem) {
                        failed = problem;
                        fail(problem);
                    }
                }

                private static boolean supportedVersion(int feature) { return feature >= 21 && feature <= 23; }

                private static void fail(Throwable problem) {
                    if (problem instanceof Error error) throw error;
                    if (problem instanceof RuntimeException runtime) throw runtime;
                    throw new LinkageError("Ironwood artifact " + GENERATION + ": " + problem.getMessage(), problem);
                }

                private static Class<?>[] preflight(ClassLoader loader) throws ClassNotFoundException {
                    Class<?>[] checked = new Class<?>[TYPES.length];
                    for (int index = 0; index < TYPES.length; index++) {
                        Class<?> type = Class.forName(TYPES[index], false, loader);
                        List<String> expected = new ArrayList<>();
                        for (String[] binding : BINDINGS) {
                            if (binding[0].equals(TYPES[index])) expected.add(binding[1] + binding[2]);
                        }
                        if (type == Support.class) {
                            expected.add("bootstrap(Ljava/lang/ClassLoader;[Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                        }
                        nativePreflight(loader, type, TYPES[index], GENERATION, expected.toArray(String[]::new));
                        checked[index] = type;
                    }
                    return checked;
                }

                // Native bootstrap repeats this check with names/signatures embedded
                // in its own payload, before its first registration. Reflection does
                // not initialize the facade or read any of its static fields.
                private static void nativePreflight(ClassLoader loader, Class<?> type, String name,
                        String generation, String[] signatures) {
                    Identity identity = type.getDeclaredAnnotation(Identity.class);
                    String observed = identity == null ? "missing identity" : identity.value();
                    if (!type.getName().equals(name) || type.getClassLoader() != loader || !generation.equals(observed)) {
                        throw new LinkageError("Ironwood class/package " + name + " expected " + generation
                                + " observed " + observed + " on " + type.getName());
                    }
                    Set<String> expected = new HashSet<>(Arrays.asList(signatures));
                    if (expected.size() != signatures.length) throw new LinkageError("duplicate Ironwood native signature: " + name);
                    for (Method method : type.getDeclaredMethods()) {
                        if (!Modifier.isNative(method.getModifiers())) continue;
                        String descriptor = MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
                        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPrivate(method.getModifiers())
                                || !expected.remove(method.getName() + descriptor)) {
                            throw new LinkageError("Ironwood native signature mismatch: " + name + "." + method.getName());
                        }
                    }
                    if (!expected.isEmpty()) throw new LinkageError("Ironwood missing native signatures: " + name + " " + expected);
                }

                private static String[] requireHost() {
                    String os = System.getProperty("os.name", "");
                    String arch = System.getProperty("os.arch", "");
                    boolean arm = arch.equals("aarch64") || arch.equals("arm64");
                    String target = os.equals("Mac OS X") && arm ? "macos-arm64"
                            : os.equals("Linux") && arm ? "linux-arm64"
                            : os.equals("Linux") && (arch.equals("amd64") || arch.equals("x86_64")) ? "linux-x86_64" : "unsupported";
                    if (System.getProperty("sun.arch.data.model", "").equals("64") && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                        for (String[] payload : PAYLOADS) {
                            if (payload[0].equals(target) && (!target.equals("macos-arm64")
                                    || atLeast(System.getProperty("os.version", ""), payload[2]))) return payload;
                        }
                    }
                    throw new UnsatisfiedLinkError("Ironwood artifact " + GENERATION + " has available targets " + Arrays.deepToString(PAYLOADS)
                            + " (64-bit little-endian; macOS minimum or Linux glibc baseline as listed); detected "
                            + os + " " + arch + " " + System.getProperty("os.version"));
                }

                private static boolean atLeast(String observed, String required) {
                    String[] actual = observed.split("\\\\.");
                    String[] minimum = required.split("\\\\.");
                    try {
                        for (int index = 0; index < Math.max(actual.length, minimum.length); index++) {
                            int left = index < actual.length ? Integer.parseInt(actual[index]) : 0;
                            int right = index < minimum.length ? Integer.parseInt(minimum[index]) : 0;
                            if (left != right) return left > right;
                        }
                        return true;
                    } catch (NumberFormatException invalid) { return false; }
                }

                private static Path extract() throws IOException {
                    return extract(requireHost());
                }

                private static Path extract(String[] payload) throws IOException {
                    Path base = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
                    UserPrincipal owner;
                    Path probe = Files.createTempFile(base, ".ironwood-owner-", ".tmp", PosixFilePermissions.asFileAttribute(FILE_MODE));
                    try { owner = Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS); }
                    finally { Files.delete(probe); }
                    String user = HexFormat.of().formatHex(sha256().digest(owner.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    Path root = privateDirectory(base.resolve("ironwood-java-bridge-" + user), owner);
                    ProcessHandle process = ProcessHandle.current();
                    String started = process.info().startInstant().orElseThrow(() -> new IOException("cannot identify current JVM start time")).toString();
                    String jvm = process.pid() + "-" + HexFormat.of().formatHex(sha256().digest(started.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    root = privateDirectory(root.resolve("jvm-" + jvm), owner);
                    root = privateDirectory(root.resolve(GENERATION), owner);
                    root = privateDirectory(root.resolve(payload[0]), owner);
                    for (String[] file : FILES) {
                        if (!file[0].equals(payload[0])) continue;
                        Path parent = root;
                        String[] components = file[1].split("/");
                        for (int index = 0; index < components.length - 1; index++) parent = privateDirectory(parent.resolve(components[index]), owner);
                        extractFile(parent.resolve(components[components.length - 1]), owner,
                                "/META-INF/ironwood/native/" + payload[0] + "/" + GENERATION + "/" + file[1], file[2]);
                    }
                    return root.resolve(payload[3]).toRealPath();
                }

                private static void extractFile(Path image, UserPrincipal owner, String resource, String expectedSha256) throws IOException {
                    if (Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
                        verify(image, owner, expectedSha256);
                        return;
                    }
                    Path partial = Files.createTempFile(image.getParent(), ".payload-", ".partial", PosixFilePermissions.asFileAttribute(FILE_MODE));
                    try {
                        try (InputStream input = Support.class.getResourceAsStream(resource)) {
                            if (input == null) throw new IOException("missing paired native resource " + resource);
                            try (var output = Files.newOutputStream(partial, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                                input.transferTo(output);
                            }
                        }
                        verify(partial, owner, expectedSha256);
                        // Hard-link publication is atomic and cannot replace an existing
                        // image, even when independent classloaders race with different builds.
                        try { Files.createLink(image, partial); }
                        catch (FileAlreadyExistsException raced) { /* Verify the winner below. */ }
                        verify(image, owner, expectedSha256);
                    } finally { Files.deleteIfExists(partial); }
                }

                private static Path privateDirectory(Path path, UserPrincipal owner) throws IOException {
                    try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)); }
                    catch (FileAlreadyExistsException present) { /* Validate, never repair another path. */ }
                    PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isDirectory() || !attributes.owner().equals(owner) || !attributes.permissions().equals(DIRECTORY_MODE)) {
                        throw new IOException("unsafe Ironwood extraction directory: " + path);
                    }
                    return path;
                }

                private static void verify(Path path, UserPrincipal owner, String expectedSha256) throws IOException {
                    PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || !attributes.owner().equals(owner) || !attributes.permissions().equals(FILE_MODE)) {
                        throw new IOException("unsafe Ironwood extraction file: " + path);
                    }
                    MessageDigest digest = sha256();
                    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[32768];
                        for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
                    }
                    if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
                        throw new IOException("Ironwood native payload digest mismatch for " + GENERATION + ": " + path);
                    }
                }

                private static MessageDigest sha256() {
                    try { return MessageDigest.getInstance("SHA-256"); }
                    catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
                }
            }
            """;
}
