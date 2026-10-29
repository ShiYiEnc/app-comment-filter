package io.github.appcommentfilter.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;

/** Scoped getter substitution keeps cached response data intact. */
public final class Kuaishou14920Adapter {
    public static final String VERSION_NAME = "14.9.20.50799";
    public static final long VERSION_CODE = 50799;
    private static final String PAGE_LIST = "com.kwai.kmp.comment.mvi.panel.pagelist.KmpCommentFeaturePageList";
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

    public void install(ClassLoader loader, RemoteConfig config, android.app.Application application) throws ReflectiveOperationException {
        Class<?> pageList = Class.forName(PAGE_LIST, false, loader);
        Class<?> responseApi = Class.forName("w2b.q", false, loader);
        CommentListFilter filter = new CommentListFilter(new CommentSnapshotReader(Class.forName("dpb.d", false, loader)));
        Method parser = pageList.getDeclaredMethod("w1", responseApi);
        Method localComments = responseApi.getMethod("getKLocalComments");
        if (parser.getReturnType() != void.class || !List.class.isAssignableFrom(localComments.getReturnType())) {
            throw new NoSuchMethodException("Unexpected parser/list signature");
        }
        PaginationCoordinator pagination = new PaginationCoordinator(application, loader, pageList, config, filter);
        pagination.install();
        XposedBridge.hookMethod(parser, new XC_MethodHook() {
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
                    param.setObjectExtra("ks.page", pagination.beforeParse(param.thisObject, response, filtered != original));
                    record(original.size() - filtered.size());
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    if (failureLogged.compareAndSet(false, true)) {
                        XposedBridge.log("[KSFilter] Response adapter failed; keeping comments (" + e.getClass().getSimpleName() + ")");
                    }
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Scope previous = (Scope) param.getObjectExtra("ks.previous");
                if (previous == null) current.remove(); else current.set(previous);
                PaginationCoordinator.Page page = (PaginationCoordinator.Page) param.getObjectExtra("ks.page");
                if (page != null) pagination.afterParse(param.thisObject, page, param.hasThrowable());
            }
        });
        XposedBridge.log("[KSFilter] Adapter ready: 14.9.20.50799, KMP w1(w2b.q), inline footer "
                + io.github.appcommentfilter.BuildConfig.VERSION_NAME);
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
