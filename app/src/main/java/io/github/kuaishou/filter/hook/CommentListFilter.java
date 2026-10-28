package io.github.kuaishou.filter.hook;

import java.util.ArrayList;
import java.util.List;
import io.github.kuaishou.filter.core.Comment;
import io.github.kuaishou.filter.core.RuleEngine;

/** Copy only after the first hidden item. Never mutate the host's input. */
public final class CommentListFilter {
    private final CommentSnapshotReader reader;
    public CommentListFilter(CommentSnapshotReader reader) { this.reader = reader; }

    public int mainCommentCount(List<?> items) {
        int count = 0;
        for (Object item : items) {
            try { if (reader.isMainComment(item)) count++; }
            catch (ReflectiveOperationException | RuntimeException ignored) { /* Preserve unknown shapes. */ }
        }
        return count;
    }

    public List<?> filter(List<?> original, RuleEngine rules) {
        List<Object> result = null;
        for (int i = 0; i < original.size(); i++) {
            Object item = original.get(i);
            boolean hide = false;
            try {
                Comment snapshot = reader.read(item);
                hide = snapshot != null && rules.evaluate(snapshot).hidden();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // A shape failure is not an unknown attribute: retain the whole item.
            }
            if (hide && result == null) result = new ArrayList<>(original.subList(0, i));
            if (!hide && result != null) result.add(item);
        }
        return result == null ? original : result;
    }
}
