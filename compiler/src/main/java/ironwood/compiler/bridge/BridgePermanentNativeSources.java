// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.CompilationArtifact;
import ironwood.compiler.ir.IrCallableKind;
import ironwood.compiler.ir.IrType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** JNI conversion consumes final object proofs and matching generated private declarations. */
public final class BridgePermanentNativeSources {
    private static final IrType STRING = IrType.reference("ironwood.lang.String");
    public record Adapter(BridgeJavaSources.NativeDeclaration declaration, String functionName) {}

    private final String source;
    private final List<Adapter> adapters;
    private final BridgeJavaSources declarations;
    private final String generation;
    private final boolean rooted;

    private BridgePermanentNativeSources(String source, List<Adapter> adapters, BridgeJavaSources declarations, String generation, boolean rooted) {
        this.source = source; this.adapters = List.copyOf(adapters); this.declarations = declarations; this.generation = generation;
        this.rooted = rooted;
    }

    public String source() { return source; }
    public List<Adapter> adapters() { return adapters; }
    public boolean rooted() { return rooted; }
    public boolean matches(BridgeJavaSources java, BridgeGeneration identity) {
        return declarations.equals(java) && generation.equals(identity.identity());
    }

    public static BridgePermanentNativeSources generate(CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgeGeneration generation, BridgePermanentJavaSources.Sources java) {
        if (!generation.matchesObjects(artifact, admission)
                || !BridgePermanentJavaSources.generate(artifact, admission, generation).equals(java)) {
            throw new IllegalArgumentException("permanent native adapters require exact final admission and generated Java declarations");
        }
        return generateAdmitted(artifact, admission, generation, java, null);
    }

    public static BridgePermanentNativeSources generateRoots(CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgeGeneration generation, BridgePermanentJavaSources.Sources java) {
        if (!generation.matchesObjects(artifact, admission)
                || !BridgePermanentJavaSources.generateRoots(artifact, admission, generation).equals(java)) {
            throw new IllegalArgumentException("root native adapters require exact final admission and generated Java declarations");
        }
        if (admission.managedHeap()) return generateManaged(artifact, admission, generation, java);
        return generateAdmitted(artifact, admission, generation, java, BridgeRootIndexSources.generate(artifact, admission, generation));
    }

    private static BridgePermanentNativeSources generateManaged(CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgeGeneration generation, BridgePermanentJavaSources.Sources java) {
        var snapshot = admission.lifetime().exceptions();
        var callbacks = admission.entries().gcCallbacks();
        var text = new StringBuilder(BridgeExceptionNativeSources.generateManaged(artifact, snapshot.projection(), snapshot.entries(),
                callbacks.map(BridgeGcCallbacks::carriers).orElse(null)));
        if (callbacks.isPresent()) text.append("static int iw_callback_restore(JNIEnv *, struct ironwood_bridge_result *);\n");
        text
                .append("\nstatic struct iw_exception_metadata iw_exceptions;\n")
                .append("__attribute__((noinline)) static void iw_permanent_failure(JNIEnv *env, int32_t status, void *exception) {\n")
                .append("    void *slots[] = {&exception}; struct iw_gc_foreign_root roots __attribute__((cleanup(iw_gc_foreign_root_close))) = {0};\n")
                .append("    if (!iw_gc_foreign_root_open(&roots, slots, 1)) iw_gc_transition_refuse();\n")
                .append(callbacks.isEmpty() ? "    if (status == 1) iw_exception_translate(env, &iw_exceptions, exception);\n"
                        : "    if (status == 1) { struct ironwood_bridge_result failure = {.exception = exception};\n"
                            + "        if (!iw_callback_restore(env, &failure)) iw_exception_translate(env, &iw_exceptions, failure.exception); }\n")
                .append("    else iw_exception_error(env, &iw_exceptions, status == 2, \"Ironwood protected entry failed\");\n}\n");
        if (BridgeByteViewSources.required(java.declarations())) text.append(BridgeByteViewInputSources.HELPERS);
        BridgeEnumNativeSources.metadata(text, java);
        var core = BridgeGcAnchorSources.generate(java, generation);
        if (callbacks.isEmpty()) text.append(core.source());
        else text.append("static int iw_callback_metadata_init(JNIEnv *, jclass *);\nstatic void iw_callback_metadata_dispose(JNIEnv *);\n")
                .append(core.source().replace("static int iw_object_metadata_init(JNIEnv *env, jclass *classes) {",
                        "static int iw_object_metadata_init(JNIEnv *env, jclass *classes) {\n    if (!iw_callback_metadata_init(env, classes)) goto failed;")
                        .replace("static void iw_object_metadata_dispose(JNIEnv *env) {", "static void iw_object_metadata_dispose(JNIEnv *env) {\n    iw_callback_metadata_dispose(env);"));
        var roots = new java.util.LinkedHashMap<IrType, Integer>();
        for (int i = 0; i < java.facades().size(); i++) roots.put(IrType.reference(java.facades().get(i).binaryName()), i);
        BridgeGcRegistrationSources.Sources registrations = null;
        if (callbacks.isPresent()) {
            text.append(BridgeGcListenerSources.generate(artifact, admission, java, generation, roots));
            registrations = BridgeGcRegistrationSources.generate(callbacks.orElseThrow(), java, generation);
            text.append(registrations.source());
        }
        var generics = BridgeGenericDomain.discover(artifact, admission.surface().types());
        for (var root : admission.surface().roots().roots()) {
            var references = new ArrayList<>(root.callable().parameters()); references.add(root.callable().result());
            for (var reference : references) {
                var storage = BridgeGenericDomain.storage(reference);
                if (roots.containsKey(storage)) roots.put(reference, roots.get(storage));
                else if (reference.isTypeParameter() && generics.variables().containsKey(reference.referenceName())) roots.put(reference, -1);
            }
        }
        var enums = new java.util.LinkedHashMap<IrType, Integer>();
        for (int i = 0; i < java.enums().size(); i++) enums.put(IrType.reference(java.enums().get(i).binaryName()), i);
        var entries = admission.entries().entries().stream().collect(Collectors.toMap(e -> e.function().linkageName(), e -> e));
        var adapters = new ArrayList<>(core.adapters());
        if (registrations != null) adapters.addAll(registrations.adapters());
        for (var binding : java.declarations().bindings()) {
            var entry = entries.get(binding.entrySymbol());
            boolean exact = entry != null && binding.method().target().filter(entry.root().callable()::equals).isPresent();
            var enumType = IrType.reference(binding.binaryName());
            if (!exact && entry != null && !binding.method().isStatic() && enums.containsKey(enumType)) {
                var constants = BridgeEnumConstants.discover(artifact, Set.of(enumType));
                exact = BridgeEnumDispatch.prove(artifact, enumType, binding.method(), constants).targets().stream()
                        .anyMatch(target -> !target.javaIdentity() && target.callable().equals(entry.root().callable()));
            }
            if (!exact || !binding.fixedEnums().equals(entry.fixedEnums()))
                throw new IllegalArgumentException("managed binding does not name its exact typed entry");
            String function = "iw_managed_" + adapters.size();
            adapters.add(new Adapter(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function));
            emit(text, entry, binding, admission.entries(), Map.of(), enums, java, function, admission, null, roots, artifact);
        }
        var criticals = java.declarations().bindings().stream().filter(binding -> binding.critical() >= 0).toList();
        if (!criticals.isEmpty()) {
            text.append(BridgeCriticalSources.MANAGED_FAILURE);
            for (int i = 0; i < criticals.size(); i++) {
                var binding = criticals.get(i);
                if (binding.critical() != i) throw new IllegalArgumentException("critical adapter indices must follow declaration order");
                emitCritical(text, entries.get(binding.entrySymbol()), binding, admission.entries());
            }
            text.append(BridgeCriticalSources.nativeSupport(criticals.size(), true));
            for (var declaration : BridgeCriticalSources.declarations(generation)) adapters.add(new Adapter(declaration, BridgeCriticalSources.nativeFunction(declaration)));
        }
        return new BridgePermanentNativeSources(text.toString(), adapters, java.declarations(), generation.identity(), true);
    }

