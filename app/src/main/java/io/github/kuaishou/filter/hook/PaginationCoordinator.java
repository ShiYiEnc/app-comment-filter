package io.github.kuaishou.filter.hook;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.kuaishou.filter.config.RemoteConfig;
import io.github.kuaishou.filter.core.PaginationPolicy;

/** Continues a short filtered list only after the host commits its cursor/loading state. */
public final class PaginationCoordinator implements Application.ActivityLifecycleCallbacks {
    static final int TARGET_MAIN_COMMENTS = 12;
    static final int MAX_AUTO_PAGES = 5;
    private static final String BASE = "com.kwai.kmp.pagelist.KMPPageList";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Session> sessions = new WeakHashMap<>();
    private final ThreadLocal<Boolean> dispatching = new ThreadLocal<>();
    private final RemoteConfig config;
    private final CommentListFilter filter;
    private final Field coreField;
    private final Method isFirst, isLoading, hasMore, latest, cursor, roots, load, locked;
    private final Class<?> coreClass, completionClass;
    private final CommentFooterController footerController;
    private WeakReference<Activity> foreground = new WeakReference<>(null);
    private Session active;

    static final class Page {
        final WeakReference<Object> response;
        final boolean first;
        final int before;
        final boolean filtered;
        int total, added;
        boolean handled;
        Page(Object response, boolean first, int before, boolean filtered) {
            this.response = new WeakReference<>(response);
            this.first = first; this.before = before; this.filtered = filtered;
        }
    }

    private static final class Session {
        final WeakReference<Object> owner, core;
        final PaginationPolicy policy;
        WeakReference<Activity> screen = new WeakReference<>(null);
        Page page;
        int ticket;
        boolean closed;
        PaginationPolicy.Result pending = PaginationPolicy.Result.NONE;
        CommentFooterController.Footer footer;
        Object footerToken;
        Session(Object owner, Object core, int target) {
            this.owner = new WeakReference<>(owner); this.core = new WeakReference<>(core);
            policy = new PaginationPolicy(target, MAX_AUTO_PAGES);
        }
    }

    public PaginationCoordinator(Application app, ClassLoader loader, Class<?> pageList,
                                 RemoteConfig config, CommentListFilter filter) throws ReflectiveOperationException {
        this.config = config; this.filter = filter;
        coreField = pageList.getDeclaredField("c1"); coreField.setAccessible(true);
        coreClass = Class.forName(BASE, false, loader);
        completionClass = Class.forName(BASE + "$c", false, loader);
        isFirst = coreClass.getMethod("i"); isLoading = coreClass.getMethod("k");
        hasMore = coreClass.getMethod("hasMore"); latest = coreClass.getMethod("j0");
        cursor = Class.forName("w2b.q", false, loader).getMethod("getKPcursor");
        roots = pageList.getMethod("r0"); load = pageList.getMethod("k"); locked = pageList.getMethod("h0");
        // Validate every lifecycle symbol before registering anything.
        coreClass.getDeclaredMethod("q", completionClass);
        coreClass.getDeclaredMethod("p", Throwable.class);
        coreClass.getDeclaredMethod("load"); coreClass.getDeclaredMethod("release");
        coreClass.getDeclaredMethod("o"); coreClass.getDeclaredMethod("refresh");
        footerController = new CommentFooterController(loader, pageList, () -> {
            synchronized (sessions) {
                if (active != null && active.pending == PaginationPolicy.Result.ASK_CONTINUE) {
                    offerContinuation(active, active.ticket);
                }
            }
        });
        app.registerActivityLifecycleCallbacks(this);
    }

