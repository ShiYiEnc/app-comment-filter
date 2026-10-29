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
    public interface GenderLookup { GenderState lookup(String userId); }
    public static final class GenderState {
        public final boolean pending;
        public final Gender gender;
        public GenderState(boolean pending, Gender gender) {
            this.pending = pending;
            this.gender = gender == null ? Gender.UNKNOWN : gender;
        }
    }
    private final Class<?> rootRow, commentRow, childRow;
    private final Method model;
    private final Field id, content, location, rootId, childRootId, author, authorId;
    private final GenderLookup genders;

    public XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                                    Class<?> info) throws ReflectiveOperationException {
        this(rootRow, commentRow, childRow, info, null);
    }

    public XiaohongshuCommentFilter(Class<?> rootRow, Class<?> commentRow, Class<?> childRow,
                                    Class<?> info, GenderLookup genders) throws ReflectiveOperationException {
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
        this.genders = genders;
        if (genders != null) {
            author = info.getDeclaredField("user"); author.setAccessible(true);
            authorId = stringField(author.getType(), "userid");
        } else { author = null; authorId = null; }
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
                Comment snapshot = new Comment((String) content.get(info), (String) location.get(info), Gender.UNKNOWN, false);
                io.github.kuaishou.filter.core.Decision decision;
                if (!rules.resolveProfileGender()) {
                    decision = rules.evaluate(snapshot, false);
                } else if (!rules.usesGenderRules() || genders == null) {
                    decision = rules.evaluate(snapshot);
                } else {
                    decision = rules.evaluatePendingGender(snapshot);
                    // Independent keyword/location blacklist matches need no profile lookup.
                    if (!decision.hidden()) {
                        Object user = author.get(info);
                        String userId = user == null ? null : (String) authorId.get(user);
                        GenderState state = genders.lookup(userId);
                        if (state == null) throw new IllegalStateException("Missing profile result");
                        if (!state.pending) decision = rules.evaluate(new Comment(snapshot.text(), snapshot.location(), state.gender, false));
                    }
                }
                if (decision.hidden()) {
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
