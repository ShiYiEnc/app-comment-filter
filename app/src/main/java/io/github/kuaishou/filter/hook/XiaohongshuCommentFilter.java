package io.github.kuaishou.filter.hook;

import io.github.kuaishou.filter.core.Comment;
import io.github.kuaishou.filter.core.Gender;
import io.github.kuaishou.filter.core.RuleEngine;
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

    public XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                                    Class<?> info) throws ReflectiveOperationException {
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
        for (Object row : original) {
            if (!rootRow.isInstance(row)) continue;
            try {
                Object info = model.invoke(row);
                if (info == null) continue;
                // CommentCommentUser in this APK does not declare a gender field.
                Comment snapshot = new Comment((String) content.get(info), (String) location.get(info), Gender.UNKNOWN, false);
                if (rules.evaluate(snapshot).hidden()) {
                    hidden.add(row);
                    String key = (String) id.get(info);
                    if (key != null && !key.isEmpty()) hiddenIds.add(key);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) { /* Preserve unreadable rows. */ }
        }
        if (hidden.isEmpty()) return original;
        List<Object> result = new ArrayList<>(original.size());
        for (Object row : original) {
            if (hidden.contains(row)) continue;
            boolean attachedToHidden = false;
            if (commentRow.isInstance(row) && !rootRow.isInstance(row)) {
                try {
                    Object info = model.invoke(row);
                    String owner = info == null ? null : (String) rootId.get(info);
                    if ((owner == null || owner.isEmpty()) && childRow.isInstance(row)) owner = (String) childRootId.get(row);
                    attachedToHidden = owner != null && hiddenIds.contains(owner);
                } catch (ReflectiveOperationException | RuntimeException ignored) { /* Preserve unknown ownership. */ }
            }
            if (!attachedToHidden) result.add(row);
        }
        return result;
    }
}
