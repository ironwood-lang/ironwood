// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.bridge;

import ironwood.compiler.BridgeObjectAdmission;
import ironwood.compiler.ir.IrType;
import ironwood.compiler.semantic.BridgeCriticalCalls;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Producer-selected critical downcalls for proved object entries. Each admitted
 * binding gains a second native adapter that Java reaches without a JVM thread-state
 * transition. Its registered JNI method stays complete and is used whenever the
 * running JVM cannot link the handle. Failures keep the protected-entry contract:
 * the adapter parks the status for its thread and a JNI helper translates it.
 * Results whose carrier has spare values report failure in the returned word, so
 * a successful call reads no shared state. Only long and double results consult
 * the pending-failure count.
 */
public final class BridgeCriticalSources {
    private static final String SIMPLE_NAME = "Critical";
    private BridgeCriticalSources() {}

    /** How one critical adapter returns its value and failure indication. */
    enum Carrier {
        /** jint status. */
        STATUS,
        /** jlong address; 1 is never an object address. */
        ADDRESS,
        /** jlong with the value in its low half; any high bit reports failure. */
        NARROW,
        /** The exact long or double; failure is observed through the pending count. */
        WIDE;

        static Carrier of(boolean address, IrType result) {
            if (address) return ADDRESS;
            if (result.equals(IrType.VOID)) return STATUS;
            if (result.isReference()) throw new IllegalArgumentException("critical result requires a primitive or permanent address");
            return switch (result.kind()) {
                case I64, F64 -> WIDE;
                default -> NARROW;
            };
        }
    }

    /** One deterministic generation pass; indices follow binding declaration order. */
    static final class Plan {
        private final BridgeCriticalCalls proof;
        private final String type;
        private int next;

        private Plan(BridgeCriticalCalls proof, String type) { this.proof = proof; this.type = type; }

        boolean admits(String entrySymbol) { return proof.refusal(entrySymbol).isEmpty(); }
        int count() { return next; }
    }

    /** Names shared by one facade class; locals cannot collide with source parameters. */
    static final class Emitter {
        private final Plan plan;
        private final Set<String> occupied;
        private String value, failure;

        Emitter(Plan plan, Set<String> occupied) { this.plan = plan; this.occupied = occupied; }

        boolean admits(String entrySymbol) { return plan != null && plan.admits(entrySymbol); }

        record Reserved(int index, String handle, String call) {}

        Reserved reserve() {
            int index = plan.next++;
            return new Reserved(index, BridgePermanentJavaSources.unique(occupied, "$ironwood$critical$" + index),
                    BridgePermanentJavaSources.unique(occupied, "$ironwood$call$" + index));
        }

