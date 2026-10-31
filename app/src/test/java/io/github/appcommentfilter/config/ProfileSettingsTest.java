package io.github.appcommentfilter.config;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the same persistence/migration policy used by the SharedPreferences adapter. */
public final class ProfileSettingsTest {
    @Test public void xhsLegacyProfileSwitchIsRemovedWithoutWritingOrDeletingRuleConditions() {
        MemoryBackend backend = new MemoryBackend();
        String legacy = "{\"schemaVersion\":1,\"enabled\":true,\"resolveProfileGender\":true,\"blacklist\":[{\"id\":\"old\",\"keywords\":[\"word\"],\"genders\":[\"F\"]}]}";
        String file = "rules." + HostApp.XIAOHONGSHU.packageName;
        backend.json.put(file, legacy); backend.revisions.put(file, 4L);
        ProfileSettings.Snapshot loaded = new ProfileSettings(backend).read(HostApp.XIAOHONGSHU);
        com.google.gson.JsonObject normalized = com.google.gson.JsonParser.parseString(loaded.json).getAsJsonObject();
        assertFalse(normalized.has("resolveProfileGender"));
        assertTrue(normalized.getAsJsonArray("blacklist").get(0).getAsJsonObject().has("genders"));
        assertTrue(normalized.getAsJsonArray("blacklist").get(0).getAsJsonObject().has("keywords"));
        assertEquals(legacy, backend.json.get(file)); assertEquals(4, loaded.revision); assertEquals(0, backend.writes);
    }

    @Test public void xhsSaveStripsObsoleteSwitchWhileOtherHostsKeepTheirExactJson() {
        MemoryBackend backend = new MemoryBackend(); ProfileSettings settings = new ProfileSettings(backend);
        String legacy = "{\"schemaVersion\":1,\"enabled\":true,\"resolveProfileGender\":true,\"whitelist\":[{\"id\":\"old\",\"genders\":[\"M\"]}]}";
        settings.save(HostApp.XIAOHONGSHU, legacy);
        String persisted = backend.json.get("rules." + HostApp.XIAOHONGSHU.packageName);
        assertFalse(persisted.contains("resolveProfileGender")); assertTrue(persisted.contains("genders"));
        settings.save(HostApp.BILIBILI, legacy);
        assertEquals(legacy, backend.json.get("rules." + HostApp.BILIBILI.packageName));
        assertEquals(1, settings.read(HostApp.XIAOHONGSHU).revision);
    }
    private static final class MemoryBackend implements ProfileSettings.Backend {
        final Map<String, String> json = new HashMap<>();
        final Map<String, Long> revisions = new HashMap<>();
        int writes;
        boolean fail;
        @Override public String readJson(String file) { return json.get(file); }
        @Override public long readRevision(String file) { return revisions.getOrDefault(file, 0L); }
        @Override public boolean write(String file, String value, long revision) {
            writes++;
            if (fail) return false;
            json.put(file, value); revisions.put(file, revision); return true;
        }
    }

    private static String rules(String keyword, boolean whitelist) {
        String rule = "{\"id\":\"rule\",\"keywords\":[\"" + keyword + "\"]}";
        return "{\"schemaVersion\":1,\"enabled\":true,\"ignoreKeywordCase\":false,\"mode\":\""
                + (whitelist ? "WHITELIST_ONLY" : "BLACKLIST") + "\",\"blacklist\":[" + rule
                + "],\"whitelist\":[" + rule.replace("\"rule\"", "\"white_rule\"") + "]}";
    }

    @Test public void saveAndReloadProfilesRetainsIndependentRulesAndRevisions() {
        MemoryBackend backend = new MemoryBackend();
        ProfileSettings writer = new ProfileSettings(backend);
        for (HostApp app : HostApp.values()) {
            writer.save(app, rules(app.name(), app.ordinal() % 2 == 0));
        }
        writer.save(HostApp.BILIBILI, rules("updated_bilibili", true));
        // Recreate the policy to ensure values come from storage, not a local cache.
        ProfileSettings reader = new ProfileSettings(backend);
        for (HostApp app : HostApp.values()) {
            ProfileSettings.Snapshot saved = reader.read(app);
            assertEquals(app == HostApp.BILIBILI ? rules("updated_bilibili", true)
                    : rules(app.name(), app.ordinal() % 2 == 0), saved.json);
            assertEquals(app == HostApp.BILIBILI ? 2 : 1, saved.revision);
        }
        assertNull(backend.readJson("rules"));
        assertEquals(6, backend.writes);
    }

