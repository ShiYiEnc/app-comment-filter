package io.github.appcommentfilter.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;
import io.github.appcommentfilter.core.RuleEngine;
import io.github.appcommentfilter.core.Gender;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native comment adapters in 9.52.0. Replaces stale DiffUtil deltas with a full refresh. */
public final class XiaohongshuAdapter {
    public static final String VERSION_NAME = "9.52.0";
    public static final long VERSION_CODE = 9520807;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final List<Method> setters = new ArrayList<>();
    private XiaohongshuGenderResolver genders;
    private XiaohongshuProjection projection;
    private RemoteConfig config;
    private HookStatus status;
    private Throwable profileFailure;
    private Method notify;
    private boolean refreshScheduled;
    private boolean admitProfilesOnRefresh;
    private final ThreadLocal<Boolean> cachedProfilesOnly = new ThreadLocal<>();

    public void install(ClassLoader loader, RemoteConfig config, HookStatus status) throws ReflectiveOperationException {
        this.config = config;
        this.status = status;
        Class<?> info = Class.forName("com.xingin.entities.CommentCommentInfo", false, loader);
        String[] names = {"com.xingin.comment.consumer.list.CommentListAdapter",
                "com.xingin.commentpanel.panel.comment.list.CommentListAdapter"};
        List<Method> getters = new ArrayList<>();
        for (String name : names) {
            Class<?> adapter = Class.forName(name, false, loader);
            Method setter = adapter.getDeclaredMethod("O0", List.class);
            if (setter.getReturnType() != void.class) throw new NoSuchMethodException(name + ".O0");
            setters.add(setter);
            Method getter = adapter.getDeclaredMethod("D0");
            if (!List.class.isAssignableFrom(getter.getReturnType())) throw new NoSuchMethodException(name + ".D0");
            getters.add(getter);
        }
        Class<?> baseAdapter = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, loader);
        notify = baseAdapter.getMethod("notifyDataSetChanged");
        Method dispatch = Class.forName("androidx.recyclerview.widget.DiffUtil$DiffResult", false, loader)
                .getMethod("dispatchUpdatesTo", baseAdapter);
        Class<?> controller = Class.forName("com.xingin.comment.consumer.list.AbsCommentListController", false, loader);
        Method delete = controller.getDeclaredMethod("N2", String.class);
        Method getAdapter = controller.getMethod("j2");
        Class<?> rootRow = Class.forName("uz7.d", false, loader);
        Class<?> commentRow = Class.forName("uz7.a", false, loader);
        Class<?> childRow = Class.forName("uz7.e", false, loader);
        XiaohongshuRowRelations relations = new XiaohongshuRowRelations(commentRow,
                Class.forName("nr7.c", false, loader), Class.forName("u47.c", false, loader),
                Class.forName("u47.e", false, loader));
        try {
            genders = new XiaohongshuGenderResolver(loader, main, this::scheduleReapply);
            genders.setDiagnostics(success -> status.issue("profile", success ? null : new ProfileRequestFailed()));
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            // Profile symbols are optional: their absence must not disable text/location filtering.
            profileFailure = error;
            XposedBridge.log("[CommentFilter] Xiaohongshu profile lookup unavailable (" + error.getClass().getSimpleName() + ")");
        }
        XiaohongshuCommentFilter filter;
        try {
            filter = new XiaohongshuCommentFilter(rootRow, commentRow, childRow, info, genders == null ? null : userId -> {
                        XiaohongshuGenderCache.Result result = genders.lookup(userId, !Boolean.TRUE.equals(cachedProfilesOnly.get()));
                        return new XiaohongshuCommentFilter.GenderState(result.pending, result.gender);
                    }, status, relations);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (genders == null) throw error;
            genders.close(); genders = null; profileFailure = error;
            // Author fields belong to the optional gender feature too. The second binding
            // still checks every mandatory text/location/projection field and may fail.
            filter = new XiaohongshuCommentFilter(rootRow, commentRow, childRow, info, null, status, relations);
        }
        projection = new XiaohongshuProjection(filter);
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            installed.add(XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object adapter = param.args[0];
                    if (!projection.requiresFullRefresh(adapter)) return;
                    // The original delta is unsafe even if a refresh must wait for layout.
                    param.setResult(null);
                    refresh(notify, adapter, 0);
                }
            }));
            for (Method setter : setters) installed.add(XposedBridge.hookMethod(setter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.args[0] instanceof List<?> original)) return;
                        RuleEngine rules = config.snapshot();
                        configureGender(rules);
                        List<?> filtered = projection.project(param.thisObject, original, rules);
                        if (filtered != original) {
                            param.args[0] = filtered;
                        }
                    } catch (RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
            for (Method getter : getters) installed.add(XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    List<?> backing = projection.backingForMutation(param.thisObject);
                    if (backing != null) param.setResult(backing);
                }
            }));
            installed.add(XposedBridge.hookMethod(delete, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("commentFilter.previousMutation", projection.beginMutation(null));
                    try { projection.beginMutation(getAdapter.invoke(param.thisObject)); }
                    catch (ReflectiveOperationException | RuntimeException e) { logFailure(e); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    projection.endMutation(param.getObjectExtra("commentFilter.previousMutation"));
                }
            }));
        } catch (RuntimeException | LinkageError e) {
            for (XC_MethodHook.Unhook hook : installed) {
                try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { }
            }
            if (genders != null) genders.close();
            throw e;
        }
        config.addListener(() -> main.post(() -> {
            configureGender(config.snapshot());
            scheduleReapply(true);
        }));
        XposedBridge.log("[CommentFilter] Xiaohongshu adapter ready: " + VERSION_NAME);
    }

    private void configureGender(RuleEngine rules) {
        if (profileFailure != null) status.issue("profile",
                rules.enabled() && rules.resolveProfileGender() && rules.usesGenderRules() ? profileFailure : null);
        if (genders != null) genders.setEnabled(rules.enabled() && rules.resolveProfileGender() && rules.usesGenderRules());
        if (!(rules.enabled() && rules.resolveProfileGender() && rules.usesGenderRules())) status.issue("profile", null);
    }

    private static final class ProfileRequestFailed extends RuntimeException { private static final long serialVersionUID = 1L; }

    private void scheduleReapply() {
        scheduleReapply(false);
    }

    private void scheduleReapply(boolean admitProfiles) {
        if (Looper.myLooper() != main.getLooper()) { main.post(() -> scheduleReapply(admitProfiles)); return; }
        admitProfilesOnRefresh |= admitProfiles;
        if (refreshScheduled || projection == null) return;
        refreshScheduled = true;
        main.postDelayed(() -> {
            refreshScheduled = false;
            boolean mayRequest = admitProfilesOnRefresh;
            admitProfilesOnRefresh = false;
            configureGender(config.snapshot());
            cachedProfilesOnly.set(!mayRequest);
            try { for (java.util.Map.Entry<Object, List<?>> source : projection.sources().entrySet()) {
                Object adapter = source.getKey();
                if (!projection.isCurrent(adapter, source.getValue())) continue;
                for (Method setter : setters) if (setter.getDeclaringClass().isInstance(adapter)) {
                    try {
                        setter.invoke(adapter, source.getValue());
                        refresh(notify, adapter, 0);
                    } catch (ReflectiveOperationException | RuntimeException error) { logFailure(error); }
                    break;
                }
            } } finally { cachedProfilesOnly.remove(); }
        }, 48);
    }

    private void refresh(Method notify, Object adapter, int attempt) {
        try { notify.invoke(adapter); }
        catch (ReflectiveOperationException | RuntimeException error) {
            // RecyclerView rejects updates during a layout/scroll callback. Retry on a later
            // main-loop turn, never replay the raw repository's stale DiffResult.
            if (attempt < 3) main.postDelayed(() -> refresh(notify, adapter, attempt + 1), 16);
            else logFailure(error);
        }
    }

    private void logFailure(Throwable error) {
        status.onBatch(0, 0, error);
        if (failureLogged.compareAndSet(false, true))
            XposedBridge.log("[CommentFilter] Xiaohongshu projection unavailable (" + error.getClass().getSimpleName() + ")");
    }
}
