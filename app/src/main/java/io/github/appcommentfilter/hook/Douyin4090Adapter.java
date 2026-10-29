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

/** 40.9.0 main-list success dispatch plus the verified response list getter. */
public final class Douyin4090Adapter {
    public static final String VERSION_NAME = "40.9.0";
    public static final long VERSION_CODE = 400901;
    private static final String PREFIX = "com.ss.android.ugc.aweme.";
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public void install(ClassLoader loader, RemoteConfig config) throws ReflectiveOperationException {
        Class<?> response = Class.forName(PREFIX + "comment.model.CommentItemList", false, loader);
        DouyinCommentReader reader = new DouyinCommentReader(
                Class.forName(PREFIX + "comment.model.Comment", false, loader),
                Class.forName(PREFIX + "profile.model.User", false, loader));
        DouyinResponseCopy copies = new DouyinResponseCopy(response);
        Class<?> mainRequest = Class.forName(PREFIX + "comment.data.list.CommentListPageNetRequest", false, loader);
        Class<?> treeRequest = Class.forName(PREFIX + "comment.data.manager.FetchCommentTreeListRequest", false, loader);
        Class<?> base = Class.forName(PREFIX + "comment.data.BaseCommentPageNetRequest", false, loader);
        Method dispatch = base.getDeclaredMethod("LJIIL", Object.class, Object.class);
        Method getter = response.getDeclaredMethod("getItems");
        // Load-more dispatch is in this version's generated Function1 (case 57),
        // and does not call BaseCommentPageNetRequest.LJIIL.
        Class<?> moreCallback = Class.forName("Y.AObjectS309S0200000_37", false, loader);
        Method onMore = moreCallback.getDeclaredMethod("invoke", Object.class);
        Field callbackKind = moreCallback.getField("$t");
        Field callbackOwner = moreCallback.getField("l0");
        if (dispatch.getReturnType() != void.class || !List.class.isAssignableFrom(getter.getReturnType())) {
            throw new NoSuchMethodException("Unexpected comment response/dispatch signature");
        }
        if (callbackKind.getType() != int.class || callbackOwner.getType() != Object.class
                || onMore.getReturnType() != Object.class) throw new NoSuchMethodException("Unexpected load-more callback");
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            installed.add(XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Class<?> owner = param.thisObject.getClass();
                    if (owner != mainRequest && owner != treeRequest) return;
                    try {
                        RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object original = param.args[0];
                        List<?> items = copies.items(original);
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException e) { logFailure(e); }
                }
            }));
            installed.add(XposedBridge.hookMethod(onMore, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (callbackKind.getInt(param.thisObject) != 57) return;
                        Object request = callbackOwner.get(param.thisObject);
                        if (request == null || (request.getClass() != mainRequest && request.getClass() != treeRequest)) return;
                        RuleEngine rules = config.snapshot();
                        if (!rules.enabled()) return;
                        Object original = param.args[0];
                        List<?> items = copies.items(original);
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException e) { logFailure(e); }
                }
            }));
            installed.add(XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof List<?>)) return;
                    try {
                        List<?> original = (List<?>) param.getResult();
                        List<?> filtered = reader.filter(original, config.snapshot());
                        if (filtered != original) param.setResult(filtered);
                    } catch (RuntimeException e) { logFailure(e); }
                }
            }));
        } catch (RuntimeException | LinkageError failure) {
            for (XC_MethodHook.Unhook hook : installed) {
                try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { /* Keep rolling back. */ }
            }
            throw failure;
        }
        XposedBridge.log("[CommentFilter] Douyin adapter ready: " + VERSION_NAME + ", main response dispatch");
    }

    private void logFailure(Exception e) {
        if (failureLogged.compareAndSet(false, true)) {
            XposedBridge.log("[CommentFilter] Douyin row retained after adapter failure ("
                    + e.getClass().getSimpleName() + ")");
        }
    }
}