    private static BridgePermanentNativeSources generateAdmitted(CompilationArtifact artifact, BridgeObjectAdmission admission,
            BridgeGeneration generation, BridgePermanentJavaSources.Sources java, BridgeRootIndexSources.Sources roots) {
        var module = admission.entries();
        var snapshot = admission.lifetime().exceptions();
        var text = new StringBuilder(BridgeExceptionNativeSources.generate(artifact, snapshot.projection(), snapshot.entries()))
                .append("\nstatic struct iw_exception_metadata iw_exceptions;\n")
                .append("__attribute__((noinline)) static void iw_permanent_failure(JNIEnv *env, int32_t status, void *exception) {\n")
                .append("    if (status == 1) iw_exception_translate(env, &iw_exceptions, exception);\n")
                .append("    else iw_exception_error(env, &iw_exceptions, status == 2, \"Ironwood protected entry failed\");\n}\n");
        if (BridgeByteViewSources.required(java.declarations())) text.append(BridgeByteViewInputSources.HELPERS);
        var permanent = new BridgePermanentJavaSources.Sources(java.declarations(), java.facades().stream().filter(value -> !value.rooted()).toList(), java.enums());
        var rootFacades = java.facades().stream().filter(BridgePermanentJavaSources.Facade::rooted).toList();
        if (roots != null) {
            text.append(roots.source());
            BridgeRootFacadeNativeSources.metadata(text, java, generation, rootFacades);
        }
        BridgeEnumNativeSources.metadata(text, permanent);
        metadata(text, permanent, generation);
        if (roots != null) text.append("static void iw_object_metadata_dispose(JNIEnv *env) { iw_permanent_metadata_dispose(env); iw_root_facade_dispose(env); }\n")
                .append("static int iw_object_metadata_init(JNIEnv *env, jclass *classes) {\n")
                .append("    if (!iw_root_facade_init(env, classes)) return 0;\n")
                .append("    if (!iw_permanent_metadata_init(env, classes)) { iw_root_facade_dispose(env); return 0; }\n    return 1;\n}\n");
        var entries = module.entries().stream().collect(Collectors.toMap(entry -> entry.function().linkageName(), entry -> entry));
        var types = new java.util.LinkedHashMap<IrType, Integer>();
        for (int index = 0; index < permanent.facades().size(); index++) types.put(IrType.reference(permanent.facades().get(index).binaryName()), index);
        var rootTypes = new java.util.LinkedHashMap<IrType, Integer>();
        for (int index = 0; index < rootFacades.size(); index++) rootTypes.put(IrType.reference(rootFacades.get(index).binaryName()), index);
        for (var root : admission.surface().roots().roots()) {
            var references = new ArrayList<>(root.callable().parameters());
            references.add(root.callable().result());
            for (var reference : references) {
                var storage = BridgeGenericDomain.storage(reference);
                if (types.containsKey(storage)) types.put(reference, types.get(storage));
                if (rootTypes.containsKey(storage)) rootTypes.put(reference, rootTypes.get(storage));
            }
        }
        var enums = new java.util.LinkedHashMap<IrType, Integer>();
        for (int index = 0; index < java.enums().size(); index++) enums.put(IrType.reference(java.enums().get(index).binaryName()), index);
        var adapters = new ArrayList<Adapter>();
        for (var binding : java.declarations().bindings()) {
            var entry = entries.get(binding.entrySymbol());
            boolean exact = entry != null && binding.method().target().filter(entry.root().callable()::equals).isPresent();
            var enumType = IrType.reference(binding.binaryName());
            if (!exact && entry != null && !binding.method().isStatic() && enums.containsKey(enumType)) {
                var constants = BridgeEnumConstants.discover(artifact, Set.of(enumType));
                exact = BridgeEnumDispatch.prove(artifact, enumType, binding.method(), constants).targets().stream()
                        .anyMatch(target -> !target.javaIdentity() && target.callable().equals(entry.root().callable()));
            }
            if (!exact || !binding.fixedEnums().equals(entry.fixedEnums())) {
                throw new IllegalArgumentException("permanent native binding does not name its exact typed entry");
            }
            String function = "iw_permanent_" + adapters.size();
            adapters.add(new Adapter(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function));
            emit(text, entry, binding, module, types, enums, java, function, admission, roots, rootTypes, artifact);
        }
        var criticals = java.declarations().bindings().stream().filter(binding -> binding.critical() >= 0).toList();
        if (criticals.isEmpty() != java.declarations().supportNatives().isEmpty()) {
            throw new IllegalArgumentException("critical adapters and their support declarations must be generated together");
        }
        if (!criticals.isEmpty()) {
            if (!generation.criticalCalls()) throw new IllegalArgumentException("critical adapters require a generation that selected them");
            text.append(BridgeCriticalSources.NATIVE_FAILURE);
            for (int index = 0; index < criticals.size(); index++) {
                var binding = criticals.get(index);
                if (binding.critical() != index) throw new IllegalArgumentException("critical adapter indices must follow declaration order");
                var entry = entries.get(binding.entrySymbol());
                var callable = entry.root().callable();
                if (BridgeRootCalls.reservation(admission, callable).isPresent() || !BridgeRootRetentionSources.slots(admission, callable).isEmpty()
                        || BridgeRootCalls.receiverState(admission, callable, !binding.method().isStatic())) {
                    throw new IllegalArgumentException("critical adapter cannot carry root state or retention slots");
                }
                emitCritical(text, entry, binding, module);
            }
            text.append(BridgeCriticalSources.nativeSupport(criticals.size()));
            for (var declaration : java.declarations().supportNatives()) {
                adapters.add(new Adapter(declaration, BridgeCriticalSources.nativeFunction(declaration)));
            }
        }
        var conversions = new java.util.HashSet<BridgeJavaSources.NativeDeclaration>();
        var generics = BridgeGenericDomain.discover(artifact, admission.surface().types());
        for (var binding : java.declarations().bindings()) {
            if (!binding.returnsPermanentAddress() || !conversions.add(binding.conversionDeclaration())) continue;
            Integer index = types.get(binding.method().result());
            String function = "iw_permanent_convert_" + adapters.size();
            adapters.add(new Adapter(binding.conversionDeclaration(), function));
            text.append("static jobject ").append(function).append("(JNIEnv *env, jclass type, jlong address) {\n    (void)type;\n");
            if (index != null) text.append("    return iw_permanent_wrap(env, ").append(index).append(", (void *)(uintptr_t)address);\n");
            else {
                var alternatives = generics.variables().get(binding.method().result().referenceName());
                if (alternatives == null || alternatives.isEmpty()) throw new IllegalArgumentException("address result lacks exact permanent facade proof");
                text.append("    if (address == 0) return NULL;\n    switch (ironwood_bridge_type_id((void *)(uintptr_t)address)) {\n");
                for (var alternative : alternatives) {
                    Integer facade = types.get(alternative);
                    if (facade == null || !admission.lifetime().references().containsKey(alternative)) {
                        throw new IllegalArgumentException("generic result alternative lacks final storage proof");
                    }
                    int typeId = admission.program().classes().stream().filter(type -> type.name().equals(alternative.referenceName()))
                            .findFirst().orElseThrow().typeId();
                    text.append("    case ").append(typeId).append(": return iw_permanent_wrap(env, ").append(facade)
                            .append(", (void *)(uintptr_t)address);\n");
                }
                text.append("    default: iw_exception_error(env, &iw_exceptions, 0, \"Unknown finite generic result\"); return NULL;\n    }\n");
            }
            text.append("}\n");
        }
        for (var binding : java.declarations().facadeRegistrations()) {
            String function = "iw_permanent_register_" + adapters.size();
            adapters.add(new Adapter(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function));
            text.append("static void ").append(function).append("(JNIEnv *env, jclass type, jlong address, jobject facade) {\n")
                    .append("    (void)type;\n")
                    .append("    jobject value = (*env)->CallStaticObjectMethod(env, iw_permanent_cache, iw_permanent_remember, address, facade);\n")
                    .append("    (void)(*env)->ExceptionCheck(env);\n")
                    .append("    if (value != NULL) (*env)->DeleteLocalRef(env, value);\n}\n");
        }
        for (var binding : java.declarations().rootDestructions()) {
            String function = "iw_root_free_" + adapters.size();
            adapters.add(new Adapter(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function));
            text.append("static void ").append(function).append("(JNIEnv *env, jclass type, jobject state, jlong address) {\n")
                    .append("    (void)type;\n    struct iw_root_record **found = iw_root_resolve(env, state, (void *)(uintptr_t)address);\n")
                    .append("    if (found != NULL) iw_root_destroy(env, found);\n}\n");
        }
        return new BridgePermanentNativeSources(text.toString(), adapters, java.declarations(), generation.identity(), roots != null);
    }