    public void install() throws ReflectiveOperationException {
        XposedBridge.hookMethod(coreClass.getDeclaredMethod("q", completionClass), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) completed(param.thisObject);
            }
        });
        XposedBridge.hookMethod(coreClass.getDeclaredMethod("load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(dispatching.get())) return;
                synchronized (sessions) {
                    Session session = sessions.get(param.thisObject);
                    if (session == null || session.closed) return;
                    try {
                        if (active == session && session.pending == PaginationPolicy.Result.ASK_CONTINUE && config.snapshot().enabled()) {
                            // Laying out a new footer can trigger native preloading; the button owns this paused action.
                            param.setResult(null); return;
                        }
                        if (Boolean.TRUE.equals(isLoading.invoke(param.thisObject))) return;
                        session.policy.onUserLoad(); session.ticket++;
                        session.pending = PaginationPolicy.Result.NONE;
                        main.post(() -> dismiss(session));
                    } catch (ReflectiveOperationException | RuntimeException e) { fail(session, e); }
                }
            }
        });
        XposedBridge.hookMethod(coreClass.getDeclaredMethod("p", Throwable.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { close(param.thisObject); }
        });
        for (String stopMethod : new String[]{"release", "o", "refresh"}) {
            XposedBridge.hookMethod(coreClass.getDeclaredMethod(stopMethod), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { close(param.thisObject); }
            });
        }
    }

    public Page beforeParse(Object owner, Object response, boolean filtered) throws ReflectiveOperationException {
        Object core = coreField.get(owner);
        Page page = new Page(response, Boolean.TRUE.equals(isFirst.invoke(core)),
                filter.mainCommentCount((List<?>) roots.invoke(owner)), filtered);
        synchronized (sessions) {
            Session session = sessions.get(core);
            if (session == null || session.closed) {
                Activity activity = foreground.get();
                int target = TARGET_MAIN_COMMENTS;
                if (activity != null) {
                    android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                    target = Math.max(target, Math.min(60, (int) Math.ceil(metrics.heightPixels / metrics.density / 40.0) + 2));
                }
                session = new Session(owner, core, target); sessions.put(core, session);
            }
            if (active != session && active != null) {
                Session old = active; old.ticket++; main.post(() -> dismiss(old));
            }
            active = session;
            session.screen = new WeakReference<>(foreground.get());
            session.ticket++; session.page = page;
            session.pending = PaginationPolicy.Result.NONE;
        }
        return page;
    }

    public void afterParse(Object owner, Page page, boolean failed) {
        try {
            if (failed) { close(coreField.get(owner)); return; }
            page.total = filter.mainCommentCount((List<?>) roots.invoke(owner));
            page.added = page.first ? page.total : Math.max(0, page.total - page.before);
        } catch (ReflectiveOperationException | RuntimeException e) { logFailure(e); }
    }

    private void completed(Object core) {
        synchronized (sessions) {
            Session session = sessions.get(core);
            if (session == null || session.closed || active != session) return;
            Page page = session.page;
            try {
                if (page == null || page.handled || latest.invoke(core) != page.response.get()) return;
                page.handled = true;
                if (!config.snapshot().enabled()) { close(core); return; }
                String next = (String) cursor.invoke(page.response.get());
                PaginationPolicy.Result result = session.policy.onPage(page.first, next,
                        Boolean.TRUE.equals(hasMore.invoke(core)), page.total, page.added, page.filtered);
                session.pending = result;
                arm(session);
                if (result == PaginationPolicy.Result.STALLED) {
                    XposedBridge.log("[KSFilter] Pagination stopped: repeated cursor");
                }
            } catch (ReflectiveOperationException | RuntimeException e) { fail(session, e); }
        }
    }

    /** Re-arm an unspent action after resuming, without consuming another policy budget. */
    private void arm(Session session) {
        int ticket = ++session.ticket;
        if (session.pending == PaginationPolicy.Result.LOAD_NEXT) {
            main.post(() -> {
                synchronized (sessions) {
                    if (session.ticket == ticket && session.footer != null) session.footer.loading();
                }
            });
            main.postDelayed(() -> request(session, ticket, 0), 200);
        } else if (session.pending == PaginationPolicy.Result.ASK_CONTINUE) {
            main.post(() -> offerContinuation(session, ticket));
        } else {
            main.post(() -> {
                synchronized (sessions) { if (session.ticket == ticket) dismiss(session); }
            });
        }
    }

    private boolean usable(Session session, int ticket) {
        Activity activity = foreground.get();
        return !session.closed && active == session && session.ticket == ticket
                && session.owner.get() != null && session.core.get() != null
                && activity != null && activity == session.screen.get()
                && !activity.isFinishing() && !activity.isDestroyed() && config.snapshot().enabled();
    }

    private void request(Session session, int ticket, int waits) {
        synchronized (sessions) {
            if (!usable(session, ticket)) return;
            Object core = session.core.get(); Object owner = session.owner.get();
            try {
                if (Boolean.TRUE.equals(isLoading.invoke(core))) {
                    if (waits < 10) main.postDelayed(() -> request(session, ticket, waits + 1), 100);
                    return;
                }
                if (!Boolean.TRUE.equals(hasMore.invoke(core)) || Boolean.TRUE.equals(locked.invoke(owner))) return;
                // Do not spend another page's budget by accidentally treating our load as user input.
                session.pending = PaginationPolicy.Result.NONE;
                dispatching.set(true);
                try { load.invoke(owner); }
                finally { dispatching.remove(); }
                XposedBridge.log("[KSFilter] Continue short filtered list via host pager");
            } catch (ReflectiveOperationException | RuntimeException e) { fail(session, e); }
        }
    }

    private void offerContinuation(Session session, int ticket) {
        offerContinuation(session, ticket, 0);
    }

    private void offerContinuation(Session session, int ticket, int attempt) {
        synchronized (sessions) {
            if (!usable(session, ticket) || session.pending != PaginationPolicy.Result.ASK_CONTINUE) return;
            try {
                Object core = session.core.get();
                if (Boolean.TRUE.equals(isLoading.invoke(core)) || !Boolean.TRUE.equals(hasMore.invoke(core))
                        || Boolean.TRUE.equals(locked.invoke(session.owner.get()))) return;
                Runnable continueAction = () -> {
                            synchronized (sessions) {
                                if (!usable(session, ticket)) return;
                                if (session.policy.continueByUser() == PaginationPolicy.Result.LOAD_NEXT) {
                                    session.pending = PaginationPolicy.Result.LOAD_NEXT;
                                    arm(session);
                                }
                            }
                        };
                if (session.footer != null && session.footer.current()) {
                    session.footer.ready(continueAction); return;
                }
                dismiss(session);
                Object response = session.page == null ? null : session.page.response.get();
                Object binding = new Object();
                session.footerToken = binding;
                session.footer = footerController.show(foreground.get(), session.owner.get(), response, continueAction, () -> main.post(() -> {
                            synchronized (sessions) {
                                if (session.closed || active != session || session.footerToken != binding) return;
                                dismiss(session);
                                // A background Activity keeps its unspent action for onResume.
                                if (foreground.get() != null) close(session.core.get());
                            }
                        }));
                if (session.footer == null) {
                    if (attempt < 20) main.postDelayed(() -> offerContinuation(session, ticket, attempt + 1), 100);
                    else XposedBridge.log("[KSFilter] Comment footer unavailable: no unique ready comment panel");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                XposedBridge.log("[KSFilter] Comment footer unavailable (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private void close(Object core) {
        synchronized (sessions) {
            Session session = sessions.remove(core);
            if (session == null) return;
            session.closed = true; session.ticket++; session.policy.stop(); session.pending = PaginationPolicy.Result.NONE;
            if (active == session) active = null;
            main.post(() -> dismiss(session));
        }
    }

    private void fail(Session session, Exception e) {
        logFailure(e); close(session.core.get());
    }
    private void logFailure(Exception e) {
        XposedBridge.log("[KSFilter] Pagination continuation unavailable (" + e.getClass().getSimpleName() + ")");
    }
    private void dismiss(Session session) {
        session.footerToken = null;
        if (session.footer != null) { session.footer.remove(); session.footer = null; }
    }

    @Override public void onActivityResumed(Activity activity) {
        synchronized (sessions) {
            foreground = new WeakReference<>(activity);
            if (active != null && !active.closed && active.screen.get() == activity) arm(active);
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        synchronized (sessions) {
            if (foreground.get() != activity) return;
            foreground.clear();
            if (active != null) { Session session = active; session.ticket++; main.post(() -> dismiss(session)); }
        }
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
