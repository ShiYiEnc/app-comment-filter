package io.github.appcommentfilter.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DexMethodIndexTest {
    private static final String OWNER = "Lfixture/dex/FingerprintFixture;";
    private byte[] resource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/dex/" + name)) {
            assertNotNull(name, input); return input.readAllBytes();
        }
    }
    private byte[] fixture() throws IOException { return resource("fingerprint.dex"); }
    private DexMethodIndex.Member member(String name) throws IOException {
        return DexMethodIndex.read(fixture(), OWNER).stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private int codeOffset(String name) throws IOException {
        JsonArray methods = JsonParser.parseString(new String(resource("members.json"), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("methods");
        for (int i = 0; i < methods.size(); i++) {
            if (methods.get(i).getAsJsonObject().get("name").getAsString().equals(name)) return methods.get(i).getAsJsonObject().get("codeOffset").getAsInt();
        }
        throw new AssertionError(name);
    }
    private void put32(byte[] data, int at, int value) { for (int i = 0; i < 4; i++) data[at + i] = (byte) (value >>> (i * 8)); }
    private int u16(byte[] data, int at) { return (data[at] & 255) | ((data[at + 1] & 255) << 8); }
    private int u32(byte[] data, int at) { return u16(data, at) | (u16(data, at + 2) << 16); }
    private void rejects(byte[] data) throws IOException {
        try { DexMethodIndex.read(data, OWNER); fail("Malformed input accepted"); } catch (IOException expected) { }
    }
    private File zip(String[] names, byte[][] contents) throws IOException {
        File file = File.createTempFile("dex-index-", ".apk");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (int i = 0; i < names.length; i++) {
                output.putNextEntry(new ZipEntry(names[i])); output.write(contents[i]); output.closeEntry();
            }
        }
        return file;
    }

    @Test public void readsExactDeclaredMethodsAndInterfaceCallSignatures() throws IOException {
        List<DexMethodIndex.Member> methods = DexMethodIndex.read(fixture(), OWNER);
        assertEquals(15, methods.size());
        assertTrue(methods.stream().allMatch(m -> OWNER.equals(m.owner)));
        DexMethodIndex.Member parser = member("parser");
        assertEquals("(Lfixture/dex/FingerprintFixture$Response;)V", parser.descriptor);
        assertEquals(1, parser.flags & 1);
        assertEquals(Set.of("getKLocalComments", "getKGodCommentCount", "getKTransparentPayload", "getKCommentCorrelationId"),
                parser.calls.stream().map(c -> c.name).collect(Collectors.toSet()));
        assertTrue(parser.calls.stream().allMatch(c -> c.owner.equals("Lfixture/dex/FingerprintFixture$Response;")));
        assertEquals(Collections.emptyList(), DexMethodIndex.read(fixture(), "Lmissing/Class;"));
    }

    @Test public void inlineSwitchAndArrayPayloadsAreNeverScannedAsInstructions() throws IOException {
        assertTrue(member("arrayPayload").calls.isEmpty());
        assertTrue(member("packed").calls.isEmpty());
        assertTrue(member("sparse").calls.isEmpty());
        assertTrue(member("wideLiteral").calls.isEmpty());
        byte[] dex = fixture(); int code = codeOffset("arrayPayload");
        boolean invokeLikeLiteral = false;
        for (int at = code + 16; at < code + 16 + u32(dex, code + 12) * 2; at += 2) invokeLikeLiteral |= u16(dex, at) == 0x71;
        assertTrue("fixture must contain invoke-like literal data", invokeLikeLiteral);
    }

    @Test public void overloadedCallsKeepFullPrototypeIdentity() throws IOException {
        DexMethodIndex.Member method = member("overloadCalls");
        assertEquals(Set.of("(I)V", "(Ljava/lang/String;)V"), method.calls.stream().map(c -> c.descriptor).collect(Collectors.toSet()));
        assertEquals(2, method.calls.stream().filter(c -> c.name.equals("consume")).count());
    }

    @Test public void readsAndWritesAreDistinctFieldReferences() throws IOException {
        DexMethodIndex.FieldRef read = member("readGroup").fields.get(0), write = member("writeGroup").fields.get(0);
        assertEquals(OWNER, read.owner); assertEquals("group", read.name); assertEquals("Ljava/lang/Object;", read.descriptor);
        assertEquals(read.name, write.name); assertTrue(read.isRead()); assertFalse(write.isRead());
        DexMethodIndex.Member statics = member("readAndWriteStatic");
        assertTrue(statics.fields.stream().anyMatch(f -> f.name.equals("staticValue") && f.isRead()));
        assertTrue(statics.fields.stream().anyMatch(f -> f.name.equals("staticValue") && !f.isRead()));
        assertTrue(statics.fields.stream().anyMatch(f -> f.name.equals("value") && f.isRead()));
    }

    @Test public void polymorphicAndCustomInvokesUseDifferentReferenceTables() throws IOException {
        assertTrue(member("polymorphic").calls.stream().anyMatch(c -> c.owner.equals("Ljava/lang/invoke/MethodHandle;") && c.name.equals("invokeExact")));
        assertTrue(member("dynamic").hasDynamicCalls);
        assertTrue(member("dynamic").calls.isEmpty());
    }

    @Test public void numericMultidexOrderStopsBeforeUnrelatedBrokenLaterDex() throws IOException {
        File apk = zip(new String[]{"classes3.dex", "classes2.dex", "classes.dex"},
                new byte[][]{new byte[0], fixture(), resource("other.dex")});
        try { assertEquals(15, DexMethodIndex.read(apk, OWNER).size()); }
        finally { Files.deleteIfExists(apk.toPath()); }
    }

    @Test public void rawDexFilesAreSupportedAndResultsCannotBeMutated() throws IOException {
        File dex = File.createTempFile("dex-index-", ".dex"); Files.write(dex.toPath(), fixture());
        try {
            List<DexMethodIndex.Member> methods = DexMethodIndex.read(dex, OWNER);
            assertEquals(15, methods.size());
            try { methods.clear(); fail("mutable result"); } catch (UnsupportedOperationException expected) { }
            try { member("parser").calls.clear(); fail("mutable calls"); } catch (UnsupportedOperationException expected) { }
        } finally { Files.deleteIfExists(dex.toPath()); }
    }

    @Test public void invalidHeaderTablesAndIndicesAreRejected() throws IOException {
        rejects(new byte[111]); rejects(null);
        byte[] bad = fixture(); put32(bad, 32, bad.length + 1); rejects(bad);
        bad = fixture(); put32(bad, 40, 0x78563412); rejects(bad);
        bad = fixture(); put32(bad, 60, bad.length - 1); rejects(bad);
        bad = fixture(); put32(bad, 56, 1_000_001); rejects(bad);
        bad = fixture(); put32(bad, u32(bad, 100), 65535); rejects(bad);
    }

    @Test public void malformedUlebAndMethodReferencesAreRejected() throws IOException {
        // D8 sorts the nested Response interface before the enclosing fixture class.
        byte[] bad = fixture(); int classData = u32(bad, u32(bad, 100) + 32 + 24);
        Arrays.fill(bad, classData, classData + 5, (byte) 0xff); rejects(bad);
        bad = fixture(); int code = codeOffset("parser");
        bad[code + 18] = (byte) 0xff; bad[code + 19] = (byte) 0xff; rejects(bad);
    }

    @Test public void truncatedUnknownAndPayloadInstructionsAreRejected() throws IOException {
        byte[] bad = fixture(); int code = codeOffset("writeGroup");
        put32(bad, code + 12, 1); rejects(bad);
        bad = fixture(); bad[code + 16] = (byte) 0xe3; rejects(bad);
        bad = fixture(); code = codeOffset("arrayPayload");
        for (int at = code + 16; at < code + 16 + u32(bad, code + 12) * 2; at += 2) {
            if (u16(bad, at) == 0x300) { put32(bad, at + 4, -1); break; }
        }
        rejects(bad);
    }

    @Test public void oversizedZipDexIsRejectedBeforeInflation() throws IOException {
        File apk = File.createTempFile("dex-index-limit-", ".apk");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
                output.putNextEntry(new ZipEntry("classes.dex")); byte[] chunk = new byte[4096];
                for (int i = 0; i < DexMethodIndex.MAX_DEX_BYTES / chunk.length; i++) output.write(chunk);
                output.write(0); output.closeEntry();
            }
            try { DexMethodIndex.read(apk, OWNER); fail("oversized entry accepted"); } catch (IOException expected) { }
        } finally { Files.deleteIfExists(apk.toPath()); }
    }
}
