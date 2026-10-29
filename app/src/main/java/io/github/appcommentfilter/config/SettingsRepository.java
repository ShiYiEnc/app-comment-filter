package io.github.appcommentfilter.config;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.Executors;
import io.github.appcommentfilter.core.AutoSaveQueue;

/** Process-scoped queue survives configuration changes without holding an Activity. */
public final class SettingsRepository {
    private static final java.util.Map<HostApp, AutoSaveQueue> QUEUES = new java.util.EnumMap<>(HostApp.class);
    private SettingsRepository() {}
    public static synchronized AutoSaveQueue get(Context context) {
        return get(context, HostApp.KUAISHOU);
    }

    public static synchronized AutoSaveQueue get(Context context, HostApp host) {
        AutoSaveQueue queue = QUEUES.get(host);
        if (queue == null) {
            Context app = context.getApplicationContext();
            Handler main = new Handler(Looper.getMainLooper());
            queue = new AutoSaveQueue(ConfigStore.load(app, host), Executors.newSingleThreadExecutor(),
                    command -> main.post(command), json -> ConfigStore.save(app, host, json));
            QUEUES.put(host, queue);
        }
        return queue;
    }

    public static HostApp selected(Context context) {
        String name = context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("selectedApp", HostApp.KUAISHOU.packageName);
        try { return HostApp.fromPackage(name); } catch (IllegalArgumentException ignored) { return HostApp.KUAISHOU; }
    }

    public static void select(Context context, HostApp host) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("selectedApp", host.packageName).apply();
    }
}
