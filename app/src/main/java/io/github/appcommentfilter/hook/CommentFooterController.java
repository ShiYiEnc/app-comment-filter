package io.github.appcommentfilter.hook;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Uses the host's real comment fragment and existing footer group; no window or overlay. */
final class CommentFooterController {
    private final Class<?> comments, wrapper;
    private final Method listGetter;
    private final Method getView, getActivity, getParams, getPager, getResponse, getAdapter, computing;
    private final Method addFooter, removeFooter, containsFooter;
    private final Field photo, ownerPhoto, footerAdapter;
    private final Method photoId, ownerPhotoId;
    private final Map<Object, WeakReference<View>> lists = new WeakHashMap<>();
    private final Map<Footer, Boolean> footers = new WeakHashMap<>();
    private final Runnable onReady;
    private final java.util.List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final java.util.List<Method> stops = new ArrayList<>(), starts = new ArrayList<>();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Bind everything before installing any hooks; a footer failure is independently recoverable. */
    CommentFooterController(ClassLoader loader, Class<?> pageList, File[] apks, Runnable onReady)
            throws ReflectiveOperationException, IOException {
        this.onReady = onReady;
        comments = Class.forName("com.yxcorp.gifshow.comment.common.fragment.AbsCommentsFragment", false, loader);
        Class<?> base = Class.forName("com.yxcorp.gifshow.recycler.fragment.RecyclerFragment", false, loader);
        Class<?> adapterType = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, loader);
        wrapper = KuaishouFooterBindings.wrapper(base, adapterType, View.class);
        listGetter = base.getMethod("j0");
        getAdapter = listGetter.getReturnType().getMethod("getAdapter");
        computing = listGetter.getReturnType().getMethod("isComputingLayout");
        getView = comments.getMethod("getView"); getActivity = comments.getMethod("getActivity");
        Class<?> params = Class.forName("com.yxcorp.gifshow.comment.common.model.CommentParams", false, loader);
        getParams = KuaishouFooterBindings.noArgReturn(comments, params, "comment params getter");
        photo = getParams.getReturnType().getField("mQPhoto");
        photoId = photo.getType().getMethod("getKId");
        getPager = base.getMethod("n");
        getResponse = KuaishouFooterBindings.noArgReturn(getPager.getReturnType(), Object.class, "pager latest response getter");
        ownerPhoto = pageList.getDeclaredField("b"); ownerPhoto.setAccessible(true);
        ownerPhotoId = ownerPhoto.getType().getMethod("getKId");
        footerAdapter = wrapper.getDeclaredField("h"); footerAdapter.setAccessible(true);
        String wrapperOwner = KuaishouFooterBindings.descriptor(wrapper);
        KuaishouFooterBindings.AddFooter symbol = KuaishouFooterBindings.addFooter(KuaishouBindings.index(apks, wrapperOwner),
                wrapperOwner, KuaishouFooterBindings.descriptor(View.class), KuaishouFooterBindings.descriptor(ViewGroup.LayoutParams.class));
        Field group = wrapper.getDeclaredField(symbol.group);
        if (!KuaishouFooterBindings.descriptor(group.getType()).equals(symbol.groupDescriptor)
                || ownerPhotoId.getReturnType() != String.class || photoId.getReturnType() != String.class)
            throw new NoSuchMethodException("Footer photo/group contract changed");
        addFooter = wrapper.getMethod(symbol.method, View.class); removeFooter = wrapper.getMethod("E2", View.class);
        containsFooter = wrapper.getMethod("l2", View.class);
        if (addFooter.getReturnType() != void.class) throw new NoSuchMethodException("Footer add contract changed");
        for (String name : new String[]{"onPause", "onPageUnSelect", "onDestroyView"}) stops.add(base.getDeclaredMethod(name));
        for (String name : new String[]{"onResume", "onPageSelect"}) starts.add(base.getDeclaredMethod(name));
    }

    void install() {
        if (!hooks.isEmpty()) return;
        try {
            hooks.add(XposedBridge.hookMethod(listGetter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!comments.isInstance(param.thisObject) || !(param.getResult() instanceof View)) return;
                    View list = (View) param.getResult();
                    synchronized (lists) {
                        WeakReference<View> previous = lists.put(param.thisObject, new WeakReference<>(list));
                        if (previous == null || previous.get() != list) list.post(onReady);
                    }
                }
            }));
            // mq changed return type in 14.9.30. The list getter and stable Fragment lifecycle
            // already provide readiness; no layout-phase or version-specific mq hook is needed.
            for (Method method : stops) {
                hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (comments.isInstance(param.thisObject)) unavailable(param.thisObject);
                        if ("onDestroyView".equals(method.getName())) synchronized (lists) { lists.remove(param.thisObject); }
                    }
                }));
            }
            for (Method method : starts) {
                hooks.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (comments.isInstance(param.thisObject)) main.post(onReady);
                    }
                }));
            }
        } catch (RuntimeException | LinkageError failure) { uninstall(); throw failure; }
    }

    void uninstall() {
        for (XC_MethodHook.Unhook hook : hooks) {
            try { hook.unhook(); } catch (RuntimeException | LinkageError ignored) { }
        }
        hooks.clear();
        synchronized (lists) { lists.clear(); }
        java.util.List<Footer> active;
        synchronized (footers) { active = new ArrayList<>(footers.keySet()); }
        Runnable cleanup = () -> { for (Footer footer : active) if (footer != null) footer.remove(); };
        if (android.os.Looper.myLooper() == main.getLooper()) cleanup.run(); else main.post(cleanup);
    }

    Footer show(Activity activity, Object owner, Object response, Runnable action, Runnable hidden)
            throws ReflectiveOperationException {
        Object match = null; View listMatch = null;
        Object fallback = null; View fallbackList = null; int candidates = 0;
        String expectedId = (String) ownerPhotoId.invoke(ownerPhoto.get(owner));
        synchronized (lists) {
            for (Map.Entry<Object, WeakReference<View>> entry : lists.entrySet()) {
                Object fragment = entry.getKey(); View list = entry.getValue().get();
                View root = (View) getView.invoke(fragment);
                if (list == null || root == null || !root.isShown() || !list.isAttachedToWindow()
                        || !root.getGlobalVisibleRect(new Rect()) || getActivity.invoke(fragment) != activity) continue;
                Object params = getParams.invoke(fragment);
                Object targetPhoto = params == null ? null : photo.get(params);
                if (targetPhoto == null || expectedId == null || !expectedId.equals(photoId.invoke(targetPhoto))) continue;
                Object nativePager = getPager.invoke(fragment);
                Object nativeResponse = nativePager == null ? null : getResponse.invoke(nativePager);
                if (response != null && nativeResponse != null && response != nativeResponse) continue;
                if (response != null && response == nativeResponse) {
                    if (match != null) return null; // Never attach to an ambiguous main/reply panel.
                    match = fragment; listMatch = list;
                }
                candidates++; fallback = fragment; fallbackList = list;
            }
        }
        if (match == null && candidates == 1) { match = fallback; listMatch = fallbackList; }
        if (match == null) return null;
        Object adapter = getAdapter.invoke(listMatch);
        if (!wrapper.isInstance(adapter) || footerAdapter.get(adapter) != null) return null;
        if (Boolean.TRUE.equals(computing.invoke(listMatch))) return null;
        Footer footer = new Footer(match, listMatch, adapter, action, hidden);
        synchronized (footers) { footers.put(footer, true); }
        try { addFooter.invoke(adapter, footer.row); }
        catch (ReflectiveOperationException | RuntimeException e) { footer.remove(); throw e; }
        return footer;
    }

    private void unavailable(Object fragment) {
        java.util.List<Footer> active;
        synchronized (footers) { active = new ArrayList<>(footers.keySet()); }
        for (Footer footer : active) {
            if (footer != null && footer.fragment.get() == fragment) footer.hidden.run();
        }
    }

    final class Footer {
        final WeakReference<Object> fragment, adapter;
        final WeakReference<View> list, root;
        final LinearLayout row;
        final Button button;
        final Runnable hidden;
        private final ViewTreeObserver.OnGlobalLayoutListener visibility;
        private Runnable action;
        private boolean detached;

        Footer(Object fragment, View list, Object adapter, Runnable action, Runnable hidden) throws ReflectiveOperationException {
            this.fragment = new WeakReference<>(fragment); this.adapter = new WeakReference<>(adapter);
            this.list = new WeakReference<>(list); this.root = new WeakReference<>((View) getView.invoke(fragment));
            this.hidden = hidden; this.action = action;
            row = new LinearLayout(list.getContext()); row.setGravity(Gravity.CENTER);
            int padding = Math.round(12 * list.getResources().getDisplayMetrics().density);
            row.setPadding(padding, padding / 2, padding, padding);
            row.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            button = new Button(list.getContext()); button.setText("继续加载评论"); button.setAllCaps(false);
            button.setOnClickListener(v -> { if (!detached && button.isEnabled()) { loading(); this.action.run(); } });
            row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            visibility = () -> {
                View panel = this.root.get();
                if (!detached && (panel == null || !panel.isShown() || !panel.getGlobalVisibleRect(new Rect()))) hidden.run();
            };
            View panel = root.get();
            if (panel != null) panel.getViewTreeObserver().addOnGlobalLayoutListener(visibility);
        }

        void loading() { button.setEnabled(false); button.setText("加载中…"); }

        boolean current() throws ReflectiveOperationException {
            View recycler = list.get(); View panel = root.get();
            return !detached && recycler != null && panel != null && panel.isShown()
                    && recycler.isAttachedToWindow() && getAdapter.invoke(recycler) == adapter.get()
                    && footerAdapter.get(adapter.get()) == null && Boolean.TRUE.equals(containsFooter.invoke(adapter.get(), row));
        }

        void ready(Runnable action) { this.action = action; button.setText("继续加载评论"); button.setEnabled(true); }

        void remove() {
            if (detached) return;
            detached = true; action = null; button.setEnabled(false); button.setOnClickListener(null); row.setVisibility(View.GONE);
            View panel = root.get();
            if (panel != null && panel.getViewTreeObserver().isAlive()) panel.getViewTreeObserver().removeOnGlobalLayoutListener(visibility);
            synchronized (footers) { footers.remove(this); }
            removeFromAdapter(0);
        }

        private void removeFromAdapter(int attempt) {
            Object hostAdapter = adapter.get(); View recycler = list.get();
            if (hostAdapter == null) return;
            try {
                if (recycler != null && Boolean.TRUE.equals(computing.invoke(recycler)) && attempt < 20) {
                    recycler.postDelayed(() -> removeFromAdapter(attempt + 1), 16); return;
                }
                removeFooter.invoke(hostAdapter, row);
            } catch (ReflectiveOperationException | RuntimeException e) {
                XposedBridge.log("[KSFilter] Footer cleanup unavailable (" + e.getClass().getSimpleName() + ")");
            }
        }
    }
}
