package io.github.kuaishou.filter.config;

import io.github.kuaishou.filter.core.RuleEngine;

/** Platform-independent profile routing and legacy migration policy. */
final class ProfileSettings {
    static final String AUTHORITY = "io.github.kuaishou.filter.config";
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
                : new Snapshot(json, backend.readRevision(file));
    }

    void save(HostApp app, String json) {
        RuleEngine.compileJson(json);
        long next = read(app).revision + 1;
        if (!backend.write(preferences(app), json, next)) {
            throw new IllegalStateException("规则保存失败，原配置未替换");
        }
    }
}
