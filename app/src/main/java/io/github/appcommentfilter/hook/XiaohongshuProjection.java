package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.RuleEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.IdentityHashMap;

/** Keeps display projections out of the host's verified delete/rebuild operation. */
final class XiaohongshuProjection {
    private final XiaohongshuCommentFilter filter;
    private final Map<Object, List<?>> originals = new WeakHashMap<>();
    private final Map<Object, Boolean> projected = new WeakHashMap<>();
    private final ThreadLocal<Object> mutation = new ThreadLocal<>();

    XiaohongshuProjection(XiaohongshuCommentFilter filter) { this.filter = filter; }

    synchronized List<?> project(Object adapter, List<?> original, RuleEngine rules) {
        List<?> displayed = filter.filter(original, rules);
        originals.put(adapter, new ArrayList<>(original));
        if (displayed != original) projected.put(adapter, true);
        return displayed;
    }

    synchronized boolean requiresFullRefresh(Object adapter) { return projected.containsKey(adapter); }

    synchronized Map<Object, List<?>> sources() { return new IdentityHashMap<>(originals); }

    synchronized boolean isCurrent(Object adapter, List<?> source) { return originals.get(adapter) == source; }

    Object beginMutation(Object adapter) {
        Object previous = mutation.get();
        if (adapter == null) mutation.remove(); else mutation.set(adapter);
        return previous;
    }

    void endMutation(Object previous) {
        if (previous == null) mutation.remove(); else mutation.set(previous);
    }

    synchronized List<?> backingForMutation(Object adapter) {
        return mutation.get() == adapter && projected.containsKey(adapter) ? originals.get(adapter) : null;
    }
}
