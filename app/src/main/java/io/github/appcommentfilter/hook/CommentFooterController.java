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
    private final Method getView, getActivity, getParams, getPager, getResponse, getAdapter, computing;
    private final Method addFooter, removeFooter, containsFooter;
    private final Field photo, ownerPhoto, footerAdapter;
    private final Method photoId, ownerPhotoId;
    private final Map<Object, WeakReference<View>> lists = new WeakHashMap<>();
    private final Map<Footer, Boolean> footers = new WeakHashMap<>();
    private final Runnable onReady;

    CommentFooterController(ClassLoader loader, Class<?> pageList, Runnable onReady) throws ReflectiveOperationException {
        this.onReady = onReady;
        comments = Class.forName("com.yxcorp.gifshow.comment.common.fragment.AbsCommentsFragment", false, loader);
        Class<?> base = Class.forName("com.yxcorp.gifshow.recycler.fragment.RecyclerFragment", false, loader);
        wrapper = Class.forName("pcl.f", false, loader);
        Method listGetter = base.getMethod("j0");
        getAdapter = listGetter.getReturnType().getMethod("getAdapter");
        computing = listGetter.getReturnType().getMethod("isComputingLayout");
        getView = comments.getMethod("getView"); getActivity = comments.getMethod("getActivity");
        getParams = comments.getMethod("Tq"); photo = getParams.getReturnType().getField("mQPhoto");
        photoId = photo.getType().getMethod("getKId");
        getPager = base.getMethod("n"); getResponse = getPager.getReturnType().getMethod("j0");
        ownerPhoto = pageList.getDeclaredField("b"); ownerPhoto.setAccessible(true);
        ownerPhotoId = ownerPhoto.getType().getMethod("getKId");
        footerAdapter = wrapper.getDeclaredField("h"); footerAdapter.setAccessible(true);
        addFooter = wrapper.getMethod("Q1", View.class); removeFooter = wrapper.getMethod("E2", View.class);
        containsFooter = wrapper.getMethod("l2", View.class);

        XposedBridge.hookMethod(listGetter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!comments.isInstance(param.thisObject) || !(param.getResult() instanceof View)) return;
                View list = (View) param.getResult();
                synchronized (lists) {
                    WeakReference<View> previous = lists.put(param.thisObject, new WeakReference<>(list));
                    if (previous == null || previous.get() != list) list.post(onReady);
                }
            }
        });
        XposedBridge.hookMethod(base.getDeclaredMethod("mq"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (comments.isInstance(param.thisObject)) new android.os.Handler(android.os.Looper.getMainLooper()).post(onReady);
            }
        });
        for (String method : new String[]{"onPause", "onPageUnSelect", "onDestroyView"}) {
            XposedBridge.hookMethod(base.getDeclaredMethod(method), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (comments.isInstance(param.thisObject)) unavailable(param.thisObject);
                    if ("onDestroyView".equals(method)) synchronized (lists) { lists.remove(param.thisObject); }
                }
            });
        }
        for (String method : new String[]{"onResume", "onPageSelect"}) {
            XposedBridge.hookMethod(base.getDeclaredMethod(method), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (comments.isInstance(param.thisObject)) new android.os.Handler(android.os.Looper.getMainLooper()).post(onReady);
                }
            });
        }
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
        footers.put(footer, true);
        try { addFooter.invoke(adapter, footer.row); }
        catch (ReflectiveOperationException | RuntimeException e) { footer.remove(); throw e; }
        return footer;
    }

    private void unavailable(Object fragment) {
        for (Footer footer : new ArrayList<>(footers.keySet())) {
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
            footers.remove(this);
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
