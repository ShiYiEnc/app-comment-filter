package io.github.appcommentfilter.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Filters only main-list response getters; nested ReplyInfo lists are untouched. */
public final class BilibiliAdapter {
    public static final String VERSION_NAME = "9.17.0";
    public static final long VERSION_CODE = 9170300;
    public static final String HD_VERSION_NAME = "2.5.1";
    public static final long HD_VERSION_CODE = 2051100;
    private static final String API = "com.bapis.bilibili.main.community.reply.v1.";
    private final boolean hd;

    public BilibiliAdapter(boolean hd) { this.hd = hd; }

    public void install(ClassLoader loader, RemoteConfig config, HookStatus status) throws ReflectiveOperationException {
        List<Binding> bindings = new ArrayList<>();
        resolve(bindings, loader, status, "MainListReply", "ReplyInfo", "getRepliesList", "getTopRepliesList");
        if (!hd) resolve(bindings, loader, status, "KMainListReply", "KReplyInfo", "getReplies", "getTopReplies");
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        AtomicBoolean logged = new AtomicBoolean();
        try {
            for (Binding binding : bindings) installed.add(XposedBridge.hookMethod(binding.getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof List<?>)) return;
                    try {
                        List<?> original = (List<?>) param.getResult();
                        List<?> filtered = binding.filter.filter(original, config.snapshot());
                        if (filtered != original) param.setResult(filtered);
                    } catch (RuntimeException | LinkageError e) {
                        status.onBatch(0, 0, e);
                        if (logged.compareAndSet(false, true)) XposedBridge.log("[CommentFilter] Bilibili list retained: " + e.getClass().getSimpleName());
                    }
                }
            }));
        } catch (RuntimeException | LinkageError e) {
            for (XC_MethodHook.Unhook hook : installed) {
                try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { }
            }
            throw e;
        }
        XposedBridge.log("[CommentFilter] Bilibili adapter ready: " + (hd ? HD_VERSION_NAME : VERSION_NAME));
    }

    private static void resolve(List<Binding> out, ClassLoader loader, FilterDiagnostics diagnostics, String response, String reply,
                                String... names) throws ReflectiveOperationException {
        Class<?> type = Class.forName(API + response, false, loader);
        BilibiliCommentFilter filter = new BilibiliCommentFilter(Class.forName(API + reply, false, loader), diagnostics);
        for (String name : names) {
            Method getter = type.getDeclaredMethod(name);
            if (!List.class.isAssignableFrom(getter.getReturnType())) throw new NoSuchMethodException(name);
            out.add(new Binding(getter, filter));
        }
    }

    private static final class Binding {
        final Method getter;
        final BilibiliCommentFilter filter;
        Binding(Method getter, BilibiliCommentFilter filter) { this.getter = getter; this.filter = filter; }
    }
}
