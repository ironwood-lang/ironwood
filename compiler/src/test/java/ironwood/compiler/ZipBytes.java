// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A byte-exact ZIP builder for the M5 archive corpus. Its defaults spell
 * entries exactly as Java's ZipOutputStream writes them with time 0 (a STORED
 * entry with explicit size and CRC, or a DEFLATED entry with a data
 * descriptor), and each field can be overridden to build a variant. It also
 * parses a central directory for metadata comparisons.
 */
final class ZipBytes {
    static final class Entry {
        byte[] name;
        byte[] data;            // uncompressed bytes
        byte[] payload;         // bytes written after the local header
        int method;
        int flags = 0x800;
        int version = 10;
        int madeBy = 10;
        int time = 0;
        int date = 0x21;
        long crc;
        long csize;
        long usize;
        byte[] localExtra = UT;
        byte[] centralExtra = UT;
        byte[] comment = new byte[0];
        long externalAttributes;
        boolean descriptor;
        boolean descriptorSignature = true;
        boolean zip64Descriptor;
        // Local header overrides; -1 keeps the computed value.
        long localCrc = -1, localCsize = -1, localUsize = -1;
        int localMethod = -1, localFlags = -1;
        byte[] localName;
        long centralOffsetDelta;
        boolean zip64Local, zip64Central;
    }

    static final byte[] UT = {0x55, 0x54, 0x05, 0x00, 0x01, 0, 0, 0, 0};

    final List<Entry> entries = new ArrayList<>();
    byte[] prefix = new byte[0];
    byte[] trailer = new byte[0];
    byte[] archiveComment = new byte[0];
    boolean zip64End;
    int countOverride = -1;
    long centralOffsetOverride = -1;
    boolean omitCentral;

    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    static long crc(byte[] data) { CRC32 crc = new CRC32(); crc.update(data); return crc.getValue(); }

