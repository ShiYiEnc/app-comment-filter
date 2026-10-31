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
        DouyinConsumerBindings.KmpConsumer kmpCandidate = null;
        try {
            Class<?> kmpViewModel = Class.forName("com.douyin.kmp.comment.comment.commentpanellist.list.KCmtListViewModel", false, loader);
            // A present but unrecognizable KMP implementation is a real binding failure.
            // Only absence of this optional implementation permits a classic-only install.
            kmpCandidate = DouyinConsumerBindings.kmp(kmpViewModel, response);
        } catch (ClassNotFoundException absent) {
            XposedBridge.log("[CommentFilter][DYProbe] KMP view model absent; classic-only adapter");
        }
        final DouyinConsumerBindings.KmpConsumer kmp = kmpCandidate;
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
        final DouyinHookDiagnostics dispatchProbe = new DouyinHookDiagnostics("treeDispatch " + dispatch, XposedBridge::log);
        final DouyinHookDiagnostics moreProbe = new DouyinHookDiagnostics("treeMore case=57 " + onMore, XposedBridge::log);
        final DouyinHookDiagnostics getterProbe = new DouyinHookDiagnostics("getter " + getter, XposedBridge::log);
        final java.util.concurrent.atomic.AtomicInteger configReports = new java.util.concurrent.atomic.AtomicInteger();
        config.addListener(() -> {
            int event = configReports.incrementAndGet();
            if (event <= 8) XposedBridge.log("[CommentFilter][DYProbe] config event=" + event + " enabled=" + config.snapshot().enabled());
        });
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            if (kmp != null) {
                final KmpExtras extras = installKmpExtras(loader, response, kmp, config, status, installed);
                final DouyinHookDiagnostics probe = new DouyinHookDiagnostics("kmp " + kmp.method, XposedBridge::log);
                XposedBridge.log("[CommentFilter][DYProbe] install kmp responseArg=" + kmp.responseArgument + " " + kmp.method);
                installed.add(XposedBridge.hookMethod(kmp.method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        long hit = probe.enter();
                        try {
                            RuleEngine rules = config.snapshot();
                            Object original = param.args[kmp.responseArgument];
                            List<?> items = rules.enabled() || DouyinHookDiagnostics.sample(hit) ? copies.items(original) : null;
                            probe.report(hit, rules.enabled(), param.thisObject, original, items, reader);
                            copies.filterArgument(param.args, kmp.responseArgument, reader, rules);
                            if (extras != null) {
                                try {
                                    extras.trace.consumed(param.thisObject, param.args[0], original, items,
                                            copies.items(param.args[kmp.responseArgument]));
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                                    extras.paging.fail(param.thisObject, error);
                                }
                                extras.paging.page(param.thisObject, param.args[0], original, items,
                                        copies.items(param.args[kmp.responseArgument]), rules);
                            }
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                    }
                }));
            }
            for (Method consumer : consumers) {
                final DouyinHookDiagnostics probe = new DouyinHookDiagnostics("consumer " + consumer, XposedBridge::log);
                XposedBridge.log("[CommentFilter][DYProbe] install consumer " + consumer);
                installed.add(XposedBridge.hookMethod(consumer, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    long hit = probe.enter();
                    try {
                        RuleEngine rules = config.snapshot();
                        Object original = param.args[0];
                        List<?> items = rules.enabled() || DouyinHookDiagnostics.sample(hit) ? copies.items(original) : null;
                        probe.report(hit, rules.enabled(), param.thisObject, original, items, reader);
                        if (!rules.enabled()) return;
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
                }));
            }
            XposedBridge.log("[CommentFilter][DYProbe] install dispatch " + dispatch);
            installed.add(XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    long hit = dispatchProbe.enter();
                    Class<?> owner = param.thisObject.getClass();
                    try {
                        RuleEngine rules = config.snapshot();
                        Object original = param.args[0];
                        List<?> items = (owner == treeRequest && rules.enabled()) || DouyinHookDiagnostics.sample(hit) ? copies.items(original) : null;
                        dispatchProbe.report(hit, rules.enabled(), param.thisObject, original, items, reader);
                        if (owner != treeRequest || !rules.enabled()) return;
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
            }));
            if (onMore != null) {
                XposedBridge.log("[CommentFilter][DYProbe] install optional more case=57 " + onMore);
                installed.add(XposedBridge.hookMethod(onMore, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (moreKind.getInt(param.thisObject) != 57) return;
                        long hit = moreProbe.enter();
                        Object request = moreOwner.get(param.thisObject);
                        RuleEngine rules = config.snapshot();
                        Object original = param.args[0];
                        boolean tree = request != null && request.getClass() == treeRequest;
                        List<?> items = (tree && rules.enabled()) || DouyinHookDiagnostics.sample(hit) ? copies.items(original) : null;
                        moreProbe.report(hit, rules.enabled(), request, original, items, reader);
                        if (!tree || !rules.enabled()) return;
                        if (items == null) return;
                        List<?> filtered = reader.filter(items, rules);
                        if (filtered != items) param.args[0] = copies.withItems(original, filtered);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(e); }
                }
                }));
            }
            XposedBridge.log("[CommentFilter][DYProbe] install getter " + getter);
            installed.add(XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    long hit = getterProbe.enter();
                    try {
                        RuleEngine rules = config.snapshot();
                        List<?> found = param.getResult() instanceof List<?> ? (List<?>) param.getResult() : null;
                        getterProbe.report(hit, rules.enabled(), param.thisObject, param.getResult(), found, reader);
                        if (param.hasThrowable() || found == null) return;
                        List<?> original = (List<?>) param.getResult();
                        List<?> filtered = reader.filter(original, rules);
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
        XposedBridge.log("[CommentFilter] Douyin response consumers ready: classic=" + consumers.size()
                + ", kmp=" + (kmp == null ? "absent" : kmp.method) + ", baseline " + VERSION_NAME);
    }

    private static final class KmpExtras {
        final DouyinPageTrace trace;
        final DouyinKmpPaging paging;
        KmpExtras(DouyinPageTrace trace, DouyinKmpPaging paging) { this.trace = trace; this.paging = paging; }
    }

    private KmpExtras installKmpExtras(ClassLoader loader, Class<?> response,
            DouyinConsumerBindings.KmpConsumer kmp, RemoteConfig config, HookStatus status,
            List<XC_MethodHook.Unhook> installed) {
        List<XC_MethodHook.Unhook> optional = new ArrayList<>();
        try {
            Method completion = null;
            for (Method candidate : kmp.method.getDeclaringClass().getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers()) || candidate.isBridge()
                        || candidate.getReturnType() != void.class || candidate.getParameterCount() != 1
                        || candidate.getParameterTypes()[0] != List.class) continue;
                if (completion != null) throw new NoSuchMethodException("Ambiguous KMP completion");
                completion = candidate;
            }
            if (completion == null) throw new NoSuchMethodException("No KMP completion");
            DouyinPageTrace trace = new DouyinPageTrace(response, XposedBridge::log);
            Class<?> vm = Class.forName("com.douyin.kmp.comment.comment.commentpanellist.list.KCmtListViewModel", false, loader);
            DouyinKmpPaging paging = new DouyinKmpPaging(loader, vm, response, kmp, config, status, status::emptyPageLimit);
            paging.install(vm, optional);
            optional.add(XposedBridge.hookMethod(completion, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) { paging.fail(param.thisObject, param.getThrowable()); return; }
                    try { trace.completed(param.thisObject, (List<?>) param.args[0]); }
                    catch (RuntimeException | LinkageError error) { paging.fail(param.thisObject, error); return; }
                    paging.complete(param.thisObject);
                }
            }));
            paging.observeConfig();
            installed.addAll(optional);
            return new KmpExtras(trace, paging);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            for (XC_MethodHook.Unhook hook : optional) {
                try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { }
            }
            status.issue("pagination", error);
            XposedBridge.log("[CommentFilter] Douyin empty-page continuation unavailable (" + error.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private void logFailure(Throwable e) {
        status.onBatch(0, 0, e);
        if (failureLogged.compareAndSet(false, true)) {
            XposedBridge.log("[CommentFilter] Douyin row retained after adapter failure ("
                    + e.getClass().getSimpleName() + ")");
        }
    }
}
