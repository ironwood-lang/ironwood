// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

/** One-target P2 loader. Native bootstrap/exception adapters are generated separately. */
public final class BridgeLoaderSources {
    private BridgeLoaderSources() {}

    public record Payload(BridgeGeneration.NativeBuild build, String minimumOs, String imageSha256) {
        public Payload {
            if (!build.target().equals("macos-arm64") || !minimumOs.matches("[0-9]+(?:\\.[0-9]+){0,2}")
                    || !imageSha256.matches("[0-9a-f]{64}") || !build.identity().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid macOS ARM64 preview payload");
            }
        }
    }

    public static String generate(BridgeGeneration generation, BridgeJavaSources declarations, Payload payload) {
        if (!payload.build().generation().equals(generation.identity()) || !payload.build().api().equals(generation.apiIdentity())) {
            throw new IllegalArgumentException("loader payload generation mismatch");
        }
        if (!declarations.generatedTypes().contains(generation.supportPackage() + ".Support")) {
            throw new IllegalArgumentException("loader declarations belong to another generation");
        }
        String classes = declarations.generatedTypes().stream().map(BridgeJavaSources::quote).collect(java.util.stream.Collectors.joining(", "));
        String bindings = declarations.bindings().stream().map(binding -> "{" + BridgeJavaSources.quote(binding.binaryName())
                + ", " + BridgeJavaSources.quote(binding.nativeName()) + ", " + BridgeJavaSources.quote(binding.descriptor()) + "}")
                .collect(java.util.stream.Collectors.joining(",\n            "));
        return TEMPLATE.replace("@PACKAGE@", generation.supportPackage())
                .replace("@GENERATION@", generation.identity()).replace("@API@", generation.apiIdentity())
                .replace("@SCHEMA@", BridgeGeneration.SCHEMA).replace("@BUILD@", payload.build().identity())
                .replace("@MINIMUM_OS@", payload.minimumOs()).replace("@SHA256@", payload.imageSha256())
                .replace("@TYPES@", classes).replace("@BINDINGS@", bindings).replace("@ENSURE@", declarations.ensureMethod());
    }

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
                private static final String GENERATION = "@GENERATION@";
                private static final String API = "@API@";
                private static final String SCHEMA = "@SCHEMA@";
                private static final String BUILD = "@BUILD@";
                private static final String IMAGE_SHA256 = "@SHA256@";
                private static final String RESOURCE = "/META-INF/ironwood/native/macos-arm64/@GENERATION@/libbridge.dylib";
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
                        Class<?>[] types = preflight(loader);
                        requireHost();
                        Path image = extract();
                        System.load(image.toString());
                        bootstrap(loader, types, GENERATION, SCHEMA, API, BUILD);
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

                private static void requireHost() {
                    String os = System.getProperty("os.name", "");
                    String arch = System.getProperty("os.arch", "");
                    if (!os.equals("Mac OS X") || !(arch.equals("aarch64") || arch.equals("arm64"))
                            || !System.getProperty("sun.arch.data.model", "").equals("64")
                            || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN
                            || !atLeast(System.getProperty("os.version", ""), "@MINIMUM_OS@")) {
                        throw new UnsatisfiedLinkError("Ironwood artifact " + GENERATION + " has macos-arm64 only (64-bit little-endian, macOS >= @MINIMUM_OS@); detected "
                                + os + " " + arch + " " + System.getProperty("os.version"));
                    }
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
                    root = privateDirectory(root.resolve("macos-arm64"), owner);
                    Path image = root.resolve("libbridge.dylib");
                    if (Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
                        verify(image, owner);
                        return image.toRealPath();
                    }
                    Path partial = Files.createTempFile(root, ".payload-", ".partial", PosixFilePermissions.asFileAttribute(FILE_MODE));
                    try {
                        try (InputStream input = Support.class.getResourceAsStream(RESOURCE)) {
                            if (input == null) throw new IOException("missing paired native resource " + RESOURCE);
                            try (var output = Files.newOutputStream(partial, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                                input.transferTo(output);
                            }
                        }
                        verify(partial, owner);
                        // Hard-link publication is atomic and cannot replace an existing
                        // image, even when independent classloaders race with different builds.
                        try { Files.createLink(image, partial); }
                        catch (FileAlreadyExistsException raced) { /* Verify the winner below. */ }
                        verify(image, owner);
                        return image.toRealPath();
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

                private static void verify(Path path, UserPrincipal owner) throws IOException {
                    PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile() || !attributes.owner().equals(owner) || !attributes.permissions().equals(FILE_MODE)) {
                        throw new IOException("unsafe Ironwood extraction file: " + path);
                    }
                    MessageDigest digest = sha256();
                    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[32768];
                        for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
                    }
                    if (!HexFormat.of().formatHex(digest.digest()).equals(IMAGE_SHA256)) {
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