    @Test public void oldRulesBelongOnlyToKuaishouAndAreNotWrittenWhenReading() {
        MemoryBackend backend = new MemoryBackend();
        String original = rules("legacy", true);
        backend.json.put("rules", original); backend.revisions.put("rules", 37L);
        ProfileSettings settings = new ProfileSettings(backend);
        for (HostApp app : HostApp.values()) {
            ProfileSettings.Snapshot saved = settings.read(app);
            if (app == HostApp.KUAISHOU) {
                assertEquals(original, saved.json); assertEquals(37, saved.revision);
            } else {
                assertEquals(RuleEngine.defaultJson(), saved.json);
                assertFalse(RuleEngine.compileJson(saved.json).enabled());
                assertEquals(0, saved.revision);
            }
        }
        assertEquals(0, backend.writes);
        assertEquals(1, backend.json.size());
    }

    @Test public void firstKuaishouSaveMigratesRevisionAndNewProfileTakesPrecedence() {
        MemoryBackend backend = new MemoryBackend();
        backend.json.put("rules", rules("legacy", false)); backend.revisions.put("rules", 37L);
        ProfileSettings settings = new ProfileSettings(backend);
        String updated = rules("migrated", true);
        settings.save(HostApp.KUAISHOU, updated);
        assertEquals(38, settings.read(HostApp.KUAISHOU).revision);
        assertEquals(updated, backend.readJson("rules." + HostApp.KUAISHOU.packageName));
        backend.json.put("rules", rules("old_file_changed", false)); backend.revisions.put("rules", 99L);
        assertEquals(updated, settings.read(HostApp.KUAISHOU).json);
        assertEquals(38, settings.read(HostApp.KUAISHOU).revision);
        assertFalse(RuleEngine.compileJson(settings.read(HostApp.BILIBILI).json).enabled());
    }

    @Test public void failedSaveKeepsPreviouslyPersistedProfileAndOtherApplications() {
        MemoryBackend backend = new MemoryBackend();
        ProfileSettings settings = new ProfileSettings(backend);
        String old = rules("old", false);
        settings.save(HostApp.DOUYIN, old);
        settings.save(HostApp.XIAOHONGSHU, rules("xhs", true));
        backend.fail = true;
        assertThrows(IllegalStateException.class, () -> settings.save(HostApp.DOUYIN, rules("new", true)));
        assertEquals(old, settings.read(HostApp.DOUYIN).json);
        assertEquals(1, settings.read(HostApp.DOUYIN).revision);
        assertEquals(rules("xhs", true), settings.read(HostApp.XIAOHONGSHU).json);
        assertEquals(1, settings.read(HostApp.XIAOHONGSHU).revision);
    }

    @Test public void malformedJsonDoesNotReachStorage() {
        MemoryBackend backend = new MemoryBackend();
        ProfileSettings settings = new ProfileSettings(backend);
        assertThrows(IllegalArgumentException.class, () -> settings.save(HostApp.BILIBILI_HD, "{"));
        assertEquals(0, backend.writes);
        assertEquals(RuleEngine.defaultJson(), settings.read(HostApp.BILIBILI_HD).json);
    }

    @Test public void exactUriRoutesToOnlyTheRequestedProfileAndLegacyMeansKuaishou() {
        assertSame(HostApp.KUAISHOU, ProfileSettings.appForUri(ProfileSettings.LEGACY_URI));
        for (HostApp target : HostApp.values()) {
            HostApp routed = ProfileSettings.appForUri(ProfileSettings.uri(target));
            assertSame(target, routed);
            for (HostApp caller : HostApp.values()) {
                assertEquals(caller == target, routed.allowsPackages(new String[]{caller.packageName}));
            }
        }
    }

    @Test public void malformedOrForeignUrisNeverFallBackToAnApplication() {
        String valid = ProfileSettings.uri(HostApp.DOUYIN);
        String[] invalid = {null, "", "content://other/rules/" + HostApp.DOUYIN.packageName,
                ProfileSettings.LEGACY_URI + "/unknown.app", valid + "/", valid + "?app=other",
                valid + "#other", ProfileSettings.LEGACY_URI + "/%63om.ss.android.ugc.aweme"};
        for (String uri : invalid) {
            assertThrows("URI: " + uri, IllegalArgumentException.class, () -> ProfileSettings.appForUri(uri));
        }
    }
}