    static byte[] deflate(byte[] data, int level, int strategy) {
        Deflater deflater = new Deflater(level, true);
        deflater.setStrategy(strategy);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    Entry stored(String name, byte[] data) {
        Entry entry = new Entry();
        entry.name = utf8(name);
        entry.data = data;
        entry.payload = data;
        entry.method = 0;
        entry.crc = crc(data);
        entry.csize = data.length;
        entry.usize = data.length;
        entries.add(entry);
        return entry;
    }

    /** A DEFLATED entry as Java's ZipOutputStream writes it: data descriptor, version 20. */
    Entry deflated(String name, byte[] data) {
        Entry entry = stored(name, data);
        entry.payload = deflate(data, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
        entry.method = 8;
        entry.flags = 0x808;
        entry.version = 20;
        entry.madeBy = 20;
        entry.csize = entry.payload.length;
        entry.descriptor = true;
        return entry;
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(prefix);
        List<Long> offsets = new ArrayList<>();
        for (Entry entry : entries) {
            offsets.add((long) out.size());
            int flags = entry.localFlags >= 0 ? entry.localFlags : entry.flags;
            int method = entry.localMethod >= 0 ? entry.localMethod : entry.method;
            long crc = entry.localCrc >= 0 ? entry.localCrc : entry.descriptor ? 0 : entry.crc;
            long csize = entry.localCsize >= 0 ? entry.localCsize : entry.descriptor ? 0 : entry.csize;
            long usize = entry.localUsize >= 0 ? entry.localUsize : entry.descriptor ? 0 : entry.usize;
            byte[] extra = entry.localExtra;
            if (entry.zip64Local) {
                extra = concat(extra, zip64Extra(entry.usize, entry.csize, -1));
                csize = 0xFFFFFFFFL;
                usize = 0xFFFFFFFFL;
            }
            byte[] name = entry.localName != null ? entry.localName : entry.name;
            int32(out, 0x04034b50);
            int16(out, entry.version);
            int16(out, flags);
            int16(out, method);
            int16(out, entry.time);
            int16(out, entry.date);
            int32(out, crc);
            int32(out, csize);
            int32(out, usize);
            int16(out, name.length);
            int16(out, extra.length);
            out.writeBytes(name);
            out.writeBytes(extra);
            out.writeBytes(entry.payload);
            if (entry.descriptor) {
                if (entry.descriptorSignature) int32(out, 0x08074b50);
                int32(out, entry.crc);
                if (entry.zip64Descriptor) {
                    int64(out, entry.csize);
                    int64(out, entry.usize);
                } else {
                    int32(out, entry.csize);
                    int32(out, entry.usize);
                }
            }
        }
        long centralStart = out.size();
        if (!omitCentral) {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                long offset = offsets.get(index) + entry.centralOffsetDelta - prefix.length;
                long csize = entry.csize;
                long usize = entry.usize;
                long localOffset = offset;
                byte[] extra = entry.centralExtra;
                if (entry.zip64Central) {
                    extra = concat(extra, zip64Extra(usize, csize, offset));
                    csize = 0xFFFFFFFFL;
                    usize = 0xFFFFFFFFL;
                    localOffset = 0xFFFFFFFFL;
                }
                int32(out, 0x02014b50);
                int16(out, entry.madeBy);
                int16(out, entry.version);
                int16(out, entry.flags);
                int16(out, entry.method);
                int16(out, entry.time);
                int16(out, entry.date);
                int32(out, entry.crc);
                int32(out, csize);
                int32(out, usize);
                int16(out, entry.name.length);
                int16(out, extra.length);
                int16(out, entry.comment.length);
                int16(out, 0);
                int16(out, 0);
                int32(out, entry.externalAttributes);
                int32(out, localOffset);
                out.writeBytes(entry.name);
                out.writeBytes(extra);
                out.writeBytes(entry.comment);
            }
        }
        long centralSize = out.size() - centralStart;
        long centralOffset = centralOffsetOverride >= 0 ? centralOffsetOverride : centralStart - prefix.length;
        int count = countOverride >= 0 ? countOverride : entries.size();
        if (zip64End) {
            long recordOffset = out.size() - prefix.length;
            int32(out, 0x06064b50);
            int64(out, 44);
            int16(out, 45);
            int16(out, 45);
            int32(out, 0);
            int32(out, 0);
            int64(out, count);
            int64(out, count);
            int64(out, centralSize);
            int64(out, centralOffset);
            int32(out, 0x07064b50);
            int32(out, 0);
            int64(out, recordOffset);
            int32(out, 1);
        }
        int32(out, 0x06054b50);
        int16(out, 0);
        int16(out, 0);
        int16(out, zip64End ? 0xFFFF : count);
        int16(out, zip64End ? 0xFFFF : count);
        int32(out, centralSize);
        int32(out, zip64End ? 0xFFFFFFFFL : centralOffset);
        int16(out, archiveComment.length);
        out.writeBytes(archiveComment);
        out.writeBytes(trailer);
        return out.toByteArray();
    }

    static byte[] zip64Extra(long usize, long csize, long offset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int16(out, 1);
        int16(out, offset >= 0 ? 24 : 16);
        int64(out, usize);
        int64(out, csize);
        if (offset >= 0) int64(out, offset);
        return out.toByteArray();
    }

    static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** One central-directory record with its decoded data. */
    record Record(String name, int madeBy, int version, int flags, int method, int time, int date, long crc,
                  long csize, long usize, String extra, String localExtra, long externalAttributes, byte[] data) {
        String metadata() {
            return name + " made=" + madeBy + " version=" + version + " flags=" + flags + " method=" + method
                    + " time=" + time + " date=" + date + " crc=" + crc + " size=" + usize + " extra=" + extra
                    + " local=" + localExtra + " attributes=" + externalAttributes;
        }
    }

    /** Parses a well-formed archive without ZIP64 or a prefix, inflating DEFLATED entries. */
    static List<Record> parse(byte[] bytes) throws java.util.zip.DataFormatException {
        int end = bytes.length - 22;
        while (end >= 0 && int32(bytes, end) != 0x06054b50) end--;
        int count = int16(bytes, end + 10);
        int position = (int) int32(bytes, end + 16);
        List<Record> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int nameLength = int16(bytes, position + 28);
            int extraLength = int16(bytes, position + 30);
            int commentLength = int16(bytes, position + 32);
            int local = (int) int32(bytes, position + 42);
            String name = new String(bytes, position + 46, nameLength, StandardCharsets.UTF_8);
            String extra = java.util.HexFormat.of().formatHex(bytes, position + 46 + nameLength,
                    position + 46 + nameLength + extraLength);
            int localName = int16(bytes, local + 26);
            int localExtraLength = int16(bytes, local + 28);
            String localExtra = java.util.HexFormat.of().formatHex(bytes, local + 30 + localName,
                    local + 30 + localName + localExtraLength);
            int method = int16(bytes, position + 10);
            long csize = int32(bytes, position + 20);
            long usize = int32(bytes, position + 24);
            int data = local + 30 + localName + localExtraLength;
            byte[] content;
            if (method == 0) {
                content = java.util.Arrays.copyOfRange(bytes, data, data + (int) csize);
            } else {
                java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
                inflater.setInput(bytes, data, (int) csize);
                content = new byte[(int) usize];
                int produced = 0;
                while (produced < content.length && !inflater.finished()) {
                    produced += inflater.inflate(content, produced, content.length - produced);
                }
                inflater.end();
            }
            records.add(new Record(name, int16(bytes, position + 4), int16(bytes, position + 6),
                    int16(bytes, position + 8), method, int16(bytes, position + 12), int16(bytes, position + 14),
                    int32(bytes, position + 16), csize, usize, extra, localExtra, int32(bytes, position + 38), content));
            position += 46 + nameLength + extraLength + commentLength;
        }
        return records;
    }

    static int int16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | (bytes[offset + 1] & 0xFF) << 8;
    }

    static long int32(byte[] bytes, int offset) {
        return int16(bytes, offset) | (long) int16(bytes, offset + 2) << 16;
    }

    static void int16(ByteArrayOutputStream out, long value) {
        out.write((int) value & 0xFF);
        out.write((int) (value >>> 8) & 0xFF);
    }

    static void int32(ByteArrayOutputStream out, long value) {
        int16(out, value & 0xFFFF);
        int16(out, (value >>> 16) & 0xFFFF);
    }

    static void int64(ByteArrayOutputStream out, long value) {
        int32(out, value & 0xFFFFFFFFL);
        int32(out, value >>> 32);
    }
}
