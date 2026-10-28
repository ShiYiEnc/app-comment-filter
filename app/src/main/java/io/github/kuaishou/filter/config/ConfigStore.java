package io.github.kuaishou.filter.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Private durable configuration. Only the module UI can save it. */
public final class ConfigStore {
    public static final String AUTHORITY = ProfileSettings.AUTHORITY;
    public static final Uri URI = Uri.parse(ProfileSettings.LEGACY_URI);
    private ConfigStore() {}

    private static ProfileSettings settings(Context context) {
        return new ProfileSettings(new ProfileSettings.Backend() {
            private SharedPreferences prefs(String file) {
                return context.getSharedPreferences(file, Context.MODE_PRIVATE);
            }
            @Override public String readJson(String file) { return prefs(file).getString("json", null); }
            @Override public long readRevision(String file) { return prefs(file).getLong("revision", 0); }
            @Override public boolean write(String file, String json, long revision) {
                return prefs(file).edit().putString("json", json).putLong("revision", revision).commit();
            }
        });
    }

    public static Uri uri(HostApp app) { return Uri.parse(ProfileSettings.uri(app)); }

    public static HostApp appForUri(Uri uri) {
        return ProfileSettings.appForUri(uri == null ? null : uri.toString());
    }

    public static String load(Context context) {
        return load(context, HostApp.KUAISHOU);
    }

    public static String load(Context context, HostApp app) {
        return settings(context).read(app).json;
    }

    public static long revision(Context context) {
        return revision(context, HostApp.KUAISHOU);
    }

    public static long revision(Context context, HostApp app) {
        return settings(context).read(app).revision;
    }

    public static synchronized void save(Context context, String json) {
        save(context, HostApp.KUAISHOU, json);
    }

    public static synchronized void save(Context context, HostApp app, String json) {
        settings(context).save(app, json);
        context.getContentResolver().notifyChange(uri(app), null);
        if (app == HostApp.KUAISHOU) context.getContentResolver().notifyChange(URI, null);
    }
}
