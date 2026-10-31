package io.github.appcommentfilter.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import android.os.SystemClock;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;

/** Scoped getter substitution keeps cached response data intact. */
public final class KuaishouAdapter {
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    private final Set<Method> hookedGetters = new HashSet<>();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicLong passes = new AtomicLong();
    private final AtomicLong hidden = new AtomicLong();
    private long lastLog = -10000;

    private static final class Scope {
        final Object response;
        final List<?> filtered;
        Scope(Object response, List<?> filtered) { this.response = response; this.filtered = filtered; }
    }

    /** APK inspection runs off the host UI thread; retain foreground state during binding. */
    public void installAsync(ClassLoader loader, RemoteConfig config, Application application, HookStatus status) {
        Foreground pending = new Foreground(); application.registerActivityLifecycleCallbacks(pending);
        new Thread(() -> {
            try {
                PaginationCoordinator pagination = install(loader, config, application, status);
                new Handler(Looper.getMainLooper()).post(() -> {
                    Activity foreground = pending.current.get();
                    if (pagination != null && foreground != null) pagination.onActivityResumed(foreground);
                    application.unregisterActivityLifecycleCallbacks(pending);
                });
                status.installed();
            } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
                status.installFailed(error);
                XposedBridge.log("[CommentFilter] Kuaishou shared contract unavailable (" + error.getClass().getSimpleName() + ")");
                new Handler(Looper.getMainLooper()).post(() -> application.unregisterActivityLifecycleCallbacks(pending));
            }
        }, "comment-filter-kmp-bind").start();
    }

    private PaginationCoordinator install(ClassLoader loader, RemoteConfig config, Application application, HookStatus status)
            throws ReflectiveOperationException, IOException {
        ArrayList<File> files = new ArrayList<>();
        files.add(new File(application.getApplicationInfo().sourceDir));
        String[] splits = application.getApplicationInfo().splitSourceDirs;
        if (splits != null) for (String split : splits) files.add(new File(split));
        KuaishouBindings binding = new KuaishouBindings(loader, files.toArray(new File[0]));
        CommentListFilter filter = new CommentListFilter(new CommentSnapshotReader(binding.commentApi), status);
        java.util.concurrent.atomic.AtomicReference<PaginationCoordinator> paging = new java.util.concurrent.atomic.AtomicReference<>();
        XposedBridge.hookMethod(binding.parser, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Scope previous = current.get();
                    param.setObjectExtra("ks.previous", previous);
                    // A nested parser must never inherit an outer response substitution.
                    current.remove();
                    try {
                        io.github.appcommentfilter.core.RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object response = param.args[0];
                        if (response == null) return;
                        Method getter = response.getClass().getMethod("getKLocalComments");
                        installGetter(getter);
                        List<?> original = (List<?>) getter.invoke(response);
                        if (original == null) return;
                        List<?> filtered = filter.filter(original, rules);
                        if (filtered != original) current.set(new Scope(response, filtered));
                        PaginationCoordinator pagination = paging.get();
                        if (pagination != null) {
                            try { param.setObjectExtra("ks.page", pagination.beforeParse(param.thisObject, response, filtered != original)); }
                            catch (ReflectiveOperationException | RuntimeException error) { status.issue("pagination", error); }
                        }
                        record(original.size() - filtered.size());
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                        status.onBatch(0, 0, e);
                        if (failureLogged.compareAndSet(false, true)) {
                            XposedBridge.log("[KSFilter] Response adapter failed; keeping comments (" + e.getClass().getSimpleName() + ")");
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Scope previous = (Scope) param.getObjectExtra("ks.previous");
                    if (previous == null) current.remove(); else current.set(previous);
                    PaginationCoordinator.Page page = (PaginationCoordinator.Page) param.getObjectExtra("ks.page");
                    PaginationCoordinator pagination = paging.get();
                    if (page != null && pagination != null) pagination.afterParse(param.thisObject, page, param.hasThrowable());
                }
        });
        // Supplementary paging/footer symbols cannot disable the independently bound filter.
        try {
            PaginationCoordinator pagination = new PaginationCoordinator(application, loader, binding, binding.paging(), config, filter, status);
            pagination.install(); paging.set(pagination);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError error) {
            status.issue("pagination", error);
            XposedBridge.log("[KSFilter] Native paging retained; optional continuation unavailable (" + error.getClass().getSimpleName() + ")");
        }
        XposedBridge.log("[KSFilter] Shared KMP contract ready: " + binding.parser.getName() + "(" + binding.responseApi.getName()
                + "), model=" + binding.commentApi.getName() + ", continuation=" + (paging.get() != null));
        return paging.get();
    }

    private static final class Foreground implements Application.ActivityLifecycleCallbacks {
        volatile WeakReference<Activity> current = new WeakReference<>(null);
        @Override public void onActivityResumed(Activity activity) { current = new WeakReference<>(activity); }
        @Override public void onActivityPaused(Activity activity) { if (current.get() == activity) current = new WeakReference<>(null); }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    private synchronized void installGetter(Method getter) {
        if (hookedGetters.contains(getter)) return;
        if (Modifier.isAbstract(getter.getModifiers()) || !List.class.isAssignableFrom(getter.getReturnType())) {
            throw new IllegalArgumentException("Unexpected concrete getter");
        }
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Scope scope = current.get();
                if (scope != null && scope.response == param.thisObject) param.setResult(scope.filtered);
            }
        });
        hookedGetters.add(getter);
    }

    private synchronized void record(int count) {
        long total = passes.incrementAndGet();
        long totalHidden = hidden.addAndGet(count);
        long now = SystemClock.elapsedRealtime();
        if (now - lastLog >= 10000) {
            lastLog = now;
            XposedBridge.log("[KSFilter] List passes=" + total + ", hidden occurrences=" + totalHidden);
        }
    }
}
