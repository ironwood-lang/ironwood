// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeOwnedCallbackAdmission;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** JNI owner adapters bound to exact facade guards, fixed slots and native entry proofs. */
public final class BridgeOwnedCallbackNativeSources {
    private final String source;
    private final BridgeJavaSources declarations;
    private final String generation;
    private final Map<BridgeJavaSources.NativeDeclaration, String> functions;

    private BridgeOwnedCallbackNativeSources(String source, BridgeJavaSources declarations, BridgeGeneration generation,
            Map<BridgeJavaSources.NativeDeclaration, String> functions) {
        this.source = source; this.declarations = declarations; this.generation = generation.identity(); this.functions = Map.copyOf(functions);
    }
    public String source() { return source; }
    public Map<BridgeJavaSources.NativeDeclaration, String> functions() { return functions; }
    public boolean matches(BridgeJavaSources java, BridgeGeneration identity) { return declarations.equals(java) && generation.equals(identity.identity()); }

    public static BridgeOwnedCallbackNativeSources generate(BridgeOwnedCallbackAdmission admission,
            BridgeGeneration generation, BridgeOwnedCallbackJavaSources.Sources java) {
        if (!generation.matchesOwnedCallbacks(admission) || !java.equals(BridgeOwnedCallbackJavaSources.generate(admission, generation))) {
            throw new IllegalArgumentException("owner adapters require exact admitted facade declarations and guard partition");
        }
        var artifact = admission.artifact(); var exceptions = admission.exceptions();
        var callbacks = BridgeCallbackNativeSources.generateOwned(admission, java);
        var listeners = BridgeListenerNativeSources.generateAll(artifact, admission.proxies());
        var index = BridgeRootIndexSources.generateOwnedCallbacks(admission, generation);
        var text = new StringBuilder(BridgeExceptionNativeSources.generate(artifact, exceptions.projection(), exceptions.entries(), admission.carriers()))
                .append("\nstatic struct iw_exception_metadata iw_exceptions;\n")
                .append(BridgeCallbackCarrierNativeSources.generate(artifact, admission.carriers()))
                .append(BridgeCallbackCarrierNativeSources.cleanup(artifact, admission.carriers(),
                        BridgeRootSet.resolve(artifact.program().orElseThrow(), admission.callbacks().entries().stream().map(BridgeOwnedCallbackEntries.Entry::callable).toList()), admission.cleanup()))
                .append("__attribute__((unused)) static jobject iw_owned_wrap(JNIEnv *, int, void *);\n")
                .append(callbacks.source()).append(listeners.source()).append(BridgeListenerNativeSources.ownersAll(admission)).append(index.source());
        metadata(text, generation, java, callbacks);
        text.append(FAILURE);
        var functions = new LinkedHashMap<BridgeJavaSources.NativeDeclaration, String>();
        for (var call : java.calls()) {
            String function = "iw_owned_" + functions.size();
            var binding = call.binding();
            functions.put(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function);
            emit(text, admission, java, listeners, index, call, function);
        }
        for (var destroy : java.declarations().rootDestructions()) {
            String function = "iw_owned_destroy_" + functions.size();
            functions.put(new BridgeJavaSources.NativeDeclaration(destroy.binaryName(), destroy.nativeName(), destroy.descriptor()), function);
            text.append("static void ").append(function).append("(JNIEnv *env, jclass type, jobject state, jlong address) {\n")
                    .append("    (void)type; struct iw_root_record **record = iw_root_resolve(env, state, (void *)(uintptr_t)address);\n")
                    .append("    if (record != NULL) iw_root_destroy(env, record);\n}\n");
        }
        return new BridgeOwnedCallbackNativeSources(text.toString(), java.declarations(), generation, functions);
    }