        /** The helper has the native declaration's exact carriers and selects JNI when no handle was linked. */
        void emit(StringBuilder text, Reserved reserved, String nativeName, List<String> nativeFormals,
                String nativeResult, Carrier carrier, String throwsClause, String indent) {
            if (value == null) {
                value = BridgePermanentJavaSources.unique(occupied, "$ironwood$value");
                failure = BridgePermanentJavaSources.unique(occupied, "$ironwood$failure");
            }
            var carriers = new ArrayList<String>(); var names = new ArrayList<String>();
            for (String formal : nativeFormals) {
                int split = formal.lastIndexOf(' ');
                carriers.add(formal.substring(0, split)); names.add(formal.substring(split + 1));
            }
            boolean returns = !nativeResult.equals("void");
            String arguments = String.join(", ", names);
            String transported = switch (carrier) {
                case STATUS -> "int";
                case ADDRESS, NARROW -> "long";
                case WIDE -> nativeResult;
            };
            text.append(indent).append("    private static final java.lang.invoke.MethodHandle ").append(reserved.handle()).append(" = ")
                    .append(plan.type).append(".link(java.lang.invoke.MethodHandles.lookup(), ").append(reserved.index())
                    .append(", java.lang.invoke.MethodType.methodType(").append(transported).append(".class")
                    .append(carriers.stream().map(name -> ", " + name + ".class").collect(Collectors.joining())).append("));\n")
                    .append(indent).append("    private static ").append(nativeResult).append(' ').append(reserved.call())
                    .append('(').append(String.join(", ", nativeFormals)).append(')').append(throwsClause).append(" {\n")
                    .append(indent).append("        if (").append(reserved.handle()).append(" == null) ")
                    .append(returns ? "return " + nativeName + "(" + arguments + ");\n" : "{ " + nativeName + "(" + arguments + "); return; }\n")
                    .append(indent).append("        ").append(transported).append(' ').append(value).append(";\n")
                    .append(indent).append("        try { ").append(value).append(" = (").append(transported).append(") ")
                    .append(reserved.handle()).append(".invokeExact(").append(arguments).append("); }\n")
                    .append(indent).append("        catch (java.lang.Throwable ").append(failure).append(") { throw ")
                    .append(plan.type).append(".unexpected(").append(failure).append("); }\n").append(indent).append("        ");
            switch (carrier) {
                case STATUS -> text.append("if (").append(value).append(" != 0) throw ").append(plan.type).append(".raise();\n");
                case ADDRESS -> text.append("if (").append(value).append(" == 1L) throw ").append(plan.type).append(".raise();\n");
                case NARROW -> text.append("if (").append(value).append(" >>> 32 != 0L) throw ").append(plan.type).append(".raise();\n");
                case WIDE -> text.append("if (").append(plan.type).append(".failed()) ").append(plan.type).append(".pending();\n");
            }
            if (returns) {
                String narrowed = carrier != Carrier.NARROW ? value : switch (nativeResult) {
                    case "boolean" -> "(int) " + value + " != 0";
                    case "float" -> "java.lang.Float.intBitsToFloat((int) " + value + ")";
                    default -> "(" + nativeResult + ") " + value;
                };
                text.append(indent).append("        return ").append(narrowed).append(";\n");
            }
            text.append(indent).append("    }\n");
        }
    }

    /** Null unless the generation selected critical calls. */
    static Plan plan(BridgeObjectAdmission admission, BridgeGeneration generation) {
        if (!generation.criticalCalls()) return null;
        return new Plan(BridgeCriticalCalls.analyze(admission.program()), binaryName(generation));
    }

    static String binaryName(BridgeGeneration generation) { return generation.supportPackage() + "." + SIMPLE_NAME; }

    static List<BridgeJavaSources.NativeDeclaration> declarations(BridgeGeneration generation) {
        return List.of(new BridgeJavaSources.NativeDeclaration(binaryName(generation), "address", "(I)J"),
                new BridgeJavaSources.NativeDeclaration(binaryName(generation), "deliver", "()V"));
    }

    static String nativeFunction(BridgeJavaSources.NativeDeclaration declaration) {
        return switch (declaration.nativeName()) {
            case "address" -> "iw_critical_address";
            case "deliver" -> "iw_critical_deliver";
            default -> throw new IllegalArgumentException("unknown critical support declaration");
        };
    }

    static String javaSource(BridgeGeneration generation, String ensure) {
        return JAVA.replace("@PACKAGE@", generation.supportPackage()).replace("@GENERATION@", generation.identity())
                .replace("@ENSURE@", ensure);
    }

    /** Appended after the JNI adapters, whose failure translation it reuses. */
    static String nativeSupport(int count) {
        var table = new StringBuilder();
        for (int index = 0; index < count; index++) table.append(index == 0 ? "" : ", ").append("(void *)iw_critical_").append(index);
        return NATIVE.replace("@TABLE@", table).replace("@COUNT@", Integer.toString(count));
    }

    /** Precedes the critical adapters. Only a failing call touches thread-local storage. */
    static final String NATIVE_FAILURE = """
            static int32_t iw_critical_failures;
            static _Thread_local struct { int32_t status; void *exception; } iw_critical_pending;
            __attribute__((noinline, cold)) static void iw_critical_failure(int32_t status, void *exception) {
                if (iw_critical_pending.status == 0) __atomic_fetch_add(&iw_critical_failures, 1, __ATOMIC_SEQ_CST);
                iw_critical_pending.status = status; iw_critical_pending.exception = exception;
            }
            """;

