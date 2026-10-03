// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

/** Bounded result surface with real dynamic native reservations and a weak Java facade cache. */
final class BridgeIdentityFixtureSources {
    private BridgeIdentityFixtureSources() {}

    static final String CONSUMER = """
            import java.lang.ref.WeakReference;
            public final class BridgeIdentityConsumer {
                static final class Root {
                    long address;
                    int status;
                    WeakReference<Node> cached;
                    WeakReference<Leaf> child;
                }
                static final class Node {
                    final Root root;
                    Node(Root root) { this.root = root; }
                    Node alias() { return wrap(aliasRoot(root, false), 0); }
                    Leaf view() { return leaf(root, childAddress(root), false, false); }
                    void free() { freeRoot(root); }
                }
                static final class Leaf {
                    final Root root;
                    final long address;
                    final boolean owning;
                    Leaf(Root root, long address, boolean owning) { this.root = root; this.address = address; this.owning = owning; }
                    Leaf self() { return leaf(root, childAlias(root, address), owning, false); }
                    int value() { return childValue(root, address); }
                    void free() {
                        if (!owning) throw new BridgeLifetimeException("borrowed child");
                        freeRoot(root);
                    }
                }
                static final class BridgeLifetimeException extends IllegalStateException {
                    BridgeLifetimeException(String message) { super(message); }
                }
                static native Root freshRoot(Root reserved, boolean absent, boolean nativeFailure, int fault, int kind);
                static native Root aliasRoot(Root root, boolean absent);
                static native Root baselineAlias(Root root);
                static native Root recallRoot();
                static native long childAddress(Root owner);
                static native long childAlias(Root owner, long address);
                static native int childValue(Root owner, long address);
                static native int scalarValue(Root owner, long address);
                static native int baselineValue(Root owner, long address);
                static native void freeRoot(Root root);
                static native long metric(int what);
                static Leaf leaf(Root root, long address, boolean owning, boolean fail) {
                    if (address == 0) return null;
                    Leaf existing = root.child == null ? null : root.child.get();
                    if (existing != null) { check(existing.address == address); return existing; }
                    Leaf created = new Leaf(root, address, owning);
                    if (fail) throw new OutOfMemoryError("borrowed facade cache insertion");
                    root.child = new WeakReference<>(created); return created;
                }
                static Node wrap(Root root, int fail) {
                    if (root == null) return null;
                    Node existing = root.cached == null ? null : root.cached.get();
                    if (existing != null) return existing;
                    if (fail == 1) throw new OutOfMemoryError("facade allocation");
                    Node created = new Node(root);
                    if (fail == 2) throw new OutOfMemoryError("weak cache insertion");
                    root.cached = new WeakReference<>(created); return created;
                }
                static Root fresh(boolean absent, boolean nativeFailure, int fault) {
                    if (fault == 1) throw new OutOfMemoryError("Java root-state preparation");
                    return freshRoot(new Root(), absent, nativeFailure, fault, 0);
                }
                static void check(boolean value) { if (!value) throw new AssertionError(); }
                static void expect(Class<? extends Throwable> type, Runnable body) {
                    try { body.run(); throw new AssertionError("missing " + type); }
                    catch (Throwable failure) { if (failure.getClass() != type) throw new AssertionError(failure); }
                }
                record Probe(WeakReference<Node> facade, WeakReference<Root> root) {}
                static Probe makeCollectible() {
                    Node node = wrap(fresh(false, false, 0), 0);
                    return new Probe(new WeakReference<>(node), new WeakReference<>(node.root));
                }
                static void collection() throws Exception {
                    Probe probe = makeCollectible();
                    long calls = metric(0), destructions = metric(1);
                    for (int attempt = 0; attempt < 200 && probe.facade().get() != null; attempt++) {
                        System.gc(); Thread.sleep(10);
                    }
                    check(probe.facade().get() == null && probe.root().get() != null);
                    check(metric(0) == calls && metric(1) == destructions);
                    Node again = wrap(recallRoot(), 0);
                    check(again.root == probe.root().get() && again.alias() == again); again.free();
                }
                static WeakReference<Leaf> childForCollection(Node owner) { return new WeakReference<>(owner.view()); }
                static void scalarBenchmark(Leaf child) {
                    var bean = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
                    bean.setThreadAllocatedMemoryEnabled(true); long thread = Thread.currentThread().threadId();
                    for (int i = 0; i < 20000; i++) {
                        check(scalarValue(child.root, child.address) == 0 && baselineValue(child.root, child.address) == 0);
                    }
                    long nativeBefore = metric(6), before = bean.getThreadAllocatedBytes(thread), sum = 0, start = System.nanoTime();
                    for (int i = 0; i < 50000; i++) sum += scalarValue(child.root, child.address);
                    long bridge = System.nanoTime() - start; start = System.nanoTime();
                    for (int i = 0; i < 50000; i++) sum += baselineValue(child.root, child.address);
                    long baseline = System.nanoTime() - start;
                    check(sum == 0 && metric(6) == nativeBefore && bean.getThreadAllocatedBytes(thread) == before);
                    System.out.println("reclaimable-scalar:50000:0:0:" + bridge + ":" + baseline);
                }
                static void views() throws Exception {
                    Node owner = wrap(fresh(false, false, 0), 0); long roots = metric(2);
                    expect(OutOfMemoryError.class, () -> leaf(owner.root, childAddress(owner.root), false, true));
                    check(metric(2) == roots && metric(3) == roots && metric(4) == roots);
                    WeakReference<Leaf> weak = childForCollection(owner);
                    long calls = metric(0), destroyed = metric(1);
                    for (int attempt = 0; attempt < 200 && weak.get() != null; attempt++) { System.gc(); Thread.sleep(10); }
                    check(weak.get() == null && metric(0) == calls && metric(1) == destroyed);
                    Leaf child = owner.view(); check(child == owner.view() && child.self() == child && child.value() == 0);
                    scalarBenchmark(child);
                    calls = metric(0); expect(BridgeLifetimeException.class, child::free);
                    check(metric(0) == calls && metric(1) == destroyed && owner.root.status == 0);
                    owner.free(); calls = metric(0);
                    expect(BridgeLifetimeException.class, child::value); expect(BridgeLifetimeException.class, child::self);
                    expect(BridgeLifetimeException.class, child::free); check(metric(0) == calls);
                    expect(BridgeLifetimeException.class, () -> scalarValue(child.root, child.address));
                    expect(BridgeLifetimeException.class, () -> baselineValue(child.root, child.address));
                    Root independent = freshRoot(new Root(), false, false, 0, 1);
                    Leaf owned = leaf(independent, independent.address, true, false);
                    check(owned.self() == owned && owned.value() == 0); owned.free(); calls = metric(0); owned.free();
                    check(metric(0) == calls && independent.status == 2);
                    expect(BridgeLifetimeException.class, owned::value); check(metric(0) == calls);
                }
                public static void main(String[] args) throws Exception {
                    System.load(args[0]);
                    if (args.length > 1) {
                        expect(OutOfMemoryError.class, () -> fresh(false, false, 0));
                        check(metric(0) == 1 && metric(2) == 0 && metric(3) == 0 && metric(4) == 0 && metric(5) == 0);
                        System.out.println("identity-native-oom-ok"); return;
                    }
                    for (int failure = 1; failure <= 5; failure++) {
                        final int injection = failure; long calls = metric(0);
                        expect(OutOfMemoryError.class, () -> fresh(false, false, injection));
                        check(metric(0) == calls && metric(2) == 0 && metric(3) == 0 && metric(4) == 0 && metric(5) == 0);
                    }
                    long allocations = metric(6);
                    check(fresh(true, false, 0) == null && aliasRoot(null, false) == null);
                    check(metric(2) == 0 && metric(3) == 0 && metric(4) == 0 && metric(6) == allocations);
                    Node node = wrap(fresh(false, false, 0), 0);
                    check(node.alias() == node && wrap(recallRoot(), 0) == node && aliasRoot(node.root, true) == null);
                    check(metric(2) == 1 && metric(3) == 1 && metric(4) == 1);
                    var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                    check(bean.isThreadAllocatedMemorySupported()); bean.setThreadAllocatedMemoryEnabled(true);
                    long thread = Thread.currentThread().threadId();
                    for (int i = 0; i < 20000; i++) { check(node.alias() == node); check(wrap(baselineAlias(node.root), 0) == node); }
                    long javaBefore = bean.getThreadAllocatedBytes(thread), nativeBefore = metric(6), start = System.nanoTime();
                    for (int i = 0; i < 50000; i++) check(node.alias() == node);
                    long bridgeNanos = System.nanoTime() - start; start = System.nanoTime();
                    for (int i = 0; i < 50000; i++) check(wrap(baselineAlias(node.root), 0) == node);
                    long baselineNanos = System.nanoTime() - start;
                    check(bean.getThreadAllocatedBytes(thread) == javaBefore && metric(6) == nativeBefore);
                    System.out.println("root-identity-benchmark:50000:0:0:" + bridgeNanos + ":" + baselineNanos);
                    int identityHash = node.hashCode(); String identityText = node.toString();
                    var identitySet = new java.util.HashSet<Node>(); identitySet.add(node);
                    node.free(); long destroyed = metric(1), calls = metric(0); node.free();
                    check(metric(1) == destroyed && metric(0) == calls);
                    check(node.equals(node) && node.hashCode() == identityHash && node.toString().equals(identityText) && identitySet.remove(node));
                    var logged = new java.util.concurrent.atomic.AtomicReference<String>();
                    Thread logger = Thread.ofPlatform().start(() -> logged.set(node.toString())); logger.join();
                    check(identityText.equals(logged.get()) && metric(0) == calls);
                    expect(BridgeLifetimeException.class, node::alias); check(metric(0) == calls);
                    expect(NullPointerException.class, () -> fresh(false, true, 0));
                    check(metric(2) == 0 && metric(3) == 0 && metric(4) == 0 && metric(5) == 1);
                    for (int failure = 1; failure <= 2; failure++) {
                        final int delivery = failure;
                        expect(OutOfMemoryError.class, () -> wrap(fresh(false, false, 0), delivery));
                        check(metric(2) == 1 && metric(3) == 1 && metric(4) == 1);
                        Root existing = recallRoot(); Node recovered = wrap(existing, 0);
                        check(recovered.root == existing && wrap(recallRoot(), 0) == recovered);
                        recovered.free();
                    }
                    collection();
                    views();
                    // Keep old facades alive while requesting allocator reuse; use real native addresses.
                    java.util.HashMap<Long, Node> dead = new java.util.HashMap<>();
                    boolean forced = "1".equals(System.getenv("IRONWOOD_BRIDGE_REUSE_ALLOCATOR"));
                    long priorReuse = metric(9);
                    boolean reused = false;
                    for (int attempt = 0; attempt < (forced ? 2 : 4096) && !reused; attempt++) {
                        Node current = wrap(fresh(false, false, 0), 0); long address = current.root.address;
                        Node old = dead.get(address);
                        if (old != null) {
                            check(old != current && old.root != current.root && old.root.status == 2);
                            check(!old.equals(current));
                            long before = metric(0); old.free(); check(metric(0) == before);
                            expect(BridgeLifetimeException.class, old::alias); check(metric(0) == before);
                            check(current.alias() == current); reused = true;
                        }
                        current.free(); dead.put(address, current);
                    }
                    check(reused);
                    if (forced) check(metric(9) > priorReuse);
                    // Growth occurs during preparation; all published roots survive table replacement.
                    Node[] many = new Node[40];
                    for (int i = 0; i < many.length; i++) {
                        if (metric(2) + 1 >= metric(7) / 2) {
                            long before = metric(0), roots = metric(2), size = metric(7);
                            expect(OutOfMemoryError.class, () -> fresh(false, false, 3));
                            check(metric(0) == before && metric(7) == size && metric(2) == roots && metric(3) == roots && metric(4) == roots);
                            // Successful growth followed by global-reference refusal must also preserve old records.
                            expect(OutOfMemoryError.class, () -> fresh(false, false, 4));
                            check(metric(0) == before && metric(7) > size && metric(2) == roots && metric(3) == roots && metric(4) == roots);
                            for (int prior = 0; prior < i; prior++) check(many[prior].alias() == many[prior]);
                        }
                        many[i] = wrap(fresh(false, false, 0), 0);
                    }
                    check(metric(2) == 40 && metric(3) == 40 && metric(4) == 40 && metric(7) >= 64);
                    for (Node current : many) { check(current.alias() == current); current.free(); }
                    check(metric(2) == 0 && metric(3) == 0 && metric(4) == 0 && metric(5) == 1);
                    check(metric(1) == metric(8));
                    System.out.println("root-identity-ok:reuse:collection:reservation:growth");
                }
            }
            """;

