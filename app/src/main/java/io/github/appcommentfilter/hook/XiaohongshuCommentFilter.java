package io.github.appcommentfilter.hook;

import io.github.appcommentfilter.core.Comment;
import io.github.appcommentfilter.core.Gender;
import io.github.appcommentfilter.core.RuleEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Projection of verified comment row interfaces; original rows/models remain unchanged. */
public final class XiaohongshuCommentFilter {
    private final Class<?> rootRow, commentRow, childRow;
    private final Method model;
    private final Field id, content, location, rootId, childRootId;
    private final FilterDiagnostics diagnostics;
    private final XiaohongshuRowRelations relations;

    public XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                                    Class<?> info) throws ReflectiveOperationException {
        this(rootRow, commentRow, childRow, info, FilterDiagnostics.NONE);
    }

    public XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                                    Class<?> info, FilterDiagnostics diagnostics) throws ReflectiveOperationException {
        this(rootRow, commentRow, childRow, info, diagnostics, null);
    }

    XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                            Class<?> info, FilterDiagnostics diagnostics,
                            XiaohongshuRowRelations relations) throws ReflectiveOperationException {
        this.diagnostics = diagnostics;
        this.relations = relations;
        this.rootRow = rootRow;
        this.commentRow = commentRow;
        this.childRow = childRow;
        model = commentRow.getMethod("e");
        if (model.getReturnType() != info || !commentRow.isAssignableFrom(rootRow)
                || !commentRow.isAssignableFrom(childRow)) throw new NoSuchMethodException("Unexpected row interface");
        id = stringField(info, "id");
        content = stringField(info, "content");
        location = stringField(info, "ipLocation");
        rootId = stringField(info, "rootCommentId");
        childRootId = stringField(childRow, "e");

    }

    private static Field stringField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (field.getType() != String.class) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }

    public List<?> filter(List<?> original, RuleEngine rules) {
        if (!rules.enabled()) return original;
        Set<Object> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> hiddenIds = new HashSet<>();
        int read = 0; Throwable failure = null;
        for (Object row : original) {
            try {
                Object comment = relations == null ? row : relations.comment(row);
                if (!rootRow.isInstance(comment)) continue;
                Object info = model.invoke(comment);
                if (info == null) continue;
                Comment snapshot = new Comment((String) content.get(info), (String) location.get(info), Gender.UNKNOWN, false);
                // XHS exposes no comment gender. Legacy gender conditions stay stored but are paused.
                io.github.appcommentfilter.core.Decision decision = rules.evaluate(snapshot, false);
                read++;
                if (decision.hidden()) {
                    hidden.add(row);
                    String key = (String) id.get(info);
                    if (key != null && !key.isEmpty()) hiddenIds.add(key);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { failure = error; }
        }
        if (hidden.isEmpty()) { diagnostics.onBatch(read, 0, failure); return original; }
        List<Object> result = new ArrayList<>(original.size());
        for (Object row : original) {
            if (hidden.contains(row)) continue;
            boolean attachedToHidden = false;
            try {
                String controlOwner = relations == null ? null : relations.replyControlOwner(row);
                attachedToHidden = controlOwner != null && hiddenIds.contains(controlOwner);
                Object comment = relations == null ? row : relations.comment(row);
                if (commentRow.isInstance(comment) && !rootRow.isInstance(comment)) {
                    Object info = model.invoke(comment);
                    String owner = info == null ? null : (String) rootId.get(info);
                    if ((owner == null || owner.isEmpty()) && childRow.isInstance(comment)) owner = (String) childRootId.get(comment);
                    attachedToHidden = owner != null && hiddenIds.contains(owner);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { failure = error; }
            if (!attachedToHidden) result.add(row);
        }
        diagnostics.onBatch(read, original.size() - result.size(), failure);
        return result;
    }
}