    private static final String NATIVE = """
            static void *const iw_critical_table[] = {@TABLE@};
            static jlong iw_critical_address(JNIEnv *env, jclass type, jint index) {
                (void)env; (void)type;
                if (index == -1) return (jlong)(uintptr_t)&iw_critical_failures;
                if (index < 0 || index >= @COUNT@) return 0;
                return (jlong)(uintptr_t)iw_critical_table[index];
            }
            static void iw_critical_deliver(JNIEnv *env, jclass type) {
                (void)type;
                int32_t status = iw_critical_pending.status;
                if (status == 0) return;
                void *exception = iw_critical_pending.exception;
                iw_critical_pending.status = 0; iw_critical_pending.exception = NULL;
                __atomic_fetch_sub(&iw_critical_failures, 1, __ATOMIC_SEQ_CST);
                iw_permanent_failure(env, status, exception);
            }
            """;

    private static final String JAVA = """
            // SPDX-License-Identifier: MIT OR Apache-2.0
            package @PACKAGE@;

            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import java.lang.invoke.VarHandle;
            import java.lang.reflect.Array;
            import java.lang.reflect.Method;

            /**
             * Optional critical downcalls. Every caller keeps its registered JNI method
             * and uses it whenever this JVM does not supply a handle.
             */
            @Identity("@GENERATION@")
            public final class Critical {
                private static final Class<?>[] CARRIERS = {boolean.class, byte.class, short.class, char.class,
                        int.class, long.class, float.class, double.class};
                private static final String[] LAYOUT_NAMES = {"JAVA_BOOLEAN", "JAVA_BYTE", "JAVA_SHORT", "JAVA_CHAR",
                        "JAVA_INT", "JAVA_LONG", "JAVA_FLOAT", "JAVA_DOUBLE"};
                private static final Object LINKER;
                private static final Object OPTIONS;
                private static final Object[] LAYOUTS;
                private static final Class<?> LAYOUT;
                private static final Method DOWNCALL;
                private static final Method SEGMENT;
                private static final Method DESCRIPTOR;
                private static final Method VOID_DESCRIPTOR;
                private static final MethodHandle PENDING;

                static {
                    Support.@ENSURE@();
                    Object linker = null, options = null;
                    Object[] layouts = null;
                    Class<?> layout = null;
                    Method downcall = null, segment = null, descriptor = null, voidDescriptor = null;
                    MethodHandle pending = null;
                    // Java 21 offers this API as a preview and Java 22/23 as final API.
                    // Reflection keeps these classes loadable on all three releases.
                    if (!"jni".equals(System.getProperty("ironwood.bridge.calls"))) {
                        try {
                            Class<?> linkerType = Class.forName("java.lang.foreign.Linker");
                            Class<?> optionType = Class.forName("java.lang.foreign.Linker$Option");
                            Class<?> segmentType = Class.forName("java.lang.foreign.MemorySegment");
                            Class<?> descriptorType = Class.forName("java.lang.foreign.FunctionDescriptor");
                            Class<?> valueType = Class.forName("java.lang.foreign.ValueLayout");
                            layout = Class.forName("java.lang.foreign.MemoryLayout");
                            Object selected = linkerType.getMethod("nativeLinker").invoke(null);
                            Object critical;
                            try { critical = optionType.getMethod("critical", boolean.class).invoke(null, false); }
                            catch (NoSuchMethodException java21) { critical = optionType.getMethod("isTrivial").invoke(null); }
                            options = Array.newInstance(optionType, 1);
                            Array.set(options, 0, critical);
                            Class<?> layoutArray = Array.newInstance(layout, 0).getClass();
                            descriptor = descriptorType.getMethod("of", layout, layoutArray);
                            voidDescriptor = descriptorType.getMethod("ofVoid", layoutArray);
                            segment = segmentType.getMethod("ofAddress", long.class);
                            downcall = linkerType.getMethod("downcallHandle", segmentType, descriptorType, options.getClass());
                            layouts = new Object[CARRIERS.length];
                            for (int index = 0; index < CARRIERS.length; index++) {
                                layouts[index] = valueType.getField(LAYOUT_NAMES[index]).get(null);
                            }
                            // A constant handle reads the native pending-failure count with one load.
                            Object counter = segmentType.getMethod("reinterpret", long.class).invoke(segment.invoke(null, address(-1)), 4L);
                            // Java 21 binds only the segment; Java 22/23 also take a base offset.
                            Object path = Array.newInstance(Class.forName("java.lang.foreign.MemoryLayout$PathElement"), 0);
                            VarHandle access = (VarHandle) layout.getMethod("varHandle", path.getClass()).invoke(layouts[4], path);
                            MethodHandle read = MethodHandles.insertArguments(access.toMethodHandle(VarHandle.AccessMode.GET), 0, counter);
                            if (read.type().parameterCount() == 1) read = MethodHandles.insertArguments(read, 0, 0L);
                            pending = read.asType(MethodType.methodType(int.class));
                            linker = selected;
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                            linker = null;
                        }
                    }
                    LINKER = linker; OPTIONS = options; LAYOUTS = layouts; LAYOUT = layout; DOWNCALL = downcall;
                    SEGMENT = segment; DESCRIPTOR = descriptor; VOID_DESCRIPTOR = voidDescriptor; PENDING = pending;
                }

                private Critical() {}
                private static native long address(int index);
                private static native void deliver();

                /** Null selects the caller's registered JNI method. */
                public static MethodHandle link(MethodHandles.Lookup caller, int index, MethodType type) {
                    Class<?> owner = caller.lookupClass();
                    Identity identity = owner.getDeclaredAnnotation(Identity.class);
                    if (!caller.hasFullPrivilegeAccess() || owner.getClassLoader() != Critical.class.getClassLoader()
                            || identity == null || !"@GENERATION@".equals(identity.value())) {
                        throw new IllegalCallerException("Ironwood critical calls are private to artifact @GENERATION@");
                    }
                    if (LINKER == null) return null;
                    try {
                        long address = address(index);
                        if (address == 0) return null;
                        Object parameters = Array.newInstance(LAYOUT, type.parameterCount());
                        for (int position = 0; position < type.parameterCount(); position++) {
                            Array.set(parameters, position, layout(type.parameterType(position)));
                        }
                        Object function = type.returnType() == void.class ? VOID_DESCRIPTOR.invoke(null, parameters)
                                : DESCRIPTOR.invoke(null, layout(type.returnType()), parameters);
                        MethodHandle handle = (MethodHandle) DOWNCALL.invoke(LINKER, SEGMENT.invoke(null, address), function, OPTIONS);
                        return handle.type().equals(type) ? handle : null;
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        return null;
                    }
                }

                private static Object layout(Class<?> carrier) {
                    for (int index = 0; index < CARRIERS.length; index++) {
                        if (CARRIERS[index] == carrier) return LAYOUTS[index];
                    }
                    throw new IllegalArgumentException("unsupported critical carrier");
                }

                /** True while any thread has a parked native failure; callers then ask for their own. */
                public static boolean failed() {
                    try { return (int) PENDING.invokeExact() != 0; }
                    catch (Throwable unexpected) { throw unexpected(unexpected); }
                }

                /** Throws the calling thread's parked native failure, if it has one. */
                public static void pending() { deliver(); }

                /** For a call whose own result reported failure: its parked failure is always thrown. */
                public static Error raise() {
                    deliver();
                    return new LinkageError("Ironwood critical call lost its native failure");
                }

                public static Error unexpected(Throwable failure) {
                    if (failure instanceof Error error) throw error;
                    if (failure instanceof RuntimeException runtime) throw runtime;
                    throw new LinkageError("Ironwood critical call failed", failure);
                }
            }
            """;
}
