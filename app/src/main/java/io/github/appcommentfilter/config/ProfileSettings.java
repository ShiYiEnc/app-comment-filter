package io.github.appcommentfilter.config;

import io.github.appcommentfilter.core.RuleEngine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Platform-independent profile routing and legacy migration policy. */
final class ProfileSettings {
    static final String AUTHORITY = "io.github.appcommentfilter.config";
    static final String LEGACY_URI = "content://" + AUTHORITY + "/rules";

    interface Backend {
        /** null means this preference file has no saved JSON. */
        String readJson(String preferences);
        long readRevision(String preferences);
        boolean write(String preferences, String json, long revision);
    }

    static final class Snapshot {
        final String json;
        final long revision;
        Snapshot(String json, long revision) { this.json = json; this.revision = revision; }
    }

    private final Backend backend;
    ProfileSettings(Backend backend) { this.backend = backend; }

    static String uri(HostApp app) { return LEGACY_URI + "/" + app.packageName; }

    static HostApp appForUri(String uri) {
        if (LEGACY_URI.equals(uri)) return HostApp.KUAISHOU;
        for (HostApp app : HostApp.values()) if (uri(app).equals(uri)) return app;
        throw new IllegalArgumentException("Unknown URI");
    }

    private static String preferences(HostApp app) { return "rules." + app.packageName; }

    Snapshot read(HostApp app) {
        String file = preferences(app);
        String json = backend.readJson(file);
        // Read the old file lazily; browsing a profile must never persist rules.
        if (json == null && app == HostApp.KUAISHOU) {
            file = "rules";
            json = backend.readJson(file);
        }
        return json == null ? new Snapshot(RuleEngine.defaultJson(), 0)
                : new Snapshot(normalize(app, json), backend.readRevision(file));
    }

    /** Read-time compatibility never rewrites rules or removes saved rule conditions. */
    private static String normalize(HostApp app, String json) {
        if (app != HostApp.XIAOHONGSHU) return json;
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("resolveProfileGender")) return json;
        root.remove("resolveProfileGender");
        return root.toString();
    }

    void save(HostApp app, String json) {
        RuleEngine.compileJson(json);
        long next = read(app).revision + 1;
        if (!backend.write(preferences(app), normalize(app, json), next)) {
            throw new IllegalStateException("规则保存失败，原配置未替换");
        }
    }
}
