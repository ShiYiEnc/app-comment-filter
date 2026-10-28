package io.github.kuaishou.filter.hook;

import io.github.kuaishou.filter.core.RuleEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps display projections out of the host's verified delete/rebuild operation. */
final class XiaohongshuProjection {
    private final XiaohongshuCommentFilter filter;
    private final Map<Object, List<?>> originals = new WeakHashMap<>();
    private final ThreadLocal<Object> mutation = new ThreadLocal<>();

    XiaohongshuProjection(XiaohongshuCommentFilter filter) { this.filter = filter; }

    synchronized List<?> project(Object adapter, List<?> original, RuleEngine rules) {
        List<?> displayed = filter.filter(original, rules);
        if (displayed != original || originals.containsKey(adapter)) {
            originals.put(adapter, new ArrayList<>(original));
        }
        return displayed;
    }

    synchronized boolean requiresFullRefresh(Object adapter) { return originals.containsKey(adapter); }

    Object beginMutation(Object adapter) {
        Object previous = mutation.get();
        if (adapter == null) mutation.remove(); else mutation.set(adapter);
        return previous;
    }

    void endMutation(Object previous) {
        if (previous == null) mutation.remove(); else mutation.set(previous);
    }

    synchronized List<?> backingForMutation(Object adapter) {
        return mutation.get() == adapter ? originals.get(adapter) : null;
    }
}