    private static void metadata(StringBuilder text, BridgePermanentJavaSources.Sources java, BridgeGeneration generation) {
        int count = java.facades().size();
        if (count == 0) {
            if (java.enums().isEmpty()) {
                text.append("static void iw_permanent_metadata_dispose(JNIEnv *env) { (void)env; }\n")
                        .append("static int iw_permanent_metadata_init(JNIEnv *env, jclass *classes) { (void)env; (void)classes; return 1; }\n");
                return;
            }
            text.append("static void iw_permanent_metadata_dispose(JNIEnv *env) { iw_enum_metadata_dispose(env); }\n")
                    .append("static int iw_permanent_metadata_init(JNIEnv *env, jclass *classes) { return iw_enum_metadata_init(env, classes); }\n");
            return;
        }
        text.append("static jclass iw_permanent_types[").append(count).append("];\n")
                .append("static jfieldID iw_permanent_addresses[").append(count).append("];\n")
                .append("static jmethodID iw_permanent_constructors[").append(count).append("];\n")
                .append("static jclass iw_permanent_cache;\nstatic jmethodID iw_permanent_lookup, iw_permanent_remember;\n")
                .append("static const char *const iw_permanent_address_names[] = {")
                .append(java.facades().stream().map(facade -> BridgeJavaSources.quote(facade.addressField())).collect(Collectors.joining(", ")))
                .append("};\n")
                .append("static void iw_permanent_metadata_dispose(JNIEnv *env) {\n")
                .append(java.enums().isEmpty() ? "" : "    iw_enum_metadata_dispose(env);\n")
                .append("    for (int index = 0; index < ").append(count).append("; index++) {\n")
                .append("        if (iw_permanent_types[index] != NULL) (*env)->DeleteGlobalRef(env, iw_permanent_types[index]);\n")
                .append("        iw_permanent_types[index] = NULL; iw_permanent_addresses[index] = NULL; iw_permanent_constructors[index] = NULL;\n    }\n")
                .append("    if (iw_permanent_cache != NULL) (*env)->DeleteGlobalRef(env, iw_permanent_cache);\n")
                .append("    iw_permanent_cache = NULL; iw_permanent_lookup = NULL; iw_permanent_remember = NULL;\n}\n")
                .append("static int iw_permanent_metadata_init(JNIEnv *env, jclass *classes) {\n")
                .append(java.enums().isEmpty() ? "" : "    if (!iw_enum_metadata_init(env, classes)) goto failed;\n");
        for (int index = 0; index < count; index++) {
            int classIndex = java.declarations().generatedTypes().indexOf(java.facades().get(index).binaryName());
            if (classIndex < 0) throw new IllegalArgumentException("facade absent from bootstrap class inventory");
            text.append("    iw_permanent_types[").append(index).append("] = (*env)->NewGlobalRef(env, classes[").append(classIndex).append("]);\n")
                    .append("    if (iw_permanent_types[").append(index).append("] == NULL) goto failed;\n");
        }
        int cacheIndex = java.declarations().generatedTypes().indexOf(generation.supportPackage() + ".PermanentCache");
        if (cacheIndex < 0) throw new IllegalArgumentException("permanent cache absent from bootstrap class inventory");
        text.append("    iw_permanent_cache = (*env)->NewGlobalRef(env, classes[").append(cacheIndex).append("]);\n")
                .append("    if (iw_permanent_cache == NULL) goto failed;\n")
                .append("    iw_permanent_lookup = (*env)->GetStaticMethodID(env, iw_permanent_cache, \"lookup\", \"(J)Ljava/lang/Object;\");\n")
                .append("    if (iw_permanent_lookup == NULL) goto failed;\n")
                .append("    iw_permanent_remember = (*env)->GetStaticMethodID(env, iw_permanent_cache, \"remember\", \"(JLjava/lang/Object;)Ljava/lang/Object;\");\n")
                .append("    if (iw_permanent_remember == NULL) goto failed;\n    return 1;\n")
                .append("failed:\n    iw_permanent_metadata_dispose(env); return 0;\n}\n")
                .append("__attribute__((unused)) static int iw_permanent_ready(JNIEnv *env, int index) {\n")
                .append("    if (iw_permanent_addresses[index] != NULL) return 1;\n")
                .append("    jfieldID address = (*env)->GetFieldID(env, iw_permanent_types[index], iw_permanent_address_names[index], \"J\");\n")
                .append("    if (address == NULL) return 0;\n")
                .append("    jmethodID constructor = (*env)->GetMethodID(env, iw_permanent_types[index], \"<init>\", \"(JLjava/lang/Void;)V\");\n")
                .append("    if (constructor == NULL) return 0;\n")
                .append("    iw_permanent_constructors[index] = constructor; iw_permanent_addresses[index] = address; return 1;\n}\n")
                .append("__attribute__((unused)) static jobject iw_permanent_wrap(JNIEnv *env, int index, void *address) {\n")
                .append("    if (address == NULL) return NULL;\n")
                .append("    jlong bits = (jlong)(uintptr_t)address;\n")
                .append("    jobject existing = (*env)->CallStaticObjectMethod(env, iw_permanent_cache, iw_permanent_lookup, bits);\n")
                .append("    if ((*env)->ExceptionCheck(env)) return NULL;\n    if (existing != NULL) return existing;\n")
                .append("    if (!iw_permanent_ready(env, index)) return NULL;\n")
                .append("    jobject created = (*env)->NewObject(env, iw_permanent_types[index], iw_permanent_constructors[index], bits, (jobject)NULL);\n")
                .append("    if ((*env)->ExceptionCheck(env) || created == NULL) return NULL;\n")
                .append("    jobject result = (*env)->CallStaticObjectMethod(env, iw_permanent_cache, iw_permanent_remember, bits, created);\n")
                .append("    int failed = (*env)->ExceptionCheck(env);\n")
                .append("    (*env)->DeleteLocalRef(env, created);\n    return failed ? NULL : result;\n}\n");
    }

