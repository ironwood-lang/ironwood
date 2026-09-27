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
        return generateAdmitted(artifact, admission, generation, java, BridgeRootIndexSources.generate(artifact, admission, generation));
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
            if (!exact) {
                throw new IllegalArgumentException("permanent native binding does not name its exact typed entry");
            }
            String function = "iw_permanent_" + adapters.size();
            adapters.add(new Adapter(new BridgeJavaSources.NativeDeclaration(binding.binaryName(), binding.nativeName(), binding.descriptor()), function));
            emit(text, entry, binding, module, types, enums, java, function, admission, roots, rootTypes);
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
            BridgeRootIndexSources.Sources roots, Map<IrType, Integer> rootTypes) {
        var id = entry.root().callable();
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
        var reservation = BridgeRootCalls.reservation(admission, id);
        boolean receiverState = BridgeRootCalls.receiverState(admission, id, instance);
        var nativeTypes = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        var strings = new ArrayList<Integer>();
        var references = new ArrayList<Integer>();
        var enumArguments = new ArrayList<Integer>();
        for (int index = constructor ? 1 : 0; index < id.parameters().size(); index++) {
            var type = id.parameters().get(index);
            if (enumParameters.containsKey(index)) {
                nativeTypes.add("int32_t");
                if (instance && index == 0) arguments.add("arg0");
                else { arguments.add("enum" + index); enumArguments.add(index); }
            } else if (type.equals(STRING)) {
                nativeTypes.add("int64_t"); nativeTypes.add("int32_t"); strings.add(index);
                arguments.add("(int64_t)(uintptr_t)chars" + index); arguments.add("length" + index);
            } else if (type.isReference()) {
                if (!types.containsKey(type) && !rootTypes.containsKey(type)) throw new IllegalArgumentException("native parameter lacks exact facade metadata");
                nativeTypes.add("void *");
                if (instance && index == 0) arguments.add("(void *)(uintptr_t)arg0");
                else { arguments.add("reference" + index); references.add(index); }
            } else { nativeTypes.add(BridgeValueNativeSources.cType(type)); arguments.add("arg" + index); }
        }
        nativeTypes.add("int64_t"); arguments.add("(int64_t)(uintptr_t)&result");
        String returned = constructor ? "jlong" : jniType(id.result());
        String exit = returned.equals("void") ? "return;" : "return 0;";
        text.append("extern int32_t ").append(entry.function().linkageName()).append('(').append(String.join(", ", nativeTypes)).append(");\n")
                .append("static ").append(returned).append(' ').append(function).append("(JNIEnv *env, jclass type");
        if (reservation.isPresent() || receiverState) text.append(", jobject root_state");
        for (int index = constructor ? 1 : 0; index < id.parameters().size(); index++) {
            text.append(", ").append(instance && index == 0 ? enumParameters.containsKey(index) ? "jint" : "jlong"
                    : jniType(id.parameters().get(index))).append(" arg").append(index);
        }
        text.append(") {\n    (void)type;\n");
        for (int index : strings) text.append("    const jchar *chars").append(index).append(" = NULL; jsize length").append(index).append(" = -1;\n");
        for (int index : references) text.append("    void *reference").append(index).append(" = NULL;\n");
        var rootInputs = references.stream().filter(index -> rootTypes.containsKey(id.parameters().get(index))).toList();
        for (int index : rootInputs) text.append("    jobject root").append(index).append(" = NULL;\n");
        for (int index : enumArguments) text.append("    int32_t enum").append(index).append(" = -1;\n");
        if (!rootInputs.isEmpty()) text.append("    if ((*env)->EnsureLocalCapacity(env, ").append(rootInputs.size() + 8).append(") != JNI_OK) goto preparation_failed;\n");
        for (int index : strings) {
            text.append("    if (arg").append(index).append(" != NULL) {\n")
                    .append("        length").append(index).append(" = (*env)->GetStringLength(env, arg").append(index).append(");\n")
                    .append("        if ((*env)->ExceptionCheck(env)) goto preparation_failed;\n")
                    .append("        chars").append(index).append(" = (*env)->GetStringChars(env, arg").append(index).append(", NULL);\n")
                    .append("        if (chars").append(index).append(" == NULL) goto preparation_failed;\n    }\n");
        }
        for (int index : references) {
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
            text.append("    if (!iw_enum_input_").append(enums.get(enumParameters.get(index).declaredType()))
                    .append("(env, arg").append(index).append(", &enum").append(index).append(")) goto preparation_failed;\n");
        }
        if (reservation.isPresent()) {
            int kind = roots.kinds().indexOf(reservation.orElseThrow());
            if (kind < 0) throw new IllegalArgumentException("fresh root lacks exact final destruction kind");
            text.append("    struct iw_root_record *reserved = iw_root_reserve(env, root_state, ").append(kind).append(");\n")
                    .append("    if (reserved == NULL) goto preparation_failed;\n");
        }
        text.append("    struct ironwood_bridge_result result;\n    int32_t status = ").append(entry.function().linkageName())
                .append('(').append(String.join(", ", arguments)).append(");\n");
        if (reservation.isPresent()) text.append("    if (status == 0 && result.value.reference != NULL) iw_root_publish(env, reserved, result.value.reference);\n")
                .append("    else iw_root_discard(env, reserved);\n");
        release(text, strings);
        text.append("    if (status != 0) { iw_permanent_failure(env, status, result.exception); ").append(exit).append(" }\n");
        if (constructor) text.append("    return (jlong)(uintptr_t)result.value.reference;\n");
        else if (enumResult.isPresent()) {
            text.append("    return iw_enum_output_").append(enums.get(enumResult.orElseThrow().declaredType()))
                    .append("(env, result.value.integer);\n");
        } else if (id.result().equals(STRING)) {
            var contract = module.stringResults().get(id);
            if (contract == null) throw new IllegalArgumentException("native String result lacks proved ownership");
            text.append("    const struct ironwood_string *value = result.value.reference;\n")
                    .append("    jstring copied = value == NULL ? NULL : (*env)->NewString(env, value->units, value->utf16_length);\n");
            if (contract.releaseAfterCopy()) text.append("    ironwood_deallocate(result.value.reference);\n");
            text.append("    return copied;\n");
        } else if (id.result().isReference()) {
            if (rootTypes.containsKey(id.result())) {
                String state = resultState(admission, id, instance, rootInputs);
                text.append("    return iw_root_wrap(env, ").append(rootTypes.get(id.result())).append(", result.value.reference, ").append(state).append(");\n");
            } else {
                if (!types.containsKey(id.result())) throw new IllegalArgumentException("native result lacks exact permanent facade metadata");
                text.append("    return iw_permanent_wrap(env, ").append(types.get(id.result())).append(", result.value.reference);\n");
            }
        } else if (id.result().equals(IrType.VOID)) text.append("    return;\n");
        else text.append("    return result.value.").append(BridgeValueNativeSources.field(id.result())).append(";\n");
        if (!strings.isEmpty() || !references.isEmpty() || !enumArguments.isEmpty() || reservation.isPresent()) {
            text.append("preparation_failed:\n"); release(text, strings); text.append("    ").append(exit).append('\n');
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
        return type.isReference() && !type.equals(STRING) ? "jobject" : BridgeValueNativeSources.jniType(type);
    }
}
