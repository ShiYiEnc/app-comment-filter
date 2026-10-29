package io.github.kuaishou.filter.config;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.kuaishou.filter.core.RuleEngine;
import de.robv.android.xposed.XposedBridge;

/** Binder/JSON work stays on a worker; callbacks only read an immutable snapshot. */
public final class RemoteConfig {
    private final Context context;
    private final Handler worker;
    private final android.net.Uri uri;
    private final String logPrefix;
    private volatile RuleEngine engine = RuleEngine.compileJson(RuleEngine.defaultJson());
    private final AtomicBoolean pending = new AtomicBoolean();
    private volatile long lastAttempt = -5000;
    private long revision = -1;
    private boolean failureLogged;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public RemoteConfig(Context context) {
        this(context, HostApp.fromPackage(context.getPackageName()));
    }

    public RemoteConfig(Context context, HostApp app) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        this.uri = ConfigStore.uri(app);
        this.logPrefix = "[CommentFilter][" + app.packageName + "] ";
        HandlerThread thread = new HandlerThread("comment-filter-config");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                this.context.getContentResolver().registerContentObserver(uri, false,
                    new ContentObserver(worker) {
                        @Override public void onChange(boolean selfChange) { refresh(); }
                    });
            } catch (RuntimeException e) {
                XposedBridge.log(logPrefix + "Config observer unavailable; polling on list access");
            }
            refresh();
        });
    }

    public RuleEngine snapshot() {
        if (SystemClock.elapsedRealtime() - lastAttempt > 5000 && pending.compareAndSet(false, true)) {
            worker.post(() -> { try { refresh(); } finally { pending.set(false); } });
        }
        return engine;
    }

    /** Listeners run on the config worker; host UI callbacks must post to their main thread. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
        worker.post(() -> {
            if (listeners.contains(listener)) {
                try { listener.run(); }
                catch (RuntimeException error) { XposedBridge.log(logPrefix + "Config listener unavailable"); }
            }
        });
    }

    public void removeListener(Runnable listener) { listeners.remove(listener); }

    private void refresh() {
        lastAttempt = SystemClock.elapsedRealtime();
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("No configuration");
            long nextRevision = cursor.getLong(cursor.getColumnIndexOrThrow("revision"));
            if (nextRevision == revision) return;
            RuleEngine next = RuleEngine.compileJson(cursor.getString(cursor.getColumnIndexOrThrow("json")));
            engine = next;
            revision = nextRevision;
            failureLogged = false;
            XposedBridge.log(logPrefix + "Rules loaded, revision=" + revision);
            for (Runnable listener : listeners) {
                try { listener.run(); }
                catch (RuntimeException error) { XposedBridge.log(logPrefix + "Config listener unavailable"); }
            }
        } catch (RuntimeException e) {
            if (!failureLogged) {
                failureLogged = true;
                XposedBridge.log(logPrefix + "Rules unavailable; keeping last valid snapshot (" + e.getClass().getSimpleName() + ")");
            }
        }
    }
}
