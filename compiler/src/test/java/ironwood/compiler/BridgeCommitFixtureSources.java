// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

/** Private bounded JNI experiment, not a production facade or dynamic root index. */
final class BridgeCommitFixtureSources {
    private BridgeCommitFixtureSources() {}

    static final String CONSUMER = """
            public final class BridgeCommitConsumer {
                static final class Root {
                    long address, incoming;
                    int kind, status, record = -1;
                    Root first, second;
                    Root(int kind) { this.kind = kind; }
                }
                static final class BridgeLifetimeException extends IllegalStateException {
                    BridgeLifetimeException(String message) { super(message); }
                }
                static native int call(int operation, Root a, Root b, Root c, Root d, int number, boolean fail, int inject);
                static native void free(Root root);
                static native long metric(int index);
                static Root item(int number) {
                    Root root = new Root(0); call(0, root, null, null, null, number, false, 0); return root;
                }
                static Root holder(Root other, Root item, boolean fail, int inject) {
                    Root root = new Root(1); call(1, root, other, item, null, 0, fail, inject); return root;
                }
                static void mutate(int operation, Root a, Root b, Root c, Root d) {
                    call(operation, a, b, c, d, 0, false, 0);
                }
                static void check(boolean condition) { if (!condition) throw new AssertionError(); }
                static void expect(Class<? extends Throwable> type, Runnable action) {
                    try { action.run(); throw new AssertionError("missing " + type); }
                    catch (Throwable failure) { if (failure.getClass() != type) throw new AssertionError(failure); }
                }
                static void refused(Root root) {
                    long calls = metric(0), destructions = metric(1);
                    expect(BridgeLifetimeException.class, () -> free(root));
                    check(metric(0) == calls && metric(1) == destructions && root.status == 0);
                }
                public static void main(String[] args) {
                    System.load(args[0]);
                    Root a = item(11), b = item(29), h = holder(null, a, false, 0);
                    if (args.length > 1) {
                        expect(OutOfMemoryError.class, () -> mutate(5, h, b, null, null));
                        check(a.incoming == 0 && b.incoming == 1 && h.first == b); refused(b);
                        free(h); free(a); free(b);
                        check(metric(2) == 0 && metric(3) == 0 && metric(4) == 1);
                        System.out.println("host-commit-snapshot-fallback-ok"); return;
                    }
                    check(a.incoming == 1 && h.first == a && metric(2) == 3);
                    refused(a);
                    mutate(3, h, a, null, null);
                    check(a.incoming == 2 && h.first == a && h.second == a);
                    mutate(2, h, b, null, null);
                    check(a.incoming == 1 && b.incoming == 1 && h.first == b && h.second == a);
                    refused(a); refused(b);
                    mutate(6, h, h, a, b);
                    check(a.incoming == 1 && b.incoming == 1 && h.first == b);
                    mutate(3, h, b, null, null);
                    check(a.incoming == 0 && b.incoming == 2);
                    expect(NullPointerException.class, () -> mutate(5, h, a, null, null));
                    check(a.incoming == 1 && b.incoming == 1 && h.first == a); refused(a);
                    expect(IllegalStateException.class, () -> mutate(9, h, b, null, null));
                    check(a.incoming == 0 && b.incoming == 2); refused(b);
                    expect(StackOverflowError.class, () -> { mutate(2, h, a, null, null); throw new StackOverflowError(); });
                    check(a.incoming == 1 && b.incoming == 1); refused(a);
                    expect(OutOfMemoryError.class, () -> {
                        call(5, h, b, null, null, 0, false, 6);
                    });
                    check(a.incoming == 0 && b.incoming == 2); refused(b);
                    Root other = holder(null, null, false, 0);
                    long beforeAlias = metric(0);
                    a.incoming = Long.MAX_VALUE - 1;
                    expect(BridgeLifetimeException.class, () -> mutate(6, h, other, a, a));
                    check(metric(0) == beforeAlias && other.first == null && h.first == b);
                    // Aliased holder records describe one actual slot, so exactly one increment fits.
                    mutate(6, h, h, a, a);
                    check(a.incoming == Long.MAX_VALUE && b.incoming == 1 && h.first == a);
                    a.incoming = 1;
                    mutate(2, h, b, null, null);
                    mutate(6, h, other, a, a);
                    check(a.incoming == 2 && b.incoming == 1 && h.first == a && other.first == a);
                    free(other); check(a.incoming == 1 && other.first == null);
                    mutate(2, h, b, null, null);
                    long calls = metric(0), records = metric(2), globals = metric(3), live = metric(4);
                    for (int injection = 1; injection <= 4; injection++) {
                        final int failure = injection;
                        expect(OutOfMemoryError.class, () -> holder(h, a, false, failure));
                        check(metric(0) == calls && metric(2) == records && metric(3) == globals && metric(4) == live);
                        check(h.first == b && b.incoming == 2 && a.incoming == 0);
                    }
                    a.incoming = Long.MAX_VALUE - 1;
                    expect(BridgeLifetimeException.class, () -> mutate(3, h, a, null, null));
                    check(metric(0) == calls && h.first == b && a.incoming == Long.MAX_VALUE - 1);
                    a.incoming = 0;
                    expect(NullPointerException.class, () -> holder(h, a, true, 0));
                    check(metric(2) == records && metric(3) == globals && h.first == a);
                    check(a.incoming == 1 && b.incoming == 1); refused(a);
                    Root kept = new Root(1);
                    expect(OutOfMemoryError.class, () -> {
                        call(1, kept, null, a, null, 0, false, 0);
                        throw new OutOfMemoryError("facade materialization after native return");
                    });
                    check(kept.first == a && a.incoming == 2 && metric(2) == records + 1);
                    free(kept); check(a.incoming == 1 && kept.status == 2);
                    long allocations = metric(5), checksum = 0, start = System.nanoTime();
                    for (int i = 0; i < 20000; i++) {
                        mutate(2, h, (i & 1) == 0 ? a : b, null, null);
                        checksum += call(8, h, null, null, null, 0, false, 0);
                    }
                    long elapsed = System.nanoTime() - start;
                    check(checksum == 400000 && metric(5) == allocations && metric(6) == 0);
                    System.out.println("host-commit-benchmark:20000:400000:" + elapsed);
                    free(h); check(h.status == 2 && h.first == null && h.second == null);
                    check(a.incoming == 0 && b.incoming == 0);
                    long frees = metric(1); free(h); check(metric(1) == frees);
                    calls = metric(0);
                    expect(BridgeLifetimeException.class, () -> mutate(2, h, a, null, null));
                    check(metric(0) == calls);
                    free(a); free(b);
                    check(metric(2) == 0 && metric(3) == 0 && metric(1) == 5);
                    // Four native throwable snapshots remain owned by this private P0 error transport.
                    check(metric(4) == 4);
                    System.out.println("host-commit-ok");
                }
            }
            """;

