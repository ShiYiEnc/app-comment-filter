package io.github.appcommentfilter.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Toast;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.BuildConfig;
import io.github.appcommentfilter.config.ConfigStore;
import io.github.appcommentfilter.config.HookHealth;
import io.github.appcommentfilter.config.HostApp;
import io.github.appcommentfilter.config.RuntimeStatusStore;

/** No Binder work on comment callbacks; coalesces reports and failures on a worker. */
public final class HookStatus implements FilterDiagnostics {
    private final Context context;
    private final HostApp app;
    private final HookHealth health;
    private final Handler worker, main = new Handler(Looper.getMainLooper());
    private boolean queued, urgentQueued, warned;
    private boolean reportFailureLogged;
    private final Runnable flush = this::publish;

    public HookStatus(Context context, HostApp app) {
        this.context = context; this.app = app;
        String version = "unknown"; long code = -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(app.packageName, 0);
            version = info.versionName == null ? "unknown" : info.versionName; code = info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { }
        health = new HookHealth(java.util.UUID.randomUUID().toString(), version, code,
                BuildConfig.VERSION_NAME, System.currentTimeMillis());
        HandlerThread thread = new HandlerThread("comment-filter-status"); thread.start();
        worker = new Handler(thread.getLooper()); schedule(true);
    }
    public void installed() { health.installed(); schedule(true); }
    public void installFailed(Throwable error) {
        health.installFailed(HookHealth.errorName(error)); schedule(true);
    }
    public void issue(String feature, Throwable error) {
        if (health.issue(feature, error == null ? "" : HookHealth.errorName(error))) schedule(error != null);
    }
    public void emptyPageLimit() {
        main.post(() -> {
            try { Toast.makeText(context, "评论过滤：已连续补页5次，可点击评论区底部继续查找", Toast.LENGTH_LONG).show(); }
            catch (RuntimeException ignored) { }
        });
    }
    @Override public void onBatch(int read, int hidden, Throwable error) {
        boolean changed = health.batch(read, hidden, error, System.currentTimeMillis());
        if (read > 0 || error != null) schedule(changed);
    }
    private synchronized void schedule(boolean urgent) {
        if (queued && (!urgent || urgentQueued)) return;
        if (queued) worker.removeCallbacks(flush);
        queued = true; urgentQueued = urgent;
        worker.postDelayed(flush, urgent ? 150 : 2000);
    }
    private void publish() {
        synchronized (this) { queued = false; urgentQueued = false; }
        HookHealth.Snapshot state = health.snapshot(System.currentTimeMillis());
        try {
            android.os.Bundle result = context.getContentResolver().call(ConfigStore.URI, "reportStatus", app.packageName,
                    RuntimeStatusStore.encode(state));
            if (result == null) throw new IllegalStateException("No diagnostic provider");
            if (result.getBoolean("warn")) warn();
        } catch (RuntimeException | LinkageError error) {
            // Reporting can be unavailable too; retain a once-per-process fallback notice.
            if (state.hasIssue()) warn();
            if (!reportFailureLogged) {
                reportFailureLogged = true;
                XposedBridge.log("[CommentFilter] Diagnostic report unavailable (" + error.getClass().getSimpleName() + ")");
            }
        }
    }
    private void warn() {
        if (warned) return;
        warned = true;
        main.post(() -> {
            try { Toast.makeText(context, "评论过滤：" + app.displayName + "适配或配置异常，请打开模块查看运行状态", Toast.LENGTH_LONG).show(); }
            catch (RuntimeException ignored) { /* Host/OS can suppress Toasts; persisted status remains available. */ }
        });
    }
}
