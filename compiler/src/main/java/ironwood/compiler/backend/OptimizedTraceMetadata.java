// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OptimizedTraceMetadata {
    private static final Pattern REGISTER_DECLARATION = Pattern.compile(
            "(?m)^declare void @ironwood_trace_register_current\\(ptr, i32\\)[^\\n]*\\n");
    private static final Pattern FUNCTION = Pattern.compile(
            "(?m)^define\\s+[^\\n]*?(@(?:\\\"(?:\\\\[0-9A-Fa-f]{2}|\\\\.|[^\\\"])*\\\"|[A-Za-z$._][A-Za-z$._0-9-]*))\\(");

    private OptimizedTraceMetadata() {
    }

    static String inject(String optimizedLlvm, boolean leadingSymbolUnderscore) {
        List<Function> functions = optimizedFunctions(optimizedLlvm);
        if (leadingSymbolUnderscore) {
            functions = new ArrayList<>(functions);
            for (Function function : List.copyOf(functions)) {
                functions.add(new Function(function.symbol(), "_" + function.name(),
                        linkageGuid("_" + function.name())));
            }
        }
        functions.sort(Comparator.comparing(Function::guid, Long::compareUnsigned));
        for (int index = 1; index < functions.size(); index++) {
            Function previous = functions.get(index - 1);
            Function current = functions.get(index);
            if (previous.guid() == current.guid() && !previous.name().equals(current.name())) {
                throw new IllegalArgumentException("optimized stack-trace GUID collision between "
                        + previous.name() + " and " + current.name());
            }
        }

        String withoutDeclaration = REGISTER_DECLARATION.matcher(optimizedLlvm).replaceFirst("");
        int metadata = withoutDeclaration.indexOf("\n!llvm.");
        if (metadata < 0) {
            metadata = withoutDeclaration.length();
        }
        StringBuilder injected = new StringBuilder();
        if (!withoutDeclaration.contains("@ironwood_trace_register(")) {
            injected.append("\ndeclare void @ironwood_trace_register(ptr, i32, ptr, i32, ptr)\n");
        }
        injected.append("\n@ironwood_trace_functions = private constant [")
                .append(functions.size()).append(" x { i64, ptr }] ");
        if (functions.isEmpty()) {
            injected.append("zeroinitializer\n");
        } else {
            injected.append('[');
            for (int index = 0; index < functions.size(); index++) {
                if (index > 0) {
                    injected.append(", ");
                }
                Function function = functions.get(index);
                injected.append("{ i64, ptr } { i64 ").append(function.guid())
                        .append(", ptr ").append(function.symbol()).append(" }");
            }
            injected.append("]\n");
        }
        injected.append("\ndefine void @ironwood_trace_register_current(ptr %sites, i32 %site.count) {\n")
                .append("entry:\n")
                .append("  call void @ironwood_trace_register(ptr %sites, i32 %site.count, ptr @ironwood_trace_functions, i32 ")
                .append(functions.size()).append(", ptr @ironwood_trace_code_end)\n")
                .append("  ret void\n")
                .append("}\n\n")
                .append("define internal void @ironwood_trace_code_end() {\n")
                .append("entry:\n")
                .append("  ret void\n")
                .append("}\n");
        return withoutDeclaration.substring(0, metadata) + injected
                + withoutDeclaration.substring(metadata);
    }

    private static List<Function> optimizedFunctions(String llvm) {
        List<Function> result = new ArrayList<>();
        Matcher matcher = FUNCTION.matcher(llvm);
        while (matcher.find()) {
            int end = llvm.indexOf("\n}", matcher.end());
            if (end < 0) {
                throw new IllegalArgumentException("unterminated optimized LLVM function");
            }
            String symbol = matcher.group(1);
            String name = decodeSymbol(symbol);
            result.add(new Function(symbol, name, linkageGuid(name)));
        }
        return result;
    }

    private static String decodeSymbol(String symbol) {
        if (!symbol.startsWith("@\"")) {
            return symbol.substring(1);
        }
        String encoded = symbol.substring(2, symbol.length() - 1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int index = 0; index < encoded.length();) {
            char character = encoded.charAt(index);
            if (character == '\\' && index + 2 < encoded.length()
                    && isHex(encoded.charAt(index + 1)) && isHex(encoded.charAt(index + 2))) {
                bytes.write(Integer.parseInt(encoded.substring(index + 1, index + 3), 16));
                index += 3;
            } else {
                byte[] plain = String.valueOf(character).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(plain);
                index++;
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }

    private static long linkageGuid(String linkageName) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(linkageName.getBytes(StandardCharsets.UTF_8));
            long guid = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                guid |= (long) Byte.toUnsignedInt(digest[index]) << (index * Byte.SIZE);
            }
            return guid;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is required for LLVM pseudo probes", exception);
        }
    }

    private record Function(String symbol, String name, long guid) {
    }
}