    private static void metadata(StringBuilder text, BridgeGeneration generation, BridgeOwnedCallbackJavaSources.Sources java,
            BridgeCallbackNativeSources.Sources callbacks) {
        int count = java.facades().size();
        text.append("static jclass iw_owned_types[").append(count).append("], iw_owned_refusal;\n")
                .append("static jmethodID iw_owned_constructors[").append(count).append("], iw_owned_lookup, iw_owned_remember;\n")
                .append("static jfieldID iw_owned_addresses[").append(count).append("], iw_owned_states[").append(count).append("], iw_owned_active;\n")
                .append("static const char *const iw_owned_address_names[] = {").append(java.facades().stream()
                        .map(facade -> BridgeJavaSources.quote(facade.addressField())).collect(Collectors.joining(", "))).append("};\n")
                .append("static const char *const iw_owned_state_names[] = {").append(java.facades().stream()
                        .map(facade -> BridgeJavaSources.quote(facade.stateField())).collect(Collectors.joining(", "))).append("};\n")
                .append("static void iw_owned_metadata_dispose(JNIEnv *env) {\n")
                .append("    for (int i = 0; i < ").append(count).append("; i++) { if (iw_owned_types[i] != NULL) (*env)->DeleteGlobalRef(env, iw_owned_types[i]);\n")
                .append("        iw_owned_types[i] = NULL; iw_owned_addresses[i] = NULL; iw_owned_states[i] = NULL; iw_owned_constructors[i] = NULL; }\n")
                .append("    if (iw_owned_refusal != NULL) (*env)->DeleteGlobalRef(env, iw_owned_refusal);\n")
                .append("    iw_owned_refusal = NULL; iw_owned_active = NULL; iw_owned_lookup = NULL; iw_owned_remember = NULL; iw_root_metadata_dispose(env);\n}\n")
                .append("static int iw_owned_metadata_init(JNIEnv *env, jclass *classes) {\n");
        int state = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".RootState");
        int refusal = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".BridgeLifetimeException");
        if (state < 0 || refusal < 0 || count == 0) throw new IllegalArgumentException("owner support is absent from validated inventory");
        text.append("    if (!iw_root_metadata_init(env, classes[").append(state).append("])) return 0;\n")
                .append("    iw_owned_lookup = (*env)->GetMethodID(env, classes[").append(state).append("], \"lookup\", \"(J)Ljava/lang/Object;\");\n")
                .append("    if (iw_owned_lookup == NULL) goto failed;\n")
                .append("    iw_owned_remember = (*env)->GetMethodID(env, classes[").append(state).append("], \"remember\", \"(JLjava/lang/Object;)Ljava/lang/Object;\");\n")
                .append("    if (iw_owned_remember == NULL) goto failed;\n")
                .append("    iw_owned_active = (*env)->GetFieldID(env, classes[").append(state).append("], \"activeUses\", \"J\");\n")
                .append("    if (iw_owned_active == NULL) goto failed;\n")
                .append("    iw_owned_refusal = (*env)->NewGlobalRef(env, classes[").append(refusal).append("]);\n")
                .append("    if (iw_owned_refusal == NULL) goto failed;\n");
        for (int i = 0; i < count; i++) {
            int type = java.declarations().generatedTypes().indexOf(java.facades().get(i).binaryName());
            if (type < 0) throw new IllegalArgumentException("owner class is absent from validated inventory");
            text.append("    iw_owned_types[").append(i).append("] = (*env)->NewGlobalRef(env, classes[").append(type).append("]);\n")
                    .append("    if (iw_owned_types[").append(i).append("] == NULL) goto failed;\n");
        }
        for (var method : callbacks.methods()) {
            int type = java.declarations().generatedTypes().indexOf(method.listener());
            if (type < 0) throw new IllegalArgumentException("listener class is absent from validated inventory");
            text.append("    ").append(method.methodField()).append(" = (*env)->GetMethodID(env, classes[").append(type).append("], ")
                    .append(BridgeBootstrapSources.cString(method.name())).append(", ").append(BridgeBootstrapSources.cString(method.descriptor())).append(");\n")
                    .append("    if (").append(method.methodField()).append(" == NULL) goto failed;\n");
        }
        text.append("    return 1;\nfailed:\n    iw_owned_metadata_dispose(env); return 0;\n}\n")
                .append(INPUT.replace("@STATE@", "L" + generation.supportPackage().replace('.', '/') + "/RootState;"))
                .append(WRAP.replace("@CONSTRUCTOR@", java.facades().getFirst().constructorDescriptor()));
    }

    private static void emit(StringBuilder text, BridgeOwnedCallbackAdmission admission, BridgeOwnedCallbackJavaSources.Sources java,
            BridgeListenerNativeSources.Sources listeners, BridgeRootIndexSources.Sources roots,
            BridgeOwnedCallbackJavaSources.Call call, String function) {
        var id = call.binding().method().target().orElseThrow();
        var slotEntry = admission.slots().stream().flatMap(proof -> proof.entries().entries().stream()).filter(entry -> entry.callable().equals(id)).findFirst();
        var slots = slotEntry.map(entry -> entry.retention().slots()).orElse(List.of());
        var nativeFormals = new ArrayList<String>(); var formals = new ArrayList<String>(); var arguments = new ArrayList<String>();
        var declarations = new StringBuilder(); var prepare = new StringBuilder(); var enter = new StringBuilder(); var leave = new StringBuilder();
        var cleanup = new StringBuilder(); var strings = new ArrayList<Integer>();
        if (call.constructor()) formals.add("jobject reserved");
        for (var input : call.inputs()) {
            int n = input.index(); var type = input.type();
            if (type.equals(IrType.reference("ironwood.lang.String"))) {
                if (input.transport() != BridgeOwnedCallbackJavaSources.Transport.VALUE) throw new IllegalArgumentException("String input lacks value transport");
                strings.add(n); formals.add("jstring arg" + n);
                nativeFormals.add("int64_t"); nativeFormals.add("int32_t");
                arguments.add("(int64_t)(uintptr_t)chars" + n); arguments.add("length" + n);
                continue;
            }
            nativeFormals.add(type.isReference() ? "void *" : BridgeValueNativeSources.cType(type));
            switch (input.transport()) {
                case VALUE -> { formals.add(BridgeValueNativeSources.jniType(type) + " arg" + n); arguments.add("arg" + n); }
                case LISTENER -> {
                    int kind = listeners.kinds().indexOf(type.referenceName());
                    if (kind < 0) throw new IllegalArgumentException("listener input has no transport kind");
                    formals.add("jobject arg" + n); arguments.add("listener" + n + " == NULL ? NULL : listener" + n + "->proxy");
                    declarations.append("    struct iw_listener *listener").append(n).append(" = NULL;\n");
                    prepare.append("    status = iw_listener_prepare(env, ").append(kind).append(", arg").append(n)
                            .append(", iw_exceptions.classes[IW_EX_OOM], &listener").append(n).append(", &frame.result);\n    if (status != 0) goto cleanup;\n");
                    cleanup.insert(0, "    iw_listener_release(env, listener" + n + ");\n");
                }
                case LOCAL_OWNER -> {
                    formals.add("jlong arg" + n); arguments.add("(void *)(uintptr_t)arg" + n);
                    if (input.ownerRecord()) {
                        formals.add("jlong owner" + n);
                        declarations.append("    struct iw_root_record *owner").append(n).append("_record = (struct iw_root_record *)(uintptr_t)owner").append(n).append(";\n");
                    }
                }
                case FOREIGN_OWNER -> {
                    int kind = java.facades().stream().map(BridgePermanentJavaSources.Facade::binaryName).toList().indexOf(type.referenceName());
                    if (kind < 0) throw new IllegalArgumentException("foreign owner has no exact facade");
                    formals.add("jobject arg" + n); arguments.add("address" + n);
                    declarations.append("    void *address").append(n).append(" = NULL; jobject state").append(n).append(" = NULL;\n");
                    prepare.append("    if (!iw_owned_input(env, ").append(kind).append(", arg").append(n).append(", &address").append(n)
                            .append(", &state").append(n).append(")) { status = -1; goto cleanup; }\n");
                    if (input.ownerRecord()) {
                        declarations.append("    struct iw_root_record *owner").append(n).append("_record = NULL;\n");
                        prepare.append("    if (state").append(n).append(" != NULL) owner").append(n).append("_record = (struct iw_root_record *)(uintptr_t)")
                                .append("(*env)->GetLongField(env, state").append(n).append(", iw_root_listener_owner);\n");
                    }
                    if (call.callback()) {
                        declarations.append("    int guarded").append(n).append(" = 0;\n");
                        enter.append("    if (state").append(n).append(" != NULL) {\n")
                                .append("        if (!iw_owned_enter(env, state").append(n).append(")) { status = -1; goto cleanup; }\n")
                                .append("        guarded").append(n).append(" = 1;\n    }\n");
                        leave.insert(0, "    if (guarded" + n + ") iw_owned_leave(env, state" + n + ");\n");
                    }
                }
            }
            if (call.callback() && input.ownerRecord()) {
                declarations.append("    int active").append(n).append(" = 0;\n");
                enter.append("    if (owner").append(n).append("_record != NULL) {\n")
                        .append("        if (!iw_listener_owner_enter(&owner").append(n).append("_record->callbacks)) {\n")
                        .append("            (*env)->ThrowNew(env, iw_owned_refusal, \"native callback nesting limit\"); status = -1; goto cleanup; }\n")
                        .append("        active").append(n).append(" = 1;\n    }\n");
                leave.insert(0, "    if (active" + n + ") iw_listener_owner_leave(env, &owner" + n + "_record->callbacks);\n");
            }
        }
        var commit = new StringBuilder();
        prepareSlots(admission, listeners, slots, declarations, prepare, commit, cleanup);
        declarations.append(BridgeStringInputSources.declarations(strings));
        // All input resources precede native execution and guard entry. A failed
        // acquisition joins the same reverse cleanup with its JNI error pending.
        prepare.append(BridgeStringInputSources.acquire(strings).replace("goto preparation_failed;", "{ status = -1; goto cleanup; }"));
        cleanup.insert(0, BridgeStringInputSources.release(strings));
        if (call.callback()) { nativeFormals.add("int64_t"); arguments.add("(int64_t)(uintptr_t)&context"); }
        nativeFormals.add("struct ironwood_bridge_result *"); arguments.add("&frame.result");
        String resultType = call.constructor() ? "jlong" : BridgeValueNativeSources.jniType(id.result());
        text.append("extern int32_t ").append(call.binding().entrySymbol()).append('(').append(String.join(", ", nativeFormals)).append(");\n")
                .append("static ").append(resultType).append(' ').append(function).append("(JNIEnv *env, jclass type")
                .append(formals.isEmpty() ? "" : ", " + String.join(", ", formals)).append(") {\n    (void)type;\n")
                .append("    struct { struct ironwood_bridge_result result;").append(slots.isEmpty() ? "" : " struct ironwood_bridge_slot slots[" + slots.size() + "];")
                .append(" } frame = {0};\n    int32_t status = 0;\n").append(declarations);
        if (call.callback()) text.append("    struct iw_callback_frame context = {env, NULL};\n");
        if (!leave.isEmpty()) text.append("    jthrowable pending = NULL;\n");
        if (call.constructor()) {
            int kind = roots.kinds().indexOf(id.parameters().getFirst());
            if (kind < 0) throw new IllegalArgumentException("constructor has no proved root kind");
            text.append("    struct iw_root_record *record = iw_root_reserve(env, reserved, ").append(kind).append(");\n    if (record == NULL) return 0;\n");
        }
        text.append(prepare).append(enter).append("    status = ").append(call.binding().entrySymbol()).append('(').append(String.join(", ", arguments)).append(");\n")
                .append(commit);
        if (call.constructor()) text.append("    if (status == 0) iw_root_publish(env, record, frame.result.value.reference);\n    else iw_root_discard(env, record);\n");
        if (!prepare.isEmpty() || !enter.isEmpty()) text.append("cleanup:\n");
        if (!leave.isEmpty()) text.append("    if (status < 0 && (*env)->ExceptionCheck(env)) { pending = (*env)->ExceptionOccurred(env); (*env)->ExceptionClear(env); }\n").append(leave);
        text.append(cleanup);
        if (!leave.isEmpty()) text.append("    if (pending != NULL) { (*env)->Throw(env, pending); (*env)->DeleteLocalRef(env, pending); }\n");
        text.append("    if (status > 0) iw_owned_failure(env, status, &frame.result);\n");
        if (call.callback() && admission.cleanup().reclaims(id)) text.append("    iw_callback_release(&context);\n");
        text.append(id.result().equals(IrType.VOID) && !call.constructor() ? "    return;\n" : "    return status == 0 ? "
                + (call.constructor() ? "(jlong)(uintptr_t)frame.result.value.reference" : "frame.result.value." + BridgeValueNativeSources.field(id.result())) + " : 0;\n");
        text.append("}\n");
    }

    private static void prepareSlots(BridgeOwnedCallbackAdmission admission, BridgeListenerNativeSources.Sources listeners,
            List<BridgeRetentionContract.Slot> slots, StringBuilder declarations, StringBuilder prepare, StringBuilder commit, StringBuilder cleanup) {
        for (int index = 0; index < slots.size(); index++) {
            var slot = slots.get(index); int holder = slot.holderInput();
            int field = admission.listenerFields().get(IrType.reference(slot.field().ownerClass())).indexOf(slot.field());
            int kind = listeners.kinds().indexOf(slot.field().type().referenceName());
            if (field < 0 || kind < 0) throw new IllegalArgumentException("listener slot has no fixed native owner field");
            String location = "owner" + holder + "_record->listeners[" + field + "]";
            // Distinct holder parameters can alias the same field. Its final
            // snapshot may therefore name any candidate from that field's writes.
            var candidates = slots.stream().filter(other -> other.field().equals(slot.field()))
                    .flatMap(other -> other.valueInputs().stream()).distinct().sorted().toList();
            for (int input : candidates) {
                String name = "prepared" + index + "_" + input;
                declarations.append("    struct iw_listener_slot *").append(name).append(" = NULL;\n");
                prepare.append("    if (owner").append(holder).append("_record != NULL) {\n")
                        .append("        status = iw_listener_slot_prepare(env, ").append(kind).append(", arg").append(input)
                        .append(", iw_exceptions.classes[IW_EX_OOM], &").append(name).append(", &frame.result);\n")
                        .append("        if (status != 0) goto cleanup;\n    }\n");
                cleanup.insert(0, "    iw_listener_slot_release(env, " + name + ");\n");
            }
            // The protected snapshot is present on normal and exceptional exits.
            // Null holders are native null-input failures, with no field to reconcile.
            commit.append("    if (frame.slots[").append(index).append("].holder != NULL) {\n")
                    .append("        void *value = frame.slots[").append(index).append("].value;\n")
                    .append("        struct iw_listener_slot **slot = &").append(location).append(";\n")
                    .append("        if (value != (*slot == NULL ? NULL : (*slot)->value->proxy)) {\n")
                    .append("            struct iw_listener_slot *selected = NULL;\n");
            for (int input : candidates) {
                String name = "prepared" + index + "_" + input;
                commit.append("            if (selected == NULL && ").append(name).append(" != NULL && ").append(name).append("->value->proxy == value) { selected = ")
                        .append(name).append("; ").append(name).append(" = NULL; }\n");
            }
            commit.append("            if (value != NULL && selected == NULL) abort();\n")
                    .append("            iw_listener_slot_commit(env, &owner").append(holder).append("_record->callbacks, slot, selected);\n        }\n    }\n");
        }
    }

    private static final String FAILURE = """
            __attribute__((noinline)) static void iw_owned_failure(JNIEnv *env, int32_t status, struct ironwood_bridge_result *result) {
                if (status == 1) {
                    if (!iw_callback_restore(env, result)) iw_exception_translate(env, &iw_exceptions, result->exception);
                } else iw_exception_error(env, &iw_exceptions, status == 2, "Ironwood protected owner entry failed");
            }
            """;

    private static final String INPUT = """
            // Cache metadata lazily after facade initialization has completed bootstrap.
            __attribute__((unused)) static int iw_owned_input(JNIEnv *env, int kind, jobject value, void **address, jobject *state) {
                if (value == NULL) { *address = NULL; *state = NULL; return 1; }
                if (iw_owned_addresses[kind] == NULL) {
                    jfieldID addressField = (*env)->GetFieldID(env, iw_owned_types[kind], iw_owned_address_names[kind], "J");
                    if (addressField == NULL) return 0;
                    jfieldID stateField = (*env)->GetFieldID(env, iw_owned_types[kind], iw_owned_state_names[kind], "@STATE@");
                    if (stateField == NULL) return 0;
                    iw_owned_states[kind] = stateField; iw_owned_addresses[kind] = addressField;
                }
                *state = (*env)->GetObjectField(env, value, iw_owned_states[kind]);
                if ((*env)->ExceptionCheck(env)) return 0;
                if ((*env)->GetIntField(env, *state, iw_root_status) != 0) {
                    (*env)->ThrowNew(env, iw_owned_refusal, "native root argument is not live"); return 0;
                }
                *address = (void *)(uintptr_t)(*env)->GetLongField(env, value, iw_owned_addresses[kind]);
                return 1;
            }
            __attribute__((unused)) static int iw_owned_enter(JNIEnv *env, jobject state) {
                jlong active = (*env)->GetLongField(env, state, iw_owned_active);
                if (active == INT64_MAX) { (*env)->ThrowNew(env, iw_owned_refusal, "native callback nesting limit"); return 0; }
                (*env)->SetLongField(env, state, iw_owned_active, active + 1); return 1;
            }
            __attribute__((unused)) static void iw_owned_leave(JNIEnv *env, jobject state) {
                jlong active = (*env)->GetLongField(env, state, iw_owned_active);
                (*env)->SetLongField(env, state, iw_owned_active, active - 1);
            }
            """;

    private static final String WRAP = """
            // The bounded invocation proof derives every exposed owner from a
            // guarded input. Resolve its already registered state and stable cache.
            __attribute__((unused)) static jobject iw_owned_wrap(JNIEnv *env, int kind, void *address) {
                if (address == NULL) return NULL;
                struct iw_root_record **found = iw_root_find(address);
                if (found == NULL) abort();
                jobject state = (*found)->state;
                jlong bits = (jlong)(uintptr_t)address;
                jobject existing = (*env)->CallObjectMethod(env, state, iw_owned_lookup, bits);
                if ((*env)->ExceptionCheck(env)) return NULL;
                if (existing != NULL) return existing;
                if (iw_owned_constructors[kind] == NULL) {
                    iw_owned_constructors[kind] = (*env)->GetMethodID(env, iw_owned_types[kind], "<init>", "@CONSTRUCTOR@");
                    if (iw_owned_constructors[kind] == NULL) return NULL;
                }
                jobject created = (*env)->NewObject(env, iw_owned_types[kind], iw_owned_constructors[kind], bits, state, (jobject)NULL);
                if ((*env)->ExceptionCheck(env) || created == NULL) return NULL;
                jobject result = (*env)->CallObjectMethod(env, state, iw_owned_remember, bits, created);
                int failed = (*env)->ExceptionCheck(env);
                (*env)->DeleteLocalRef(env, created); return failed ? NULL : result;
            }
            """;
}
