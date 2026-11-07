// SPDX-License-Identifier: MIT OR Apache-2.0
package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Cold object validation and retention; never patches executable instructions. */
final class GcRootSections {
    private GcRootSections() {}

    static byte[] retain(byte[] object) throws IOException {
        return prepare(object).object();
    }

    static String contract(byte[] object) throws IOException {
        var prepared = prepare(object);
        return "// SPDX-License-Identifier: MIT OR Apache-2.0\n#include <stdint.h>\n"
                + "const uint64_t iw_gc_map_hash = UINT64_C(0x" + Long.toUnsignedString(prepared.hash(), 16) + ");\n";
    }

    private record Prepared(byte[] object, long hash) {}

    static String elfLinkerScript() {
        return "/* SPDX-License-Identifier: MIT OR Apache-2.0 */\nSECTIONS {\n"
                + "  .data.rel.ro : ALIGN(8) {\n"
                + "    HIDDEN(__start_ironwood_gc_maps = .);\n"
                + "    KEEP(*(ironwood_gc_maps))\n"
                + "    HIDDEN(__stop_ironwood_gc_maps = .);\n"
                + "  }\n} INSERT BEFORE .dynamic;\n";
    }

    private static Prepared prepare(byte[] object) throws IOException {
        if (object.length >= 4 && object[0] == 0x7f && object[1] == 'E' && object[2] == 'L' && object[3] == 'F') return elf(object);
        return macho(object);
    }