    private static void emit(StringBuilder text, BridgeEntryModule.Entry entry, BridgeJavaSources.Binding binding,
            BridgeEntryModule module, Map<IrType, Integer> types, Map<IrType, Integer> enums,
            BridgePermanentJavaSources.Sources java, String function, BridgeObjectAdmission admission,
            BridgeRootIndexSources.Sources roots, Map<IrType, Integer> rootTypes, CompilationArtifact artifact) {
        var id = entry.root().callable();
        var callbacks = module.gcCallbacks();
        boolean context = callbacks.map(value -> value.context(id)).orElse(false);
        var batch = admission.batching().map(value -> value.entries().get(id)).orElse(null);
        var arrays = BridgeArrayValueSources.contract(artifact, id, admission.managedHeap(),
                module.gcCallbacks().map(BridgeGcCallbacks::listeners).orElse(null));
        var enumParameters = module.enumConversions().map(conversions -> conversions.parameters().getOrDefault(id, List.of()))
                .orElse(List.of()).stream().collect(Collectors.toMap(BridgeEnumConversions.Parameter::input, parameter -> parameter));
        for (var parameter : enumParameters.values()) {
            Integer index = enums.get(parameter.declaredType());
            if (index == null || !java.enums().get(index).constants().containsAll(parameter.constants())) {
                throw new IllegalArgumentException("native enum argument differs from exact typed token mapping");
            }
        }
        var enumResult = module.enumConversions().map(conversions -> conversions.results().get(id));
        enumResult.ifPresent(result -> {
            Integer index = enums.get(result.declaredType());
            if (index == null || !java.enums().get(index).constants().equals(result.constants())) {
                throw new IllegalArgumentException("native enum result differs from exact typed token mapping");
            }
        });
        boolean constructor = id.kind() == IrCallableKind.CONSTRUCTOR;
        boolean instance = !constructor && !binding.method().isStatic();
        boolean retaining = callbacks.map(value -> value.retaining(id)).orElse(false);
        boolean explicitReceiver = retaining && instance;
        var reservation = BridgeRootCalls.reservation(admission, id);
        boolean receiverState = BridgeRootCalls.receiverState(admission, id, instance);
        int slotCount = BridgeRootRetentionSources.slots(admission, id).size();
        String result = slotCount == 0 ? "result" : "retention_frame.result";
        var nativeTypes = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        var strings = new ArrayList<Integer>();
        var references = new ArrayList<Integer>();
        var listeners = new ArrayList<Integer>();
        var enumArguments = new ArrayList<Integer>();
        for (int index = constructor ? 1 : 0; index < id.parameters().size(); index++) {
            if (entry.fixedEnums().containsKey(index)) continue;
            var type = id.parameters().get(index);
            if (enumParameters.containsKey(index)) {
                nativeTypes.add("int32_t");
                if (instance && index == 0) arguments.add("arg0");
                else { arguments.add("enum" + index); enumArguments.add(index); }
            } else if (type.equals(STRING)) {
                nativeTypes.add("int64_t"); nativeTypes.add("int32_t"); strings.add(index);
                arguments.add("(int64_t)(uintptr_t)chars" + index); arguments.add("length" + index);
            } else if (ironwood.compiler.semantic.BridgeByteViews.view(type)) {
                nativeTypes.add("void *"); arguments.add("view" + index);
            } else if (type.isArray()) {
                nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)array" + index);
            } else if (type.isReference()) {
                boolean listener = callbacks.map(value -> value.listener(type)).orElse(false);
                if (!types.containsKey(type) && !rootTypes.containsKey(type) && !listener) throw new IllegalArgumentException("native parameter lacks exact facade metadata");
                nativeTypes.add("void *");
                if (instance && !explicitReceiver && index == 0) arguments.add("(void *)(uintptr_t)arg0");
                else { arguments.add("reference" + index); references.add(index); }
                if (listener) listeners.add(index);
            } else { nativeTypes.add(BridgeValueNativeSources.cType(type)); arguments.add("arg" + index); }
        }
        if (context) { nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)&callback_frame"); }
        nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)&" + result);
        var typedNativeTypes = entry.function().parameters().stream()
                .map(parameter -> parameter.value().type()).map(type -> type.isReference() ? "void *" : BridgeValueNativeSources.cType(type)).toList();
        // Boolean JNI carriers are unsigned bytes; typed lowering normalizes their i8 bits.
        var carrierTypes = nativeTypes.stream().map(type -> type.equals("uint8_t") ? "int8_t" : type).toList();
        if (!carrierTypes.equals(typedNativeTypes)) {
            throw new IllegalArgumentException("native declaration differs from protected typed entry ABI");
        }
        boolean managed = admission.managedHeap();
        boolean managedResult = managed && (constructor || BridgeGcJavaSources.handle(admission, id.result()));
        String returned = managedResult ? "jobject" : constructor || binding.returnsPermanentAddress() ? "jlong" : jniType(id.result());
        String exit = returned.equals("void") ? "return;" : "return 0;";
        text.append("extern int32_t ").append(entry.function().linkageName()).append('(').append(String.join(", ", carrierTypes)).append(");\n");
        if (batch != null) text.append("extern int32_t ").append(batch.entrySymbol()).append('(').append(String.join(", ", carrierTypes)).append(");\n");
        text.append("static ").append(returned).append(' ').append(function).append("(JNIEnv *env, jclass type");
        if (retaining) text.append(", jlong registration_id");
        if (!managed && (reservation.isPresent() || receiverState)) text.append(", jobject root_state");
        for (int index = constructor ? 1 : 0; index < id.parameters().size(); index++) {
            if (entry.fixedEnums().containsKey(index)) continue;
            boolean token = binding.enumTokenParameters().contains(index - (instance || constructor ? 1 : 0));
            text.append(", ").append(instance && !explicitReceiver && index == 0 ? enumParameters.containsKey(index) ? "jint" : "jlong"
                    : token ? "jint" : jniType(id.parameters().get(index))).append(" arg").append(index);
        }
        if (batch != null) text.append(", jobject batch_buffer");
        text.append(") {\n    (void)type;\n");
        if (callbacks.isPresent()) BridgeGcRegistrationSources.declarations(text, admission, id);
        text.append(BridgeArrayInputSources.declarations(id.parameters()))
                .append(BridgeByteViewInputSources.declarations(id.parameters()));
        for (int index : strings) text.append("    const jchar *chars").append(index).append(" = NULL; jsize length").append(index).append(" = -1;\n");
        for (int index : references) text.append("    void *reference").append(index).append(" = NULL;\n");
        for (int index : listeners) text.append("    struct iw_gc_listener_use listener").append(index)
                .append(" __attribute__((cleanup(iw_gc_listener_use_close))) = {.env = env};\n");
        if (context) text.append("    struct iw_callback_frame callback_frame __attribute__((cleanup(iw_gc_callback_finish))) = {.env = env};\n")
                .append("    iw_gc_callback_prepare(&callback_frame);\n");
        var rootInputs = references.stream().filter(index -> rootTypes.containsKey(id.parameters().get(index))).toList();
        for (int index : rootInputs) text.append("    jobject root").append(index).append(" = NULL;\n");
        for (int index : enumArguments) text.append("    int32_t enum").append(index).append(" = -1;\n");
        if (managed) {
            if (slotCount == 0) text.append("    struct ironwood_bridge_result result;\n");
            else text.append("    struct { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[").append(slotCount)
                    .append("]; } retention_frame = {0};\n");
            var slots = new ArrayList<String>();
            slots.add("&" + result + ".exception");
            if (constructor || id.result().isReference() && enumResult.isEmpty()) slots.add("&" + result + ".value.reference");
            if (instance && !explicitReceiver && !enumParameters.containsKey(0)) slots.add("&arg0");
            for (int index : references) slots.add("&reference" + index);
            if (context) slots.add("&callback_frame.created_carriers");
            for (int index : BridgeArrayInputSources.indices(id.parameters())) slots.add("&array_state" + index + ".converted");
            boolean collecting = ironwood.compiler.ir.GcEffects.analyzeForeignOwners(admission.program())
                    .function(entry.function().linkageName()).mayCollect();
            boolean conversions = id.parameters().stream().skip(instance || constructor ? 1 : 0).anyMatch(IrType::isReference);
            if (slots.size() > 1 && (collecting || managedResult || id.result().isReference() || conversions)) {
                text.append("    ").append(result).append(".exception = NULL;\n");
                if (constructor || id.result().isReference() && enumResult.isEmpty()) text.append("    ").append(result).append(".value.reference = NULL;\n");
                text.append("    void *native_slots[] = {").append(String.join(", ", slots)).append("};\n")
                    .append("    struct iw_gc_foreign_root native_roots __attribute__((cleanup(iw_gc_foreign_root_close))) = {0};\n")
                    .append("    if (!iw_gc_foreign_root_open(&native_roots, native_slots, ").append(slots.size()).append(")) iw_gc_transition_refuse();\n");
            }
            if (managedResult) text.append("    struct iw_gc_anchor_reservation reserved __attribute__((cleanup(iw_gc_anchor_discard))) = {0};\n")
                    .append("    if (!iw_gc_anchor_reserve(env, &reserved)) goto preparation_failed;\n");
        }
        if (callbacks.isPresent()) BridgeGcRegistrationSources.acquire(text, admission, id);
        if (!rootInputs.isEmpty()) text.append("    if ((*env)->EnsureLocalCapacity(env, ").append(rootInputs.size() + 8).append(") != JNI_OK) goto preparation_failed;\n");
        for (int index : strings) {
            text.append("    if (arg").append(index).append(" != NULL) {\n")
                    .append("        length").append(index).append(" = (*env)->GetStringLength(env, arg").append(index).append(");\n")
                    .append("        if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("        chars").append(index).append(" = (*env)->GetStringChars(env, arg").append(index).append(", NULL);\n")
                    .append(managed ? "        if (!iw_gc_jni_acquired(env, chars" + index + ")) goto preparation_failed;\n    }\n"
                            : "        if (chars" + index + " == NULL) goto preparation_failed;\n    }\n");
        }
        for (int index : references) {
            if (listeners.contains(index)) {
                text.append("    { int32_t prepared = iw_gc_listener_prepare(env, ")
                        .append(BridgeGcListenerSources.kind(callbacks.orElseThrow(), id.parameters().get(index)))
                        .append(", arg").append(index).append(", &listener").append(index).append(".value, &").append(result).append(");\n")
                        .append("      if (prepared != 0) { if (prepared > 0) iw_permanent_failure(env, prepared, ").append(result)
                        .append(".exception); goto preparation_failed; }\n")
                        .append("      reference").append(index).append(" = listener").append(index).append(".value == NULL ? NULL : listener")
                        .append(index).append(".value->proxy; }\n");
                continue;
            }
            if (rootTypes.containsKey(id.parameters().get(index))) {
                text.append("    if (!iw_root_input(env, ").append(rootTypes.get(id.parameters().get(index))).append(", arg").append(index)
                        .append(", &reference").append(index).append(", &root").append(index).append(")) goto preparation_failed;\n");
                continue;
            }
            int facade = types.get(id.parameters().get(index));
            text.append("    if (arg").append(index).append(" != NULL) {\n")
                    .append("        if (!iw_permanent_ready(env, ").append(facade).append(")) goto preparation_failed;\n")
                    .append("        jlong address = (*env)->GetLongField(env, arg").append(index).append(", iw_permanent_addresses[").append(facade).append("]);\n")
                    .append("        if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("        reference").append(index).append(" = (void *)(uintptr_t)address;\n    }\n");
        }
        for (int index : enumArguments) {
            if (binding.enumTokenParameters().contains(index - (instance || constructor ? 1 : 0))) {
                text.append("    enum").append(index).append(" = arg").append(index).append(";\n");
            } else text.append("    if (!iw_enum_input_").append(enums.get(enumParameters.get(index).declaredType()))
                    .append("(env, arg").append(index).append(", &enum").append(index).append(")) goto preparation_failed;\n");
        }
        if (managed && callbacks.isPresent()) BridgeGcRegistrationSources.prepare(text, admission, id);
        else BridgeRootRetentionSources.prepare(text, admission, id, instance);
        text.append(BridgeByteViewInputSources.acquire(artifact, id, managed, callbacks.map(BridgeGcCallbacks::listeners).orElse(null)))
                .append(BridgeArrayInputSources.acquire(id.parameters()));
        if (batch != null) text.append("    callback_frame.batch_buffer = batch_buffer;\n")
                .append("    if (batch_buffer != NULL) {\n")
                .append("        callback_frame.batch_data = (*env)->GetDirectBufferAddress(env, batch_buffer);\n")
                .append("        if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n    }\n");
        if (!managed && reservation.isPresent()) {
            int kind = roots.kinds().indexOf(reservation.orElseThrow());
            if (kind < 0) throw new IllegalArgumentException("fresh root lacks exact final destruction kind");
            text.append("    struct iw_root_record *reserved = iw_root_reserve(env, root_state, ").append(kind).append(");\n")
                    .append("    if (reserved == NULL) goto preparation_failed;\n");
        }
        if (slotCount == 0 && !managed) text.append("    struct ironwood_bridge_result result;\n");
        else if (slotCount != 0 && !managed) text.append("    struct { struct ironwood_bridge_result result; struct ironwood_bridge_slot slots[").append(slotCount)
                .append("]; } retention_frame = {0};\n");
        text.append("    int32_t status = ");
        if (batch != null) text.append("callback_frame.batch_data != NULL ? ").append(batch.entrySymbol())
                .append('(').append(String.join(", ", arguments)).append(") : ");
        text.append(entry.function().linkageName())
                .append('(').append(String.join(", ", arguments)).append(");\n");
        if (!managed && reservation.isPresent()) text.append("    if (status == 0 && ").append(result).append(".value.reference != NULL) iw_root_publish(env, reserved, ")
                .append(result).append(".value.reference);\n")
                .append("    else iw_root_discard(env, reserved);\n");
        if (managed && callbacks.isPresent()) BridgeGcRegistrationSources.commit(text, admission, id);
        else BridgeRootRetentionSources.commit(text, admission, id, instance);
        release(text, strings);
        BridgeArrayValueSources.finish(text, arrays, result, "iw_permanent_failure", exit, slotCount != 0,
                module.stringResults().containsKey(id) && module.stringResults().get(id).releaseAfterCopy());
        if (!id.result().isArray()) text.append(BridgeArrayInputSources.release(id.parameters()));
        if (id.result().isArray()) BridgeArrayValueSources.result(text, arrays, result);
        else if (managedResult) {
            var returnedType = constructor ? id.parameters().getFirst() : id.result();
            Integer kind = rootTypes.get(returnedType);
            if (kind == null) throw new IllegalArgumentException("managed result has no exact finite facade domain");
            String selectedKind = kind.toString();
            if (kind == -1) {
                var alternatives = BridgeGenericDomain.discover(artifact, admission.surface().types()).variables().get(returnedType.referenceName());
                if (alternatives == null || alternatives.isEmpty()) throw new IllegalArgumentException("empty managed result domain");
                text.append("    int facade_kind = -1;\n    if (").append(result).append(".value.reference != NULL) switch (ironwood_bridge_type_id(")
                        .append(result).append(".value.reference)) {\n");
                for (var alternative : alternatives) {
                    Integer target = rootTypes.get(alternative);
                    if (target == null || target < 0) throw new IllegalArgumentException("generic result alternative lacks an exact facade");
                    int typeId = admission.program().classes().stream().filter(type -> type.name().equals(alternative.referenceName())).findFirst().orElseThrow().typeId();
                    text.append("    case ").append(typeId).append(": facade_kind = ").append(target).append("; break;\n");
                }
                text.append("    default: iw_exception_error(env, &iw_exceptions, 0, \"unknown managed result domain\"); return NULL;\n    }\n");
                selectedKind = "facade_kind";
            }
            text.append("    return iw_gc_anchor_deliver(env, &reserved, ").append(selectedKind).append(", ").append(result).append(".value.reference);\n");
        }
        else if (constructor || binding.returnsPermanentAddress()) text.append("    return (jlong)(uintptr_t)").append(result).append(".value.reference;\n");
        else if (enumResult.isPresent()) {
            text.append("    return iw_enum_output_").append(enums.get(enumResult.orElseThrow().declaredType()))
                    .append("(env, ").append(result).append(".value.integer);\n");
        } else if (id.result().equals(STRING)) {
            var contract = module.stringResults().get(id);
            if (contract == null) throw new IllegalArgumentException("native String result lacks proved ownership");
            text.append("    const struct ironwood_string *value = ").append(result).append(".value.reference;\n")
                    .append("    jstring copied = value == NULL ? NULL : (*env)->NewString(env, value->units, value->utf16_length);\n");
            if (managed) text.append("    if (value != NULL && !iw_gc_jni_acquired(env, copied)) return NULL;\n");
            if (contract.releaseAfterCopy()) text.append("    ironwood_deallocate(").append(result).append(".value.reference);\n");
            text.append("    return copied;\n");
        } else if (id.result().isReference()) {
            if (rootTypes.containsKey(id.result())) {
                String state = resultState(admission, id, instance, rootInputs);
                text.append("    return iw_root_wrap(env, ").append(rootTypes.get(id.result())).append(", ").append(result).append(".value.reference, ").append(state).append(");\n");
            } else {
                if (!types.containsKey(id.result())) throw new IllegalArgumentException("native result lacks exact permanent facade metadata");
                text.append("    return iw_permanent_wrap(env, ").append(types.get(id.result())).append(", ").append(result).append(".value.reference);\n");
            }
        } else if (id.result().equals(IrType.VOID)) text.append("    return;\n");
        else text.append("    return ").append(result).append(".value.").append(BridgeValueNativeSources.field(id.result())).append(";\n");
        boolean fallibleEnumInput = enumArguments.stream().anyMatch(index -> !binding.enumTokenParameters().contains(index - (instance || constructor ? 1 : 0)));
        if (!strings.isEmpty() || !references.isEmpty() || fallibleEnumInput || reservation.isPresent() || managedResult || retaining || batch != null || !managed && slotCount != 0
                || !BridgeArrayInputSources.indices(id.parameters()).isEmpty()
                || !BridgeByteViewInputSources.indices(id.parameters()).isEmpty()) {
            text.append("preparation_failed:\n"); release(text, strings);
            text.append(BridgeArrayInputSources.release(id.parameters())).append("    ").append(exit).append('\n');
        }
        text.append("}\n");
    }

    /** Same protected entry and result frame as the JNI adapter, without JNI conversions or a JNIEnv. */
    private static void emitCritical(StringBuilder text, BridgeEntryModule.Entry entry, BridgeJavaSources.Binding binding,
            BridgeEntryModule module) {
        var id = entry.root().callable();
        boolean instance = !binding.method().isStatic();
        var enumParameters = module.enumConversions().map(conversions -> conversions.parameters().getOrDefault(id, List.of()))
                .orElse(List.of()).stream().map(BridgeEnumConversions.Parameter::input).collect(Collectors.toSet());
        if (id.kind() == IrCallableKind.CONSTRUCTOR || id.result().isReference() && !binding.returnsPermanentAddress()) {
            throw new IllegalArgumentException("critical adapter requires a primitive, void or permanent address result");
        }
        var formals = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        for (int index = 0; index < id.parameters().size(); index++) {
            if (entry.fixedEnums().containsKey(index)) continue;
            var type = id.parameters().get(index);
            if (instance && index == 0) {
                if (enumParameters.contains(index) || !type.isReference()) {
                    throw new IllegalArgumentException("critical adapter requires a permanent facade receiver");
                }
                formals.add("jlong arg0"); arguments.add("(void *)(uintptr_t)arg0");
            } else if (enumParameters.contains(index)) {
                if (!binding.enumTokenParameters().contains(index - (instance ? 1 : 0))) {
                    throw new IllegalArgumentException("critical adapter requires primitive enum tokens");
                }
                formals.add("jint arg" + index); arguments.add("arg" + index);
            } else if (type.isReference()) {
                throw new IllegalArgumentException("critical adapter cannot convert a reference argument");
            } else {
                formals.add(BridgeValueNativeSources.jniType(type) + " arg" + index); arguments.add("arg" + index);
            }
        }
        arguments.add("(int64_t)(uintptr_t)&result");
        var carrier = BridgeCriticalSources.Carrier.of(binding.returnsPermanentAddress(), id.result());
        String returned = switch (carrier) {
            case STATUS -> "jint";
            case ADDRESS, NARROW -> "jlong";
            case WIDE -> jniType(id.result());
        };
        String failed = switch (carrier) {
            case STATUS, ADDRESS -> "1";
            case NARROW -> "INT64_C(1) << 32";
            case WIDE -> "0";
        };
        text.append("static ").append(returned).append(" iw_critical_").append(binding.critical()).append('(')
                .append(formals.isEmpty() ? "void" : String.join(", ", formals)).append(") {\n")
                .append("    struct ironwood_bridge_result result;\n")
                .append("    int32_t status = ").append(entry.function().linkageName()).append('(').append(String.join(", ", arguments)).append(");\n")
                .append("    if (__builtin_expect(status != 0, 0)) { iw_critical_failure(status, result.exception); return ")
                .append(failed).append("; }\n");
        switch (carrier) {
            case STATUS -> text.append("    return 0;\n");
            case ADDRESS -> text.append("    return (jlong)(uintptr_t)result.value.reference;\n");
            case NARROW -> {
                // Java narrows the low half again, so sign or zero extension is immaterial.
                if (id.result().kind() == IrType.Kind.F32) {
                    text.append("    union { float value; uint32_t bits; } single = {result.value.single};\n")
                            .append("    return (jlong)single.bits;\n");
                } else text.append("    return (jlong)(uint32_t)result.value.").append(BridgeValueNativeSources.field(id.result())).append(";\n");
            }
            case WIDE -> text.append("    return result.value.").append(BridgeValueNativeSources.field(id.result())).append(";\n");
        }
        text.append("}\n");
    }

    private static String resultState(BridgeObjectAdmission admission, BridgeCallableId callable, boolean instance, List<Integer> rootInputs) {
        var origin = admission.roots().orElseThrow().protocol().resultOrigins().get(callable);
        if (origin == null) throw new IllegalArgumentException("root result lacks an exact admitted origin");
        if (origin.kind() == BridgeResultOriginContract.Kind.FRESH_ROOT) return "root_state";
        if (origin.kind() == BridgeResultOriginContract.Kind.NULL_ONLY) return "NULL";
        var inputs = origin.inputs().stream().sorted().toList();
        var expression = new StringBuilder();
        for (int offset = 0; offset < inputs.size(); offset++) {
            int index = inputs.get(offset);
            boolean receiver = instance && index == 0;
            if (!receiver && !rootInputs.contains(index)) throw new IllegalArgumentException("root result names an input without root state");
            String state = receiver ? "root_state" : "root" + index;
            if (offset + 1 == inputs.size()) expression.append(state);
            else expression.append("result.value.reference == ").append(receiver ? "(void *)(uintptr_t)arg0" : "reference" + index)
                    .append(" ? ").append(state).append(" : ");
        }
        return expression.toString();
    }

    private static void release(StringBuilder text, List<Integer> strings) {
        for (int index = strings.size() - 1; index >= 0; index--) {
            int argument = strings.get(index);
            text.append("    if (chars").append(argument).append(" != NULL) (*env)->ReleaseStringChars(env, arg")
                    .append(argument).append(", chars").append(argument).append(");\n");
        }
    }

    private static String jniType(IrType type) {
        return type.isReference() && !type.equals(STRING) && !type.isArray() ? "jobject" : BridgeValueNativeSources.jniType(type);
    }
}
