// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import ironwood.compiler.backend.*;
import ironwood.compiler.bridge.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class BridgeOwnedCallbackNativeTests {
    static final String NAME = "Java Bridge paired owner callbacks preserve slots aliases foreign guards and exception identity";
    private BridgeOwnedCallbackNativeTests() {}

    static void owners() throws Exception {
        var admission = BridgeOwnedCallbackJavaTests.admission();
        var producer = BridgeProducerInputs.discover();
        var generation = BridgeGeneration.createOwnedCallbacks("owners.jar", admission,
                producer.compilerVersion(), producer.compilerIdentity(), producer.runtimeIdentity());
        var projected = BridgeOwnedCallbackJavaSources.generate(admission, generation);
        var declarations = projected.declarations();
        var nativeSources = BridgeOwnedCallbackNativeSources.generate(admission, generation, projected);
        try {
            BridgeOwnedCallbackNativeSources.generate(admission, generation, new BridgeOwnedCallbackJavaSources.Sources(
                    declarations, projected.facades(), projected.calls().subList(1, projected.calls().size())));
            throw new AssertionError("partial guard partition accepted");
        } catch (IllegalArgumentException expected) { check(expected.getMessage().contains("exact"), expected.getMessage()); }
        var discovery = LlvmToolchain.discover(null); check(discovery.successful(), discovery.error());
        var toolchain = discovery.toolchain().orElseThrow();
        String target = BridgeGeneratedJarTests.target();
        Path base = Path.of("workspace/java-bridge/evidence/p5/owner-native").toAbsolutePath(); Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "run-"), llvm = directory.resolve("owners.ll");
        Files.writeString(llvm, new LlvmEmitter().emit(admission.program()));
        for (var source : BridgeOwnedCallbackJavaTests.sources()) Files.writeString(directory.resolve(source.path().getFileName()), source.content());
        Path javaHome = Path.of(System.getProperty("java.home"));
        for (var level : List.of(OptimizationLevel.O0, OptimizationLevel.O3)) for (boolean faults : List.of(false, true)) {
            Path folder = directory.resolve(level.name() + (faults ? "-faults" : "")); Files.createDirectories(folder);
            var build = generation.nativeBuild(target, Map.of("fixture", faults ? "owner-callback-faults" : "owner-callbacks", "optimization", level.clangArgument()));
            String source = nativeSources.source() + BridgeBootstrapSources.generate(generation, build, declarations, nativeSources);
            if (faults) source = INJECTION + source.replace("malloc(sizeof(*prepared))", "test_slot_allocate(sizeof(*prepared))")
                    .replace("free(prepared)", "test_slot_free(prepared)").replace("free(slot)", "test_slot_free(slot)")
                    .replace("(*env)->GetStringChars(env, ", "test_chars(env, ").replace("(*env)->ReleaseStringChars(env, ", "test_release_chars(env, ")
                    .replace(" = iw_owned_wrap(env, ", " = test_owned_wrap(env, ")
                    .replace("(*env)->DeleteLocalRef(env, reference", "test_reference_release(env, reference") + COUNTERS;
            Path jar = BridgeGeneratedJarTests.build(folder, llvm, toolchain, level, generation, build, declarations, source, Map.of());
            Files.writeString(folder.resolve("adapter.sha256"), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + "\n");
            Path consumer = folder.resolve("OwnerConsumer.java"); Files.writeString(consumer, CONSUMER);
            BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/javac").toString(), "--release", "21", "-Xlint:all", "-Werror", "-cp", jar.toString(), consumer.toString()), "consumer-javac");
            String output = BridgeEntryTests.run(folder, List.of(javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-Xmx32m", "-Xss1m", "-cp",
                    jar + java.io.File.pathSeparator + folder, "OwnerConsumer", Boolean.toString(faults)), "consumer");
            check(output.equals("owner-callbacks-ok\n"), output);
            if (faults) for (int limit : List.of(2, 3)) {
                String failed = BridgeEntryTests.run(folder, List.of("/usr/bin/env", "IRONWOOD_ALLOCATION_LIMIT=" + limit,
                        javaHome.resolve("bin/java").toString(), "-Xcheck:jni", "-cp", jar + java.io.File.pathSeparator + folder,
                        "OwnerConsumer", "true", "copy-oom"), "copy-oom-" + limit);
                check(failed.equals("owner-copy-oom-ok\n"), failed);
            }
        }
        System.out.println("paired owner callback evidence: " + directory);
    }

    private static final String INJECTION = """
            #include <stdlib.h>
            #include <stdint.h>
            #include <jni.h>
            static int64_t test_slots;
            static int test_failure;
            static int64_t test_buffers;
            static int test_string_failure;
            static int test_reference_failure;
            static int64_t test_references;
            static jobject test_owned_wrap(JNIEnv *, int, void *);
            static void test_reference_release(JNIEnv *env, jobject value) {
                test_references--; (*env)->DeleteLocalRef(env, value);
            }
            static const jchar *test_chars(JNIEnv *env, jstring text, jboolean *copy) {
                if (test_string_failure > 0 && --test_string_failure == 0) {
                    jclass oom = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                    if (oom != NULL) { (*env)->ThrowNew(env, oom, "injected String acquisition failure"); (*env)->DeleteLocalRef(env, oom); }
                    return NULL;
                }
                const jchar *value = (*env)->GetStringChars(env, text, copy);
                if (value != NULL) test_buffers++; return value;
            }
            static void test_release_chars(JNIEnv *env, jstring text, const jchar *value) {
                test_buffers--; (*env)->ReleaseStringChars(env, text, value);
            }
            static void *test_slot_allocate(size_t size) {
                if (test_failure > 0 && --test_failure == 0) return NULL;
                void *value = malloc(size); if (value != NULL) test_slots++; return value;
            }
            static void test_slot_free(void *value) { test_slots--; free(value); }
            """;
    private static final String COUNTERS = """
            JNIEXPORT jlong JNICALL Java_OwnerConsumer_counts(JNIEnv *env, jclass type, jint kind) {
                (void)env; (void)type;
                if (kind == 0) return test_slots;
                if (kind == 1) return (jlong)iw_root_occupied;
                if (kind == 2) return ironwood_allocation_count();
                if (kind == 4) return test_buffers;
                if (kind == 5) return ironwood_live_allocation_count();
                if (kind == 6) return test_references;
                int64_t count = 0;
                for (struct iw_listener *listener = iw_listeners; listener != NULL; listener = listener->next) count++;
                return count;
            }
            JNIEXPORT void JNICALL Java_OwnerConsumer_fault(JNIEnv *env, jclass type, jint count) {
                (void)env; (void)type; test_failure = count;
            }
            JNIEXPORT void JNICALL Java_OwnerConsumer_stringFault(JNIEnv *env, jclass type, jint count) {
                (void)env; (void)type; test_string_failure = count;
            }
            JNIEXPORT void JNICALL Java_OwnerConsumer_referenceFault(JNIEnv *env, jclass type, jint count) {
                (void)env; (void)type; test_reference_failure = count;
            }
            static jobject test_owned_wrap(JNIEnv *env, int kind, void *address) {
                if (test_reference_failure > 0 && --test_reference_failure == 0) {
                    jclass oom = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                    if (oom != NULL) { (*env)->ThrowNew(env, oom, "injected facade conversion failure"); (*env)->DeleteLocalRef(env, oom); }
                    return NULL;
                }
                jobject value = iw_owned_wrap(env, kind, address);
                if (value != NULL) test_references++; return value;
            }
            """;

    private static final String CONSUMER = """
            import ownerfacades.Holder;
            import ownerfacades.OtherOwner;
            import ownerfacades.Listener;
            public final class OwnerConsumer {
                private static Class<?> refusal;
                private static native long counts(int kind);
                private static native void fault(int count);
                private static native void stringFault(int count);
                private static native void referenceFault(int count);
                private static volatile Object allocated;
                private static Object state(Object owner) throws Exception {
                    for (var field : owner.getClass().getDeclaredFields()) if (field.getType().getSimpleName().equals("RootState")) {
                        field.setAccessible(true); return field.get(owner);
                    }
                    throw new AssertionError("missing private state");
                }
                private static void active(Object owner, long value) throws Exception {
                    Object state = state(owner); var field = state.getClass().getDeclaredField("activeUses");
                    field.setAccessible(true); field.setLong(state, value);
                }
                private static long active(Object owner) throws Exception {
                    Object state = state(owner); var field = state.getClass().getDeclaredField("activeUses");
                    field.setAccessible(true); return field.getLong(state);
                }
                private static void refused(Runnable action) {
                    try { action.run(); throw new AssertionError("missing lifetime refusal"); }
                    catch (IllegalStateException expected) {
                        check(expected.getClass().getSimpleName().equals("BridgeLifetimeException"));
                        if (refusal == null) refusal = expected.getClass(); else check(refusal == expected.getClass());
                    }
                }
                private static void same(Throwable expected, Runnable action) {
                    try { action.run(); throw new AssertionError("missing callback failure"); }
                    catch (Throwable actual) { check(expected == actual); }
                }
                public static void main(String[] args) throws Exception {
                    boolean instrumented = Boolean.parseBoolean(args[0]);
                    if (args.length > 1) { copiedFailure(); return; }
                    Holder left = new Holder(5L), right = new Holder(7L);
                    OtherOwner foreign = new OtherOwner();
                    try {
                        Listener a = n -> n + 1L, b = n -> n * 2L;
                        left.store(a); foreign.store(b);
                        check(left.fire(right, foreign, 3L) == 17L && left.value() == 5L);
                        active(foreign, Long.MAX_VALUE);
                        refused(() -> left.fire(right, foreign, 3L));
                        check(active(left) == 0L && active(right) == 0L && active(foreign) == Long.MAX_VALUE);
                        active(foreign, 0L);
                        check(left.fire(left, foreign, 3L) == 15L);
                        if (instrumented) {
                            long allocations = counts(2);
                            for (int i = 0; i < 1000; i++) check(left.twice(1L) == 5L);
                            check(counts(2) == allocations && counts(0) == 2 && counts(1) == 3 && counts(3) == 2);
                            for (int fail = 1; fail <= 2; fail++) {
                                fault(fail);
                                try { left.choose(a, b, false); throw new AssertionError("missing slot preparation failure"); }
                                catch (OutOfMemoryError expected) { }
                                finally { fault(0); }
                                check(left.twice(1L) == 5L && counts(0) == 2 && counts(3) == 2);
                            }
                        }
                        left.store(n -> { refused(left::free); refused(right::free); refused(foreign::free); return n; });
                        check(left.fire(right, foreign, 3L) == 16L);
                        left.store(n -> { if (n == 1L) left.store(b); return n + 10L; });
                        check(left.twice(1L) == 23L && left.twice(1L) == 6L);
                        left.choose(a, b, false); check(left.twice(1L) == 6L);
                        try { left.choose(a, b, true); throw new AssertionError("missing setter failure"); }
                        catch (IllegalStateException expected) { check(expected.getClass() == IllegalStateException.class); }
                        check(left.twice(1L) == 5L);
                        Holder.put(left, left, a, b); check(left.twice(1L) == 6L);
                        Holder.put(left, right, a, b); check(left.twice(1L) == 5L && right.twice(1L) == 6L);
                        copied(left, instrumented);
                        exposed(left, right, foreign, instrumented);
                        RuntimeException original = new RuntimeException("callback");
                        left.store(n -> { left.store(b); throw original; });
                        same(original, () -> left.fire(right, foreign, 3L));
                        check(left.fire(right, foreign, 3L) == 19L);
                        foreign.store(n -> { throw original; });
                        same(original, () -> left.fire(right, foreign, 3L));
                        foreign.store(b);
                        boolean[] nested = {false};
                        left.store(n -> {
                            refused(left::free); refused(right::free); refused(foreign::free);
                            if (!nested[0]) { nested[0] = true; check(left.fire(right, foreign, n) == n + 7L + n * 2L); nested[0] = false; }
                            return n;
                        });
                        check(left.fire(right, foreign, 4L) == 19L);
                        same(original, () -> Holder.direct(left, n -> { throw original; }, 1L));
                        check(Holder.direct(left, a, 1L) == 7L);
                        left.store(a); foreign.free();
                        refused(() -> left.fire(right, foreign, 1L));
                        check(left.twice(1L) == 5L);
                        right.free(); refused(() -> left.fire(right, foreign, 1L));
                        check(left.twice(1L) == 5L);
                        left.store(null);
                        try { left.twice(1L); throw new AssertionError("missing null listener failure"); }
                        catch (NullPointerException expected) { }
                    } finally { left.free(); right.free(); foreign.free(); }
                    left.free(); right.free(); foreign.free();
                    refused(() -> left.value());
                    if (instrumented) check(counts(0) == 0 && counts(1) == 0 && counts(3) == 0 && counts(4) == 0);
                    System.out.println("owner-callbacks-ok");
                }
                private static long hash(String text) {
                    if (text == null) return -1L;
                    long result = 1L; for (int i = 0; i < text.length(); i++) result = result * 31L + text.charAt(i);
                    return result;
                }
                private static void exposed(Holder left, Holder right, OtherOwner foreign, boolean instrumented) throws Exception {
                    Object[] retained = new Object[3];
                    check(left.expose((self, other, different) -> {
                        check(self == left && other == right && different == foreign);
                        retained[0] = self; retained[1] = other; retained[2] = different;
                        refused(self::free); refused(other::free); refused(different::free);
                        check(self.expose((again, alias, empty) -> {
                            check(again == self && alias == self && empty == null);
                            refused(again::free); return 9L;
                        }, self, null) == 9L);
                        return self.value() + other.value();
                    }, right, foreign) == 12L);
                    check(retained[0] == left && retained[1] == right && retained[2] == foreign);
                    check(active(left) == 0L && active(right) == 0L && active(foreign) == 0L);
                    RuntimeException failure = new RuntimeException("reference callback");
                    same(failure, () -> left.expose((self, other, different) -> { throw failure; }, right, foreign));
                    Holder temporary = new Holder(19L);
                    check(temporary.expose((self, empty, different) -> { retained[0] = self; return self.value(); }, null, null) == 19L);
                    temporary.free();
                    refused(() -> ((Holder)retained[0]).value());
                    check(active(left) == 0L && active(right) == 0L && active(foreign) == 0L);
                    if (instrumented) {
                        check(counts(6) == 0L);
                        for (int fail = 1; fail <= 3; fail++) {
                            referenceFault(fail);
                            try {
                                left.expose((self, other, different) -> { throw new AssertionError("failed conversion called listener"); }, right, foreign);
                                throw new AssertionError("missing facade conversion failure");
                            } catch (OutOfMemoryError expected) { }
                            finally { referenceFault(0); }
                            check(counts(6) == 0L && active(left) == 0L && active(right) == 0L && active(foreign) == 0L);
                        }
                        // Test-only cache eviction exercises reconstitution. Real
                        // weak entries vanish only after the old facade is unreachable.
                        Holder evicted = new Holder(23L);
                        Object ownerState = state(evicted);
                        var cache = ownerState.getClass().getDeclaredField("cache"); cache.setAccessible(true); cache.set(ownerState, null);
                        check(evicted.expose((self, other, different) -> {
                            check(self != evicted && self.hashCode() == evicted.hashCode()); retained[0] = self;
                            refused(self::free); return self.value();
                        }, null, null) == 23L);
                        Holder restored = (Holder)retained[0];
                        check(state(restored) == ownerState);
                        check(restored.expose((self, other, different) -> { check(self == restored); return self.value(); }, null, null) == 23L);
                        restored.free(); refused(evicted::value); evicted.free(); check(counts(6) == 0L);
                    }
                }
                private static void copied(Holder holder, boolean instrumented) throws Exception {
                    String[] values = {null, "", "abc", new String(new char[]{'a', 0, 'b'}), new String(new char[]{0xd800, 'x', 0xdc00})};
                    holder.store(n -> n);
                    for (String first : values) for (String second : values) {
                        check(holder.textValue(first) == hash(first));
                        check(holder.text(first, second, 0L) == hash(first) + hash(second));
                    }
                    boolean[] nested = {false};
                    holder.store(n -> {
                        refused(holder::free); allocated = new byte[1024];
                        if (!nested[0]) { nested[0] = true; check(holder.text("inner", null, 1L) == hash("inner") - 1L); nested[0] = false; }
                        return n;
                    });
                    check(holder.text("outer", "tail", 0L) == hash("outer") + hash("tail"));
                    RuntimeException failure = new RuntimeException("copied callback");
                    holder.store(n -> { throw failure; });
                    long live = instrumented ? counts(5) : 0L;
                    same(failure, () -> holder.text("outer", "tail", 0L));
                    if (instrumented) {
                        check(counts(5) == live && counts(4) == 0);
                        holder.store(n -> { throw new AssertionError("failed acquisition ran native callback"); });
                        for (int fail = 1; fail <= 2; fail++) {
                            long before = counts(2); stringFault(fail);
                            try { holder.text("outer", "tail", 0L); throw new AssertionError("missing String failure"); }
                            catch (OutOfMemoryError expected) { }
                            finally { stringFault(0); }
                            check(counts(2) == before && counts(4) == 0 && active(holder) == 0L);
                        }
                    }
                }
                private static void copiedFailure() {
                    Holder holder = new Holder(5L);
                    try {
                        holder.store(n -> { throw new AssertionError("failed native copy ran callback"); });
                        check(counts(2) == 2L); long live = counts(5);
                        try { holder.text("first", "second", 0L); throw new AssertionError("missing native copy failure"); }
                        catch (OutOfMemoryError expected) { }
                        check(counts(5) == live && counts(4) == 0L);
                    } finally { holder.free(); }
                    check(counts(0) == 0 && counts(1) == 0 && counts(3) == 0);
                    System.out.println("owner-copy-oom-ok");
                }
                private static void check(boolean value) { if (!value) throw new AssertionError(); }
            }
            """;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