    static final String ADAPTER = """
            extern void ironwood_bridge_bootstrap(void);
            struct record { void *address; jobject state; int kind; };
            static struct record **table;
            static size_t capacity, occupied;
            static int64_t calls, destroyed, globals, reservations, published;
            static void *last_address;
            #define TOMBSTONE ((struct record *)(uintptr_t)1)
            static jclass root_class, lifetime_class, oom_class, npe_class;
            static jfieldID address_id, status_id;
            static void raise(JNIEnv *env, jclass type, const char *message) { (*env)->ThrowNew(env, type, message); }
            static jclass anchor(JNIEnv *env, const char *name) {
                jclass local = (*env)->FindClass(env, name); if (local == NULL) return NULL;
                jclass global = (jclass)(*env)->NewGlobalRef(env, local); (*env)->DeleteLocalRef(env, local); return global;
            }
            JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *unused) {
                (void)unused; JNIEnv *env = NULL;
                if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) return JNI_ERR;
                root_class = anchor(env, "BridgeIdentityConsumer$Root"); if (root_class == NULL) return JNI_ERR;
                lifetime_class = anchor(env, "BridgeIdentityConsumer$BridgeLifetimeException"); if (lifetime_class == NULL) return JNI_ERR;
                oom_class = anchor(env, "java/lang/OutOfMemoryError"); if (oom_class == NULL) return JNI_ERR;
                npe_class = anchor(env, "java/lang/NullPointerException"); if (npe_class == NULL) return JNI_ERR;
                address_id = (*env)->GetFieldID(env, root_class, "address", "J"); if (address_id == NULL) return JNI_ERR;
                status_id = (*env)->GetFieldID(env, root_class, "status", "I"); if (status_id == NULL) return JNI_ERR;
                ironwood_bridge_bootstrap(); return JNI_VERSION_1_8;
            }
            static size_t bucket(void *address, size_t size) {
                uintptr_t value = (uintptr_t)address >> 3;
                value ^= value >> 29; value *= UINT64_C(0x9e3779b97f4a7c15); value ^= value >> 32;
                return (size_t)value & (size - 1);
            }
            static void insert(struct record **into, size_t size, struct record *record) {
                size_t at = bucket(record->address, size);
                while (into[at] != NULL && into[at] != TOMBSTONE) at = (at + 1) & (size - 1);
                into[at] = record;
            }
            static struct record **lookup(void *address) {
                if (address == NULL || capacity == 0) return NULL;
                size_t at = bucket(address, capacity);
                for (size_t inspected = 0; inspected < capacity; inspected++) {
                    if (table[at] == NULL) return NULL;
                    if (table[at] != TOMBSTONE && table[at]->address == address) return &table[at];
                    at = (at + 1) & (capacity - 1);
                }
                return NULL;
            }
            static int prepare_capacity(int fault) {
                if (capacity != 0 && occupied + 1 < capacity / 2) return 1;
                if (capacity > SIZE_MAX / (2 * sizeof(*table))) return 0;
                size_t next = capacity == 0 ? 8 : capacity * 2;
                struct record **replacement = fault == 3 ? NULL : calloc(next, sizeof(*table));
                if (replacement == NULL) return 0;
                for (size_t i = 0; i < capacity; i++) if (table[i] != NULL && table[i] != TOMBSTONE) insert(replacement, next, table[i]);
                free(table); table = replacement; capacity = next; return 1;
            }
            /* No growth, allocation, lookup or Java call after the native result is known. */
            __attribute__((noinline)) static void publish(JNIEnv *env, struct record *record, void *address) {
                record->address = address; insert(table, capacity, record); occupied++; published++;
                (*env)->SetLongField(env, record->state, address_id, (jlong)(uintptr_t)address);
                last_address = address;
            }
            static void discard(JNIEnv *env, struct record *record) {
                if (record->state != NULL) { (*env)->DeleteGlobalRef(env, record->state); globals--; }
                free(record); reservations--;
            }
            static void failed(JNIEnv *env, int status, struct ironwood_bridge_result *frame) {
                raise(env, status == 2 || strstr(frame->failure.type_name, "OutOfMemoryError") != NULL ? oom_class : npe_class,
                    "protected native failure");
            }
            static int live_owner(JNIEnv *env, jobject state) {
                if ((*env)->GetIntField(env, state, status_id) == 0) return 1;
                raise(env, lifetime_class, "dead view owner"); return 0;
            }
            JNIEXPORT jlong JNICALL Java_BridgeIdentityConsumer_childAddress(JNIEnv *env, jclass type, jobject owner) {
                (void)type; if (!live_owner(env, owner)) return 0;
                void *address = (void *)(uintptr_t)(*env)->GetLongField(env, owner, address_id);
                struct ironwood_bridge_result frame; calls++;
                int status = call_view(address, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return 0; }
                return (jlong)(uintptr_t)frame.value.reference;
            }
            JNIEXPORT jlong JNICALL Java_BridgeIdentityConsumer_childAlias(JNIEnv *env, jclass type, jobject owner, jlong address) {
                (void)type; if (!live_owner(env, owner)) return 0;
                struct ironwood_bridge_result frame; calls++;
                int status = call_self((void *)(uintptr_t)address, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return 0; }
                return (jlong)(uintptr_t)frame.value.reference;
            }
            JNIEXPORT jint JNICALL Java_BridgeIdentityConsumer_childValue(JNIEnv *env, jclass type, jobject owner, jlong address) {
                (void)type; if (!live_owner(env, owner)) return 0;
                struct ironwood_bridge_result frame; calls++;
                int status = call_value((void *)(uintptr_t)address, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return 0; }
                return frame.value.integer;
            }
            /* Uninstrumented scalar path; childValue separately counts native access for safety tests. */
            JNIEXPORT jint JNICALL Java_BridgeIdentityConsumer_scalarValue(JNIEnv *env, jclass type, jobject owner, jlong address) {
                (void)type; if (!live_owner(env, owner)) return 0;
                struct ironwood_bridge_result frame;
                int status = call_value((void *)(uintptr_t)address, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return 0; }
                return frame.value.integer;
            }
            /* Handwritten JNI comparison keeps the same protected native getter and required owner check. */
            JNIEXPORT jint JNICALL Java_BridgeIdentityConsumer_baselineValue(JNIEnv *env, jclass type, jobject owner, jlong address) {
                (void)type;
                if ((*env)->GetIntField(env, owner, status_id) != 0) { raise(env, lifetime_class, "dead view owner"); return 0; }
                struct ironwood_bridge_result frame;
                int status = call_value((void *)(uintptr_t)address, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return 0; }
                return frame.value.integer;
            }
            JNIEXPORT jobject JNICALL Java_BridgeIdentityConsumer_freshRoot(JNIEnv *env, jclass type, jobject state,
                    jboolean absent, jboolean native_failure, jint fault, jint kind) {
                (void)type;
                if (fault == 5) { raise(env, oom_class, "local capacity preparation"); return NULL; }
                if ((*env)->EnsureLocalCapacity(env, 8) != JNI_OK) return NULL;
                struct record *record = fault == 2 ? NULL : malloc(sizeof(*record));
                if (record == NULL) { raise(env, oom_class, "native record allocation"); return NULL; }
                reservations++; *record = (struct record){.kind = kind};
                if (!prepare_capacity(fault)) { discard(env, record); raise(env, oom_class, "native index growth"); return NULL; }
                record->state = fault == 4 ? NULL : (*env)->NewGlobalRef(env, state);
                if (record->state == NULL) {
                    discard(env, record); if (!(*env)->ExceptionCheck(env)) raise(env, oom_class, "global reference preparation"); return NULL;
                }
                globals++;
                struct ironwood_bridge_result frame; frame.value.reference = (void *)(uintptr_t)0xdead;
                calls++;
                int status = kind == 1 ? call_newLeaf((int64_t)(uintptr_t)&frame)
                    : native_failure ? call_freshFail((int64_t)(uintptr_t)&frame)
                    : call_fresh(absent, (int64_t)(uintptr_t)&frame);
                if (status == 0 && frame.value.reference != NULL) {
                    publish(env, record, frame.value.reference); return state;
                }
                /* Failure entries must overwrite a poisoned result before exception extraction. */
                if (status != 0 && frame.value.reference != NULL) abort();
                discard(env, record);
                if (status != 0) failed(env, status, &frame);
                return NULL;
            }
            static jobject expose(JNIEnv *env, struct ironwood_bridge_result *frame, int status) {
                if (status != 0) { failed(env, status, frame); return NULL; }
                if (frame->value.reference == NULL) return NULL;
                struct record **found = lookup(frame->value.reference);
                if (found == NULL) abort(); /* A proved root alias cannot create an unregistered identity. */
                return (*found)->state;
            }
            JNIEXPORT jobject JNICALL Java_BridgeIdentityConsumer_aliasRoot(JNIEnv *env, jclass type, jobject state, jboolean absent) {
                (void)type;
                if (state != NULL && (*env)->GetIntField(env, state, status_id) != 0) {
                    raise(env, lifetime_class, "dead root"); return NULL;
                }
                void *address = state == NULL ? NULL : (void *)(uintptr_t)(*env)->GetLongField(env, state, address_id);
                struct ironwood_bridge_result frame; calls++;
                int status = call_argument(address, absent, (int64_t)(uintptr_t)&frame);
                return expose(env, &frame, status);
            }
            JNIEXPORT jobject JNICALL Java_BridgeIdentityConsumer_recallRoot(JNIEnv *env, jclass type) {
                (void)type;
                struct record **found = lookup(last_address);
                if (found == NULL) { raise(env, lifetime_class, "no live recalled root"); return NULL; }
                struct ironwood_bridge_result frame; calls++;
                int status = call_alias((*found)->address, 0, (int64_t)(uintptr_t)&frame);
                return expose(env, &frame, status);
            }
            /* Matched handwritten JNI: same checks/typed call/cache; one known alias input needs no index lookup. */
            JNIEXPORT jobject JNICALL Java_BridgeIdentityConsumer_baselineAlias(JNIEnv *env, jclass type, jobject state) {
                (void)type;
                if ((*env)->GetIntField(env, state, status_id) != 0) { raise(env, lifetime_class, "dead root"); return NULL; }
                void *address = (void *)(uintptr_t)(*env)->GetLongField(env, state, address_id);
                struct ironwood_bridge_result frame; calls++;
                int status = call_argument(address, 0, (int64_t)(uintptr_t)&frame);
                if (status != 0) { failed(env, status, &frame); return NULL; }
                return frame.value.reference == NULL ? NULL : state;
            }
            JNIEXPORT void JNICALL Java_BridgeIdentityConsumer_freeRoot(JNIEnv *env, jclass type, jobject state) {
                (void)type;
                if ((*env)->GetIntField(env, state, status_id) == 2) return;
                void *address = (void *)(uintptr_t)(*env)->GetLongField(env, state, address_id);
                struct record **found = lookup(address);
                if (found == NULL) { raise(env, lifetime_class, "unregistered root"); return; }
                struct record *record = *found;
                (*env)->SetIntField(env, state, status_id, 1);
                calls++; if (record->kind == 1) freeLeaf(address); else freeNode(address); destroyed++;
                (*env)->SetIntField(env, state, status_id, 2);
                *found = TOMBSTONE; occupied--; discard(env, record);
            }
            JNIEXPORT jlong JNICALL Java_BridgeIdentityConsumer_metric(JNIEnv *env, jclass type, jint what) {
                (void)env; (void)type;
                switch (what) {
                    case 0: return calls; case 1: return destroyed; case 2: return (jlong)occupied; case 3: return globals;
                    case 4: return reservations; case 5: return ironwood_live_allocation_count();
                    case 6: return ironwood_allocation_count(); case 7: return (jlong)capacity; case 8: return published;
                    case 9: return REUSE_COUNT(); default: return -1;
                }
            }
            """;
}
