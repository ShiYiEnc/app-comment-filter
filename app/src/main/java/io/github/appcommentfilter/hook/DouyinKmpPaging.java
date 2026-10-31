package io.github.appcommentfilter.hook;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.appcommentfilter.config.RemoteConfig;
import io.github.appcommentfilter.core.RuleEngine;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Uses only the host's normal idle -> load-more transition; never changes paging metadata. */
final class DouyinKmpPaging {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, Entry> sources = new WeakHashMap<>();
    private final RemoteConfig config;
    private final Method loadMore, hasMore, value, lifecycle, lifecycleState;
    private final Field sourceField, stateFlow, ownerReference, panelField, sourceSession, more, cursor;
    private final Class<?> requestKind, sourceClass, lifecycleOwner;
    private final List<Method> sourceGetters = new ArrayList<>();
    private final HookStatus status;
    private final Runnable limitNotice;
    private int automaticRequests;
    private int skippedReports;
    private static final class Entry {
        WeakReference<Object> pager = new WeakReference<>(null), vm = new WeakReference<>(null);
        final DouyinEmptyPagePolicy policy = new DouyinEmptyPagePolicy();
        RuleEngine rules;
        String session;
        String panelSession;
        boolean capturedPanel;
        boolean invoking;
    }

    DouyinKmpPaging(ClassLoader loader, Class<?> vm, Class<?> response,
                    DouyinConsumerBindings.KmpConsumer binding, RemoteConfig config,
                    HookStatus status, Runnable limitNotice) throws ReflectiveOperationException {
        this.config = config; this.status = status; this.limitNotice = limitNotice;
        sourceClass = binding.method.getDeclaringClass(); requestKind = binding.method.getParameterTypes()[0];
        Class<?> pager = Class.forName("com.bytedance.kmp.klist.KListViewModel", false, loader);
        sourceField = uniqueField(pager, type -> type != Object.class && type.isAssignableFrom(sourceClass));
        stateFlow = uniqueField(pager, type -> type.getName().equals("kotlinx.coroutines.flow.MutableStateFlow"));
        ownerReference = uniqueField(vm, type -> type == WeakReference.class);
        panelField = uniqueField(vm, type -> type.getName().equals(vm.getPackage().getName() + ".KCmtListPanelViewModel"));
        sourceSession = uniqueField(sourceClass, type -> type == String.class);
        loadMore = pager.getDeclaredMethod("Uj"); hasMore = pager.getDeclaredMethod("hasMore");
        if (loadMore.getReturnType() != void.class || hasMore.getReturnType() != boolean.class)
            throw new NoSuchMethodException("KMP paging contract");
        value = Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader).getMethod("getValue");
        lifecycleOwner = Class.forName("androidx.lifecycle.LifecycleOwner", false, loader);
        lifecycle = lifecycleOwner.getMethod("getLifecycle");
        lifecycleState = Class.forName("androidx.lifecycle.Lifecycle", false, loader).getMethod("getCurrentState");
        more = response.getField("hasMore"); cursor = response.getField("cursor");
        if (more.getType() != boolean.class || cursor.getType() != long.class)
            throw new NoSuchFieldException("KMP response metadata");
        for (Method getter : vm.getDeclaredMethods()) if (!Modifier.isStatic(getter.getModifiers())
                && !getter.isBridge() && getter.getParameterCount() == 0 && getter.getReturnType() == sourceClass)
            sourceGetters.add(getter);
        if (sourceGetters.isEmpty()) throw new NoSuchMethodException("KMP source owner getter");
    }

    void install(Class<?> vm, List<XC_MethodHook.Unhook> hooks) throws ReflectiveOperationException {
        Class<?> pager = loadMore.getDeclaringClass();
        boolean foundConstructor = false;
        for (java.lang.reflect.Constructor<?> constructor : pager.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 2
                    || constructor.getParameterTypes()[1] != sourceField.getType()) continue;
            foundConstructor = true;
            hooks.add(XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !sourceClass.isInstance(param.args[1])) return;
                    synchronized (DouyinKmpPaging.this) {
                        entry(param.args[1]).pager = new WeakReference<>(param.thisObject);
                    }
                }
            }));
        }
        if (!foundConstructor) throw new NoSuchMethodException("KMP pager source constructor");
        for (Method getter : sourceGetters) hooks.add(XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object source = param.getResult();
                if (param.hasThrowable() || !sourceClass.isInstance(source)) return;
                synchronized (DouyinKmpPaging.this) {
                    Entry entry = entry(source);
                    if (entry.vm.get() != param.thisObject) entry.policy.cancel();
                    entry.vm = new WeakReference<>(param.thisObject);
                }
            }
        }));
        hooks.add(XposedBridge.hookMethod(loadMore, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    synchronized (DouyinKmpPaging.this) {
                        Entry entry = sources.get(sourceField.get(param.thisObject));
                        if (entry != null && !entry.invoking && idle(param.thisObject)) entry.policy.manualRequest();
                    }
                } catch (ReflectiveOperationException | RuntimeException error) { failed(error); }
            }
        }));
        hooks.add(XposedBridge.hookMethod(vm.getDeclaredMethod("onCleared"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                synchronized (DouyinKmpPaging.this) {
                    for (Entry entry : sources.values()) if (entry.vm.get() == param.thisObject) {
                        entry.policy.cancel(); entry.vm.clear();
                    }
                }
            }
        }));
    }
    void observeConfig() {
        config.addListener(() -> {
            synchronized (DouyinKmpPaging.this) { for (Entry entry : sources.values()) entry.policy.cancel(); }
        });
    }

    synchronized void page(Object source, Object kind, Object response, List<?> input, List<?> visible,
                           RuleEngine rules) {
        Entry entry = entry(source); entry.policy.cancel(); entry.rules = rules;
        try {
            entry.session = (String) sourceSession.get(source);
            Object vm = entry.vm.get();
            entry.capturedPanel = vm != null;
            entry.panelSession = vm == null ? null : panelState(vm).session;
            entry.policy.page(kind instanceof Enum<?> e ? e.name() : "", cursor.getLong(response), more.getBoolean(response),
                    input == null ? 0 : input.size(), visible == null ? 0 : visible.size(), rules.enabled());
            status.issue("pagination", null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(source, error); }
    }
    synchronized void complete(Object source) {
        Entry entry = sources.get(source);
        if (entry == null) return;
        long ticket = entry.policy.complete();
        if (entry.policy.current(ticket)) {
            WeakReference<Object> reference = new WeakReference<>(source);
            main.postDelayed(() -> continuePage(reference, ticket, 0), 80);
        }
    }
    private synchronized void continuePage(WeakReference<Object> reference, long ticket, int attempt) {
        Object source = reference.get(); Entry entry = sources.get(source);
        if (source == null || entry == null || !entry.policy.current(ticket)) return;
        try {
            Object pager = entry.pager.get(), vm = entry.vm.get();
            if (pager == null || vm == null) { skipped("ownerBound=" + (vm != null) + " pagerBound=" + (pager != null)); return; }
            if (entry.rules != config.snapshot() || !entry.rules.enabled()) { skipped("configurationChanged"); return; }
            WeakReference<?> ownerRef = (WeakReference<?>) ownerReference.get(vm);
            Object owner = ownerRef == null ? null : ownerRef.get();
            if (!lifecycleOwner.isInstance(owner)) { skipped("noUiLifecycle"); return; }
            String lifecycleName = enumName(lifecycleState.invoke(lifecycle.invoke(owner)));
            if (!"RESUMED".equals(lifecycleName)) { skipped("lifecycle=" + lifecycleName); return; }
            if (entry.session == null || !entry.session.equals(sourceSession.get(source))) { skipped("sessionChanged"); return; }
            PanelState panel = panelState(vm);
            if (!entry.capturedPanel || !panel.showing || !java.util.Objects.equals(entry.panelSession, panel.session)) {
                skipped("panelHiddenOrChanged"); return;
            }
            boolean current = false;
            for (Method getter : sourceGetters) if (getter.invoke(vm) == source) current = true;
            if (!current) { skipped("sourceChanged"); return; }
            if (!idle(pager)) {
                if (attempt < 20) main.postDelayed(() -> continuePage(reference, ticket, attempt + 1), 50);
                if (attempt == 20) skipped("pagerBusy"); return;
            }
            if (!Boolean.TRUE.equals(hasMore.invoke(pager)) || !ready(pager)) { skipped("notHasMore"); return; }
            if (entry.policy.notifyLimit(ticket)) {
                skipped("extraPageLimit=" + DouyinEmptyPagePolicy.MAX_EXTRA_PAGES); limitNotice.run(); return;
            }
            if (!entry.policy.claim(ticket)) return;
            entry.invoking = true;
            try {
                loadMore.invoke(pager);
                if (++automaticRequests <= 24) XposedBridge.log("[CommentFilter][DYPage] autoContinue=" + automaticRequests);
            } finally { entry.invoking = false; }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { entry.policy.cancel(); failed(error); }
    }
    private boolean idle(Object pager) throws ReflectiveOperationException {
        Object state = value.invoke(stateFlow.get(pager));
        Field request = uniqueField(state.getClass(), type -> type == requestKind);
        return "IDLE".equals(enumName(request.get(state)));
    }
    private boolean ready(Object pager) throws ReflectiveOperationException {
        Object state = value.invoke(stateFlow.get(pager));
        // Verified KListState footer field. Reject a changed layout instead of retrying ERROR.
        Field footer = state.getClass().getDeclaredField("LIZIZ");
        if (!footer.getType().isEnum()) throw new NoSuchFieldException("KMP footer state");
        footer.setAccessible(true);
        return "HAS_MORE".equals(enumName(footer.get(state)));
    }
    private static final class PanelState {
        final String session; final boolean showing;
        PanelState(String session, boolean showing) { this.session = session; this.showing = showing; }
    }
    private PanelState panelState(Object vm) throws ReflectiveOperationException {
        Object panel = panelField.get(vm), matched = null;
        Field visible = null, id = null;
        for (Field flow : panel.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(flow.getModifiers()) || !flow.getType().getName().equals("kotlinx.coroutines.flow.MutableStateFlow")) continue;
            flow.setAccessible(true);
            Object state = value.invoke(flow.get(panel));
            if (state == null) continue;
            int booleans = 0, strings = 0, count = 0;
            Field candidateVisible = null, candidateId = null;
            for (Field field : state.getClass().getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                count++;
                if (field.getType() == boolean.class) { booleans++; candidateVisible = field; }
                if (field.getType() == String.class) { strings++; candidateId = field; }
            }
            if (count != 5 || booleans != 1 || strings != 1) continue;
            if (matched != null) throw new NoSuchFieldException("Ambiguous KMP panel visibility state");
            matched = state; visible = candidateVisible; id = candidateId;
        }
        if (matched == null) throw new NoSuchFieldException("Missing KMP panel visibility state");
        visible.setAccessible(true); id.setAccessible(true);
        return new PanelState((String) id.get(matched), visible.getBoolean(matched));
    }
    private static String enumName(Object value) { return value instanceof Enum<?> e ? e.name() : ""; }
    private Entry entry(Object source) { return sources.computeIfAbsent(source, key -> new Entry()); }
    synchronized void fail(Object source, Throwable error) {
        Entry entry = sources.get(source);
        if (entry != null) entry.policy.cancel();
        failed(error);
    }
    private void failed(Throwable error) { status.issue("pagination", error); }
    private void skipped(String reason) {
        if (++skippedReports <= 24) XposedBridge.log("[CommentFilter][DYPage] continuationSkipped=" + reason);
    }
    private interface TypeMatch { boolean matches(Class<?> type); }
    private static Field uniqueField(Class<?> owner, TypeMatch match) throws NoSuchFieldException {
        Field found = null;
        for (Field field : owner.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()) && match.matches(field.getType())) {
            if (found != null) throw new NoSuchFieldException("Ambiguous " + owner.getName() + " contract");
            found = field;
        }
        if (found == null) throw new NoSuchFieldException("Missing " + owner.getName() + " contract");
        found.setAccessible(true); return found;
    }
}
