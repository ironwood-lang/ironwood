// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.backend;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/** Canonicalizes independent probe roots without changing nested records or code. */
final class SharedTraceOrder {
    private SharedTraceOrder() {}

    static byte[] canonicalize(byte[] object) throws IOException {
        if (object.length >= 4 && object[0] == 0x7f && object[1] == 'E' && object[2] == 'L' && object[3] == 'F') return elf(object);
        return macho(object);
    }

    private static byte[] macho(byte[] object) throws IOException {
        var data = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);
        if (object.length < 32 || data.getInt(0) != 0xfeedfacf || data.getInt(12) != 1) throw invalid("expected Mach-O 64-bit object");
        int commands = data.getInt(16), extent = data.getInt(20);
        if (commands < 0 || extent < 0 || extent > object.length - 32 || commands > extent / 8) throw invalid("load-command extent");
        int position = 32, sectionOffset = -1, sectionSize = 0;
        for (int i = 0; i < commands; i++) {
            if (32 + extent - position < 8) throw invalid("truncated load command");
            int command = data.getInt(position), size = data.getInt(position + 4);
            if (size < 8 || size > 32 + extent - position) throw invalid("load-command size");
            if (command == 0x19) {
                if (size < 72) throw invalid("segment header");
                int count = data.getInt(position + 64);
                if (count < 0 || 72L + 80L * count > size) throw invalid("section inventory");
                for (int j = 0; j < count; j++) {
                    int section = position + 72 + j * 80;
                    if (!name(object, section, "__probes") || !name(object, section + 16, "__PSEUDO_PROBE")) continue;
                    if (sectionOffset != -1 || data.getInt(section + 60) != 0) throw invalid("duplicate or relocated probe section");
                    long length = data.getLong(section + 40), offset = Integer.toUnsignedLong(data.getInt(section + 48));
                    if (length < 0 || offset < 32L + extent || offset > object.length || length > object.length - offset) throw invalid("probe section extent");
                    sectionOffset = (int)offset; sectionSize = (int)length;
                }
            }
            position += size;
        }
        if (position != 32 + extent) throw invalid("unused load-command bytes");
        if (sectionOffset == -1) return object;
        return ordered(object, sectionOffset, sectionSize);
    }

    private static byte[] elf(byte[] object) throws IOException {
        var data = ByteBuffer.wrap(object).order(ByteOrder.LITTLE_ENDIAN);
        if (object.length < 64 || object[4] != 2 || object[5] != 1 || data.getShort(16) != 1) throw invalid("expected little-endian ELF64 object");
        long table = data.getLong(40);
        int stride = Short.toUnsignedInt(data.getShort(58)), count = Short.toUnsignedInt(data.getShort(60)), namesIndex = Short.toUnsignedInt(data.getShort(62));
        if (stride != 64 || count == 0 || namesIndex >= count || table < 64 || table > object.length
                || (long)stride * count > object.length - table) throw invalid("ELF section table");
        int namesHeader = (int)table + namesIndex * stride;
        long namesOffset = data.getLong(namesHeader + 24), namesLength = data.getLong(namesHeader + 32);
        extent(object, namesOffset, namesLength);
        byte[] result = object;
        for (int i = 0; i < count; i++) {
            int section = (int)table + i * stride;
            long name = Integer.toUnsignedLong(data.getInt(section));
            if (name >= namesLength) throw invalid("ELF section name");
            int start = (int)(namesOffset + name), end = start;
            while (end < namesOffset + namesLength && object[end] != 0) end++;
            if (end == namesOffset + namesLength) throw invalid("unterminated ELF section name");
            if (!new String(object, start, end - start, StandardCharsets.US_ASCII).equals(".pseudo_probe")) continue;
            if (data.getInt(section + 4) != 1) throw invalid("ELF probe section type");
            long offset = data.getLong(section + 24), size = data.getLong(section + 32);
            extent(object, offset, size);
            for (int j = 0; j < count; j++) {
                int relocation = (int)table + j * stride, type = data.getInt(relocation + 4);
                if ((type == 4 || type == 9) && data.getInt(relocation + 44) == i && data.getLong(relocation + 32) != 0) {
                    throw invalid("relocated ELF probe section");
                }
            }
            result = ordered(result, (int)offset, (int)size);
        }
        return result;
    }

    private static void extent(byte[] object, long offset, long size) throws IOException {
        if (offset < 64 || offset > object.length || size < 0 || size > object.length - offset) throw invalid("ELF section extent");
    }

    private static byte[] ordered(byte[] object, int sectionOffset, int sectionSize) throws IOException {
        byte[] probes = Arrays.copyOfRange(object, sectionOffset, sectionOffset + sectionSize);
        var cursor = new Cursor(probes); var groups = new ArrayList<Group>();
        while (cursor.position < probes.length) {
            int start = cursor.position;
            long guid = cursor.group();
            groups.add(new Group(guid, Arrays.copyOfRange(probes, start, cursor.position)));
        }
        groups.sort((a, b) -> {
            int order = Long.compareUnsigned(a.guid(), b.guid());
            return order != 0 ? order : Arrays.compareUnsigned(a.bytes(), b.bytes());
        });
        byte[] result = object.clone(); int position = sectionOffset;
        for (var group : groups) { System.arraycopy(group.bytes(), 0, result, position, group.bytes().length); position += group.bytes().length; }
        return result;
    }

    private static boolean name(byte[] bytes, int offset, String expected) {
        byte[] name = expected.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 16; i++) if (bytes[offset + i] != (i < name.length ? name[i] : 0)) return false;
        return true;
    }

    private record Group(long guid, byte[] bytes) {}

    /** Same stream structure consumed by trace_decode_node; root groups reset their address base. */
    private static final class Cursor {
        private final byte[] bytes;
        private int position;
        private Cursor(byte[] bytes) { this.bytes = bytes; }
        private int byteValue() throws IOException {
            if (position == bytes.length) throw invalid("truncated probe record");
            return Byte.toUnsignedInt(bytes[position++]);
        }
        private long fixed() throws IOException {
            long value = 0;
            for (int i = 0; i < 8; i++) value |= (long)byteValue() << (i * 8);
            return value;
        }
        private long leb(boolean signed) throws IOException {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                int b = byteValue(), part = b & 127;
                if (i == 9 && (signed ? part != 0 && part != 127 : part > 1)) throw invalid("probe integer overflow");
                value |= (long)part << (i * 7);
                if ((b & 128) == 0) return value;
            }
            throw invalid("unterminated probe integer");
        }
        private long group() throws IOException {
            long root = 0; int pending = 1; boolean first = true;
            while (pending > 0) {
                if (!first) leb(false);
                long guid = fixed(); if (first) root = guid;
                first = false; pending--;
                long probes = leb(false), children = leb(false);
                if (probes < 0 || probes > (bytes.length - position) / 3
                        || children < 0 || children > (bytes.length - position) / 11) throw invalid("probe node counts");
                for (long i = 0; i < probes; i++) {
                    long index = leb(false);
                    if (index < 0 || index > 0xffffffffL) throw invalid("probe index overflow");
                    int flags = byteValue();
                    if ((flags & 128) != 0) leb(true);
                    else {
                        // Absolute data must be a sentinel GUID, never a relocatable code address.
                        if ((flags & 32) == 0) throw invalid("absolute non-sentinel probe");
                        fixed();
                    }
                    if ((flags & 64) != 0) leb(false);
                }
                long next = (long)pending + children;
                if (next > (bytes.length - position) / 11) throw invalid("truncated probe descendants");
                pending = (int)next;
            }
            return root;
        }
    }

    private static IOException invalid(String message) { return new IOException("cannot canonicalize shared traces: " + message); }
}