    private static Prepared macho(byte[] object) throws IOException {
        var data = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);
        if (object.length < 32 || data.getInt(0) != 0xfeedfacf || data.getInt(4) != 0x100000c
                || data.getInt(12) != 1) throw invalid("expected arm64 Mach-O object");
        int count = data.getInt(16), bytes = data.getInt(20), position = 32, root = -1;
        long symbolsOffset = -1, symbolsCount = 0, stringsOffset = -1, stringsSize = 0;
        int sectionIndex = 0;
        Set<Integer> executable = new HashSet<>();
        if (bytes < 0 || bytes > object.length - 32 || count < 0 || count > bytes / 8) throw invalid("command extent");
        boolean unwind = false;
        for (int i = 0; i < count; ++i) {
            if (position > 32 + bytes - 8) throw invalid("command header");
            int command = data.getInt(position), size = data.getInt(position + 4);
            if (size < 8 || size > 32 + bytes - position) throw invalid("command size");
            if (command == 2) {
                if (size < 24 || symbolsOffset != -1) throw invalid("symbol table command");
                symbolsOffset = Integer.toUnsignedLong(data.getInt(position + 8));
                symbolsCount = Integer.toUnsignedLong(data.getInt(position + 12));
                stringsOffset = Integer.toUnsignedLong(data.getInt(position + 16));
                stringsSize = Integer.toUnsignedLong(data.getInt(position + 20));
            }
            if (command == 0x19) {
                if (size < 72) throw invalid("segment size");
                int sections = data.getInt(position + 64);
                if (sections < 0 || sections > (size - 72) / 80) throw invalid("section count");
                for (int j = 0; j < sections; j++) {
                    int section = position + 72 + 80 * j;
                    sectionIndex++;
                    if (name(object, section, "__text") && name(object, section + 16, "__TEXT")) executable.add(sectionIndex);
                    if (name(object, section, "__compact_unwind") || name(object, section, "__eh_frame")) unwind = true;
                    if (name(object, section, "__llvm_stackmaps") && (name(object, section + 16, "__LLVM_STACKMAPS")
                            || name(object, section + 16, "__DATA_CONST"))) {
                        if (root != -1 || (data.getInt(section + 64) & 255) != 0) throw invalid("duplicate/nonregular root section");
                        root = section;
                    }
                }
            }
            position += size;
        }
        if (position != 32 + bytes || root == -1 || !unwind) throw invalid("missing roots or unwind coverage");
        long offset = Integer.toUnsignedLong(data.getInt(root + 48)), size = data.getLong(root + 40);
        extent(object, offset, size);
        int map = (int)offset;
        if (size < 16 || data.get(map) != 3 || data.get(map + 1) != 0 || data.getShort(map + 2) != 0) throw invalid("map version");
        long functions = Integer.toUnsignedLong(data.getInt(map + 4)), constants = Integer.toUnsignedLong(data.getInt(map + 8));
        long records = Integer.toUnsignedLong(data.getInt(map + 12));
        if (functions == 0 && (constants != 0 || records != 0 || size != 16) || functions > (size - 16) / 24 || constants > (size - 16 - 24 * functions) / 8
                || functions != 0 && records == 0 || records > 1048576) throw invalid("map counts");
        long relocationOffset = Integer.toUnsignedLong(data.getInt(root + 56));
        long relocations = Integer.toUnsignedLong(data.getInt(root + 60));
        extent(object, relocationOffset, 8 * relocations);
        if (relocations != functions) throw invalid("function relocation count");
        extent(object, symbolsOffset, 16 * symbolsCount);
        extent(object, stringsOffset, stringsSize);
        byte[] normalized = java.util.Arrays.copyOfRange(object, map, (int)(offset + size));
        var canonical = ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN);
        Set<Integer> locations = new HashSet<>();
        for (int i = 0; i < relocations; i++) {
            int relocation = (int)relocationOffset + 8 * i;
            int address = data.getInt(relocation), bits = data.getInt(relocation + 4);
            if (address < 16 || (address - 16) % 24 != 0 || (address - 16) / 24 >= functions
                    || (bits >>> 24 & 1) != 0 || (bits >>> 25 & 3) != 3 || bits >>> 28 != 0 || (bits >>> 27 & 1) != 1
                    || !locations.add(address)) throw invalid("function relocation form");
            int symbol = bits & 0xffffff;
            if (symbol >= symbolsCount || data.getLong(map + address) != 0) throw invalid("function relocation addend/symbol");
            int entry = (int)symbolsOffset + 16 * symbol;
            if ((data.get(entry + 4) & 0xe) != 0xe || !executable.contains(Byte.toUnsignedInt(data.get(entry + 5)))) {
                throw invalid("root relocation does not resolve executable storage");
            }
            long name = Integer.toUnsignedLong(data.getInt(entry));
            if (name >= stringsSize) throw invalid("function symbol name");
            int start = (int)(stringsOffset + name), end = start;
            while (end < stringsOffset + stringsSize && object[end] != 0) end++;
            if (end == stringsOffset + stringsSize) throw invalid("unterminated function symbol");
            String linkage = new String(object, start, end - start, StandardCharsets.UTF_8);
            if (!linkage.startsWith("_")) throw invalid("Mach-O function symbol convention");
            long nativeId = OptimizedTraceMetadata.linkageGuid(linkage), irId = OptimizedTraceMetadata.linkageGuid(linkage.substring(1));
            canonical.putLong(address, Long.compareUnsigned(nativeId, irId) < 0 ? nativeId : irId);
        }
        // ld64 otherwise discards an anonymous stack-map section even when its
        // section$start symbol is referenced, leaving a zero-length placeholder.
        byte[] result = object.clone();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(root + 64, data.getInt(root + 64) | 0x10000000);
        // dyld applies function-address fixups, then protects __DATA_CONST.
        // A retained custom segment alone remains writable after loading.
        java.util.Arrays.fill(result, root + 16, root + 32, (byte)0);
        byte[] segment = "__DATA_CONST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(segment, 0, result, root + 16, segment.length);
        long hash = 0xcbf29ce484222325L;
        for (byte value : normalized) hash = (hash ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        return new Prepared(result, hash);
    }

    /** Relocatable ELF admission; native execution has a separate exact target gate. */
    private static Prepared elf(byte[] object) throws IOException {
        var data = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);
        if (object.length < 64 || object[4] != 2 || object[5] != 1 || data.getShort(16) != 1
                || (data.getShort(18) != 183 && data.getShort(18) != 62)) throw invalid("expected supported ELF64 object");
        long table = data.getLong(40);
        int stride = Short.toUnsignedInt(data.getShort(58)), count = Short.toUnsignedInt(data.getShort(60)),
                namesIndex = Short.toUnsignedInt(data.getShort(62));
        if (stride != 64 || count == 0 || namesIndex >= count) throw invalid("ELF section inventory");
        extent(object, table, (long)stride * count);
        int names = (int)table + namesIndex * stride;
        long namesOffset = data.getLong(names + 24), namesSize = data.getLong(names + 32);
        extent(object, namesOffset, namesSize);
        int root = -1, rootIndex = -1;
        boolean unwind = false;
        for (int i = 0; i < count; i++) {
            int header = (int)table + i * stride;
            long name = Integer.toUnsignedLong(data.getInt(header));
            if (name >= namesSize) throw invalid("ELF section name");
            int start = (int)(namesOffset + name), end = start;
            while (end < namesOffset + namesSize && object[end] != 0) end++;
            if (end == namesOffset + namesSize) throw invalid("ELF unterminated section name");
            String text = new String(object, start, end - start, StandardCharsets.US_ASCII);
            if (text.equals(".eh_frame")) unwind = data.getLong(header + 32) != 0;
            if (text.equals(".llvm_stackmaps") || text.equals("ironwood_gc_maps")) {
                if (root != -1 || data.getInt(header + 4) != 1) throw invalid("ELF duplicate/non-PROGBITS root section");
                root = header; rootIndex = i;
            }
        }
        if (root == -1 || !unwind) throw invalid("ELF missing roots or unwind coverage");
        long offset = data.getLong(root + 24), size = data.getLong(root + 32);
        extent(object, offset, size);
        int map = (int)offset;
        if (size < 16 || data.get(map) != 3 || data.get(map + 1) != 0 || data.getShort(map + 2) != 0) throw invalid("ELF map version");
        long functions = Integer.toUnsignedLong(data.getInt(map + 4)), constants = Integer.toUnsignedLong(data.getInt(map + 8));
        long records = Integer.toUnsignedLong(data.getInt(map + 12));
        if (functions == 0 && (constants != 0 || records != 0 || size != 16) || functions > (size - 16) / 24 || constants > (size - 16 - 24 * functions) / 8
                || functions != 0 && records == 0 || records > 1048576) throw invalid("ELF map counts");
        byte[] normalized = java.util.Arrays.copyOfRange(object, map, (int)(offset + size));
        var canonical = ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN);
        Set<Long> locations = new HashSet<>();
        for (int i = 0; i < count; i++) {
            int header = (int)table + i * stride;
            if (data.getInt(header + 44) != rootIndex || data.getInt(header + 4) != 4) continue;
            long relocationOffset = data.getLong(header + 24), relocationSize = data.getLong(header + 32);
            extent(object, relocationOffset, relocationSize);
            if (data.getLong(header + 56) != 24 || relocationSize % 24 != 0) throw invalid("ELF relocation stride");
            int symbolsIndex = data.getInt(header + 40);
            if (symbolsIndex < 0 || symbolsIndex >= count) throw invalid("ELF relocation symbol table");
            int symbols = (int)table + symbolsIndex * stride;
            long symbolOffset = data.getLong(symbols + 24), symbolSize = data.getLong(symbols + 32);
            extent(object, symbolOffset, symbolSize);
            if (data.getInt(symbols + 4) != 2 || data.getLong(symbols + 56) != 24 || symbolSize % 24 != 0) throw invalid("ELF symbol stride");
            int stringsIndex = data.getInt(symbols + 40);
            if (stringsIndex < 0 || stringsIndex >= count) throw invalid("ELF symbol strings");
            int strings = (int)table + stringsIndex * stride;
            long stringsOffset = data.getLong(strings + 24), stringsSize = data.getLong(strings + 32);
            extent(object, stringsOffset, stringsSize);
            if (data.getInt(strings + 4) != 3) throw invalid("ELF symbol string table");
            for (long p = relocationOffset; p < relocationOffset + relocationSize; p += 24) {
                long address = data.getLong((int)p), info = data.getLong((int)p + 8), symbol = info >>> 32;
                int type = (int)info, expected = data.getShort(18) == 183 ? 257 : 1;
                if (address < 16 || (address - 16) % 24 != 0 || (address - 16) / 24 >= functions || type != expected
                        || !locations.add(address) || symbol >= symbolSize / 24 || data.getLong(map + (int)address) != 0) {
                    throw invalid("ELF absolute function relocation");
                }
                int entry = (int)(symbolOffset + symbol * 24);
                int section = Short.toUnsignedInt(data.getShort(entry + 6));
                if (section == 0 || section >= count || (data.getLong((int)table + section * stride + 8) & 4) == 0) {
                    throw invalid("ELF root relocation does not resolve executable storage");
                }
                int kind = Byte.toUnsignedInt(data.get(entry + 4)) & 15;
                long value = data.getLong(entry + 8), addend = data.getLong((int)p + 16);
                if ((kind != 2 && kind != 3) || value < 0 || addend < 0 || value > Long.MAX_VALUE - addend) {
                    throw invalid("ELF function relocation target");
                }
                // Local functions may be expressed as section-symbol + addend.
                // Resolve that address back to one named function, never a guessed
                // section identity or an arbitrary interior instruction address.
                int function = -1;
                for (long s = symbolOffset; s < symbolOffset + symbolSize; s += 24) {
                    int candidate = (int)s;
                    if ((data.get(candidate + 4) & 15) == 2
                            && Short.toUnsignedInt(data.getShort(candidate + 6)) == section
                            && data.getLong(candidate + 8) == value + addend) {
                        if (function != -1) throw invalid("ELF ambiguous function symbol");
                        function = candidate;
                    }
                }
                if (function == -1) throw invalid("ELF missing exact function symbol");
                long name = Integer.toUnsignedLong(data.getInt(function));
                if (name >= stringsSize) throw invalid("ELF function symbol name");
                int start = (int)(stringsOffset + name), end = start;
                while (end < stringsOffset + stringsSize && object[end] != 0) end++;
                if (end == start || end == stringsOffset + stringsSize) throw invalid("ELF unterminated function symbol");
                canonical.putLong((int)address, OptimizedTraceMetadata.linkageGuid(new String(object, start, end - start, StandardCharsets.UTF_8)));
            }
        }
        if (locations.size() != functions) throw invalid("ELF missing function relocations");
        byte[] result = object.clone();
        // Absolute function relocations need writable input storage for PIE.
        // The companion linker script places it in RELRO after relocation.
        // SHF_ALLOC | SHF_WRITE | SHF_GNU_RETAIN; no executable text relocations.
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putLong(root + 8, data.getLong(root + 8) | 3 | 0x200000);
        long hash = 0xcbf29ce484222325L;
        for (byte value : normalized) hash = (hash ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        return new Prepared(result, hash);
    }

    private static boolean name(byte[] bytes, int offset, String name) {
        int end = offset;
        while (end < offset + 16 && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII).equals(name);
    }

    private static void extent(byte[] object, long offset, long size) throws IOException {
        if (offset < 0 || size < 0 || offset > object.length || size > object.length - offset) throw invalid("section/relocation extent");
    }

    private static IOException invalid(String reason) { return new IOException("GC3 root object refused: " + reason); }
}
