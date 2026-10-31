package io.github.appcommentfilter.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;
import io.github.appcommentfilter.core.RuleEngine;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filter concrete main responses before they become UI rows, for both host request branches. */
public final class Douyin4090Adapter {
    public static final String VERSION_NAME = "40.9.0";
    public static final long VERSION_CODE = 400901;
    private static final String PREFIX = "com.ss.android.ugc.aweme.";
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private HookStatus status;

    public void install(ClassLoader loader, RemoteConfig config, HookStatus status) throws ReflectiveOperationException {
        this.status = status;
        Class<?> response = Class.forName(PREFIX + "comment.model.CommentItemList", false, loader);
        DouyinCommentReader reader = new DouyinCommentReader(
                Class.forName(PREFIX + "comment.model.Comment", false, loader),
                Class.forName(PREFIX + "profile.model.User", false, loader), status);
        DouyinResponseCopy copies = new DouyinResponseCopy(response);
        Class<?> mainRequest = Class.forName(PREFIX + "comment.data.list.CommentListPageNetRequest", false, loader);
        Class<?> fetchHelper = Class.forName(PREFIX + "comment.commentlist.listcontainer.commentlist.CommentListDataFetchHelper", false, loader);
        List<Method> consumers = new ArrayList<>(DouyinConsumerBindings.modern(fetchHelper, mainRequest, response));
        // Both branches ship in 40.9.0 and are selected by host experiments. The old branch
        // reads the public items field as well, so a response getter hook alone cannot cover it.
        try {
            Class<?> legacy = Class.forName(PREFIX + "comment.presenter.CommentFetchModel", false, loader);
            consumers.addAll(DouyinConsumerBindings.legacy(legacy, response));
        } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            XposedBridge.log("[CommentFilter] Douyin legacy branch unavailable (" + unavailable.getClass().getSimpleName() + ")");
        }
        Class<?> treeRequest = Class.forName(PREFIX + "comment.data.manager.FetchCommentTreeListRequest", false, loader);
        Class<?> base = Class.forName(PREFIX + "comment.data.BaseCommentPageNetRequest", false, loader);
        Method dispatch = base.getDeclaredMethod("LJIIL", Object.class, Object.class);
        Method getter = response.getDeclaredMethod("getItems");
        if (dispatch.getReturnType() != void.class || !List.class.isAssignableFrom(getter.getReturnType())) {
            throw new NoSuchMethodException("Unexpected comment response/dispatch signature");
        }
        // This optional compatibility hook only supplements the tree manager. Main-list
        // coverage no longer depends on a generated lambda class or its numeric case tag.
        Method onMore = null; Field callbackKind = null, callbackOwner = null;
        try {
            Class<?> moreCallback = Class.forName("Y.AObjectS309S0200000_37", false, loader);
            Method candidate = moreCallback.getDeclaredMethod("invoke", Object.class);
            Field kind = moreCallback.getField("$t"), owner = moreCallback.getField("l0");
            if (kind.getType() == int.class && owner.getType() == Object.class && candidate.getReturnType() == Object.class) {
                onMore = candidate; callbackKind = kind; callbackOwner = owner;
            }
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException unavailable) {
            XposedBridge.log("[CommentFilter] Douyin optional tree load-more callback unavailable");
        }
        final Field moreKind = callbackKind, moreOwner = callbackOwner;
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            for (Method consumer : consumers) installed.add(XposedBridge.hookMethod(consumer, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object original = param.args[0];
                        List<?> items = copies.items(original);
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
            installed.add(XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Class<?> owner = param.thisObject.getClass();
                    if (owner != treeRequest) return;
                    try {
                        RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object original = param.args[0];
                        List<?> items = copies.items(original);
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
            if (onMore != null) installed.add(XposedBridge.hookMethod(onMore, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (moreKind.getInt(param.thisObject) != 57) return;
                        Object request = moreOwner.get(param.thisObject);
                        if (request == null || request.getClass() != treeRequest) return;
                        RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object original = param.args[0];
                        List<?> items = copies.items(original);
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
            installed.add(XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof List<?>)) return;
                    try {
                        List<?> original = (List<?>) param.getResult();
                        List<?> filtered = reader.filter(original, config.snapshot());
                        if (filtered != original) param.setResult(filtered);
                    } catch (RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
        } catch (RuntimeException | LinkageError failure) {
            for (XC_MethodHook.Unhook hook : installed) {
                try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { /* Keep rolling back. */ }
            }
            throw failure;
        }
        XposedBridge.log("[CommentFilter] Douyin response consumers ready: " + consumers.size() + ", baseline " + VERSION_NAME);
    }

    private void logFailure(Throwable e) {
        status.onBatch(0, 0, e);
        if (failureLogged.compareAndSet(false, true)) {
            XposedBridge.log("[CommentFilter] Douyin row retained after adapter failure ("
                    + e.getClass().getSimpleName() + ")");
        }
    }
}
