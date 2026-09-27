// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

/** Actual-engine D208 subprocess, with optional copied-runtime event recording. */
final class BridgeOrderBookFixtureSources {
    private BridgeOrderBookFixtureSources() {}

    static final String CONSUMER = """
            public final class BridgeOrderBookConsumer {
                record Book(long address) { Book { check(address != 0); } }
                static native long construct(int capacity);
                static native long order(long book);
                static native void verify(long book, long order, boolean mutate);
                static native long metric(boolean live);
                static native void phase(int phase);
                static native void events();
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                public static void main(String[] args) {
                    System.load(args[0]);
                    phase(1);
                    Book control = new Book(construct(2)); long order = order(control.address());
                    check(order != 0); verify(control.address(), order, false);
                    long before = metric(false), live = metric(true);
                    System.out.println("control:" + before + ":" + live);
                    phase(2); Book attempted = null;
                    if (args[1].equals("calibrate")) {
                        attempted = new Book(construct(3));
                        check(metric(false) - before == 12 && metric(true) - live == 12);
                        System.out.println("created:" + (metric(false) - before));
                    } else {
                        int successes = Integer.parseInt(args[1]);
                        try { attempted = new Book(construct(3)); throw new AssertionError("construction succeeded"); }
                        catch (OutOfMemoryError expected) {
                            check(expected.getMessage().contains("OrderBook.iron:"));
                            System.out.println("contained:" + expected.getMessage());
                        }
                        check(attempted == null);
                        long allocated = metric(false) - before, surviving = metric(true) - live;
                        check(allocated == successes && surviving == successes - (successes == 11 ? 2 : 1));
                        System.out.println("failed:" + allocated + ":" + surviving);
                    }
                    phase(3); long after = metric(false), afterLive = metric(true);
                    verify(control.address(), order, true);
                    check(metric(false) == after && metric(true) == afterLive);
                    System.out.println("control-survived:allocation-free"); events();
                }
            }
            """;

    static final String ADAPTER = """
            #include <stdio.h>
            #include <inttypes.h>
            extern void ironwood_bridge_bootstrap(void);
            static jclass oom_class;
            static void *side;
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved; JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                jclass local = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
                if (local == NULL) return JNI_ERR;
                oom_class = (jclass)(*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local);
                if (oom_class == NULL) return JNI_ERR;
                ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;
            }
            static int failure(JNIEnv *env, int status, const struct ironwood_bridge_result *frame) {
                if (status == 0) return 0;
                if (status != 1 || strstr(frame->failure.type_name, "OutOfMemoryError") == NULL) abort();
                char message[2048]; size_t length = 0;
                for (int i = 0; i < frame->failure.frame_count; i++) {
                    const struct ironwood_trace_site *site = frame->failure.frames[i];
                    int written = snprintf(message + length, sizeof(message) - length, "%s(%s:%d);",
                        site->callable, site->file, site->line);
                    if (written < 0 || (size_t)written >= sizeof(message) - length) abort();
                    length += (size_t)written;
                }
                if (length == 0) abort();
                (*env)->ThrowNew(env, oom_class, message); return 1;
            }
            JNIEXPORT jlong JNICALL Java_BridgeOrderBookConsumer_construct(JNIEnv *env, jclass type, jint capacity) {
                (void)type; struct ironwood_bridge_result frame;
                frame.value.reference = (void *)(uintptr_t)0xdead;
                int status = call_OrderBook(capacity, capacity, (int64_t)(uintptr_t)&frame);
                if (status != 0) {
                    if (frame.value.reference != NULL) abort();
                    failure(env, status, &frame); return 0;
                }
                return (jlong)(uintptr_t)frame.value.reference;
            }
            JNIEXPORT jlong JNICALL Java_BridgeOrderBookConsumer_order(JNIEnv *env, jclass type, jlong book) {
                (void)type; struct ironwood_bridge_result frame;
                int status = call_buy((int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return 0;
                side = frame.value.reference;
                status = call_createLimit((void *)(uintptr_t)book, 101, side, 25, 100, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return 0;
                return (jlong)(uintptr_t)frame.value.reference;
            }
            JNIEXPORT void JNICALL Java_BridgeOrderBookConsumer_verify(JNIEnv *env, jclass type, jlong book, jlong order, jboolean mutate) {
                (void)type; struct ironwood_bridge_result frame;
                int status = call_getId((void *)(uintptr_t)order, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                if (frame.value.wide != 101) abort();
                status = call_getOpenSize((void *)(uintptr_t)order, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                if (frame.value.wide != 25) abort();
                status = call_getRestingOrderCount((void *)(uintptr_t)book, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                if (frame.value.integer != 1) abort();
                if (!mutate) return;
                status = call_reduceTo((void *)(uintptr_t)order, 12, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                status = call_getOpenSize((void *)(uintptr_t)order, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                if (frame.value.wide != 12) abort();
                status = call_cancel((void *)(uintptr_t)order, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                status = call_getRestingOrderCount((void *)(uintptr_t)book, (int64_t)(uintptr_t)&frame);
                if (failure(env, status, &frame)) return;
                if (frame.value.integer != 0) abort();
            }
            JNIEXPORT jlong JNICALL Java_BridgeOrderBookConsumer_metric(JNIEnv *env, jclass type, jboolean live) {
                (void)env; (void)type;
                return live ? (jlong)ironwood_live_allocation_count() : (jlong)ironwood_allocation_count();
            }
            JNIEXPORT void JNICALL Java_BridgeOrderBookConsumer_phase(JNIEnv *env, jclass type, jint phase) {
                (void)env; (void)type; PHASE(phase);
            }
            JNIEXPORT void JNICALL Java_BridgeOrderBookConsumer_events(JNIEnv *env, jclass type) {
                (void)env; (void)type; EVENTS();
            }
            """;

    static final String RECORDING = """
            #include <inttypes.h>
            /* Test-only fixed event log: no production runtime or allocation policy change. */
            struct bridge_allocation_event { int phase, kind; uintptr_t address; size_t size; const char *type; };
            static struct bridge_allocation_event bridge_events[256];
            static int bridge_event_count, bridge_phase;
            void bridge_test_phase(int phase) { bridge_phase = phase; }
            static void bridge_test_record(int kind, void *address, size_t size, const void *object_type) {
                const struct ironwood_type_info *type = object_type;
                if (bridge_event_count == 256) abort();
                bridge_events[bridge_event_count++] = (struct bridge_allocation_event){bridge_phase, kind,
                    (uintptr_t)address, size, type == NULL ? "<none>" : type->name};
            }
            void bridge_test_events(void) {
                for (int i = 0; i < bridge_event_count; i++) {
                    const struct bridge_allocation_event *e = &bridge_events[i];
                    printf("event:%d:%d:%" PRIuPTR ":%zu:%s\\n", e->phase, e->kind, e->address, e->size, e->type);
                }
            }
            """;
}
