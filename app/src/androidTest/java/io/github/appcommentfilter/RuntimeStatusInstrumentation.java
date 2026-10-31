package io.github.appcommentfilter;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import io.github.appcommentfilter.config.ConfigStore;
import io.github.appcommentfilter.config.HookHealth;
import io.github.appcommentfilter.config.HostApp;
import io.github.appcommentfilter.config.RuntimeStatusStore;
import java.util.Map;

/** Device-side Binder/persistence checks; no LSPosed or third-party test runner required. */
public final class RuntimeStatusInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    private Bundle report(HostApp app, HookHealth.Snapshot state) {
        return getTargetContext().getContentResolver().call(ConfigStore.URI, "reportStatus", app.packageName, RuntimeStatusStore.encode(state));
    }
    private void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        if (arguments.getString("contractApk") != null) {
            try {
                result.putString("result", KuaishouContractProbe.inspect(getTargetContext(), arguments.getString("contractApk")));
                finish(Activity.RESULT_OK, result);
            } catch (Throwable error) {
                StringBuilder message = new StringBuilder();
                for (Throwable cause = error; cause != null; cause = cause.getCause()) message.append(cause).append("; ");
                result.putString("error", message.toString()); finish(Activity.RESULT_CANCELED, result);
            }
            return;
        }
        SharedPreferences preferences = getTargetContext().getSharedPreferences("runtime_status", Context.MODE_PRIVATE);
        Map<String, ?> backup = preferences.getAll();
        boolean seed = arguments.getString("seed") != null;
        int resultCode = Activity.RESULT_OK;
        try {
            long now = System.currentTimeMillis();
            if (seed) {
                String scenario = arguments.getString("seed");
                HookHealth health = new HookHealth("ui-" + scenario, "99.0.0", 990000, BuildConfig.VERSION_NAME, now);
                if (!"starting".equals(scenario)) health.installed();
                if ("active".equals(scenario) || "partial".equals(scenario)) health.batch(25, 3, null, now);
                if ("failed".equals(scenario)) health.installFailed("NoSuchMethodException");
                if ("partial".equals(scenario)) health.issue("runtime", "CommentShapeError");
                HookHealth.Snapshot state = health.snapshot(now);
                if ("old".equals(scenario)) state.versionCode--;
                report(HostApp.XIAOHONGSHU, state);
                result.putString("result", "seeded " + scenario);
            } else {
                preferences.edit().clear().commit();
                HookHealth health = new HookHealth("one", "99.0.0", 990000, BuildConfig.VERSION_NAME, now);
                check(!report(HostApp.XIAOHONGSHU, health.snapshot(now)).getBoolean("warn"), "startup cannot warn");
                health.installed(); report(HostApp.XIAOHONGSHU, health.snapshot(now + 1));
                check(RuntimeStatusStore.load(getTargetContext(), HostApp.XIAOHONGSHU).lastCommentAt == 0, "ready is not read");
                health.issue("runtime", "NoSuchMethodException");
                check(report(HostApp.XIAOHONGSHU, health.snapshot(now + 2)).getBoolean("warn"), "first issue must warn");
                check(!report(HostApp.XIAOHONGSHU, health.snapshot(now + 3)).getBoolean("warn"), "repeat issue must not warn");
                HookHealth restart = new HookHealth("two", "99.0.0", 990000, BuildConfig.VERSION_NAME, now + 4);
                restart.installFailed("NoSuchMethodException");
                check(report(HostApp.XIAOHONGSHU, restart.snapshot(now + 4)).getBoolean("warn"), "new category must warn");
                check(!report(HostApp.XIAOHONGSHU, restart.snapshot(now + 5)).getBoolean("warn"), "persisted suppression");
                check(report(HostApp.DOUYIN, restart.snapshot(now + 5)).getBoolean("warn"), "hosts must be independent");
                report(HostApp.XIAOHONGSHU, health.snapshot(now + 2));
                check("two".equals(RuntimeStatusStore.load(getTargetContext(), HostApp.XIAOHONGSHU).session), "stale session rejected");
                Bundle invalid = new Bundle(); invalid.putString("status", "{\"stage\":\"garbage\"}");
                try {
                    getTargetContext().getContentResolver().call(ConfigStore.URI, "reportStatus", HostApp.XIAOHONGSHU.packageName, invalid);
                    throw new AssertionError("malformed report accepted");
                } catch (IllegalArgumentException expected) { }
                try {
                    getTargetContext().getContentResolver().update(ConfigStore.uri(HostApp.XIAOHONGSHU), new android.content.ContentValues(), null, null);
                    throw new AssertionError("rule write accepted");
                } catch (SecurityException expected) { }
                HookHealth upgraded = new HookHealth("three", "99.0.1", 990001, BuildConfig.VERSION_NAME, now + 10);
                upgraded.installFailed("NoSuchMethodException");
                check(report(HostApp.XIAOHONGSHU, upgraded.snapshot(now + 10)).getBoolean("warn"), "new version may warn again");
                result.putString("result", "PASS: startup, readiness, notice dedup, categories, host isolation, stale sessions, invalid input, rule immutability, version scope");
            }
        } catch (Throwable failure) {
            result.putString("error", failure.toString()); resultCode = Activity.RESULT_CANCELED;
        } finally {
            if (!seed) {
                SharedPreferences.Editor edit = preferences.edit().clear();
                for (Map.Entry<String, ?> entry : backup.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) edit.putString(entry.getKey(), (String) value);
                    else if (value instanceof java.util.Set<?>) {
                        @SuppressWarnings("unchecked") java.util.Set<String> values = (java.util.Set<String>) value;
                        edit.putStringSet(entry.getKey(), values);
                    }
                }
                edit.commit();
            }
        }
        finish(resultCode, result);
    }
}
