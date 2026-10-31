package io.github.appcommentfilter.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;

/** Separate from rules: scoped hosts can report only their own diagnostics. */
public final class RuntimeStatusStore {
    private static final Gson JSON = new Gson();
    private RuntimeStatusStore() { }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("runtime_status", Context.MODE_PRIVATE);
    }
    public static HookHealth.Snapshot load(Context context, HostApp app) {
        try { return JSON.fromJson(prefs(context).getString(app.packageName, "null"), HookHealth.Snapshot.class); }
        catch (RuntimeException error) { return null; }
    }
    public static Bundle encode(HookHealth.Snapshot state) {
        Bundle result = new Bundle(); result.putString("status", JSON.toJson(state)); return result;
    }
    public static synchronized Bundle record(Context context, HostApp app, Bundle input) {
        String json = input == null ? null : input.getString("status");
        if (json == null || json.length() > 8192) throw new IllegalArgumentException("Invalid status");
        HookHealth.Snapshot state = JSON.fromJson(json, HookHealth.Snapshot.class);
        if (state == null || state.session == null || state.session.length() > 100
                || state.versionName == null || state.versionName.length() > 100
                || state.moduleVersion == null || state.moduleVersion.length() > 100
                || !("STARTING".equals(state.stage) || "READY".equals(state.stage) || "FAILED".equals(state.stage))
                || state.startedAt <= 0 || state.updatedAt < state.startedAt
                || state.updatedAt > System.currentTimeMillis() + 60000 || state.read < 0 || state.hidden < 0
                || state.lastCommentAt < 0 || state.lastCommentAt > state.updatedAt) {
            throw new IllegalArgumentException("Invalid status fields");
        }
        for (String error : new String[]{state.installError, state.runtimeError, state.configError, state.profileError, state.paginationError}) {
            if (error == null || !error.matches("[A-Za-z0-9_$]{0,100}")) throw new IllegalArgumentException("Invalid issue");
        }
        HookHealth.Snapshot previous = load(context, app);
        Bundle result = new Bundle();
        if (previous != null && (previous.startedAt > state.startedAt
                || (previous.session.equals(state.session) && previous.updatedAt > state.updatedAt))) return result;
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor edit = preferences.edit().putString(app.packageName, JSON.toJson(state));
        // Persist the notice scope, so restarting an incompatible app cannot spam warnings.
        String scopeKey = app.packageName + ".noticeScope", noticesKey = app.packageName + ".notices";
        String scope = state.versionName + "/" + state.versionCode + "/" + state.moduleVersion;
        Set<String> notices = scope.equals(preferences.getString(scopeKey, ""))
                ? new HashSet<>(preferences.getStringSet(noticesKey, java.util.Collections.emptySet())) : new HashSet<>();
        boolean warn = false;
        String[] categories = {"install", "runtime", "config", "profile", "pagination"};
        String[] errors = {state.installError, state.runtimeError, state.configError, state.profileError, state.paginationError};
        for (int i = 0; i < categories.length; i++) if (!errors[i].isEmpty()) warn |= notices.add(categories[i]);
        edit.putString(scopeKey, scope).putStringSet(noticesKey, notices);
        if (!edit.commit()) throw new IllegalStateException("Status not saved");
        result.putBoolean("warn", warn); return result;
    }
}