    static final String HEADERS = """
            #include <jni.h>
            #include <stdint.h>
            #include <limits.h>
            #include <stdlib.h>
            #include <string.h>
            #include "ironwood_bridge.h"
            """;

    static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            struct frame { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[4]; };
            struct slot_layout { int holder, field; unsigned inputs; };
            struct layout { int count; struct slot_layout slots[4]; };
            static const struct layout layouts[] = { ${layouts} };
            static jclass root_class, lifetime_class, npe_class, ise_class, oom_class;
            static jfieldID address_id, incoming_id, kind_id, status_id, record_id, slot_ids[2];
            /* Bounded experiment: all index storage exists before any call. P3 needs dynamic preflight capacity. */
            static struct { void *address; jobject state; } index_records[64];
            static int64_t calls, destructions, records, globals, order_failures;
            struct root { jobject state; void *address; jlong count; int kind; };
            struct change { int holder, field, old, next; unsigned possible; };
            struct prepared { struct root roots[12]; int count; int input[4]; struct change changes[4]; int changes_count; int payload[4]; };
            #define ADDRESS(f) ((int64_t)(uintptr_t)&(f))
            static void raise(JNIEnv *env, jclass type, const char *message) { (*env)->ThrowNew(env, type, message); }
            static jclass anchor(JNIEnv *env, const char *name) {
                jclass local = (*env)->FindClass(env, name);
                if (local == NULL) return NULL;
                jclass result = (jclass)(*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local); return result;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
                (void)reserved; JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                root_class = anchor(env, "BridgeCommitConsumer$Root"); if (root_class == NULL) return JNI_ERR;
                lifetime_class = anchor(env, "BridgeCommitConsumer$BridgeLifetimeException"); if (lifetime_class == NULL) return JNI_ERR;
                npe_class = anchor(env, "java/lang/NullPointerException"); if (npe_class == NULL) return JNI_ERR;
                ise_class = anchor(env, "java/lang/IllegalStateException"); if (ise_class == NULL) return JNI_ERR;
                oom_class = anchor(env, "java/lang/OutOfMemoryError"); if (oom_class == NULL) return JNI_ERR;
                #define FIELD(target, name, signature) target = (*env)->GetFieldID(env, root_class, name, signature); if (target == NULL) return JNI_ERR
                FIELD(address_id, "address", "J"); FIELD(incoming_id, "incoming", "J");
                FIELD(kind_id, "kind", "I"); FIELD(status_id, "status", "I"); FIELD(record_id, "record", "I");
                FIELD(slot_ids[0], "first", "LBridgeCommitConsumer$Root;"); FIELD(slot_ids[1], "second", "LBridgeCommitConsumer$Root;");
                #undef FIELD
                ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;
            }
            static int add_root(JNIEnv *env, struct prepared *p, jobject state) {
                if (state == NULL) return -1;
                for (int i = 0; i < p->count; i++) if ((*env)->IsSameObject(env, state, p->roots[i].state)) return i;
                if ((*env)->GetIntField(env, state, status_id) != 0) {
                    raise(env, lifetime_class, "dead root"); return -2;
                }
                int i = p->count++;
                p->roots[i] = (struct root){state, (void *)(uintptr_t)(*env)->GetLongField(env, state, address_id),
                    (*env)->GetLongField(env, state, incoming_id), (*env)->GetIntField(env, state, kind_id)};
                return i;
            }
            static int prepare(JNIEnv *env, struct prepared *p, const struct layout *layout, jobject inputs[4]) {
                if ((*env)->EnsureLocalCapacity(env, 32) != JNI_OK) return 0;
                for (int i = 0; i < 4; i++) { p->input[i] = add_root(env, p, inputs[i]); if (p->input[i] == -2) return 0; }
                for (int i = 0; i < layout->count; i++) {
                    int holder = p->input[layout->slots[i].holder], field = layout->slots[i].field;
                    if (holder < 0) { p->payload[i] = -1; continue; }
                    int at = 0;
                    while (at < p->changes_count && (p->changes[at].holder != holder || p->changes[at].field != field)) at++;
                    if (at == p->changes_count) {
                        jobject old = (*env)->GetObjectField(env, p->roots[holder].state, slot_ids[field]);
                        int prior = add_root(env, p, old); if (prior == -2) return 0;
                        p->changes[p->changes_count++] = (struct change){holder, field, prior, prior, 0};
                    }
                    p->payload[i] = at;
                    for (int input = 0; input < 4; input++) if ((layout->slots[i].inputs & (1u << input)) && p->input[input] >= 0)
                        p->changes[at].possible |= 1u << p->input[input];
                }
                for (int root = 0; root < p->count; root++) {
                    int maximum = 0;
                    for (int slot = 0; slot < p->changes_count; slot++) maximum += (p->changes[slot].possible >> root) & 1u;
                    if (p->roots[root].count > INT64_MAX - maximum) {
                        raise(env, lifetime_class, "incoming count headroom"); return 0;
                    }
                }
                return 1;
            }
            /* Only a violated compiler origin contract can reach abort; no recoverable commit failure exists. */
            static int final_root(const struct prepared *p, void *address) {
                if (address == NULL) return -1;
                for (int i = 0; i < p->count; i++) if (p->roots[i].address == address) return i;
                abort();
            }
            /* This epilogue uses only prepared references/field IDs and bounded arithmetic. */
            __attribute__((noinline)) static void commit(JNIEnv *env, struct prepared *p, const struct layout *layout, const struct frame *f) {
                for (int i = 0; i < layout->count; i++) if (p->payload[i] >= 0 && f->slots[i].holder != NULL)
                    p->changes[p->payload[i]].next = final_root(p, f->slots[i].value);
                for (int i = 0; i < p->changes_count; i++) {
                    struct change *s = &p->changes[i];
                    if (s->next != s->old && s->next >= 0) {
                        struct root *r = &p->roots[s->next]; (*env)->SetLongField(env, r->state, incoming_id, ++r->count);
                    }
                }
                /* The private observation checks all increments exist before the first decrement. */
                for (int i = 0; i < p->count; i++) {
                    int needed = 0;
                    for (int j = 0; j < p->changes_count; j++) needed += p->changes[j].next == i;
                    if (p->roots[i].count < needed) order_failures++;
                }
                for (int i = 0; i < p->changes_count; i++) {
                    struct change *s = &p->changes[i];
                    if (s->next != s->old && s->old >= 0) {
                        struct root *r = &p->roots[s->old]; (*env)->SetLongField(env, r->state, incoming_id, --r->count);
                    }
                }
                for (int i = 0; i < p->changes_count; i++) {
                    struct change *s = &p->changes[i];
                    (*env)->SetObjectField(env, p->roots[s->holder].state, slot_ids[s->field],
                        s->next < 0 ? NULL : p->roots[s->next].state);
                }
            }
            JNIEXPORT jint JNICALL Java_BridgeCommitConsumer_call(JNIEnv *env, jclass type, jint op,
                    jobject a, jobject b, jobject c, jobject d, jint number, jboolean fail, jint inject) {
                (void)type;
                if (op < 0 || op >= 10 || a == NULL) { raise(env, ise_class, "private binding misuse"); return 0; }
                /* Scalar reads need neither root-index lookup nor retention preparation. */
                if (op == 8) {
                    if ((*env)->GetIntField(env, a, status_id) != 0) { raise(env, lifetime_class, "dead root"); return 0; }
                    void *address = (void *)(uintptr_t)(*env)->GetLongField(env, a, address_id);
                    struct frame f = {0}; calls++;
                    int status = call_value(address, ADDRESS(f));
                    if (status != 0) raise(env, npe_class, "native failure");
                    return f.result.value.integer;
                }
                if (inject == 1) { raise(env, oom_class, "local capacity preparation"); return 0; }
                struct prepared p = {0}; jobject inputs[4] = {a,b,c,d};
                if (!prepare(env, &p, &layouts[op], inputs)) return 0;
                int reserved = -1; jobject global = NULL;
                if (op <= 1) {
                    if (inject == 2) { raise(env, oom_class, "registration storage preparation"); return 0; }
                    for (int i = 0; i < 64; i++) if (index_records[i].state == NULL) { reserved = i; break; }
                    if (reserved < 0 || inject == 3) { raise(env, oom_class, "index capacity preparation"); return 0; }
                    if (inject != 4) global = (*env)->NewGlobalRef(env, a);
                    if (global == NULL) {
                        if (!(*env)->ExceptionCheck(env)) raise(env, oom_class, "global reference preparation");
                        return 0;
                    }
                    globals++;
                }
                void *addresses[4];
                for (int i = 0; i < 4; i++) addresses[i] = p.input[i] < 0 ? NULL : p.roots[p.input[i]].address;
                struct frame f = {0}; int status; calls++;
                switch (op) {
                    case 0: status = call_newItem(number, ADDRESS(f)); break;
                    case 1: status = call_newHolder(addresses[1], addresses[2], fail, ADDRESS(f)); break;
                    case 2: status = call_set(addresses[0], addresses[1], ADDRESS(f)); break;
                    case 3: status = call_two(addresses[0], addresses[1], ADDRESS(f)); break;
                    case 4: status = call_clear(addresses[0], ADDRESS(f)); break;
                    case 5: status = call_setThenFail(addresses[0], addresses[1], ADDRESS(f)); break;
                    case 6: status = call_both(addresses[0], addresses[1], addresses[2], addresses[3], ADDRESS(f)); break;
                    case 7: status = call_other(addresses[0], addresses[1], addresses[2], ADDRESS(f)); break;
                    case 9: status = call_producerFailure(addresses[0], addresses[1], ADDRESS(f)); break;
                    default: abort();
                }
                if (reserved >= 0 && status == 0) {
                    index_records[reserved].address = f.result.value.reference; index_records[reserved].state = global; records++;
                    p.roots[p.input[0]].address = f.result.value.reference;
                    (*env)->SetLongField(env, a, address_id, (jlong)(uintptr_t)f.result.value.reference);
                    (*env)->SetIntField(env, a, record_id, reserved);
                }
                commit(env, &p, &layouts[op], &f);
                if (reserved >= 0 && status != 0) { (*env)->DeleteGlobalRef(env, global); globals--; }
                if (status != 0) raise(env, inject == 6 || status == 2 ||
                    strstr(f.result.failure.type_name, "OutOfMemoryError") != NULL ? oom_class :
                    strstr(f.result.failure.type_name, "IllegalStateException") != NULL ? ise_class : npe_class, "native failure after commit");
                return f.result.value.integer;
            }
            JNIEXPORT void JNICALL Java_BridgeCommitConsumer_free(JNIEnv *env, jclass type, jobject state) {
                (void)type;
                if ((*env)->GetIntField(env, state, status_id) == 2) return;
                if ((*env)->GetIntField(env, state, status_id) != 0 || (*env)->GetLongField(env, state, incoming_id) != 0) {
                    raise(env, lifetime_class, "root remains retained"); return;
                }
                struct prepared p = {0}; jobject inputs[4] = {state,NULL,NULL,NULL};
                int kind = (*env)->GetIntField(env, state, kind_id);
                const struct layout empty = {0, {{0,0,0}}};
                const struct layout *layout = kind == 1 ? &layouts[4] : &empty;
                if (!prepare(env, &p, layout, inputs)) return;
                int at = (*env)->GetIntField(env, state, record_id);
                (*env)->SetIntField(env, state, status_id, 1);
                calls++; if (kind == 1) freeHolder(p.roots[0].address); else freeItem(p.roots[0].address);
                destructions++;
                struct frame f = {0};
                for (int i = 0; i < layout->count; i++) f.slots[i].holder = p.roots[0].address;
                commit(env, &p, layout, &f);
                (*env)->SetIntField(env, state, status_id, 2);
                (*env)->SetLongField(env, state, address_id, 0);
                (*env)->SetIntField(env, state, record_id, -1);
                jobject global = index_records[at].state; index_records[at].state = NULL; index_records[at].address = NULL;
                records--; globals--; (*env)->DeleteGlobalRef(env, global);
            }
            JNIEXPORT jlong JNICALL Java_BridgeCommitConsumer_metric(JNIEnv *env, jclass type, jint metric) {
                (void)env; (void)type;
                switch (metric) {
                    case 0: return calls; case 1: return destructions; case 2: return records; case 3: return globals;
                    case 4: return ironwood_live_allocation_count(); case 5: return ironwood_allocation_count(); case 6: return order_failures;
                    default: return -1;
                }
            }
            """;
}
