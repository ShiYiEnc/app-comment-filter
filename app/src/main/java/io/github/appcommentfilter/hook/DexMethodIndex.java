package io.github.appcommentfilter.hook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded, execution-free declaration/call index for one exact DEX class.
 * Instruction widths and payloads follow the AOSP Dalvik bytecode specification.
 * This is a fingerprint reader, not a verifier, decompiler, or runtime call graph.
 */
public final class DexMethodIndex {
    public static final int MAX_DEX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_ITEMS = 1_000_000, MAX_MEMBERS = 65_536;
    private static final int MAX_REFERENCES = 200_000, MAX_CODE_UNITS = 8_000_000;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes([0-9]*)\\.dex");
    private DexMethodIndex() { }

    public static final class Call {
        public final String owner, name, descriptor;
        public Call(String owner, String name, String descriptor) { this.owner = owner; this.name = name; this.descriptor = descriptor; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Call)) return false;
            Call value = (Call) other;
            return owner.equals(value.owner) && name.equals(value.name) && descriptor.equals(value.descriptor);
        }
        @Override public int hashCode() { return Objects.hash(owner, name, descriptor); }
        @Override public String toString() { return owner + "->" + name + descriptor; }
    }

    public static final class FieldRef {
        public final String owner, name, descriptor;
        public final int opcode;
        public FieldRef(String owner, String name, String descriptor, int opcode) {
            this.owner = owner; this.name = name; this.descriptor = descriptor; this.opcode = opcode;
        }
        public boolean isRead() { return (opcode >= 0x52 && opcode <= 0x58) || (opcode >= 0x60 && opcode <= 0x66); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FieldRef)) return false;
            FieldRef value = (FieldRef) other;
            return opcode == value.opcode && owner.equals(value.owner) && name.equals(value.name) && descriptor.equals(value.descriptor);
        }
        @Override public int hashCode() { return Objects.hash(owner, name, descriptor, opcode); }
        @Override public String toString() { return owner + "->" + name + ":" + descriptor; }
    }

    public static final class Member {
        public final String owner, name, descriptor;
        public final int flags;
        public final List<Call> calls;
        public final List<FieldRef> fields;
        /** invoke-custom is retained as an unknown, never mistaken for a method_id. */
        public final boolean hasDynamicCalls;
        public Member(String owner, String name, String descriptor, int flags, List<Call> calls, List<FieldRef> fields) {
            this.owner = owner; this.name = name; this.descriptor = descriptor; this.flags = flags;
            this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields)); this.hasDynamicCalls = false;
        }
        Member(Call identity, int flags, Set<Call> calls, Set<FieldRef> fields, boolean dynamic) {
            this.owner = identity.owner; this.name = identity.name; this.descriptor = identity.descriptor; this.flags = flags;
            this.calls = Collections.unmodifiableList(new ArrayList<>(calls));
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields)); this.hasDynamicCalls = dynamic;
        }
    }

    public static List<Member> read(File artifact, String ownerDescriptor) throws IOException {
        validateOwner(ownerDescriptor);
        try (InputStream input = new FileInputStream(artifact)) {
            byte[] magic = new byte[4]; int count = input.read(magic);
            if (count == 4 && magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n') {
                try (InputStream dex = new FileInputStream(artifact)) {
                    return read(readBytes(dex, artifact.length()), ownerDescriptor);
                }
            }
        }
        try (ZipFile zip = new ZipFile(artifact)) {
            List<ZipEntry> entries = new ArrayList<>(); Set<Integer> ordinals = new HashSet<>();
            Enumeration<? extends ZipEntry> source = zip.entries(); int inspected = 0;
            while (source.hasMoreElements()) {
                if (++inspected > 200_000) throw invalid("Too many archive entries");
                ZipEntry entry = source.nextElement(); Matcher match = DEX_ENTRY.matcher(entry.getName());
                if (entry.isDirectory() || !match.matches()) continue;
                int ordinal = ordinal(entry.getName());
                if (!ordinals.add(ordinal) || entries.size() >= 128) throw invalid("Duplicate or excessive DEX entries");
                entries.add(entry);
            }
            entries.sort(Comparator.comparingInt(entry -> {
                String digits = entry.getName().substring(7, entry.getName().length() - 4);
                return digits.isEmpty() ? 1 : Integer.parseInt(digits);
            }));
            for (ZipEntry entry : entries) {
                try (InputStream input = zip.getInputStream(entry)) {
                    Parser parser = new Parser(readBytes(input, entry.getSize()));
                    List<Member> result = parser.find(ownerDescriptor);
                    if (result != null) return result;
                }
            }
            return Collections.emptyList();
        }
    }

    public static List<Member> read(byte[] dex, String ownerDescriptor) throws IOException {
        validateOwner(ownerDescriptor);
        List<Member> result = new Parser(dex).find(ownerDescriptor);
        return result == null ? Collections.emptyList() : result;
    }

    private static void validateOwner(String owner) throws IOException {
        if (owner == null || owner.length() < 3 || owner.length() > 4096 || owner.charAt(0) != 'L'
                || owner.charAt(owner.length() - 1) != ';' || owner.indexOf('\0') >= 0) throw invalid("Expected exact class descriptor");
    }
    private static int ordinal(String name) throws IOException {
        String digits = name.substring(7, name.length() - 4);
        if (digits.isEmpty()) return 1;
        try {
            int number = Integer.parseInt(digits);
            if (number < 2 || number > 10000 || digits.charAt(0) == '0') throw invalid("Invalid DEX ordinal");
            return number;
        } catch (NumberFormatException error) { throw invalid("Invalid DEX ordinal"); }
    }
    private static byte[] readBytes(InputStream input, long size) throws IOException {
        if (size < 112 || size > MAX_DEX_BYTES) throw invalid("DEX exceeds byte limit or has no header");
        byte[] result = new byte[(int) size]; int at = 0;
        while (at < result.length) {
            int count = input.read(result, at, result.length - at);
            if (count < 0) throw invalid("Truncated DEX entry");
            if (count == 0) { int next = input.read(); if (next < 0) throw invalid("Truncated DEX entry"); result[at++] = (byte) next; }
            else at += count;
        }
        if (input.read() != -1) throw invalid("DEX entry length mismatch");
        return result;
    }
    private static IOException invalid(String message) { return new IOException(message); }

    private static final class Cursor { int at; Cursor(int at) { this.at = at; } }
    private static final class Parser {
        final byte[] data;
        final int strings, stringsOff, types, typesOff, protos, protosOff, methods, methodsOff;
        final int fields, fieldsOff, classes, classesOff, dataOff, dataEnd, version;
        final Map<Integer, String> stringCache = new HashMap<>();
        int cachedChars, references, codeUnits, identityChars;

        Parser(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length < 112 || bytes.length > MAX_DEX_BYTES) throw invalid("Invalid DEX size");
            data = bytes;
            if (data[0] != 'd' || data[1] != 'e' || data[2] != 'x' || data[3] != '\n' || data[7] != 0
                    || data[4] != '0' || data[5] < '3' || data[5] > '4' || data[6] < '0' || data[6] > '9') throw invalid("Invalid DEX magic");
            version = (data[5] - '0') * 10 + data[6] - '0';
            if (version < 35 || version == 36 || version > 40) throw invalid("Unsupported DEX version");
            if (u32(32) != data.length || u32(36) != 112 || u32(40) != 0x12345678) throw invalid("Invalid DEX header");
            strings = u32(56); stringsOff = table(strings, u32(60), 4, MAX_ITEMS);
            types = u32(64); typesOff = table(types, u32(68), 4, MAX_MEMBERS);
            protos = u32(72); protosOff = table(protos, u32(76), 12, MAX_MEMBERS);
            fields = u32(80); fieldsOff = table(fields, u32(84), 8, MAX_MEMBERS);
            methods = u32(88); methodsOff = table(methods, u32(92), 8, MAX_MEMBERS);
            classes = u32(96); classesOff = table(classes, u32(100), 32, MAX_MEMBERS);
            dataOff = u32(108); int size = u32(104); range(dataOff, size);
            if (size > 0 && (dataOff < 112 || (dataOff & 3) != 0)) throw invalid("Invalid DEX data section");
            dataEnd = dataOff + size;
        }

        int table(int count, int offset, int width, int max) throws IOException {
            if (count > max || (count == 0 && offset != 0) || (count > 0 && (offset < 112 || (offset & 3) != 0))) throw invalid("Invalid DEX table");
            range(offset, (long) count * width); return offset;
        }
        void range(int offset, long length) throws IOException {
            if (offset < 0 || length < 0 || (long) offset + length > data.length) throw invalid("DEX offset out of bounds");
        }
        void inData(int offset, long length) throws IOException {
            range(offset, length);
            if (offset < dataOff || (long) offset + length > dataEnd) throw invalid("Item outside DEX data section");
        }
        int u16(int offset) throws IOException { range(offset, 2); return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }
        int u32(int offset) throws IOException {
            long value = unsigned32(offset);
            if (value > Integer.MAX_VALUE) throw invalid("DEX integer exceeds limit"); return (int) value;
        }
        long unsigned32(int offset) throws IOException {
            range(offset, 4);
            return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8) | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
        }
        int uleb(Cursor cursor) throws IOException {
            int value = 0;
            for (int index = 0; index < 5; index++) {
                inData(cursor.at, 1); int part = data[cursor.at++] & 255;
                if (index == 4 && (part & 0xf8) != 0) throw invalid("Oversized ULEB128");
                value |= (part & 127) << (index * 7);
                if ((part & 128) == 0) return value;
            }
            throw invalid("Unterminated ULEB128");
        }
        int index(int index, int count) throws IOException {
            if (index < 0 || index >= count) throw invalid("DEX index out of bounds"); return index;
        }
        String string(int id) throws IOException {
            index(id, strings); String hit = stringCache.get(id); if (hit != null) return hit;
            Cursor cursor = new Cursor(u32(stringsOff + id * 4)); int length = uleb(cursor);
            if (length > 8192) throw invalid("DEX identity string exceeds limit");
            StringBuilder text = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                inData(cursor.at, 1); int first = data[cursor.at++] & 255; int character;
                if (first > 0 && first < 128) character = first;
                else if ((first & 0xe0) == 0xc0) {
                    int second = continuation(cursor); character = ((first & 31) << 6) | second;
                    if (character != 0 && character < 128) throw invalid("Noncanonical MUTF-8");
                } else if ((first & 0xf0) == 0xe0) {
                    int second = continuation(cursor), third = continuation(cursor);
                    character = ((first & 15) << 12) | (second << 6) | third;
                    if (character < 2048) throw invalid("Noncanonical MUTF-8");
                } else throw invalid("Invalid MUTF-8 identity");
                text.append((char) character);
            }
            inData(cursor.at, 1);
            if (data[cursor.at] != 0) throw invalid("MUTF-8 length mismatch");
            String value = text.toString();
            if (stringCache.size() < 32768 && cachedChars + value.length() <= 1_000_000) {
                stringCache.put(id, value); cachedChars += value.length();
            }
            return value;
        }
        int continuation(Cursor cursor) throws IOException {
            inData(cursor.at, 1); int value = data[cursor.at++] & 255;
            if ((value & 0xc0) != 0x80) throw invalid("Invalid MUTF-8 continuation"); return value & 63;
        }
        String type(int id) throws IOException { index(id, types); return string(u32(typesOff + id * 4)); }
        String proto(int id) throws IOException {
            index(id, protos); int at = protosOff + id * 12;
            String result = type(u32(at + 4)); StringBuilder signature = new StringBuilder("(");
            int parameters = u32(at + 8);
            if (parameters != 0) {
                inData(parameters, 4); if ((parameters & 3) != 0) throw invalid("Misaligned parameter list");
                int count = u32(parameters); if (count > 1024) throw invalid("Too many parameters");
                inData(parameters + 4, (long) count * 2);
                for (int i = 0; i < count; i++) { signature.append(type(u16(parameters + 4 + i * 2))); if (signature.length() > 8192) throw invalid("Descriptor exceeds limit"); }
            }
            return signature.append(')').append(result).toString();
        }
        Call method(int id) throws IOException {
            index(id, methods); int at = methodsOff + id * 8;
            String owner = type(u16(at)), name = string(u32(at + 4)), descriptor = proto(u16(at + 2));
            identityBudget(owner, name, descriptor); return new Call(owner, name, descriptor);
        }
        FieldRef field(int id, int opcode) throws IOException {
            index(id, fields); int at = fieldsOff + id * 8;
            String owner = type(u16(at)), name = string(u32(at + 4)), descriptor = type(u16(at + 2));
            identityBudget(owner, name, descriptor); return new FieldRef(owner, name, descriptor, opcode);
        }
        void identityBudget(String owner, String name, String descriptor) throws IOException {
            int count = owner.length() + name.length() + descriptor.length();
            if (count > 8_000_000 - identityChars) throw invalid("Identity character budget exceeded");
            identityChars += count;
        }

        List<Member> find(String owner) throws IOException {
            for (int i = 0; i < classes; i++) {
                int at = classesOff + i * 32;
                if (!owner.equals(type(u32(at)))) continue;
                int classData = u32(at + 24);
                if (classData == 0) return Collections.emptyList();
                Cursor cursor = new Cursor(classData);
                int staticFields = uleb(cursor), instanceFields = uleb(cursor), direct = uleb(cursor), virtual = uleb(cursor);
                if ((long) staticFields + instanceFields > MAX_MEMBERS || (long) direct + virtual > MAX_MEMBERS) throw invalid("Excessive class members");
                skipFields(cursor, staticFields); skipFields(cursor, instanceFields);
                List<Member> result = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
                readMethods(cursor, direct, owner, result, seen); readMethods(cursor, virtual, owner, result, seen);
                return Collections.unmodifiableList(result);
            }
            return null;
        }
        void skipFields(Cursor cursor, int count) throws IOException {
            long current = 0;
            for (int i = 0; i < count; i++) { current += uleb(cursor); if (current >= fields) throw invalid("Class field index out of bounds"); uleb(cursor); }
        }
        void readMethods(Cursor cursor, int count, String owner, List<Member> output, Set<Integer> seen) throws IOException {
            long current = 0;
            for (int i = 0; i < count; i++) {
                current += uleb(cursor); if (current >= methods) throw invalid("Class method index out of bounds");
                int flags = uleb(cursor), code = uleb(cursor); Call identity = method((int) current);
                if (!identity.owner.equals(owner) || !seen.add((int) current)) throw invalid("Invalid class method owner or duplicate");
                Set<Call> calls = new LinkedHashSet<>(); Set<FieldRef> fields = new LinkedHashSet<>();
                boolean dynamic = code != 0 && instructions(code, calls, fields);
                output.add(new Member(identity, flags, calls, fields, dynamic));
            }
        }
        boolean instructions(int code, Set<Call> calls, Set<FieldRef> fields) throws IOException {
            inData(code, 16); if ((code & 3) != 0) throw invalid("Misaligned code_item");
            int size = u32(code + 12); inData(code + 16, (long) size * 2);
            if (size > MAX_CODE_UNITS - codeUnits) throw invalid("Code budget exceeded"); codeUnits += size;
            int tries = u16(code + 6);
            if (tries > 0) inData(code + 16 + size * 2, (size % 2) * 2L + tries * 8L);
            boolean dynamic = false;
            for (int pc = 0; pc < size;) {
                int at = code + 16 + pc * 2, unit = u16(at), op = unit & 255;
                int width = width(op);
                if (op == 0 && unit != 0) {
                    if ((pc & 1) != 0) throw invalid("Misaligned instruction payload");
                    if (size - pc < 2) throw invalid("Truncated payload header");
                    long payload;
                    if (unit == 0x100) payload = 4L + u16(at + 2) * 2L;
                    else if (unit == 0x200) payload = 2L + u16(at + 2) * 4L;
                    else if (unit == 0x300) {
                        if (size - pc < 4) throw invalid("Truncated array payload header");
                        int elementWidth = u16(at + 2);
                        if (elementWidth != 1 && elementWidth != 2 && elementWidth != 4 && elementWidth != 8) throw invalid("Invalid array element width");
                        payload = 4L + (unsigned32(at + 4) * elementWidth + 1) / 2;
                    } else throw invalid("Unknown instruction payload");
                    if (payload > size - pc) throw invalid("Truncated instruction payload"); width = (int) payload;
                }
                if (width == 0 || width > size - pc) throw invalid("Unsupported or truncated instruction");
                if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xfa || op == 0xfb) {
                    if (op >= 0xfa) { if (version < 38) throw invalid("Opcode requires DEX 038"); index(u16(at + 6), protos); }
                    calls.add(method(u16(at + 2)));
                    if (++references > MAX_REFERENCES) throw invalid("Reference budget exceeded");
                } else if (op >= 0x52 && op <= 0x6d) {
                    fields.add(field(u16(at + 2), op));
                    if (++references > MAX_REFERENCES) throw invalid("Reference budget exceeded");
                } else if (op == 0xfc || op == 0xfd) {
                    if (version < 38) throw invalid("Opcode requires DEX 038"); dynamic = true;
                } else if (op == 0xfe || op == 0xff) {
                    if (version < 39) throw invalid("Opcode requires DEX 039");
                    if (op == 0xff) index(u16(at + 2), protos);
                }
                pc += width;
            }
            return dynamic;
        }
        int width(int op) {
            if (op == 0 || op == 1 || op == 4 || op == 7 || (op >= 0x0a && op <= 0x12)
                    || op == 0x1d || op == 0x1e || op == 0x21 || op == 0x27 || op == 0x28
                    || (op >= 0x7b && op <= 0x8f) || (op >= 0xb0 && op <= 0xcf)) return 1;
            if (op == 2 || op == 5 || op == 8 || op == 0x13 || op == 0x15 || op == 0x16
                    || op == 0x19 || op == 0x1a || op == 0x1c || op == 0x1f || op == 0x20
                    || op == 0x22 || op == 0x23 || op == 0x29 || (op >= 0x2d && op <= 0x3d)
                    || (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf)
                    || (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) return 2;
            if (op == 3 || op == 6 || op == 9 || op == 0x14 || op == 0x17 || op == 0x1b
                    || (op >= 0x24 && op <= 0x26) || (op >= 0x2a && op <= 0x2c)
                    || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)
                    || op == 0xfc || op == 0xfd) return 3;
            if (op == 0xfa || op == 0xfb) return 4;
            return op == 0x18 ? 5 : 0;
        }
    }
}
