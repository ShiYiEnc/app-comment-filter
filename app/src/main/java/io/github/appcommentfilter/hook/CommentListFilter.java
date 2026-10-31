package io.github.appcommentfilter.hook;

import java.util.ArrayList;
import java.util.List;
import io.github.appcommentfilter.core.Comment;
import io.github.appcommentfilter.core.RuleEngine;

/** Copy only after the first hidden item. Never mutate the host's input. */
public final class CommentListFilter {
    private final CommentSnapshotReader reader;
    private final FilterDiagnostics diagnostics;
    public CommentListFilter(CommentSnapshotReader reader) { this(reader, FilterDiagnostics.NONE); }
    public CommentListFilter(CommentSnapshotReader reader, FilterDiagnostics diagnostics) {
        this.reader = reader; this.diagnostics = diagnostics;
    }

    public int mainCommentCount(List<?> items) {
        int count = 0;
        for (Object item : items) {
            try { if (reader.isMainComment(item)) count++; }
            catch (ReflectiveOperationException | RuntimeException ignored) { /* Preserve unknown shapes. */ }
        }
        return count;
    }

    public List<?> filter(List<?> original, RuleEngine rules) {
        if (!rules.enabled()) return original;
        List<Object> result = null;
        int read = 0, removed = 0; Throwable failure = null;
        for (int i = 0; i < original.size(); i++) {
            Object item = original.get(i);
            boolean hide = false;
            try {
                Comment snapshot = reader.read(item);
                hide = snapshot != null && rules.evaluate(snapshot).hidden();
                if (snapshot != null) read++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                failure = error;
                // A shape failure is not an unknown attribute: retain the whole item.
            }
            if (hide && result == null) result = new ArrayList<>(original.subList(0, i));
            if (!hide && result != null) result.add(item);
            if (hide) removed++;
        }
        diagnostics.onBatch(read, removed, failure);
        return result == null ? original : result;
    }
}
